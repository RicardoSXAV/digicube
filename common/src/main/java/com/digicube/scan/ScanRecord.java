package com.digicube.scan;

import com.digicube.digimon.Progression;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One tamer's scan: a bar of data per family toward its Digitama, which families the tamer has sighted, and the data
 * of their last {@link Progression#SCAN_PACE_DEFEATS} defeats for the pace the SCAN page reads out. Pure rules; the
 * server keeps one per player in {@link ScanSavedData}.
 */
public final class ScanRecord {
    /**
     * What one gain did to a bar.
     * @param added   data that went into the bar
     * @param lost    data past the bar's capacity, thrown away
     * @param readied whether the bar reached a Digitama's worth with this gain
     */
    public record Gain(int added, int lost, boolean readied) {
        public static final Gain NONE = new Gain(0, 0, false);
    }

    /** One defeat in the pace window: the family it fed and how much. */
    public record Defeat(Identifier family, int data) {}

    private final Map<Identifier, Integer> data = new LinkedHashMap<>();
    private final Set<Identifier> seen = new LinkedHashSet<>();
    private final Deque<Defeat> recent = new ArrayDeque<>();

    public ScanRecord() {}

    ScanRecord(Map<Identifier, Integer> data, Set<Identifier> seen, List<Defeat> recent) {
        data.forEach((family, amount) -> { if (amount > 0) this.data.put(family, Math.min(amount, Progression.SCAN_CAPACITY)); });
        this.seen.addAll(seen);
        for (Defeat defeat : recent) remember(defeat);
    }

    /** Data in {@code family}'s bar, 0 to {@link Progression#SCAN_CAPACITY}. */
    public int data(Identifier family) { return data.getOrDefault(family, 0); }
    public boolean seen(Identifier family) { return seen.contains(family); }
    /** Whether the bar holds a Digitama's worth: CONVERT is open. */
    public boolean ready(Identifier family) { return data(family) >= Progression.DIGITAMA_DATA; }

    /** The first sighting of {@code family} fills {@link Progression#FIRST_SIGHTING_DATA}; later ones nothing. */
    public Gain sight(Identifier family) {
        if (!seen.add(family)) return Gain.NONE;
        return add(family, Progression.FIRST_SIGHTING_DATA);
    }

    /** A defeat's data for {@code family}: into the bar and into the pace window. */
    public Gain defeat(Identifier family, int amount) {
        remember(new Defeat(family, Math.max(0, amount)));
        return add(family, amount);
    }

    private void remember(Defeat defeat) {
        recent.addLast(defeat);
        while (recent.size() > Progression.SCAN_PACE_DEFEATS) recent.removeFirst();
    }

    private Gain add(Identifier family, int amount) {
        if (amount <= 0) return Gain.NONE;
        int before = data(family), after = Math.min(Progression.SCAN_CAPACITY, before + amount);
        data.put(family, after);
        return new Gain(after - before, before + amount - after,
                before < Progression.DIGITAMA_DATA && after >= Progression.DIGITAMA_DATA);
    }

    /** Developer tooling and checks: puts {@code data} (clamped to the bar) in {@code family}'s bar, sighting it. */
    public void set(Identifier family, int data) {
        seen.add(family);
        this.data.put(family, Math.clamp(data, 0, Progression.SCAN_CAPACITY));
    }

    /** Spends a Digitama's worth of {@code family}'s data. @return false, spending nothing, when the bar holds less */
    public boolean convert(Identifier family) {
        if (!ready(family)) return false;
        data.put(family, data(family) - Progression.DIGITAMA_DATA);
        return true;
    }

    /**
     * Defeats still needed for {@code family}'s next Digitama at the recent pace: the bar's distance to a Digitama
     * over the family's mean data per defeat in the window (a defeat of another family counts as nothing for it).
     * @return 0 when the bar is ready, -1 when the family has had no data in the window
     */
    public int defeatsLeft(Identifier family) {
        int missing = Progression.DIGITAMA_DATA - data(family);
        if (missing <= 0) return 0;
        int sum = 0;
        for (Defeat defeat : recent) if (defeat.family().equals(family)) sum += defeat.data();
        if (sum <= 0) return -1;
        // ceil(missing / (sum / n)) in integers
        return (missing * recent.size() + sum - 1) / sum;
    }

    Map<Identifier, Integer> dataMap() { return Map.copyOf(data); }
    Set<Identifier> seenSet() { return Set.copyOf(seen); }
    List<Defeat> recentList() { return new ArrayList<>(recent); }
}
