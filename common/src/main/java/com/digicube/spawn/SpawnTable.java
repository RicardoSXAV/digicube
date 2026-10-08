package com.digicube.spawn;

import com.digicube.digimon.DigimonStage;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Every wild spawn entry of one dimension, the regions its land is divided into and the level bands of their dangers.
 * Pure data plus the weighted pick, so the spawner only supplies the spot and the random source.
 *
 * @param dimension the dimension this table applies to
 * @param entries   at least one entry
 * @param regions   named kinds of land; a biome in none of them is calm
 * @param bands     levels added to an entry's range by the danger of the land, per stage; a missing stage adds none
 */
public record SpawnTable(ResourceKey<Level> dimension, List<SpawnEntry> entries, List<SpawnRegion> regions,
                         Map<SpawnDanger, Map<DigimonStage, Integer>> bands) {

    public SpawnTable {
        Objects.requireNonNull(dimension, "dimension");
        if (entries.isEmpty()) throw new IllegalArgumentException(dimension.identifier() + ": a spawn table needs at least one entry");
        entries = List.copyOf(entries);
        regions = List.copyOf(regions);
        Map<String, SpawnRegion> byId = new HashMap<>();
        Map<Identifier, SpawnRegion> byBiome = new HashMap<>();
        for (SpawnRegion region : regions) {
            if (byId.put(region.id(), region) != null) throw new IllegalArgumentException(dimension.identifier() + ": region " + region.id() + " is listed twice");
            for (Identifier biome : region.biomes()) {
                SpawnRegion other = byBiome.put(biome, region);
                if (other != null) throw new IllegalArgumentException(biome + " is in both " + other.id() + " and " + region.id());
            }
        }
        for (SpawnEntry entry : entries) {
            for (String region : entry.regions()) {
                if (!byId.containsKey(region)) throw new IllegalArgumentException(entry.species() + ": unknown region " + region);
            }
        }
        Map<SpawnDanger, Map<DigimonStage, Integer>> copy = new EnumMap<>(SpawnDanger.class);
        bands.forEach((danger, levels) -> {
            levels.forEach((stage, bonus) -> {
                if (bonus < 0) throw new IllegalArgumentException(danger.getId() + " " + stage.getId() + ": a band never lowers a level");
            });
            copy.put(danger, Map.copyOf(levels));
        });
        bands = Map.copyOf(copy);
    }

    /** A table without regions or bands: every spot is calm. */
    public SpawnTable(ResourceKey<Level> dimension, List<SpawnEntry> entries) {
        this(dimension, entries, List.of(), Map.of());
    }

    /** The region {@code biome} belongs to, or null when it is in none. */
    public SpawnRegion region(Identifier biome) {
        for (SpawnRegion region : regions) {
            if (region.biomes().contains(biome)) return region;
        }
        return null;
    }

    /** The region called {@code id}, or null. */
    public SpawnRegion region(String id) {
        for (SpawnRegion region : regions) {
            if (region.id().equals(id)) return region;
        }
        return null;
    }

    /** The danger of the land {@code biome} is: its region's, or calm. */
    public SpawnDanger danger(Identifier biome) {
        SpawnRegion region = region(biome);
        return region == null ? SpawnDanger.CALM : region.danger();
    }

    /** Levels a wild Digimon of {@code stage} gains on land of {@code danger}, above its entry's own range. */
    public int levelBonus(SpawnDanger danger, DigimonStage stage) {
        return bands.getOrDefault(danger, Map.of()).getOrDefault(stage, 0);
    }

    /** Entries that accept a spot in no region, in table order. */
    public List<SpawnEntry> candidates(SpawnPlacement placement, boolean bright, boolean dark,
                                       Predicate<Identifier> biomeIds, Predicate<TagKey<Biome>> biomeTags) {
        return candidates(placement, bright, dark, biomeIds, biomeTags, null);
    }

    /** Entries that accept a spot, in table order; {@code region} is the id of the spot's region, or null. */
    public List<SpawnEntry> candidates(SpawnPlacement placement, boolean bright, boolean dark,
                                       Predicate<Identifier> biomeIds, Predicate<TagKey<Biome>> biomeTags, String region) {
        List<SpawnEntry> candidates = new ArrayList<>();
        for (SpawnEntry entry : entries) {
            if (entry.matches(placement, bright, dark, biomeIds, biomeTags, region)) candidates.add(entry);
        }
        return candidates;
    }

    /** Weighted pick among {@code candidates}, or null when there is nothing to choose from. */
    public static SpawnEntry pick(RandomSource random, List<SpawnEntry> candidates) {
        return pick(random, candidates, Map.of());
    }

    /**
     * Weighted pick among {@code candidates} at a spot where {@code crowd} counts the wild Digimon of each species
     * already near: each one there divides its species' weight ({@link #crowdedWeight}), so the land fills with a mix
     * instead of the commonest kind. Null when there is nothing to choose from.
     */
    public static SpawnEntry pick(RandomSource random, List<SpawnEntry> candidates, Map<Identifier, Integer> crowd) {
        int total = 0;
        for (SpawnEntry entry : candidates) total += crowdedWeight(entry, crowd);
        if (total <= 0) return null;
        int roll = random.nextInt(total);
        for (SpawnEntry entry : candidates) {
            roll -= crowdedWeight(entry, crowd);
            if (roll < 0) return entry;
        }
        return candidates.getLast();
    }

    /** An entry's weight with {@code n} of its species already near: {@code weight / (1 + n)}, never below 1. */
    public static int crowdedWeight(SpawnEntry entry, Map<Identifier, Integer> crowd) {
        return Math.max(1, entry.weight() / (1 + Math.max(0, crowd.getOrDefault(entry.species(), 0))));
    }
}
