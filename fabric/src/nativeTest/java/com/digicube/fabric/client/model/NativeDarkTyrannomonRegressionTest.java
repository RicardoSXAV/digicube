package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * DarkTyrannomon as the compiled NativeGroundModel draws it:
 * <ul>
 * <li>the walk and the run are planted: through the flat of a stance the ankle runs back at exactly the gait's pace,
 *     level, so the feet never slide under the sheet's strides;</li>
 * <li>the rider sits where the sheet seats them, and the walk and the run carry them only as far as the body's bob and
 *     lean; the feather under the seat is hidden while ridden and drawn unridden;</li>
 * <li>Iron Tail: the tail the client draws is the tail the server strikes with (the motion's contact points, the tail_02
 *     and tail_04 pivots), it sweeps low enough to strike a small body, and it turns its rider with the body's half turn.</li>
 * </ul>
 */
public final class NativeDarkTyrannomonRegressionTest {
    private static int checks;
    private static double worstPlant, worstTail;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** A part's pivot in the entity's frame at yaw 0 (blocks, +z forward, feet at 0), as riderOffset maps model space. */
    private static Vec3 pivot(ModelPart root, List<String> path, float scale) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(0, 0, 0, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    private static ModelPart part(ModelPart root, List<String> path) {
        ModelPart part = root;
        for (String name : path) part = part.getChild(name);
        return part;
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("darktyrannomon"));
        float scale = species.body().modelScale();
        var gait = species.locomotion().groundGait();
        var mount = species.body().mount().orElseThrow();
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = scale;
        float cycle = gait.cycleTicks();
        var pelvis = List.of("darktyrannomon", "pelvis");

        // Planted: the left foot lands at phase 0 and is flat from 12 % to 78 % of its stance (duty .56 walking, .40 running).
        for (float run : new float[]{0, 1}) {
            double pace = (run == 0 ? gait.stride() : gait.runStride()) * scale / cycle;
            float duty = run == 0 ? .56F : .40F;
            state.groundAnimationAmount = 1; state.groundRunAmount = run; state.ageInTicks = 0;
            var ankle = List.of("darktyrannomon", "pelvis", "thigh_L", "shin_L", "foot_L");
            float from = .14F * duty * cycle, until = .76F * duty * cycle;
            state.groundAnimationPhase = from; model.setupAnim(state);
            Vec3 start = pivot(root, ankle, scale);
            for (float t = from; t <= until; t += .125F) {
                state.groundAnimationPhase = t; model.setupAnim(state);
                Vec3 at = pivot(root, ankle, scale);
                Vec3 expected = start.subtract(0, 0, pace * (t - from));
                double error = at.distanceTo(expected);
                worstPlant = Math.max(worstPlant, error);
                check(error < .012, (run == 0 ? "walk" : "run") + " ankle slides at " + t + ": " + error);
            }
        }

        // The seat.
        state.isBeingRidden = true; state.groundAnimationAmount = 0; state.groundRunAmount = 0; state.ageInTicks = 0;
        state.mountAnchor = mount.position(0);
        Vec3 seated = model.riderOffset(state);
        check(seated.length() < .06, "at rest the rider is drawn at the sheet's seat: " + seated);
        check(Math.abs(model.riderYaw(state)) < 2, "at rest the rider faces the mount's way: " + model.riderYaw(state));
        var crest = part(root, List.of("darktyrannomon", "pelvis", "torso", "neck", "crest_03"));
        check(!crest.visible, "the feather under the seat is hidden while ridden");
        double walkDrift = 0, runDrift = 0, walkYaw = 0;
        state.groundAnimationAmount = 1;
        for (float t = 0; t < cycle; t += .5F) {
            state.groundAnimationPhase = t; state.groundRunAmount = 0;
            walkDrift = Math.max(walkDrift, model.riderOffset(state).length()); walkYaw = Math.max(walkYaw, Math.abs(model.riderYaw(state)));
            state.groundRunAmount = 1;
            runDrift = Math.max(runDrift, model.riderOffset(state).length());
        }
        check(walkDrift < .25 && walkYaw < 8, "walking the rider rides the bob: " + walkDrift + " blocks, " + walkYaw + " degrees");
        check(runDrift < .65, "running the lean carries the rider forward, no further: " + runDrift);
        state.isBeingRidden = false; state.groundAnimationAmount = 0; model.setupAnim(state);
        check(crest.visible, "unridden the feather is drawn");

        // Iron Tail: drawn tail against the server's contact points, while the attack has the whole pose (after its blend-in,
        // before its blend-out), and the half turn carries the rider round.
        var tail = species.attacks().stream().filter(a -> a.id().getPath().equals("iron_tail")).findFirst().orElseThrow();
        state.attackDefinition = tail; state.attackAnimationName = "iron_tail"; state.attackAnimation.start(0);
        var base = List.of("darktyrannomon", "pelvis", "tail_01", "tail_02");
        var tip = List.of("darktyrannomon", "pelvis", "tail_01", "tail_02", "tail_03", "tail_04");
        double lowest = Double.MAX_VALUE;
        float spun = 0;
        for (float t = definition.attackBlendIn(); t <= tail.durationTicks() - definition.attackBlendOut(); t += .5F) {
            state.ageInTicks = t; state.isBeingRidden = true; state.mountAnchor = mount.position(0);
            model.riderOffset(state);
            if (t >= 28 && t <= 36) spun = Math.max(spun, Math.abs(model.riderYaw(state)));
            var frame = tail.motion().sample(t);
            double e = Math.max(pivot(root, base, scale).distanceTo(frame.hornBase()), pivot(root, tip, scale).distanceTo(frame.hornTip()));
            worstTail = Math.max(worstTail, e);
            check(e < .02, "iron tail drawn apart from where it strikes at " + t + ": " + e);
            if (t >= tail.motion().activeFrom() && t <= tail.motion().activeUntil())
                lowest = Math.min(lowest, pivot(root, List.of("darktyrannomon", "pelvis", "tail_01", "tail_02", "tail_03", "tail_04", "tail_05", "tail_06"), scale).y);
        }
        check(lowest < 1.1, "the tail sweeps low enough for a small body: its sixth joint at " + lowest);
        check(spun > 100, "the half turn carries the rider round with the body: " + spun + " degrees");
        System.out.printf(Locale.ROOT, "DarkTyrannomon native checks passed: %d checks, planted ankle within %.4f blocks, tail within %.4f, rider turned %.0f degrees, tail joint down to %.2f blocks%n",
                checks, worstPlant, worstTail, spun, lowest);
    }
}
