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
    /** The species' flight costs. */
    public DigimonFlight.Costs costs() { return definition.costs(); }
    /** Spend one active server tick without allowing negative charge. */
    public void consume() { consume(1); }
    /** Spend {@code rate} ticks of plain flight this tick (a boost or a climb more, a glide less). */
    public void consume(double rate) { charge = Math.max(0, charge - Math.max(0, rate)); }
    /** Spend a one-off cost: {@code ticks} of the reserve at once (a barrel roll, a takeoff). */
    public void spend(double ticks) { consume(ticks); }
    /** Spend an attack cast on the wing: the costs' {@code attack} share of the whole reserve. */
    public void spendAttack() { consume(definition.costs().attack() * definition.capacityTicks()); }
    /** Begin mandatory recovery after ending a flight. */
    public void landed() { restRemaining = definition.restTicks(); }
    /** Advance one eligible grounded tick of recovery. */
    public void rest() { rest(1); }
    /** Advance one grounded tick of recovery at {@code rate} of the usual refill (slower while fighting). */
    public void rest(double rate) {
        if (restRemaining > 0) restRemaining--;
        charge = Math.min(definition.capacityTicks(), charge + rate * definition.capacityTicks() / definition.rechargeTicks());
    }
    /** @param savedCharge stored remaining charge; nonfinite data becomes empty
     * @param savedRest stored mandatory rest, clamped to the species definition */
    public void restore(double savedCharge, int savedRest) {
        charge = Double.isFinite(savedCharge) ? Math.clamp(savedCharge, 0, definition.capacityTicks()) : 0;
        restRemaining = Math.clamp(savedRest, 0, definition.restTicks());
    }
}
