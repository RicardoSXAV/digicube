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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Weapons a Digimon throws and gets back: a returning throw (a boomerang that flies out along a curve, turns at its
 * range and comes back to a fixed point beside where it left, to be caught there or lost) and a charged throw (a
 * projectile formed in the hands and held to grow bigger and heavier before it is thrown on a ballistic arc).
 *
 * <p>Read from {@code data/digicube/thrown_attacks.json}; the hand positions the clips release, catch and pick up at
 * come from {@code data/digicube/thrown_motion/<species>.json}, in the entity's frame (blocks,
 * x = its left, y = up, z = ahead).
 */
public final class ThrownAttacks {
    /** How far along a clip the fist does its work, and how long the clip lasts, in ticks. */
    public record Clip(String name, int length, int event) {}

    /**
     * A returning throw. Tapped, it goes at once ({@code throwClip}); held, the wind-up stops cocked at {@code holdAt}
     * and plays {@code holdClip} (a blend by the charge) until it is let go, then {@code releaseClip} (a blend too).
     * @param maxRange farthest turn of a tapped throw; {@code farRange} is a full charge's
     * @param chargeSpeed {@code [tap, full charge]} multiplier of the flight's pace; {@code chargePower} of its blows
     * @param airBoost share a throw from the air gains in pace, reach and power ({@link #impulse})
     * @param lift how far the far turn may sink below and climb above the cruise height toward the aim, in blocks
     * @param catchSide lateral offset of the catch point, {@code [a, b]}: a + b x range blocks to the curve's side
     * @param bulge how far the return leg swings out beyond the catch line, in blocks
     * @param speed blocks a tick at release, at the far turn, and on the way back
     * @param returnPower share of the attack's damage a hit on the way back deals
     * @param catchWindow share of the return leg, counted back from its end, where the thrower can catch it
     * @param dropTicks how long a lost weapon lies before the thrower's grows back
     * @param recovery ticks after the catch (or the pickup) clip before the next throw may start
     * @param cruiseHeight blocks above the thrower's feet the weapon flies at: it drops to it from the hand, and comes
     *                     home to the catching fist's height
     */
    public record Returning(DigimonAttack attack, String projectile, float modelScale, double minRange, double maxRange,
                            double[] catchSide, double bulge, double[] speed, float spin, float returnPower,
                            double hitRadius, double hitHeight, double catchRadius, double catchWindow, int dropTicks,
                            double pickupRadius, Clip throwClip, Clip catchClip, Clip pickupClip,
                            Vec3 releasePoint, Vec3 catchPoint, Vec3 pickupPoint, int recovery, double cruiseHeight,
                            double farRange, int holdAt, int chargeTicks, int maxHold, float[] chargeSpeed, float[] chargePower,
                            float airBoost, double[] lift, String holdClip, Clip releaseClip, Vec3 releasePointHeavy) {
        public double catchOffset(double range) { return Math.min(catchSide[0] + catchSide[1] * range, CATCH_SIDE_CAP); }
        public float mix(float[] pair, float charge) { return pair[0] + (pair[1] - pair[0]) * Math.clamp(charge, 0, 1); }
        /** How far out the turn can be at this charge, thrown standing on the ground. */
        public double reach(float charge) { return maxRange + (farRange - maxRange) * Math.clamp(charge, 0, 1); }
        /** The fist the bone leaves: the tap throw's, a little further forward the harder the wind-up. */
        public Vec3 releasePoint(float charge) { return releasePoint.lerp(releasePointHeavy, Math.clamp(charge, 0, 1)); }
        /** Ticks from the start to the bone leaving the fist: a tap ({@code hold} below zero), or a hold of so many ticks. */
        public int releaseAfter(int hold) { return hold < 0 ? throwClip.event() : holdAt + hold + releaseClip.event(); }
        /** Ticks of holding that reach {@code charge}. */
        public int holdFor(float charge) { return Math.round(Math.clamp(charge, 0, 1) * chargeTicks); }
    }
    /** Farthest a catch point lies to the side of the throw, in blocks: past it a long throw's loop is no wider than a short one's. */
    public static final double CATCH_SIDE_CAP = 4.5;

