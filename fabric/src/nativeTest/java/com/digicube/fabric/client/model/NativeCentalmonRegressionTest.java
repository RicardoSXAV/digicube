package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Centarumon's arm chain on the compiled client model: twelve links on a verlet rope ({@code ropes} in
 * ground_models.json, {@link RopeChains}) pinned at the wrist cuff. It hangs under its own weight at rest, keeps its
 * links their rest distance apart, trails behind a galloping body and swings on ahead when the body stops dead, turns
 * with the body, snaps back after a teleport, and never enters the barrel, the breast, the near foreleg or the hand.
 */
public final class NativeCentalmonRegressionTest {
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError("FAIL: " + message);
    }

    private static Matrix4f pose(ModelPart root, List<String> path) {
        var stack = new PoseStack(); ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        return new Matrix4f(stack.last().pose());
    }

    /** A link's centre in the world (the renderer's convention: model blocks y down, z back, body facing +z at yaw 0). */
    private static Vector3f world(ModelPart root, RopeChains.Rope rope, int link, DigimonRenderState state) {
        var path = new ArrayList<>(rope.path()); path.add(rope.links().get(link));
        Vector3f m = pose(root, path).transformPosition(new Vector3f());
        return new Vector3f(m.x, 1.501F - m.y, -m.z).mul(state.modelScale).rotateY((float) -Math.toRadians(state.bodyRot))
                .add((float) state.x, (float) state.y, (float) state.z);
    }

    private static List<Vector3f> links(ModelPart root, RopeChains.Rope rope, DigimonRenderState state) {
        var out = new ArrayList<Vector3f>();
        for (int i = 0; i < rope.links().size(); i++) out.add(world(root, rope, i, state));
        return out;
    }

    /** How deep (px) the deepest link centre sits inside a collider box, 0 when all are outside. */
    private static float inside(ModelPart root, RopeChains.Rope rope) {
        float worst = 0;
        for (var box : rope.colliders()) {
            Matrix4f toBox = pose(root, box.path()).invert();
            for (int i = 1; i < rope.links().size(); i++) {
                var path = new ArrayList<>(rope.path()); path.add(rope.links().get(i));
                Vector3f b = toBox.transformPosition(pose(root, path).transformPosition(new Vector3f())).mul(16);
                float depth = Math.min(Math.min(Math.min(b.x - box.min().x, box.max().x - b.x), Math.min(b.y - box.min().y, box.max().y - b.y)),
                        Math.min(b.z - box.min().z, box.max().z - b.z));
                worst = Math.max(worst, depth);
            }
        }
        return worst;
    }

    /** The largest bend between two neighbouring segments, degrees: a whip cracks through kinks, a heavy chain curves. */
    private static float kink(List<Vector3f> points) {
        float worst = 0;
        for (int i = 1; i < points.size() - 1; i++) {
            Vector3f a = new Vector3f(points.get(i)).sub(points.get(i - 1)), b = new Vector3f(points.get(i + 1)).sub(points.get(i));
            worst = Math.max(worst, (float) Math.toDegrees(a.angle(b)));
        }
        return worst;
    }

    /** Each link's side axis in the world, to see how fast the links turn about themselves. */
    private static List<Vector3f> sides(ModelPart root, RopeChains.Rope rope) {
        var out = new ArrayList<Vector3f>();
        for (String link : rope.links()) {
            var path = new ArrayList<>(rope.path()); path.add(link);
            out.add(pose(root, path).transformDirection(new Vector3f(1, 0, 0)).normalize());
        }
        return out;
    }

    /** The largest turn of any link between two frames, degrees. */
    private static float turn(List<Vector3f> before, List<Vector3f> after) {
        float worst = 0;
        for (int i = 0; i < before.size(); i++) worst = Math.max(worst, (float) Math.toDegrees(before.get(i).angle(after.get(i))));
        return worst;
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("centalmon"));
        var definition = NativeGroundModel.definitions().get(species.id());
        check(definition.ropes().size() == 1 && definition.ropes().getFirst().links().size() == 12, "one twelve-link chain");
        var rope = definition.ropes().getFirst();
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = species.body().modelScale(); state.attackAnimation.start(0);
        state.attackAnimation.stop(); state.attackAnimationName = null;
        state.ropes = new RopeChains.State();

        // Rest distances between links, in the world.
        root.getAllParts().forEach(ModelPart::resetPose);
        var rest = links(root, rope, state);
        float total = 0;
        float[] spacing = new float[11];
        for (int i = 0; i < 11; i++) { spacing[i] = rest.get(i).distance(rest.get(i + 1)); total += spacing[i]; }

        int frame = 0;
        float worstInside = 0, worstStretch = 0;
        // 1. Standing, idle: it hangs straight down under its weight.
        for (; frame < 200; frame++) {
            state.ageInTicks = frame * .5F; state.groundAnimationAmount = 0; state.x = 0; state.z = 0; state.bodyRot = 0;
            model.setupAnim(state);
        }
        var hanging = links(root, rope, state);
        for (var p : root.getAllParts()) check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot), "finite chain");
        float drop = hanging.getFirst().y - hanging.getLast().y;
        check(drop > .85F * total, "at rest the chain hangs down: " + drop + " of " + total + " blocks");
        float sway = new Vector3f(hanging.getLast()).sub(hanging.getFirst()).mul(1, 0, 1).length();
        check(sway < .3F * total, "at rest the chain hangs nearly plumb: the end " + sway + " blocks off the anchor");
        for (int i = 0; i < 11; i++) {
            float stretch = Math.abs(hanging.get(i).distance(hanging.get(i + 1)) - spacing[i]) / spacing[i];
            worstStretch = Math.max(worstStretch, stretch);
        }
        check(worstStretch < .05F, "links keep their spacing at rest: " + worstStretch);
        float restBack = hanging.getLast().z - hanging.getFirst().z;

        // 2. Galloping off (+z is ahead at yaw 0): the end trails behind the anchor.
        double x = 0, speed = 0; float trailed = 0, streams = 0, steadyDrop = 0, whip = 0, kinked = 0, spin = 0;
        Vector3f lastEnd = null; var lastSides = sides(root, rope);
        for (int i = 0; i < 160; i++, frame++) {
            speed = Math.min(.5, speed + .02); x += speed * .5;
            state.ageInTicks = frame * .5F; state.groundAnimationAmount = 1; state.groundRunAmount = 1; state.groundAnimationPhase = frame * 1.5F;
            state.z = x; state.bodyRot = 0;
            model.setupAnim(state);
            var now = links(root, rope, state);
            trailed = Math.max(trailed, restBack - (now.getLast().z - now.getFirst().z));
            Vector3f end = new Vector3f(now.getLast()).sub(now.getFirst());
            // How fast the free end moves about the anchor, blocks a tick (frames are half ticks), in the steady gallop.
            if (i >= 120 && lastEnd != null) whip = Math.max(whip, end.distance(lastEnd) * 2);
            lastEnd = end;
            kinked = Math.max(kinked, kink(now));
            // (Not over the first two ticks: the test jumps from the idle pose to the gallop's at once, the game blends.)
            var turned = sides(root, rope); if (i >= 4) spin = Math.max(spin, turn(lastSides, turned) * 2); lastSides = turned;
            // The steady stream: averaged over the last twenty ticks, the swing of the start long damped out.
            if (i >= 120) { streams += (restBack - (now.getLast().z - now.getFirst().z)) / 40; steadyDrop += (now.getFirst().y - now.getLast().y) / 40; }
            worstInside = Math.max(worstInside, inside(root, rope));
            for (int k = 0; k < 11; k++) worstStretch = Math.max(worstStretch, Math.abs(now.get(k).distance(now.get(k + 1)) - spacing[k]) / spacing[k]);
        }
        check(trailed > .25F * total, "galloping, the chain trails behind: " + trailed + " blocks");
        check(streams > .25F * total && steadyDrop > .6F * total, "at a steady gallop the air streams it back: " + streams + " back, " + steadyDrop + " down");

        // 3. A dead stop: the chain swings on ahead of where it trailed.
        var before = links(root, rope, state);
        float swungAhead = 0;
        for (int i = 0; i < 20; i++, frame++) {
            state.ageInTicks = frame * .5F; state.groundAnimationAmount = 0; state.groundRunAmount = 0;
            model.setupAnim(state);
            var now = links(root, rope, state);
            swungAhead = Math.max(swungAhead, (now.getLast().z - now.getFirst().z) - (before.getLast().z - before.getFirst().z));
            worstInside = Math.max(worstInside, inside(root, rope));
        }
        System.out.printf("Centarumon chain: %.2f blocks, hangs %.2f, trails %.2f (steady %.2f), end speed %.3f b/t, kink %.1f deg, spin %.1f deg/t, swings on %.2f, stretch %.3f, inside %.2f px%n",
                total, drop, trailed, streams, whip, kinked, spin, swungAhead, worstStretch, worstInside);
        check(whip < .1F, "a heavy chain: at a steady gallop its end moves under .1 blocks a tick about the anchor: " + whip);
        check(kinked < 30, "it curves, never kinks like a whip: " + kinked + " degrees between neighbouring links");
        check(spin < 30, "no link flicks or flips round (they flipped 198 degrees a tick): at most " + spin + " degrees a tick");
        check(swungAhead > .2F * total, "stopping dead, the chain swings on ahead: " + swungAhead + " blocks");

        // 4. Turning on the spot, then a teleport: the chain follows the body round and snaps back to the arm.
        for (int i = 0; i < 60; i++, frame++) {
            state.ageInTicks = frame * .5F; state.bodyRot = i * 6;
            model.setupAnim(state);
            worstInside = Math.max(worstInside, inside(root, rope));
            var now = links(root, rope, state);
            for (int k = 0; k < 11; k++) worstStretch = Math.max(worstStretch, Math.abs(now.get(k).distance(now.get(k + 1)) - spacing[k]) / spacing[k]);
        }
        state.x += 40; state.ageInTicks = ++frame * .5F; model.setupAnim(state);
        var moved = links(root, rope, state);
        check(moved.getLast().distance(moved.getFirst()) <= total * 1.05F, "after a teleport the chain is back on the arm");

        check(worstStretch < .15F, "links keep their spacing through gallop, stop and turn: " + worstStretch);
        check(worstInside < .6F, "no link enters the body, the foreleg or the hand: " + worstInside + " px deep");
        System.out.println("Native Centarumon regression test passed (" + checks + " checks)");
    }
}
