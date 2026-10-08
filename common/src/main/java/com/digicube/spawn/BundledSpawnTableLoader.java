package com.digicube.spawn;

import com.digicube.Constants;
import com.digicube.digimon.DigimonStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Loads the bundled wild spawn tables identically on client and server at startup, the
 * way {@link com.digicube.digimon.BundledSpeciesLoader} loads species: a catalog names
 * the tables, and each table is one JSON file for one dimension. Bad content fails
 * loudly at startup rather than quietly at the first spawn. Datapack reload is future
 * work, together with species.
 */
public final class BundledSpawnTableLoader {

    private static final String CATALOG = "/data/" + Constants.MOD_ID + "/spawn_tables.json";
    private static final String TABLE_FOLDER = "/data/" + Constants.MOD_ID + "/spawn_table/";

    private BundledSpawnTableLoader() {}

    /**
     * Parse and validate every table in the catalog.
     * @param knownSpecies tells whether a species id is registered
     * @return tables in catalog order, one per dimension
     */
    public static List<SpawnTable> load(Predicate<Identifier> knownSpecies) {
        var tables = new LinkedHashMap<ResourceKey<Level>, SpawnTable>();
        for (var element : GsonHelper.getAsJsonArray(read(CATALOG), "tables")) {
            String name = GsonHelper.convertToString(element, "spawn table name");
            SpawnTable table = parse(name, read(TABLE_FOLDER + name + ".json"), knownSpecies);
            if (tables.putIfAbsent(table.dimension(), table) != null) {
                throw new IllegalArgumentException(name + ": " + table.dimension().identifier() + " already has a spawn table");
            }
        }
        return List.copyOf(tables.values());
    }

    /**
     * Decode one table. Unqualified species names use the mod namespace; unqualified
     * biome names are vanilla biomes. {@code regions} and {@code bands} are optional:
     * without them every spot is calm.
     * @param name         table name, used in error messages
     * @param json         table data
     * @param knownSpecies tells whether a species id is registered
     * @return validated immutable table
     */
    public static SpawnTable parse(String name, JsonObject json, Predicate<Identifier> knownSpecies) {
        Identifier dimension = Identifier.tryParse(GsonHelper.getAsString(json, "dimension"));
        if (dimension == null) throw new IllegalArgumentException(name + ": invalid dimension id");
        try {
            var regions = new ArrayList<SpawnRegion>();
            if (json.has("regions")) {
                for (var element : GsonHelper.getAsJsonArray(json, "regions")) regions.add(region(GsonHelper.convertToJsonObject(element, "region")));
            }
            var bands = new EnumMap<SpawnDanger, Map<DigimonStage, Integer>>(SpawnDanger.class);
            if (json.has("bands")) {
                for (var band : GsonHelper.getAsJsonObject(json, "bands").entrySet()) {
                    var levels = new EnumMap<DigimonStage, Integer>(DigimonStage.class);
                    for (var level : GsonHelper.convertToJsonObject(band.getValue(), "band").entrySet()) {
                        levels.put(DigimonStage.byId(level.getKey()), GsonHelper.convertToInt(level.getValue(), "band level"));
                    }
                    bands.put(SpawnDanger.byId(band.getKey()), levels);
                }
            }
            var entries = new ArrayList<SpawnEntry>();
            for (var element : GsonHelper.getAsJsonArray(json, "entries")) {
                entries.add(entry(name, GsonHelper.convertToJsonObject(element, "spawn entry"), knownSpecies));
            }
            return new SpawnTable(ResourceKey.create(Registries.DIMENSION, dimension), entries, regions, bands);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
        }
    }

    private static SpawnRegion region(JsonObject json) {
        var biomes = new ArrayList<Identifier>();
        for (var element : GsonHelper.getAsJsonArray(json, "biomes")) {
            String reference = GsonHelper.convertToString(element, "biome");
            Identifier biome = Identifier.tryParse(reference);
            if (biome == null || biome.getPath().isEmpty() || reference.startsWith("#")) {
                throw new IllegalArgumentException("A region lists biome ids, not " + reference);
            }
            biomes.add(biome);
        }
        return new SpawnRegion(GsonHelper.getAsString(json, "id"), SpawnDanger.byId(GsonHelper.getAsString(json, "danger")), biomes);
    }

    private static SpawnEntry entry(String table, JsonObject json, Predicate<Identifier> knownSpecies) {
        try {
            Identifier species = speciesId(GsonHelper.getAsString(json, "species"));
            if (!knownSpecies.test(species)) throw new IllegalArgumentException("unknown species " + species);
            int[] level = range(json, "level");
            int[] pack = json.has("pack") ? range(json, "pack") : new int[]{1, 1};
            var biomes = new ArrayList<BiomeFilter>();
            if (json.has("biomes")) {
                for (var element : GsonHelper.getAsJsonArray(json, "biomes")) {
                    biomes.add(BiomeFilter.parse(GsonHelper.convertToString(element, "biome")));
                }
            }
            var regions = new ArrayList<String>();
            if (json.has("regions")) {
                for (var element : GsonHelper.getAsJsonArray(json, "regions")) regions.add(GsonHelper.convertToString(element, "region"));
            }
            return new SpawnEntry(species, GsonHelper.getAsInt(json, "weight"), level[0], level[1], pack[0], pack[1], biomes, regions,
                    SpawnPlacement.byId(GsonHelper.getAsString(json, "placement", SpawnPlacement.LAND.getId())),
                    SpawnTime.byId(GsonHelper.getAsString(json, "time", SpawnTime.ANY.getId())));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(table + ": " + e.getMessage(), e);
        }
    }

    private static int[] range(JsonObject json, String key) {
        JsonArray array = GsonHelper.getAsJsonArray(json, key);
        if (array.size() != 2) throw new IllegalArgumentException(key + " must be [min, max]");
        return new int[]{GsonHelper.convertToInt(array.get(0), key), GsonHelper.convertToInt(array.get(1), key)};
    }

    private static Identifier speciesId(String value) {
        return value.contains(":") ? Identifier.parse(value) : Constants.id(value);
    }

    private static JsonObject read(String path) {
        try (var input = BundledSpawnTableLoader.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing bundled spawn table resource " + path);
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled spawn table resource " + path, e);
        }
    }
}
