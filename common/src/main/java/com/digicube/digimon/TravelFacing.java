package com.digicube.digimon;

/**
 * Which way a body looks while it walks a path, for one that does not simply face its travel (Crabmon): its directional
 * gait then plays forward, backward and sideways steps from how the travel splits in the body's frame.
 * @param sideOnFrom      at this navigation speed modifier or faster the body turns side-on to its travel, whichever side
 *                        is nearer (a crab's hurried scuttle); slower it walks facing its travel
 * @param faceTargetWithin while its enemy is closer than this many blocks the body faces the enemy instead and steps
 *                        forward, back and aside at it, whatever the pace
 * @param turnRate        degrees a tick the body turns toward the facing it wants
 */
public record TravelFacing(double sideOnFrom, double faceTargetWithin, float turnRate) {
    public TravelFacing {
        if (!Double.isFinite(sideOnFrom) || sideOnFrom <= 0 || !Double.isFinite(faceTargetWithin) || faceTargetWithin < 0
                || !Float.isFinite(turnRate) || turnRate <= 0 || turnRate > 180)
            throw new IllegalArgumentException("Invalid travel facing");
    }
}
