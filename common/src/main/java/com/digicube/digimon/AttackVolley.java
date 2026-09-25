package com.digicube.digimon;

import com.digicube.Constants;
import com.digicube.entity.AttackBox;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An authored burst that fires parts of the caster's own body as homing missiles (Digmon's Gold Rush: the nose and hand
 * drills). The clip hides those parts from {@code launchTick} and grows them back; in between each flies as a
 * {@code VolleyMissileEntity}, drawn with the part's own geometry. {@code volley} in {@code authored_attacks.json} holds the
 * flight, {@code attack_motion/<attack>_volley.json} (harness {@code digmon/gold_rush_02/make_volley.py}) where each part
 * leaves the body.
 *
 * @param launchTick  attack tick at which the parts leave
 * @param speed       full thrust, blocks a tick
 * @param life        ticks a missile flies before it burns out
 * @param turn        degrees a tick a missile turns toward its mark once lit
 * @param power       share of the attack's damage one missile deals (they add up: each hit bypasses the hurt cooldown)
 * @param model       species whose mesh the parts come from
 * @param modelScale  that mesh's scale in the world
 * @param missiles    one per part, in launch order
 */
public record AttackVolley(int launchTick, double speed, int life, float turn, float power, String model, float modelScale,
                           List<Missile> missiles) {
    /**
     * @param part   mesh part flown (it has no children)
     * @param delay  ticks the part coasts out of its socket before it lights
     * @param launch the part at release in the caster's frame (blocks: x left, y up, z forward), its z half-axis toward the tip
     * @param centre the part's centre in its own frame, px: the axis it spins about passes through it
     * @param tip    the direction of the tip in the part's own frame
     */
    public record Missile(String part, int delay, AttackBox launch, Vec3 centre, Vec3 tip) {}

    /** Ticks of coasting at the socket before the thrust lights. */
    public static final int MAX_DELAY = 5;

    static AttackVolley load(String attack, JsonObject c, int duration) {
        var table = read("/data/digicube/attack_motion/" + attack + "_volley.json");
        if (!attack.equals(GsonHelper.getAsString(table, "attack"))) throw new IllegalArgumentException("Volley table for another attack " + attack);
        var missiles = new ArrayList<Missile>();
        for (var entry : GsonHelper.getAsJsonArray(table, "missiles")) {
            var m = entry.getAsJsonObject();
            var box = GsonHelper.getAsJsonArray(m, "launch");
            if (box.size() != 12) throw new IllegalArgumentException("Volley launch box " + attack);
            var missile = new Missile(GsonHelper.getAsString(m, "part"), GsonHelper.getAsInt(m, "delay"),
                    new AttackBox(vector(box, 0), vector(box, 3), vector(box, 6), vector(box, 9)),
                    vector(GsonHelper.getAsJsonArray(m, "centre"), 0), vector(GsonHelper.getAsJsonArray(m, "tip"), 0));
            if (missile.delay() < 0 || missile.delay() > MAX_DELAY || missile.launch().z().lengthSqr() < 1.0E-6
                    || Math.abs(missile.tip().length() - 1) > 1.0E-3) throw new IllegalArgumentException("Volley missile " + attack + " " + missile.part());
            missiles.add(missile);
        }
        var volley = new AttackVolley(GsonHelper.getAsInt(table, "launch_tick"), GsonHelper.getAsDouble(c, "speed"),
                GsonHelper.getAsInt(c, "life"), GsonHelper.getAsFloat(c, "turn"), GsonHelper.getAsFloat(c, "power"),
                GsonHelper.getAsString(table, "model"), GsonHelper.getAsFloat(table, "model_scale"), List.copyOf(missiles));
        if (missiles.isEmpty() || volley.launchTick() < 0 || volley.launchTick() >= duration || !(volley.speed() > 0) || volley.speed() > 4
                || volley.life() < 1 || !(volley.turn() >= 0) || !(volley.power() > 0) || !(volley.modelScale() > 0))
            throw new IllegalArgumentException("Invalid volley " + attack);
        return volley;
    }

    private static JsonObject read(String path) {
        try (var input = AttackVolley.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path);
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException(path, e); }
    }

    private static Vec3 vector(JsonArray a, int i) {
        double x = a.get(i).getAsDouble(), y = a.get(i + 1).getAsDouble(), z = a.get(i + 2).getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Nonfinite volley coordinate");
        return new Vec3(x, y, z);
    }

    /** The mesh a volley's parts are drawn from, as the client names it. */
    public net.minecraft.resources.Identifier modelId() { return Constants.id(model); }
}
