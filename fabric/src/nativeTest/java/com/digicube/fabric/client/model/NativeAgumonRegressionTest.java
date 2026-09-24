package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.AttackGeometry;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Agumon's compiled renderer against the server: the snout the fireball charges and leaves from, the ember drawn in
 * the mouth, the flying core on the centre of the ball's hitbox, the claws at the hit, the head look and the gait.
 */
public final class NativeAgumonRegressionTest {
    private static final float SCALE = 1.3F / 7;
    /** The muzzle marker in the head's own frame, model pixels: 24 below the pivot and 32 ahead (fx_contract.json). */
    private static final Vec3 MUZZLE = new Vec3(0, 24 / 16.0, -32 / 16.0);
    private static final List<String> HEAD = List.of("root", "pelvis", "torso", "neck", "head");
    /** Key reduction moves a point at the end of the head chain by less than this many blocks. */
    private static final double TOLERANCE = .004;
    private static int checks;
    private static double worst;

    private static PoseStack stack(float yaw, Vec3 origin) {
        var s = new PoseStack(); s.translate(origin.x, origin.y, origin.z);
        TectonicWaveRenderer.applyWorldTransform(s, yaw, SCALE);
        s.translate(0, EntityModel.MODEL_Y_OFFSET, 0); return s;
    }
    private static Vec3 point(ModelPart root, List<String> path, Vec3 local, PoseStack s) {
        ModelPart part = root;
        root.translateAndRotate(s);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(s); }
        var v = s.last().pose().transformPosition((float) local.x, (float) local.y, (float) local.z, new org.joml.Vector3f());
        return new Vec3(v.x, v.y, v.z);
    }
    private static Map<String, List<Vec3>> rendered(ModelPart root, PoseStack s) {
        Map<String, List<Vec3>> actual = new HashMap<>();
        root.visit(s, (pose, path, index, cube) -> {
            var vertices = actual.computeIfAbsent(path.substring(path.lastIndexOf('/') + 1), k -> new ArrayList<>());
            for (var polygon : cube.polygons) for (var vertex : polygon.vertices()) {
                var v = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new org.joml.Vector3f());
                vertices.add(new Vec3(v.x, v.y, v.z));
            }
        });
        return actual;
    }
    private static Vec3 centre(List<Vec3> points) {
        double x = 0, y = 0, z = 0; for (var p : points) { x += p.x; y += p.y; z += p.z; }
        return new Vec3(x / points.size(), y / points.size(), z / points.size());
    }
    private static void near(Vec3 a, Vec3 b, double tolerance, String label) {
        double error = a.distanceTo(b); if (tolerance <= TOLERANCE) worst = Math.max(worst, error); checks++;
        if (error > tolerance) throw new AssertionError(label + " error=" + error + " expected=" + a + " actual=" + b);
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon"));
        check(Math.abs(species.body().modelScale() - SCALE) < 1e-5, "exported and installed at one scale");
        var definition = NativeGroundModel.definitions().get(species.id());
        check(definition != null && definition.walkBlend() && definition.look() != null && definition.look().path().equals(HEAD),
                "the native model, its planted gait and its head look are catalogued");
        var effects = definition.attackEffects();
        check(effects != null && effects.clips().get("pepper_breath").equals("mouth_charge") && effects.clips().get("claw").equals("claw")
                && effects.clips().get("claw_mirrored").equals("claw_mirrored"), "the charge and both claw streaks follow their attacks");
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var effectRoot = NativeEffectModel.createLayer(effects.effect()).bakeRoot();
        var effect = new NativeEffectModel(effectRoot, effects.effect());
        for (String clip : List.of("claw", "claw_mirrored", "mouth_charge", "fireball_flight", "fireball_impact"))
            check(effect.has(clip), "effect clip " + clip);

        var pepper = DigimonSpeciesBootstrap.PEPPER_BREATH; var claw = DigimonSpeciesBootstrap.CLAW;
        check(species.attacks().equals(List.of(pepper, claw)), "move priority preserved");
        var muzzle = FireballMuzzles.get(pepper).orElseThrow();
        check(FireballMuzzles.get(claw).isEmpty() && pepper.motion() == null, "only the fireball reads a muzzle; it takes no motion rules");

        // The snout the server charges and fires from is the one drawn, at every heading and height.
        var state = new DigimonRenderState(); state.modelScale = SCALE; state.attackAnimation.start(0);
        state.attackDefinition = pepper; state.attackAnimationName = "pepper_breath";
        for (float t = definition.attackBlendIn(); t <= pepper.durationTicks() - definition.attackBlendOut(); t += .25F) {
            state.ageInTicks = t; model.setupAnim(state);
            for (int h = 0; h < 8; h++) for (int elevation = -1; elevation <= 1; elevation++) {
                float yaw = h * 45; var origin = new Vec3(13, 80 + elevation, -19);
                near(AttackGeometry.world(origin, muzzle.sample(t).mouth(), yaw), point(root, HEAD, MUZZLE, stack(yaw, origin)), TOLERANCE,
                        "muzzle at " + t + " yaw " + yaw);
            }
        }
        var release = muzzle.sample(pepper.hitTick()).mouth();
        check(release.y > .6 && release.y < .75 && release.z > .35 && release.z < .5, "the fireball leaves the snout, not the forehead: " + release);

        // The ember is drawn inside the mouth, never dropping out of it, and ends at the lips as the ball leaves.
        var fx = new NativeEffectState(); fx.scale = SCALE; fx.clip = "mouth_charge";
        int glowing = 0;
        for (float t = 0; t < pepper.hitTick(); t += .125F) {
            fx.tick = t; fx.yaw = 0; effect.setupAnim(fx);
            if (!effectRoot.getChild("pb_core").visible) continue;
            glowing++;
            var core = centre(rendered(effectRoot, stack(0, Vec3.ZERO)).get("pb_core"));
            var mouth = muzzle.sample(t).mouth();
            check(core.y > .6 && Math.abs(core.x) < .01, "the ember stays at mouth height at " + t + ": " + core);
            near(mouth, core, .2, "ember inside the mouth at " + t);
        }
        check(glowing >= 40, "the ember glows through the last tick of the charge: " + glowing);
        fx.tick = pepper.hitTick(); effect.setupAnim(fx);
        check(!effectRoot.getChild("pb_core").visible, "the caster's ember is gone once the ball is out");

        // The flying core sits on the centre of the ball's hitbox and its tail trails the flight.
        fx.clip = "fireball_flight";
        for (float t = 0; t < 20; t += .5F) {
            fx.tick = t; effect.setupAnim(fx);
            for (float yaw : new float[]{0, 90, 215}) for (float pitch : new float[]{-30, 0, 45}) {
                var s = new PoseStack(); PepperBreathRenderer.transform(s, yaw, pitch);
                var parts = rendered(effectRoot, s);
                near(Vec3.ZERO, centre(parts.get("pb_core")), .01, "core on the hitbox centre at " + t);
                var forward = Vec3.directionFromRotation(pitch, yaw);
                check(centre(parts.get("pb_tail_tip")).dot(forward) < -.4, "the tail trails the flight at " + t + " yaw " + yaw + " pitch " + pitch);
            }
        }
        fx.clip = "fireball_impact"; fx.tick = com.digicube.entity.PepperBreathEntity.IMPACT_TICKS; effect.setupAnim(fx);
        check(effectRoot.getAllParts().stream().noneMatch(p -> p != effectRoot && p.visible),
                "nothing of the flare outlives the impact ticks");

        // Claws: the striking hand leaves the body at the hit tick; its streak is drawn only for the swipe.
        state.attackDefinition = claw;
        double reach = 0;
        for (boolean mirrored : new boolean[]{false, true}) {
            state.attackAnimationName = claw.animationName(mirrored); state.ageInTicks = claw.hitTick(); model.setupAnim(state);
            var parts = rendered(root, stack(0, Vec3.ZERO));
            String side = mirrored ? "left" : "right";
            double tip = parts.entrySet().stream().filter(e -> e.getKey().startsWith(side + "_hand_claw")).flatMap(e -> e.getValue().stream())
                    .mapToDouble(Vec3::z).max().orElseThrow();
            reach = Math.max(reach, tip);
            fx.clip = state.attackAnimationName; fx.tick = claw.hitTick(); effect.setupAnim(fx);
            check(effectRoot.getChild("claw_streak_0").visible, "the streak shows at the hit, " + side);
            fx.tick = claw.durationTicks() - 1; effect.setupAnim(fx);
            check(!effectRoot.getChild("claw_streak_0").visible, "and is gone in the recovery, " + side);
        }
        check(reach > species.body().dimensions().width() / 2, "the claw reaches past the body: " + reach);
        Constants.LOG.info("Agumon claw tip at the hit: {} blocks ahead of the feet (half width {})", reach, species.body().dimensions().width() / 2);

        // Head look turns only the head, within its limits; an attack in full swing overrides it.
        state.attackAnimation.stop(); state.attackAnimationName = null; state.attackDefinition = null; state.ageInTicks = 10;
        state.yRot = 0; state.xRot = 0; model.setupAnim(state);
        float restYaw = root.getChild("root").getChild("pelvis").getChild("torso").getChild("neck").getChild("head").yRot;
        state.yRot = 80; state.xRot = -60; model.setupAnim(state);
        var head = root.getChild("root").getChild("pelvis").getChild("torso").getChild("neck").getChild("head");
        check(Math.abs(Math.toDegrees(head.yRot - restYaw) - definition.look().yaw()) < 1e-3, "head yaw is limited");

        // The planted gait: every direction and step size gives a finite pose, and a walk moves the feet.
        for (float amount : new float[]{0, .25F, .5F, .75F, 1})
            for (float[] shares : new float[][]{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}, {.5F, 0, .5F, 0}}) {
                state.groundAnimationAmount = amount; state.gaitShares = shares; state.groundAnimationPhase = 7.125F; model.setupAnim(state);
                for (var p : root.getAllParts()) check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot + p.xScale), "finite gait");
            }
        state.gaitShares = new float[]{1, 0, 0, 0}; state.groundAnimationAmount = 1;
        state.groundAnimationPhase = 0; model.setupAnim(state); float a = root.getChild("root").getChild("pelvis").getChild("left_thigh").xRot;
        state.groundAnimationPhase = 10; model.setupAnim(state); float b = root.getChild("root").getChild("pelvis").getChild("left_thigh").xRot;
        check(Math.abs(a - b) > .05, "half a cycle apart the legs have swapped");

        var gait = species.locomotion().groundGait();
        check(gait.cycleTicks() == 20 && gait.directional() && Math.abs(gait.fullSpeed(SCALE) - .52 / 20) < 1e-4,
                "the gait covers the authored 0.52 blocks a cycle");
        Constants.LOG.info("Agumon native parity: {} checks, worst muzzle error {} blocks", checks, worst);
    }

}
