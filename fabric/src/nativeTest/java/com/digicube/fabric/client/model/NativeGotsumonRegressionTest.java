package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.AttackBox;
import com.digicube.entity.AttackGeometry;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Actual compiled renderer transforms versus native vertices and server contact volumes: the striking fist of both
 * punches, and the falling stone of a Comet Hammer drawn at its landing point instead of at the caster's feet.
 */
public final class NativeGotsumonRegressionTest {
    private static final float SCALE = .25F;
    /** What the punch's volume adds around the drawn hand: to each side, above and below, and ahead over its smear. */
    private static final double PUNCH_SIDE = .08, PUNCH_HEIGHT = .15, PUNCH_REACH = .35;
    /** Key reduction moves a vertex at the end of a six-joint chain by less than this many blocks. */
    private static final double TOLERANCE = .004;
    private static int checks;
    private static double worst;

    private static PoseStack stack(float yaw, Vec3 origin) {
        var s = new PoseStack(); s.translate(origin.x, origin.y, origin.z);
        TectonicWaveRenderer.applyWorldTransform(s, yaw, SCALE);
        s.translate(0, EntityModel.MODEL_Y_OFFSET, 0); return s;
    }
    private static Map<String, List<Vec3>> rendered(ModelPart root, float yaw, Vec3 origin) {
        Map<String, List<Vec3>> actual = new HashMap<>();
        root.visit(stack(yaw, origin), (pose, path, index, cube) -> {
            var vertices = actual.computeIfAbsent(path.substring(path.lastIndexOf('/') + 1), k -> new ArrayList<>());
            for (var polygon : cube.polygons) for (var vertex : polygon.vertices()) {
                var v = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new org.joml.Vector3f());
                vertices.add(new Vec3(v.x, v.y, v.z));
            }
        });
        return actual;
    }
    private static void near(Vec3 a, Vec3 b, String label) {
        double error = a.distanceTo(b); worst = Math.max(worst, error); checks++;
        if (error > TOLERANCE) throw new AssertionError(label + " error=" + error + " expected=" + a + " actual=" + b);
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** Every drawn vertex lies inside the server's cuboid, and the cuboid is no larger than what is drawn. */
    private static void encloses(AttackBox box, List<Vec3> vertices, String label) {
        encloses(box, vertices, new double[][]{{0, 0}, {0, 0}, {0, 0}}, label);
    }

    /** @param margins per axis, how far the cuboid is meant to extend past what is drawn, below and above */
    private static void encloses(AttackBox box, List<Vec3> vertices, double[][] margins, String label) {
        Vec3[] axes = {box.x(), box.y(), box.z()};
        for (int i = 0; i < 3; i++) {
            Vec3 axis = axes[i];
            double length = axis.length(), low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (Vec3 v : vertices) {
                double along = v.subtract(box.center()).dot(axis) / length;
                low = Math.min(low, along); high = Math.max(high, along);
            }
            double error = Math.max(Math.abs(high + margins[i][1] - length), Math.abs(low - margins[i][0] + length)); worst = Math.max(worst, error); checks++;
            if (error > TOLERANCE) throw new AssertionError(label + " drawn " + low + ".." + high + " against +-" + length);
        }
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("gotsumon"));
        check(species.body().modelScale() == SCALE, "exported and installed at one scale");
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = SCALE; state.attackAnimation.start(0);

        var punch = species.attacks().stream().filter(a -> a.id().getPath().equals("hardest_punch")).findFirst().orElseThrow();
        var comet = species.attacks().stream().filter(a -> a.id().getPath().equals("comet_hammer")).findFirst().orElseThrow();
        check(species.attacks().getFirst() == comet, "the special is tried before the punch that fills its cooldown");
        check(punch.alternateSides() && punch.kind() == DigimonAttack.Kind.BOX_SWEEP, "alternating authored fist");
        var authoredPunch = AuthoredAttacks.get(punch); var authoredComet = AuthoredAttacks.get(comet);
        check(!authoredPunch.anchored() && authoredPunch.contactParts().size() == 2, "the punch follows its caster; its star needs a hit");
        check(authoredComet.anchored() && authoredComet.anchorLockTick() == 15 && authoredComet.anchorLockTick() < authoredComet.hitWindows().getFirst()[0],
                "the landing point locks before the stone can hurt");
        var approach = authoredComet.anchorApproach();
        check(approach.y > 4 && approach.z < -2.5 && authoredComet.particles() == com.digicube.entity.StrikeParticles.STONE && authoredPunch.particles() == com.digicube.entity.StrikeParticles.STONE,
                "the stone gathers high above the floor, back toward its caster: " + approach);
        check(comet.motion().minimumRange() >= 2 && comet.range() > comet.motion().minimumRange(), "a summon is not a melee move");

        // The fist: every finger of the striking hand inside the damaging cuboid, both sides, all headings.
        for (boolean mirrored : new boolean[]{false, true}) {
            state.attackDefinition = punch; state.attackAnimationName = punch.animationName(mirrored);
            String hand = mirrored ? "hand_right" : "hand_left";
            // The drawn pose eases in from idle and back out (attack_blend_in / _out); between them it is the clip the server sweeps.
            for (float t = 2; t <= punch.durationTicks() - 3; t += .25F) {
                state.ageInTicks = t; model.setupAnim(state);
                float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F;
                for (int h = 0; h < 8; h++) for (int elevation = -1; elevation <= 1; elevation++) {
                    float yaw = h * 45; var origin = new Vec3(13, 80 + elevation, -19);
                    var vertices = new ArrayList<Vec3>();
                    rendered(root, yaw, origin).forEach((name, points) -> { if (name.startsWith(hand)) vertices.addAll(points); });
                    encloses(authoredPunch.sample(tick, false, mirrored)[0].world(origin, yaw, 0), vertices,
                            new double[][]{{PUNCH_SIDE, PUNCH_SIDE}, {PUNCH_HEIGHT, PUNCH_HEIGHT}, {0, PUNCH_REACH}}, hand + " " + t + " yaw " + yaw);
                }
            }
        }
        double reach = 0;
        for (double t = punch.motion().activeFrom(); t <= punch.motion().activeUntil(); t += .125)
            for (boolean mirrored : new boolean[]{false, true}) {
                var box = authoredPunch.sample(t, false, mirrored)[0];
                reach = Math.max(reach, box.center().z + Math.abs(box.x().z) + Math.abs(box.y().z) + Math.abs(box.z().z));
            }
        check(reach > species.body().dimensions().width() / 2 && reach < punch.range(), "the fist leaves the body and stays inside its range: " + reach);

        // The stone: drawn from the landing point, it fills the cuboid the server sweeps from the same point.
        var effectRoot = NativeEffectModel.createLayer(authoredComet.effect()).bakeRoot();
        var effect = new NativeEffectModel(effectRoot, authoredComet.effect());
        var fx = new NativeEffectState(); fx.scale = SCALE; fx.clip = "effect";
        int falling = 0;
        for (float t = 0; t <= comet.durationTicks(); t += .25F) {
            var boxes = authoredComet.sample(t);
            if (boxes[0] == null || authoredComet.sample(t + .25F)[0] == null || t < .25F || authoredComet.sample(t - .25F)[0] == null) continue;
            falling++;
            for (int h = 0; h < 8; h++) for (int elevation = -1; elevation <= 1; elevation++) {
                fx.tick = t; fx.yaw = h * 45; var landing = new Vec3(-7.5, 64 + elevation, 31.25);
                effect.setupAnim(fx);
                check(effectRoot.getChild("fx_comet_core_0").visible, "the damaging stone is the visible one at " + t);
                encloses(boxes[0].world(landing, fx.yaw, 0), rendered(effectRoot, fx.yaw, landing).get("fx_comet_core_0"), "stone " + t + " yaw " + fx.yaw);
            }
        }
        check(falling > 60, "the stone is followed down its whole fall");
        var burst = authoredComet.sample(comet.hitTick())[1];
        check(burst != null && burst.center().horizontalDistance() < 1e-6 && burst.bounds().minY > -1e-6 && burst.bounds().getXsize() <= 2.4,
                "the landing burst sits on the floor around the landing point, no wider than its dust");
        fx.tick = 16; effect.setupAnim(fx);
        check(effectRoot.getChild("fx_warning").visible && !effectRoot.getChild("fx_impact").visible, "warning after the lock, no burst before the landing");
        fx.tick = 14; effect.setupAnim(fx);
        check(!effectRoot.getChild("fx_warning").visible, "no warning while the landing point still follows the target");

        // The punch effect has a clip per hand.
        var punchEffect = new NativeEffectModel(NativeEffectModel.createLayer(authoredPunch.effect()).bakeRoot(), authoredPunch.effect());
        check(punchEffect.has("effect") && punchEffect.has("effect_mirrored"), "a smear for each fist");

        state.attackAnimation.stop(); state.attackAnimationName = null;
        for (float amount : new float[]{0, .25F, .5F, 1, 0}) {
            state.groundAnimationAmount = amount; state.groundAnimationPhase = 7.125F; model.setupAnim(state);
            for (var p : root.getAllParts()) check(Float.isFinite(p.x + p.y + p.z + p.xScale + p.yScale + p.zScale), "finite reset/gait");
        }
        if (args.length > 0) {
            var animation = new NativeAnimationSet(root, definition.animation());
            try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))) {
                for (var entry : net.minecraft.util.GsonHelper.parse(reader).getAsJsonArray("samples")) {
                    var row = entry.getAsJsonObject(); root.getAllParts().forEach(ModelPart::resetPose);
                    animation.apply(row.get("clip").getAsString(), row.get("tick").getAsFloat(), 1);
                    for (int h = 0; h < 8; h += 3) {
                        var origin = new Vec3(-11, 50, 27); float yaw = h * 45;
                        var actual = rendered(root, yaw, origin);
                        for (var object : row.getAsJsonArray("objects")) {
                            var o = object.getAsJsonObject(); var vertices = actual.get(o.get("name").getAsString());
                            for (var vertex : o.getAsJsonArray("points")) {
                                var p = vertex.getAsJsonArray();
                                var expected = AttackGeometry.world(origin, new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()), yaw);
                                near(expected, vertices.stream().min(Comparator.comparingDouble(expected::distanceToSqr)).orElseThrow(),
                                        "native " + row.get("clip") + " " + row.get("tick") + " " + o.get("name"));
                            }
                        }
                    }
                }
            }
        }
        Constants.LOG.info("Gotsumon native parity: {} checks, worst error {} blocks", checks, worst);
    }
}
