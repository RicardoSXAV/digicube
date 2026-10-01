package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.AuthoredAttacks;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.KineticAttacks;
import com.digicube.digimon.RiderAttack;
import com.digicube.digimon.RushAttacks;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Monochromon as the compiled NativeGroundModel draws it:
 * <ul>
 * <li>the walk lattice's full amble and the gallop are planted: through the flat of each foot's stance its sole runs back
 *     at exactly the gait's pace, level, so the feet never slide under the sheet's strides;</li>
 * <li>the rider sits where the sheet seats them, and the gaits carry them only as far as the trunk's bob and sway;</li>
 * <li>Guardy Tusk: the horn the client draws is where the server's motion has it (horn base and tip), and the drawn horn's
 *     tip is inside the struck volume through the hit window; the rush's brace lowers the head and the charge keeps it
 *     low, the horn's pressure streaks shown only while it charges;</li>
 * <li>Volcano Strike: the ball the server launches leaves where the drawn magma sits in the jaws at the release;</li>
 * <li>at rest every membrane (the horn's streaks, the mouth's magma) is hidden.</li>
 * </ul>
 */
public final class NativeMonochromonRegressionTest {
    private static int checks;
    private static double worstPlant, worstHorn, worstMuzzle;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** A point in a part's frame (model px) in the entity's frame at yaw 0 (blocks, +z forward, feet at 0). */
    private static Vec3 point(ModelPart root, List<String> path, float scale, float x, float y, float z) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(x / 16, y / 16, z / 16, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    private static Vec3 pivot(ModelPart root, List<String> path, float scale) { return point(root, path, scale, 0, 0, 0); }

    private static ModelPart part(ModelPart root, List<String> path) {
        ModelPart part = root;
        for (String name : path) part = part.getChild(name);
        return part;
    }

    private static final List<String> BODY = List.of("monochromon", "body"), HEAD = List.of("monochromon", "body", "head");
    private static final List<String> HORN = List.of("monochromon", "body", "head", "nasal_horn");

    private static List<String> foot(String leg) {
        String side = leg.charAt(1) == 'L' ? "left" : "right", role = leg.charAt(0) == 'F' ? "fore" : "hind";
        return List.of("monochromon", "body", role + "_" + side + "_upper", role + "_" + side + "_lower", role + "_" + side + "_foot");
    }

    /** One foot's sole (its middle, 4 px under its pivot) through the flat of its stance runs back at the gait's pace. */
    private static void planted(NativeGroundModel model, ModelPart root, DigimonRenderState state, float scale, float cycle, double pace,
                                String leg, float down, float duty, String label) {
        float from = (down + .12F * duty) * cycle, until = (down + .6F * duty) * cycle;
        state.groundAnimationPhase = from; model.setupAnim(state);
        Vec3 start = point(root, foot(leg), scale, 0, 4, 0);
        for (float t = from; t <= until; t += .125F) {
            state.groundAnimationPhase = t; model.setupAnim(state);
            Vec3 at = point(root, foot(leg), scale, 0, 4, 0);
            double error = at.distanceTo(start.subtract(0, 0, pace * (t - from)));
            worstPlant = Math.max(worstPlant, error);
            check(error < .01, label + " " + leg + " slides at " + t + ": " + error);
        }
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("monochromon"));
        float scale = species.body().modelScale();
        var gait = species.locomotion().groundGait();
        var mount = species.body().mount().orElseThrow();
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = scale;
        float cycle = gait.cycleTicks();

        // The rider's slots: Volcano Strike on the quick button, Guardy Tusk held on the special one.
        check(mount.riderAttacks().size() == 2 && mount.riderAttacks().get(0).aim() == RiderAttack.Aim.SHOT
                && mount.riderAttacks().get(1).aim() == RiderAttack.Aim.RUSH && mount.riderAttacks().get(1).input() == RiderAttack.Input.HOLD,
                "rider slots: Volcano Strike shot, Guardy Tusk held rush");
        var tusk = species.attacks().stream().filter(a -> a.id().getPath().equals("guardy_tusk")).findFirst().orElseThrow();
        var volcano = species.attacks().stream().filter(a -> a.id().getPath().equals("volcano_strike")).findFirst().orElseThrow();
        var rush = RushAttacks.get(tusk);
        check(rush != null && AuthoredAttacks.get(tusk) != null && AuthoredAttacks.get(tusk).rootTravel(), "Guardy Tusk is a rush ending in a travelling blow");
        var shot = KineticAttacks.get(volcano);
        check(shot != null && shot.burns() && shot.projectile().equals("volcano_strike_projectile"), "Volcano Strike is a burning shot");

        // At rest every membrane is hidden.
        model.setupAnim(state);
        for (String name : List.of("gt_left_pressure", "gt_tip_glint", "gt_air_chip_0"))
            check(!part(root, List.of("monochromon", "body", "head", "nasal_horn", "gt_root", name)).visible, name + " hidden at rest");
        check(!part(root, List.of("monochromon", "body", "head", "vm_root", "vm_crust_00")).visible, "the mouth's magma hidden at rest");

        // Planted: the amble (left hind, left fore, right hind, right fore, duty .58) and the gallop (left hind, right hind,
        // right fore, left fore).
        state.groundAnimationAmount = 1; state.ageInTicks = 0;
        state.groundRunAmount = 0;
        double walkPace = gait.stride() * scale / cycle;
        for (var f : new Object[][]{{"HL", 0F}, {"FL", .3F}, {"HR", .5F}, {"FR", .8F}})
            planted(model, root, state, scale, cycle, walkPace, (String) f[0], (Float) f[1], .58F, "amble");
        state.groundRunAmount = 1;
        double runPace = gait.runStride() * scale / cycle;
        for (var f : new Object[][]{{"HL", 0F, .3F}, {"HR", .1F, .3F}, {"FR", .4F, .28F}, {"FL", .5F, .28F}})
            planted(model, root, state, scale, cycle, runPace, (String) f[0], (Float) f[1], (Float) f[2], "gallop");

        // The seat.
        state.isBeingRidden = true; state.groundAnimationAmount = 0; state.groundRunAmount = 0; state.ageInTicks = 0;
        state.mountAnchor = mount.position(0);
        Vec3 seated = model.riderOffset(state);
        check(seated.length() < .06, "at rest the rider is drawn at the sheet's seat: " + seated);
        double walkDrift = 0, runDrift = 0;
        state.groundAnimationAmount = 1;
        for (float t = 0; t < cycle; t += .25F) {
            state.groundAnimationPhase = t; state.groundRunAmount = 0;
            walkDrift = Math.max(walkDrift, model.riderOffset(state).length());
            state.groundRunAmount = 1;
            runDrift = Math.max(runDrift, model.riderOffset(state).length());
        }
        check(walkDrift < .15, "ambling the rider rides the bob and sway: " + walkDrift);
        check(runDrift < .3, "galloping the rider rides the trunk's rock: " + runDrift);
        state.isBeingRidden = false; state.groundAnimationAmount = 0;

        // The rush: the brace lowers the head (the horn levelled ahead) and the charge keeps it low over the gallop, the
        // horn's streaks showing only once it charges.
        float restPitch = (float) Math.toDegrees(part(root, HEAD).xRot);
        state.rushStanding = true; state.rushBuild = rush.build(); state.rushTicks = rush.build() - .5F; state.ageInTicks = 0;
        model.setupAnim(state);
        float braced = (float) Math.toDegrees(part(root, HEAD).xRot);
        check(braced - restPitch > 20, "the brace lowers the head: " + (braced - restPitch) + " degrees");
        check(part(root, List.of("monochromon", "body", "head", "nasal_horn", "gt_root", "gt_left_pressure")).visible, "streaks shown as the brace ends");
        state.rushTicks = rush.build() + 12; state.groundAnimationAmount = 1; state.groundRunAmount = 1; state.groundAnimationPhase = 2;
        model.setupAnim(state);
        float charging = (float) Math.toDegrees(part(root, HEAD).xRot);
        check(charging - restPitch > 15, "the charge keeps the head low over the gallop: " + (charging - restPitch) + " degrees");
        state.rushTicks = -1; state.rushStanding = false; state.groundAnimationAmount = 0; state.groundRunAmount = 0;

        // Guardy Tusk's blow: the drawn horn is the server's, while the attack has the whole pose.
        var authored = AuthoredAttacks.get(tusk);
        state.attackDefinition = tusk; state.attackAnimationName = "guardy_tusk"; state.attackAnimation.start(0);
        boolean tipStruck = false;
        for (float t = definition.attackBlendIn(); t <= tusk.durationTicks() - definition.attackBlendOut(); t += .25F) {
            state.ageInTicks = t;
            model.setupAnim(state);
            var frame = tusk.motion().sample(t);
            Vec3 base = point(root, HORN, scale, 0, 0, 0);
            double e = base.distanceTo(frame.hornBase());
            worstHorn = Math.max(worstHorn, e);
            check(e < .08, "the horn drawn apart from where it strikes at " + t + ": " + e);
            if (t >= authored.hitWindows().getFirst()[0] && t <= authored.hitWindows().getFirst()[1]) {
                var boxes = authored.sample(t);
                Vec3 tip = new Vec3(frame.hornTip().x, frame.hornTip().y, frame.hornTip().z);
                var box = boxes[0];
                if (box != null) {
                    Vec3 d = tip.subtract(box.center());
                    boolean inside = Math.abs(d.dot(box.x().normalize())) <= box.x().length() + 1.0E-3
                            && Math.abs(d.dot(box.y().normalize())) <= box.y().length() + 1.0E-3
                            && Math.abs(d.dot(box.z().normalize())) <= box.z().length() + 1.0E-3;
                    check(inside, "the horn's tip is inside its struck volume at " + t);
                    tipStruck = true;
                }
            }
        }
        check(tipStruck, "the horn's volume strikes through the hit window");

        // Volcano Strike: the ball leaves where the drawn magma sits in the jaws at the release.
        state.attackDefinition = volcano; state.attackAnimationName = "volcano_strike"; state.attackAnimation.start(0);
        state.attackAimPitch = 0; state.ageInTicks = volcano.hitTick() - .01F;
        model.setupAnim(state);
        Vec3 drawn = pivot(root, List.of("monochromon", "body", "head", "vm_root"), scale);
        Vec3 muzzle = shot.motion().sample(volcano.hitTick() - .01F).muzzle();
        worstMuzzle = drawn.distanceTo(muzzle);
        check(worstMuzzle < .04, "the ball leaves from the drawn jaws: " + worstMuzzle);
        state.ageInTicks = 6;
        model.setupAnim(state);
        check(part(root, List.of("monochromon", "body", "head", "vm_root", "vm_crust_00")).visible, "magma wells in the jaws before the release");

        System.out.println(String.format(Locale.ROOT, "Monochromon native checks passed: %d checks, feet planted within %.4f blocks, horn within %.4f, "
                + "ball from the jaws within %.4f, seat %.3f, amble drift %.3f, gallop drift %.3f", checks, worstPlant, worstHorn, worstMuzzle,
                seated.length(), walkDrift, runDrift));
    }
}
