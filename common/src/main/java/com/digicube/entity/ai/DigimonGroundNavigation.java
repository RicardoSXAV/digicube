package com.digicube.entity.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;

/** Vanilla ground navigation that also accepts the steering target as node arrival; see {@link SteeringArrival}. */
public final class DigimonGroundNavigation extends GroundPathNavigation {
    /**
     * Bind the land navigation to its creature.
     * @param mob the Digimon
     * @param level the level it walks in
     */
    public DigimonGroundNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected void followThePath() {
        int before = path.getNextNodeIndex();
        super.followThePath();
        SteeringArrival.advance(path, mob, before, maxDistanceToWaypoint, getMaxVerticalDistanceToWaypoint());
    }
}
