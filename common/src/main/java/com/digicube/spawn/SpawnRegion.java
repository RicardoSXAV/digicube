package com.digicube.spawn;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A named kind of land in one dimension's spawn table: the biomes it covers and how dangerous it is. A spawn entry may
 * name regions instead of biomes; the level band of a spot is its region's, and the SCAN page says where a family
 * lives by its regions' names ({@link #translationKey}).
 *
 * @param id     lowercase name, unique in its table, e.g. {@code badlands}
 * @param danger the level band wild Digimon spawn in here
 * @param biomes the biome ids it covers; a biome belongs to one region at most
 */
public record SpawnRegion(String id, SpawnDanger danger, List<Identifier> biomes) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    public SpawnRegion {
        Objects.requireNonNull(id, "region id");
        Objects.requireNonNull(danger, "region danger");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid region id: " + id);
        if (biomes.isEmpty()) throw new IllegalArgumentException(id + ": a region needs at least one biome");
        biomes = List.copyOf(biomes);
        if (biomes.stream().distinct().count() != biomes.size()) throw new IllegalArgumentException(id + ": a biome is listed twice");
    }

    /** Translation key of the region's name, e.g. {@code spawn_region.digicube.badlands}. */
    public String translationKey() {
        return "spawn_region.digicube." + id;
    }
}
