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
     * @param air          the move as a flyer casts it on the wing ({@code air} in the data: its own clip, motion, gather,
     *                     burst, speeds and contact window, a gather of its own played in the air), or null: then a pounce
     *                     from the air is this one with its gather skipped
     * @param airborne     this is such a wing form
     * @param shake        the body shakes itself into the pounce (a wolf's shake, heard when its species has no battle cry);
     *                     {@code "shake": false} for a body with nothing to shake (a beetle), whose start is the lunge's
     *                     whoosh alone
     * @param tip          share of the line's pitch the whole body tips by (1 by default); the aim part (the neck) turns the
     *                     rest of the way about the motion's {@code head}, as far as the clip's aim weight lets it: a biped
     *                     keeps its feet under it and aims its horns up or down instead ({@link com.digicube.entity.PounceLines#posed})
     * @param runStart     blocks a tick from which a pounce on the ground skips its gather and keeps its pace into the burst (a
     *                     charge from a run needs no crouch); 0 never
     * @param launch       the blow throws its victim along the dash ({@code launch}, {@link Launch}) in place of {@code knockback};
     *                     null for a plain push. A wing form without one of its own takes the move's
     * @param run          the move as a charge from a run strikes ({@code run_motion} in the data: the same move with the
     *                     contact points of its run clip), or null: then a charge from a run strikes with the move's own
     */
    public record Spec(DigimonAttack attack, int charges, int gather, int burst, float[] speed, float exit, float home, float cone,
                       float[] groundPitch, float[] airPitch, int contactUntil, int snap, float freeze, float shatter, float knockback,
                       String impact, Spec air, boolean airborne, boolean shake, float tip, float runStart, Launch launch, Spec run) {
        public Spec(DigimonAttack attack, int charges, int gather, int burst, float[] speed, float exit, float home, float cone,
                    float[] groundPitch, float[] airPitch, int contactUntil, int snap, float freeze, float shatter, float knockback,
                    String impact, Spec air, boolean airborne, boolean shake, float tip, float runStart, Launch launch) {
            this(attack, charges, gather, burst, speed, exit, home, cone, groundPitch, airPitch, contactUntil, snap, freeze, shatter, knockback, impact,
                    air, airborne, shake, tip, runStart, launch, null);
        }
        public Spec(DigimonAttack attack, int charges, int gather, int burst, float[] speed, float exit, float home, float cone,
                    float[] groundPitch, float[] airPitch, int contactUntil, int snap, float freeze, float shatter, float knockback,
                    String impact, Spec air, boolean airborne, boolean shake, float tip, float runStart) {
            this(attack, charges, gather, burst, speed, exit, home, cone, groundPitch, airPitch, contactUntil, snap, freeze, shatter, knockback, impact,
                    air, airborne, shake, tip, runStart, null);
        }
        public Spec(DigimonAttack attack, int charges, int gather, int burst, float[] speed, float exit, float home, float cone,
                    float[] groundPitch, float[] airPitch, int contactUntil, int snap, float freeze, float shatter, float knockback,
                    String impact, Spec air, boolean airborne, boolean shake) {
            this(attack, charges, gather, burst, speed, exit, home, cone, groundPitch, airPitch, contactUntil, snap, freeze, shatter, knockback, impact,
                    air, airborne, shake, 1, 0);
        }
        public Spec(DigimonAttack attack, int charges, int gather, int burst, float[] speed, float exit, float home, float cone,
                    float[] groundPitch, float[] airPitch, int contactUntil, int snap, float freeze, float shatter, float knockback, String impact) {
            this(attack, charges, gather, burst, speed, exit, home, cone, groundPitch, airPitch, contactUntil, snap, freeze, shatter, knockback, impact, null, false, true);
        }

        /** The form a pounce takes: its wing form when it has one and is cast on the wing, else itself. */
        public Spec forAir(boolean wing) { return wing && air != null ? air : this; }
        /** The form a charge from a run strikes with: its run form ({@code run_motion}) when it has one, else itself. */
        public Spec forRun(boolean running) { return running && run != null ? run : this; }
        /** This form with {@code run} as its run form. */
        Spec withRun(Spec run) {
            return new Spec(attack, charges, gather, burst, speed, exit, home, cone, groundPitch, airPitch, contactUntil, snap, freeze, shatter,
                    knockback, impact, air, airborne, shake, tip, runStart, launch, run);
        }
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

        /** The clip tick a pounce starts at: the air skips the gather, unless this is a wing form (which plays its own). */
        public int startTick(boolean air) { return air && !airborne ? gather : 0; }

        /** Whether a pounce on the ground at {@code pace} (blocks a tick) is a charge from a run, which skips its gather. */
        public boolean runs(double pace) { return runStart > 0 && !airborne && pace >= runStart; }

        /** Start event forms: 0 a pounce from the ground, 1 from the air (its wing form if any), 2 a charge from a run. */
        public static final int GROUND = 0, AIR = 1, RUNNING = 2;

        /** The clip tick a pounce cast as {@code form} starts at (a run's charge skips the gather, as the air does). */
        public int startTick(int form) { return form == RUNNING ? gather : startTick(form == AIR); }
    }

    private static final Map<Identifier, Spec> SPECS = load();
    private PounceAttacks() {}

    public static Spec get(DigimonAttack attack) { return attack == null ? null : SPECS.get(attack.id()); }
    public static boolean handles(DigimonAttack attack) { return attack != null && attack.kind() == DigimonAttack.Kind.POUNCE && get(attack) != null; }
    /** Every pounce, for the species sheets to name. */
    public static List<DigimonAttack> attacks() { return SPECS.values().stream().map(Spec::attack).toList(); }

    private static Spec check(Spec spec, Identifier id) {
        var attack = spec.attack();
        if (spec.charges() < 1 || spec.gather() < 0 || spec.burst() < 1 || spec.gather() + spec.burst() > attack.durationTicks()
                || !(spec.speed()[0] > 0 && spec.speed()[1] > 0 && spec.speed()[0] < 3 && spec.speed()[1] < 3) || !(spec.exit() >= 0)
                || !(spec.home() >= 0) || !(spec.cone() >= 0 && spec.cone() <= 60)
                || spec.groundPitch()[0] > spec.groundPitch()[1] || spec.airPitch()[0] > spec.airPitch()[1]
                || spec.contactUntil() < attack.hitTick() || spec.contactUntil() >= attack.durationTicks()
                || spec.snap() < attack.hitTick() || spec.snap() >= attack.durationTicks()
                || !(spec.freeze() >= 0 && spec.freeze() <= FreezeMark.FULL) || !(spec.shatter() >= 1) || !(spec.knockback() >= 0)
                || !(spec.tip() >= 0 && spec.tip() <= 1) || !(spec.runStart() >= 0))
            throw new IllegalArgumentException("Invalid pounce " + id + (spec.airborne() ? " (wing form)" : ""));
        return spec;
    }

    /** A pounce entry's ground form, striking as {@code attack}'s motion has it, with its wing form {@code air}. */
    private static Spec ground(JsonObject c, DigimonAttack attack, Spec air) {
        return new Spec(attack, GsonHelper.getAsInt(c, "charges", 1), GsonHelper.getAsInt(c, "gather"), GsonHelper.getAsInt(c, "burst"),
                floats(c, "speed", 2), GsonHelper.getAsFloat(c, "exit"), GsonHelper.getAsFloat(c, "home"), GsonHelper.getAsFloat(c, "cone"),
                floats(c, "ground_pitch", 2), floats(c, "air_pitch", 2), GsonHelper.getAsInt(c, "contact_until"), GsonHelper.getAsInt(c, "snap"),
                GsonHelper.getAsFloat(c, "freeze"), GsonHelper.getAsFloat(c, "shatter"), GsonHelper.getAsFloat(c, "knockback"),
                GsonHelper.getAsString(c, "impact", ""), air, false, GsonHelper.getAsBoolean(c, "shake", true),
                GsonHelper.getAsFloat(c, "tip", 1), GsonHelper.getAsFloat(c, "run_start", 0), Launch.parse(c));
    }

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
                Spec air = null;
                if (c.has("air")) {
                    // The wing form: the same move (its id, power, cooldown and uses), with its own clip timing, motion and burst.
                    var a = GsonHelper.getAsJsonObject(c, "air");
                    var wingMotion = AttackMotion.load(Constants.id(GsonHelper.getAsString(a, "motion")));
                    var wingAttack = new DigimonAttack(id, DigimonAttack.Kind.POUNCE, attack.power(), attack.cooldownTicks(),
                            attack.durationTicks(), GsonHelper.getAsInt(a, "hit_tick"), attack.range(), false, wingMotion, null, 0);
                    air = check(new Spec(wingAttack, GsonHelper.getAsInt(c, "charges", 1), GsonHelper.getAsInt(a, "gather"), GsonHelper.getAsInt(a, "burst"),
                            floats(a, "speed", 2), GsonHelper.getAsFloat(a, "exit"), GsonHelper.getAsFloat(a, "home"), GsonHelper.getAsFloat(c, "cone"),
                            floats(c, "ground_pitch", 2), floats(c, "air_pitch", 2), GsonHelper.getAsInt(a, "contact_until"), GsonHelper.getAsInt(a, "snap"),
                            GsonHelper.getAsFloat(c, "freeze"), GsonHelper.getAsFloat(c, "shatter"), GsonHelper.getAsFloat(a, "knockback", GsonHelper.getAsFloat(c, "knockback")),
                            GsonHelper.getAsString(c, "impact", ""), null, true, GsonHelper.getAsBoolean(c, "shake", true),
                            GsonHelper.getAsFloat(a, "tip", 1), 0, a.has("launch") ? Launch.parse(a) : Launch.parse(c)), id);
                }
                Spec run = null;
                if (c.has("run_motion")) {
                    // The run form: the move itself (its timing, burst and blow) striking with its run clip's contact points.
                    var runAttack = new DigimonAttack(id, DigimonAttack.Kind.POUNCE, attack.power(), attack.cooldownTicks(), attack.durationTicks(),
                            attack.hitTick(), attack.range(), false, AttackMotion.load(Constants.id(GsonHelper.getAsString(c, "run_motion"))), null, 0);
                    run = check(ground(c, runAttack, null), id);
                    if (!(run.runStart() > 0)) throw new IllegalArgumentException("A pounce's run_motion needs its run_start " + id);
                }
                var spec = check(ground(c, attack, air).withRun(run), id);
                result.put(id, spec);
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read pounce attacks", e);
        }
    }
}
