package com.digicube.digimon;

import net.minecraft.nbt.CompoundTag;

import java.util.EnumMap;
import java.util.Set;

/**
 * How one individual has been raised in its current form, for CLASSIC's digivolution: the wild defeats it took part
 * in, by the foe's attribute and by where it fought ({@link RaisingPlace}). Server side only, never shown (the hints
 * are hidden). Saved with the evolution state; a growth into a Rookie starts it over, so the Baby II's fights decide
 * the Rookie and the Rookie's the Champion. Counters stop at {@link #MAX_COUNT}.
 */
public final class RaisingRecord {
    public static final int MAX_COUNT = 1_000_000;

    private int wins;
    private final EnumMap<DigimonAttribute, Integer> foes = new EnumMap<>(DigimonAttribute.class);
    private final EnumMap<RaisingPlace, Integer> places = new EnumMap<>(RaisingPlace.class);

    /** Wild defeats it took part in. */
    public int wins() { return wins; }

    /** Those of foes of {@code attribute}. */
    public int foes(DigimonAttribute attribute) { return foes.getOrDefault(attribute, 0); }

    /** Those where {@code place} applied. */
    public int places(RaisingPlace place) { return places.getOrDefault(place, 0); }

    /** A wild defeat it took part in, of a foe of {@code foe}, with the places that applied where it stood. */
    public void win(DigimonAttribute foe, Set<RaisingPlace> where) {
        wins = bump(wins);
        foes.merge(foe, 1, (a, b) -> bump(a));
        for (RaisingPlace place : where) places.merge(place, 1, (a, b) -> bump(a));
    }

    private static int bump(int count) {
        return Math.min(MAX_COUNT, count + 1);
    }

    /**
     * How well {@code raising} is met: wins over its foe plus wins in its place. Every route of one form is scored
     * over the same wins, so the sums compare as the shares would. 0 for a route without a raising.
     */
    public int score(RaisingCondition raising) {
        return raising == null ? 0 : foes(raising.foe()) + places(raising.place());
    }

    /** Starts over: the form it grew into has its own story. */
    public void clear() {
        wins = 0;
        foes.clear();
        places.clear();
    }

    public boolean isEmpty() { return wins == 0; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (wins == 0) return tag;
        tag.putInt("wins", wins);
        foes.forEach((attribute, count) -> tag.putInt("foe_" + attribute.getId(), count));
        places.forEach((place, count) -> tag.putInt("place_" + place.id(), count));
        return tag;
    }

    /** Fills this record from {@code tag}, as {@link #save} wrote it; unknown or bad entries are left out. */
    public void read(CompoundTag tag) {
        clear();
        wins = Math.clamp(tag.getIntOr("wins", 0), 0, MAX_COUNT);
        if (wins == 0) return;
        for (DigimonAttribute attribute : DigimonAttribute.values()) {
            int count = Math.clamp(tag.getIntOr("foe_" + attribute.getId(), 0), 0, wins);
            if (count > 0) foes.put(attribute, count);
        }
        for (RaisingPlace place : RaisingPlace.values()) {
            int count = Math.clamp(tag.getIntOr("place_" + place.id(), 0), 0, wins);
            if (count > 0) places.put(place, count);
        }
    }
}
