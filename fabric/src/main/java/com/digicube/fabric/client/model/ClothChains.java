package com.digicube.fabric.client.model;

import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Hanging cloth on a native model, simulated on the client after the pose is applied: a chain of hinged parts
 * ({@code cloth} in {@code ground_models.json}), each a damped pendulum in pitch and roll under gravity, thrown by
 * the acceleration of its hinge (the body's motion and the pelvis's own) and by the air it moves through, held out
 * of the body by collider points (the fronts of the thighs and shins) it may never fall behind. Nothing here is
 * keyed in a clip; the chain parts stay at rest in every authored clip.
 */
public final class ClothChains {
    /** A hinged chain: {@code path} leads from the root to the part the first segment hangs from. */
    public record Chain(List<String> path, List<String> segments, float[] lengths, List<Collider> colliders, float margin) {}
    /** Points (model px, the part's own frame) the cloth must stay in front of. */
    public record Collider(List<String> path, float[][] points) {}

    /** Per-entity simulation state, kept by the renderer for as long as the entity is drawn. */
    public static final class State {
        private double[][] pitch, roll, pitchSpeed, rollSpeed;
        private final Vector3f lastHinge = new Vector3f(), lastVelocity = new Vector3f(), acceleration = new Vector3f();
        private float lastAge = Float.NaN;
        private boolean settled;
    }

    /** Model gravity in blocks a tick squared, and how the cloth answers it. */
    private static final float GRAVITY = 0.08F, DAMPING = 0.12F, BENDING = 0.35F, DRAG = 0.012F, STEP = 0.25F;
    private static final float MAX_PITCH = (float) Math.toRadians(105), MAX_ROLL = (float) Math.toRadians(35), FOLD = (float) Math.toRadians(28);
    private static final float MAX_SPEED = 0.9F;

    private ClothChains() {}

