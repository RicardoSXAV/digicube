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
                evolutions, moves, json.has("body") ? body(GsonHelper.getAsJsonObject(json, "body")) : DigimonBody.DEFAULT);
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
                    GsonHelper.getAsFloat(m, "speed"), GsonHelper.getAsFloat(m, "step_height")));
        }
        return new DigimonBody(GsonHelper.getAsFloat(json, "model_scale"),
                EntityDimensions.scalable(width, height).withEyeHeight(eye), mount);
    }

    private static Identifier identifier(String value) {
        return value.contains(":") ? Identifier.parse(value) : Constants.id(value);
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
