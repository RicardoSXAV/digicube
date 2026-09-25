package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.ThrowerState;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The compiled Mojyamon renderer against the server's thrower anchors: the bone leaves the drawn fist where the server
 * spawns it, the icicle leaves the drawn fists where the server throws it at both ends of the charge, and the catch
 * and pickup close where the server meets the bone. Also the layering (a throw on the walk keeps the gait's legs and
 * plays the throw above the waist; standing, the throw's own footwork), the charge blend (the held spear grows with
 * the synced charge) and the carried bone (drawn on the back while carried, only from the clip while thrown).
 *
 * <p>With an evidence path, writes every drawn quad of a set of poses (world position and atlas UV) for an offline
 * picture of what the game draws.
 */
public final class NativeMojyamonRegressionTest {
    private static final float SCALE = .45F;          // Golemon-sized since 2026-09-25
    /** The drawn hand and the server's anchor agree to within this (key reduction and the upper-body blend). */
    private static final double TOLERANCE = .1; // a whip moves the fist ~1.5 blocks a tick: key interpolation shows
    private static int checks;
    private static Map<String, List<String>> PATHS;
    private static double worst;

    private static PoseStack stack(float yaw, Vec3 origin) {
        var s = new PoseStack(); s.translate(origin.x, origin.y, origin.z);
        TectonicWaveRenderer.applyWorldTransform(s, yaw, SCALE);
        s.translate(0, EntityModel.MODEL_Y_OFFSET, 0); return s;
    }
    /** World position of a point in a part's own frame (model pixels), for the model as posed. */
    private static Vec3 point(ModelPart root, List<String> path, float x, float y, float z, float yaw, Vec3 origin) {
        var s = stack(yaw, origin);
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

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("mojyamon"));
        check(species.body().modelScale() == SCALE, "exported and installed at one scale");
        var definition = NativeGroundModel.definitions().get(species.id());
        check(definition.carried().equals("bone_back") && definition.upperBody().equals(List.of("mojyamon", "pelvis", "waist")), "carried bone and upper body");
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var bone = species.attacks().get(0); var icicle = species.attacks().get(1);
        var returning = ThrownAttacks.returning(bone); var charged = ThrownAttacks.charged(icicle);
        var state = new DigimonRenderState(); state.modelScale = SCALE;
        var paths = new HashMap<String, List<String>>();
        var mesh = NativeModelGeometry.mesh(definition.geometry());
        for (var part : mesh.parts()) paths.put(part.name(), List.of(part.path()));
        PATHS = paths;

        // Carried bone: on the back while carried, gone while it flies; the in-hand props are hidden at rest.
        var back = root.getChild("mojyamon").getChild("pelvis").getChild("waist").getChild("body").getChild("bone_back");
        state.boneCarried = true; state.ageInTicks = 3; model.setupAnim(state);
        check(back.visible, "the bone is drawn on the back while carried");
        state.boneCarried = false; model.setupAnim(state);
        check(!back.visible, "no bone on the back while it flies");
        var hand = part(root, paths.get("bone_hand")); var ice = part(root, paths.get("icicle"));
        check(!hand.visible && !ice.visible, "the fists are empty at rest");

        // The throw: the drawn fist holds the bone where the server lets it go (standing, at the release tick).
        Vec3 origin = new Vec3(4, 70, -9);
        state.attackAnimation.start(0); state.attackDefinition = bone; state.attackAnimationName = returning.throwClip().name();
        state.groundAnimationAmount = 0; state.boneCarried = false;
        for (float yaw : new float[]{0, 90, 215}) {
            state.ageInTicks = returning.throwClip().event(); model.setupAnim(state);
            check(!back.visible, "the bone is not on the back during the throw");
            // The bone's grip spot (19 px along it from its middle, in the fist) against the server's release point.
            near(ThrowerState.local(origin, returning.releasePoint(), yaw), boneGrip(root, paths, yaw, origin), TOLERANCE, "bone release " + yaw);
            state.ageInTicks = returning.throwClip().event() + .5F; model.setupAnim(state);
            check(!hand.visible, "the fist opens at the release");
        }
        // Held: the hold starts on the pose the tap throw has at the hold tick, and the release lets go where the server
        // does at either end of the charge.
        state.ageInTicks = returning.holdAt(); state.attackAnimationName = returning.throwClip().name(); model.setupAnim(state);
        Vec3 cocked = boneGrip(root, paths, 0, origin);
        state.attackAnimationName = returning.holdClip(); state.throwCharge = 0; state.ageInTicks = 0; model.setupAnim(state);
        near(cocked, boneGrip(root, paths, 0, origin), .03, "the hold starts where the throw is cocked");
        state.throwCharge = 1; model.setupAnim(state);
        check(boneGrip(root, paths, 0, origin).distanceTo(cocked) > .15, "a full charge winds the bone further back");
        state.attackAnimationName = returning.releaseClip().name();
        for (float c : new float[]{0, 1}) {
            state.throwCharge = c; state.ageInTicks = returning.releaseClip().event(); model.setupAnim(state);
            near(ThrowerState.local(origin, returning.releasePoint(c), 0), boneGrip(root, paths, 0, origin), TOLERANCE, "charged bone release at " + c);
        }
        state.throwCharge = 0;

