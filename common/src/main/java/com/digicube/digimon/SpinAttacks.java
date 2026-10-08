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
 * Spins in the shell ({@code data/digicube/spin_attacks.json}, Shellmon's Drill Shell, {@link DigimonAttack.Kind#SPIN}):
 * the body withdraws into its shell (the cast), spins up while the move is held (its strength), and is let go to spin
 * off where it is aimed, striking every body it runs into, harder the faster it goes and harder to steer as well. The
 * entity's {@code ShellSpin} runs it; the server moves the body from the press until the body has come back out.
 */
public final class SpinAttacks {
    /**
     * @param attack     the move: its power is the strongest blow's (a weaker spin strikes a share of it), cooldown and range
     * @param withdraw   ticks the withdrawal takes, before it can spin up (the cast)
     * @param charge     ticks of holding to spin up to full strength
     * @param aiCharge   ticks the AI spins up from closest to furthest prey
     * @param maxHold    ticks a held spin waits before it goes by itself
     * @param speed      blocks a tick it sets off at, weakest and strongest
     * @param friction   share of its speed it keeps each tick on the ground
     * @param stopBelow  blocks a tick under which it winds down
     * @param maxTicks   ticks it spins on at most once let go
     * @param turn       degrees a tick it is steered at its slowest and its fastest
     * @param grip       share of the steering its travel follows at its slowest and its fastest (the rest slides on: a
     *                   fast spin skids wide through a turn)
     * @param power      share of the move's power struck at the weakest and the strongest (times the share of its top
     *                   speed it still has)
     * @param knockback  blocks a tick a struck body is thrown along its travel, weakest and strongest
     * @param toss       blocks a tick it is thrown up, weakest and strongest
     * @param bounce     share of its speed it keeps off a wall
     * @param rebound    share of its speed it keeps off a body it strikes
     * @param hitCooldown ticks before it can strike the same body again
     * @param windDown   ticks it takes to stop spinning
     * @param emerge     ticks the body takes to come back out
     * @param guard      share of a blow's damage the body takes while it is in its shell (from half way through the
     *                   withdrawal until it starts to come out); 1 when the sheet names none
     */
    public record Spec(DigimonAttack attack, int withdraw, int charge, int[] aiCharge, int maxHold, float[] speed, float friction,
                       float stopBelow, int maxTicks, float[] turn, float[] grip, float[] power, float[] knockback, float[] toss,
                       float bounce, float rebound, int hitCooldown, int windDown, int emerge, float guard) {
        public static float share(float[] pair, float t) { return pair[0] + (pair[1] - pair[0]) * Math.clamp(t, 0, 1); }
        /** How fast it sets off for a spin-up of {@code charge} (0 to 1). */
        public float launch(float charge) { return share(speed, charge); }
        /** Its top speed: a full spin-up's. */
        public float top() { return speed[1]; }
    }

    private static final Map<Identifier, Spec> SPECS = load();
    private SpinAttacks() {}

    public static Spec get(DigimonAttack attack) { return attack == null ? null : SPECS.get(attack.id()); }
    public static boolean handles(DigimonAttack attack) { return attack != null && attack.kind() == DigimonAttack.Kind.SPIN && get(attack) != null; }
    /** Every spin's move, for the species sheets to name. */
    public static List<DigimonAttack> attacks() { return SPECS.values().stream().map(Spec::attack).toList(); }

    private static float[] pair(JsonObject c, String key, Identifier id) {
        JsonArray a = GsonHelper.getAsJsonArray(c, key);
        if (a.size() != 2) throw new IllegalArgumentException("A spin's " + key + " is a pair " + id);
        float[] v = {a.get(0).getAsFloat(), a.get(1).getAsFloat()};
        if (!Float.isFinite(v[0]) || !Float.isFinite(v[1]) || v[0] < 0 || v[1] < 0) throw new IllegalArgumentException("Invalid spin " + key + " " + id);
        return v;
    }

    private static Map<Identifier, Spec> load() {
        String path = "/data/digicube/spin_attacks.json";
        JsonObject config;
        try (var input = SpinAttacks.class.getResourceAsStream(path)) {
            if (input == null) return Map.of();
            config = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Cannot read " + path, e); }
        var result = new LinkedHashMap<Identifier, Spec>();
        for (var entry : config.entrySet()) {
            var id = Constants.id(entry.getKey());
            var c = entry.getValue().getAsJsonObject();
            int withdraw = GsonHelper.getAsInt(c, "withdraw");
            var attack = new DigimonAttack(id, DigimonAttack.Kind.SPIN, GsonHelper.getAsFloat(c, "power"), GsonHelper.getAsInt(c, "cooldown"),
                    withdraw + 1, 0, GsonHelper.getAsDouble(c, "range"), false);
            float[] ai = pair(c, "ai_charge", id);
            var spec = new Spec(attack, withdraw, GsonHelper.getAsInt(c, "charge"), new int[]{(int) ai[0], (int) ai[1]},
                    GsonHelper.getAsInt(c, "max_hold"), pair(c, "speed", id), GsonHelper.getAsFloat(c, "friction"),
                    GsonHelper.getAsFloat(c, "stop_below"), GsonHelper.getAsInt(c, "max_ticks"), pair(c, "turn", id), pair(c, "grip", id),
                    pair(c, "power_share", id), pair(c, "knockback", id), pair(c, "toss", id), GsonHelper.getAsFloat(c, "bounce"),
                    GsonHelper.getAsFloat(c, "rebound"), GsonHelper.getAsInt(c, "hit_cooldown"), GsonHelper.getAsInt(c, "wind_down"),
                    GsonHelper.getAsInt(c, "emerge"), GsonHelper.getAsFloat(c, "shell_guard", 1F));
            if (withdraw < 1 || spec.charge() < 1 || spec.aiCharge()[0] < 0 || spec.aiCharge()[1] < spec.aiCharge()[0] || spec.maxHold() < spec.charge()
                    || !(spec.speed()[0] > 0 && spec.speed()[1] >= spec.speed()[0] && spec.speed()[1] < 3)
                    || !(spec.friction() > .5 && spec.friction() <= 1) || !(spec.stopBelow() > 0 && spec.stopBelow() < spec.speed()[0])
                    || spec.maxTicks() < 1 || !(spec.turn()[0] > 0) || !(spec.grip()[0] > 0 && spec.grip()[0] <= 1 && spec.grip()[1] > 0 && spec.grip()[1] <= 1)
                    || !(spec.bounce() >= 0 && spec.bounce() <= 1) || !(spec.rebound() >= 0 && spec.rebound() <= 1)
                    || spec.hitCooldown() < 1 || spec.windDown() < 1 || spec.emerge() < 1 || !(spec.guard() > 0 && spec.guard() <= 1))
                throw new IllegalArgumentException("Invalid spin " + id);
            result.put(id, spec);
        }
        return Collections.unmodifiableMap(result);
    }
}
