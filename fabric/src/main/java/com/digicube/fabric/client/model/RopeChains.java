package com.digicube.fabric.client.model;

import com.digicube.fabric.client.render.DigimonRenderState;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.GsonHelper;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A hanging chain on a native model, simulated on the client after the pose is applied ({@code ropes} in
 * {@code ground_models.json}): its links are siblings under one frame part, and each is placed on a point of a verlet
 * rope in the world, so the chain lags behind the arm it hangs from, swings as the body runs, turns and stops, and
 * settles under its own weight. The first link is pinned where the model rests it; each link keeps its rest distance
 * to the next; boxes on the body (model px, in a part's own frame, grown by {@code radius}) push the links out of the
 * barrel and the legs. Any clip's keys on the links are overwritten, so the clips need not be touched.
 */
public final class RopeChains {
    /** A chain: {@code path} leads from the root to the frame part the {@code links} hang in. */
    public record Rope(List<String> path, List<String> links, float radius, List<Box> colliders) {}
    /**
     * A box a link may not enter: {@code min}/{@code max} in model px, in the frame of the part at {@code path}, grown by
     * {@code radius} (the rope's, unless the box sets its own: a hand right beside the anchor gets a thin one, or its
     * margin sweeps into the chain's top links on every stride and snaps them round the corner).
     */
    public record Box(List<String> path, Vector3f min, Vector3f max, float radius) {}

    /** Per-entity simulation state, kept by the renderer for as long as the entity is drawn. */
    public static final class State {
        private Vector3f[][] points, previous;
        private float lastAge = Float.NaN;
    }

    /**
     * Minecraft's own gravity in blocks a tick squared, the share of its swing about the anchor a link keeps per tick (a
     * heavy iron chain swings on once and settles; the bob of a galloping arm only rocks it), and air drag per block of
     * speed: at a full gallop, half a block a tick, the chain streams back about 20 degrees.
     */
    private static final float GRAVITY = .08F, KEEP = .75F, DRAG = .12F, STEP = .25F;
    /**
     * A heavy chain's own friction: the share of each link's speed relative to its neighbours' (a bend travelling down
     * the chain) that its links rub away per tick. It leaves the whole chain's swing alone and stops the end from
     * whipping round faster than the rest. {@code BEND} is the most one link turns from the last, degrees.
     */
    private static final float INTERNAL = .95F, BEND = 30;
    private static final int ITERATIONS = 6;
    /** A jump of the anchor further than this (a teleport, a respawn) drops the rope where the model rests it. */
    private static final float SNAP = 2F;

    private RopeChains() {}

    public static void apply(ModelPart root, DigimonRenderState state, List<Rope> ropes, State s) {
        if (ropes.isEmpty() || s == null) return;
        if (s.points == null) { s.points = new Vector3f[ropes.size()][]; s.previous = new Vector3f[ropes.size()][]; }
        float dt = Float.isNaN(s.lastAge) ? 0 : Math.clamp(state.ageInTicks - s.lastAge, 0, 2);
        boolean advance = dt > 1.0E-4F || Float.isNaN(s.lastAge);
        float yaw = (float) Math.toRadians(state.bodyRot);
        for (int r = 0; r < ropes.size(); r++) {
            var rope = ropes.get(r);
            ModelPart frame = part(root, rope.path());
            Matrix4f frameModel = transform(root, rope.path()).pose();   // frame px/16 -> model blocks
            int n = rope.links().size();
            // Rest points: where the model puts each link, in the world.
            Vector3f[] rest = new Vector3f[n];
            for (int i = 0; i < n; i++) {
                PartPose pose = frame.getChild(rope.links().get(i)).getInitialPose();
                rest[i] = toWorld(frameModel.transformPosition(new Vector3f(pose.x() / 16, pose.y() / 16, pose.z() / 16)), state, yaw);
            }
            Vector3f[] p = s.points[r];
            if (p == null || p[0].distance(rest[0]) > SNAP) {
                p = s.points[r] = new Vector3f[n]; s.previous[r] = new Vector3f[n];
                for (int i = 0; i < n; i++) { p[i] = new Vector3f(rest[i]); s.previous[r][i] = new Vector3f(rest[i]); }
            }
            Vector3f[] q = s.previous[r];
            float[] lengths = new float[n - 1];
            for (int i = 0; i < n - 1; i++) lengths[i] = rest[i].distance(rest[i + 1]);
            if (advance && dt > 0) {
                // The pin moves with the arm over the frame; the free links follow under gravity and their constraints.
                Vector3f pinFrom = new Vector3f(p[0]);
                float remaining = dt, done = 0;
                var boxes = boxes(root, rope, state, yaw);
                while (remaining > 1.0E-5F) {
                    float h = Math.min(STEP, remaining); remaining -= h; done += h;
                    float keep = (float) Math.pow(KEEP, h), rub = 1 - (float) Math.pow(1 - INTERNAL, h);
                    // Damping slows the swing about the anchor, not the flight through the world (that is the air's).
                    Vector3f carried = new Vector3f(rest[0]).sub(pinFrom).mul(h / dt);
                    Vector3f[] v = new Vector3f[n];
                    v[0] = carried;
                    for (int i = 1; i < n; i++) v[i] = new Vector3f(p[i]).sub(q[i]).sub(carried).mul(keep).add(carried);
                    // Friction between links eases each toward its neighbours' mean; the end toward the line through the
                    // two above it, so a chain swinging whole (speeds growing down its length) keeps its swing.
                    Vector3f[] eased = new Vector3f[n];
                    for (int i = 1; i < n; i++) {
                        Vector3f toward = i < n - 1 ? new Vector3f(v[i - 1]).add(v[i + 1]).mul(.5F) : new Vector3f(v[i - 1]).mul(2).sub(v[i - 2]);
                        eased[i] = new Vector3f(v[i]).lerp(toward, rub);
                    }
                    for (int i = 1; i < n; i++) {
                        Vector3f step = eased[i];
                        // Drag -DRAG |u| u on the speed u = v / h, over h ticks: -DRAG |v| v, whatever the step.
                        Vector3f drag = new Vector3f(step).mul(-DRAG * step.length());
                        q[i].set(p[i]);
                        p[i].add(step).add(drag).add(0, -GRAVITY * h * h, 0);
                    }
                    p[0].set(pinFrom).lerp(rest[0], done / dt); q[0].set(p[0]);
                    for (int k = 0; k < ITERATIONS; k++) {
                        for (int i = 0; i < n - 1; i++) {
                            Vector3f d = new Vector3f(p[i + 1]).sub(p[i]);
                            float length = d.length();
                            if (length < 1.0E-6F) continue;
                            float error = (length - lengths[i]) / length;
                            if (i == 0) p[1].sub(d.mul(error));
                            else { d.mul(error * .5F); p[i].add(d); p[i + 1].sub(d); }
                        }
                        // Links bend only so far at each joint: two apart, they stay at least the chord of that bend apart.
                        for (int i = 0; i < n - 2; i++) {
                            float least = (lengths[i] + lengths[i + 1]) * (float) Math.cos(Math.toRadians(BEND) / 2);
                            Vector3f d = new Vector3f(p[i + 2]).sub(p[i]);
                            float length = d.length();
                            if (length >= least || length < 1.0E-6F) continue;
                            d.mul((least - length) / length);
                            if (i == 0) p[2].add(d);
                            else { d.mul(.5F); p[i].sub(d); p[i + 2].add(d); }
                        }
                        for (int i = 1; i < n; i++) for (var box : boxes) box.pushOut(p[i], q[i]);
                    }
                    // A chain does not stretch: from the pin down, each link is set its rest distance from the one above.
                    for (int i = 1; i < n; i++) {
                        Vector3f d = new Vector3f(p[i]).sub(p[i - 1]);
                        if (d.lengthSquared() > 1.0E-12F) p[i].set(d.normalize(lengths[i - 1]).add(p[i - 1]));
                    }
                }
                p[0].set(rest[0]);
            }
            // Pose each link on its point, its length along the rope, keeping its rest twist about that length. The side
            // axis is carried down the chain from the frame's own (turned by the least rotation from one link's length
            // to the next), so no link spins about itself as the chain swings through any direction.
            Matrix4f modelFrame = new Matrix4f(frameModel).invert();
            Vector3f[] local = new Vector3f[n];
            for (int i = 0; i < n; i++) local[i] = modelFrame.transformPosition(toModel(p[i], state, yaw)).mul(16);
            Vector3f side = new Vector3f(1, 0, 0), lastAlong = new Vector3f(0, 1, 0);
            for (int i = 0; i < n; i++) {
                ModelPart link = frame.getChild(rope.links().get(i));
                PartPose pose = link.getInitialPose();
                Vector3f along = i < n - 1 ? new Vector3f(local[i + 1]).sub(local[i]) : new Vector3f(local[i]).sub(local[i - 1]);
                if (i > 0 && i < n - 1) along.add(new Vector3f(local[i]).sub(local[i - 1]));
                if (along.lengthSquared() < 1.0E-8F) along.set(0, 1, 0);
                along.normalize();
                side.rotate(new org.joml.Quaternionf().rotationTo(lastAlong, along));
                side.sub(new Vector3f(along).mul(side.dot(along)));
                if (side.lengthSquared() < 1.0E-6F) side.set(1, 0, 0).sub(new Vector3f(along).mul(along.x));
                side.normalize();
                lastAlong.set(along);
                Vector3f depth = new Vector3f(side).cross(along);
                Matrix3f basis = new Matrix3f().setColumn(0, side).setColumn(1, along).setColumn(2, depth)
                        .mul(new Matrix3f().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot()));
                Vector3f angles = basis.getEulerAnglesZYX(new Vector3f());
                link.x = local[i].x; link.y = local[i].y; link.z = local[i].z;
                link.xRot = angles.x; link.yRot = angles.y; link.zRot = angles.z;
            }
        }
        if (advance) s.lastAge = state.ageInTicks;
    }

    /** Model blocks (y down, z back, turning with the body) to the world, the renderer's convention. */
    private static Vector3f toWorld(Vector3f model, DigimonRenderState state, float yaw) {
        return new Vector3f(model.x, 1.501F - model.y, -model.z).mul(state.modelScale).rotateY(-yaw)
                .add((float) state.x, (float) state.y, (float) state.z);
    }

    private static Vector3f toModel(Vector3f world, DigimonRenderState state, float yaw) {
        Vector3f v = new Vector3f(world).sub((float) state.x, (float) state.y, (float) state.z).rotateY(yaw).div(state.modelScale);
        return v.set(v.x, 1.501F - v.y, -v.z);
    }

    /** A collider box placed in the world for this frame. */
    private record Placed(Matrix4f toBox, Matrix4f fromBox, Vector3f min, Vector3f max, DigimonRenderState state, float yaw) {
        /** Out of the box through the face the link came in by (its position before the step), else the nearest. */
        void pushOut(Vector3f world, Vector3f before) {
            Vector3f b = toBox.transformPosition(toModel(world, state, yaw)).mul(16);
            if (b.x <= min.x || b.x >= max.x || b.y <= min.y || b.y >= max.y || b.z <= min.z || b.z >= max.z) return;
            Vector3f a = toBox.transformPosition(toModel(before, state, yaw)).mul(16);
            float[] gaps = {b.x - min.x, max.x - b.x, b.y - min.y, max.y - b.y, b.z - min.z, max.z - b.z};
            boolean[] crossed = {a.x <= min.x, a.x >= max.x, a.y <= min.y, a.y >= max.y, a.z <= min.z, a.z >= max.z};
            boolean any = false;
            for (boolean c : crossed) any |= c;
            int face = -1;
            for (int i = 0; i < 6; i++) if ((!any || crossed[i]) && (face < 0 || gaps[i] < gaps[face])) face = i;
            switch (face) {
                case 0 -> b.x = min.x; case 1 -> b.x = max.x;
                case 2 -> b.y = min.y; case 3 -> b.y = max.y;
                case 4 -> b.z = min.z; default -> b.z = max.z;
            }
            Vector3f out = toWorld(fromBox.transformPosition(b.div(16)), state, yaw);
            // Iron on armour does not bounce: the link is set outside with the speed it had, not flung by the push.
            before.add(new Vector3f(out).sub(world));
            world.set(out);
        }
    }

    private static List<Placed> boxes(ModelPart root, Rope rope, DigimonRenderState state, float yaw) {
        var placed = new ArrayList<Placed>();
        for (var box : rope.colliders()) {
            Matrix4f from = new Matrix4f(transform(root, box.path()).pose());
            Vector3f grow = new Vector3f(box.radius());
            placed.add(new Placed(new Matrix4f(from).invert(), from, new Vector3f(box.min()).sub(grow), new Vector3f(box.max()).add(grow), state, yaw));
        }
        return placed;
    }

    private static PoseStack.Pose transform(ModelPart root, List<String> path) {
        var stack = new PoseStack(); ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        return stack.last();
    }

    private static ModelPart part(ModelPart root, List<String> path) {
        ModelPart part = root;
        for (String p : path) part = part.getChild(p);
        return part;
    }

    /** Parse the {@code ropes} array of a ground model entry. */
    public static List<Rope> read(JsonObject config) {
        if (!config.has("ropes")) return List.of();
        var ropes = new ArrayList<Rope>();
        for (var item : config.getAsJsonArray("ropes")) {
            var o = item.getAsJsonObject();
            List<String> path = strings(o.getAsJsonArray("path")), links = strings(o.getAsJsonArray("links"));
            if (links.size() < 2) throw new IllegalArgumentException("A rope needs at least two links");
            float radius = GsonHelper.getAsFloat(o, "radius", 1);
            var colliders = new ArrayList<Box>();
            if (o.has("colliders")) for (var entry : o.getAsJsonArray("colliders")) {
                var c = entry.getAsJsonObject();
                var min = c.getAsJsonArray("min");
                var max = c.getAsJsonArray("max");
                colliders.add(new Box(strings(c.getAsJsonArray("path")),
                        new Vector3f(min.get(0).getAsFloat(), min.get(1).getAsFloat(), min.get(2).getAsFloat()),
                        new Vector3f(max.get(0).getAsFloat(), max.get(1).getAsFloat(), max.get(2).getAsFloat()),
                        GsonHelper.getAsFloat(c, "radius", radius)));
            }
            ropes.add(new Rope(path, links, radius, List.copyOf(colliders)));
        }
        return List.copyOf(ropes);
    }

    private static List<String> strings(com.google.gson.JsonArray array) {
        var out = new ArrayList<String>(); array.forEach(n -> out.add(n.getAsString())); return List.copyOf(out);
    }
}
