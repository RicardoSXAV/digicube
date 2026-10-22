package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Keeps a partner Digimon near its tamer as a companion animal does. It sets off once the tamer is beyond its species'
 * start distance and heads for a spot at its stop distance from them, on the side it is already on (never into the
 * tamer), keeping to the tamer's own pace with a little more the further behind it is ({@link #CATCH_UP}), at most its
 * follow pace (its run, the tamer sprinting). It lays a new path only when the tamer has moved on from the spot the last
 * one led to ({@link #REPATH_MOVED}) or the path is old ({@link #REPATH_EVERY}), so it never stops and starts on every
 * path laid afresh; it arrives slowing down ({@link DigimonGroundNavigation}) and goes on walking alongside a tamer who
 * keeps walking. Left far behind it teleports next to them. Yields to combat when the Digimon has both a target and usable
 * attacks.
 */
public final class FollowOwnerGoal extends Goal {

    private static final double TELEPORT_DISTANCE = 20.0;
    private static final int TELEPORT_ATTEMPTS = 10;
    /** Blocks the tamer has to move from the spot the path was laid for before a new path is laid. */
    private static final double REPATH_MOVED = 1.25;
    /** Ticks a path is kept at most (a tamer gone round a corner needs a new one). */
    private static final int REPATH_EVERY = 20;
    /** Blocks a tick of pace added for every block the partner is further from its spot than its stop distance. */
    private static final double CATCH_UP = .05;
    /** Share of its stop distance from its tamer it keeps to. */
    private static final double KEEP = .8;
    /**
     * Arrived nearer its tamer than {@link #CROWDED} of where it keeps to, it steps back, facing them, for at most
     * {@link #BACK_OFF} ticks, at {@link #BACK_PACE} of its walk: a short step back reads as an animal making room, a turn
     * away and back as fuss.
     */
    private static final double CROWDED = .6, BACK_PACE = .6;
    private static final int BACK_OFF = 40;
    /** Blocks a tick of the tamer's pace from which they count as walking on. */
    private static final double TAMER_WALKS = .03;

    private final DigimonEntity mob;
    private LivingEntity owner;
    private Vec3 pathFor, ownerLast;
    private double ownerPace;
    private int pathAge, teleportCheck, backOff;

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
        if (!canFollow(owner) || owner != mob.getOwner()) return false;
        float stop = mob.getLocomotion().followStopDistance();
        // a tamer walking on is walked with; one standing is arrived at, a little way off them
        return ownerPace > TAMER_WALKS || mob.distanceTo(owner) > stop || backOff > 0 || !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        pathFor = null;
        pathAge = 0;
        teleportCheck = 0;
        backOff = 0;
        ownerLast = owner.position();
        ownerPace = 0;
    }

    @Override
    public void stop() {
        owner = null;
        mob.setRunningToOwner(false);
        mob.getNavigation().stop();
    }

    private boolean canFollow(LivingEntity candidate) {
        return candidate != null && candidate.isAlive() && !candidate.isSpectator()
                && mob.isAlive() && !mob.isHolding() && (mob.getTarget() == null || !mob.hasAttacks()) && !mob.isAttacking()
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
        Vec3 at = owner.position();
        ownerPace = Mth.lerp(.3, ownerPace, at.subtract(ownerLast).horizontalDistance());
        ownerLast = at;
        boolean running = locomotion.canRun() && owner instanceof Player && owner.isSprinting();
        if (running != mob.isRunningToOwner()) {
            // React to sprint/release immediately, even between paths.
            pathFor = null;
            mob.setRunningToOwner(running);
        }
        mob.getLookControl().setLookAt(owner, 10.0F, (float) mob.getMaxHeadXRot());
        double distance = mob.distanceTo(owner);
        if (--teleportCheck <= 0) {
            teleportCheck = adjustedTickDelay(10);
            if (!mob.isSwimmingMovement() && !(mob.canFly() && mob.flightReserve().ready() && distance < 32)
                    && distance >= TELEPORT_DISTANCE && teleportNearOwner()) return;
        }
        double speed;
        if (mob.canSwim() && mob.isInWater()) speed = 1.0;
        else {
            // the tamer's pace, more the further behind (blocks past its stop distance), at most its follow pace
            float stop = locomotion.followStopDistance();
            double most = mob.paceForModifier(locomotion.followSpeed(running));
            double pace = Mth.clamp(ownerPace + (distance - stop) * CATCH_UP, Math.min(most, mob.walkPace()), most);
            speed = mob.modifierForPace(pace);
        }
        mob.getNavigation().setSpeedModifier(speed);
        if (stepsBack(distance)) return;
        if (pathFor != null && ++pathAge < REPATH_EVERY && at.distanceToSqr(pathFor) < REPATH_MOVED * REPATH_MOVED
                && !mob.getNavigation().isDone()) return;
        pathFor = at;
        pathAge = 0;
        Vec3 spot = spot();
        // A swimmer catches up through the water; a spot the ground navigation cannot reach falls back on the tamer (from
        // further off than where it keeps to: right by them that would walk it into them).
        if (!mob.getNavigation().moveTo(spot.x, spot.y, spot.z, speed) && distance > locomotion.followStopDistance())
            mob.getNavigation().moveTo(owner, speed);
    }

    /**
     * A tamer standing and the partner right at their feet: it steps back, facing them, to where it keeps to (or for
     * {@link #BACK_OFF} ticks at most). Returns whether it is stepping back this tick.
     */
    private boolean stepsBack(double distance) {
        double keep = mob.getLocomotion().followStopDistance() * KEEP;
        if (backOff == 0 && ownerPace <= TAMER_WALKS && distance < keep * CROWDED && mob.getNavigation().isDone()) backOff = BACK_OFF;
        if (backOff == 0) return false;
        if (--backOff == 0 || distance >= keep - .2 || ownerPace > TAMER_WALKS) { backOff = 0; return false; }
        float yaw = (float) (Mth.atan2(owner.getZ() - mob.getZ(), owner.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90;
        double pace = mob.modifierForPace(mob.walkPace() * BACK_PACE);
        mob.getNavigation().stop();
        if (mob.getMoveControl() instanceof DigimonMoveControl control) control.walkFacing(yaw, -1, 0, pace);
        else {
            Vec3 spot = spot();
            mob.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, pace);
        }
        return true;
    }

    /**
     * Where it heads: {@link #KEEP} of its stop distance from the tamer on its own side of them, never into them and never
     * right at their feet (arrived too close, it steps back to it).
     */
    private Vec3 spot() {
        Vec3 at = owner.position(), away = mob.position().subtract(at).multiply(1, 0, 1);
        float stop = mob.getLocomotion().followStopDistance();
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(-Mth.sin(owner.getYRot() * Mth.DEG_TO_RAD), 0, Mth.cos(owner.getYRot() * Mth.DEG_TO_RAD)).scale(-1);
        return at.add(away.normalize().scale(stop * KEEP));
    }

    private boolean teleportNearOwner() {
        for (int attempt = 0; attempt < TELEPORT_ATTEMPTS; attempt++) {
            double x = owner.getX() + mob.getRandom().nextInt(7) - 3;
            double z = owner.getZ() + mob.getRandom().nextInt(7) - 3;
            if (mob.randomTeleport(x, owner.getY(), z, false)) {
                mob.setRunningToOwner(false);
                mob.getNavigation().stop();
                pathFor = null;
                return true;
            }
        }
        return false;
    }
}
