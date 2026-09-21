package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonTactics;
import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Fights the current target with the attacks of the species.
 *
 * <p>Each tick: face the target; if an attack is in progress, hold position; otherwise dodge
 * what the target is winding up if the species does that, use the best ready attack for its
 * move set (including mark/fuel combos), and failing that take up position: a firing stance,
 * the species' hold band, or the target itself. The attack (timing, damage, projectile,
 * animation event) is run by {@link DigimonEntity} so it keeps going even if this goal stops
 * mid-swing. The knobs are the species' {@link DigimonTactics}.
 */
public final class DigimonAttackGoal extends Goal {

    /** How far a sidestep goes, and how much longer than the wind-up it is kept. */
    private static final double DODGE_DISTANCE = 3.0, DODGE_SPEED = 1.35;
    private static final int DODGE_EXTRA_TICKS = 4, STRAFE_TICKS = 24;
    /** The spike wave locks its aim four ticks before it lands; the sidestep starts this close to that, and lasts while spikes rise. */
    private static final int LATE_DODGE_TICKS = 9, LINE_DODGE_EXTRA_TICKS = 14;
    /** A projectile this close and closing is worth a sidestep. */
    private static final double INBOUND_RANGE = 7.0;

    private final DigimonEntity mob;
    private final double speedModifier;
    private int ticksUntilPathRecalc;
    private Vec3 positionedTarget;
    private int positionedAtTick;
    private java.util.List<DigimonAttack> positionedMoves = java.util.List.of();
    /** The evasion in progress: where to, until when; and which wind-up was already answered. */
    private Vec3 dodgeTo;
    private int dodgeUntil, answeredWindUp = Integer.MIN_VALUE, answeredProjectile = -1, strafeUntil, strafeSign = 1;

    public DigimonAttackGoal(DigimonEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return !mob.isVehicle() && target != null && target.isAlive() && mob.hasAttacks() && mob.canAttack(target);
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isAttacking() || canUse();
    }

