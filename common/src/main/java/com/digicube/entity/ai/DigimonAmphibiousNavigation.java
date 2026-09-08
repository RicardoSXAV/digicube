package com.digicube.entity.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.level.Level;

/** Vanilla amphibious navigation that also accepts the steering target as node arrival; see {@link SteeringArrival}. */
public final class DigimonAmphibiousNavigation extends AmphibiousPathNavigation {
    /**
     * Bind the amphibious navigation to its creature.
     * @param mob the swimming Digimon
     * @param level the level it moves in
     */
    public DigimonAmphibiousNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected void followThePath() {
        int before = path.getNextNodeIndex();
        super.followThePath();
        SteeringArrival.advance(path, mob, before, maxDistanceToWaypoint, getMaxVerticalDistanceToWaypoint());
    }
}
