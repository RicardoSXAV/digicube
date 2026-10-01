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
import java.util.Map;

/**
 * Held charges that end in a blow ({@code rush_attacks.json}, Monochromon's Guardy Tusk): the body braces, then rushes,
 * then strikes with the authored attack of the same id (its clip, volumes and effect). The entity's
 * {@code BullRush} runs it; held longer it rushes harder.
 */
public final class RushAttacks {
    /**
     * @param build          ticks a rider's brace lasts before the rush (the attack tile fills over them)
     * @param aiBuild        the same for the AI
     * @param pace           blocks a tick at the top of the rush
     * @param ramp           ticks the rush takes to reach its pace from where the brace left it
     * @param brake          ticks a standing brace takes to stop the body
     * @param standingBelow  blocks a tick under which the brace stands and paws the ground; faster, the body lowers its
     *                       head on the run and keeps its pace
     * @param turn           degrees a tick the rush turns after the rider's view or the AI's prey
     * @param braceTurn      degrees a tick a standing brace turns
     * @param maxTicks       ticks of rushing after which it strikes by itself
     * @param reach          blocks from which the AI starts a rush
     * @param power          share of the blow's damage released from the brace, and after a full rush
     * @param knockback      share of the blow's knockback, the same two ends
     * @param toss           blocks a tick the blow throws its victim up, the same two ends
     * @param scrape         ticks of a standing brace between which the pawing forefoot drags back along the ground
     */
    public record Spec(Identifier id, int build, int aiBuild, double pace, int ramp, int brake, double standingBelow, float turn,
                       float braceTurn, int maxTicks, double reach, float[] power, float[] knockback, float[] toss, float[] scrape) {
        /** How far the rush has gone, 0 (released from the brace) to 1 (at its full pace), for the blow it ends in. */
        public float share(float low, float high, float rushed) { return low + (high - low) * Math.clamp(rushed, 0, 1); }
        public float power(float rushed) { return share(power[0], power[1], rushed); }
        public float knockback(float rushed) { return share(knockback[0], knockback[1], rushed); }
        public float toss(float rushed) { return share(toss[0], toss[1], rushed); }
    }

    private static final Map<Identifier, Spec> SPECS = load();
    private RushAttacks() {}

    public static Spec get(DigimonAttack attack) { return attack == null ? null : SPECS.get(attack.id()); }
    public static boolean handles(DigimonAttack attack) { return get(attack) != null; }

    private static float[] pair(JsonObject c, String key, Identifier id) {
        JsonArray a = GsonHelper.getAsJsonArray(c, key);
        if (a.size() != 2) throw new IllegalArgumentException("A rush's " + key + " is a pair " + id);
        float[] v = {a.get(0).getAsFloat(), a.get(1).getAsFloat()};
        if (!Float.isFinite(v[0]) || !Float.isFinite(v[1]) || v[0] < 0 || v[1] < v[0]) throw new IllegalArgumentException("Invalid rush " + key + " " + id);
        return v;
    }

    private static Map<Identifier, Spec> load() {
        String path = "/data/digicube/rush_attacks.json";
        JsonObject config;
        try (var input = RushAttacks.class.getResourceAsStream(path)) {
            if (input == null) return Map.of();
            config = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Cannot read " + path, e); }
        var result = new LinkedHashMap<Identifier, Spec>();
        for (var entry : config.entrySet()) {
            var id = Constants.id(entry.getKey());
            var c = entry.getValue().getAsJsonObject();
            var spec = new Spec(id, GsonHelper.getAsInt(c, "build"), GsonHelper.getAsInt(c, "ai_build"), GsonHelper.getAsDouble(c, "pace"),
                    GsonHelper.getAsInt(c, "ramp"), GsonHelper.getAsInt(c, "brake"), GsonHelper.getAsDouble(c, "standing_below"),
                    GsonHelper.getAsFloat(c, "turn"), GsonHelper.getAsFloat(c, "brace_turn"), GsonHelper.getAsInt(c, "max_ticks"),
                    GsonHelper.getAsDouble(c, "reach"), pair(c, "power", id), pair(c, "knockback", id), pair(c, "toss", id), pair(c, "scrape", id));
            if (spec.build() < 1 || spec.aiBuild() < 1 || !(spec.pace() > 0 && spec.pace() < 2) || spec.ramp() < 1 || spec.brake() < 1
                    || spec.standingBelow() < 0 || !(spec.turn() > 0) || spec.braceTurn() < 0 || spec.maxTicks() < 1 || !(spec.reach() > 0))
                throw new IllegalArgumentException("Invalid rush " + id);
            result.put(id, spec);
        }
        return Collections.unmodifiableMap(result);
    }
}
