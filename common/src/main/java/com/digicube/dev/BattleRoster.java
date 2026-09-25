package com.digicube.dev;

import com.digicube.digimon.Progression;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.List;

/**
 * One side of a Battle Testing fight as the panel sends it: up to {@link #MAX_KINDS} kinds, each a species, a level
 * and how many of it. Both ends read and write the same tags, and the server clamps whatever arrives, so a side is
 * never empty of counts nor larger than {@link #MAX_SIDE} bodies. Also the formation the side is staged in.
 */
public final class BattleRoster {
    public static final String SPECIES = "species", LEVEL = "level", COUNT = "count";
    public static final int MAX_KINDS = 4, MAX_COUNT = 20, MAX_SIDE = 40;
    /** Fighters stand in ranks of this many, parallel to the line between the sides. */
    public static final int RANK = 5;

    private BattleRoster() {}

    public record Entry(String species, int level, int count) {
        public Entry {
            level = Progression.clampLevel(level);
            count = Math.clamp(count, 1, MAX_COUNT);
        }
    }

    public static ListTag write(List<Entry> side) {
        ListTag list = new ListTag();
        for (Entry entry : side) {
            CompoundTag tag = new CompoundTag();
            tag.putString(SPECIES, entry.species());
            tag.putInt(LEVEL, entry.level());
            tag.putInt(COUNT, entry.count());
            list.add(tag);
        }
        return list;
    }

    /** The side in {@code list}, clamped: the first {@link #MAX_KINDS} kinds, counts cut so the side stays within {@link #MAX_SIDE}. */
    public static List<Entry> read(ListTag list) {
        List<Entry> side = new ArrayList<>();
        int room = MAX_SIDE;
        for (int i = 0; i < list.size() && side.size() < MAX_KINDS && room > 0; i++) {
            CompoundTag tag = list.getCompoundOrEmpty(i);
            Entry entry = new Entry(tag.getStringOr(SPECIES, ""), tag.getIntOr(LEVEL, Progression.MIN_LEVEL), tag.getIntOr(COUNT, 1));
            entry = new Entry(entry.species(), entry.level(), Math.min(entry.count(), room));
            room -= entry.count();
            side.add(entry);
        }
        return side;
    }

    public static int total(List<Entry> side) {
        return side.stream().mapToInt(Entry::count).sum();
    }

    /**
     * Where fighter {@code index} of {@code count} stands: {@code [across, along]} in blocks, across measured outward
     * from the line between the sides (the front rank at {@code front}), along measured along that line, each rank
     * centred on it. Neighbours are {@code spacing} apart both ways.
     */
    public static double[] place(int index, int count, double front, double spacing) {
        int rank = index / RANK, inRank = Math.min(RANK, count - rank * RANK), slot = index % RANK;
        return new double[]{front + rank * spacing, (slot - (inRank - 1) / 2.0) * spacing};
    }
}
