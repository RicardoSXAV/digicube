package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.KineticAttacks;
import com.digicube.digimon.PounceAttacks;
import com.digicube.digimon.RiderAttack;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.PounceLines;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Greymon as the compiled NativeGroundModel draws it:
 * <ul>
 * <li>every gait is planted: whatever part of a foot's sole stands lowest (the heel as the walk strikes, the ball as the run
 *     lands, the whole sole between) runs back at exactly the pace its lattice was authored for, through the walk's columns
 *     and each of the run's, so no foot slides under the sheet's strides at any pace; turning on the spot, the standing
 *     foot stays put on the ground while the body goes round over it;</li>
 * <li>in every mix of the gait clips (the walk's columns, the run's, the walk into the run) the knees stay forward;</li>
 * <li>the rider sits where the sheet seats them, and the gaits carry them only as far as the trunk's bob and sway;</li>
 * <li>Great Antler: the horn the client draws is where the server's motion has it through the charge, and aimed up or down
 *     (the body tips its share, the neck turns the rest) it is where the server poses it (PounceLines.posed); from a leap
 *     the air form's own clip plays;</li>
 * <li>Mega Flame: the ball the server launches leaves from between the drawn jaws at the release;</li>
 * <li>the head's fire and horn light (greymon_fx) are drawn in the head's frame as the head is drawn, with a clip for each
 *     move and form, as long as the move's own;</li>
 * <li>the leap and both moves have their clips at the lengths the entity plays them for.</li>
 * </ul>
 */
