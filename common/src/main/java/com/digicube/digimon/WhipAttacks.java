package com.digicube.digimon;

import com.digicube.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A long arm used as a whip ({@code data/digicube/whip_attacks.json}, {@link DigimonAttack.Kind#WHIP}; Gesomon's Devil
 * Bashing), by a rider ({@code aim: "whip"} in the rider data) and by the AI alike: held, the arm swings back behind its
 * own side and coils there, tighter the longer it is held, gathering momentum; let go, it lashes out at the aim (the
 * crosshair, or the AI's prey), and the aim steers it for as long as the lash lasts, so a flick of the mouse sweeps it
 * across. What it hits and how hard is {@code com.digicube.entity.WhipArm}; the arm geometry is measured from the mesh,
 * so the server hits with the arm the client draws.
 */
public final class WhipAttacks {
    /**
     * One long arm: the path to the part it hangs from, the model parts the client poses (sections root first, then
     * the pad, each a child of the one before), each section's length
     * (blocks; the last is the offset to the pad), the pad's length and half width, and where the arm's root sits from the
     * rider's seat (blocks: x left, y up, z forward), on land and afloat.
     */
    public record Arm(List<String> parentPath, List<String> parts, float[] sections, float pad, float padRadius, Vec3 base, Vec3 waterBase) {
        public float reach() { float r = pad; for (float s : sections) r += s; return r; }
    }

    /**
     * How the AI whips.
     * @param reach    share of the arm's reach it strikes from (the pad's tip has to meet the body, not graze it)
     * @param winds    ticks it holds the wind-up: a quick snap (the enemy is about to strike), an ordinary lash, a full
     *                 one (the enemy is hampered or committed to a move of its own)
     * @param lashLead ticks from the release to the pad meeting what it was aimed at, for leading a moving enemy
     * @param sweep    degrees the aim runs through the enemy over the lash, from the arm's own side past it, so the pad
     *                 sweeps across the body at speed instead of stopping at its edge
     * @param patience ticks a wound arm is held beyond its plan for an enemy out of reach before it is let fall
     */
    public record Ai(float reach, int[] winds, int lashLead, float sweep, int patience) {}

    /**
     * @param attack       the move: its power, cooldown (from the wind-up's start) and range (how far the AI reaches)
     * @param wind         where the root turns while held: degrees behind its own side (yaw) and up (pitch, negative);
     *                     {@code windCharged} where it has gone by a full charge
     * @param curl         degrees the arm coils behind (yaw) and down (pitch) toward the pad while held at a full charge
     * @param rest         where it goes back to afterwards (degrees out to its side, and down)
     * @param windSpring   stiffness and damping of the root's turn while held; {@code lashSpring}, {@code recoverSpring} likewise
     * @param maxTurn      the most the root turns in a tick, degrees
     * @param lag          ticks each section trails the one before it
     * @param power        share of the attack's power dealt by a tap and by a full wind-up
     * @param referenceSpeed the pad's speed (blocks a tick) at which that share is dealt; {@code speedScale} bounds how
     *                     much a slower or faster pad takes off or adds
     * @param waterPitch   the body's dive pitch limit afloat (degrees), which turns the root about the seat
     */
    public record Spec(DigimonAttack attack, Arm left, Arm right, float[] wind, float[] windCharged, float[] curl, float[] rest,
                       int minWindTicks, int chargeTicks, int lashTicks, int recoverTicks, float[] windSpring, float[] lashSpring,
                       float[] recoverSpring, float maxTurn, float lag, float[] power, float referenceSpeed, float[] speedScale,
                       float knockback, float radius, float waterPitch, Ai ai) {
        /** +1 is the left arm, -1 the right. */
        public Arm arm(int side) { return side > 0 ? left : right; }
        public float power(float charge) { return power[0] + (power[1] - power[0]) * Math.clamp(charge, 0, 1); }
        public float speedScale(float padSpeed) { return Math.clamp(padSpeed / referenceSpeed, speedScale[0], speedScale[1]); }
    }

    private static final Map<Identifier, Spec> SPECS = load();
    private WhipAttacks() {}

    public static Spec get(DigimonAttack attack) { return attack == null ? null : SPECS.get(attack.id()); }
    public static Spec get(Identifier attack) { return SPECS.get(attack); }
    public static boolean handles(DigimonAttack attack) { return attack != null && attack.kind() == DigimonAttack.Kind.WHIP && get(attack) != null; }
    /** Every whip's move, for the species sheets to name. */
    public static List<DigimonAttack> attacks() { return SPECS.values().stream().map(Spec::attack).toList(); }

    private static float[] floats(JsonObject o, String key, int size) {
        JsonArray a = GsonHelper.getAsJsonArray(o, key);
        if (a.size() != size) throw new IllegalArgumentException("Whip " + key + " needs " + size + " numbers");
        float[] out = new float[size];
        for (int i = 0; i < size; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static Vec3 vector(JsonObject o, String key) {
        float[] v = floats(o, key, 3);
        return new Vec3(v[0], v[1], v[2]);
    }

    private static Arm arm(JsonObject o) {
        var parts = new ArrayList<String>();
        GsonHelper.getAsJsonArray(o, "parts").forEach(p -> parts.add(p.getAsString()));
        var parent = new ArrayList<String>();
        GsonHelper.getAsJsonArray(o, "parent_path").forEach(p -> parent.add(p.getAsString()));
        JsonArray s = GsonHelper.getAsJsonArray(o, "sections");
        float[] sections = new float[s.size()];
        for (int i = 0; i < sections.length; i++) sections[i] = s.get(i).getAsFloat();
        var arm = new Arm(List.copyOf(parent), List.copyOf(parts), sections, GsonHelper.getAsFloat(o, "pad"), GsonHelper.getAsFloat(o, "pad_radius"),
                vector(o, "base"), vector(o, "water_base"));
        if (parts.size() != sections.length + 1 || sections.length < 2) throw new IllegalArgumentException("A whip arm needs its sections and the pad");
        for (float x : sections) if (!(x > 0 && x < 4)) throw new IllegalArgumentException("Invalid whip section");
        return arm;
    }

    private static Map<Identifier, Spec> load() {
        try (var input = WhipAttacks.class.getResourceAsStream("/data/digicube/whip_attacks.json")) {
            if (input == null) return Map.of();
            var data = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            var result = new LinkedHashMap<Identifier, Spec>();
            for (var entry : data.entrySet()) {
                var c = entry.getValue().getAsJsonObject();
                var arms = GsonHelper.getAsJsonObject(c, "arms");
                var id = Constants.id(entry.getKey());
                var move = GsonHelper.getAsJsonObject(c, "attack");
                var attack = new DigimonAttack(id, DigimonAttack.Kind.WHIP, GsonHelper.getAsFloat(move, "power"), GsonHelper.getAsInt(move, "cooldown"),
                        GsonHelper.getAsInt(move, "duration"), GsonHelper.getAsInt(move, "hit_tick"), GsonHelper.getAsDouble(move, "range"), false);
                var ai = GsonHelper.getAsJsonObject(c, "ai");
                float[] winds = floats(ai, "winds", 3);
                var brain = new Ai(GsonHelper.getAsFloat(ai, "reach"), new int[]{(int) winds[0], (int) winds[1], (int) winds[2]},
                        GsonHelper.getAsInt(ai, "lash_lead"), GsonHelper.getAsFloat(ai, "sweep"), GsonHelper.getAsInt(ai, "patience"));
                var spec = new Spec(attack, arm(arms.getAsJsonObject("L")), arm(arms.getAsJsonObject("R")), floats(c, "wind", 2),
                        floats(c, "wind_charged", 2), floats(c, "curl", 2), floats(c, "rest", 2),
                        GsonHelper.getAsInt(c, "min_wind_ticks"), GsonHelper.getAsInt(c, "charge_ticks"), GsonHelper.getAsInt(c, "lash_ticks"),
                        GsonHelper.getAsInt(c, "recover_ticks"), floats(c, "wind_spring", 2), floats(c, "lash_spring", 2), floats(c, "recover_spring", 2),
                        GsonHelper.getAsFloat(c, "max_turn"), GsonHelper.getAsFloat(c, "lag"), floats(c, "power", 2),
                        GsonHelper.getAsFloat(c, "reference_speed"), floats(c, "speed_scale", 2), GsonHelper.getAsFloat(c, "knockback"),
                        GsonHelper.getAsFloat(c, "radius"), GsonHelper.getAsFloat(c, "water_pitch", 0), brain);
                if (spec.minWindTicks() < 1 || spec.chargeTicks() < 1 || spec.lashTicks() < 2 || spec.recoverTicks() < 1 || !(spec.lag() >= 0)
                        || !(spec.maxTurn() > 0) || !(spec.referenceSpeed() > 0) || !(spec.radius() > 0)
                        || !(brain.reach() > 0 && brain.reach() <= 1) || brain.winds()[0] < spec.minWindTicks()
                        || brain.winds()[0] > brain.winds()[1] || brain.winds()[1] > brain.winds()[2] || brain.lashLead() < 0 || brain.patience() < 0)
                    throw new IllegalArgumentException("Invalid whip " + id);
                result.put(id, spec);
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read whip attacks", e);
        }
    }
}
