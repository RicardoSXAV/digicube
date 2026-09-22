package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.AttackBox;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.AttackTravelSync;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Actual compiled renderer transforms versus native vertices and server contact volumes: the three blades of the
 * Lizard Dance inside their cuboids through every contact window while the root travels, the Akinakes blade inside
 * its cuboid as it is driven into the floor, and the hanging cloth: three hinged parts the clips never key, pushed
 * out of the thighs by the client simulation.
 */
public final class NativeDinohyumonRegressionTest {
    private static final float SCALE = .32F;
    private static final double CUFF_MARGIN = .25, KNIFE_MARGIN = .25, BLADE_MARGIN = .25;
    /** Key reduction moves a vertex at the end of a long chain by less than this many blocks. */
    private static final double TOLERANCE = .008;
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

    /** Every drawn vertex lies inside the server's cuboid, which extends past what is drawn by the margin on every side. */
    private static void encloses(AttackBox box, List<Vec3> vertices, double margin, String label) {
        Vec3[] axes = {box.x(), box.y(), box.z()};
        check(vertices != null && !vertices.isEmpty(), label + " has drawn vertices");
        for (int i = 0; i < 3; i++) {
            Vec3 axis = axes[i];
            double length = axis.length(), low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (Vec3 v : vertices) {
                double along = v.subtract(box.center()).dot(axis) / length;
                low = Math.min(low, along); high = Math.max(high, along);
            }
            double error = Math.max(Math.abs(high + margin - length), Math.abs(low - margin + length)); worst = Math.max(worst, error); checks++;
            if (error > TOLERANCE) throw new AssertionError(label + " drawn " + low + ".." + high + " against +-" + length + " margin " + margin);
        }
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("dinohyumon"));
        check(species.body().modelScale() == SCALE, "exported and installed at one scale");
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = SCALE; state.attackAnimation.start(0);
        state.cloth = new ClothChains.State();

        var dance = species.attacks().stream().filter(a -> a.id().getPath().equals("lizard_dance")).findFirst().orElseThrow();
        var sword = species.attacks().stream().filter(a -> a.id().getPath().equals("akinakes")).findFirst().orElseThrow();
        check(species.attacks().getFirst() == sword, "the sword is tried before the dance that fills its cooldown");
        var authoredDance = AuthoredAttacks.get(dance); var authoredSword = AuthoredAttacks.get(sword);
        check(dance.kind() == DigimonAttack.Kind.BOX_SWEEP && authoredDance.rootTravel() && authoredDance.leap() == null && authoredDance.hitWindows().size() == 5
                && authoredDance.maxHits() == 5 && authoredDance.contactParts().size() == 10, "a travelling five-blow sweep whose sparks need a hit");
        check(AttackTravelSync.drivesRoot(dance) && dance.motion().sample(dance.durationTicks()).travel() > .9 && dance.motion().sample(dance.durationTicks()).travel() < 1.1,
                "the dance carries its caster about a block");
        check(AttackTravelSync.steps(dance, 20) == AttackTravelSync.LUNGE_STEPS && AttackTravelSync.steps(dance, 50) == AttackTravelSync.VANILLA_STEPS, "lunge sync only while the dance travels");
        var leap = authoredSword.leap();
        check(sword.kind() == DigimonAttack.Kind.BOX_BURST && leap != null && leap.launch() == 24 && leap.land() == 40 && !authoredSword.rootTravel()
                && authoredSword.hitWindows().size() == 2 && authoredSword.hitWindows().getFirst()[0] >= leap.land() - 1, "a jump that lands before its blade can hurt");
        check(AttackTravelSync.steps(sword, 30) == AttackTravelSync.LUNGE_STEPS && AttackTravelSync.steps(sword, 10) == AttackTravelSync.VANILLA_STEPS && !AttackTravelSync.drivesRoot(sword),
                "lunge sync through the flight, no horn drive");
        check(authoredDance.particles() == com.digicube.entity.StrikeParticles.STEEL && authoredSword.particles() == com.digicube.entity.StrikeParticles.STEEL, "steel");
        check(sword.motion().minimumRange() >= 2 && sword.range() > sword.motion().minimumRange(), "a jump is not a melee move");
        for (double u : new double[]{0, .5, 1}) {
            var p = com.digicube.entity.AuthoredVolumeAttack.arc(new Vec3(0, 10, 0), new Vec3(4, 11, 0), 2, u);
            check(Math.abs(p.y - (10 + u + (u == .5 ? 2 : 0))) < 1e-9 && Math.abs(p.x - 4 * u) < 1e-9, "the arc peaks over the chord's middle");
        }

        // The dance: each blade inside its cuboid through its window, at eight headings and three heights.
        String[] blades = {"crescent_R", "cleaver", "crescent_L"}; double[] margins = {CUFF_MARGIN, KNIFE_MARGIN, CUFF_MARGIN};
        state.attackDefinition = dance; state.attackAnimationName = dance.animationName(false);
        int swept = 0;
        for (float t = 2; t <= dance.durationTicks() - 5; t += .25F) {
            state.ageInTicks = t; model.setupAnim(state);
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F;
            var boxes = authoredDance.sample(tick);
            for (int column = 0; column < 3; column++) {
                if (boxes[column] == null) continue;
                swept++;
                for (int h = 0; h < 8; h++) for (int elevation = -1; elevation <= 1; elevation++) {
                    float yaw = h * 45; var origin = new Vec3(13, 80 + elevation, -19);
                    encloses(boxes[column].world(origin, yaw, 0), rendered(root, yaw, origin).get(blades[column]), margins[column], blades[column] + " " + t + " yaw " + yaw);
                }
            }
        }
        check(swept >= 5 * 10, "every window sweeps its blade: " + swept);

