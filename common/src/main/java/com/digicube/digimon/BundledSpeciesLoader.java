package com.digicube.digimon;

import com.digicube.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the bundled species catalog identically on client and server at startup.
 * Species content is JSON; attack mechanics are shared references from the bootstrap.
 * This classpath loader does not yet support datapack reloads or server catalog sync.
 */
public final class BundledSpeciesLoader {
    private BundledSpeciesLoader() {}

    /**
     * Parse and validate the whole catalog before publishing any species.
     * @param attacks known shared moves
     * @return species in catalog order
     */
    public static List<DigimonSpecies> load(Map<Identifier, DigimonAttack> attacks) {
        var species = new LinkedHashMap<Identifier, DigimonSpecies>();
        for (var entry : GsonHelper.getAsJsonArray(read("/data/digicube/species.json"), "species")) {
            Identifier id = identifier(GsonHelper.convertToString(entry, "species id"));
            String path = "/data/" + id.getNamespace() + "/species/" + id.getPath() + ".json";
            if (species.putIfAbsent(id, parse(id, read(path), attacks)) != null) {
                throw new IllegalArgumentException("Duplicate species in catalog: " + id);
            }
        }
        for (var definition : species.values()) {
            for (var evolution : definition.evolutions()) {
                if (!species.containsKey(evolution.target())) {
                    throw new IllegalArgumentException(definition.id() + ": unknown evolution target " + evolution.target());
                }
            }
        }
        return List.copyOf(species.values());
    }

    /**
     * Parse one bundled sheet on its own, as the developer panel does to undo tuning.
     * Evolution targets are not cross-checked here; the full catalog did that at startup.
     */
    public static DigimonSpecies loadOne(Identifier id, Map<Identifier, DigimonAttack> attacks) {
        return parse(id, read("/data/" + id.getNamespace() + "/species/" + id.getPath() + ".json"), attacks);
    }

    /**
     * Decode a species sheet. Unqualified references use the mod namespace.
     * @param id species identifier from its resource path
     * @param json species data
     * @param attacks known shared moves
     * @return validated immutable definition
     */
    public static DigimonSpecies parse(Identifier id, JsonObject json, Map<Identifier, DigimonAttack> attacks) {
        int health = GsonHelper.getAsInt(json, "base_health");
        int attack = GsonHelper.getAsInt(json, "base_attack");
        int defence = GsonHelper.getAsInt(json, "base_defence");
        float speed = GsonHelper.getAsFloat(json, "base_speed");
        if (health <= 0 || attack < 0 || defence < 0 || !Float.isFinite(speed) || speed <= 0) {
            throw new IllegalArgumentException(id + ": invalid base stats");
        }
        var moves = new ArrayList<DigimonAttack>();
        for (var entry : GsonHelper.getAsJsonArray(json, "attacks")) {
            Identifier moveId = identifier(GsonHelper.convertToString(entry, "attack id"));
            DigimonAttack move = attacks.get(moveId);
            if (move == null) throw new IllegalArgumentException(id + ": unknown attack " + moveId);
            if (moves.contains(move)) throw new IllegalArgumentException(id + ": duplicate attack " + moveId);
            moves.add(move);
        }
        var evolutions = new ArrayList<Evolution>();
        for (var entry : GsonHelper.getAsJsonArray(json, "evolutions")) {
            JsonObject e = GsonHelper.convertToJsonObject(entry, "evolution");
            int level = GsonHelper.getAsInt(e, "min_level");
            int bond = GsonHelper.getAsInt(e, "min_bond", 0);
            int weight = GsonHelper.getAsInt(e, "max_weight", -1);
            int training = GsonHelper.getAsInt(e, "min_training", 0);
            if (level < 1 || bond < 0 || bond > 100 || weight < -1 || training < 0) {
                throw new IllegalArgumentException(id + ": invalid evolution conditions");
            }
            evolutions.add(new Evolution(identifier(GsonHelper.getAsString(e, "target")), level, bond, weight, training,
                    e.has("required_item") ? identifier(GsonHelper.getAsString(e, "required_item")) : null));
        }
        return new DigimonSpecies(id, DigimonStage.byId(GsonHelper.getAsString(json, "stage")),
                DigimonAttribute.byId(GsonHelper.getAsString(json, "attribute")), health, attack, defence, speed,
                evolutions, moves, json.has("body") ? body(GsonHelper.getAsJsonObject(json, "body")) : DigimonBody.DEFAULT,
                json.has("locomotion") ? locomotion(GsonHelper.getAsJsonObject(json, "locomotion")) : DigimonLocomotion.DEFAULT);
    }

