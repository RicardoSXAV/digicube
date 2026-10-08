package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.digicube.fabric.client.render.TectonicWaveRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Elecmon's installed clips played through the compiled NativeGroundModel.
 *
 * <p>The idle keeps every standing paw where it stands, on the ground (the hind paws all through, the forepaws until
 * he sits up and again once he is down); no paw goes under the ground in any gait column, at full amplitude or between
 * columns; its blinks are the idle's texture windows.
 *
 * <p>Sparkling Thunder's bolt leaves where the model draws the ball over the fan (the motion's mouth marker at the hit
 * tick). Nine Tails' struck volumes hold every tail's drawn end through the hit window, all round him; the whirl is a
 * whole turn, half way round mid-whirl, and back at zero turn once he stands, so its blend-out never winds him back.
 */
public final class NativeElecmonRegressionTest {
    private static final float SCALE = .3F;
    /** Blocks; 0.2 model px at this scale. The installed curves are reduced within 2e-4 rad, far inside it. */
    private static final double TOLERANCE = .004;
    /** Between two lattice columns a mixed paw may dip a little: half a model pixel. */
    private static final double BLEND_DIP = .5 * SCALE / 16;
    /** The sole in the paw's own frame (model px): its plane, its middle, its heel and toe lines and half its width. */
    private static final float SOLE = 3.5F, MIDDLE = -3, HEEL = 3.5F, TOE = -9.66F, HALF = 6;
    private static final String[] PAWS = {"forepaw_L", "forepaw_R", "hindpaw_L", "hindpaw_R"};
    private static int checks;
    private static double worst;