    @Override
    public void start() {
        mob.setAggressive(true);
        ticksUntilPathRecalc = 0;
        positionedTarget = null;
        positionedMoves = java.util.List.of();
        dodgeTo = null;
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        mob.getNavigation().stop();
        mob.resetConstrictionApproach();
        dodgeTo = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        mob.traceCombat(target);
        DigimonTactics tactics = mob.tactics();
        if (mob.isAttacking()) {
            // A stream can be cut to get out of the way of a shot or a spike wave; anything else is committed.
            if (!(mob.getActiveAttack().fuel() != null && tactics.dodgeChance() > 0 && breakStreamForShot(target, tactics))) {
                mob.getNavigation().stop();
                dodgeTo = null;
                return;
            }
        }
        if (tickDodge(target, tactics)) return;
        // A species with a fight pace of its own (slow traveller, fast striker) uses it for every move of the fight.
        double speedModifier = tactics.fightSpeed() > 0 ? tactics.fightSpeed() : this.speedModifier;
        if (mob.tickConstrictionApproach(target,speedModifier)) return;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        DigimonAttack attack = mob.chooseAttack(target);
        if (attack != null) {
            mob.getNavigation().stop();
            mob.startAttack(attack, target);
            return;
        }

        var desiredMoves = mob.positioningAttacks(target);
        boolean opening = desiredMoves.stream().anyMatch(move -> move.kind() == DigimonAttack.Kind.CONSTRICTION);
        boolean pressing = tactics.pressImpaired() && DigimonEntity.impaired(target);
        double runSpeed = tactics.fightSpeed() > 0 ? tactics.fightSpeed() : mob.getLocomotion().runSpeed();
        boolean charging = tactics.chargeDistance() > 0 && mob.distanceToSqr(target) > tactics.chargeDistance() * tactics.chargeDistance();
        double pursuitSpeed = charging && tactics.chargeSpeed() > 0 ? Math.max(tactics.chargeSpeed(), speedModifier)
                : pressing || charging || mob.getLocomotion().groundGait() != null
                && mob.getLocomotion().groundGait().runStride() > mob.getLocomotion().groundGait().stride()
                && mob.distanceToSqr(target) > 64 ? Math.max(runSpeed, speedModifier) : speedModifier;
        boolean preparing = desiredMoves.stream().noneMatch(mob::isAttackReady);
        // Nothing to fire and a band to keep: the band wins over standing in a stance next to an enemy that can hit us.
        if (preparing && tactics.holdsRange() && !opening && !pressing && threatens(target) && holdRange(target, tactics, runSpeed)) return;
        if (preparing && desiredMoves.stream().anyMatch(move -> mob.canAttackFrom(move,target,mob.position()))) {
            // Hold a useful firing stance; otherwise use recovery time to get
            // there, including climbing/descending to a reachable platform.
            mob.getNavigation().stop();
            return;
        }
        if (!desiredMoves.equals(positionedMoves)) ticksUntilPathRecalc = 0;
        if (--ticksUntilPathRecalc <= 0) {
            ticksUntilPathRecalc = adjustedTickDelay(6 + mob.getRandom().nextInt(6));
            if (positionedTarget != null && !mob.getNavigation().isDone()
                    && mob.tickCount - positionedAtTick < 20
                    && desiredMoves.equals(positionedMoves) && positionedTarget.distanceToSqr(target.position()) < 1) return;
            positionedMoves = desiredMoves;
            var combatPath = DigimonCombatPosition.find(mob, target,preparing);
            if (combatPath != null && mob.getNavigation().moveTo(combatPath, pursuitSpeed)) {
                positionedTarget = target.position();
                // Navigation can keep a blocked path alive while an enemy pins us.
                // Re-evaluate at least once a second, even if the target stays put.
                positionedAtTick = mob.tickCount;
                return;
            }
            positionedTarget = null;
            if (mob.isInWater() && !mob.canSwim()) {
                // A floating land body has no path down to prey under it: it paddles over it and strikes what comes up.
                Vec3 over = new Vec3(target.getX(), mob.getY(), target.getZ());
                if (!mob.getNavigation().moveTo(over.x, over.y, over.z, pursuitSpeed)) {
                    mob.getNavigation().stop();
                    if (mob.position().subtract(over).horizontalDistanceSqr() > 1) mob.getMoveControl().setWantedPosition(over.x, over.y, over.z, pursuitSpeed);
                }
                return;
            }
            if (preparing) {
                mob.getNavigation().stop();
                return;
            }
            if (Math.abs(target.getY()-mob.getY()) > .6) {
                // Never fall back to pressing into the bottom of a cliff or
                // alternating chase/retreat while both attacks recover.
                var reachable = mob.getNavigation().createPath(target,0);
                if (reachable != null && reachable.canReach()) mob.getNavigation().moveTo(reachable,speedModifier);
                else mob.getNavigation().stop();
                return;
            }
            double spacing = mob.minimumAttackSpacing();
            if (spacing > 0 && mob.position().subtract(target.position()).horizontalDistanceSqr() < spacing * spacing
                    && Math.abs(mob.getY() - target.getY()) < .6) {
                var away = mob.position().subtract(target.position()).multiply(1, 0, 1).normalize();
                if (away.lengthSqr() < .01) away = Vec3.directionFromRotation(0, mob.getYRot() + 180);
                var retreat = mob.position().add(away.scale(spacing + 0.5));
                mob.getNavigation().moveTo(retreat.x, retreat.y, retreat.z, speedModifier);
            } else if (tactics.leadTicks() > 0) {
                // Walk to where the target will be, not where it is.
                Vec3 ahead = predicted(target, tactics.leadTicks());
                mob.getNavigation().moveTo(ahead.x, ahead.y, ahead.z, pursuitSpeed);
            } else {
                mob.getNavigation().moveTo(target, pursuitSpeed);
            }
        }
    }

    /** The target's position {@code ticks} from now at its current pace; the target itself with no lead. */
    private static Vec3 predicted(LivingEntity target, int ticks) {
        if (ticks <= 0) return target.position();
        Vec3 velocity = target.position().subtract(target.xOld, target.yOld, target.zOld).multiply(1, 0, 1);
        return target.position().add(velocity.scale(ticks));
    }

    // --- evasion -------------------------------------------------------------------------