    private static DigimonLocomotion locomotion(JsonObject json) {
        return new DigimonLocomotion(GsonHelper.getAsFloat(json, "follow_start_distance"),
                GsonHelper.getAsFloat(json, "follow_stop_distance"),
                GsonHelper.getAsDouble(json, "walk_speed"), GsonHelper.getAsDouble(json, "run_speed"),
                GsonHelper.getAsDouble(json, "swim_speed", 0),
                json.has("flight") ? flight(GsonHelper.getAsJsonObject(json, "flight")) : null,
                json.has("ground_gait") ? new DigimonGait(
                        GsonHelper.getAsFloat(json.getAsJsonObject("ground_gait"), "cycle_ticks"),
                        GsonHelper.getAsDouble(json.getAsJsonObject("ground_gait"), "stride"),
                        GsonHelper.getAsFloat(json.getAsJsonObject("ground_gait"), "max_playback_rate", Float.MAX_VALUE)) : null);
    }

    private static DigimonFlight flight(JsonObject json) {
        return new DigimonFlight(GsonHelper.getAsDouble(json, "speed"),
                GsonHelper.getAsInt(json, "capacity_ticks"), GsonHelper.getAsInt(json, "recharge_ticks"),
                GsonHelper.getAsInt(json, "rest_ticks"), GsonHelper.getAsDouble(json, "restart_fraction"),
                GsonHelper.getAsInt(json, "landing_reserve_ticks"), GsonHelper.getAsInt(json, "minimum_flight_ticks"),
                GsonHelper.getAsDouble(json, "start_distance"), GsonHelper.getAsDouble(json, "stop_distance"),
                GsonHelper.getAsDouble(json, "cruise_height"), GsonHelper.getAsFloat(json, "clearance_width"),
                GsonHelper.getAsFloat(json, "clearance_height"));
    }

    private static DigimonBody body(JsonObject json) {
        float width = GsonHelper.getAsFloat(json, "width");
        float height = GsonHelper.getAsFloat(json, "height");
        float eye = GsonHelper.getAsFloat(json, "eye_height");
        if (!Float.isFinite(width) || width <= 0 || !Float.isFinite(height) || height <= 0
                || !Float.isFinite(eye) || eye < 0 || eye > height) {
            throw new IllegalArgumentException("Invalid species dimensions");
        }
        Optional<DigimonBody.Mount> mount = Optional.empty();
        if (json.has("mount")) {
            JsonObject m = GsonHelper.getAsJsonObject(json, "mount");
            JsonArray seat = GsonHelper.getAsJsonArray(m, "seat");
            if (seat.size() != 3) throw new IllegalArgumentException("A mount seat needs three coordinates");
            mount = Optional.of(new DigimonBody.Mount(new Vec3(seat.get(0).getAsDouble(),
                    seat.get(1).getAsDouble(), seat.get(2).getAsDouble()),
                    GsonHelper.getAsFloat(m, "speed"), GsonHelper.getAsFloat(m, "step_height"),
                    GsonHelper.getAsBoolean(m, "standing", false),
                    m.has("water_seat_offset") ? vector(GsonHelper.getAsJsonArray(m, "water_seat_offset")) : Vec3.ZERO,
                    m.has("flight") ? aerialMount(GsonHelper.getAsJsonObject(m, "flight")) : null));
        }
        return new DigimonBody(GsonHelper.getAsFloat(json, "model_scale"),
                EntityDimensions.scalable(width, height).withEyeHeight(eye), mount);
    }

    private static AerialMount aerialMount(JsonObject j) {
        return new AerialMount(GsonHelper.getAsDouble(j,"cruise_speed"),
                GsonHelper.getAsDouble(j,"acceleration"), GsonHelper.getAsDouble(j,"braking"),
                GsonHelper.getAsDouble(j,"climb_speed"), GsonHelper.getAsDouble(j,"descend_speed"),
                GsonHelper.getAsFloat(j,"turn_degrees"), GsonHelper.getAsInt(j,"takeoff_ticks"),
                GsonHelper.getAsInt(j,"lift_tick"), GsonHelper.getAsInt(j,"landing_ticks"),
                GsonHelper.getAsInt(j,"wing_loop_ticks"),
                j.has("dive") ? aerialDive(GsonHelper.getAsJsonObject(j,"dive")) : null);
    }

    private static AerialMount.Dive aerialDive(JsonObject j) {
        return new AerialMount.Dive(GsonHelper.getAsDouble(j, "max_speed"),
                GsonHelper.getAsDouble(j, "acceleration"), GsonHelper.getAsDouble(j, "drag"),
                GsonHelper.getAsDouble(j, "climb_drag"), GsonHelper.getAsDouble(j, "turn_drag"));
    }

    private static Identifier identifier(String value) {
        return value.contains(":") ? Identifier.parse(value) : Constants.id(value);
    }

    private static Vec3 vector(JsonArray coordinates) {
        if (coordinates.size() != 3) throw new IllegalArgumentException("A mount offset needs three coordinates");
        return new Vec3(coordinates.get(0).getAsDouble(), coordinates.get(1).getAsDouble(), coordinates.get(2).getAsDouble());
    }

    private static JsonObject read(String path) {
        try (var input = BundledSpeciesLoader.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing bundled species resource " + path);
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled species resource " + path, e);
        }
    }
}
