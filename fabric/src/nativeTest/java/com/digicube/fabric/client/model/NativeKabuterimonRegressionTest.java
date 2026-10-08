package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.AttackMotion;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.PounceAttacks;
import com.digicube.entity.PounceLines;
import com.digicube.entity.ai.FlightPhase;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.digicube.fabric.client.render.TectonicWaveRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Kabuterimon's installed clips played through the compiled NativeGroundModel and its flight pose (FlightPose).
 *
 * <p>On the ground: the idle keeps both feet where they stand on the ground; no sole goes under the ground in any walk
 * column, between columns or stepping round in either pivot. In the air: every phase poses finitely and the rider's seat
 * stays where it rests through every posture and their mixes (the postures swing the body under the seat), and so it
 * does through the flight's pitch, bank and a whole barrel roll (all turned about the seat). The attacks: on the ground
 * and on the wing the clip the model plays puts the horn's tip where Beet Horn's motion strikes with it at every contact
 * tick, and on the wing, tipped along a ram's line, where the server's bite puts it ({@code PounceLines.tipped}).
 */
public final class NativeKabuterimonRegressionTest {
    private static final float SCALE = .45F;
    private static final double TOLERANCE = .006;
    /** At a column a sole keeps within a third of a model px of the ground (the reduced curves); between two columns a
     * planted foot (or a pivot mixed with the walk) may dip a little more: 0.75 model px at this scale. */
    private static final double COLUMN_DIP = .33 * SCALE / 16;
    private static final double BLEND_DIP = .75 * SCALE / 16;
    /** The sole in the foot's own frame (model px): its plane, toe and heel lines and half its width. */
    private static final float SOLE = 5F, TOE = -39F, HEEL = 15.5F, HALF = 12F;
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

