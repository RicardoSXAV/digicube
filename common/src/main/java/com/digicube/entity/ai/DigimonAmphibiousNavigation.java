package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Set;

/**
 * Vanilla amphibious navigation that also accepts the steering target as node arrival; see {@link SteeringArrival}. In the
 * water, and for a serpent anywhere, a node counts as reached within {@link #SWIM_REACH} across: a swimmer carries its
 * speed through a turn, and a serpent cannot turn on the spot, and held to vanilla's half a width (0.45 blocks for
 * Seadramon) either circled a node inside its turn for ever. A serpent's paths go up any ledge it climbs
 * ({@link DigimonEntity#maxUpStep}).
 */
public final class DigimonAmphibiousNavigation extends AmphibiousPathNavigation {
    /** Blocks, along each horizontal axis, within which a swimmer in the water, or a serpent, has reached a node. */
    public static final float SWIM_REACH = 1.5F;

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
        // (across only: a swimmer sinks or climbs onto a node's level on its own, and one held off it by a block and a
        // half hung over its prey, too high to wrap it)
        boolean wide = mob.isInWater() || mob instanceof DigimonEntity digimon && digimon.serpent() != null;
        SteeringArrival.advance(path, mob, before, wide ? Math.max(maxDistanceToWaypoint, SWIM_REACH) : maxDistanceToWaypoint,
                getMaxVerticalDistanceToWaypoint());
    }

    @Override
    protected Path createPath(Set<BlockPos> targets, int radiusOffset, boolean above, int reachRange, float maxPathLength) {
        if (!(mob instanceof DigimonEntity digimon)) return super.createPath(targets, radiusOffset, above, reachRange, maxPathLength);
        digimon.planningPath(true);
        try {
            return super.createPath(targets, radiusOffset, above, reachRange, maxPathLength);
        } finally {
            digimon.planningPath(false);
        }
    }
}
