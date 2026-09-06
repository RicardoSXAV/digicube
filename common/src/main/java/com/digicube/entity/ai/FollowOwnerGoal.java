package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Keeps a partner Digimon near its tamer, like a tamed wolf: walks over when the tamer
 * gets beyond its species' start distance, stops at its stop distance, and
 * teleports next to them when left far behind. Yields to combat (does nothing while the
 * Digimon has a target).
 */
public final class FollowOwnerGoal extends Goal {

    private static final double TELEPORT_DISTANCE = 20.0;
    private static final int TELEPORT_ATTEMPTS = 10;

    private final DigimonEntity mob;
    private LivingEntity owner;
    private int ticksUntilPathRecalc;

    public FollowOwnerGoal(DigimonEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity candidate = mob.getOwner();
        if (!canFollow(candidate)) {
            return false;
        }
        float startDistance = mob.getLocomotion().followStartDistance();
        if (mob.distanceToSqr(candidate) < (double) (startDistance * startDistance)) {
            return false;
        }
        owner = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        float stopDistance = mob.getLocomotion().followStopDistance();
        return canFollow(owner) && owner == mob.getOwner()
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
        mob.setRunningToOwner(false);
        mob.getNavigation().stop();
        mob.getNavigation().setSpeedModifier(mob.getLocomotion().walkSpeed());
    }

    private boolean canFollow(LivingEntity candidate) {
        return candidate != null && candidate.isAlive() && !candidate.isSpectator()
                && mob.isAlive() && mob.getTarget() == null && !mob.isAttacking()
                && !mob.isPassenger() && !mob.isVehicle();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!canFollow(owner)) {
            stop();
            return;
        }
        var locomotion = mob.getLocomotion();
        boolean running = locomotion.canRun() && owner instanceof Player && owner.isSprinting();
        if (running != mob.isRunningToOwner()) {
            // React to sprint/release immediately, even between path recalculations.
            ticksUntilPathRecalc = 0;
            mob.setRunningToOwner(running);
        }
        double speed = locomotion.followSpeed(running);
        mob.getNavigation().setSpeedModifier(speed);
        mob.getLookControl().setLookAt(owner, 10.0F, (float) mob.getMaxHeadXRot());
        if (--ticksUntilPathRecalc > 0) {
            return;
        }
        ticksUntilPathRecalc = adjustedTickDelay(running ? 5 : 10);
        if (mob.distanceToSqr(owner) >= TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            teleportNearOwner();
        } else {
            mob.getNavigation().moveTo(owner, speed);
        }
    }

    private void teleportNearOwner() {
        for (int attempt = 0; attempt < TELEPORT_ATTEMPTS; attempt++) {
            double x = owner.getX() + mob.getRandom().nextInt(7) - 3;
            double z = owner.getZ() + mob.getRandom().nextInt(7) - 3;
            if (mob.randomTeleport(x, owner.getY(), z, false)) {
                mob.setRunningToOwner(false);
                mob.getNavigation().stop();
                return;
            }
        }
    }
}