    /**
     * A charged throw. Every pair is {@code [tap, full charge]}; a charge between mixes them linearly.
     * @param chargeTicks ticks of holding that grow the projectile to its full size
     * @param maxHold longest hold before the throw goes by itself
     */
    public record Charged(DigimonAttack attack, String projectile, float modelScale, Clip form, String hold, Clip release,
                          int chargeTicks, int maxHold, float[] power, float[] speed, float[] gravity, float[] size,
                          float[] halfWidth, float[] halfLength, float[] knockback, int embedTicks, float walkPace,
                          Vec3[] releasePoint, float airBoost) {
        public float mix(float[] pair, float charge) { return pair[0] + (pair[1] - pair[0]) * Math.clamp(charge, 0, 1); }
        public Vec3 releasePoint(float charge) { return releasePoint[0].lerp(releasePoint[1], Math.clamp(charge, 0, 1)); }
        /** Ticks from the start to the release for a hold of {@code hold} ticks. */
        public int releaseAfter(int hold) { return form.length() + hold + release.event(); }
        /** Ticks of holding that reach {@code charge}. */
        public int holdFor(float charge) { return Math.round(Math.clamp(charge, 0, 1) * chargeTicks); }
    }

    /** Most of a throw's own speed the body's pace along it adds. */
    public static final double MOMENTUM_CAP = .4;

    /**
     * How much harder a throw leaves the hand for the body that throws it: {@code airBoost} more thrown from a leap (the
     * whole body whips into it, nothing braced on the ground to lose it to), and the body's own pace along the throw as
     * a share of the throw's speed ({@link #MOMENTUM_CAP} at most; walking away from it takes nothing off). Pace,
     * reach and power of the throw go with it.
     * @param motion the body's travel over the last tick, blocks
     */
    public static float impulse(boolean airborne, Vec3 motion, Vec3 direction, double speed, float airBoost) {
        Vec3 flat = direction.multiply(1, 0, 1);
        double along = flat.lengthSqr() < 1.0E-8 ? 0 : motion.multiply(1, 0, 1).dot(flat.normalize());
        return (float) (1 + (airborne ? airBoost : 0) + Math.clamp(along / Math.max(.05, speed), 0, MOMENTUM_CAP));
    }
    /** The extra damage a harder throw deals: half of what it gained in impulse. */
    public static float impulsePower(float impulse) { return 1 + .5F * (impulse - 1); }

    private static final Map<Identifier, Returning> RETURNING = new LinkedHashMap<>();
    private static final Map<Identifier, Charged> CHARGED = new LinkedHashMap<>();
    static { load(); }
    private ThrownAttacks() {}

    public static Collection<Returning> returning() { return Collections.unmodifiableCollection(RETURNING.values()); }
    public static Collection<Charged> charged() { return Collections.unmodifiableCollection(CHARGED.values()); }
    public static Returning returning(DigimonAttack attack) { return attack == null ? null : RETURNING.get(attack.id()); }
    public static Charged charged(DigimonAttack attack) { return attack == null ? null : CHARGED.get(attack.id()); }
    public static boolean handles(DigimonAttack attack) {
        return attack != null && (attack.kind() == DigimonAttack.Kind.RETURNING_THROW || attack.kind() == DigimonAttack.Kind.CHARGED_THROW);
    }
    public static java.util.List<DigimonAttack> attacks() {
        var all = new java.util.ArrayList<DigimonAttack>();
        RETURNING.values().forEach(r -> all.add(r.attack()));
        CHARGED.values().forEach(c -> all.add(c.attack()));
        return all;
    }

