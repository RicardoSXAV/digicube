package com.digicube.digimon;

/** Shared frost-combo tuning and context-based choices; independent of species order. */
public final class IceCombo {
    public static final int MARK_TICKS = 160;
    public static final int FREEZE_TICKS = 60;
    /** Seven seconds from freeze onset, retained even when a bite shatters the ice early. */
    public static final int RESISTANCE_TICKS = 140;
    public static final int CONTACT_TICKS = 20;
    public static final int FUEL_MARGIN_TICKS = 8;
    public static final float SHATTER_MULTIPLIER = 1.5F;

    private IceCombo() {}

    public enum Choice { BITE, BREATH, APPROACH }

    /** One second of landed flame; the tank size no longer dictates combo timing. */
    public static int requiredContactTicks(AttackFuel fuel) {
        return Math.min(CONTACT_TICKS, fuel.capacityTicks());
    }

    /** Leave room for flame travel and a few missed frames before committing a partial tank. */
    public static int comboFuelTicks(AttackFuel fuel) {
        return Math.min(fuel.capacityTicks(), requiredContactTicks(fuel) + FUEL_MARGIN_TICKS);
    }

    public static float biteMultiplier(boolean frozen) { return frozen ? SHATTER_MULTIPLIER : 1; }

    /**
     * Mark reachable prey, convert the mark, then bite the frozen victim. Bite through
     * resistance while fuel refills; reserve unmarked ranged damage for inaccessible prey.
     */
    public static Choice choose(boolean biteReady, boolean biteInRange, boolean breathReady,
                                boolean breathInRange, boolean marked, boolean frozen, boolean resistant,
                                boolean enoughFuel, boolean canApproachBite) {
        if (frozen) {
            if (biteReady && biteInRange) return Choice.BITE;
            return Choice.APPROACH;
        }
        if (marked && !resistant && enoughFuel && breathReady) {
            if (breathInRange) return Choice.BREATH;
            // Defend against close pressure or blocked flame instead of waiting
            // indefinitely for a perfect combo. A valid breath still wins next time.
            return biteReady && biteInRange ? Choice.BITE : Choice.APPROACH;
        }
        // Bite during resistance and while refilling. Don't spend the combo reserve
        // on an unmarked close target during the short bite cooldown.
        if (biteInRange) return biteReady ? Choice.BITE : Choice.APPROACH;
        if (canApproachBite) return Choice.APPROACH;
        return breathReady && breathInRange && enoughFuel ? Choice.BREATH : Choice.APPROACH;
    }
}
