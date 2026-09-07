package com.digicube.digimon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who hurt a wild Digimon, by how much, and when they last did.
 *
 * <p>The ledger lives on the victim, so a fight costs one small map that is freed with
 * the entity, and attackers never hold references to wild Digimon. Callers record the
 * health actually lost after armour and immunity frames, never the raw hit, so
 * mitigation and overkill cannot inflate a share. The ledger is transient: it is not
 * saved, and a reload mid-fight starts it empty.
 */
public final class DamageLedger {

    /**
     * One attacker's running total.
     * @param attacker    entity id of the attacker
     * @param damage      health the victim lost to it so far
     * @param lastHitTick game time of its most recent hit
     */
    public record Entry(UUID attacker, float damage, long lastHitTick) {}

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /** Adds {@code damage} to the attacker's total; zero, negative or NaN damage is ignored. */
    public void record(UUID attacker, float damage, long tick) {
        if (!(damage > 0)) return;
        Entry previous = entries.get(attacker);
        entries.put(attacker, previous == null ? new Entry(attacker, damage, tick)
                : new Entry(attacker, previous.damage() + damage, tick));
    }

    /** Entries whose last hit is at most {@code memoryTicks} before {@code now}, oldest first. */
    public List<Entry> recent(long now, int memoryTicks) {
        List<Entry> recent = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (now - entry.lastHitTick() <= memoryTicks) recent.add(entry);
        }
        return recent;
    }

    /** Every entry, oldest first. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }
}
