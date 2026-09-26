package com.digicube.digimon;

/**
 * Species-specific follow distances and navigation speeds. Equal speeds disable running.
 * @param followStartDistance distance in blocks that starts following
 * @param followStopDistance distance in blocks that stops following
 * @param walkSpeed navigation modifier while the owner walks
 * @param runSpeed navigation modifier while the owner sprints
 * @param swimSpeed target water speed in blocks per tick; zero disables aquatic locomotion
 * @param flight optional burst-flight settings; null retains ground/aquatic movement
 * @param groundGait optional authored stride and cycle measurements
 * @param hoverFallSpeed a body that hovers on fins instead of feet (Bukamon) glides down at most this many blocks per
 *                       tick, takes no fall damage and makes no footsteps; zero means it walks
 * @param jet optional: it swims in pulses (a squid), null for a steady swimmer
 * @param travelFacing optional: where the body looks while it walks a path (a crab), null to face its travel
 */
public record DigimonLocomotion(float followStartDistance, float followStopDistance,
                               double walkSpeed, double runSpeed, double swimSpeed, DigimonFlight flight,
                               DigimonGait groundGait, double hoverFallSpeed, JetSwim jet, TravelFacing travelFacing) {
    public DigimonLocomotion(float start, float stop, double walk, double run, double swim, DigimonFlight flight,
                             DigimonGait groundGait, double hoverFallSpeed, JetSwim jet) {
        this(start, stop, walk, run, swim, flight, groundGait, hoverFallSpeed, jet, null);
    }
    public DigimonLocomotion(float start, float stop, double walk, double run, double swim, DigimonFlight flight,
                             DigimonGait groundGait, double hoverFallSpeed) {
        this(start, stop, walk, run, swim, flight, groundGait, hoverFallSpeed, null);
    }
    public DigimonLocomotion(float start, float stop, double walk, double run, double swim, DigimonFlight flight,
                             DigimonGait groundGait) {
        this(start, stop, walk, run, swim, flight, groundGait, 0);
    }
    public DigimonLocomotion(float start, float stop, double walk, double run, double swim, DigimonFlight flight) {
        this(start, stop, walk, run, swim, flight, null);
    }
    public DigimonLocomotion(float start, float stop, double walk, double run, double swim) {
        this(start, stop, walk, run, swim, null);
    }

    public boolean canFly() { return flight != null; }
    /** Original follow behavior, used by species without locomotion data. */
    public static final DigimonLocomotion DEFAULT = new DigimonLocomotion(10, 3, 1.15, 1.15, 0);

    /** Validate distances and speeds before the species can be registered. */
    public DigimonLocomotion {
        if (!Float.isFinite(followStartDistance) || !Float.isFinite(followStopDistance)
                || followStopDistance <= 0 || followStartDistance <= followStopDistance
                || !Double.isFinite(walkSpeed) || !Double.isFinite(runSpeed)
                || walkSpeed <= 0 || runSpeed < walkSpeed
                || !Double.isFinite(swimSpeed) || swimSpeed < 0 || swimSpeed > 1
                || !Double.isFinite(hoverFallSpeed) || hoverFallSpeed < 0 || hoverFallSpeed > 1 || jet != null && swimSpeed <= 0) {
            throw new IllegalArgumentException("Invalid species locomotion settings");
        }
    }

    /** @return whether the body floats above the ground on fins rather than standing on it */
    public boolean hovers() {
        return hoverFallSpeed > 0;
    }

    /** @return whether this species has a faster sprint-following pace */
    public boolean canRun() {
        return runSpeed > walkSpeed;
    }

    /**
     * Check whether aquatic movement is configured.
     * @return whether this species uses amphibious navigation and controlled swimming
     */
    public boolean canSwim() {
        return swimSpeed > 0;
    }

    /**
     * Navigation modifiers, applied to the entity's movement attribute exactly once.
     * @param running whether the follow goal is in its running state
     * @return the navigation speed multiplier
     */
    public double followSpeed(boolean running) {
        return running ? runSpeed : walkSpeed;
    }
}
