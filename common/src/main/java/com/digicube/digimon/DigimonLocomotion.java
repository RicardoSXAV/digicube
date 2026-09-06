package com.digicube.digimon;

/**
 * Species-specific follow distances and navigation speeds. Equal speeds disable running.
 * @param followStartDistance distance in blocks that starts following
 * @param followStopDistance distance in blocks that stops following
 * @param walkSpeed navigation modifier while the owner walks
 * @param runSpeed navigation modifier while the owner sprints
 */
public record DigimonLocomotion(float followStartDistance, float followStopDistance,
                               double walkSpeed, double runSpeed) {
    /** Original follow behavior, used by species without locomotion data. */
    public static final DigimonLocomotion DEFAULT = new DigimonLocomotion(10, 3, 1.15, 1.15);

    /** Validate distances and speeds before the species can be registered. */
    public DigimonLocomotion {
        if (!Float.isFinite(followStartDistance) || !Float.isFinite(followStopDistance)
                || followStopDistance <= 0 || followStartDistance <= followStopDistance
                || !Double.isFinite(walkSpeed) || !Double.isFinite(runSpeed)
                || walkSpeed <= 0 || runSpeed < walkSpeed) {
            throw new IllegalArgumentException("Invalid species locomotion settings");
        }
    }

    /** @return whether this species has a faster sprint-following pace */
    public boolean canRun() {
        return runSpeed > walkSpeed;
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
