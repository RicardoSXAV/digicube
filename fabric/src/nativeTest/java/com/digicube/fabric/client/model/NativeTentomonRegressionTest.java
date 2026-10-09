package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.KineticAttacks;
import com.digicube.entity.ai.FlightPhase;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.digicube.fabric.client.render.TectonicWaveRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Tentomon's installed clips played through the compiled NativeGroundModel and its flight pose (FlightPose).
 *
 * <p>On the ground: the idle keeps both feet planted; no claw under a foot goes under the ground in any walk column,
 * between columns or stepping round in either pivot. In the air every phase poses finitely, the membranes show only off
 * the ground and the coronas only through Petit Thunder, and from the landing's touchdown both feet stand on the
 * ground. Twice Arm: on the ground and on the wing the drawn claw points are where the move's motion puts them (the
 * server's markers). Petit Thunder: the effect's shock, drawn in the body's root frame, is where the shot leaves (the
 * kinetic motion's muzzle).
 */
public final class NativeTentomonRegressionTest {
    private static final float SCALE = .2F;
    private static final double TOLERANCE = .006;
    /** A planted claw at a column keeps within a third of a model px of the ground; between columns (angles mixed by
     * weight) a little more: 0.75 model px. */
    private static final double COLUMN_DIP = .33 * SCALE / 16, BLEND_DIP = .75 * SCALE / 16;
    /** Under a foot, in its own frame (model px): the sole's plane and the claws' reach from its middle. */
    private static final float SOLE = 26F, CLAW = 22F;
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
    private static void near(Vec3 expected, Vec3 actual, double tolerance, String label) {
        double error = expected.distanceTo(actual); worst = Math.max(worst, error); checks++;
        if (error > tolerance) throw new AssertionError(label + " error=" + error + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** The lowest of a foot's claw points and sole rim, relative to the ground (blocks). */
    private static double soleHeight(ModelPart root, List<String> path, Vec3 origin) {
        double low = Double.MAX_VALUE;
        for (float[] p : new float[][]{{CLAW, 0}, {-CLAW, 0}, {0, CLAW}, {0, -CLAW}, {8, 8}, {-8, 8}, {8, -8}, {-8, -8}})
            low = Math.min(low, point(root, path, p[0], SOLE, p[1], origin).y - origin.y);
        return low;
    }
    private static boolean visible(ModelPart root, List<String> path) {
        ModelPart part = root;
        for (String name : path) { part = part.getChild(name); if (!part.visible) return false; }
        return true;
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("tentomon"));
        check(species.body().modelScale() == SCALE, "exported and installed at one scale");
        var gait = species.locomotion().groundGait();
        check(gait != null && gait.pivotReach() > 0, "a planted walk that pivots");
        var definition = NativeGroundModel.definitions().get(species.id());
        check(definition != null && definition.flight() != null, "the catalog model flies (its flight block)");
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var paths = new HashMap<String, List<String>>();
        for (var part : NativeModelGeometry.mesh(definition.geometry()).parts()) paths.put(part.name(), List.of(part.path()));
        var state = new DigimonRenderState(); state.modelScale = SCALE;
        Vec3 origin = new Vec3(3, 64, -7);
        ground(model, root, state, paths, origin, gait.cycleTicks());
        air(model, root, state, paths, origin);
        claws(model, root, state, paths, origin);
        thunder(model, root, state, paths);
        Constants.LOG.info("Tentomon native checks passed: {} checks, worst error {} blocks", checks, String.format("%.4f", worst));
    }

    private static void reset(DigimonRenderState s) {
        s.flightPhase = FlightPhase.GROUNDED; s.groundAnimationAmount = 0; s.attackAnimation.stop(); s.attackAnimationName = null;
        s.flightCruise = s.flightDash = s.flightDive = s.flightBrake = s.flightBank = s.flightPitch = s.flightPower = s.swimRoll = 0;
        s.pouncePitch = 0; s.attackAir = false; s.pivotTurn = 0; s.flightLandingProgress = 0;
    }

    private static final String[] FEET = {"leg_foot_l", "leg_foot_r"};

    private static void ground(NativeGroundModel model, ModelPart root, DigimonRenderState s, Map<String, List<String>> paths, Vec3 origin, float cycle) {
        reset(s);
        s.ageInTicks = 0; model.setupAnim(s);
        Vec3[] rest = {point(root, paths.get(FEET[0]), 0, SOLE, 0, origin), point(root, paths.get(FEET[1]), 0, SOLE, 0, origin)};
        for (int t = 0; t < 100; t++) {
            s.ageInTicks = t; model.setupAnim(s);
            // planted: the foot never slides, and as the body breathes and sways over it it rolls on its claws, its lowest
            // claw on the ground
            for (int f = 0; f < 2; f++) {
                Vec3 at = point(root, paths.get(FEET[f]), 0, SOLE, 0, origin);
                near(new Vec3(rest[f].x, 0, rest[f].z), new Vec3(at.x, 0, at.z), TOLERANCE, FEET[f] + " stands still through the idle at " + t);
                check(Math.abs(soleHeight(root, paths.get(FEET[f]), origin)) < COLUMN_DIP, FEET[f] + " stands on its claws through the idle at " + t);
            }
            check(!visible(root, paths.get("wing_l")) && !visible(root, paths.get("wing_r")), "the membranes are folded away standing");
        }
        double deepest = 0;
        for (float amount : new float[]{.125F, .25F, .4F, .5F, .75F, .9F, 1}) {
            boolean column = amount == .25F || amount == .5F || amount == .75F || amount == 1;
            s.groundAnimationAmount = amount;
            for (float phase = 0; phase < cycle; phase += .25F) {
                s.groundAnimationPhase = phase; s.ageInTicks = 0; model.setupAnim(s);
                for (String foot : FEET) {
                    double h = soleHeight(root, paths.get(foot), origin);
                    deepest = Math.min(deepest, h);
                    check(h > (column ? -COLUMN_DIP : -BLEND_DIP), foot + " stays over the ground walking at " + amount + " phase " + phase + ": " + h);
                }
            }
        }
        Constants.LOG.info("Tentomon walk: deepest claw {} blocks", String.format("%.4f", deepest));
        for (float turn : new float[]{-1, -.5F, .5F, 1}) {
            s.groundAnimationAmount = .3F; s.pivotTurn = turn; s.ageInTicks = 0;
            for (float phase = 0; phase < cycle; phase += .25F) {
                s.groundAnimationPhase = phase; model.setupAnim(s);
                for (String foot : FEET) {
                    double h = soleHeight(root, paths.get(foot), origin);
                    check(h > (Math.abs(turn) == 1 ? -COLUMN_DIP : -BLEND_DIP), foot + " stays over the ground pivoting " + turn + " phase " + phase + ": " + h);
                }
            }
        }
        s.pivotTurn = 0;
    }

    private static void air(NativeGroundModel model, ModelPart root, DigimonRenderState s, Map<String, List<String>> paths, Vec3 origin) {
        reset(s);
        for (FlightPhase phase : FlightPhase.values()) {
            s.flightPhase = phase;
            for (float t = 0; t <= 30; t += .5F) {
                s.flightPhaseTime = t; s.flightLandingProgress = Math.min(1, t / 20); s.ageInTicks = t; s.wingClock = t * 1.3F;
                s.groundAnimationAmount = .5F; s.groundAnimationPhase = t;
                model.setupAnim(s);
                root.getAllParts().forEach(p -> check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot + p.xScale + p.yScale + p.zScale), "finite pose in " + phase));
                boolean wings = visible(root, paths.get("wing_l")) && visible(root, paths.get("wing_r"));
                if (phase == FlightPhase.FLYING || phase == FlightPhase.APPROACH) check(wings, "the membranes beat flying (" + phase + " " + t + ")");
                check(!visible(root, paths.get("pt_corona_l_crown")), "no corona outside Petit Thunder");
            }
        }
        // every posture mix with the wing layers poses finitely, the membranes showing
        s.flightPhase = FlightPhase.FLYING; s.flightPhaseTime = 40;
        float[][] shares = {{0, 0, 0, 0}, {1, 0, 0, 0}, {1, 1, 0, 0}, {1, 0, 1, 0}, {0, 0, 0, 1}, {.5F, .5F, .3F, .2F}};
        for (float[] mix : shares) {
            s.flightCruise = mix[0]; s.flightDash = mix[1]; s.flightDive = mix[2]; s.flightBrake = mix[3]; s.flightPower = mix[1];
            for (float t = 0; t < 40; t += 1.25F) {
                s.ageInTicks = t; s.wingClock = t * 1.4F; model.setupAnim(s);
                root.getAllParts().forEach(p -> check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot), "finite flying pose"));
                check(visible(root, paths.get("wing_l")), "the membranes show in every posture");
            }
        }
        // from the touchdown both feet stand on the ground while the membranes furl and the carapace shuts
        reset(s);
        s.flightPhase = FlightPhase.LANDING;
        for (float t = 0; t <= 16; t += .5F) {
            s.flightPhaseTime = t; s.ageInTicks = t; s.wingClock = t; model.setupAnim(s);
            for (String foot : FEET) check(Math.abs(soleHeight(root, paths.get(foot), origin)) < .012, foot + " on the ground landing at " + t);
        }
        check(!visible(root, paths.get("wing_l")), "the membranes are put away by the landing's end");
    }

    /** Twice Arm's drawn claw points against the move's motion (the server's markers), on the ground and on the wing. */
    private static void claws(NativeGroundModel model, ModelPart root, DigimonRenderState s, Map<String, List<String>> paths, Vec3 origin) {
        var attack = DigimonSpeciesRegistry.getOrThrow(Constants.id("tentomon")).attacks().stream()
                .filter(a -> a.id().getPath().equals("twice_arm")).findFirst().orElseThrow();
        var motion = attack.motion();
        for (boolean air : new boolean[]{false, true}) {
            reset(s);
            s.flightPhase = air ? FlightPhase.FLYING : FlightPhase.GROUNDED; s.flightPhaseTime = 40; s.attackAir = air;
            s.attackAnimationName = "twice_arm";
            for (float tick = 4; tick <= 14; tick += 1) {
                s.attackAnimation.start(0);
                s.ageInTicks = tick; s.wingClock = tick;
                model.setupAnim(s);
                var frame = motion.sample(tick);
                String side = tick < 9.2F ? "l" : "r";
                Vec3 drawn = point(root, paths.get("main_forearm_" + side), 0, 60, 0, origin);
                Vec3 marker = origin.add(frame.hornTip());
                // the clip is played inside its blends: compare only where it plays whole
                if (tick >= 2 && tick <= attack.durationTicks() - 4)
                    near(marker, drawn, .02, "the " + side + " claw " + (air ? "on the wing" : "on the ground") + " at " + tick);
                if (air) check(visible(root, paths.get("wing_l")), "the membranes keep beating through a cut on the wing");
            }
        }
    }

    /** Petit Thunder's shock drawn in the root's frame where the kinetic motion's muzzle leaves. */
    private static void thunder(NativeGroundModel model, ModelPart root, DigimonRenderState s, Map<String, List<String>> paths) {
        var attack = DigimonSpeciesRegistry.getOrThrow(Constants.id("tentomon")).attacks().stream()
                .filter(a -> a.id().getPath().equals("petit_thunder")).findFirst().orElseThrow();
        var kinetic = KineticAttacks.get(attack);
        var fxMesh = NativeModelGeometry.mesh(Constants.id("models/entity/tentomon_fx.mesh.json"));
        float[] shock = null;
        for (var part : fxMesh.parts()) if (part.name().equals("pt_shock_core")) shock = part.pose();
        check(shock != null, "the effect carries the shock");
        reset(s);
        s.flightPhase = FlightPhase.FLYING; s.flightPhaseTime = 40; s.attackAir = true; s.attackAnimationName = "petit_thunder";
        Vec3 origin = Vec3.ZERO;
        for (float tick : new float[]{16, 18, attack.hitTick()}) {
            s.attackAnimation.start(0);
            s.ageInTicks = tick; s.wingClock = tick;
            model.setupAnim(s);
            check(visible(root, paths.get("pt_corona_l_crown")) && visible(root, paths.get("pt_corona_r_crown")), "both coronas burn through the gather at " + tick);
            // the effect's root is the body's drawn root; the shock sits at its own place in that frame
            var r = s.drawnRoot;
            var m = new org.joml.Matrix4f().translate(r[0] / 16F, r[1] / 16F, r[2] / 16F).rotate(new org.joml.Quaternionf().rotationZYX(r[5], r[4], r[3]));
            var local = m.transformPosition(shock[0] / 16F, shock[1] / 16F, shock[2] / 16F, new org.joml.Vector3f());
            var stack = stack(origin); stack.last().pose().transformPosition(local);
            Vec3 drawn = new Vec3(local.x, local.y, local.z);
            Vec3 muzzle = kinetic.motion().sample(tick).muzzle();
            near(muzzle, drawn, .03, "the shock at " + tick + " where the shot leaves");
        }
    }
}
