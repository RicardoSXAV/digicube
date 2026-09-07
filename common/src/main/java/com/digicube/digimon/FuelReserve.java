package com.digicube.digimon;

/** An individual's fuel tank. An exhausted tank must refill completely before a new use. */
public final class FuelReserve {
    private final AttackFuel definition;
    private int charge;
    private boolean inUse;
    private boolean recharging;

    /** @param definition shared timing for this individual's initially full tank */
    public FuelReserve(AttackFuel definition) {
        this.definition = definition;
        charge = maximum();
    }

    private int maximum() { return definition.capacityTicks() * definition.rechargeTicks(); }

    /** @return whether emission can start, including the exhausted-tank lock */
    public boolean isReady() { return !inUse && !recharging && charge >= definition.rechargeTicks(); }

    /** @return whether a new use was started */
    public boolean begin() {
        if (!isReady()) return false;
        inUse = true;
        return true;
    }

    /**
     * Consume one tick of emission; recharge never runs during a use.
     * @return whether enough fuel was available
     */
    public boolean consume() {
        if (!inUse || charge < definition.rechargeTicks()) return false;
        charge -= definition.rechargeTicks();
        if (charge < definition.rechargeTicks()) recharging = true;
        return true;
    }

    /** Release the tank when emission finishes or is interrupted. */
    public void end() { inUse = false; }

    /** Advance one server tick of passive refill whenever the tank is not in use. */
    public void tickRecharge() {
        if (!inUse) {
            charge = (int) Math.min(maximum(), (long) charge + definition.capacityTicks());
            if (charge == maximum()) recharging = false;
        }
    }

    /** @return exact integer charge for persistence */
    public int savedCharge() { return charge; }
    /** @return whether exhaustion requires a full refill before the next use */
    public boolean isRecharging() { return recharging; }

    /**
     * Loading interrupts emission but preserves exactly how much fuel still needs refilling.
     * @param savedCharge serialized integer charge
     * @param savedRecharging serialized exhaustion lock
     */
    public void restore(int savedCharge, boolean savedRecharging) {
        charge = Math.clamp(savedCharge, 0, maximum());
        recharging = charge < definition.rechargeTicks() || savedRecharging && charge < maximum();
        inUse = false;
    }
}