        // The sword: the blade inside its cuboid as it is driven in; the shock ring on the floor ahead of the feet.
        state.attackDefinition = sword; state.attackAnimationName = sword.animationName(false);
        int driven = 0;
        for (float t = 39.5F; t <= 41.5F; t += .25F) {
            state.ageInTicks = t; model.setupAnim(state);
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F;
            var box = authoredSword.sample(tick)[0];
            if (box == null) continue;
            driven++;
            for (int h = 0; h < 8; h++) {
                float yaw = h * 45; var origin = new Vec3(-7.5, 64, 31.25);
                encloses(box.world(origin, yaw, 0), rendered(root, yaw, origin).get("akinakes"), BLADE_MARGIN, "akinakes " + t + " yaw " + yaw);
            }
        }
        check(driven >= 8, "the blade is swept through the impact: " + driven);
        var ring = authoredSword.sample(42.5)[1];
        check(ring != null && ring.center().z > .4 && ring.center().z < 1.6 && Math.abs(ring.center().x) < .3 && ring.bounds().minY > -1e-6 && ring.bounds().getXsize() > 3,
                "the shock ring sits on the floor around the buried blade: " + (ring == null ? null : ring.center()));
        check(authoredSword.sample(30)[0] == null && authoredSword.sample(30)[1] == null, "nothing hurts in flight");

        // The effects: every effect part the volumes name exists in its model.
        for (var authored : List.of(authoredDance, authoredSword)) {
            var effect = new NativeEffectModel(NativeEffectModel.createLayer(authored.effect()).bakeRoot(), authored.effect());
            check(effect.has("effect"), authored.effect() + " has its clip");
            var fxRoot = NativeEffectModel.createLayer(authored.effect()).bakeRoot();
            for (var group : authored.visualParts()) for (String name : group) check(fxRoot.hasChild(name), authored.effect() + " draws " + name);
            for (String name : authored.contactParts()) check(fxRoot.hasChild(name), authored.effect() + " sparks " + name);
        }

        // Gait: walk and run share the phase; the cloth hangs from the belt and swings clear of a forward thigh.
        state.attackAnimation.stop(); state.attackAnimationName = null;
        var animations = new NativeAnimationSet(root, definition.animation());
        check(animations.has("run") && animations.blendNames().contains("run") && animations.blendNames().contains("walk"), "walk and run lattices");
        check(definition.cloth().size() == 1 && definition.cloth().getFirst().segments().equals(List.of("cloth_0", "cloth_1", "cloth_2")), "one three-segment cloth chain");
        var belt = root.getChild("root").getChild("pelvis").getChild("belt"); var cloth0 = belt.getChild("cloth_0");
        check(Math.abs(cloth0.y - 3) < .01 && cloth0.z < -11 && cloth0.z > -12.5 && Math.abs(cloth0.getChild("cloth_1").y - 14) < .01, "hinges on the panel's cut lines");
        for (String clip : List.of("idle", "walk", "run", "lizard_dance", "akinakes")) {
            root.getAllParts().forEach(ModelPart::resetPose); animations.apply(clip, 7.25F, 1);
            var c0 = belt.getChild("cloth_0");
            check(c0.xRot == 0 && c0.zRot == 0 && c0.getChild("cloth_1").xRot == 0, clip + " never keys the cloth");
        }
        double forwardMost = 0, clothMost = 0;
        for (int frame = 0; frame < 120; frame++) {
            state.ageInTicks = frame * .5F; state.groundAnimationAmount = 1; state.groundAnimationPhase = frame * .5F; state.groundRunAmount = frame < 60 ? 0 : 1;
            state.x = frame * .1; state.bodyRot = 0;
            model.setupAnim(state);
            var thigh = root.getChild("root").getChild("pelvis").getChild("thigh_L"); var c0 = belt.getChild("cloth_0");
            for (var p : root.getAllParts()) check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot), "finite gait and cloth");
            check(c0.xRot <= .06F && Math.abs(c0.zRot) < .7F, "the cloth never falls back into the pelvis: " + c0.xRot);
            if (-thigh.xRot > forwardMost) { forwardMost = -thigh.xRot; clothMost = -c0.xRot; }
        }
        check(forwardMost > .3 && clothMost > .2, "with the thigh " + forwardMost + " forward the cloth swings " + clothMost);
        for (float amount : new float[]{0, .25F, .5F, 1, 0}) {
            state.groundAnimationAmount = amount; state.groundAnimationPhase = 7.125F; model.setupAnim(state);
            for (var p : root.getAllParts()) check(Float.isFinite(p.x + p.y + p.z + p.xScale + p.yScale + p.zScale), "finite reset/gait");
        }
        if (args.length > 0) {
            try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))) {
                for (var entry : net.minecraft.util.GsonHelper.parse(reader).getAsJsonArray("samples")) {
                    var row = entry.getAsJsonObject(); root.getAllParts().forEach(ModelPart::resetPose);
                    animations.apply(row.get("clip").getAsString(), row.get("tick").getAsFloat(), 1);
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
        Constants.LOG.info("Dinohyumon native parity: {} checks, worst error {} blocks", checks, worst);
    }
}