    private static double soleHeight(ModelPart root, List<String> path, Vec3 origin) {
        double low = Double.MAX_VALUE;
        for (float x : new float[]{-HALF, HALF}) for (float z : new float[]{HEEL, TOE})
            low = Math.min(low, point(root, path, x, SOLE, z, origin).y - origin.y);
        return low;
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("kabuterimon"));
        check(species.body().modelScale() == SCALE, "exported and installed at one scale");
        check(species.attacks().size() == 2, "Beet Horn and Mega Blaster");
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
        horn(model, root, state, paths, origin);
        Constants.LOG.info("Kabuterimon native checks passed: {} checks, worst error {} blocks", checks, String.format("%.4f", worst));
    }

    private static void reset(DigimonRenderState s) {
        s.flightPhase = FlightPhase.GROUNDED; s.groundAnimationAmount = 0; s.attackAnimation.stop(); s.attackAnimationName = null;
        s.flightCruise = s.flightDash = s.flightDive = s.flightBrake = s.flightBank = s.flightPitch = s.flightPower = s.swimRoll = 0;
        s.pouncePitch = 0; s.attackAir = false; s.isBeingRidden = false;
    }

    private static void ground(NativeGroundModel model, ModelPart root, DigimonRenderState s, Map<String, List<String>> paths, Vec3 origin, float cycle) {
        reset(s);
        model.setupAnim(s);
        Vec3[] rest = {point(root, paths.get("foot_l"), 0, SOLE, 0, origin), point(root, paths.get("foot_r"), 0, SOLE, 0, origin)};
        for (int t = 0; t < 120; t++) {
            s.ageInTicks = t; model.setupAnim(s);
            near(rest[0], point(root, paths.get("foot_l"), 0, SOLE, 0, origin), TOLERANCE, "idle left foot stands at t=" + t);
            near(rest[1], point(root, paths.get("foot_r"), 0, SOLE, 0, origin), TOLERANCE, "idle right foot stands at t=" + t);
        }
        // at a column a sole never goes under the ground; mixed between two columns (their angles added by weight) a
        // planted sole may dip a little: BLEND_DIP
        double deepest = 0;
        for (float amount : new float[]{.125F, .25F, .4F, .5F, .75F, .9F, 1}) {
            boolean column = amount == .25F || amount == .5F || amount == .75F || amount == 1;
            s.groundAnimationAmount = amount;
            for (float phase = 0; phase < cycle; phase += .5F) {
                s.groundAnimationPhase = phase; s.ageInTicks = 0; model.setupAnim(s);
                for (String foot : new String[]{"foot_l", "foot_r"}) {
                    double h = soleHeight(root, paths.get(foot), origin);
                    deepest = Math.min(deepest, h);
                    check(h > (column ? -COLUMN_DIP : -BLEND_DIP), foot + " stays over the ground walking at " + amount + " phase " + phase + ": " + h);
                }
            }
        }
        Constants.LOG.info("Kabuterimon walk: deepest sole {} blocks", String.format("%.4f", deepest));
        for (float turn : new float[]{-1, -.5F, .5F, 1}) {
            s.groundAnimationAmount = .3F; s.pivotTurn = turn; s.ageInTicks = 0;
            for (float phase = 0; phase < cycle; phase += .5F) {
                s.groundAnimationPhase = phase; model.setupAnim(s);
                for (String foot : new String[]{"foot_l", "foot_r"}) {
                    double h = soleHeight(root, paths.get(foot), origin);
                    check(h > (Math.abs(turn) == 1 ? -COLUMN_DIP : -BLEND_DIP), foot + " stays over the ground pivoting " + turn + " phase " + phase + ": " + h);
                }
            }
        }
        s.pivotTurn = 0;
    }

    private static Vec3 seat(NativeGroundModel model, DigimonRenderState s) {
        s.mountAnchor = Vec3.ZERO;
        return model.riderOffset(s);
    }

    private static void air(NativeGroundModel model, ModelPart root, DigimonRenderState s, Map<String, List<String>> paths, Vec3 origin) {
        reset(s);
        s.flightPhase = FlightPhase.FLYING; s.flightPhaseTime = 40;
        Vec3 rest = seat(model, s);
        float[][] shares = {{0, 0, 0, 0}, {1, 0, 0, 0}, {.5F, 0, 0, 0}, {1, 1, 0, 0}, {1, .5F, 0, 0}, {1, 0, 1, 0}, {1, 0, .5F, 0}, {0, 0, 0, 1}, {.5F, .5F, .3F, .2F}};
        for (float[] mix : shares) {
            s.flightCruise = mix[0]; s.flightDash = mix[1]; s.flightDive = mix[2]; s.flightBrake = mix[3];
            for (float t = 0; t < 40; t += 2.5F) {
                s.ageInTicks = t; s.wingClock = t * 1.3F;
                Vec3 at = seat(model, s);
                near(rest, at, .4, "the seat stays put flying " + Arrays.toString(mix) + " at " + t);
                root.getAllParts().forEach(p -> check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot), "finite flying pose"));
            }
        }
        s.flightCruise = 1; s.flightDash = s.flightDive = s.flightBrake = 0;
        s.ageInTicks = 0; s.wingClock = 0;
        Vec3 cruise = seat(model, s);
        for (float pitch : new float[]{-40, 30, 75}) for (float bank : new float[]{-55, 0, 55}) for (float roll = 0; roll <= 360; roll += 45) {
            s.flightPitch = pitch; s.flightBank = bank; s.swimRoll = roll;
            near(cruise, seat(model, s), .05, "the body turns about the seat: pitch " + pitch + " bank " + bank + " roll " + roll);
        }
        reset(s);
        for (FlightPhase phase : FlightPhase.values()) {
            s.flightPhase = phase;
            for (float t = 0; t <= 30; t += .5F) {
                s.flightPhaseTime = t; s.flightLandingProgress = Math.min(1, t / 20); s.ageInTicks = t; s.wingClock = t;
                model.setupAnim(s);
                root.getAllParts().forEach(p -> check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot), "finite pose in " + phase));
            }
        }
        // a touchdown stands both feet on the ground (the land clip's contact onward)
        s.flightPhase = FlightPhase.LANDING;
        for (float t = 0; t <= 13; t += .5F) {
            s.flightPhaseTime = t; model.setupAnim(s);
            for (String foot : new String[]{"foot_l", "foot_r"})
                check(Math.abs(soleHeight(root, paths.get(foot), origin)) < .03, foot + " on the ground landing at " + t);
        }
    }

    /** Beet Horn's drawn horn tip at the snap, on the ground and on the wing, against the motion the server strikes with. */
    private static void horn(NativeGroundModel model, ModelPart root, DigimonRenderState s, Map<String, List<String>> paths, Vec3 origin) {
        var spec = PounceAttacks.get(DigimonSpeciesRegistry.getOrThrow(Constants.id("kabuterimon")).attacks().stream()
                .filter(PounceAttacks::handles).findFirst().orElseThrow());
        for (boolean air : new boolean[]{false, true}) {
            reset(s);
            var form = spec.forAir(air);
            check(form.airborne() == air, "Beet Horn has its wing form");
            s.flightPhase = air ? FlightPhase.FLYING : FlightPhase.GROUNDED;
            s.attackAir = air;
            s.attackAnimationName = "beet_horn";
            AttackMotion motion = form.attack().motion();
            for (float tick = form.attack().hitTick(); tick <= form.contactUntil(); tick += 1) {
                s.attackAnimation.start(0);
                s.ageInTicks = tick;
                model.setupAnim(s);
                var frame = motion.sample(tick);
                Vec3 marker = origin.add(frame.hornTip());
                // the tip's far end in the horn tip's own frame: the motion was measured there
                var tipPath = paths.get("horn_tip");
                Vec3 drawn = farthest(root, tipPath, origin);
                near(marker, drawn, .02, (air ? "wing" : "ground") + " horn tip at " + tick);
                if (!air) continue;
                // tipped along a ram's line the drawn tip is where the server's bite puts it (both turn about the seat)
                var seat = DigimonSpeciesRegistry.getOrThrow(Constants.id("kabuterimon")).body().mount().orElseThrow().seat();
                for (float pitch : new float[]{-35, 20}) {
                    s.pouncePitch = pitch;
                    model.setupAnim(s);
                    Vec3 bitten = origin.add(PounceLines.tipped(frame.hornTip(), new Vec3(0, seat.y, seat.z), 0, pitch));
                    near(bitten, farthest(root, tipPath, origin), .02, "wing horn tip tipped " + pitch + " at " + tick);
                }
                s.pouncePitch = 0;
            }
        }
    }

    /** The horn tip block's far end (its vertex furthest along its own length, from the mesh), in the world. */
    private static Vec3 farthest(ModelPart root, List<String> path, Vec3 origin) {
        var s = stack(origin);
        ModelPart part = root; part.translateAndRotate(s);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(s); }
        var pose = s.last().pose();
        // the middle of the tip block's far end (its far corners are many, the middle one point), as the motion takes it
        var definition = NativeGroundModel.definitions().get(Constants.id("kabuterimon"));
        var part0 = Arrays.stream(NativeModelGeometry.mesh(definition.geometry()).parts()).filter(p -> p.name().equals(path.getLast())).findFirst().orElseThrow();
        float far = -Float.MAX_VALUE;
        for (var quad : part0.quads()) for (var v : quad.vertices()) far = Math.max(far, v[1]);
        float x = 0, y = 0, z = 0; int n = 0;
        var seen = new HashSet<String>();
        for (var quad : part0.quads()) for (var v : quad.vertices())
            if (v[1] >= far - .01F && seen.add(v[0] + "," + v[1] + "," + v[2])) { x += v[0]; y += v[1]; z += v[2]; n++; }
        return toVec(pose.transformPosition(x / n / 16F, y / n / 16F, z / n / 16F, new org.joml.Vector3f()));
    }

    private static Vec3 toVec(org.joml.Vector3f v) { return new Vec3(v.x, v.y, v.z); }
}
