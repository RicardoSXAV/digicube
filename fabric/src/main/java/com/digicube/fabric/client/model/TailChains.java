package com.digicube.fabric.client.model;

import com.digicube.fabric.client.render.DigimonRenderState;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.GsonHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A tail on a native model that holds its own line and swings with the body ({@code tails} in {@code ground_models.json}),
 * simulated on the client after the pose. Its links are siblings under one frame part, the tail's root, which the clips
 * keep moving; the joints between the links, and the tip, are points in the world. Each is pulled toward where the clips
 * put it relative to the joint above (the joint's muscle: {@code stiffness} at the root down to {@code tip_stiffness}),
 * damped as it swings about that line ({@code damping}), streamed back by the air it runs through ({@code drag}) and
 * sagging a little under its weight ({@code sag}). So the tail trails a start, swings out of a turn, lifts as the body
 * falls from a leap and whips down on the landing, bobs through the gallop and settles back on the clip's line when the
 * body stands. A joint bends at most {@code bend} degrees from the one above (the first {@code root_bend} from the
 * clip's line), and no point goes under the ground the body stands on. Any clip's keys on the links are overwritten;
 * {@code carried} parts ride on the last link as they rest on it. A step up or down a block is felt as the legs carry
 * the body through it, eased over a few ticks ({@link #FOLLOW}): the game sets a body on the step above or below in a
 * single tick, and felt raw every step of a hillside jolted the tail like a leap.
 */
public final class TailChains {
    /**
     * A tail: {@code path} leads from the root to the frame part the {@code links} hang in, root first; {@code tip} is the
     * far end of the last link (model px in the frame). Stiffness and damping are per tick squared and per tick, drag per
     * block of speed, sag in blocks a tick squared, bends in degrees.
     */
    public record Tail(List<String> path, List<String> links, Vector3f tip, List<String> carried, float stiffness,
                       float tipStiffness, float damping, float drag, float sag, float bend, float rootBend) {}

    /** Per-entity simulation state, kept by the renderer for as long as the entity is drawn. */
    public static final class State {
        /** Per tail: the joints and tip, where they are and how fast they go (blocks and blocks a tick, from the entity, world axes). */
        private Vector3f[][] points, velocities, targets;
        private double x, y, z;
        /** Where the tail feels the body's height to be, and how fast it feels it rise (blocks, blocks a tick). */
        private double feltY, feltRise;
        private boolean stepping;
        private float lastAge = Float.NaN;
    }

    /** Ticks per integration step, and the share of the bend above that a joint's line turns with. */
    private static final float STEP = .25F, LOCAL = .85F;
    /** A jump of the body further than this, or a gap longer than MAX_GAP ticks, drops the tail on the clip's line. */
    private static final float SNAP = 3, MAX_GAP = 5;
    /** Blocks above the body's feet no point of the tail goes under. */
    private static final float FLOOR = .08F;
    /** Per tick: how fast the tail comes round to a step up or down (critically damped, no overshoot). */
    private static final float FOLLOW = .3F;

    private TailChains() {}

    public static void apply(ModelPart root, DigimonRenderState state, List<Tail> tails, State s) {
        if (tails.isEmpty() || s == null) return;
        if (s.points == null || s.points.length != tails.size()) {
            s.points = new Vector3f[tails.size()][];
            s.velocities = new Vector3f[tails.size()][];
            s.targets = new Vector3f[tails.size()][];
            s.lastAge = Float.NaN;
        }
        float dt = Float.isNaN(s.lastAge) ? 0 : state.ageInTicks - s.lastAge;
        // Everything is kept relative to the entity, so the numbers stay small wherever it is; a move shifts them back.
        Vector3f shift = new Vector3f((float) (state.x - s.x), (float) (state.y - s.y), (float) (state.z - s.z));
        boolean reset = Float.isNaN(s.lastAge) || dt < 0 || dt > MAX_GAP || shift.length() > SNAP;
        boolean advance = !reset && dt > 1.0E-4F;
        shift.y = (float) feel(s, state, dt, reset, advance);
        float yaw = (float) Math.toRadians(state.bodyRot);
        for (int t = 0; t < tails.size(); t++) {
            var tail = tails.get(t);
            ModelPart frame = part(root, tail.path());
            Matrix4f frameModel = transform(root, tail.path());
            int n = tail.links().size();
            Vector3f[] rest = restPoints(frame, tail);
            // Where the clips put the joints now.
            Vector3f[] target = new Vector3f[n + 1];
            for (int k = 0; k <= n; k++) target[k] = toWorld(frameModel.transformPosition(new Vector3f(rest[k]).div(16)), state, yaw);
            Vector3f[] p = s.points[t], v = s.velocities[t], before = s.targets[t];
            if (reset || p == null || p.length != n + 1) {
                p = s.points[t] = new Vector3f[n + 1];
                v = s.velocities[t] = new Vector3f[n + 1];
                for (int k = 0; k <= n; k++) { p[k] = new Vector3f(target[k]); v[k] = new Vector3f(); }
                before = null;
            } else if (shift.lengthSquared() > 0) {
                for (var point : p) point.sub(shift);
                if (before != null) for (var point : before) point.sub(shift);
            }
            if (advance && before != null) simulate(tail, p, v, before, target, dt);
            s.targets[t] = target;
            pose(frame, tail, rest, p, frameModel, state, yaw);
        }
        s.x = state.x; s.y = state.y; s.z = state.z;
        if (advance || reset) s.lastAge = state.ageInTicks;
    }

    /**
     * How far the tail feels the body rise this frame: all of it in the air (a leap, a fall), and on the ground (a step
     * up or down, which the game makes in one tick) the body's height eased in at {@link #FOLLOW}, from a standstill as it
     * lands.
     */
    private static double feel(State s, DigimonRenderState state, float dt, boolean reset, boolean advance) {
        double before = s.feltY;
        if (reset) { s.feltY = state.y; s.feltRise = 0; s.stepping = false; return 0; }
        if (!advance) return 0;
        if (!state.groundedMove) {
            s.feltRise = (state.y - s.y) / dt;
            s.feltY += state.y - s.y;
            s.stepping = false;
            return state.y - s.y;
        }
        if (!s.stepping) { s.feltRise = 0; s.stepping = true; }
        int steps = Math.max(1, (int) Math.ceil(dt / STEP));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            s.feltRise += (FOLLOW * FOLLOW * (state.y - s.feltY) - 2 * FOLLOW * s.feltRise) * h;
            s.feltY += s.feltRise * h;
        }
        return s.feltY - before;
    }

    /** The joints at rest in the frame (px): each link's pivot, then the tip. */
    private static Vector3f[] restPoints(ModelPart frame, Tail tail) {
        int n = tail.links().size();
        Vector3f[] rest = new Vector3f[n + 1];
        for (int k = 0; k < n; k++) {
            PartPose pose = frame.getChild(tail.links().get(k)).getInitialPose();
            rest[k] = new Vector3f(pose.x(), pose.y(), pose.z());
        }
        rest[n] = new Vector3f(tail.tip());
        return rest;
    }

    /**
     * One frame of the tail, {@code dt} ticks in steps of at most {@link #STEP}: the root rides the clip's line from
     * {@code from} to {@code to}; every other point is pushed by its muscle, damping, drag and sag, then held at its
     * length from the point above, within its bend, above the floor.
     */
    private static void simulate(Tail tail, Vector3f[] p, Vector3f[] v, Vector3f[] from, Vector3f[] to, float dt) {
        int n = p.length - 1, steps = Math.max(1, (int) Math.ceil(dt / STEP));
        float h = dt / steps;
        float[] length = new float[n];
        for (int k = 0; k < n; k++) length[k] = to[k].distance(to[k + 1]);
        // How fast the clip's line carries each point through the world: the swing is damped about it, not about still air.
        Vector3f[] carried = new Vector3f[n + 1];
        for (int k = 0; k <= n; k++) carried[k] = new Vector3f(to[k]).sub(from[k]).div(dt);
        Vector3f[] at = new Vector3f[n + 1], next = new Vector3f[n + 1];
        for (int step = 1; step <= steps; step++) {
            float f = (float) step / steps;
            for (int k = 0; k <= n; k++) at[k] = new Vector3f(from[k]).lerp(to[k], f);
            Quaternionf bent = new Quaternionf();
            for (int k = 1; k <= n; k++) {
                if (k >= 2) {
                    // the segment above, turned off its clip line: this one's line turns with it (a joint holds its own angle)
                    Vector3f line = new Vector3f(at[k - 1]).sub(at[k - 2]), now = new Vector3f(p[k - 1]).sub(p[k - 2]);
                    bent = new Quaternionf().slerp(new Quaternionf().rotationTo(line, now), LOCAL);
                }
                Vector3f aim = new Vector3f(at[k]).sub(at[k - 1]).rotate(bent).add(p[k - 1]);
                float stiffness = tail.stiffness() + (tail.tipStiffness() - tail.stiffness()) * (k - 1) / Math.max(1, n - 1);
                Vector3f push = new Vector3f(aim).sub(p[k]).mul(stiffness);
                push.sub(new Vector3f(v[k]).sub(carried[k]).mul(tail.damping()));
                push.sub(new Vector3f(v[k]).mul(tail.drag() * v[k].length()));
                push.y -= tail.sag();
                v[k].add(push.mul(h));
                next[k] = new Vector3f(v[k]).mul(h).add(p[k]);
            }
            next[0] = new Vector3f(at[0]);
            for (int k = 1; k <= n; k++) {
                Vector3f line = k == 1 ? new Vector3f(at[1]).sub(at[0]) : new Vector3f(next[k - 1]).sub(next[k - 2]);
                Vector3f along = new Vector3f(next[k]).sub(next[k - 1]);
                if (along.lengthSquared() < 1.0E-12F) along.set(line);
                limit(line.normalize(), along.normalize(), (float) Math.toRadians(k == 1 ? tail.rootBend() : tail.bend()));
                next[k].set(along).mul(length[k - 1]).add(next[k - 1]);
                if (next[k].y < FLOOR && next[k - 1].y >= FLOOR) {
                    // along the ground: the point slides up onto it, keeping its distance from the one above
                    float drop = next[k - 1].y - FLOOR, flat = (float) Math.sqrt(Math.max(0, length[k - 1] * length[k - 1] - drop * drop));
                    Vector3f level = new Vector3f(along.x, 0, along.z);
                    if (level.lengthSquared() < 1.0E-8F) level.set(1, 0, 0);
                    next[k].set(level.normalize().mul(flat)).add(next[k - 1].x, FLOOR, next[k - 1].z);
                }
            }
            for (int k = 1; k <= n; k++) {
                v[k].set(next[k]).sub(p[k]).div(h);
                p[k].set(next[k]);
            }
            p[0].set(at[0]);
            v[0].set(carried[0]);
        }
    }

    /** Turns unit vector {@code along} back toward unit vector {@code line} until they are at most {@code most} radians apart. */
    private static void limit(Vector3f line, Vector3f along, float most) {
        float cos = Math.clamp(line.dot(along), -1, 1), angle = (float) Math.acos(cos);
        if (angle <= most) return;
        Vector3f axis = new Vector3f(line).cross(along);
        if (axis.lengthSquared() < 1.0E-10F) {
            // straight back on itself: any side will do
            axis.set(Math.abs(line.y) < .9F ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).cross(line);
        }
        along.set(line).rotateAxis(most, axis.normalize().x, axis.y, axis.z);
    }

    /**
     * Each link from its joint toward the next, turned from its rest line by the least rotation (so it never spins about
     * its own length), and the carried parts on the last link as they rest on it.
     */
    private static void pose(ModelPart frame, Tail tail, Vector3f[] rest, Vector3f[] p, Matrix4f frameModel, DigimonRenderState state, float yaw) {
        Matrix4f toFrame = new Matrix4f(frameModel).invert();
        int n = tail.links().size();
        Vector3f[] local = new Vector3f[n + 1];
        for (int k = 0; k <= n; k++) local[k] = toFrame.transformPosition(toModel(p[k], state, yaw)).mul(16);
        Matrix4f last = null, lastRest = null;
        for (int k = 0; k < n; k++) {
            ModelPart link = frame.getChild(tail.links().get(k));
            PartPose pose = link.getInitialPose();
            Vector3f restLine = new Vector3f(rest[k + 1]).sub(rest[k]), line = new Vector3f(local[k + 1]).sub(local[k]);
            Quaternionf turn = line.lengthSquared() < 1.0E-8F ? new Quaternionf() : new Quaternionf().rotationTo(restLine, line);
            Quaternionf rotation = turn.mul(new Quaternionf().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot()));
            Vector3f angles = rotation.getEulerAnglesZYX(new Vector3f());
            link.x = local[k].x; link.y = local[k].y; link.z = local[k].z;
            link.xRot = angles.x; link.yRot = angles.y; link.zRot = angles.z;
            if (k == n - 1) {
                last = new Matrix4f().translation(local[k]).rotate(rotation);
                lastRest = matrix(pose);
            }
        }
        if (last == null) return;
        Matrix4f onLast = new Matrix4f(last).mul(lastRest.invert());
        for (String name : tail.carried()) {
            ModelPart part = frame.getChild(name);
            Matrix4f placed = new Matrix4f(onLast).mul(matrix(part.getInitialPose()));
            Vector3f at = placed.getTranslation(new Vector3f());
            Vector3f angles = placed.getNormalizedRotation(new Quaternionf()).getEulerAnglesZYX(new Vector3f());
            part.x = at.x; part.y = at.y; part.z = at.z;
            part.xRot = angles.x; part.yRot = angles.y; part.zRot = angles.z;
        }
    }

    private static Matrix4f matrix(PartPose pose) {
        return new Matrix4f().translation(pose.x(), pose.y(), pose.z()).rotate(new Quaternionf().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot()));
    }

    /** Model units (y down, z back, turning with the body) to blocks from the entity along the world's axes, the renderer's convention. */
    private static Vector3f toWorld(Vector3f model, DigimonRenderState state, float yaw) {
        return new Vector3f(model.x, 1.501F - model.y, -model.z).mul(state.modelScale).rotateY(-yaw);
    }

    private static Vector3f toModel(Vector3f world, DigimonRenderState state, float yaw) {
        Vector3f v = new Vector3f(world).rotateY(yaw).div(state.modelScale);
        return v.set(v.x, 1.501F - v.y, -v.z);
    }

    private static Matrix4f transform(ModelPart root, List<String> path) {
        var stack = new PoseStack();
        ModelPart part = root;
        part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        return new Matrix4f(stack.last().pose());
    }

    private static ModelPart part(ModelPart root, List<String> path) {
        ModelPart part = root;
        for (String name : path) part = part.getChild(name);
        return part;
    }

    /** Parse the {@code tails} array of a ground model entry. */
    public static List<Tail> read(JsonObject config) {
        if (!config.has("tails")) return List.of();
        var tails = new ArrayList<Tail>();
        for (var item : config.getAsJsonArray("tails")) {
            var o = item.getAsJsonObject();
            var tip = o.getAsJsonArray("tip");
            if (tip == null || tip.size() != 3) throw new IllegalArgumentException("A tail needs its tip");
            var tail = new Tail(strings(o.getAsJsonArray("path")), strings(o.getAsJsonArray("links")),
                    new Vector3f(tip.get(0).getAsFloat(), tip.get(1).getAsFloat(), tip.get(2).getAsFloat()),
                    o.has("carried") ? strings(o.getAsJsonArray("carried")) : List.of(),
                    GsonHelper.getAsFloat(o, "stiffness"), GsonHelper.getAsFloat(o, "tip_stiffness"), GsonHelper.getAsFloat(o, "damping"),
                    GsonHelper.getAsFloat(o, "drag", 0), GsonHelper.getAsFloat(o, "sag", 0), GsonHelper.getAsFloat(o, "bend"),
                    GsonHelper.getAsFloat(o, "root_bend"));
            if (tail.links().isEmpty() || !(tail.stiffness() > 0 && tail.stiffness() <= 1) || !(tail.tipStiffness() > 0 && tail.tipStiffness() <= 1)
                    || !(tail.damping() >= 0 && tail.damping() <= 2) || !(tail.drag() >= 0 && tail.drag() <= 4) || !(tail.sag() >= 0 && tail.sag() <= .1F)
                    || !(tail.bend() > 0 && tail.bend() <= 90) || !(tail.rootBend() > 0 && tail.rootBend() <= 90))
                throw new IllegalArgumentException("Invalid tail " + tail.links());
            tails.add(tail);
        }
        return List.copyOf(tails);
    }

    private static List<String> strings(com.google.gson.JsonArray array) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.getAsString()));
        return List.copyOf(out);
    }
}
