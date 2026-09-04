package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Keeps a partner Digimon near its tamer, like a tamed wolf: walks over when the tamer
 * gets more than {@code startDistance} blocks away, stops at {@code stopDistance}, and
 * teleports next to them when left far behind. Yields to combat (does nothing while the
 * Digimon has a target).
 */
public final class FollowOwnerGoal extends Goal {

    private static final double TELEPORT_DISTANCE = 20.0;
    private static final int TELEPORT_ATTEMPTS = 10;

    private final DigimonEntity mob;
    private final double speedModifier;
    private final float startDistance;
    private final float stopDistance;
    private LivingEntity owner;
    private int ticksUntilPathRecalc;

    public FollowOwnerGoal(DigimonEntity mob, double speedModifier, float startDistance, float stopDistance) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.startDistance = startDistance;
        this.stopDistance = stopDistance;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity candidate = mob.getOwner();
        if (candidate == null || candidate.isSpectator() || mob.getTarget() != null) {
            return false;
        }
        if (mob.distanceToSqr(candidate) < (double) (startDistance * startDistance)) {
            return false;
        }
        owner = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isAlive() && mob.getTarget() == null
                && !mob.getNavigation().isDone()
                && mob.distanceToSqr(owner) > (double) (stopDistance * stopDistance);
    }

    @Override
    public void start() {
        ticksUntilPathRecalc = 0;
    }

    @Override
    public void stop() {
        owner = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(owner, 10.0F, (float) mob.getMaxHeadXRot());
        if (--ticksUntilPathRecalc > 0) {
            return;
        }
        ticksUntilPathRecalc = adjustedTickDelay(10);
        if (mob.distanceToSqr(owner) >= TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            teleportNearOwner();
        } else {
            mob.getNavigation().moveTo(owner, speedModifier);
        }
    }

    private void teleportNearOwner() {
        for (int attempt = 0; attempt < TELEPORT_ATTEMPTS; attempt++) {
            double x = owner.getX() + mob.getRandom().nextInt(7) - 3;
            double z = owner.getZ() + mob.getRandom().nextInt(7) - 3;
            if (mob.randomTeleport(x, owner.getY(), z, false)) {
                mob.getNavigation().stop();
                return;
            }
        }
    }
}