        // The catch and the pickup close on the server's points.
        for (var clip : List.of(returning.catchClip(), returning.pickupClip())) {
            state.attackAnimationName = clip.name(); state.ageInTicks = clip.event() + .01F; model.setupAnim(state);
            check(hand.visible, clip.name() + " closes the fist on the bone");
            Vec3 expected = clip == returning.catchClip() ? returning.catchPoint() : returning.pickupPoint();
            near(ThrowerState.local(origin, expected, 30), boneGrip(root, paths, 30, origin), TOLERANCE, clip.name());
        }

        // The icicle: the hold blends light to heavy by the charge, the spear grows, the release leaves the fist.
        state.attackDefinition = icicle; state.attackAnimationName = charged.hold();
        float light = 0, heavy = 0;
        for (float c : new float[]{0, 1}) {
            state.throwCharge = c; state.ageInTicks = 5; model.setupAnim(state);
            check(ice.visible, "the held spear is drawn at charge " + c);
            if (c == 0) light = ice.xScale; else heavy = ice.xScale;
        }
        check(Math.abs(light - 1) < .02 && Math.abs(heavy - charged.size()[1]) < .05, "the spear grows with the charge: " + light + " -> " + heavy);
        state.attackAnimationName = charged.release().name();
        for (float c : new float[]{0, 1}) {
            state.throwCharge = c; state.ageInTicks = charged.release().event(); model.setupAnim(state);
            near(ThrowerState.local(origin, charged.releasePoint(c), 0), point(root, paths.get("icicle"), 0, 0, 0, 0, origin), TOLERANCE, "icicle release at charge " + c);
        }

        // Layering: throwing on the walk, the legs are the gait's and the arms the throw's.
        state.attackDefinition = bone; state.attackAnimationName = returning.throwClip().name();
        state.groundAnimationAmount = 1; state.groundAnimationPhase = 3; state.gaitShares = new float[]{1, 0, 0, 0};
        state.ageInTicks = 6; model.setupAnim(state);
        Vec3 layeredFoot = point(root, paths.get("foot_left"), 0, 0, 0, 0, origin), layeredHand = point(root, paths.get("hand_right"), 0, 0, 0, 0, origin);
        state.attackAnimation.stop(); state.attackAnimationName = null; model.setupAnim(state);
        Vec3 walkingFoot = point(root, paths.get("foot_left"), 0, 0, 0, 0, origin), walkingHand = point(root, paths.get("hand_right"), 0, 0, 0, 0, origin);
        near(walkingFoot, layeredFoot, .002, "the gait keeps the legs through a throw on the walk");
        check(layeredHand.distanceTo(walkingHand) > .3, "the throw owns the arm on the walk");

        // A throw from a leap: the legs keep the jump, the arms are the throw's.
        state.groundAnimationAmount = 0; state.leapWeight = 1; state.leapTick = 8;
        model.setupAnim(state);
        Vec3 tuckedFoot = point(root, paths.get("foot_left"), 0, 0, 0, 0, origin);
        state.attackAnimation.start(0); state.attackAnimationName = returning.throwClip().name(); state.ageInTicks = 6; model.setupAnim(state);
        near(tuckedFoot, point(root, paths.get("foot_left"), 0, 0, 0, 0, origin), .002, "a throw from a leap keeps the tucked legs");
        state.attackAnimation.stop(); state.attackAnimationName = null; state.leapWeight = 0; state.leapTick = -1; model.setupAnim(state);
        check(tuckedFoot.y - point(root, paths.get("foot_left"), 0, 0, 0, 0, origin).y > .25, "the jump tucks the feet up");