    /** Cuts the running stream when a shot is inbound and the roll says dodge; the dodge itself follows this tick. */
    private boolean breakStreamForShot(LivingEntity target, DigimonTactics tactics) {
        // The spike wave is worth a stream too: its sidestep starts here, at the late moment, with the one roll it gets.
        int windUp = threateningWindUp(target);
        if (windUp >= 0 && windUp <= LATE_DODGE_TICKS && ((DigimonEntity) target).getActiveAttack().kind() == DigimonAttack.Kind.GROUND_WAVE) {
            int started = mob.tickCount - ((DigimonEntity) target).currentAttackTick();
            if (started == answeredWindUp) return false;
            answeredWindUp = started;
            if (mob.getRandom().nextFloat() >= tactics.dodgeChance()) return false;
            mob.interruptAttack();
            return startDodge(target, windUp + LINE_DODGE_EXTRA_TICKS, "wind-up of tectonic wave, stream cut");
        }
        Projectile shot = inboundShot(target);
        if (shot == null || shot.getId() == answeredProjectile) return false;
        answeredProjectile = shot.getId();
        if (mob.getRandom().nextFloat() >= tactics.dodgeChance()) return false;
        mob.interruptAttack();
        answeredProjectile = -1;
        return true;
    }

    /** Keeps a sidestep going, or starts one against a wind-up or an inbound shot. True while it owns the tick. */
    private boolean tickDodge(LivingEntity target, DigimonTactics tactics) {
        if (dodgeTo != null) {
            if (mob.tickCount < dodgeUntil && !mob.getNavigation().isDone()) return true;
            dodgeTo = null;
        }
        if (tactics.dodgeChance() <= 0 || !mob.onGround() && !mob.isInWater()) return false;
        int windUp = threateningWindUp(target);
        if (windUp >= 0) {
            var other = (DigimonEntity) target;
            int started = mob.tickCount - other.currentAttackTick();
            if (started == answeredWindUp) return false;
            if (other.currentAttackTick() < tactics.reactionTicks()) return false;
            // An aimed line (the spike wave) follows us until just before it lands: stepping aside early only
            // moves the aim. Wait, then be in motion across the line when the aim locks.
            boolean aimedLine = other.getActiveAttack().kind() == DigimonAttack.Kind.GROUND_WAVE;
            if (aimedLine && windUp > LATE_DODGE_TICKS) return false;
            answeredWindUp = started;
            if (mob.getRandom().nextFloat() >= tactics.dodgeChance()) return false;
            return startDodge(target, windUp + (aimedLine ? LINE_DODGE_EXTRA_TICKS : DODGE_EXTRA_TICKS), "wind-up of " + other.getActiveAttack().id().getPath());
        }
        Projectile shot = inboundShot(target);
        if (shot != null) {
            if (shot.getId() == answeredProjectile) return false;
            answeredProjectile = shot.getId();
            if (mob.getRandom().nextFloat() >= tactics.dodgeChance()) return false;
            double ticksToArrive = shot.position().distanceTo(mob.position()) / Math.max(.05, shot.getDeltaMovement().length());
            return startDodge(target, (int) ticksToArrive + DODGE_EXTRA_TICKS, "shot " + shot.getType().toShortString());
        }
        return false;
    }

    /**
     * Ticks until the target's current attack lands, when that attack could reach us and has not landed
     * yet; -1 otherwise. Melee, sweeps, lunges and wraps are dodged on the wind-up; shots in flight.
     */
    private int threateningWindUp(LivingEntity target) {
        if (!(target instanceof DigimonEntity other) || !other.isAttacking()) return -1;
        DigimonAttack attack = other.getActiveAttack();
        if (attack == null || attack.isRanged() && attack.kind() != DigimonAttack.Kind.GROUND_WAVE) return -1;
        // A wrap's hit tick is its capture, two seconds in: the one wind-up worth running from.
        int remaining = attack.hitTick() - other.currentAttackTick();
        if (remaining <= 0) return -1;
        double reach = attack.range() + (mob.getBbWidth() + other.getBbWidth()) * .5 + 1;
        return mob.distanceToSqr(other) <= reach * reach ? remaining : -1;
    }

    /** The target's projectile closing on us, if any. */
    private Projectile inboundShot(LivingEntity target) {
        AABB around = mob.getBoundingBox().inflate(INBOUND_RANGE);
        for (Projectile shot : mob.level().getEntitiesOfClass(Projectile.class, around, s -> s.getOwner() == target)) {
            Vec3 toUs = mob.position().add(0, mob.getBbHeight() * .5, 0).subtract(shot.position());
            Vec3 velocity = shot.getDeltaMovement();
            if (velocity.lengthSqr() < .0025 || toUs.dot(velocity) <= 0) continue;
            // Closing, and aimed within about a body width of us.
            double miss = toUs.cross(velocity.normalize()).length();
            if (miss <= mob.getBbWidth() + 1.5) return shot;
        }
        return null;
    }

