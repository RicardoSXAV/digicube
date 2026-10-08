package com.digicube.spawn;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a group of species lives in the wild, read from the spawn tables: the regions their entries name, each with its
 * danger and the levels the group spawns at there (an entry's range plus its region's band). The SCAN page says where a
 * family lives with it. Entries that name biomes instead of regions do not appear: only regions have names to show.
 */
public final class SpawnHabitat {

    /**
     * One region the group lives in.
     * @param region   the region
     * @param minLevel the lowest level any of the group spawns at there
     * @param maxLevel the highest
     */
    public record Place(SpawnRegion region, int minLevel, int maxLevel) {}

    private SpawnHabitat() {}

    /** The places of {@code species} across {@code tables}, calm land first, then wild, then dangerous, each in table order. */
    public static List<Place> of(Collection<SpawnTable> tables, Collection<Identifier> species) {
        Map<SpawnRegion, int[]> levels = new LinkedHashMap<>();
        for (SpawnTable table : tables) {
            for (SpawnEntry entry : table.entries()) {
                if (!species.contains(entry.species())) continue;
                DigimonSpecies sheet = DigimonSpeciesRegistry.get(entry.species()).orElse(null);
                if (sheet == null) continue;
                for (String id : entry.regions()) {
                    SpawnRegion region = table.region(id);
                    if (region == null) continue;
                    int bonus = table.levelBonus(region.danger(), sheet.stage());
                    int[] range = levels.computeIfAbsent(region, ignored -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
                    range[0] = Math.min(range[0], entry.minLevel() + bonus);
                    range[1] = Math.max(range[1], entry.maxLevel() + bonus);
                }
            }
        }
        List<Place> places = new ArrayList<>();
        levels.forEach((region, range) -> places.add(new Place(region, range[0], range[1])));
        places.sort(Comparator.comparingInt(place -> place.region().danger().ordinal()));
        return places;
    }

    /** The places of {@code species} in every registered table. */
    public static List<Place> of(Collection<Identifier> species) {
        return of(SpawnTables.all(), species);
    }
}
