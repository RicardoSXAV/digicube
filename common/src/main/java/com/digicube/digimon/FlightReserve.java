package com.digicube.digimon;

/** Per-individual stamina, separate from attack fuel. Only a deployed creature resting on land refills it. */
public final class FlightReserve {
    private final DigimonFlight definition;
    private double charge;
    private int restRemaining;

    /** @param definition shared timing for an initially full individual reserve */
    public FlightReserve(DigimonFlight definition) {
        this.definition = definition;
        charge = definition.capacityTicks();
    }

    /** @return whether enough fuel and grounded rest permit takeoff */
    public boolean ready() { return restRemaining == 0 && charge >= definition.capacityTicks() * definition.restartFraction(); }
    /** @return whether the remaining fuel is reserved for descent */
    public boolean mustLand() { return charge <= definition.landingReserveTicks(); }
    /** @return whether powered ascent/hover must cease */
    public boolean exhausted() { return charge <= 0; }
    /** @return exact remaining charge for persistence */
    public double charge() { return charge; }
    /** @return mandatory rest still owed */
    public int restRemaining() { return restRemaining; }
    /** @return bounded reserve fraction for the client meter */
    public float fraction() { return (float) (charge / definition.capacityTicks()); }
    /** Spend one active server tick without allowing negative charge. */
    public void consume() { charge = Math.max(0, charge - 1); }
    /** Begin mandatory recovery after ending a flight. */
    public void landed() { restRemaining = definition.restTicks(); }
    /** Advance one eligible grounded tick of recovery. */
    public void rest() {
        if (restRemaining > 0) restRemaining--;
        charge = Math.min(definition.capacityTicks(), charge + (double) definition.capacityTicks() / definition.rechargeTicks());
    }
    /** @param savedCharge stored remaining charge; nonfinite data becomes empty
     * @param savedRest stored mandatory rest, clamped to the species definition */
    public void restore(double savedCharge, int savedRest) {
        charge = Double.isFinite(savedCharge) ? Math.clamp(savedCharge, 0, definition.capacityTicks()) : 0;
        restRemaining = Math.clamp(savedRest, 0, definition.restTicks());
    }
}
