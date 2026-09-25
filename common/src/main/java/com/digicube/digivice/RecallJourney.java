package com.digicube.digivice;

/**
 * One clock for server cooldowns, client flight and the external review. No distant chunk simulation.
 * The device lands in the hand at {@link #arriveAt()}: the cooldown ends there and the inventory shows it from
 * then on. {@code settle} is the hand absorbing the catch, cosmetic only.
 */
public record RecallJourney(boolean nearby, float departure, float lift, float flight, float settle) {
    /** The smallest real-flight range, the least view distance (two chunks). */
    public static final double MIN_FLY_RANGE = 32;
    /**
     * Within {@code flyRange} (the receiving player's view distance in blocks: whatever they can see, a lake below a
     * hill, a valley) the device really flies from where it lies; beyond it, it comes in from its bearing.
     */
    public static RecallJourney plan(double distance, boolean sameDimension, double flyRange) {
        double d = Math.max(0, distance), range = Math.max(MIN_FLY_RANGE, flyRange);
        // Unhurried on purpose: the route and its shooting-star trail are the show. A long flight holds about 45
        // blocks a second, so the star stays readable across a whole valley.
        if (sameDimension && d <= range) return new RecallJourney(true, .30F, .62F,
                (float)Math.max(1.25 + Math.sqrt(d / 32), d / 45), .34F);
        float wait = sameDimension ? .8F + 3.2F * (float)Math.clamp(
                Math.log(Math.max(range, d) / range) / Math.log(100000D / range), 0, 1) : 2;
        return new RecallJourney(false, wait, 0, 2.3F, .34F);
    }
    public float flightAt() { return departure + lift; }
    public float arriveAt() { return flightAt() + flight; }
    public float duration() { return arriveAt() + settle; }
    public int ticks() { return (int)Math.ceil(arriveAt() * 20); }
}
