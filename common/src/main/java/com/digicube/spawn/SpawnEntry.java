package com.digicube.spawn;

import com.digicube.digimon.Progression;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One line of a spawn table: which species appears where, how often, at what level and
 * in what numbers. Immutable and validated on construction, so a bad table fails at
 * startup rather than at the first spawn.
 *
 * @param species   species id; the loader checks it exists
 * @param weight    relative weight among the entries that pass the filters at a spot
 * @param minLevel  inclusive lower level bound in calm land; a wilder region's band adds to both bounds
 * @param maxLevel  inclusive upper level bound in calm land
 * @param minPack   inclusive smallest group size
 * @param maxPack   inclusive largest group size
 * @param biomes    accepted biomes; with no regions either, every biome
 * @param regions   accepted regions of the table ({@link SpawnRegion}), by id
 * @param placement land or water
 * @param time      any, day or night
 */
public record SpawnEntry(Identifier species, int weight, int minLevel, int maxLevel, int minPack, int maxPack,
                         List<BiomeFilter> biomes, List<String> regions, SpawnPlacement placement, SpawnTime time) {

    /** Largest group a single entry may spawn; keeps a typo from flooding a chunk. */
    public static final int MAX_PACK = 8;

    public SpawnEntry {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(time, "time");
        if (weight <= 0) throw new IllegalArgumentException(species + ": weight must be positive");
        if (minLevel < Progression.MIN_LEVEL || maxLevel > Progression.LEVEL_CAP || minLevel > maxLevel) {
            throw new IllegalArgumentException(species + ": level range must lie within "
                    + Progression.MIN_LEVEL + ".." + Progression.LEVEL_CAP + " and not be inverted");
        }
        if (minPack < 1 || maxPack > MAX_PACK || minPack > maxPack) {
            throw new IllegalArgumentException(species + ": pack range must lie within 1.." + MAX_PACK + " and not be inverted");
        }
        biomes = List.copyOf(biomes);
        regions = List.copyOf(regions);
    }

    /** An entry filtered by biomes alone. */
    public SpawnEntry(Identifier species, int weight, int minLevel, int maxLevel, int minPack, int maxPack,
                      List<BiomeFilter> biomes, SpawnPlacement placement, SpawnTime time) {
        this(species, weight, minLevel, maxLevel, minPack, maxPack, biomes, List.of(), placement, time);
    }

    /** Whether this entry may spawn at a spot in no region. */
    public boolean matches(SpawnPlacement placement, boolean bright, boolean dark,
                           Predicate<Identifier> biomeIds, Predicate<TagKey<Biome>> biomeTags) {
        return matches(placement, bright, dark, biomeIds, biomeTags, null);
    }

    /**
     * Whether this entry may spawn at a spot.
     * @param placement what the spot offers, land or water
     * @param bright    the level reports daylight
     * @param dark      the level reports night
     * @param biomeIds  tests a biome id against the spot's biome
     * @param biomeTags tests a biome tag against the spot's biome
     * @param region    id of the region the spot's biome belongs to, or null for none
     */
    public boolean matches(SpawnPlacement placement, boolean bright, boolean dark,
                           Predicate<Identifier> biomeIds, Predicate<TagKey<Biome>> biomeTags, String region) {
        if (this.placement != placement || !time.matches(bright, dark)) return false;
        if (biomes.isEmpty() && regions.isEmpty()) return true;
        if (region != null && regions.contains(region)) return true;
        for (BiomeFilter filter : biomes) {
            if (filter.matches(biomeIds, biomeTags)) return true;
        }
        return false;
    }

    public int rollLevel(RandomSource random) {
        return random.nextIntBetweenInclusive(minLevel, maxLevel);
    }

    public int rollPack(RandomSource random) {
        return random.nextIntBetweenInclusive(minPack, maxPack);
    }
}
