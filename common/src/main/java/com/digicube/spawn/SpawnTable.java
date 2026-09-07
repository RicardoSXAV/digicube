package com.digicube.spawn;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Every wild spawn entry of one dimension. Pure data plus the weighted pick, so the
 * spawner only supplies the spot and the random source.
 *
 * @param dimension the dimension this table applies to
 * @param entries   at least one entry
 */
public record SpawnTable(ResourceKey<Level> dimension, List<SpawnEntry> entries) {

    public SpawnTable {
        Objects.requireNonNull(dimension, "dimension");
        if (entries.isEmpty()) throw new IllegalArgumentException(dimension.identifier() + ": a spawn table needs at least one entry");
        entries = List.copyOf(entries);
    }

    /** Entries that accept a spot, in table order. */
    public List<SpawnEntry> candidates(SpawnPlacement placement, boolean bright, boolean dark,
                                       Predicate<Identifier> biomeIds, Predicate<TagKey<Biome>> biomeTags) {
        List<SpawnEntry> candidates = new ArrayList<>();
        for (SpawnEntry entry : entries) {
            if (entry.matches(placement, bright, dark, biomeIds, biomeTags)) candidates.add(entry);
        }
        return candidates;
    }

    /** Weighted pick among {@code candidates}, or null when there is nothing to choose from. */
    public static SpawnEntry pick(RandomSource random, List<SpawnEntry> candidates) {
        int total = 0;
        for (SpawnEntry entry : candidates) total += entry.weight();
        if (total <= 0) return null;
        int roll = random.nextInt(total);
        for (SpawnEntry entry : candidates) {
            roll -= entry.weight();
            if (roll < 0) return entry;
        }
        return candidates.getLast();
    }
}
