package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonTactics;
import com.digicube.entity.AttackGeometry;
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
    /** Blocks of open ground a jet dodge and a jet getaway need ahead of them. */
    private static final double JET_DODGE_ROOM = 4.5, JET_ESCAPE_ROOM = 6.0;
    /** Headings from straight away that a getaway tries, the last ones past the enemy's side when a wall is behind. */
    private static final float[] ESCAPE_TURNS = {0, 35, -35, 70, -70, 110, -110, 150, -150};
    /** A circling step, and how often a circling gallop is re-aimed. */
    private static final double CIRCLE_STEP = 4.0;
    private static final int CIRCLE_TICKS = 8;

    private final DigimonEntity mob;
    private final double speedModifier;
    private int ticksUntilPathRecalc;
    private Vec3 positionedTarget;
    private int positionedAtTick;
    private java.util.List<DigimonAttack> positionedMoves = java.util.List.of();
    /** The evasion in progress: where to, until when; and which wind-up was already answered. */
    private Vec3 dodgeTo;
    private int dodgeUntil, answeredWindUp = Integer.MIN_VALUE, answeredProjectile = -1, answeredStream = Integer.MIN_VALUE,
            strafeUntil, strafeSign = 1, circleAt;


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
        if (mob.shootingOnTheRun()) { tickOnTheRun(target, tactics); return; }
        if (mob.isAttacking()) {
            // A stream can be cut to get out of the way of a shot or a spike wave; anything else is committed.
            if (!(mob.getActiveAttack().fuel() != null && dodgeChance(mob, tactics) > 0 && breakStreamForShot(target, tactics))) {
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
        if (engage(target, tactics)) return;

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

    /** The species' chance to sidestep; an Exposed body cannot. */
    public static float dodgeChance(LivingEntity mob, DigimonTactics tactics) {
        return com.digicube.digimon.ExposedMark.exposed(mob) ? 0 : tactics.dodgeChance();
    }

    /** Cuts the running stream when a shot is inbound and the roll says dodge; the dodge itself follows this tick. */
    private boolean breakStreamForShot(LivingEntity target, DigimonTactics tactics) {
        // The spike wave is worth a stream too: its sidestep starts here, at the late moment, with the one roll it gets.
        int windUp = threateningWindUp(target);
        if (windUp >= 0 && windUp <= LATE_DODGE_TICKS && ((DigimonEntity) target).getActiveAttack().kind() == DigimonAttack.Kind.GROUND_WAVE) {
            int started = mob.tickCount - ((DigimonEntity) target).currentAttackTick();
            if (started == answeredWindUp) return false;
            answeredWindUp = started;
            if (mob.getRandom().nextFloat() >= dodgeChance(mob, tactics)) return false;
            mob.interruptAttack();
            return startDodge(target, windUp + LINE_DODGE_EXTRA_TICKS, "wind-up of tectonic wave, stream cut");
        }
        Projectile shot = inboundShot(target);
        if (shot == null || shot.getId() == answeredProjectile) return false;
        answeredProjectile = shot.getId();
        if (mob.getRandom().nextFloat() >= dodgeChance(mob, tactics)) return false;
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
        if (dodgeChance(mob, tactics) <= 0 || !mob.onGround() && !mob.isInWater()) return false;
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
            if (mob.getRandom().nextFloat() >= dodgeChance(mob, tactics)) return false;
            return startDodge(target, windUp + (aimedLine ? LINE_DODGE_EXTRA_TICKS : DODGE_EXTRA_TICKS), "wind-up of " + other.getActiveAttack().id().getPath());
        }
        Projectile shot = inboundShot(target);
        if (shot != null) {
            if (shot.getId() == answeredProjectile) return false;
            answeredProjectile = shot.getId();
            if (mob.getRandom().nextFloat() >= dodgeChance(mob, tactics)) return false;
            double ticksToArrive = shot.position().distanceTo(mob.position()) / Math.max(.05, shot.getDeltaMovement().length());
            return startDodge(target, (int) ticksToArrive + DODGE_EXTRA_TICKS, "shot " + shot.getType().toShortString());
        }
        // A stream turns after its target, so a sidestep only wastes time: a body with a jet leaves its reach, which ends it.
        int stream = tactics.dashDodge() && mob.jetReady() ? streamOnUs(target, tactics) : Integer.MIN_VALUE;
        if (stream != Integer.MIN_VALUE && stream != answeredStream) {
            answeredStream = stream;
            if (mob.getRandom().nextFloat() >= dodgeChance(mob, tactics)) return false;
            if (jetAway(mob, target, target.position(), "jet_dodge")) { mob.countDodge(); return true; }
        }
        return false;
    }

    /**
     * The tick the target's stream started, when it is breathing (or about to) and we are within its reach; one
     * roll answers the whole stream. {@code Integer.MIN_VALUE} otherwise.
     */
    private int streamOnUs(LivingEntity target, DigimonTactics tactics) {
        if (!(target instanceof DigimonEntity other) || !other.isAttacking()) return Integer.MIN_VALUE;
        DigimonAttack attack = other.getActiveAttack();
        if (attack == null || attack.fuel() == null || attack.motion() == null) return Integer.MIN_VALUE;
        int tick = other.currentAttackTick();
        if (tick < tactics.reactionTicks() || tick > attack.motion().activeUntil()) return Integer.MIN_VALUE;
        double reach = attack.range() + (mob.getBbWidth() + other.getBbWidth()) * .5;
        return mob.distanceToSqr(other) <= reach * reach ? mob.tickCount - tick : Integer.MIN_VALUE;
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

    /**
     * Sidesteps perpendicular to the line of attack, to whichever side has room; back a little as well. A species that
     * dashes jets out of the way instead while its jet is ready.
     */
    private boolean startDodge(LivingEntity target, int ticks, String why) {
        Vec3 line = target.position().subtract(mob.position()).multiply(1, 0, 1);
        if (line.lengthSqr() < .01) line = Vec3.directionFromRotation(0, mob.getYRot());
        line = line.normalize();
        Vec3 side = new Vec3(-line.z, 0, line.x);
        int first = mob.getRandom().nextBoolean() ? 1 : -1;
        if (mob.tactics().dashDodge() && mob.jetReady()) {
            // Aside and a little back; cornered, aside and past the attacker.
            for (int attempt = 0; attempt < 4; attempt++) {
                int sign = attempt % 2 == 0 ? first : -first;
                Vec3 dir = side.scale(sign).add(line.scale(attempt < 2 ? -.35 : .5)).normalize();
                if (!openRun(mob, dir, JET_DODGE_ROOM) || !mob.startJetBurst(target, null, AttackGeometry.yaw(Vec3.ZERO, dir), true, "jet_dodge")) continue;
                dodgeTo = null;
                mob.countDodge();
                Constants.LOG.debug("[tactics] {} jets clear of the {} (side {})", mob.getType().toShortString(), why, sign);
                return true;
            }
        }
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
            // A brawler that has closed in is left behind on the jet, where it has room.
            if (tactics.dashEscape() && brawls(target) && jetAway(mob, target, target.position(), "jet_getaway")) return true;
            // Back off past the band's inner edge; if straight back is blocked, angle away.
            double want = tactics.holdMin() + 1 - distance;
            for (float turn : new float[]{0, 45, -45, 90, -90}) {
                Vec3 to = mob.position().add(away.yRot((float) Math.toRadians(turn)).scale(want));
                var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
                if (path != null && path.canReach() && mob.getNavigation().moveTo(path, speed)) return true;
            }
            // Cornered: a skirmisher runs along the wall rather than stand in reach.
            if (tactics.gallop()) { circle(target, tactics, speed); return true; }
            return false;
        }
        double walk = tactics.gallop() ? speed : speedModifier;
        if (distance > tactics.holdMax()) {
            Vec3 to = ahead.add(away.scale(tactics.holdMax() - .5));
            return mob.getNavigation().moveTo(to.x, to.y, to.z, walk) || mob.getNavigation().moveTo(target, walk);
        }
        // A skirmisher never stands still, shots or no shots coming.
        if (tactics.strafe() && tactics.gallop()) { circle(target, tactics, speed); return true; }
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

    // --- the jet and the run -------------------------------------------------------------

    /**
     * A shot loosed on the run: the legs keep circling in the band while the upper body aims, a blow is sidestepped
     * without dropping the shot, and once the shot has left an opening is charged at once.
     */
    private void tickOnTheRun(LivingEntity target, DigimonTactics tactics) {
        if (tickDodge(target, tactics)) return;
        if (mob.shotFollowingThrough() && engage(target, tactics)) return;
        circle(target, tactics, tactics.fightSpeed() > 0 ? tactics.fightSpeed() : Math.max(speedModifier, mob.getLocomotion().runSpeed()));
    }

    /**
     * An Exposed or impaired target within {@code dash_engage} is run down with the jet and bucked: the opening the
     * cannon made (it cannot dodge, and crits come easily), taken before it closes.
     */
    private boolean engage(LivingEntity target, DigimonTactics tactics) {
        if (tactics.dashEngage() <= 0 || !(com.digicube.digimon.ExposedMark.exposed(target) || DigimonEntity.impaired(target))
                || Math.abs(target.getY() - mob.getY()) > .6 || !mob.jetReady()) return false;
        double distance = mob.position().subtract(target.position()).horizontalDistance();
        return distance <= tactics.dashEngage() && mob.hasLineOfSight(target) && mob.startJetBurst(target, target, 0, false, "jet_charge");
    }

    /**
     * Circles the target, drifting back toward the middle of the band: sideways to the line between them, so a shot
     * under way never has its back turned to the target (the upper body turns at most 110 degrees).
     */
    private void circle(LivingEntity target, DigimonTactics tactics, double speed) {
        if (mob.tickCount < circleAt && !mob.getNavigation().isDone()) return;
        circleAt = mob.tickCount + CIRCLE_TICKS;
        if (mob.tickCount >= strafeUntil) { strafeSign = mob.getRandom().nextBoolean() ? 1 : -1; strafeUntil = mob.tickCount + STRAFE_TICKS * 2; }
        Vec3 away = mob.position().subtract(target.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < .01) away = Vec3.directionFromRotation(0, mob.getYRot() + 180);
        double distance = away.length();
        away = away.normalize();
        double drift = Math.clamp(((tactics.holdMin() + tactics.holdMax()) * .5 - distance) * .5, -CIRCLE_STEP * .5, CIRCLE_STEP * .5);
        for (int attempt = 0; attempt < 2; attempt++) {
            Vec3 to = mob.position().add(new Vec3(-away.z, 0, away.x).scale(strafeSign * CIRCLE_STEP)).add(away.scale(drift));
            var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
            if (path != null && path.canReach() && mob.getNavigation().moveTo(path, speed)) return;
            strafeSign = -strafeSign;
        }
        mob.getNavigation().stop();
    }

    /**
     * A jet burst away from {@code from}, straight away where there is room, else angled off, else past the enemy's
     * side. {@code target} is who the fight is with, or null for a blinded body getting clear of where it was hit from.
     */
    public static boolean jetAway(DigimonEntity mob, LivingEntity target, Vec3 from, String skill) {
        if (!mob.jetReady()) return false;
        Vec3 away = mob.position().subtract(from).multiply(1, 0, 1);
        away = away.lengthSqr() < .01 ? Vec3.directionFromRotation(0, mob.getYRot() + 180) : away.normalize();
        for (float turn : ESCAPE_TURNS) {
            Vec3 dir = away.yRot((float) Math.toRadians(turn));
            if (openRun(mob, dir, JET_ESCAPE_ROOM) && mob.startJetBurst(target, null, AttackGeometry.yaw(Vec3.ZERO, dir), false, skill)) return true;
        }
        return false;
    }

    /** Open ground along {@code dir} for {@code blocks}: a path there that arrives. */
    private static boolean openRun(DigimonEntity mob, Vec3 dir, double blocks) {
        Vec3 to = mob.position().add(dir.scale(blocks));
        var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
        return path != null && path.canReach() && path.getNodeCount() <= blocks + 3;
    }

    /** A target that fights up close: worth leaving behind before it lands a blow. */
    private static boolean brawls(LivingEntity target) {
        return target instanceof DigimonEntity other ? other.hasCloseAttack()
                : target instanceof net.minecraft.world.entity.player.Player;
    }
}
