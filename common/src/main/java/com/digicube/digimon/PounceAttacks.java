package com.digicube.digimon;

import com.digicube.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pounce: a dash that ends in a bite ({@code data/digicube/pounce_attacks.json}, {@link DigimonAttack.Kind#POUNCE};
 * Garurumon's Freeze Fang). A short gather, then a burst along its line that eases from its opening speed to its closing
 * one and hands the rest of its momentum back to the body; the jaws are open through the burst and strike the first body
 * they meet, where the dash stops. The AI pounces at where its prey will be; a rider pounces along the crosshair (bent a
 * little toward an enemy near it), on the ground or in the air, where the gather is skipped and the fall is held while
 * the burst lasts. Uses stack ({@code charges}), each back after the attack's cooldown.
 *
 * <p>What the move does to its victim is data too: {@code freeze} is what a bite pays into the victim's Freeze gauge
 * ({@link FreezeMark}), and a Frozen victim takes {@code shatter} times the damage and is broken out of the ice.
 */
public final class PounceAttacks {
    /**
     * @param attack       the move: power, cooldown (per use), duration (the clip), hit tick (the first tick the jaws can
     *                     strike) and range (how far the AI pounces from)
     * @param charges      uses held at once
     * @param gather       ticks of the crouch before the burst on the ground (the clip's burst starts here)
     * @param burst        ticks the burst drives the body
     * @param speed        blocks a tick at the burst's first tick and at its last, eased between
     * @param exit         blocks a tick of momentum the body keeps after the burst
     * @param home         degrees a tick the burst turns after its prey (the AI's target or a rider's soft target)
     * @param cone         degrees around the crosshair a rider's pounce finds an enemy to bend toward
     * @param groundPitch  lowest and highest pitch (degrees, up positive) of a pounce from the ground
     * @param airPitch     the same in the air: a pounce from a leap may dive onto its prey or rise to one above
     * @param contactUntil last tick the jaws can strike (from the attack's hit tick)
     * @param snap         the clip's tick where the jaws shut: a bite that lands earlier jumps the clip there
     * @param freeze       share of a Freeze gauge a bite pays in (0 to 100)
     * @param shatter      damage factor against a Frozen victim, which the bite breaks out of the ice
     * @param knockback    push on a bitten victim, blocks a tick
     * @param impact       the effect model the client bursts at the jaws where a bite lands (its {@code effect} clip)
     */
    public record Spec(DigimonAttack attack, int charges, int gather, int burst, float[] speed, float exit, float home, float cone,
                       float[] groundPitch, float[] airPitch, int contactUntil, int snap, float freeze, float shatter, float knockback,
                       String impact) {
        /** Blocks a tick at tick {@code i} of the burst (0 = its first). */
        public float speedAt(int i) {
            float u = burst <= 1 ? 1 : Mth.clamp(i / (float) (burst - 1), 0, 1);
            // eased out: the leap off the hind legs is the fastest moment
            float e = 1 - (1 - u) * (1 - u);
            return speed[0] + (speed[1] - speed[0]) * e;
        }

        /** Blocks the burst covers before its momentum is handed back. */
        public float distance() {
            float d = 0;
            for (int i = 0; i < burst; i++) d += speedAt(i);
            return d;
        }

        /** Clamps a pitch (degrees, up positive) to what a pounce from the ground or the air may take. */
        public float pitch(float degrees, boolean air) {
            float[] range = air ? airPitch : groundPitch;
            return Mth.clamp(degrees, range[0], range[1]);
        }

        /** The clip tick a pounce starts at: the air skips the gather. */
        public int startTick(boolean air) { return air ? gather : 0; }
    }

    private static final Map<Identifier, Spec> SPECS = load();
    private PounceAttacks() {}

    public static Spec get(DigimonAttack attack) { return attack == null ? null : SPECS.get(attack.id()); }
    public static boolean handles(DigimonAttack attack) { return attack != null && attack.kind() == DigimonAttack.Kind.POUNCE && get(attack) != null; }
    /** Every pounce, for the species sheets to name. */
    public static List<DigimonAttack> attacks() { return SPECS.values().stream().map(Spec::attack).toList(); }

    private static float[] floats(JsonObject o, String key, int size) {
        JsonArray a = GsonHelper.getAsJsonArray(o, key);
        if (a.size() != size) throw new IllegalArgumentException("Pounce " + key + " needs " + size + " numbers");
        float[] out = new float[size];
        for (int i = 0; i < size; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static Map<Identifier, Spec> load() {
        try (var input = PounceAttacks.class.getResourceAsStream("/data/digicube/pounce_attacks.json")) {
            if (input == null) return Map.of();
            var data = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            var result = new LinkedHashMap<Identifier, Spec>();
            for (var entry : data.entrySet()) {
                var c = entry.getValue().getAsJsonObject();
                var id = Constants.id(entry.getKey());
                var motion = AttackMotion.load(id);
                var attack = new DigimonAttack(id, DigimonAttack.Kind.POUNCE, GsonHelper.getAsFloat(c, "power"), GsonHelper.getAsInt(c, "cooldown"),
                        GsonHelper.getAsInt(c, "duration"), GsonHelper.getAsInt(c, "hit_tick"), GsonHelper.getAsDouble(c, "range"), false, motion, null, 0);
                var spec = new Spec(attack, GsonHelper.getAsInt(c, "charges", 1), GsonHelper.getAsInt(c, "gather"), GsonHelper.getAsInt(c, "burst"),
                        floats(c, "speed", 2), GsonHelper.getAsFloat(c, "exit"), GsonHelper.getAsFloat(c, "home"), GsonHelper.getAsFloat(c, "cone"),
                        floats(c, "ground_pitch", 2), floats(c, "air_pitch", 2), GsonHelper.getAsInt(c, "contact_until"), GsonHelper.getAsInt(c, "snap"),
                        GsonHelper.getAsFloat(c, "freeze"), GsonHelper.getAsFloat(c, "shatter"), GsonHelper.getAsFloat(c, "knockback"),
                        GsonHelper.getAsString(c, "impact", ""));
                if (spec.charges() < 1 || spec.gather() < 0 || spec.burst() < 1 || spec.gather() + spec.burst() > attack.durationTicks()
                        || !(spec.speed()[0] > 0 && spec.speed()[1] > 0 && spec.speed()[0] < 3 && spec.speed()[1] < 3) || !(spec.exit() >= 0)
                        || !(spec.home() >= 0) || !(spec.cone() >= 0 && spec.cone() <= 60)
                        || spec.groundPitch()[0] > spec.groundPitch()[1] || spec.airPitch()[0] > spec.airPitch()[1]
                        || spec.contactUntil() < attack.hitTick() || spec.contactUntil() >= attack.durationTicks()
                        || spec.snap() < attack.hitTick() || spec.snap() >= attack.durationTicks()
                        || !(spec.freeze() >= 0 && spec.freeze() <= FreezeMark.FULL) || !(spec.shatter() >= 1) || !(spec.knockback() >= 0))
                    throw new IllegalArgumentException("Invalid pounce " + id);
                result.put(id, spec);
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read pounce attacks", e);
        }
    }
}