        // The look: the face sits on the chest, so the waist turns the upper body and the head only a little of it.
        var look = definition.look();
        check(!look.carry().isEmpty() && look.yaw() <= 8, "a head fused to its trunk hands its look to the waist");
        state.yRot = 0; model.setupAnim(state);
        Vec3 nose0 = point(root, paths.get("nose"), 0, 0, 0, 0, origin), neck0 = point(root, paths.get("head"), 0, 0, 0, 0, origin);
        state.yRot = 40; model.setupAnim(state);
        Vec3 nose1 = point(root, paths.get("nose"), 0, 0, 0, 0, origin), neck1 = point(root, paths.get("head"), 0, 0, 0, 0, origin);
        double turned = Math.toDegrees(Math.acos(Math.clamp(nose0.subtract(neck0).multiply(1, 0, 1).normalize()
                .dot(nose1.subtract(neck1).multiply(1, 0, 1).normalize()), -1, 1)));
        float most = look.yaw() + look.carry().stream().map(c -> c.yaw()).reduce(0F, Float::sum);
        check(turned > most * .7 && turned < most + 3, "the face follows the look through the waist: " + turned + " of at most " + most);
        state.yRot = 0;

        // The rider sits on the crown (harness mount_01/seat.py): drawn at rest where the sheet seats it (so the first
        // person eye is there too), carried by the head through every performance, and the head carrying it does not look.
        var mount = species.body().mount().orElseThrow();
        state.attackAnimation.stop(); state.attackAnimationName = null; state.groundAnimationAmount = 0; state.boneCarried = true;
        state.isBeingRidden = true; state.mountAnchor = mount.position(0); state.yRot = state.xRot = 0; state.ageInTicks = 12;
        Vec3 seated = model.riderOffset(state);
        check(seated.length() < .06, "the rider is drawn at the sheet's seat at rest: " + seated);
        state.yRot = 30; state.xRot = 20;
        near(seated, model.riderOffset(state), 1.0E-6, "a head carrying its rider does not look around");
        state.yRot = state.xRot = 0;
        double lurch = 0;
        var holdClip = new ThrownAttacks.Clip(returning.holdClip(), 20, 0);
        for (var clip : List.of(returning.throwClip(), holdClip, returning.releaseClip(), returning.catchClip(), returning.pickupClip(), charged.release())) {
            state.attackAnimation.start(0); state.attackAnimationName = clip.name(); state.attackDefinition = clip == charged.release() ? icicle : bone;
            for (float c : new float[]{0, 1}) for (float t = 0; t <= clip.length(); t += .5F) {
                state.throwCharge = c; state.ageInTicks = t; lurch = Math.max(lurch, model.riderOffset(state).length());
            }
        }
        check(lurch < .8, "the rider rides the heave of every performance, never flung: " + lurch);
        // And the leap: the rider goes with the jump, sinks with its landing, and is never thrown off the crown.
        state.attackAnimation.stop(); state.attackAnimationName = null; state.leapWeight = 1;
        double leapLurch = 0;
        for (float t = 0; t <= 22; t += .5F) { state.leapTick = t; leapLurch = Math.max(leapLurch, model.riderOffset(state).length()); }
        state.leapWeight = 0; state.leapTick = -1;
        check(leapLurch < .8, "the rider rides the jump: " + leapLurch);
        state.isBeingRidden = false; state.attackAnimation.stop(); state.attackAnimationName = null;

