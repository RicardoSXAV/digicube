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
 */
public record DigimonFlight(double speed, int capacityTicks, int rechargeTicks, int restTicks,
                            double restartFraction, int landingReserveTicks, int minimumFlightTicks,
                            double startDistance, double stopDistance, double cruiseHeight,
                            float clearanceWidth, float clearanceHeight) {
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
                || !Float.isFinite(clearanceHeight) || clearanceHeight <= 0 || clearanceHeight > 6) {
            throw new IllegalArgumentException("Invalid species flight settings");
        }
    }
}