    /** Sidesteps perpendicular to the line of attack, to whichever side has room; back a little as well. */
    private boolean startDodge(LivingEntity target, int ticks, String why) {
        Vec3 line = target.position().subtract(mob.position()).multiply(1, 0, 1);
        if (line.lengthSqr() < .01) line = Vec3.directionFromRotation(0, mob.getYRot());
        line = line.normalize();
        Vec3 side = new Vec3(-line.z, 0, line.x);
        int first = mob.getRandom().nextBoolean() ? 1 : -1;
        for (int sign : new int[]{first, -first}) {
            Vec3 to = mob.position().add(side.scale(sign * DODGE_DISTANCE)).subtract(line.scale(DODGE_DISTANCE * .35));
            var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
            if (path == null || !path.canReach()) continue;
            double pace = mob.tactics().fightSpeed() > 0 ? mob.tactics().fightSpeed() : Math.max(speedModifier, mob.getLocomotion().runSpeed());
            if (!mob.getNavigation().moveTo(path, pace * DODGE_SPEED)) continue;
            dodgeTo = to;
            dodgeUntil = mob.tickCount + ticks;
            mob.countDodge();
            Constants.LOG.debug("[tactics] {} dodges the {} ({} ticks, side {})", mob.getType().toShortString(), why, ticks, sign);
            return true;
        }
        return false;
    }

    // --- range holding -------------------------------------------------------------------

    /** A target with any attack is worth keeping a band from; a cow or a player at range is not. */
    private static boolean threatens(LivingEntity target) {
        return target instanceof DigimonEntity other ? other.hasAttacks() : target instanceof net.minecraft.world.entity.player.Player;
    }

    private static boolean shoots(LivingEntity target) {
        return target instanceof DigimonEntity other && other.hasRangedAttack();
    }

    /** Keeps the species' band around the target while nothing is ready; true when it moved or held. */
    private boolean holdRange(LivingEntity target, DigimonTactics tactics, double runSpeed) {
        if (Math.abs(target.getY() - mob.getY()) > .6 && !mob.isInWater()) return false;
        double distance = mob.position().subtract(target.position()).horizontalDistance();
        Vec3 ahead = predicted(target, tactics.leadTicks());
        Vec3 away = mob.position().subtract(ahead).multiply(1, 0, 1);
        if (away.lengthSqr() < .01) away = Vec3.directionFromRotation(0, mob.getYRot() + 180);
        away = away.normalize();
        double speed = tactics.fightSpeed() > 0 ? tactics.fightSpeed() : Math.max(runSpeed, speedModifier);
        if (distance < tactics.holdMin()) {
            // Back off past the band's inner edge; if straight back is blocked, angle away.
            double want = tactics.holdMin() + 1 - distance;
            for (float turn : new float[]{0, 45, -45, 90, -90}) {
                Vec3 to = mob.position().add(away.yRot((float) Math.toRadians(turn)).scale(want));
                var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
                if (path != null && path.canReach() && mob.getNavigation().moveTo(path, speed)) return true;
            }
            return false;
        }
        if (distance > tactics.holdMax()) {
            Vec3 to = ahead.add(away.scale(tactics.holdMax() - .5));
            return mob.getNavigation().moveTo(to.x, to.y, to.z, speedModifier) || mob.getNavigation().moveTo(target, speedModifier);
        }
        if (!tactics.strafe() || !shoots(target)) { mob.getNavigation().stop(); return true; }
        // Inside the band: circle, changing direction now and then, so a shot needs a lead.
        if (mob.tickCount >= strafeUntil || mob.getNavigation().isDone()) {
            if (mob.tickCount >= strafeUntil) { strafeSign = mob.getRandom().nextBoolean() ? 1 : -1; strafeUntil = mob.tickCount + STRAFE_TICKS; }
            Vec3 side = new Vec3(-away.z, 0, away.x).scale(strafeSign * 2.5);
            Vec3 to = mob.position().add(side);
            var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
            if (path == null || !path.canReach() || !mob.getNavigation().moveTo(path, speedModifier)) {
                strafeSign = -strafeSign;
                mob.getNavigation().stop();
            }
        }
        return true;
    }
}
