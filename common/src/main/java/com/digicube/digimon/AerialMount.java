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
 * @param agility optional agile flight (the view-led dive, boost, strafe, barrel roll); null keeps the steady handling
 */
public record AerialMount(double cruiseSpeed, double acceleration,
                          double braking, double climbSpeed, double descendSpeed,
                          float turnDegrees, int takeoffTicks, int liftTick,
                          int landingTicks, int wingLoopTicks, Dive dive, Agility agility) {
    /** Reject invalid or unbounded species tuning. */
    public AerialMount {
        if (!finite(cruiseSpeed, acceleration, braking, climbSpeed, descendSpeed, turnDegrees)
                || cruiseSpeed <= 0 || cruiseSpeed > 1.5
                || acceleration <= 0 || braking < acceleration || braking > 1
                || climbSpeed <= 0 || descendSpeed <= 0 || climbSpeed > 1 || descendSpeed > 1
                || turnDegrees <= 0 || turnDegrees > 45 || takeoffTicks < 4 || takeoffTicks > 100
                || liftTick < 0 || liftTick >= takeoffTicks || landingTicks < 4 || landingTicks > 100
                || wingLoopTicks < 2 || wingLoopTicks > 100
                || dive != null && dive.maxSpeed() < cruiseSpeed
                || agility != null && agility.maxSpeed() < cruiseSpeed * agility.boost()) {
            throw new IllegalArgumentException("Invalid aerial mount handling");
        }
    }

    /** Steady handling only (no agile flight). */
    public AerialMount(double cruiseSpeed, double acceleration, double braking, double climbSpeed, double descendSpeed,
                       float turnDegrees, int takeoffTicks, int liftTick, int landingTicks, int wingLoopTicks, Dive dive) {
        this(cruiseSpeed, acceleration, braking, climbSpeed, descendSpeed, turnDegrees, takeoffTicks, liftTick, landingTicks,
                wingLoopTicks, dive, null);
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

    /**
     * Agile flight ({@code body.mount.flight.agility}): the body goes where its rider looks, and speed is energy. Looking
     * down a dive gathers speed by {@code gravity} (blocks a tick, a tick, straight down) toward {@code maxSpeed}; pulled
     * out of it the speed carries on ahead and bleeds off at {@code drag} (a share of what is over the powered speed, a
     * tick), faster climbing (the climb trades it for height at {@code gravity} too) and through hard turns. The faster
     * it goes the wider it turns ({@code turnDegrees} at cruise, never under {@code fastTurn} of it).
     * @param boost the sprint key's share over cruise: wingbeats on full
     * @param strafe the strafe keys' sideways push, a share of cruise; the body banks into it
     * @param gravity speed gained a tick diving straight down, and spent climbing straight up
     * @param drag share of the speed over the powered speed lost a tick
     * @param maxSpeed terminal speed of a dive
     * @param fastTurn the least share of the turn rate left at top speed
     * @param bank degrees the body banks at most into a turn or a slide
     * @param rollSpeed a barrel roll's throw aside (blocks a tick)
     * @param rollTicks ticks a barrel roll takes
     * @param hardLanding downward speed (blocks a tick) at which meeting the ground is a hard landing
     */
    public record Agility(float boost, float strafe, double gravity, double drag, double maxSpeed, float fastTurn,
                          float bank, float rollSpeed, int rollTicks, double hardLanding) {
        public Agility {
            if (!finite(boost, strafe, gravity, drag, maxSpeed, fastTurn, bank, rollSpeed, hardLanding)
                    || boost < 1 || boost > 3 || strafe < 0 || strafe > 1.5 || gravity <= 0 || gravity > .2
                    || drag <= 0 || drag > .5 || maxSpeed <= 0 || maxSpeed > 2.5 || fastTurn <= 0 || fastTurn > 1
                    || bank < 0 || bank > 80 || rollSpeed < 0 || rollSpeed > 2 || rollTicks < 6 || rollTicks > 40
                    || hardLanding <= 0 || hardLanding > 3) {
                throw new IllegalArgumentException("Invalid aerial agility");
            }
        }
    }

    private static boolean finite(double... values) {
        for (double v : values) if (!Double.isFinite(v)) return false;
        return true;
    }
}