        if (args.length > 0) evidence(model, root, state, returning, charged, bone, icicle, Path.of(args[0]));
        System.out.printf(Locale.ROOT, "Mojyamon native checks passed: %d checks, worst anchor error %.4f blocks%n", checks, worst);
    }

    private static ModelPart part(ModelPart root, List<String> path) {
        ModelPart p = root; for (String name : path) p = p.getChild(name); return p;
    }
    /** The fist's grip on the bone: 19 px along it from its middle (the bone's own frame; model axes keep its x). */
    private static Vec3 boneGrip(ModelPart root, Map<String, List<String>> paths, float yaw, Vec3 origin) {
        return point(root, paths.get("bone_hand"), -19, 0, 0, yaw, origin);
    }

    /** Paths (as {@link ModelPart#visit} names them) of the parts set invisible. */
    private static void collectHidden(ModelPart root, Collection<List<String>> paths, List<String> out) {
        for (var path : paths) {
            ModelPart part = root;
            for (int i = 0; i < path.size(); i++) {
                part = part.getChild(path.get(i));
                if (!part.visible) { out.add(String.join("/", path.subList(0, i + 1))); break; }
            }
        }
    }

    private static void evidence(NativeGroundModel model, ModelPart root, DigimonRenderState state, ThrownAttacks.Returning returning,
                                 ThrownAttacks.Charged charged, DigimonAttack bone, DigimonAttack icicle, Path out) throws Exception {
        record Pose(String name, DigimonAttack attack, String clip, float tick, float amount, float phase, float[] shares, float charge, boolean carried) {}
        var poses = new ArrayList<Pose>();
        float[] fwd = {1, 0, 0, 0}, left = {0, 0, 1, 0}, backw = {0, 1, 0, 0};
        for (float t : new float[]{0, 20, 40, 60}) poses.add(new Pose("idle_" + (int) t, null, null, t, 0, 0, fwd, 0, true));
        for (float p : new float[]{0, 3.5F, 7, 10.5F}) poses.add(new Pose("walk_" + p, null, null, 0, 1, p, fwd, 0, true));
        for (float p : new float[]{0, 3.5F, 7, 10.5F}) poses.add(new Pose("strafe_" + p, null, null, 0, 1, p, left, 0, true));
        for (float p : new float[]{0, 7}) poses.add(new Pose("back_" + p, null, null, 0, 1, p, backw, 0, true));
        for (float t : new float[]{0, 3, 5, 7, 8, 9, 10, 12, 15}) poses.add(new Pose("throw_" + t, bone, returning.throwClip().name(), t, 0, 0, fwd, 0, false));
        for (float t : new float[]{6, 9}) poses.add(new Pose("throw_walking_" + t, bone, returning.throwClip().name(), t, 1, t, fwd, 0, false));
        for (float t : new float[]{0, 2, 4, 7, 10, 13}) poses.add(new Pose("catch_" + t, bone, returning.catchClip().name(), t, 0, 0, fwd, 0, false));
        for (float t : new float[]{4, 8, 12, 17}) poses.add(new Pose("pickup_" + t, bone, returning.pickupClip().name(), t, 0, 0, fwd, 0, false));
        for (float t : new float[]{0, 3, 6, 8}) poses.add(new Pose("form_" + t, icicle, charged.form().name(), t, 0, 0, fwd, 0, true));
        for (float c : new float[]{0, .5F, 1}) poses.add(new Pose("hold_" + c, icicle, charged.hold(), 5, 0, 0, fwd, c, true));
        poses.add(new Pose("hold_walking_1", icicle, charged.hold(), 5, 1, 3.5F, left, 1, true));
        for (float c : new float[]{0, 1}) for (float t : new float[]{2, 4, 7}) poses.add(new Pose("release_" + c + "_" + t, icicle, charged.release().name(), t, 0, 0, fwd, c, true));
        var sb = new StringBuilder("{\"texture\":\"mojyamon\",\"poses\":[");
        boolean firstPose = true;
        for (var p : poses) {
            state.attackDefinition = p.attack(); state.attackAnimationName = p.clip();
            if (p.clip() != null) state.attackAnimation.start(0); else state.attackAnimation.stop();
            state.ageInTicks = p.tick(); state.groundAnimationAmount = p.amount(); state.groundAnimationPhase = p.phase();
            state.gaitShares = p.shares(); state.throwCharge = p.charge(); state.boneCarried = p.carried();
            model.setupAnim(state);
            if (!firstPose) sb.append(','); firstPose = false;
            sb.append("{\"name\":\"").append(p.name()).append("\",\"quads\":[");
            boolean[] first = {true};
            // Only what the game draws: a hidden part hides everything it carries.
            var hiddenPaths = new ArrayList<String>();
            collectHidden(root, PATHS.values(), hiddenPaths);
            root.visit(stack(0, Vec3.ZERO), (pose, path, index, cube) -> {
                String at = path.startsWith("/") ? path.substring(1) : path;
                for (String hidden : hiddenPaths) if (at.equals(hidden) || at.startsWith(hidden + "/")) return;
                for (var polygon : cube.polygons) {
                    if (!first[0]) sb.append(','); first[0] = false;
                    sb.append('[');
                    for (int i = 0; i < polygon.vertices().length; i++) {
                        var vertex = polygon.vertices()[i];
                        var v = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new org.joml.Vector3f());
                        if (i > 0) sb.append(',');
                        sb.append(String.format(Locale.ROOT, "[%.4f,%.4f,%.4f,%.5f,%.5f]", v.x, v.y, v.z, vertex.u(), vertex.v()));
                    }
                    sb.append(']');
                }
            });
            sb.append("]}");
        }
        sb.append("]}");
        Files.writeString(out, sb.toString());
        System.out.println("Wrote " + poses.size() + " poses to " + out);
    }
}