    /** Every clip name a thrower plays, and the attack it belongs to (the renderer and the client's clip clock). */
    public static DigimonAttack owner(String clip) {
        if (clip == null) return null;
        for (var r : RETURNING.values())
            if (clip.equals(r.throwClip().name()) || clip.equals(r.catchClip().name()) || clip.equals(r.pickupClip().name())
                    || clip.equals(r.holdClip()) || clip.equals(r.releaseClip().name())) return r.attack();
        for (var c : CHARGED.values())
            if (clip.equals(c.form().name()) || clip.equals(c.hold()) || clip.equals(c.release().name())) return c.attack();
        return null;
    }
    /** Length of a thrower clip in ticks; a hold lasts until the server says otherwise. */
    public static int length(String clip) {
        for (var r : RETURNING.values()) {
            for (var c : new Clip[]{r.throwClip(), r.catchClip(), r.pickupClip(), r.releaseClip()}) if (c.name().equals(clip)) return c.length();
            if (r.holdClip().equals(clip)) return 20 * 60;
        }
        for (var c : CHARGED.values()) {
            if (c.form().name().equals(clip)) return c.form().length();
            if (c.release().name().equals(clip)) return c.release().length();
            if (c.hold().equals(clip)) return 20 * 60;
        }
        return 0;
    }

    /** A clip that carries on a performance already under way (a hold, a release after it): it does not blend in again. */
    public static boolean continues(String clip) {
        for (var r : RETURNING.values()) if (clip.equals(r.holdClip()) || clip.equals(r.releaseClip().name())) return true;
        for (var c : CHARGED.values()) if (clip.equals(c.hold()) || clip.equals(c.release().name())) return true;
        return false;
    }
    /** A clip that another follows without a blend (a form, a hold): it does not blend out. */
    public static boolean goesOn(String clip) {
        for (var r : RETURNING.values()) if (clip.equals(r.holdClip())) return true;
        for (var c : CHARGED.values()) if (clip.equals(c.form().name()) || clip.equals(c.hold())) return true;
        return false;
    }

