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
 * <li>turning on the spot (the pivot clips, plain, on the walk's phase), each standing foot stays put on the ground as the
 *     body turns over it at the sheet's pivot stride, and the turn keeps the pivot's cadence while it gathers;</li>
 * <li>Iron Tail: the tail the client draws is the tail the server strikes with (the motion's contact points, the tail_02
 *     and tail_04 pivots), it sweeps low enough to strike a small body, it turns its rider with the body's half turn, and
 *     no tail joint twitches through the move or swings out through the attack's blend-out into the idle.</li>
 * </ul>
 */
public final class NativeDarkTyrannomonRegressionTest {
    private static int checks;
    private static double worstPlant, worstPivot, worstTail, tailJump, tailOvershoot;

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

        // Turning on the spot (pivot_left / pivot_right, plain clips on the walk's phase): the body goes round its centre
        // on the phase the entity pays the turn with (DigimonGait.pivotTravel), and each standing foot stays put on the
        // ground, its ankle read back in the world as the body turns over it, flat (the ankle at its standing height).
        var feet = List.of(List.of("darktyrannomon", "pelvis", "thigh_L", "shin_L", "foot_L"), List.of("darktyrannomon", "pelvis", "thigh_R", "shin_R", "foot_R"));
        int pivotSteps = 128;
        for (int way : new int[]{1, -1}) {
            state.groundAnimationAmount = 1; state.groundRunAmount = 0; state.pivotTurn = way; state.ageInTicks = 0;
            float degrees = 4;
            // degrees the body turns a tick of the clip (the yaw rises turning right)
            double perPhase = way * degrees / gait.advance(gait.pivotTravel(degrees, 0), 1, scale, 0);
            double dt = cycle / pivotSteps;
            for (int f = 0; f < feet.size(); f++) {
                var world = new Vec3[pivotSteps + 1];
                double[] height = new double[pivotSteps + 1];
                double lowest = Double.MAX_VALUE;
                for (int i = 0; i <= pivotSteps; i++) {
                    state.groundAnimationPhase = (float) (i * dt); model.setupAnim(state);
                    Vec3 ankle = pivot(root, feet.get(f), scale);
                    double yaw = Math.toRadians(perPhase * i * dt), cos = Math.cos(yaw), sin = Math.sin(yaw);
                    world[i] = new Vec3(ankle.x * cos - ankle.z * sin, ankle.y, ankle.x * sin + ankle.z * cos);
                    height[i] = ankle.y;
                    lowest = Math.min(lowest, ankle.y);
                }
                String foot = (f == 0 ? "left" : "right") + " foot turning " + (way > 0 ? "right" : "left");
                int standing = 0;
                for (int i = 3; i <= pivotSteps - 2; i++) {
                    boolean down = true;
                    for (int j = i - 2; j <= i + 1; j++) down &= height[j] <= lowest + .004;
                    if (!down) continue;
                    standing++;
                    double moved = world[i].subtract(world[i - 1]).horizontalDistance();
                    worstPivot = Math.max(worstPivot, moved);
                    check(moved < .004, "the " + foot + " slides at phase " + i * dt + ": " + moved);
                }
                check(standing >= pivotSteps / 2, "the " + foot + " stands between its steps: " + standing + " of " + pivotSteps);
            }
            state.pivotTurn = 0;
        }
        // A turn on the spot gathers and brakes over as long as the gait's stride takes to grow: the pivot keeps its cadence
        // all through, never fluttering its feet while its stride is still small.
        {
            float rate = gait.pivotTurnRate(scale), speed = 0, facing = 0, amount = 0, cadence = 0;
            for (int tick = 0; tick < 60; tick++) {
                speed = com.digicube.entity.ai.SteadyBodyControl.ease(speed, 150 - facing, rate);
                facing += speed;
                double travel = gait.pivotTravel(speed, 0);
                amount = net.minecraft.util.Mth.approach(amount, (float) Math.min(1, travel / gait.fullSpeed(scale)), com.digicube.entity.DigimonEntity.AMPLITUDE_EASE);
                if (travel > 1.0E-6) cadence = Math.max(cadence, gait.advance(travel, amount, scale, 0));
            }
            check(Math.abs(facing - 150) < .01F, "a turn on the spot ends where it was going: " + facing);
            check(cadence <= gait.pivotCadence() + .02F, "a turn on the spot keeps its pivot's cadence while it gathers: " + cadence);
            check(rate > 5 && rate < 6, "standing, the body turns no faster than its pivot steps round: " + rate + " degrees a tick");
        }
        state.groundAnimationAmount = 0; state.groundAnimationPhase = 0;

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

        // The tail never twitches: from the wind-up to the end of the move, including the attack's blend-out into the idle,
        // no tail joint's turn from its rest changes more than a few degrees in a quarter tick, and through the blend-out
        // no joint turns further from rest than it was when the blend began (a clip whose keys had crossed the Euler lock
        // onto the far branch ended on deltas the mix with the idle's passed through a ninety-degree swing of the tail).
        var joints = List.of("tail_01", "tail_02", "tail_03", "tail_04", "tail_05", "tail_06", "tail_07");
        float blendFrom = tail.durationTicks() - definition.attackBlendOut();
        float[] before = null, atBlend = null;
        for (float t = 8; t <= tail.durationTicks(); t += .25F) {
            state.ageInTicks = t; model.setupAnim(state);
            float[] now = new float[joints.size()];
            ModelPart link = part(root, List.of("darktyrannomon", "pelvis"));
            for (int k = 0; k < joints.size(); k++) { link = link.getChild(joints.get(k)); now[k] = turnFromRest(link); }
            if (before != null) for (int k = 0; k < now.length; k++) {
                tailJump = Math.max(tailJump, Math.abs(now[k] - before[k]));
                check(Math.abs(now[k] - before[k]) < 10, joints.get(k) + " jumps " + Math.abs(now[k] - before[k]) + " degrees at " + t);
            }
            if (t >= blendFrom) {
                if (atBlend == null) atBlend = now.clone();
                for (int k = 0; k < now.length; k++) {
                    tailOvershoot = Math.max(tailOvershoot, now[k] - atBlend[k]);
                    check(now[k] - atBlend[k] < 3, joints.get(k) + " swings out through the blend-out at " + t + ": " + (now[k] - atBlend[k]) + " degrees past where the blend began");
                }
            }
            before = now;
        }
        System.out.printf(Locale.ROOT, "DarkTyrannomon native checks passed: %d checks, planted ankle within %.4f blocks, pivot ankle within %.4f, tail within %.4f, rider turned %.0f degrees, tail joint down to %.2f blocks, tail joints move at most %.1f degrees a quarter tick and %.1f past the blend-out's start%n",
                checks, worstPlant, worstPivot, worstTail, spun, lowest, tailJump, tailOvershoot);
    }

    /** Degrees a posed part is turned from its rest pose. */
    private static float turnFromRest(ModelPart part) {
        var pose = part.getInitialPose();
        var rest = new org.joml.Quaternionf().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot());
        var now = new org.joml.Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
        float dot = Math.abs(rest.dot(now));
        return (float) Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
    }
}
