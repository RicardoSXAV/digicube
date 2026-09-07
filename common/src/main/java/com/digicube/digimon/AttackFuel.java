package com.digicube.digimon;

/**
 * Shared sustained-attack timing, in server ticks.
 * @param capacityTicks emission duration of a full tank
 * @param rechargeTicks time to refill an empty tank
 * @param damageIntervalTicks spacing between damage pulses, respecting vanilla hurt immunity
 */
public record AttackFuel(int capacityTicks, int rechargeTicks, int damageIntervalTicks) {
    /** Validate timing and the integer charge representation. */
    public AttackFuel {
        if (capacityTicks < 1 || rechargeTicks < 1 || damageIntervalTicks < 10
                || (long) capacityTicks * rechargeTicks > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid attack fuel timing");
        }
    }
}
