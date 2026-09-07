package com.digicube.spawn;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every wild {@link SpawnTable}, keyed by dimension. Like the species registry this is a
 * plain map populated once at startup from bundled data and read-only afterwards.
 */
public final class SpawnTables {

    private static final Map<ResourceKey<Level>, SpawnTable> TABLES = new LinkedHashMap<>();

    private SpawnTables() {}

    /** Loads the bundled tables. Call after the species registry is populated. */
    public static void registerBuiltIn() {
        BundledSpawnTableLoader.load(id -> DigimonSpeciesRegistry.get(id).isPresent()).forEach(SpawnTables::register);
        Constants.LOG.info("Registered {} wild spawn tables.", TABLES.size());
    }

    public static void register(SpawnTable table) {
        SpawnTable previous = TABLES.put(table.dimension(), table);
        if (previous != null) {
            Constants.LOG.warn("Spawn table for {} was registered twice; the later definition wins.", table.dimension().identifier());
        }
    }

    public static Optional<SpawnTable> get(ResourceKey<Level> dimension) {
        return Optional.ofNullable(TABLES.get(dimension));
    }

    public static Collection<SpawnTable> all() {
        return Collections.unmodifiableCollection(TABLES.values());
    }

    public static int size() {
        return TABLES.size();
    }

    /** Wipes the registry. For a future reload; not for gameplay code. */
    public static void clear() {
        TABLES.clear();
    }
}