    private static PoseStack stack(Vec3 origin) {
        var s = new PoseStack(); s.translate(origin.x, origin.y, origin.z);
        TectonicWaveRenderer.applyWorldTransform(s, 0, SCALE);
        s.translate(0, EntityModel.MODEL_Y_OFFSET, 0); return s;
    }
    private static Vec3 point(ModelPart root, List<String> path, float x, float y, float z, Vec3 origin) {
        var s = stack(origin);
        ModelPart part = root; part.translateAndRotate(s);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(s); }
        var v = s.last().pose().transformPosition(x / 16F, y / 16F, z / 16F, new org.joml.Vector3f());
        return new Vec3(v.x, v.y, v.z);
    }
    private static void near(Vec3 expected, Vec3 actual, String label) {
        double error = expected.distanceTo(actual); worst = Math.max(worst, error); checks++;
        if (error > TOLERANCE) throw new AssertionError(label + " error=" + error + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** The lowest of a paw's sole corners, blocks above the ground. */
    private static double soleHeight(ModelPart root, List<String> path, Vec3 origin) {
        double low = Double.MAX_VALUE;
        for (float x : new float[]{-HALF, HALF}) for (float z : new float[]{HEEL, TOE})
            low = Math.min(low, point(root, path, x, SOLE, z, origin).y - origin.y);
        return low;
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("elecmon"));
        check(species.body().modelScale() == SCALE, "exported and installed at one scale");
        check(species.attacks().size() == 2, "Sparkling Thunder and Nine Tails");
        var gait = species.locomotion().groundGait();
        check(gait != null && gait.directional() && gait.pivotReach() > 0, "a directional planted gait that pivots");
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var paths = new HashMap<String, List<String>>();
        for (var part : NativeModelGeometry.mesh(definition.geometry()).parts()) paths.put(part.name(), List.of(part.path()));
        var state = new DigimonRenderState(); state.modelScale = SCALE;
        Vec3 origin = new Vec3(3, 64, -7);

        idle(model, root, state, paths, origin);
        gaits(model, root, state, paths, origin, gait.cycleTicks());
        attacks(species, model, root, state, paths, origin);
        System.out.printf(Locale.ROOT, "Elecmon native checks passed: %d checks, worst planted-paw error %.5f blocks%n", checks, worst);
    }

    /** The idle: standing paws stay put on the ground; the blinks are the idle's own texture windows. */
    private static void idle(NativeGroundModel model, ModelPart root, DigimonRenderState state, Map<String, List<String>> paths, Vec3 origin) {
        state.groundAnimationAmount = 0;
        Map<String, Vec3> planted = new HashMap<>();
        for (float t = 0; t < 160; t += .5F) {
            state.ageInTicks = t; model.setupAnim(state);
            for (String paw : PAWS) {
                // the forepaws leave the ground while he sits up (40 to 112)
                boolean standing = paw.startsWith("hind") || t <= 40 || t >= 112;
                if (!standing) { planted.remove(paw + (t < 76 ? "a" : "b")); continue; }
                String key = paw + (t <= 40 ? "a" : "b");
                Vec3 p = point(root, paths.get(paw), 0, SOLE, MIDDLE, origin);
                Vec3 first = planted.computeIfAbsent(key, k -> p);
                near(first, p, "idle " + paw + " at " + t);
                check(Math.abs(p.y - origin.y) < TOLERANCE, "idle " + paw + " on the ground at " + t + ": " + (p.y - origin.y));
            }
        }
        // the rest of the hind paws' standing: the same place before and after he sat up
        near(planted.get("hindpaw_La"), planted.getOrDefault("hindpaw_Lb", planted.get("hindpaw_La")), "the left hind paw never moved");
        // blinks
        state.ageInTicks = 25; check(model.texture(state).getPath().endsWith("elecmon_blink.png"), "he blinks at 25");
        state.ageInTicks = 50; check(model.texture(state).getPath().endsWith("elecmon.png"), "his eyes are open at 50");
        state.ageInTicks = 160 * 3 + 73; check(model.texture(state).getPath().endsWith("elecmon_blink.png"), "the blinks loop with the idle");
    }

    /** No paw under the ground in any column, at full amplitude and between columns. */
    private static void gaits(NativeGroundModel model, ModelPart root, DigimonRenderState state, Map<String, List<String>> paths, Vec3 origin, float cycle) {
        float[][] shares = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
        String[] names = {"walk", "walk_back", "strafe_left", "strafe_right"};
        for (float amount : new float[]{1, .7F, .5F}) {
            double allowed = amount == 1 ? TOLERANCE : BLEND_DIP;
            for (int g = 0; g < 4; g++) for (float t = 0; t < cycle; t += .25F) {
                state.groundAnimationAmount = amount; state.groundRunAmount = 0; state.gaitShares = shares[g];
                state.pivotTurn = 0; state.groundAnimationPhase = t; state.ageInTicks = t; model.setupAnim(state);
                for (String paw : PAWS) check(soleHeight(root, paths.get(paw), origin) > -allowed,
                        names[g] + " at " + amount + ": " + paw + " under the ground at " + t);
            }
            for (float turn : new float[]{1, -1}) for (float t = 0; t < cycle; t += .25F) {
                state.groundAnimationAmount = amount; state.groundRunAmount = 0; state.gaitShares = shares[0];
                state.pivotTurn = turn; state.groundAnimationPhase = t; model.setupAnim(state);
                for (String paw : PAWS) check(soleHeight(root, paths.get(paw), origin) > -allowed,
                        "pivot " + turn + " at " + amount + ": " + paw + " under the ground at " + t);
            }
        }
        for (float t = 0; t < cycle; t += .25F) {
            state.groundAnimationAmount = 1; state.groundRunAmount = 1; state.gaitShares = shares[0]; state.pivotTurn = 0;
            state.groundAnimationPhase = t; model.setupAnim(state);
            for (String paw : PAWS) check(soleHeight(root, paths.get(paw), origin) > -TOLERANCE, "run: " + paw + " under the ground at " + t);
        }
        state.groundRunAmount = 0; state.pivotTurn = 0; state.groundAnimationAmount = 0;
    }

    /** A tail's drawn end, in its tip part's frame (model px): its tip block's point. */
    private static float tailEnd(int k) { return -(14 - Math.abs(k)); }

    private static void attacks(com.digicube.digimon.DigimonSpecies species, NativeGroundModel model, ModelPart root, DigimonRenderState state,
                                Map<String, List<String>> paths, Vec3 origin) {
        var thunder = species.attacks().stream().filter(a -> a.id().getPath().equals("sparkling_thunder")).findFirst().orElseThrow();
        var whirl = species.attacks().stream().filter(a -> a.id().getPath().equals("nine_tails")).findFirst().orElseThrow();
        state.groundAnimationAmount = 0;

        // Sparkling Thunder: the bolt leaves the ball the model draws over the fan, high over his head
        state.attackDefinition = thunder; state.attackAnimationName = "sparkling_thunder"; state.attackAnimation.start(0);
        float hit = thunder.hitTick();
        state.ageInTicks = hit; model.setupAnim(state);
        Vec3 ball = point(root, paths.get("tail_05_tip"), 0, -19, 0, origin).subtract(origin);
        near(thunder.motion().sample(hit).mouth(), ball, "the bolt's source on the drawn ball");
        check(ball.y > 1.0, "the ball rides over his head: " + ball.y + " blocks up");
        check(com.digicube.digimon.AuthoredAttacks.get(thunder).discharges(), "a discharge");

        // Nine Tails: every tail's end inside its volume through the window, and the turn whole and undone
        var authored = com.digicube.digimon.AuthoredAttacks.get(whirl);
        state.attackDefinition = whirl; state.attackAnimationName = "nine_tails"; state.attackAnimation.start(0);
        double[] window = authored.hitWindows().getFirst();
        Set<Integer> sides = new HashSet<>();
        for (double t = window[0]; t <= window[1]; t += .5) {
            state.ageInTicks = (float) t; model.setupAnim(state);
            var boxes = authored.sample(t);
            for (int i = 0; i < 9; i++) {
                int k = i - 4;
                Vec3 end = point(root, paths.get(String.format(Locale.ROOT, "tail_%02d_tip", i + 1)), 0, tailEnd(k), 0, origin).subtract(origin);
                var box = boxes[i];
                Vec3 d = end.subtract(box.center());
                check(Math.abs(d.dot(box.x())) <= box.x().lengthSqr() + 1e-6 && Math.abs(d.dot(box.y())) <= box.y().lengthSqr() + 1e-6
                        && Math.abs(d.dot(box.z())) <= box.z().lengthSqr() + 1e-6, "tail " + (i + 1) + "'s end inside its volume at " + t);
                sides.add((int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(end.x, end.z)) / 90), 4));
            }
        }
        check(sides.size() == 4, "the tails sweep all round him: quarters " + sides);
        state.ageInTicks = 6.3F; model.setupAnim(state);
        check(Math.abs(Math.abs(root.getChild("root").yRot) - Math.PI) < .35, "half way round mid-whirl: " + root.getChild("root").yRot);
        // the turn ends whole on a key and steps back to zero a thousandth of a tick later: the same pose, and the attack's
        // clock (whole milliseconds, a fiftieth of a tick) never reads a time between the two
        state.ageInTicks = 9.24F; model.setupAnim(state);
        check(Math.abs(Math.abs(root.getChild("root").yRot) - 2 * Math.PI) < .01, "all but a whole turn just before he lands: " + root.getChild("root").yRot);
        for (float t : new float[]{9.26F, 9.5F, 12, 15}) {
            state.ageInTicks = t; model.setupAnim(state);
            check(Math.abs(root.getChild("root").yRot) < 1e-3, "back at zero turn once he stands, at " + t + ": " + root.getChild("root").yRot);
        }
        state.attackDefinition = null; state.attackAnimationName = null; state.attackAnimation.stop();
    }
}