    /** Simulate and pose every chain of the model for this frame; a repeated call in the same frame only re-poses. */
    public static void apply(ModelPart root, DigimonRenderState state, List<Chain> chains, State s) {
        if (chains.isEmpty() || s == null) return;
        if (s.pitch == null) {
            s.pitch = new double[chains.size()][]; s.roll = new double[chains.size()][]; s.pitchSpeed = new double[chains.size()][]; s.rollSpeed = new double[chains.size()][];
            for (int c = 0; c < chains.size(); c++) {
                int n = chains.get(c).segments().size();
                s.pitch[c] = new double[n]; s.roll[c] = new double[n]; s.pitchSpeed[c] = new double[n]; s.rollSpeed[c] = new double[n];
            }
        }
        float dt = Float.isNaN(s.lastAge) ? 0 : Math.clamp(state.ageInTicks - s.lastAge, 0, 2);
        boolean advance = dt > 1.0E-4F || Float.isNaN(s.lastAge);
        for (int c = 0; c < chains.size(); c++) {
            var chain = chains.get(c);
            var parent = transform(root, chain.path());
            var inverse = parent.pose().get3x3(new Matrix3f()).transpose();
            ModelPart first = child(root, chain.path(), chain.segments().getFirst());
            Vector3f pivot = new Vector3f(first.x / 16, first.y / 16, first.z / 16);
            Vector3f hingeModel = parent.pose().transformPosition(new Vector3f(pivot));
            if (advance) {
                // World motion of the first hinge: model space is y-down, z back, and turns with the body (the renderer's convention).
                float yaw = (float) Math.toRadians(state.bodyRot);
                Vector3f world = new Vector3f(hingeModel.x, 1.5F - hingeModel.y, -hingeModel.z).mul(state.modelScale).rotateY(-yaw)
                        .add((float) state.x, (float) state.y, (float) state.z);
                Vector3f velocity = Float.isNaN(s.lastAge) || dt <= 0 ? new Vector3f() : new Vector3f(world).sub(s.lastHinge).div(dt);
                if (!Float.isNaN(s.lastAge) && dt > 0) {
                    var a = new Vector3f(velocity).sub(s.lastVelocity).div(dt);
                    if (a.length() > 1.5F) a.normalize(1.5F);
                    s.acceleration.lerp(a, 0.5F);
                }
                s.lastHinge.set(world); s.lastVelocity.set(velocity);
                // Effective gravity in the chain's frame: gravity less the hinge's acceleration, plus the air the cloth moves through.
                Vector3f push = new Vector3f(0, -GRAVITY, 0).sub(s.acceleration).sub(new Vector3f(velocity).mul(DRAG * velocity.length()));
                Vector3f model = new Vector3f(push).rotateY(yaw); model.set(model.x, -model.y, -model.z);
                Vector3f g = inverse.transform(model).div(state.modelScale);   // model units a tick squared
                float pitchTarget = (float) Math.atan2(-g.z, g.y), rollTarget = (float) Math.atan2(g.x, g.y), strength = g.length();
                var bounds = minimumPitch(root, chain, parent, inverse, pivot, s.pitch[c], s.roll[c]);
                float remaining = Float.isNaN(s.lastAge) ? 0 : dt;
                if (!s.settled) { settle(chain, s.pitch[c], bounds); s.settled = true; }
                while (remaining > 0) {
                    float h = Math.min(STEP, remaining); remaining -= h;
                    for (int i = 0; i < chain.segments().size(); i++) {
                        double length = chain.lengths()[i] / 16.0, above = i == 0 ? 0 : s.pitch[c][i - 1], aboveRoll = i == 0 ? 0 : s.roll[c][i - 1];
                        double pitchAcc = -(strength / length) * Math.sin(s.pitch[c][i] - pitchTarget) - BENDING * (s.pitch[c][i] - above) - DAMPING * s.pitchSpeed[c][i];
                        double rollAcc = -(strength / length) * Math.sin(s.roll[c][i] - rollTarget) - BENDING * (s.roll[c][i] - aboveRoll) - DAMPING * s.rollSpeed[c][i];
                        s.pitchSpeed[c][i] = Mth.clamp(s.pitchSpeed[c][i] + pitchAcc * h, -MAX_SPEED, MAX_SPEED);
                        s.rollSpeed[c][i] = Mth.clamp(s.rollSpeed[c][i] + rollAcc * h, -MAX_SPEED, MAX_SPEED);
                        s.pitch[c][i] += s.pitchSpeed[c][i] * h; s.roll[c][i] += s.rollSpeed[c][i] * h;
                    }
                    constrain(chain, s.pitch[c], s.pitchSpeed[c], s.roll[c], s.rollSpeed[c], bounds);
                    bounds = minimumPitch(root, chain, parent, inverse, pivot, s.pitch[c], s.roll[c]);
                }
                constrain(chain, s.pitch[c], s.pitchSpeed[c], s.roll[c], s.rollSpeed[c], bounds);
            }
            for (int i = 0; i < chain.segments().size(); i++) {
                ModelPart part = child(root, chain.path(), chain.segments().subList(0, i + 1));
                part.xRot -= (float) (s.pitch[c][i] - (i == 0 ? 0 : s.pitch[c][i - 1]));
                part.zRot -= (float) (s.roll[c][i] - (i == 0 ? 0 : s.roll[c][i - 1]));
            }
        }
        if (advance) s.lastAge = state.ageInTicks;
    }

    private static void settle(Chain chain, double[] pitch, float[] bounds) {
        for (int i = 0; i < pitch.length; i++) pitch[i] = Math.max(bounds[i], i == 0 ? 0 : pitch[i - 1] - FOLD);
    }

    private static void constrain(Chain chain, double[] pitch, double[] pitchSpeed, double[] roll, double[] rollSpeed, float[] bounds) {
        for (int i = 0; i < pitch.length; i++) {
            double floor = Math.max(bounds[i], i == 0 ? -0.05 : pitch[i - 1] - FOLD);
            if (pitch[i] < floor) { pitch[i] = floor; if (pitchSpeed[i] < 0) pitchSpeed[i] = 0; }
            if (pitch[i] > MAX_PITCH) { pitch[i] = MAX_PITCH; if (pitchSpeed[i] > 0) pitchSpeed[i] = 0; }
            if (roll[i] < -MAX_ROLL) { roll[i] = -MAX_ROLL; if (rollSpeed[i] < 0) rollSpeed[i] = 0; }
            if (roll[i] > MAX_ROLL) { roll[i] = MAX_ROLL; if (rollSpeed[i] > 0) rollSpeed[i] = 0; }
        }
    }

