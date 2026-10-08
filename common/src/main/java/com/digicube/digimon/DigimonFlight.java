package com.digicube.digimon;

/**
 * Optional, species-defined burst flight. Speeds are blocks/tick; durations are server ticks.
 * @param speed maximum cruise velocity
 * @param capacityTicks full powered-flight capacity
 * @param rechargeTicks grounded ticks to refill from empty
 * @param restTicks mandatory rest after touchdown
 * @param restartFraction minimum fuel fraction for another takeoff
 * @param landingReserveTicks remaining charge that requests descent
 * @param minimumFlightTicks minimum cruise time before voluntary landing
 * @param startDistance owner distance that starts catch-up flight
 * @param stopDistance owner distance that permits landing
 * @param cruiseHeight desired altitude above the destination's feet
 * @param clearanceWidth collision and navigation width with open covers
 * @param clearanceHeight collision and navigation height with open covers
 * @param costs what flying hard and fighting on the wing take from the reserve ({@link Costs#STEADY} without)
 */
public record DigimonFlight(double speed, int capacityTicks, int rechargeTicks, int restTicks,
                            double restartFraction, int landingReserveTicks, int minimumFlightTicks,
                            double startDistance, double stopDistance, double cruiseHeight,
                            float clearanceWidth, float clearanceHeight, Costs costs) {
    public DigimonFlight {
        if (!Double.isFinite(speed) || speed <= 0 || speed > .8
                || capacityTicks < 120 || capacityTicks > 2400 || rechargeTicks < 20 || rechargeTicks > 12000
                || restTicks < 20 || restTicks > 1200 || minimumFlightTicks < 0
                || landingReserveTicks < 32 || minimumFlightTicks + landingReserveTicks + 32 >= capacityTicks
                || !Double.isFinite(restartFraction) || restartFraction <= 0 || restartFraction > 1
                || restartFraction * capacityTicks <= landingReserveTicks + 32
                || !Double.isFinite(startDistance) || !Double.isFinite(stopDistance)
                || stopDistance < 2 || startDistance <= stopDistance || startDistance > 32
                || !Double.isFinite(cruiseHeight) || cruiseHeight < .75 || cruiseHeight > 4
                || !Float.isFinite(clearanceWidth) || clearanceWidth <= 0 || clearanceWidth > 6
                || !Float.isFinite(clearanceHeight) || clearanceHeight <= 0 || clearanceHeight > 6
                || costs == null) {
            throw new IllegalArgumentException("Invalid species flight settings");
        }
    }

    /** Steady costs: a tick of reserve a tick of flight, whatever the flight, and attacks free. */
    public DigimonFlight(double speed, int capacityTicks, int rechargeTicks, int restTicks, double restartFraction,
                         int landingReserveTicks, int minimumFlightTicks, double startDistance, double stopDistance,
                         double cruiseHeight, float clearanceWidth, float clearanceHeight) {
        this(speed, capacityTicks, rechargeTicks, restTicks, restartFraction, landingReserveTicks, minimumFlightTicks,
                startDistance, stopDistance, cruiseHeight, clearanceWidth, clearanceHeight, Costs.STEADY);
    }

    /**
     * What the flight reserve pays ({@code locomotion.flight.costs}): rates a tick against the tick of plain flight
     * ({@code boost} on the sprint key, {@code climb} rising on the jump key, {@code glide} diving or gliding with the wings
     * held), one-off ticks ({@code roll}, a barrel roll; {@code takeoff}), and an attack cast on the wing, a share of the
     * whole reserve ({@code attack}): flying is long, fighting from the sky is not. Fighting also slows the refill on the
     * ground: {@code combat_recharge} of the rate for {@code combat_ticks} after the last blow given or taken.
     */
    public record Costs(float boost, float climb, float glide, float roll, float takeoff, float attack,
                        float combatRecharge, int combatTicks) {
        public static final Costs STEADY = new Costs(1, 1, 1, 0, 0, 0, 1, 0);
        public Costs {
            if (!(boost >= 0 && boost <= 10 && climb >= 0 && climb <= 10 && glide >= 0 && glide <= 10 && roll >= 0 && roll <= 1200
                    && takeoff >= 0 && takeoff <= 1200 && attack >= 0 && attack <= 1 && combatRecharge > 0 && combatRecharge <= 1
                    && combatTicks >= 0 && combatTicks <= 2400)) {
                throw new IllegalArgumentException("Invalid flight costs");
            }
        }
    }
}
