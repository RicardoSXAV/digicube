package com.digicube.digimon;

/**
 * Cold, what a chilling frost stream charges on its victim (Seadramon's Ice Blast): tuning shared by the stream, the
 * mark and the chilling wrap loop. Garurumon's frost builds the Freeze gauge instead ({@link FreezeMark}).
 */
public final class IceCombo {
    public static final int FUEL_MARGIN_TICKS = 8;
    /** A second of landed frost charges Cold. */
    public static final int COLD_CHARGE_TICKS = 20;
    /** Six seconds of slowed movement; further frost contact tops the timer back up. */
    public static final int COLD_TICKS = 120;
    public static final double COLD_SLOW = -.4;
    /** An unfed charge holds for two seconds, then drains a tick per tick. */
    public static final int COLD_DECAY_DELAY_TICKS = 40;
    /** Contact re-applies Cold only once this much of it has run down, not every tick. */
    public static final int COLD_REFRESH_STEP_TICKS = 10;

    private IceCombo() {}

    /** Fuel a chilling stream needs to finish a Cold charge, and keeps back from prey that is already Cold. */
    public static int chillFuelTicks(AttackFuel fuel) {
        return Math.min(fuel.capacityTicks(), COLD_CHARGE_TICKS + FUEL_MARGIN_TICKS);
    }
}