public final class NativeGreymonRegressionTest {
    private static int checks;
    private static double worstPlant, worstPivot, worstHorn, worstAimed, worstMuzzle, worstKnee;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** A point in a part's frame (model px) in the entity's frame at yaw 0 (blocks, +z forward, feet at 0). */
    private static Vec3 point(ModelPart root, List<String> path, float scale, float x, float y, float z) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(x / 16, y / 16, z / 16, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    private static final List<String> HEAD = List.of("root", "pelvis", "torso", "neck", "head");
    private static final List<String> JAW = List.of("root", "pelvis", "torso", "neck", "head", "jaw");
    private static final List<String> NASAL_TIP = List.of("root", "pelvis", "torso", "neck", "head", "nasal_horn_1", "nasal_horn_2",
            "nasal_horn_3", "nasal_horn_4");

    private static List<String> foot(String side) {
        return List.of("root", "pelvis", "thigh_" + side, "shin_" + side, "foot_" + side);
    }

    /** A foot's sole under its heel, its middle and its ball (foot frame, model px): one of them bears the body. */
    private static final float[][] SOLE = {{0, 12, 5}, {0, 12, -9}, {0, 12, -23}};

    private static Vec3[] sole(ModelPart root, String side, float scale) {
        Vec3[] out = new Vec3[SOLE.length];
        for (int i = 0; i < SOLE.length; i++) out[i] = point(root, foot(side), scale, SOLE[i][0], SOLE[i][1], SOLE[i][2]);
        return out;
    }

    /** How far a knee turns off straight ahead (degrees), 180 when it is not in front of the line from its hip to its ankle. */
    private static double kneeSplay(ModelPart root, String side, float scale) {
        Vec3 hip = point(root, List.of("root", "pelvis", "thigh_" + side), scale, 0, 0, 0);
        Vec3 knee = point(root, List.of("root", "pelvis", "thigh_" + side, "shin_" + side), scale, 0, 0, 0);
        Vec3 line = point(root, foot(side), scale, 0, 0, 0).subtract(hip).normalize(), out = knee.subtract(hip);
        out = out.subtract(line.scale(out.dot(line)));
        return out.z < .02 ? 180 : Math.toDegrees(Math.atan2(Math.abs(out.x), out.z));
    }

    private static int lowest(Vec3[] points) {
        int k = 0;
        for (int i = 1; i < points.length; i++) if (points[i].y < points[k].y) k = i;
        return k;
    }

    /**
     * Through a whole cycle at quarter ticks, the lowest of each foot's sole points, while it stays on the ground (within
     * 4 thousandths of a block of the cycle's lowest), runs back at {@code pace} blocks a tick of the phase.
     */
    private static void planted(NativeGroundModel model, ModelPart root, DigimonRenderState state, float scale, float cycle, double pace, String label) {
        int steps = Math.round(cycle * 4);
        for (String side : List.of("left", "right")) {
            var at = new Vec3[steps + 1][];
            double ground = Double.MAX_VALUE;
            for (int i = 0; i <= steps; i++) {
                state.groundAnimationPhase = i / 4F; model.setupAnim(state);
                at[i] = sole(root, side, scale);
                ground = Math.min(ground, at[i][lowest(at[i])].y);
            }
            int standing = 0;
            for (int i = 0; i < steps; i++) {
                int k = lowest(at[i]);
                if (at[i][k].y > ground + .004 || at[i + 1][k].y > ground + .004) continue;
                standing++;
                Vec3 moved = at[i + 1][k].subtract(at[i][k]);
                double error = Math.abs(moved.z + pace / 4) + Math.abs(moved.x) + Math.abs(moved.y);
                worstPlant = Math.max(worstPlant, error);
                check(error < .008, label + " " + side + " foot slides at " + i / 4F + ": " + error);
            }
            check(standing >= 4, label + " " + side + " foot never stands: " + standing);
        }
    }

    private static JsonObject clips() {
        try (var input = NativeGreymonRegressionTest.class.getResourceAsStream("/assets/digicube/models/entity/greymon_fx.animation.json")) {
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject("clips");
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private static JsonObject bodyClips() {
        try (var input = NativeGreymonRegressionTest.class.getResourceAsStream("/assets/digicube/models/entity/greymon.animation.json")) {
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject("clips");
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("greymon"));
        float scale = species.body().modelScale();
        var gait = species.locomotion().groundGait();
        var mount = species.body().mount().orElseThrow();
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = scale;
        float cycle = gait.cycleTicks();

        // The moves: Mega Flame a burning fireball that bursts where it strikes, Great Antler a horn charge with an air form.
        var flame = species.attacks().get(0);
        var antler = species.attacks().get(1);
        var shot = KineticAttacks.get(flame);
        var charge = PounceAttacks.get(antler);
        check(shot != null && shot.burns() && shot.blast() != null && "mega_flame".equals(shot.projectile())
                && shot.shotStyle() == com.digicube.entity.ShotStyle.FIRE, "Mega Flame is a burning fireball that bursts");
        check(charge != null && charge.tip() < 1 && charge.runStart() > 0 && charge.air() != null
                && "great_antler_impact_fx".equals(charge.impact()), "Great Antler aims with the neck, charges from a run and has an air form");
        var slots = mount.riderAttacks();
        check(slots.size() == 2 && slots.get(0).aim() == RiderAttack.Aim.POUNCE && slots.get(1).aim() == RiderAttack.Aim.SHOT
                && slots.get(1).move() && slots.get(1).air(), "rider slots: the horn charge, then the fireball on the run and from a leap");

        // The clips, at the lengths the entity plays them for.
        var body = bodyClips();
        check(body.getAsJsonObject("mega_flame").get("length").getAsFloat() == flame.durationTicks()
                && body.getAsJsonObject("great_antler").get("length").getAsFloat() == antler.durationTicks()
                && body.getAsJsonObject("great_antler_air").get("length").getAsFloat() == charge.air().attack().durationTicks()
                && body.getAsJsonObject("jump").get("length").getAsFloat() == DigimonEntity.LEAP_END,
                "the moves and the leap last what the entity plays them for");
        var fx = clips();
        for (String name : List.of("mega_flame", "great_antler", "great_antler_air"))
            check(fx.has(name) && fx.getAsJsonObject(name).get("length").getAsFloat() == body.getAsJsonObject(name).get("length").getAsFloat(),
                    "the head's effect has a clip for " + name + " as long as the body's");
        var effects = definition.attackEffects();
        check(effects != null && effects.follow() != null && effects.follow().equals(HEAD) && "greymon_fx".equals(effects.effect())
                && effects.clips().keySet().containsAll(List.of("mega_flame", "great_antler", "great_antler_air")),
                "the head's effect is drawn in the head's frame for every move and form");

        // Planted: the walk's columns and the run's.
        state.ageInTicks = 0; state.gaitShares = new float[]{1, 0, 0, 0};
        state.groundRunAmount = 0; state.groundRunShare = -1;
        for (float amount : new float[]{.5F, 1}) {
            state.groundAnimationAmount = amount;
            planted(model, root, state, scale, cycle, gait.stride() * amount * scale / cycle, "walk at " + amount);
        }
        state.groundAnimationAmount = 1; state.groundRunAmount = 1;
        for (float share : new float[]{.45F, .7F, 1}) {
            state.groundRunShare = share;
            planted(model, root, state, scale, cycle, gait.runStride() * share * scale / cycle, "run at " + share);
        }
        // Mixed: in every mix the model makes of the gait clips (the idle into the walk and the walk's columns by its
        // amplitude, the run's columns by its share, the walk into the run as it crosses over) each knee stays in front of
        // the line from its hip to its ankle and within 30 degrees of straight ahead. The model adds the clips' weighted
        // angles, so a leg whose angles wrapped a whole turn in one clip, or whose hip twisted, bent its knee out sideways.
        float[][] mixes = {{.4F, 0, -1}, {.6F, 0, -1}, {.9F, 0, -1}, {1, 1, .58F}, {1, 1, .85F}, {1, 1, 1}, {1, .5F, .47F}, {1, .5F, .75F}};
        for (float[] mix : mixes) {
            state.groundAnimationAmount = mix[0]; state.groundRunAmount = mix[1]; state.groundRunShare = mix[2];
            for (int i = 0; i < Math.round(cycle * 4); i++) {
                state.groundAnimationPhase = i / 4F; model.setupAnim(state);
                for (String side : List.of("left", "right")) {
                    double splay = kneeSplay(root, side, scale);
                    worstKnee = Math.max(worstKnee, splay);
                    check(splay < 30, "the " + side + " knee turns " + splay + " degrees off ahead at " + i / 4F + " (amount " + mix[0]
                            + ", run " + mix[1] + ", share " + mix[2] + ")");
                }
            }
        }
        state.groundRunAmount = 0; state.groundRunShare = -1;

        // Turning on the spot: the body goes round its centre on the phase the entity pays the turn with, and the standing
        // foot stays put on the ground.
        for (float amount : new float[]{.5F, 1}) for (int way : new int[]{1, -1}) {
            state.gaitShares = new float[]{1, 0, 0, 0}; state.groundAnimationAmount = amount; state.pivotTurn = way;
            float degrees = 4;
            double perPhase = way * degrees / gait.advance(gait.pivotTravel(degrees, 0), amount, scale, 0);
            int steps = Math.round(cycle * 8);
            var world = new Vec3[2][steps + 1][];
            double[] ground = {Double.MAX_VALUE, Double.MAX_VALUE};
            for (int i = 0; i <= steps; i++) {
                state.groundAnimationPhase = i / 8F; model.setupAnim(state);
                double yaw = Math.toRadians(perPhase * i / 8F), cos = Math.cos(yaw), sin = Math.sin(yaw);
                for (int f = 0; f < 2; f++) {
                    Vec3[] s = sole(root, f == 0 ? "left" : "right", scale);
                    for (int k = 0; k < s.length; k++) s[k] = new Vec3(s[k].x * cos - s[k].z * sin, s[k].y, s[k].x * sin + s[k].z * cos);
                    world[f][i] = s;
                    ground[f] = Math.min(ground[f], s[lowest(s)].y);
                }
            }
            for (int f = 0; f < 2; f++) {
                int standing = 0;
                for (int i = 0; i < steps; i++) {
                    int k = lowest(world[f][i]);
                    if (world[f][i][k].y > ground[f] + .004 || world[f][i + 1][k].y > ground[f] + .004) continue;
                    standing++;
                    double moved = world[f][i + 1][k].subtract(world[f][i][k]).horizontalDistance();
                    worstPivot = Math.max(worstPivot, moved);
                    check(moved < .008, "turning " + (way > 0 ? "right" : "left") + " at " + amount + ", the " + (f == 0 ? "left" : "right")
                            + " foot slides at " + i / 8F + ": " + moved);
                }
                check(standing >= 8, "turning on the spot, the " + (f == 0 ? "left" : "right") + " foot stands between its steps: " + standing);
            }
            state.pivotTurn = 0;
        }
        state.groundAnimationAmount = 0;

        // The seat.
        state.isBeingRidden = true; state.ageInTicks = 0; state.mountAnchor = mount.position(0);
        Vec3 seated = model.riderOffset(state);
        check(seated.length() < .06, "at rest the rider is drawn at the sheet's seat: " + seated);
        // Walking and running the trunk leans into its pace (the seat goes forward with it, steadily) and bobs and sways
        // over it: the lean stays modest and the rock about it small.
        double walkDrift = 0, runDrift = 0;
        double[][] walkBox = {{9, 9, 9}, {-9, -9, -9}}, runBox = {{9, 9, 9}, {-9, -9, -9}};
        state.groundAnimationAmount = 1;
        for (float t = 0; t < cycle; t += .25F) {
            state.groundAnimationPhase = t; state.groundRunAmount = 0; state.groundRunShare = -1;
            Vec3 w = model.riderOffset(state);
            walkDrift = Math.max(walkDrift, w.length());
            state.groundRunAmount = 1; state.groundRunShare = 1;
            Vec3 r = model.riderOffset(state);
            runDrift = Math.max(runDrift, r.length());
            double[][] v = {{w.x, w.y, w.z}, {r.x, r.y, r.z}};
            for (int i = 0; i < 3; i++) {
                walkBox[0][i] = Math.min(walkBox[0][i], v[0][i]); walkBox[1][i] = Math.max(walkBox[1][i], v[0][i]);
                runBox[0][i] = Math.min(runBox[0][i], v[1][i]); runBox[1][i] = Math.max(runBox[1][i], v[1][i]);
            }
        }
        double walkRock = Math.max(walkBox[1][0] - walkBox[0][0], Math.max(walkBox[1][1] - walkBox[0][1], walkBox[1][2] - walkBox[0][2]));
        double runRock = Math.max(runBox[1][0] - runBox[0][0], Math.max(runBox[1][1] - runBox[0][1], runBox[1][2] - runBox[0][2]));
        check(walkDrift < .25 && walkRock < .2, "walking the rider leans and rides the trunk's bob and sway: " + walkDrift + ", rock " + walkRock);
        check(runDrift < .5 && runRock < .25, "running the rider leans and rides the trunk's rock: " + runDrift + ", rock " + runRock);
        state.isBeingRidden = false; state.groundAnimationAmount = 0; state.groundRunAmount = 0; state.groundRunShare = -1;

        // Great Antler: the drawn horn is the server's through the charge, level and aimed up or down.
        Vec3 groundPivot = new Vec3(0, species.body().dimensions().height() * .5, 0);
        state.attackDefinition = antler; state.attackAnimationName = "great_antler"; state.attackAnimation.start(0);
        for (float pitch : new float[]{0, 25, -20}) {
            state.pouncePitch = pitch;
            for (float t = definition.attackBlendIn(); t <= antler.durationTicks() - definition.attackBlendOut(); t += .25F) {
                state.ageInTicks = t;
                model.setupAnim(state);
                var frame = antler.motion().sample(t);
                Vec3 drawn = point(root, NASAL_TIP, scale, 1, -5, 1);
                Vec3 expected = PounceLines.posed(groundPivot, charge, frame, frame.hornTip(), 0, pitch);
                double e = drawn.distanceTo(expected);
                if (pitch == 0) worstHorn = Math.max(worstHorn, e); else worstAimed = Math.max(worstAimed, e);
                check(e < .1, "the horn drawn apart from where it strikes at " + t + ", aimed " + pitch + ": " + e);
            }
        }
        state.pouncePitch = 0;
        // From a leap the air form's clip plays: the head is lowered into its lance earlier than the ground charge's.
        state.ageInTicks = 4; state.attackAir = false; model.setupAnim(state);
        float groundHead = (float) Math.toDegrees(root.getChild("root").getChild("pelvis").xRot);
        state.attackAir = true; model.setupAnim(state);
        float airHead = (float) Math.toDegrees(root.getChild("root").getChild("pelvis").xRot);
        check(Math.abs(airHead - groundHead) > 1, "from a leap the air form's own clip plays: " + groundHead + " vs " + airHead);
        state.attackAir = false;

        // Mega Flame: the ball leaves from between the drawn jaws at the release, and the head's effect is drawn on the head.
        state.attackDefinition = flame; state.attackAnimationName = "mega_flame"; state.attackAnimation.start(0);
        state.attackAimPitch = 0; state.ageInTicks = flame.hitTick() - .01F;
        model.setupAnim(state);
        Vec3 upper = point(root, HEAD, scale, 0, 6, -49.5F), lower = point(root, JAW, scale, 0, -4.3F, -36.76F);
        Vec3 jaws = upper.add(lower).scale(.5);
        worstMuzzle = jaws.distanceTo(shot.motion().sample(flame.hitTick() - .01F).muzzle());
        check(worstMuzzle < .04, "the ball leaves from between the drawn jaws: " + worstMuzzle);
        Vec3 head = point(root, HEAD, scale, 0, 0, 0);
        Vec3 followed = new Vec3(state.drawnFollow[0] / 16, 1.5 - state.drawnFollow[1] / 16, -state.drawnFollow[2] / 16).scale(scale);
        check(head.distanceTo(followed) < 1.0E-3, "the head's effect is placed on the head as drawn: " + head.distanceTo(followed));
        var headPart = root.getChild("root").getChild("pelvis").getChild("torso").getChild("neck").getChild("head");
        check(Math.abs(state.drawnFollow[3]) > 1.0E-4 || Math.abs(headPart.xRot) < 1.0E-4, "the head's effect turns with the head");

        System.out.println(String.format(Locale.ROOT, "Greymon native checks passed: %d checks, feet planted within %.4f blocks a quarter tick, "
                        + "knees within %.1f degrees of ahead in every gait mix, turning within %.4f, horn within %.4f level and %.4f aimed, "
                        + "ball from the jaws within %.4f, seat %.3f, walk lean %.3f rock %.3f, run lean %.3f rock %.3f", checks, worstPlant, worstKnee,
                worstPivot, worstHorn, worstAimed, worstMuzzle, seated.length(), walkDrift, walkRock, runDrift, runRock));
    }
}
