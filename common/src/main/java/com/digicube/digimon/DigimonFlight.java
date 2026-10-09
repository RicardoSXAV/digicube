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
 * @param timing the takeoff's and the landing's clocks ({@link Timing#DEFAULT} without)
 * @param sortie how it fights on the wing unridden, or null when it never takes off to fight
 * @param endless whether its wings never tire ({@code endless}): the reserve is never drawn on, so it never has to land
 */
public record DigimonFlight(double speed, int capacityTicks, int rechargeTicks, int restTicks,
                            double restartFraction, int landingReserveTicks, int minimumFlightTicks,
                            double startDistance, double stopDistance, double cruiseHeight,
                            float clearanceWidth, float clearanceHeight, Costs costs, Timing timing, Sortie sortie,
                            boolean endless) {
    public DigimonFlight {
        if (timing == null) timing = Timing.DEFAULT;
        int landing = timing.landingTicks();
        if (!Double.isFinite(speed) || speed <= 0 || speed > .8
                || capacityTicks < 120 || capacityTicks > 2400 || rechargeTicks < 20 || rechargeTicks > 12000
                || restTicks < 20 || restTicks > 1200 || minimumFlightTicks < 0
                || landingReserveTicks < landing || minimumFlightTicks + landingReserveTicks + landing >= capacityTicks
                || !Double.isFinite(restartFraction) || restartFraction <= 0 || restartFraction > 1
                || restartFraction * capacityTicks <= landingReserveTicks + landing
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

    /** The default takeoff and landing clocks, and no fighting on the wing. */
    public DigimonFlight(double speed, int capacityTicks, int rechargeTicks, int restTicks, double restartFraction,
                         int landingReserveTicks, int minimumFlightTicks, double startDistance, double stopDistance,
                         double cruiseHeight, float clearanceWidth, float clearanceHeight, Costs costs) {
        this(speed, capacityTicks, rechargeTicks, restTicks, restartFraction, landingReserveTicks, minimumFlightTicks,
                startDistance, stopDistance, cruiseHeight, clearanceWidth, clearanceHeight, costs, Timing.DEFAULT, null, false);
    }

    /**
     * The clocks of an unridden flyer's takeoff and landing ({@code locomotion.flight}: {@code lift_tick},
     * {@code takeoff_ticks}, {@code landing_ticks}, {@code loop_ticks}): it leaves the ground at {@code liftTick} of a
     * takeoff that becomes flight at {@code takeoffTicks}; a landing lasts {@code landingTicks} from the touchdown, which
     * waits on the ground for the wing loop's seam ({@code loopTicks}; 1 lands at once). A flying mount keeps the clocks
     * of its {@code body.mount.flight}.
     */
    public record Timing(int liftTick, int takeoffTicks, int landingTicks, int loopTicks) {
        public static final Timing DEFAULT = new Timing(13, 32, 32, 40);
        public Timing {
            if (!(liftTick >= 0 && takeoffTicks > liftTick && takeoffTicks <= 80 && landingTicks >= 4 && landingTicks <= 80
                    && loopTicks >= 1 && loopTicks <= 80)) throw new IllegalArgumentException("Invalid flight timing");
        }
    }

    /**
     * How an unridden flyer fights on the wing ({@code locomotion.flight.sortie}): with prey and a move it casts only on
     * the wing ready (a shot with {@code "wing": "only"}), and at least {@code reserve} of its flight reserve, it takes off,
     * hovers {@code height} blocks over the prey's feet, {@code from} to {@code to} blocks from it in the open and in sight,
     * and casts there; a blow it can also cast on the wing goes in when the prey is within reach. It lands once nothing it
     * casts on the wing is ready within {@code linger} ticks, or when the reserve runs low.
     * With {@code hold} it fights the whole fight on the wing: it takes off for any prey within reach, whatever is ready,
     * and comes down only once it has had no prey for {@code linger} ticks. With {@code strike} (blocks over the prey's
     * feet, 0 for none) it swoops in to cast a blow it can also cast on the wing: it hovers that low beside the prey, where
     * the blow reaches it, as the blow comes ready.
     */
    public record Sortie(double height, double from, double to, float reserve, int linger, boolean hold, double strike) {
        public Sortie {
            if (!(Double.isFinite(height) && height >= .5 && height <= 6 && Double.isFinite(from) && from >= 1 && to > from && to <= 20
                    && reserve > 0 && reserve <= 1 && linger >= 0 && linger <= 400
                    && Double.isFinite(strike) && (strike == 0 || strike >= .1 && strike <= 3)))
                throw new IllegalArgumentException("Invalid flight sortie");
        }

        /** A sortie that lands when nothing is ready, and casts its blows only when the prey comes within reach. */
        public Sortie(double height, double from, double to, float reserve, int linger) {
            this(height, from, to, reserve, linger, false, 0);
        }
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
