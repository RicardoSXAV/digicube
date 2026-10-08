package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.AuthoredAttacks;
import com.digicube.digimon.CompoundAttacks;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.KineticAttacks;
import com.digicube.digimon.PounceAttacks;
import com.digicube.entity.AttackStance;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.PounceLines;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Leomon as the compiled NativeGroundModel draws it:
 * <ul>
 * <li>every gait is planted: whatever point of a foot's sole stands lowest (the heel, the pad's front or the claw tips)
 *     stays put on the ground while it stands, through the walk's amplitudes, backwards, aside, the run's columns and
 *     turning on the spot, upright and crouched (the {@code _crouch} twins), and part way into the crouch (on the twins'
 *     shared footfalls);</li>
 * <li>the paws stay on the floor ({@code paws.floor}): part way into a crouch and turning as it walks (gait clips mixed by
 *     their weights, which alone would sink the feet) no foot sinks into the ground nor floats over it, and the gaits as
 *     authored are left as they are;</li>
 * <li>crouched, the head stays under the crouched box; through the roll's tucked ticks the body stays under the roll's
 *     box; in the air the tuck ({@code jump_crouch}) is more compact than the leap and lands into the crouch;</li>
 * <li>Lion Sword: the hilt on the back and the sword in the hand follow the stance (the hilt until the draw's swap and from
 *     the sheathe's, the sword between), and the draw and the sheathe are as long and swap where compound_attacks.json
 *     says; the stab's blade is where the server's pounce line has its contact segment, level and aimed, on the ground, from
 *     a run (its own contact points, {@code run_motion}) and from the air; the aimed slashes' blade is where the server
 *     leans their volumes at any aim pitch;</li>
 * <li>Beast King Fist: the shot leaves from the fist the server launches it from (kinetic_motion's muzzle); the aura and
 *     the sword's speed lines draw full-bright;</li>
 * <li>across every clip's pose as drawn (the gaits, crouched or not, the leap, the roll, the stance and every strike, the
 *     sword out or away) no two faces share a plane where they overlap (they would flicker), and on the ground the tail
 *     and the mane's locks never sink into it.</li>
 * </ul>
 * Reported, not held: a walking turn's and a diagonal's feet along the ground on the walk's footfalls, the shot cast on the
 * run against the standing muzzle.
 */
public final class NativeLeomonRegressionTest {
    private static int checks;
    /** Blocks a standing sole point may move in the world in a quarter tick. */
    private static final double LIMIT = .008;
    /** A measured number the test reports without holding it, printed as it is found. */
    private static void report(String line) { System.out.println(line); }
    private static double worstUpright, worstCrouched, worstPartial, worstRun, worstPivot, worstFloor, worstTurnFloor, mostRaised, worstBlade, worstSlash, worstMuzzle;

    /** A motion's point leaned as the server leans an aimed volume (AttackBox.aimed): about the head, down by aim x aim weight. */
    private static Vec3 leaned(Vec3 point, com.digicube.digimon.AttackMotion.Frame frame, float aim) {
        double a = Math.toRadians(aim * frame.aimWeight()), c = Math.cos(a), s = Math.sin(a);
        Vec3 v = point.subtract(frame.head());
        return frame.head().add(v.x, v.y * c - v.z * s, v.y * s + v.z * c);
    }

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    private static final List<String> LEFT_FOOT = List.of("root", "pelvis", "left_thigh", "left_shin", "left_foot");
    private static final List<String> RIGHT_FOOT = List.of("root", "pelvis", "right_thigh", "right_shin", "right_foot");
    private static final List<List<String>> FEET = List.of(LEFT_FOOT, RIGHT_FOOT);
    private static final List<String> SWORD = List.of("root", "pelvis", "torso", "left_upper_arm", "left_forearm", "left_hand", "ls_root");
    private static final List<String> HILT = List.of("root", "pelvis", "belt", "stowed_sword", "stowed_sword_hilt");
    private static final List<String> FIST = List.of("root", "pelvis", "torso", "right_upper_arm", "right_forearm", "right_hand");
    /** A foot's sole in its own frame (model px): the heel, the pad's front and the claw tips; one of them bears the body. */
    private static final float[][] SOLE = {{0, 12.5F, 8.5F}, {0, 15.5F, -22}, {0, 15, -28}};
    /** The blade's contact segment in the sword's frame (model px): where the blade leaves the guard, and its point. */
    private static final float[] BLADE_BASE = {0, -24, 0}, BLADE_TIP = {1, -56.5F, 0};
    /** The fist's knuckles in the right hand's frame (model px): where the shot leaves. */
    private static final float[] KNUCKLES = {7, -1.5F, 2.5F};
    /** Hair and fur that flick past the body (the roll's box holds the body, not its locks). */
    private static final List<String> HAIR = List.of("tail_", "crown_", "left_mane", "right_mane", "left_rear_lock", "right_rear_lock",
            "left_cheek_fur", "right_cheek_fur", "left_beard", "right_beard", "beard_center");

    // --- the body's points ------------------------------------------------------------------------------------------

    /** A point in a part's frame (model px) in the entity's frame at yaw 0 (blocks, x his left, z forward, feet at 0). */
    private static Vec3 point(ModelPart root, List<String> path, float scale, float x, float y, float z) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(x / 16, y / 16, z / 16, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    private static Vec3 point(ModelPart root, List<String> path, float scale, float[] at) { return point(root, path, scale, at[0], at[1], at[2]); }

    private static ModelPart part(ModelPart root, List<String> path) {
        ModelPart part = root;
        for (String name : path) part = part.getChild(name);
        return part;
    }

    private static Vec3[] sole(ModelPart root, List<String> foot, float scale) {
        Vec3[] out = new Vec3[SOLE.length];
        for (int i = 0; i < SOLE.length; i++) out[i] = point(root, foot, scale, SOLE[i]);
        return out;
    }

    private static int lowest(Vec3[] points) {
        int k = 0;
        for (int i = 1; i < points.length; i++) if (points[i].y < points[k].y) k = i;
        return k;
    }

    /** One drawn face: its part, corners (model px), unit normal and plane offset. */
    private record Face(String part, double[][] v, double[] n, double d) {}

    /** Every face the model draws as posed (shown parts only), in model px; {@code low} also gets each part's lowest point (blocks). */
    private static List<Face> faces(ModelPart root, NativeModelGeometry.Mesh mesh) {
        var out = new ArrayList<Face>();
        for (var p : mesh.parts()) {
            if (p.quads().length == 0) continue;
            var stack = new PoseStack();
            ModelPart part = root; part.translateAndRotate(stack);
            boolean shown = part.visible;
            for (String name : p.path()) { part = part.getChild(name); part.translateAndRotate(stack); shown &= part.visible; }
            if (!shown || part.skipDraw) continue;
            var m = stack.last().pose();
            for (var q : p.quads()) {
                double[][] v = new double[4][];
                for (int i = 0; i < 4; i++) {
                    var w = m.transformPosition(q.vertices()[i][0] / 16, q.vertices()[i][1] / 16, q.vertices()[i][2] / 16, new org.joml.Vector3f());
                    v[i] = new double[]{w.x * 16, w.y * 16, w.z * 16};
                }
                double[] n = cross(sub(v[1], v[0]), sub(v[2], v[0]));
                double length = Math.sqrt(dot(n, n));
                if (length < 1.0E-6) continue;
                n = new double[]{n[0] / length, n[1] / length, n[2] / length};
                out.add(new Face(p.name(), v, n, dot(n, v[0])));
            }
        }
        return out;
    }

    /** Height over the ground (blocks) of a model point in px (y down, ground at 24 px). */
    private static double height(double[] v, float scale) { return (24 - v[1]) / 16 * scale; }

    /** The lowest point of the feet's own faces as drawn (blocks over the ground). */
    private static double feetLow(ModelPart root, NativeModelGeometry.Mesh mesh, float scale) {
        double low = 9;
        for (var face : faces(root, mesh)) if (face.part().endsWith("_foot")) for (double[] v : face.v()) low = Math.min(low, height(v, scale));
        return low;
    }

    // --- planted feet -----------------------------------------------------------------------------------------------

    /** Where the body is at phase {@code t} of a gait: its heading (radians, turning right positive) and its feet (x, z). */
    private interface Travel { double[] at(double t); }

    /** Along a straight line at (vx, vz) blocks a phase tick in its own frame. */
    private static Travel straight(double vx, double vz) { return t -> new double[]{0, vx * t, vz * t}; }

    /** Forward at {@code forward} blocks a phase tick, its heading turning {@code turn} radians a phase tick (right positive). */
    private static Travel arc(double forward, double turn) {
        return t -> {
            if (Math.abs(turn) < 1.0E-9) return new double[]{0, 0, forward * t};
            double yaw = turn * t;
            return new double[]{yaw, forward * (Math.cos(yaw) - 1) / turn, forward * Math.sin(yaw) / turn};
        };
    }

    /** Each foot's sole points in the world through a cycle at quarter ticks: [foot][sample][point]. */
    private static Vec3[][][] soles(NativeGroundModel model, ModelPart root, DigimonRenderState state, float scale, int steps, Travel travel) {
        var at = new Vec3[2][steps + 1][];
        for (int i = 0; i <= steps; i++) {
            state.groundAnimationPhase = i / 4F; model.setupAnim(state);
            double[] where = travel.at(i / 4.0);
            double cos = Math.cos(where[0]), sin = Math.sin(where[0]);
            for (int f = 0; f < 2; f++) {
                Vec3[] s = sole(root, FEET.get(f), scale);
                for (int k = 0; k < s.length; k++) s[k] = new Vec3(s[k].x * cos - s[k].z * sin + where[1], s[k].y, s[k].x * sin + s[k].z * cos + where[2]);
                at[f][i] = s;
            }
        }
        return at;
    }

    /** The samples a foot stands on (its lowest sole point within 4 thousandths of a block of its lowest in the cycle). */
    private static boolean[] standing(Vec3[][] foot) {
        double ground = Double.MAX_VALUE;
        for (Vec3[] s : foot) ground = Math.min(ground, s[lowest(s)].y);
        boolean[] on = new boolean[foot.length];
        for (int i = 0; i < foot.length; i++) on[i] = foot[i][lowest(foot[i])].y <= ground + .004;
        return on;
    }

    /**
     * Through a whole cycle at quarter ticks, the lowest of each foot's sole points, while it stands, stays put on the ground
     * as the body travels (its move along the ground a quarter tick: the floor may raise the body as the clips mix, which
     * the floor's own checks hold). With a {@code reference} (a mixed pose: part way into the crouch), the foot stands where
     * the reference's does (the authored footfalls the mixed clips share).
     * @return the worst move a quarter tick
     */
    private static double planted(NativeGroundModel model, ModelPart root, DigimonRenderState state, float scale, float cycle, Travel travel,
                                  Consumer<DigimonRenderState> reference, String label) {
        int steps = Math.round(cycle * 4);
        var at = soles(model, root, state, scale, steps, travel);
        Vec3[][][] stance = at;
        if (reference != null) {
            var plain = copy(state);
            reference.accept(plain);
            stance = soles(model, root, plain, scale, steps, travel);
        }
        double worst = 0;
        for (int f = 0; f < 2; f++) {
            boolean[] on = standing(stance[f]);
            int pairs = 0;
            for (int i = 0; i < steps; i++) {
                if (!on[i] || !on[i + 1]) continue;
                pairs++;
                int k = lowest(at[f][i]);
                Vec3 moved = at[f][i + 1][k].subtract(at[f][i][k]);
                double error = Math.abs(moved.x) + Math.abs(moved.z);
                worst = Math.max(worst, error);
                check(error < LIMIT, label + ", " + FEET.get(f).getLast() + " slides at " + i / 4F + ": " + error);
            }
            check(pairs >= 4, label + ", " + FEET.get(f).getLast() + " never stands: " + pairs);
        }
        return worst;
    }

    private static DigimonRenderState copy(DigimonRenderState state) {
        var out = new DigimonRenderState();
        out.modelScale = state.modelScale; out.gaitShares = state.gaitShares.clone(); out.groundAnimationAmount = state.groundAnimationAmount;
        out.groundRunAmount = state.groundRunAmount; out.groundRunShare = state.groundRunShare; out.pivotTurn = state.pivotTurn;
        out.crouchWeight = state.crouchWeight; out.ageInTicks = state.ageInTicks;
        return out;
    }

    // --- states -------------------------------------------------------------------------------------------------------

    private static void reset(DigimonRenderState state) {
        state.gaitShares = new float[]{1, 0, 0, 0};
        state.groundAnimationAmount = 0; state.groundAnimationPhase = 0; state.groundRunAmount = 0; state.groundRunShare = -1;
        state.pivotTurn = 0; state.crouchWeight = 0; state.rollWeight = 0; state.rollTick = -1; state.leapTick = -1; state.leapWeight = 0;
        state.ageInTicks = 0; state.attackAnimation.stop(); state.attackAnimationName = null; state.attackDefinition = null;
        state.attackAir = false; state.attackRun = false; state.attackUpperBody = false; state.pouncePitch = 0; state.attackAimPitch = 0;
        state.chainFrom = null; state.glowSplit = false;
        stance(state, null, 0);
    }

    private static CompoundAttacks.Definition sword;

    /** The sword's stance as the renderer sets it (null phase: none). */
    private static void stance(DigimonRenderState state, AttackStance.Phase phase, float ticks) {
        state.stancePhase = phase;
        state.stanceMove = phase == null ? null : sword.attack().id().getPath();
        state.stanceCompound = phase == null ? null : sword;
        state.stanceTicks = ticks;
        state.stanceDrawn = phase != null && AttackStance.drawn(sword.stance(), phase, ticks);
    }

    /** A strike under way: its clip on its own clock, from the ground, a run or the air. */
    private static void strike(DigimonRenderState state, DigimonAttack attack, float tick, boolean run, boolean air) {
        state.attackDefinition = attack; state.attackAnimationName = attack.id().getPath(); state.attackAnimation.start(0);
        state.attackRun = run; state.attackAir = air; state.ageInTicks = tick;
    }

    private static JsonObject clips() {
        try (var input = NativeLeomonRegressionTest.class.getResourceAsStream("/assets/digicube/models/entity/leomon.animation.json")) {
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject("clips");
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    /** The first time a clip's visibility key turns {@code part} to {@code shown} after its start, or -1. */
    private static float turns(JsonObject clips, String clip, String part, boolean shown) {
        var steps = clips.getAsJsonObject(clip).getAsJsonObject("visibility").getAsJsonArray(part);
        for (int i = 1; i < steps.size(); i++) {
            var step = steps.get(i).getAsJsonArray();
            if (step.get(1).getAsBoolean() == shown && steps.get(i - 1).getAsJsonArray().get(1).getAsBoolean() != shown) return step.get(0).getAsFloat();
        }
        return -1;
    }

    /** The definition with other paws (the record's own constructor, every other component as it is). */
    private static NativeGroundModel.Definition withPaws(NativeGroundModel.Definition definition, NativeGroundModel.Paws paws) throws ReflectiveOperationException {
        var components = NativeGroundModel.Definition.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals("paws") ? paws : components[i].getAccessor().invoke(definition);
        }
        return NativeGroundModel.Definition.class.getDeclaredConstructor(types).newInstance(values);
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("leomon"));
        float scale = species.body().modelScale();
        var gait = species.locomotion().groundGait();
        var crouch = species.body().crouch();
        var roll = crouch.roll();
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var mesh = NativeModelGeometry.mesh(definition.geometry());
        var state = new DigimonRenderState(); state.modelScale = scale;
        float cycle = gait.cycleTicks();
        var clips = clips();

        // The moves as data: Lion Sword a stance whose forms are the slash combo and the stab, Beast King Fist a gauge whose
        // forms are the punch and the shot.
        sword = CompoundAttacks.get(species.attacks().get(0));
        var fist = CompoundAttacks.get(species.attacks().get(1));
        check(sword != null && sword.stance() != null && fist != null && fist.gauge() != null, "Lion Sword has a stance and Beast King Fist a gauge");
        DigimonAttack stab = null, punch = fist.forms().get(0).attack(), shot = fist.forms().get(1).attack();
        List<DigimonAttack> slashes = new ArrayList<>();
        for (var form : sword.forms()) {
            if (PounceAttacks.handles(form.attack())) stab = form.attack();
            else if (!slashes.contains(form.attack())) slashes.add(form.attack());
        }
        var combo = AuthoredAttacks.forms(slashes.getFirst());
        if (combo != null) slashes = combo.all();
        check(stab != null && slashes.size() == 3 && PounceAttacks.handles(punch) && KineticAttacks.handles(shot),
                "the stab and the punch are pounces, the slash three combo forms, the shot a kinetic shot");
        var stabSpec = PounceAttacks.get(stab);
        var shotSpec = KineticAttacks.get(shot);

        // ---------------------------------------------------------------------------------------------- planted gaits
        // Upright and crouched (the _crouch twins whole): the walk's amplitudes, backwards, aside, turning on the spot.
        for (float c : new float[]{0, 1}) {
            String bent = c == 0 ? "" : " crouched";
            for (float amount : new float[]{.25F, .375F, .5F, .625F, .75F, .875F, 1}) for (float age : new float[]{0, 61}) {
                reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.ageInTicks = age;
                double w = planted(model, root, state, scale, cycle, straight(0, gait.stride() * amount * scale / cycle), null, "walk at " + amount + bent);
                if (c == 0) worstUpright = Math.max(worstUpright, w); else worstCrouched = Math.max(worstCrouched, w);
            }
            for (float amount : new float[]{.5F, .75F, 1}) {
                double back = gait.backStride() * amount * scale / cycle, side = gait.sideStride() * amount * scale / cycle;
                Object[][] ways = {{"back", new float[]{0, 1, 0, 0}, straight(0, -back)}, {"left", new float[]{0, 0, 1, 0}, straight(side, 0)},
                        {"right", new float[]{0, 0, 0, 1}, straight(-side, 0)}};
                for (Object[] way : ways) {
                    reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.gaitShares = (float[]) way[1];
                    double w = planted(model, root, state, scale, cycle, (Travel) way[2], null, way[0] + " at " + amount + bent);
                    if (c == 0) worstUpright = Math.max(worstUpright, w); else worstCrouched = Math.max(worstCrouched, w);
                }
            }
            // turning on the spot: the body goes round its centre on the phase the entity pays the turn with
            for (float amount : new float[]{.25F, .5F, .75F, 1}) for (int way : new int[]{1, -1}) {
                reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.pivotTurn = way;
                double turn = Math.toRadians(way / gait.advance(gait.pivotTravel(1, 0), amount, scale, 0));
                worstPivot = Math.max(worstPivot, planted(model, root, state, scale, cycle, arc(0, turn), null,
                        "turning " + (way > 0 ? "right" : "left") + " at " + amount + bent));
            }
        }
        // Part way into the crouch: the gait and its twin mixed, standing on the footfalls they share, held along the ground.
        Consumer<DigimonRenderState> upright = s -> s.crouchWeight = 0;
        for (float c : new float[]{.25F, .5F, .75F}) {
            String bent = " crouched " + c;
            for (float amount : new float[]{.25F, .5F, .75F, 1}) {
                reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount;
                worstPartial = Math.max(worstPartial, planted(model, root, state, scale, cycle, straight(0, gait.stride() * amount * scale / cycle),
                        upright, "walk at " + amount + bent));
            }
            for (float amount : new float[]{.5F, 1}) {
                double back = gait.backStride() * amount * scale / cycle, side = gait.sideStride() * amount * scale / cycle;
                Object[][] ways = {{"back", new float[]{0, 1, 0, 0}, straight(0, -back)}, {"left", new float[]{0, 0, 1, 0}, straight(side, 0)},
                        {"right", new float[]{0, 0, 0, 1}, straight(-side, 0)}};
                for (Object[] way : ways) {
                    reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.gaitShares = (float[]) way[1];
                    worstPartial = Math.max(worstPartial, planted(model, root, state, scale, cycle, (Travel) way[2], upright, way[0] + " at " + amount + bent));
                }
                for (int way : new int[]{1, -1}) {
                    reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.pivotTurn = way;
                    double turn = Math.toRadians(way / gait.advance(gait.pivotTravel(1, 0), amount, scale, 0));
                    worstPartial = Math.max(worstPartial, planted(model, root, state, scale, cycle, arc(0, turn), upright,
                            "turning " + (way > 0 ? "right" : "left") + " at " + amount + bent));
                }
            }
        }
        // The run's columns (a crouch at a run rolls: the run has no twin).
        for (float share : new float[]{.55F, .65F, .75F, .875F, 1}) {
            reset(state); state.groundAnimationAmount = 1; state.groundRunAmount = 1; state.groundRunShare = share;
            worstRun = Math.max(worstRun, planted(model, root, state, scale, cycle, straight(0, gait.runStride() * share * scale / cycle), null, "run at " + share));
        }

        // ------------------------------------------------------------------------------------------- the paws' floor
        // The same body without the floor, to see what it does: the gaits mixed by their weights sink the feet, and the floor
        // raises the body out of the ground; the gaits as authored it leaves as they are.
        var sinking = definition.paws();
        var plainRoot = definition.createLayer().bakeRoot();
        var plain = new NativeGroundModel(plainRoot, withPaws(definition, new NativeGroundModel.Paws(sinking.feet(), sinking.sound(), sinking.volume(),
                sinking.pitch(), sinking.replaces(), false)));
        check(sinking.floor(), "Leomon's paws are kept on the floor");
        double sunk = 0;
        // part way into the crouch: standing, walking, backing and turning on the spot
        for (float c : new float[]{.25F, .5F, .75F}) {
            Object[][] cases = {{"standing", 0F, new float[]{1, 0, 0, 0}, 0}, {"walking", .5F, new float[]{1, 0, 0, 0}, 0},
                    {"walking", 1F, new float[]{1, 0, 0, 0}, 0}, {"backing", 1F, new float[]{0, 1, 0, 0}, 0},
                    {"turning right", .5F, new float[]{1, 0, 0, 0}, 1}, {"turning left", 1F, new float[]{1, 0, 0, 0}, -1}};
            for (Object[] row : cases) for (int i = 0; i < 80; i++) {
                reset(state); state.crouchWeight = c; state.groundAnimationAmount = (float) row[1]; state.gaitShares = (float[]) row[2];
                state.pivotTurn = (int) row[3]; state.groundAnimationPhase = i / 4F; state.ageInTicks = i * 1.8F;
                model.setupAnim(state);
                double low = feetLow(root, mesh, scale);
                worstFloor = Math.max(worstFloor, Math.abs(low));
                check(Math.abs(low) < .005, row[0] + " at " + row[1] + " crouched " + c + ", the feet off the floor at " + i / 4F + ": " + low);
                plain.setupAnim(state);
                sunk = Math.max(sunk, -feetLow(plainRoot, mesh, scale));
            }
        }
        check(sunk > .03, "without the floor the mixed crouch would sink the feet: " + sunk);
        // turning as it walks (the walk and the pivot by their shares), upright and crouched
        double turnSunk = 0;
        for (float c : new float[]{0, 1}) for (float amount : new float[]{.5F, 1}) for (float share : new float[]{.25F, .5F, .75F}) for (int way : new int[]{1, -1}) {
            for (int i = 0; i < 80; i++) {
                reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.pivotTurn = way * share; state.groundAnimationPhase = i / 4F;
                model.setupAnim(state);
                double low = feetLow(root, mesh, scale);
                worstTurnFloor = Math.max(worstTurnFloor, Math.abs(low));
                check(Math.abs(low) < .01, "a walking turn " + share + " at " + amount + (c > 0 ? " crouched" : "") + ", the feet off the floor at " + i / 4F + ": " + low);
                plain.setupAnim(state);
                turnSunk = Math.max(turnSunk, -feetLow(plainRoot, mesh, scale));
            }
        }
        check(turnSunk > .03, "without the floor a walking turn would sink the feet: " + turnSunk);
        // the gaits as authored (every lattice's own columns, the idle, the run's): the floor raises them no more than a few
        // thousandths of a block
        Consumer<String> pure = label -> {
            for (int i = 0; i < 80; i++) {
                state.groundAnimationPhase = i / 4F; state.ageInTicks = i * 1.8F;
                model.setupAnim(state); plain.setupAnim(state);
                double raised = point(root, List.of("root", "pelvis"), scale, 0, 0, 0).y - point(plainRoot, List.of("root", "pelvis"), scale, 0, 0, 0).y;
                mostRaised = Math.max(mostRaised, raised);
                check(raised < .008, label + ": the floor raises an authored gait by " + raised);
            }
        };
        for (float c : new float[]{0, 1}) {
            reset(state); state.crouchWeight = c; pure.accept("standing" + c);
            for (float amount : new float[]{.25F, .5F, .75F, 1}) {
                reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; pure.accept("walk " + amount + " " + c);
                state.pivotTurn = 1; pure.accept("turning right " + amount + " " + c);
                state.pivotTurn = -1; pure.accept("turning left " + amount + " " + c);
                state.pivotTurn = 0;
                if (amount < .5F) continue;
                state.gaitShares = new float[]{0, 1, 0, 0}; pure.accept("back " + amount + " " + c);
                state.gaitShares = new float[]{0, 0, 1, 0}; pure.accept("left " + amount + " " + c);
                state.gaitShares = new float[]{0, 0, 0, 1}; pure.accept("right " + amount + " " + c);
            }
        }
        for (float share : new float[]{.55F, .75F, 1}) { reset(state); state.groundAnimationAmount = 1; state.groundRunAmount = 1; state.groundRunShare = share; pure.accept("run " + share); }

        // (reported, not held: a walking turn's feet along the ground on the walk's footfalls, and a diagonal)
        double walkingTurn = 0, walkingTurnCrouched = 0, diagonal = 0;
        Consumer<DigimonRenderState> walkOnly = s -> s.pivotTurn = 0;
        for (float c : new float[]{0, 1}) for (float amount : new float[]{.5F, .75F, 1}) for (float share : new float[]{.25F, .5F}) for (int way : new int[]{1, -1}) {
            reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.pivotTurn = way * share;
            double whole = Math.toRadians(1 / gait.advance(gait.pivotTravel(1, 0), amount, scale, 0));
            double w = measured(model, root, state, scale, cycle, arc((1 - share) * gait.stride() * amount * scale / cycle, way * share * whole), walkOnly);
            if (c == 0) walkingTurn = Math.max(walkingTurn, w); else walkingTurnCrouched = Math.max(walkingTurnCrouched, w);
        }
        for (float c : new float[]{0, 1}) for (float amount : new float[]{.75F, 1}) {
            reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.gaitShares = new float[]{.6F, 0, .4F, 0};
            diagonal = Math.max(diagonal, measured(model, root, state, scale, cycle,
                    straight(.4 * gait.sideStride() * amount * scale / cycle, .6 * gait.stride() * amount * scale / cycle), s -> s.gaitShares = new float[]{1, 0, 0, 0}));
        }
        report(String.format(Locale.ROOT, "walking turns slide %.4f upright and %.4f crouched along the ground on the walk's footfalls, a diagonal %.4f (reported)",
                walkingTurn, walkingTurnCrouched, diagonal));

        // ------------------------------------------------------------------------------------- crouch, roll and tuck
        // Crouched, the head stays under the crouched box: standing in the crouch's idle and through every crouched gait.
        double crouchHead = 0, crouchHair = 0;
        for (int t = 0; t < 144; t += 2) {
            reset(state); state.crouchWeight = 1; state.ageInTicks = t; model.setupAnim(state);
            var tops = tops(root, mesh, scale);
            crouchHead = Math.max(crouchHead, tops.head()); crouchHair = Math.max(crouchHair, tops.all());
        }
        for (float amount : new float[]{.25F, .5F, .75F, 1}) for (float[] shares : new float[][]{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}})
            for (float pivot : new float[]{0, 1, -1}) {
                if (pivot != 0 && shares[0] < 1) continue;
                for (int i = 0; i < 80; i++) {
                    reset(state); state.crouchWeight = 1; state.groundAnimationAmount = amount; state.gaitShares = shares; state.pivotTurn = pivot;
                    state.groundAnimationPhase = i / 4F; state.ageInTicks = i * 1.8F;
                    model.setupAnim(state);
                    var tops = tops(root, mesh, scale);
                    crouchHead = Math.max(crouchHead, tops.head()); crouchHair = Math.max(crouchHair, tops.all());
                }
            }
        check(crouchHead < crouch.height(), "crouched, the head stays under the crouched box: " + crouchHead + " of " + crouch.height());
        // The roll: from a quarter tick into its box's tucked ticks until the box stands again, the head and the body (its
        // locks and tail aside) stay under the roll's box (the clip dives under it as the box tucks).
        double rollHead = 0, rollBody = 0, rollEdge;
        String rollTop = "";
        reset(state); state.rollWeight = 1; state.rollTick = roll.lowFrom(); model.setupAnim(state);
        rollEdge = tops(root, mesh, scale).body();
        for (float t = roll.lowFrom() + .25F; t < roll.lowUntil(); t += .25F) {
            reset(state); state.rollWeight = 1; state.rollTick = t; model.setupAnim(state);
            var tops = tops(root, mesh, scale);
            rollHead = Math.max(rollHead, tops.head());
            if (tops.body() > rollBody) { rollBody = tops.body(); rollTop = tops.bodyPart() + " at " + t; }
        }
        check(rollHead < roll.height() && rollBody < roll.height(), "through the roll's tucked ticks the body stays under its box: head " + rollHead
                + ", body " + rollBody + " (" + rollTop + ") of " + roll.height());
        // The tuck in the air (jump_crouch on the leap's clock): more compact than the leap through the flight (its height from
        // the feet to the top less), and landing into the crouch, the head under the crouched box.
        double tuckLess = Double.MAX_VALUE, landedHead = 0;
        for (float t = DigimonEntity.LEAP_RISE + 2; t <= DigimonEntity.LEAP_LAND - 2; t += .5F) {
            reset(state); state.leapWeight = 1; state.leapTick = t; model.setupAnim(state);
            double leap = tops(root, mesh, scale).body() - feetLow(root, mesh, scale);
            state.crouchWeight = 1; model.setupAnim(state);
            double tuck = tops(root, mesh, scale).body() - feetLow(root, mesh, scale);
            tuckLess = Math.min(tuckLess, leap - tuck);
        }
        for (float t = DigimonEntity.LEAP_LAND + 3; t <= DigimonEntity.LEAP_END; t += .5F) {
            reset(state); state.leapWeight = 1; state.leapTick = t; state.crouchWeight = 1; model.setupAnim(state);
            landedHead = Math.max(landedHead, tops(root, mesh, scale).head());
        }
        check(tuckLess > 0, "in the air the tuck is more compact than the leap: " + tuckLess);
        check(landedHead < crouch.height(), "the tuck lands into the crouch, the head under its box: " + landedHead);
        report(String.format(Locale.ROOT, "crouched head %.3f (locks %.3f) under %.2f; roll head %.3f, body %.3f (%s) under %.2f (%.3f at the tuck's first instant); "
                        + "tuck %.3f more compact than the leap, landed head %.3f", crouchHead, crouchHair, crouch.height(), rollHead, rollBody, rollTop,
                roll.height(), rollEdge, tuckLess, landedHead));

        // ------------------------------------------------------------------------------------------------ Lion Sword
        // The stance's clips: as long as the stance and swapping where it says, by their own visibility keys too.
        var spec = sword.stance();
        String drawClip = sword.attack().id().getPath() + "_draw", sheatheClip = sword.attack().id().getPath() + "_sheathe";
        check(clips.getAsJsonObject(drawClip).get("length").getAsFloat() == spec.draw() && clips.getAsJsonObject(sheatheClip).get("length").getAsFloat() == spec.sheathe(),
                "the draw and the sheathe last as long as the stance's");
        check(turns(clips, drawClip, "ls_root", true) == spec.drawSwap() && turns(clips, drawClip, "stowed_sword_hilt", false) == spec.drawSwap()
                && turns(clips, sheatheClip, "ls_root", false) == spec.sheatheSwap() && turns(clips, sheatheClip, "stowed_sword_hilt", true) == spec.sheatheSwap(),
                "the draw and the sheathe swap the sword where the stance does");
        // The sword in the hand and the hilt on the back follow the stance every frame, standing or running.
        for (float run : new float[]{0, 1}) {
            for (var phase : AttackStance.Phase.values()) {
                int length = switch (phase) { case DRAW -> spec.draw(); case HOLD -> spec.hold(); case SHEATHE -> spec.sheathe(); };
                for (float t = 0; t < length; t += .25F) {
                    reset(state); state.groundAnimationAmount = run; state.groundRunAmount = run; state.groundAnimationPhase = t;
                    stance(state, phase, t); state.ageInTicks = t;
                    model.setupAnim(state);
                    boolean out = switch (phase) { case DRAW -> t >= spec.drawSwap(); case HOLD -> true; case SHEATHE -> t < spec.sheatheSwap(); };
                    check(part(root, SWORD).visible == out && part(root, HILT).visible != out,
                            phase + " at " + t + (run > 0 ? " running" : "") + ": the sword " + (out ? "in the hand" : "on the back"));
                }
            }
            reset(state); state.groundAnimationAmount = run; state.groundRunAmount = run; model.setupAnim(state);
            check(!part(root, SWORD).visible && part(root, HILT).visible, "no stance: the sword on the back");
        }
        // The stab's blade is where the server's pounce line has its contact segment, level and aimed (the body tips its
        // share about the pounce's pivot, the torso turns the rest about the body's own axis): on the ground, from a run (its
        // run clip striking with its own contact points, run_motion) and in the air (its air form, tipped whole).
        Vec3 pivot = new Vec3(0, species.body().dimensions().height() * .5, 0);
        check(stabSpec.forRun(true) != stabSpec && PounceAttacks.get(punch).forRun(true) != PounceAttacks.get(punch),
                "the stab and the punch strike from a run with their run clips' contact points");
        for (int form : new int[]{PounceAttacks.Spec.GROUND, PounceAttacks.Spec.AIR, PounceAttacks.Spec.RUNNING}) {
            var line = stabSpec.forAir(form == PounceAttacks.Spec.AIR).forRun(form == PounceAttacks.Spec.RUNNING);
            String name = form == PounceAttacks.Spec.AIR ? "air" : form == PounceAttacks.Spec.RUNNING ? "run" : "ground";
            float[] pitches = form == PounceAttacks.Spec.AIR ? new float[]{0, -40, 25} : new float[]{0, 20, -15};
            for (float pitch : pitches) {
                float from = Math.max(definition.attackBlendIn(), stabSpec.startTick(form));
                for (float t = from; t <= stab.durationTicks() - definition.attackBlendOut(); t += .25F) {
                    reset(state); stance(state, AttackStance.Phase.HOLD, 20);
                    strike(state, stab, t, form == PounceAttacks.Spec.RUNNING, form == PounceAttacks.Spec.AIR);
                    state.pouncePitch = pitch;
                    model.setupAnim(state);
                    var frame = line.attack().motion().sample(t);
                    double e = Math.max(point(root, SWORD, scale, BLADE_TIP).distanceTo(PounceLines.posed(pivot, line, frame, frame.hornTip(), 0, pitch)),
                            point(root, SWORD, scale, BLADE_BASE).distanceTo(PounceLines.posed(pivot, line, frame, frame.hornBase(), 0, pitch)));
                    worstBlade = Math.max(worstBlade, e);
                    check(e < .03, "the stab's blade (" + name + ", aimed " + pitch + ") apart from the server's at " + t + ": " + e);
                }
            }
        }
        // The slashes are aimed (their entries' `aimed`): at any aim pitch the drawn blade is where the server leans the
        // motion's blade (about its head, by the pitch times the aim weight) through every hit window.
        for (var slash : slashes) {
            check(com.digicube.entity.AuthoredVolumeAttack.aims(slash), slash.id().getPath() + " is aimed");
            var windows = AuthoredAttacks.get(slash).hitWindows();
            // every half tick of the windows: an instant both the server's samples and the clip's clock (whole milliseconds) hold exactly
            for (float aim : new float[]{0, 30, -15}) for (var window : windows) for (double t = Math.ceil(window[0] * 2) / 2; t <= window[1]; t += .5) {
                reset(state); stance(state, AttackStance.Phase.HOLD, 20);
                strike(state, slash, (float) t, false, false);
                state.attackAimPitch = aim;
                model.setupAnim(state);
                var frame = slash.motion().sample(t);
                double e = Math.max(point(root, SWORD, scale, BLADE_TIP).distanceTo(leaned(frame.hornTip(), frame, aim)),
                        point(root, SWORD, scale, BLADE_BASE).distanceTo(leaned(frame.hornBase(), frame, aim)));
                worstSlash = Math.max(worstSlash, e);
                check(e < .03, slash.id().getPath() + "'s blade aimed " + aim + " apart from the server's at " + t + ": " + e);
            }
        }

        // ------------------------------------------------------------------------------------------ Beast King Fist
        // The shot leaves from the fist the server launches it from, the sword out or away.
        for (boolean drawn : new boolean[]{false, true}) {
            reset(state); if (drawn) stance(state, AttackStance.Phase.HOLD, 20);
            strike(state, shot, shot.hitTick() - .01F, false, false);
            model.setupAnim(state);
            double e = point(root, FIST, scale, KNUCKLES).distanceTo(shotSpec.motion().sample(shot.hitTick() - .01F).muzzle());
            worstMuzzle = Math.max(worstMuzzle, e);
            check(e < .02, "the shot leaves from the drawn fist" + (drawn ? " with the sword out" : "") + ": " + e);
        }
        // (reported: on the move the legs run under the shot and carry the fist with them)
        reset(state); state.groundAnimationAmount = 1; state.groundRunAmount = 1; state.groundRunShare = 1; state.groundAnimationPhase = 5;
        strike(state, shot, shot.hitTick() - .01F, false, false); state.attackUpperBody = true;
        model.setupAnim(state);
        double moving = point(root, FIST, scale, KNUCKLES).distanceTo(shotSpec.motion().sample(shot.hitTick() - .01F).muzzle());
        // The aura and the sword's speed lines draw full-bright in the glow's pass, the rest of the body at its light.
        glows(model, state, () -> { reset(state); strike(state, punch, 4, false, false); }, "the punch's aura");
        var thrust = stab;
        glows(model, state, () -> { reset(state); stance(state, AttackStance.Phase.HOLD, 20); strike(state, thrust, 5, false, false); }, "the stab's speed lines");
        report(String.format(Locale.ROOT, "stab blade within %.4f of the server's line (ground, run and air), the aimed slashes' within %.4f; "
                + "shot from the fist within %.4f (%.3f on the run, reported)", worstBlade, worstSlash, worstMuzzle, moving));

        // ---------------------------------------------------------------------- every pose: no flicker, nothing sunk
        var poses = poses(state, species, slashes, stab, punch, shot, roll);
        var flicker = new TreeSet<String>();
        double sunkHair = 0;
        String sunkWhere = "";
        for (var pose : poses) {
            pose.set().run();
            model.setupAnim(state);
            var faces = faces(root, mesh);
            coplanar(faces, pose.label(), flicker);
            if (!pose.ground()) continue;
            for (var face : faces) {
                if (!(face.part().startsWith("tail_") || face.part().contains("mane") || face.part().contains("rear_lock"))) continue;
                for (double[] v : face.v()) {
                    double h = -height(v, scale);
                    if (h > sunkHair) { sunkHair = h; sunkWhere = face.part() + " in " + pose.label(); }
                }
            }
        }
        var pairs = new java.util.TreeMap<String, Integer>();
        for (String f : flicker) pairs.merge(f.substring(0, f.indexOf(" (")), 1, Integer::sum);
        report(String.format(Locale.ROOT, "%d poses, %d with faces sharing a plane %s; tail and locks at most %.3f under the ground (%s)", poses.size(),
                flicker.size(), pairs, sunkHair, sunkWhere));
        check(flicker.isEmpty(), "faces sharing a plane where they overlap (they flicker): " + flicker.size() + " " + flicker.stream().limit(12).toList());
        check(sunkHair < .03, "the tail and the mane's locks stay out of the ground: " + sunkHair + " under it (" + sunkWhere + ")");

        System.out.println(String.format(Locale.ROOT, "Leomon native checks passed: %d checks; feet planted within %.4f blocks a quarter tick upright, %.4f crouched, "
                        + "%.4f part way into the crouch, %.4f running, %.4f turning on the spot; on the floor within %.4f part way crouched and %.4f "
                        + "turning as it walks, the authored gaits raised at most %.4f",
                checks, worstUpright, worstCrouched, worstPartial, worstRun, worstPivot, worstFloor, worstTurnFloor, mostRaised));

    }

    /** A pose of the model to sweep: how to set the state, its name, and whether the body stands on the ground in it. */
    private record Pose(String label, Runnable set, boolean ground) {}

    /**
     * Every clip's pose as the game draws it, every half tick: the idle and every gait's columns upright and crouched (and
     * with the sword out), the run's columns, the leap and its tuck, the roll, the draw and the sheathe over the idle and
     * the run, every strike of both moves from the ground, a run and the air (the sword out; the fist's with it away too).
     */
    private static List<Pose> poses(DigimonRenderState state, com.digicube.digimon.DigimonSpecies species, List<DigimonAttack> slashes,
                                    DigimonAttack stab, DigimonAttack punch, DigimonAttack shot, com.digicube.digimon.DigimonBody.Roll roll) {
        var out = new ArrayList<Pose>();
        float cycle = species.locomotion().groundGait().cycleTicks();
        for (boolean drawn : new boolean[]{false, true}) {
            AttackStance.Phase phase = drawn ? AttackStance.Phase.HOLD : null;
            String out2 = drawn ? ", the sword out" : "";
            for (float c : new float[]{0, 1}) {
                String bent = c > 0 ? " crouched" : "";
                for (int t = 0; t < 144; t += 3) {
                    int at = t;
                    out.add(new Pose("idle at " + t + bent + out2, () -> { reset(state); state.crouchWeight = c; state.ageInTicks = at; stance(state, phase, at); }, true));
                }
                Object[][] lattices = {{"walk", new float[]{1, 0, 0, 0}, 0F, new float[]{.25F, .5F, .75F, 1}}, {"back", new float[]{0, 1, 0, 0}, 0F, new float[]{.5F, .75F, 1}},
                        {"left", new float[]{0, 0, 1, 0}, 0F, new float[]{.5F, .75F, 1}}, {"right", new float[]{0, 0, 0, 1}, 0F, new float[]{.5F, .75F, 1}},
                        {"turning right", new float[]{1, 0, 0, 0}, 1F, new float[]{.25F, .5F, .75F, 1}}, {"turning left", new float[]{1, 0, 0, 0}, -1F, new float[]{.25F, .5F, .75F, 1}}};
                for (Object[] lattice : lattices) for (float amount : (float[]) lattice[3]) for (float t = 0; t < cycle; t += .5F) {
                    float at = t;
                    out.add(new Pose(lattice[0] + " " + amount + " at " + t + bent + out2, () -> {
                        reset(state); state.crouchWeight = c; state.groundAnimationAmount = amount; state.gaitShares = (float[]) lattice[1];
                        state.pivotTurn = (float) lattice[2]; state.groundAnimationPhase = at; state.ageInTicks = at; stance(state, phase, at);
                    }, true));
                }
            }
            for (float share : new float[]{.55F, .75F, 1}) for (float t = 0; t < cycle; t += .5F) {
                float at = t;
                out.add(new Pose("run " + share + " at " + t + out2, () -> {
                    reset(state); state.groundAnimationAmount = 1; state.groundRunAmount = 1; state.groundRunShare = share; state.groundAnimationPhase = at;
                    state.ageInTicks = at; stance(state, phase, at);
                }, true));
            }
            for (float c : new float[]{0, 1}) for (float t = 0; t <= DigimonEntity.LEAP_END; t += .5F) {
                float at = t;
                out.add(new Pose((c > 0 ? "tuck" : "leap") + " at " + t + out2, () -> {
                    reset(state); state.leapWeight = 1; state.leapTick = at; state.crouchWeight = c; stance(state, phase, at);
                }, at < DigimonEntity.LEAP_RISE || at >= DigimonEntity.LEAP_LAND));
            }
            for (float t = 0; t <= roll.ticks(); t += .5F) {
                float at = t;
                out.add(new Pose("roll at " + t + out2, () -> { reset(state); state.rollWeight = 1; state.rollTick = at; stance(state, phase, at); }, true));
            }
        }
        var spec = sword.stance();
        for (var phase : new AttackStance.Phase[]{AttackStance.Phase.DRAW, AttackStance.Phase.SHEATHE}) for (float run : new float[]{0, 1}) {
            int length = phase == AttackStance.Phase.DRAW ? spec.draw() : spec.sheathe();
            for (float t = 0; t <= length; t += .5F) {
                float at = t;
                out.add(new Pose(phase + " at " + t + (run > 0 ? " running" : ""), () -> {
                    reset(state); state.groundAnimationAmount = run; state.groundRunAmount = run; state.groundRunShare = run > 0 ? 1 : -1;
                    state.groundAnimationPhase = at; state.ageInTicks = at; stance(state, phase, at);
                }, true));
            }
        }
        List<Object[]> strikes = new ArrayList<>();
        for (var slash : slashes) strikes.add(new Object[]{slash, false, false, true});
        for (var pounce : List.of(stab, punch)) {
            boolean blade = pounce == stab;
            for (boolean drawn : blade ? new boolean[]{true} : new boolean[]{false, true}) {
                strikes.add(new Object[]{pounce, false, false, drawn});
                strikes.add(new Object[]{pounce, true, false, drawn});
                strikes.add(new Object[]{pounce, false, true, drawn});
            }
        }
        strikes.add(new Object[]{shot, false, false, false});
        strikes.add(new Object[]{shot, false, false, true});
        for (Object[] strike : strikes) {
            DigimonAttack attack = (DigimonAttack) strike[0];
            boolean run = (boolean) strike[1], air = (boolean) strike[2], drawn = (boolean) strike[3];
            for (float t = 0; t <= attack.durationTicks(); t += .5F) {
                float at = t;
                out.add(new Pose(attack.id().getPath() + (run ? " run" : air ? " air" : "") + " at " + t + (drawn ? ", the sword out" : ""), () -> {
                    reset(state); if (drawn) stance(state, AttackStance.Phase.HOLD, 20);
                    strike(state, attack, at, run, air);
                }, !air));
            }
        }
        return out;
    }

    /** Faces closer than this (model px) to one plane share it; the overlap (px squared) a pair must have to flicker. */
    private static final double TOLERANCE = .05, PARALLEL = 1 - 1.0E-3, MIN_OVERLAP = .25;

    /** Pairs of faces facing the same way on one plane that overlap (as the asset check judges the rest pose), named. */
    private static void coplanar(List<Face> faces, String pose, TreeSet<String> into) {
        var order = new ArrayList<>(faces);
        order.sort(java.util.Comparator.comparingDouble(Face::d));
        for (int i = 0; i < order.size(); i++) {
            Face a = order.get(i);
            for (int j = i + 1; j < order.size() && order.get(j).d() - a.d() < TOLERANCE; j++) {
                Face b = order.get(j);
                if (dot(a.n(), b.n()) <= PARALLEL || offPlane(b.v(), a) >= TOLERANCE || offPlane(a.v(), b) >= TOLERANCE) continue;
                double[] u = cross(a.n(), Math.abs(a.n()[0]) < .9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0});
                double ul = Math.sqrt(dot(u, u));
                u = new double[]{u[0] / ul, u[1] / ul, u[2] / ul};
                double[] w = cross(a.n(), u);
                if (area(clip(project(a.v(), u, w), project(b.v(), u, w))) > MIN_OVERLAP)
                    into.add((a.part().compareTo(b.part()) <= 0 ? a.part() + " ~ " + b.part() : b.part() + " ~ " + a.part()) + " (" + pose + ")");
            }
        }
    }

    private static double offPlane(double[][] vertices, Face plane) {
        double worst = 0;
        for (double[] p : vertices) worst = Math.max(worst, Math.abs(dot(p, plane.n()) - plane.d()));
        return worst;
    }

    private static List<double[]> project(double[][] v, double[] u, double[] w) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : v) out.add(new double[]{dot(p, u), dot(p, w)});
        return out;
    }

    private static double signedArea(List<double[]> poly) {
        double sum = 0;
        for (int i = 0; i < poly.size(); i++) {
            double[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum / 2;
    }

    private static double area(List<double[]> poly) { return poly.isEmpty() ? 0 : Math.abs(signedArea(poly)); }

    /** Sutherland-Hodgman against a convex clipper. */
    private static List<double[]> clip(List<double[]> subject, List<double[]> clipper) {
        if (signedArea(clipper) < 0) clipper = clipper.reversed();
        List<double[]> out = subject;
        for (int i = 0; i < clipper.size() && !out.isEmpty(); i++) {
            double[] a = clipper.get(i), b = clipper.get((i + 1) % clipper.size());
            List<double[]> in = out;
            out = new ArrayList<>();
            double[] s = in.getLast();
            for (double[] e : in) {
                if (inside(e, a, b)) {
                    if (!inside(s, a, b)) out.add(intersect(s, e, a, b));
                    out.add(e);
                } else if (inside(s, a, b)) out.add(intersect(s, e, a, b));
                s = e;
            }
        }
        return out;
    }

    private static boolean inside(double[] p, double[] a, double[] b) {
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= -1e-9;
    }

    private static double[] intersect(double[] p, double[] q, double[] a, double[] b) {
        double d1x = p[0] - q[0], d1y = p[1] - q[1], d2x = a[0] - b[0], d2y = a[1] - b[1], den = d1x * d2y - d1y * d2x;
        if (Math.abs(den) < 1e-12) return p;
        double t = ((p[0] - a[0]) * d2y - (p[1] - a[1]) * d2x) / den;
        return new double[]{p[0] - t * d1x, p[1] - t * d1y};
    }

    // --- glow parts ---------------------------------------------------------------------------------------------------

    /** A dim light (block 3, sky 5): the body's own, which its other parts keep. */
    private static final int DIM = 3 << 4 | 5 << 20;

    /**
     * Posed by {@code set} (glow parts shown), the glow's pass as the renderer submits it draws them full-bright, the body's
     * pass the rest at its light, and the two draw the whole body once between them.
     */
    @SuppressWarnings("unchecked")
    private static void glows(NativeGroundModel model, DigimonRenderState state, Runnable set, String label) {
        set.run();
        var whole = draw(model, state, DIM);
        state.glowSplit = true;
        var body = draw(model, state, DIM);
        List<Object[]> submits = new ArrayList<>();
        var collector = (SubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("submitModel")) submits.add(arguments);
                    return null;
                });
        model.submitGlow(state, new PoseStack(), collector, null, OverlayTexture.NO_OVERLAY, -1, 0);
        check(submits.size() == 1 && (int) submits.getFirst()[4] == LightCoordsUtil.FULL_BRIGHT, label + ": one glow pass, full-bright");
        var glow = draw((Model<DigimonRenderState>) submits.getFirst()[0], state, LightCoordsUtil.FULL_BRIGHT);
        state.glowSplit = false;
        check(!glow.at.isEmpty() && glow.light.stream().allMatch(l -> l == LightCoordsUtil.FULL_BRIGHT), label + " draws full-bright: " + glow.at.size() + " corners");
        check(body.at.size() + glow.at.size() == whole.at.size() && body.light.stream().allMatch(l -> l == DIM), label + ": the rest of the body keeps its light");
    }

    private static Capture draw(Model<DigimonRenderState> model, DigimonRenderState state, int light) {
        model.setupAnim(state);
        var capture = new Capture();
        model.renderToBuffer(new PoseStack(), capture, light, OverlayTexture.NO_OVERLAY, -1);
        return capture;
    }

    /** Every corner drawn and the light it carries. */
    private static final class Capture implements VertexConsumer {
        final List<String> at = new ArrayList<>();
        final List<Integer> light = new ArrayList<>();

        private void vertex(float x, float y, float z, int light) {
            at.add(Math.round(x * 1000) + "," + Math.round(y * 1000) + "," + Math.round(z * 1000));
            this.light.add(light);
        }

        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) { vertex(x, y, z, light); }
        @Override public VertexConsumer addVertex(float x, float y, float z) { vertex(x, y, z, 0); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { light.set(light.size() - 1, u | v << 16); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    /** As {@link #planted} (reported, not held): the worst move a quarter tick along the ground. */
    private static double measured(NativeGroundModel model, ModelPart root, DigimonRenderState state, float scale, float cycle, Travel travel,
                                   Consumer<DigimonRenderState> reference) {
        int steps = Math.round(cycle * 4);
        var at = soles(model, root, state, scale, steps, travel);
        var stance = at;
        if (reference != null) { var plain = copy(state); reference.accept(plain); stance = soles(model, root, plain, scale, steps, travel); }
        double worst = 0;
        for (int f = 0; f < 2; f++) {
            boolean[] on = standing(stance[f]);
            for (int i = 0; i < steps; i++) {
                if (!on[i] || !on[i + 1]) continue;
                int k = lowest(at[f][i]);
                Vec3 moved = at[f][i + 1][k].subtract(at[f][i][k]);
                worst = Math.max(worst, Math.abs(moved.x) + Math.abs(moved.z));
            }
        }
        return worst;
    }

    /** The highest points (blocks): the head part's own faces, the body's without its hair and tail (and which part), and everything. */
    private record Tops(double head, double body, String bodyPart, double all) {}

    private static Tops tops(ModelPart root, NativeModelGeometry.Mesh mesh, float scale) {
        double head = -9, body = -9, all = -9;
        String top = "";
        for (var face : faces(root, mesh)) for (double[] v : face.v()) {
            double y = height(v, scale);
            all = Math.max(all, y);
            if (face.part().equals("head")) head = Math.max(head, y);
            if (HAIR.stream().noneMatch(face.part()::startsWith) && y > body) { body = y; top = face.part(); }
        }
        return new Tops(head, body, top, all);
    }

    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
