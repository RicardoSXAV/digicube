package com.digicube.digimon;

/**
 * Reusable aerial handling, in blocks and server ticks. No species names grant flight.
 * @param cruiseSpeed powered level-flight speed, blocks per tick
 * @param acceleration velocity change per tick while moving
 * @param braking velocity change per tick when movement is released
 * @param climbSpeed maximum upward speed
 * @param descendSpeed maximum downward speed
 * @param turnDegrees maximum yaw change per tick
 * @param takeoffTicks duration of the native launch
 * @param liftTick first physical lift tick
 * @param landingTicks grounded settling duration after contact
 * @param wingLoopTicks length of the repeating native flight clip
 * @param dive optional altitude-to-speed handling; null retains steady flight
 */
public record AerialMount(double cruiseSpeed, double acceleration,
                          double braking, double climbSpeed, double descendSpeed,
                          float turnDegrees, int takeoffTicks, int liftTick,
                          int landingTicks, int wingLoopTicks, Dive dive) {
    /** Reject invalid or unbounded species tuning. */
    public AerialMount {
        if (!finite(cruiseSpeed, acceleration, braking, climbSpeed, descendSpeed, turnDegrees)
                || cruiseSpeed <= 0 || cruiseSpeed > 1.5
                || acceleration <= 0 || braking < acceleration || braking > 1
                || climbSpeed <= 0 || descendSpeed <= 0 || climbSpeed > 1 || descendSpeed > 1
                || turnDegrees <= 0 || turnDegrees > 45 || takeoffTicks < 4 || takeoffTicks > 100
                || liftTick < 0 || liftTick >= takeoffTicks || landingTicks < 4 || landingTicks > 100
                || wingLoopTicks < 2 || wingLoopTicks > 100
                || dive != null && dive.maxSpeed() < cruiseSpeed) {
            throw new IllegalArgumentException("Invalid aerial mount handling");
        }
    }

    /** Species tuning for earned momentum, in blocks per tick and velocity change per tick.
     * @param maxSpeed terminal dive speed
     * @param acceleration speed gained along a descending trajectory
     * @param drag level-flight loss of speed above powered cruise
     * @param climbDrag additional momentum spent climbing
     * @param turnDrag additional momentum spent on a hard turn */
    public record Dive(double maxSpeed, double acceleration, double drag, double climbDrag, double turnDrag) {
        /** Reject non-finite or unbounded momentum settings. */
        public Dive {
            if (!finite(maxSpeed, acceleration, drag, climbDrag, turnDrag)
                    || maxSpeed <= 0 || maxSpeed > 1.5 || acceleration <= 0 || acceleration > .1
                    || drag <= 0 || drag > .1 || climbDrag < 0 || climbDrag > .1
                    || turnDrag < 0 || turnDrag > .2) {
                throw new IllegalArgumentException("Invalid aerial dive handling");
            }
        }
    }
    private static boolean finite(double... values) {
        for (double v : values) if (!Double.isFinite(v)) return false;
        return true;
    }
}