    /** The smallest forward pitch of each segment that keeps it in front of every collider point within its reach. */
    private static float[] minimumPitch(ModelPart root, Chain chain, PoseStack.Pose parent, Matrix3f inverse, Vector3f pivot, double[] pitch, double[] roll) {
        int n = chain.segments().size(); float[] bounds = new float[n];
        java.util.Arrays.fill(bounds, -0.05F);
        Vector3f origin = parent.pose().getTranslation(new Vector3f());
        Vector3f[] hinges = new Vector3f[n + 1]; hinges[0] = new Vector3f(pivot);
        for (int i = 0; i < n; i++) {
            double l = chain.lengths()[i] / 16.0, f = pitch[i], r = roll[i];
            hinges[i + 1] = new Vector3f(hinges[i]).add((float) (l * Math.sin(r)), (float) (l * Math.cos(f) * Math.cos(r)), (float) (-l * Math.sin(f) * Math.cos(r)));
        }
        for (var collider : chain.colliders()) {
            var m = transform(root, collider.path()).pose();
            for (float[] p : collider.points()) {
                Vector3f q = m.transformPosition(new Vector3f(p[0] / 16, p[1] / 16, p[2] / 16)).sub(origin);
                inverse.transform(q);
                for (int i = 0; i < n; i++) {
                    float dx = q.x - hinges[i].x, dy = q.y - hinges[i].y, dz = q.z - hinges[i].z;
                    double distance = Math.sqrt(dy * dy + dz * dz), reach = chain.lengths()[i] / 16.0 + chain.margin() / 16.0;
                    if (dy <= 0 || distance > reach || Math.abs(dx) > 0.75F) continue;
                    double angle = Math.atan2(-dz, dy) + Math.asin(Math.min(1, chain.margin() / 16.0 / Math.max(1.0E-4, distance)));
                    if (angle > bounds[i]) bounds[i] = (float) angle;
                }
            }
        }
        return bounds;
    }

    private static PoseStack.Pose transform(ModelPart root, List<String> path) {
        var stack = new PoseStack(); ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        return stack.last();
    }

    private static ModelPart child(ModelPart root, List<String> path, String name) {
        ModelPart part = root;
        for (String p : path) part = part.getChild(p);
        return part.getChild(name);
    }

    private static ModelPart child(ModelPart root, List<String> path, List<String> chain) {
        ModelPart part = root;
        for (String p : path) part = part.getChild(p);
        for (String p : chain) part = part.getChild(p);
        return part;
    }

    /** Parse the {@code cloth} array of a ground model entry. */
    public static List<Chain> read(com.google.gson.JsonObject config) {
        if (!config.has("cloth")) return List.of();
        var chains = new java.util.ArrayList<Chain>();
        for (var item : config.getAsJsonArray("cloth")) {
            var o = item.getAsJsonObject();
            var path = new java.util.ArrayList<String>(); o.getAsJsonArray("path").forEach(n -> path.add(n.getAsString()));
            var segments = new java.util.ArrayList<String>(); o.getAsJsonArray("segments").forEach(n -> segments.add(n.getAsString()));
            var lengths = new float[segments.size()]; var l = o.getAsJsonArray("lengths");
            if (l.size() != lengths.length || lengths.length == 0) throw new IllegalArgumentException("A cloth chain needs one length per segment");
            for (int i = 0; i < lengths.length; i++) { lengths[i] = l.get(i).getAsFloat(); if (!(lengths[i] > 0)) throw new IllegalArgumentException("Invalid cloth length"); }
            var colliders = new java.util.ArrayList<Collider>();
            if (o.has("colliders")) for (var entry : o.getAsJsonArray("colliders")) {
                var c = entry.getAsJsonObject(); var cp = new java.util.ArrayList<String>(); c.getAsJsonArray("path").forEach(n -> cp.add(n.getAsString()));
                var pts = c.getAsJsonArray("points"); var points = new float[pts.size()][3];
                for (int i = 0; i < points.length; i++) for (int j = 0; j < 3; j++) points[i][j] = pts.get(i).getAsJsonArray().get(j).getAsFloat();
                colliders.add(new Collider(List.copyOf(cp), points));
            }
            chains.add(new Chain(List.copyOf(path), List.copyOf(segments), lengths, List.copyOf(colliders), net.minecraft.util.GsonHelper.getAsFloat(o, "margin", 2)));
        }
        return List.copyOf(chains);
    }
}
