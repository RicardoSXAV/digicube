package com.digicube.digimon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-emission contact ledger. Misses, other victims and other casters cannot pay a freeze. */
public final class IceExposure {
    private record Contact(int ticks, int lastTick) {}
    private final Map<UUID, Contact> contacts = new HashMap<>();

    public void clear() { contacts.clear(); }

    /** One actual, unobstructed contact tick on a marked, susceptible victim. */
    public boolean touch(UUID target, int emissionTick, boolean marked, boolean resistant, int requiredTicks) {
        if (!marked || resistant) {
            contacts.remove(target);
            return false;
        }
        Contact previous = contacts.get(target);
        if (previous != null && previous.lastTick() == emissionTick) return false;
        int ticks = previous == null ? 1 : previous.ticks() + 1;
        contacts.put(target, new Contact(ticks, emissionTick));
        return ticks == requiredTicks;
    }
}