    private static JsonObject read(String path) {
        try (var input = ThrownAttacks.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path);
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Cannot read " + path, e); }
    }
    private static double[] doubles(JsonObject o, String key, int n) {
        var a = o.getAsJsonArray(key);
        if (a.size() != n) throw new IllegalArgumentException("Expected " + n + " numbers in " + key);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) { r[i] = a.get(i).getAsDouble(); if (!Double.isFinite(r[i])) throw new IllegalArgumentException(key); }
        return r;
    }
    private static float[] pair(JsonObject o, String key) {
        double[] d = doubles(o, key, 2); return new float[]{(float) d[0], (float) d[1]};
    }
    private static Clip clip(JsonArray a) {
        var c = new Clip(a.get(0).getAsString(), a.get(1).getAsInt(), a.size() > 2 ? a.get(2).getAsInt() : 0);
        if (c.length() < 1 || c.event() < 0 || c.event() >= c.length()) throw new IllegalArgumentException("Invalid thrower clip " + c);
        return c;
    }
    private static Vec3 point(JsonObject o) {
        var a = o.getAsJsonArray("point"); return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    private static void load() {
        var config = read("/data/digicube/thrown_attacks.json");
        for (var entry : config.entrySet()) {
            var c = entry.getValue().getAsJsonObject();
            var id = Constants.id(entry.getKey());
            var kind = DigimonAttack.Kind.valueOf(c.get("kind").getAsString());
            var attack = new DigimonAttack(id, kind, c.get("power").getAsFloat(), c.get("cooldown").getAsInt(),
                    c.get("duration").getAsInt(), c.get("hit_tick").getAsInt(), c.get("range").getAsDouble(), false);
            var motion = read("/data/digicube/thrown_motion/" + c.get("motion").getAsString() + ".json");
            var clips = c.getAsJsonObject("clips");
            if (kind == DigimonAttack.Kind.RETURNING_THROW) {
                var r = new Returning(attack, c.get("projectile").getAsString(), c.get("model_scale").getAsFloat(),
                        c.get("min_range").getAsDouble(), c.get("max_range").getAsDouble(), doubles(c, "catch_side", 2),
                        c.get("bulge").getAsDouble(), doubles(c, "speed", 3), c.get("spin").getAsFloat(), c.get("return_power").getAsFloat(),
                        c.get("hit_radius").getAsDouble(), c.get("hit_height").getAsDouble(), c.get("catch_radius").getAsDouble(),
                        c.get("catch_window").getAsDouble(), c.get("drop_ticks").getAsInt(), c.get("pickup_radius").getAsDouble(),
                        clip(clips.getAsJsonArray("throw")), clip(clips.getAsJsonArray("catch")), clip(clips.getAsJsonArray("pickup")),
                        point(motion.getAsJsonObject("bone_release")), point(motion.getAsJsonObject("bone_catch")),
                        point(motion.getAsJsonObject("bone_pickup")), c.get("recovery").getAsInt(), c.get("cruise_height").getAsDouble(),
                        c.get("far_range").getAsDouble(), c.get("hold_at").getAsInt(), c.get("charge_ticks").getAsInt(), c.get("max_hold").getAsInt(),
                        pair(c, "charge_speed"), pair(c, "charge_power"), GsonHelper.getAsFloat(c, "air_boost", 0), doubles(c, "lift", 2),
                        clips.get("hold").getAsString(), clip(clips.getAsJsonArray("release")), point(motion.getAsJsonObject("bone_release_heavy")));
                if (r.minRange() <= 0 || r.maxRange() < r.minRange() || r.speed()[0] <= 0 || r.speed()[1] <= 0 || r.speed()[2] <= 0
                        || r.catchWindow() <= 0 || r.catchWindow() > 1 || r.dropTicks() < 1 || r.throwClip().event() != attack.hitTick() || r.cruiseHeight() <= 0
                        || r.throwClip().length() != attack.durationTicks() || r.farRange() < r.maxRange() || r.chargeTicks() < 1 || r.maxHold() < r.chargeTicks()
                        || r.holdAt() < 1 || r.holdAt() + r.releaseClip().event() != r.throwClip().event()
                        || r.holdAt() + r.releaseClip().length() != r.throwClip().length() || r.airBoost() < 0 || r.lift()[0] > 0 || r.lift()[1] < 0)
                    throw new IllegalArgumentException("Invalid returning throw " + id);
                RETURNING.put(id, r);
            } else if (kind == DigimonAttack.Kind.CHARGED_THROW) {
                var light = motion.getAsJsonObject("icicle_light"); var heavy = motion.getAsJsonObject("icicle_heavy");
                var ch = new Charged(attack, c.get("projectile").getAsString(), c.get("model_scale").getAsFloat(),
                        clip(clips.getAsJsonArray("form")), clips.get("hold").getAsString(), clip(clips.getAsJsonArray("release")),
                        c.get("charge_ticks").getAsInt(), c.get("max_hold").getAsInt(), pair(c, "charge_power"), pair(c, "speed"),
                        pair(c, "gravity"), pair(c, "size"), pair(c, "half_width"), pair(c, "half_length"), pair(c, "knockback"),
                        c.get("embed_ticks").getAsInt(), GsonHelper.getAsFloat(c, "walk_pace", 1), new Vec3[]{point(light), point(heavy)},
                        GsonHelper.getAsFloat(c, "air_boost", 0));
                if (ch.chargeTicks() < 1 || ch.maxHold() < ch.chargeTicks() || ch.form().length() + ch.release().event() != attack.hitTick()
                        || ch.form().length() + ch.release().length() != attack.durationTicks())
                    throw new IllegalArgumentException("Invalid charged throw " + id);
                CHARGED.put(id, ch);
            } else throw new IllegalArgumentException("Not a thrown attack kind: " + id);
        }
    }
}
