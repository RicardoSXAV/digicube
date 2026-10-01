package com.digicube.digimon;

import com.digicube.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A breath that behaves like a gas thrown from the mouth ({@code data/digicube/breath_attacks.json}; Garurumon's
 * Howling Blaster, Seadramon's Ice Blast, Meramon's Heat Wave from his two palms), a frost stream ({@link DigimonAttack.Kind#FROST_STREAM}) on a tank like any
 * other. Instead of a straight jet it is a train of puffs ({@code com.digicube.entity.FrostBreath}): each tick the mouth
 * sheds some along the aim with the body's own motion added, they fly on slowed by the air, widen as they age, slide
 * along what they hit and die away, so a swept aim bends the stream like water from a hose and a running breather trails
 * it. The server strikes with the same puffs every client draws.
 */
public final class BreathAttacks {
    /**
     * @param attack    the move (a fueled frost stream): power per damage pulse, the clip, its motion (mouth and aim pivot)
     * @param speed     blocks a tick a puff leaves the mouth at
     * @param drag      share of its speed a puff keeps each tick
     * @param life      ticks a puff lasts
     * @param radius    a puff's radius (blocks) by its age (ticks): [age, radius] points with rising ages from 0, straight
     *                  between them and held past the last; the drawn flame is as wide (narrow at the mouth, broadest
     *                  partway out, its tips thin)
     * @param perTick   puffs shed a tick (spread over the tick, so the train stays whole at any sweep)
     * @param spread    random scatter of a puff's direction (share of its speed)
     * @param rise      what a puff's vertical speed gains each tick (negative sinks: cold air is heavy)
     * @param bounce    share of the speed into a surface that a puff keeps, turned along it
     * @param freeze    share of a Freeze gauge ({@link FreezeMark}) each tick of contact pays in (0 to 100)
     * @param chills    contact charges Cold ({@link IceCombo}) instead of the Freeze gauge ({@code "mark": "cold"})
     * @param burn      ticks contact sets a body alight for, kept topped up while it plays on it ({@code "mark": "burn"}:
     *                  a fire breath, a Burn in the fight's terms; it thaws a frozen body and pays no Freeze), else 0
     * @param melt      snow and ice the breath plays on melt (snow layers and blocks go, ice turns to water)
     * @param waterIce  still water the breath crosses freezes into frosted ice (the frost walker's, which melts back)
     * @param douse     fire the breath crosses goes out
     * @param turn      degrees a tick the head follows the aim across, and {@code pitchTurn} up or down
     * @param twist     the most the neck turns off the body's heading toward the aim (degrees); past it the body turns
     * @param effect    the effect model the renderer draws the puffs with
     * @param art       how the puffs are drawn from it: {@code flame} (sections of one flame, Howling Blaster's) or
     *                  {@code shards} (ice shards, frosty sheets and snow, Ice Blast's)
     * @param sounds    what every client plays for it (optional: null plays nothing)
     * @param pixel     blocks one pixel of the effect model's art is drawn at (the flame's breadth scales with it)
     * @param cooling   the colour a puff's blocks are tinted toward as it dies (red, green, blue shares; Howling Blaster's
     *                  frost cools a little bluer, Heat Wave's fire deeper red)
     */
    public record Spec(DigimonAttack attack, float speed, float drag, int life, float[][] radius, int perTick, float spread, float rise,
                       float bounce, float freeze, boolean chills, int burn, boolean melt, boolean waterIce, boolean douse, float turn,
                       float pitchTurn, float twist, String effect, String art, Sounds sounds, float pixel, float[] cooling) {
        /** A fire breath: contact sets bodies alight instead of chilling them. */
        public boolean burns() { return burn > 0; }
        /** Blocks a puff flies over its whole life in still air (the stream's reach). */
        public float reach() {
            float d = 0, v = speed;
            for (int i = 0; i < life; i++) { d += v; v *= drag; }
            return d;
        }
        public float radiusAt(float age) {
            if (age <= radius[0][0]) return radius[0][1];
            for (int i = 1; i < radius.length; i++) {
                if (age <= radius[i][0]) {
                    float t = (age - radius[i - 1][0]) / (radius[i][0] - radius[i - 1][0]);
                    return radius[i - 1][1] + (radius[i][1] - radius[i - 1][1]) * t;
                }
            }
            return radius[radius.length - 1][1];
        }
    }

    /**
     * A breath's sounds, sound event ids: {@code start} as the mouth starts shedding, {@code loop} looped from the mouth
     * while it sheds (fading in under the start), {@code end} as it stops (the loop fading out under it).
     */
    public record Sounds(Identifier start, Identifier loop, Identifier end) {}

    private static final Map<Identifier, Spec> SPECS = load();
    private BreathAttacks() {}

    public static Spec get(DigimonAttack attack) { return attack == null ? null : SPECS.get(attack.id()); }
    public static boolean handles(DigimonAttack attack) { return attack != null && attack.kind() == DigimonAttack.Kind.FROST_STREAM && get(attack) != null; }
    /** Every breath's move, for the species sheets to name. */
    public static List<DigimonAttack> attacks() { return SPECS.values().stream().map(Spec::attack).toList(); }

    private static float[] floats(JsonObject o, String key, int size) {
        JsonArray a = GsonHelper.getAsJsonArray(o, key);
        if (a.size() != size) throw new IllegalArgumentException("Breath " + key + " needs " + size + " numbers");
        float[] out = new float[size];
        for (int i = 0; i < size; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static Sounds sounds(JsonObject c) {
        if (!c.has("sounds")) return null;
        var o = GsonHelper.getAsJsonObject(c, "sounds");
        return new Sounds(Identifier.parse(GsonHelper.getAsString(o, "start")), Identifier.parse(GsonHelper.getAsString(o, "loop")),
                Identifier.parse(GsonHelper.getAsString(o, "end")));
    }

    /** A radius profile: [age, radius] points with rising ages, the first at age 0, every radius above zero. */
    private static float[][] profile(JsonObject o, String key) {
        JsonArray a = GsonHelper.getAsJsonArray(o, key);
        float[][] out = new float[a.size()][];
        for (int i = 0; i < out.length; i++) {
            JsonArray point = a.get(i).getAsJsonArray();
            out[i] = new float[]{point.get(0).getAsFloat(), point.get(1).getAsFloat()};
            if (point.size() != 2 || !(out[i][1] > 0 && out[i][1] < 4) || i == 0 && out[i][0] != 0 || i > 0 && !(out[i][0] > out[i - 1][0]))
                throw new IllegalArgumentException("Breath " + key + " needs [age, radius] points with rising ages from 0");
        }
        if (out.length < 2) throw new IllegalArgumentException("Breath " + key + " needs at least two points");
        return out;
    }

    private static Map<Identifier, Spec> load() {
        try (var input = BreathAttacks.class.getResourceAsStream("/data/digicube/breath_attacks.json")) {
            if (input == null) return Map.of();
            var data = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            var result = new LinkedHashMap<Identifier, Spec>();
            for (var entry : data.entrySet()) {
                var c = entry.getValue().getAsJsonObject();
                var id = Constants.id(entry.getKey());
                float[] fuel = floats(c, "fuel", 3);
                var attack = new DigimonAttack(id, DigimonAttack.Kind.FROST_STREAM, GsonHelper.getAsFloat(c, "power"), 0,
                        GsonHelper.getAsInt(c, "duration"), GsonHelper.getAsInt(c, "hit_tick"), GsonHelper.getAsDouble(c, "range"), false,
                        AttackMotion.load(id), new AttackFuel((int) fuel[0], (int) fuel[1], (int) fuel[2]), 0);
                String mark = GsonHelper.getAsString(c, "mark", "freeze");
                if (!mark.equals("freeze") && !mark.equals("cold") && !mark.equals("burn"))
                    throw new IllegalArgumentException("Breath " + id + " marks freeze, cold or burn");
                int burn = mark.equals("burn") ? GsonHelper.getAsInt(c, "burn") : 0;
                if (mark.equals("burn") && (burn < 1 || burn > 400)) throw new IllegalArgumentException("Breath " + id + " burns 1 to 400 ticks");
                float[] cooling = c.has("cooling") ? floats(c, "cooling", 3) : new float[]{.88F, .92F, 1};
                String art = GsonHelper.getAsString(c, "art", "flame");
                if (!art.equals("flame") && !art.equals("shards")) throw new IllegalArgumentException("Breath " + id + " is drawn as flame or shards");
                var spec = new Spec(attack, GsonHelper.getAsFloat(c, "speed"), GsonHelper.getAsFloat(c, "drag"), GsonHelper.getAsInt(c, "life"),
                        profile(c, "radius"), GsonHelper.getAsInt(c, "per_tick"), GsonHelper.getAsFloat(c, "spread"), GsonHelper.getAsFloat(c, "rise"),
                        GsonHelper.getAsFloat(c, "bounce"), GsonHelper.getAsFloat(c, "freeze"), mark.equals("cold"), burn,
                        GsonHelper.getAsBoolean(c, "melt", false), GsonHelper.getAsBoolean(c, "water_ice", false), GsonHelper.getAsBoolean(c, "douse", false),
                        GsonHelper.getAsFloat(c, "turn"), GsonHelper.getAsFloat(c, "pitch_turn"), GsonHelper.getAsFloat(c, "twist"),
                        GsonHelper.getAsString(c, "effect"), art, sounds(c), GsonHelper.getAsFloat(c, "pixel", .025F), cooling);
                if (!(spec.speed() > 0 && spec.speed() < 4) || !(spec.drag() > 0 && spec.drag() <= 1) || spec.life() < 2 || spec.life() > 80
                        || spec.perTick() < 1 || spec.perTick() > 6
                        || !(spec.spread() >= 0 && spec.spread() < 1) || !(spec.bounce() >= 0 && spec.bounce() <= 1)
                        || !(spec.freeze() >= 0 && spec.freeze() <= FreezeMark.FULL) || !(spec.turn() > 0) || !(spec.pitchTurn() > 0)
                        || !(spec.twist() >= 0 && spec.twist() <= 110) || !(spec.pixel() > .005F && spec.pixel() < .1F)
                        || spec.burns() && (spec.freeze() > 0 || spec.waterIce())
                        || !(cooling[0] >= 0 && cooling[0] <= 1 && cooling[1] >= 0 && cooling[1] <= 1 && cooling[2] >= 0 && cooling[2] <= 1))
                    throw new IllegalArgumentException("Invalid breath " + id);
                result.put(id, spec);
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read breath attacks", e);
        }
    }
}
