package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonTactics;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
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
 * mid-swing. The knobs are the species' {@link DigimonTactics}. With {@code footwork} the band is kept as a fighter keeps
 * it, facing the target: backing off after its moves are spent, circling (now and then crouched, {@code stalk}) until one
 * is ready again, then running back in, and as a combo of blows ends it may get out of reach for a beat ({@code spacing});
 * a blow or shot no duck escapes may be rolled away from ({@code roll_dodge}).
 *
 * <p>Moves the tamer put on manual are never picked here; a standing order picks its move and
 * nothing else ({@link DigimonEntity#orderAttack}). With every move on manual the Digimon only
 * follows the fight, dodging as ever, and waits.
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
    /** The planner of a species that fights with thrown weapons; null for everyone else. */
    private ThrowerBrain brain;
    private int ticksUntilPathRecalc;
    private Vec3 positionedTarget;
    private int positionedAtTick;
    private java.util.List<DigimonAttack> positionedMoves = java.util.List.of();
    /** The evasion in progress: where to, until when; and which wind-up was already answered. */
    private Vec3 dodgeTo;
    private int dodgeUntil, answeredWindUp = Integer.MIN_VALUE, answeredProjectile = -1, answeredStream = Integer.MIN_VALUE,
            strafeUntil, strafeSign = 1, circleAt;
    /** A duck held until a tick (or a roll, until it ends), and a leap clear of a ground wave flown until the body is down again. */
    private boolean ducking, leapingClear;
    private int duckUntil, leapStart, leapUntil;
    /** Ticks a duck is held past the moment the threat is gone, and the most blocks above the low box a blow may pass. */
    private static final int DUCK_EXTRA_TICKS = 3;
    private static final double DUCK_MARGIN = .08;
    /** An aimed burst keeps its aim until this close to its blow: a duck goes after that; any other blow, from this close. */
    private static final int DUCK_AIMED_TICKS = 2, DUCK_TICKS = 4;
    /** A ground wave's aim locks four ticks before it lands; a leap clear of it goes from here on, and lands this far aside. */
    private static final int WAVE_LEAP_TICKS = 3;
    private static final double WAVE_LEAP_REACH = 3.5;
    /** Open ground a dodge roll needs along its heading, blocks. */
    private static final double ROLL_ROOM = 4.0;
    /** Footwork's paces as movement modifiers: backing off and circling are steps, not a run (the crouch slows its own). */
    private static final double BACK_PACE = 1.05, CIRCLE_PACE = .8;
    /** The walk that faces the target while it steps (footwork); made on first use. */
    private FacingWalk facing;
    /** The band's circling is crouched (stalk); the crouch is let go as soon as anything else takes the tick. */
    private boolean stalking;
    /** This stretch of circling is a stalk, and the spot it steps for (footwork). */
    private boolean stalkStretch;
    private Vec3 circleGoal;
    /** The body was striking last tick (a combo's end is the tick it stops), and the beat out of reach lasts until this tick. */
    private boolean wasAttacking;
    private DigimonAttack lastStrike;
    private int spaceUntil;
    /** The combo under way: strikes in it, how many it runs to before the body may break it off, and whether it did. */
    private int comboStrikes, comboLength;
    private boolean comboDecided, comboBroken;
    /** A beat out of reach after a combo, ticks (and up to as many more); a hop back lands this far away. */
    private static final int SPACE_TICKS = 16;
    private static final double HOP_BACK_REACH = 3.2;


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
        if (ducking || stalking) mob.crouch(false);
        ducking = leapingClear = stalking = false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // A stalk is held only while the band's circling keeps it, tick by tick.
        boolean stalked = stalking;
        stalking = false;
        try {
            fight();
        } finally {
            if (stalked && !stalking && !ducking) mob.crouch(false);
        }
    }

    private void fight() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        mob.traceCombat(target);
        DigimonTactics tactics = mob.tactics();
        // A combo of blows up close has just ended (a chained strike starts the same tick and never ends one).
        boolean attacking = mob.isAttacking();
        if (attacking) lastStrike = mob.getActiveAttack();
        if (attacking && !wasAttacking) {
            comboStrikes = 1;
            comboLength = 3 + mob.getRandom().nextInt(2);
            comboDecided = comboBroken = false;
        }
        if (wasAttacking && !attacking) comboEnded(target, tactics);
        wasAttacking = attacking;
        // A thrower plans its throws, catches and footwork itself; it still sidesteps like everyone else.
        if (ThrowerBrain.handles(mob)) {
            if (brain == null) brain = new ThrowerBrain(mob);
            brain.tick(target, tactics, () -> tickDodge(target, tactics));
            return;
        }
        if (mob.shootingOnTheRun()) { tickOnTheRun(target, tactics); return; }
        if (mob.whipWinding()) {
            // A wound whip waits for its moment: the body turns onto the prey and closes in while it is out of reach.
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (mob.whipOutOfReach()) mob.getNavigation().moveTo(target, tactics.fightSpeed() > 0 ? tactics.fightSpeed() : speedModifier);
            else mob.getNavigation().stop();
            dodgeTo = null;
            return;
        }
        if (mob.isAttacking()) {
            // A compound's strike in its chain window is cut short by its next cast, which starts from the pose it is in; a
            // fighter that spaces its combos breaks one off after a few strikes instead (spacing).
            if (!breaksCombo(tactics) && mob.chainStrike(target)) {
                comboStrikes++;
                mob.getNavigation().stop();
                dodgeTo = null;
                return;
            }
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
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (engage(target, tactics)) return;
        if (mob.awaitingOrders()) {
            shadow(target, tactics, speedModifier);
            return;
        }

        // The beat out of reach after a combo: the band is kept, facing the target, before going back in.
        if (mob.tickCount < spaceUntil && !mob.hasStandingOrder() && threatens(target)
                && holdRange(target, tactics, tactics.fightSpeed() > 0 ? tactics.fightSpeed() : mob.getLocomotion().runSpeed())) return;
        DigimonAttack attack = mob.chooseAttack(target);
        if (attack != null) {
            mob.getNavigation().stop();
            mob.startAttack(attack, target);
            return;
        }
        // A leap to pounce from the air flies on its own: no path steers it.
        if (mob.leapingToPounce()) {
            mob.getNavigation().stop();
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
        // A weapon being drawn is as good as ready: the body closes in under the draw for the strikes to come.
        boolean preparing = desiredMoves.stream().noneMatch(move -> mob.isAttackReady(move) || mob.readying(move));
        // Nothing to fire and a band to keep: the band wins over standing in a stance next to an enemy that can hit us.
        // An order closes in for its move instead.
        if (preparing && tactics.holdsRange() && !opening && !pressing && !mob.hasStandingOrder() && threatens(target)
                && holdRange(target, tactics, runSpeed)) return;
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

    /** The species' chance to duck under what its lowered box lets pass; an Exposed body cannot. */
    public static float duckChance(LivingEntity mob, DigimonTactics tactics) {
        return com.digicube.digimon.ExposedMark.exposed(mob) ? 0 : tactics.duckChance();
    }

    /**
     * Keeps a sidestep, a duck or a leap clear going, or starts one against a wind-up or an inbound shot. True while it owns
     * the tick. A blow or a shot that would pass over the body's lowered box (body.crouch) is ducked under standing or
     * walking, or rolled under at a run, timed so it passes through the roll's tucked window ({@code duck_chance}); a ground
     * wave is leapt clear of by a body that leaps ({@code leap_dodge}); anything else, or a failed roll of the dice, is
     * sidestepped ({@code dodge_chance}).
     */
    private boolean tickDodge(LivingEntity target, DigimonTactics tactics) {
        if (ducking) {
            if (mob.isRolling() || mob.tickCount < duckUntil && mob.isLow()) {
                mob.getNavigation().stop();
                mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
                return true;
            }
            ducking = false;
            mob.crouch(false);
        }
        if (leapingClear) {
            // The leap flies on its own: no path steers it until the body is down again.
            if (mob.tickCount < leapUntil && (mob.tickCount - leapStart < 3 || !mob.onGround())) {
                mob.getNavigation().stop();
                return true;
            }
            leapingClear = false;
        }
        if (dodgeTo != null) {
            if (mob.tickCount < dodgeUntil && !mob.getNavigation().isDone()) return true;
            dodgeTo = null;
        }
        if (dodgeChance(mob, tactics) <= 0 && duckChance(mob, tactics) <= 0 && rollDodge(mob, tactics) <= 0
                || !mob.onGround() && !mob.isInWater()) return false;
        int windUp = threateningWindUp(target);
        if (windUp >= 0) {
            var other = (DigimonEntity) target;
            int started = mob.tickCount - other.currentAttackTick();
            if (started == answeredWindUp) return false;
            if (other.currentAttackTick() < tactics.reactionTicks()) return false;
            DigimonAttack attack = other.getActiveAttack();
            // An aimed line (the spike wave) follows us until just before it lands: stepping aside early only
            // moves the aim. Wait, then be in motion across the line when the aim locks.
            boolean aimedLine = attack.kind() == DigimonAttack.Kind.GROUND_WAVE;
            // A body that leaps goes up and out of the line just after the aim has locked: no lead follows it there.
            if (aimedLine && tactics.leapDodge() && !mob.isInWater() && mob.agility().canLeap()) {
                if (windUp > WAVE_LEAP_TICKS) return false;
                answeredWindUp = started;
                if (mob.getRandom().nextFloat() >= dodgeChance(mob, tactics)) return false;
                return leapClear(target, "wind-up of " + attack.id().getPath());
            }
            if (aimedLine && windUp > LATE_DODGE_TICKS) return false;
            // A leap is aimed at its launch: a sidestep before that only moves the landing, one after it escapes a long flight.
            var leap = leap(attack);
            if (leap != null && other.currentAttackTick() < leap.launch()) return false;
            // A blow that would pass over the lowered box: ducked under once its aim has locked and held through its blows, or
            // rolled under at a run so they all fall in the roll's tucked window.
            boolean rolls = !aimedLine && duckChance(mob, tactics) > 0 && mob.getBody().crouch() != null && mob.agility().wouldRoll();
            if (!aimedLine && duckChance(mob, tactics) > 0 && mob.getBody().crouch() != null && blowPassesOver(other, rolls)) {
                int over = Math.max(windUp, attack.motion().activeUntil() - other.currentAttackTick());
                int when = rolls ? rollTiming(windUp, over)
                        : windUp > (attack.kind() == DigimonAttack.Kind.BOX_BURST ? DUCK_AIMED_TICKS : DUCK_TICKS) ? WAIT : NOW;
                if (when == WAIT) return false;
                answeredWindUp = started;
                if (when == NOW && mob.getRandom().nextFloat() < duckChance(mob, tactics)
                        && startDuck(over + DUCK_EXTRA_TICKS, "blow " + attack.id().getPath())) return true;
            }
            answeredWindUp = started;
            // No duck escapes it: rolled away from, aside and back out of its reach.
            if (!aimedLine && mob.getRandom().nextFloat() < rollDodge(mob, tactics)
                    && rollAway(target.position().subtract(mob.position()), "wind-up of " + attack.id().getPath())) return true;
            if (mob.getRandom().nextFloat() >= dodgeChance(mob, tactics)) return false;
            return startDodge(target, windUp + (aimedLine ? LINE_DODGE_EXTRA_TICKS : DODGE_EXTRA_TICKS), "wind-up of " + attack.id().getPath());
        }
        Projectile shot = inboundShot(target);
        if (shot != null) {
            if (shot.getId() == answeredProjectile) return false;
            double ticksToArrive = shot.position().distanceTo(mob.position()) / Math.max(.05, shot.getDeltaMovement().length());
            // A shot that would pass over the lowered box is ducked, or rolled under at a run as it arrives (seen for the
            // body's reaction first).
            boolean rolls = duckChance(mob, tactics) > 0 && mob.getBody().crouch() != null && mob.agility().wouldRoll();
            if (duckChance(mob, tactics) > 0 && mob.getBody().crouch() != null && shotPassesOver(shot, rolls)) {
                if (shot.tickCount < tactics.reactionTicks()) return false;
                // How soon it arrives: the shot closing on the body, its own way counted (a body running at it meets it sooner;
                // one that rolls goes at the roll's pace).
                Vec3 own = rolls ? mob.agility().rollVelocity() : mob.agility().lastMove();
                Vec3 toUs = mob.position().subtract(shot.position()), closing = shot.getDeltaMovement().subtract(own);
                double arrives = toUs.length() / Math.max(.05, toUs.lengthSqr() < 1.0E-8 ? closing.length() : closing.dot(toUs.normalize()));
                int lands = (int) Math.floor(arrives);
                int over = (int) Math.ceil(arrives + (mob.getBbWidth() + shot.getBbWidth()) / Math.max(.05, closing.length()));
                int when = rolls ? rollTiming(lands, over) : NOW;
                if (when == WAIT) return false;
                answeredProjectile = shot.getId();
                if (when == NOW && mob.getRandom().nextFloat() < duckChance(mob, tactics)
                        && startDuck(over + DUCK_EXTRA_TICKS, "shot " + shot.getType().toShortString())) return true;
            }
            answeredProjectile = shot.getId();
            // Rolled out of its line, across it, as a sidestep would be.
            if (mob.getRandom().nextFloat() < rollDodge(mob, tactics)
                    && rollAway(shot.getDeltaMovement().scale(-1), "shot " + shot.getType().toShortString())) return true;
            if (mob.getRandom().nextFloat() >= dodgeChance(mob, tactics)) return false;
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
     * yet; -1 otherwise. Melee, sweeps, lunges, leaps and wraps are dodged on the wind-up; shots in flight.
     */
    private int threateningWindUp(LivingEntity target) {
        if (!(target instanceof DigimonEntity other) || !other.isAttacking()) return -1;
        DigimonAttack attack = other.getActiveAttack();
        if (attack == null || attack.isRanged() && attack.kind() != DigimonAttack.Kind.GROUND_WAVE && leap(attack) == null) return -1;
        // A wrap's strike lands when it reaches its prey, and a whip on its wielder's own plan (DigimonEntity.attackLandsIn).
        int remaining = other.attackLandsIn();
        if (remaining <= 0) return -1;
        double reach = attack.range() + (mob.getBbWidth() + other.getBbWidth()) * .5 + 1;
        return mob.distanceToSqr(other) <= reach * reach ? remaining : -1;
    }

    /** A jumping strike's flight, or null: its landing is where the blow falls. */
    private static com.digicube.digimon.AuthoredAttacks.Leap leap(DigimonAttack attack) {
        var authored = attack == null ? null : com.digicube.digimon.AuthoredAttacks.get(attack);
        return authored == null ? null : authored.leap();
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

    // --- ducking and leaping clear (body.crouch, body.leap: Agility) ---------------------------------------------------

    /** What a roll does with a threat: goes now, waits for it to come closer, or cannot fit it in its tucked window. */
    private static final int NOW = 0, WAIT = 1, NEVER = 2;

    /**
     * When a roll answers a threat that strikes from {@code lands} ticks from now until {@code over}: now, if every tick of it
     * falls in the roll's tucked window started now (a tick to spare at either end, for the order bodies and shots move in);
     * later, if it would from a later start; else never.
     */
    private int rollTiming(int lands, int over) {
        var roll = mob.getBody().crouch().roll();
        int earliest = over - (roll.lowUntil() - 2), latest = lands - (roll.lowFrom() + 1);
        if (earliest > latest || latest < 0) return NEVER;
        return earliest > 0 ? WAIT : NOW;
    }

    /** Ducks (or rolls, at a run) and holds it for {@code ticks}, or a roll until it ends: the box is lowered at once. */
    private boolean startDuck(int ticks, String why) {
        boolean rolled = mob.agility().wouldRoll();
        if (rolled ? !mob.roll() : !mob.crouch(true)) return false;
        ducking = true;
        duckUntil = mob.tickCount + ticks;
        mob.getNavigation().stop();
        dodgeTo = null;
        mob.countDodge();
        mob.countSkill(rolled ? "roll" : "duck");
        Constants.LOG.debug("[tactics] {} {} under the {} ({} ticks)", mob.getType().toShortString(), rolled ? "rolls" : "ducks", why, ticks);
        return true;
    }

    /** The species' chance to roll away from what no duck escapes; an Exposed body, or one whose roll has no speed of its own, cannot. */
    public static float rollDodge(DigimonEntity mob, DigimonTactics tactics) {
        var crouch = mob.getBody().crouch();
        return com.digicube.digimon.ExposedMark.exposed(mob) || crouch == null || crouch.roll() == null || crouch.roll().speed() <= 0
                ? 0 : tactics.rollDodge();
    }

    /**
     * A dodge roll away from a threat coming along {@code toThreat} (from the body toward it): aside and back where there is
     * room, else straight back, else aside and past it. The roll runs on its own until it ends; the body faces its heading.
     */
    private boolean rollAway(Vec3 toThreat, String why) {
        Vec3 line = toThreat.multiply(1, 0, 1);
        if (line.lengthSqr() < .01) line = Vec3.directionFromRotation(0, mob.getYRot());
        line = line.normalize();
        Vec3 side = new Vec3(-line.z, 0, line.x);
        int first = mob.getRandom().nextBoolean() ? 1 : -1;
        Vec3[] headings = {side.scale(first).subtract(line.scale(.7)), side.scale(-first).subtract(line.scale(.7)), line.scale(-1),
                side.scale(first).add(line.scale(.35)), side.scale(-first).add(line.scale(.35))};
        for (Vec3 heading : headings) {
            Vec3 dir = heading.normalize();
            if (!openRun(mob, dir, ROLL_ROOM) || !mob.roll(dir)) continue;
            ducking = true;
            duckUntil = mob.tickCount + mob.getBody().crouch().roll().ticks();
            mob.getNavigation().stop();
            dodgeTo = null;
            mob.countDodge();
            mob.countSkill("roll_dodge");
            Constants.LOG.debug("[tactics] {} rolls away from the {}", mob.getType().toShortString(), why);
            return true;
        }
        return false;
    }

    /** Leaps up and out of a ground wave's line, to whichever side has room to land on. */
    private boolean leapClear(LivingEntity target, String why) {
        Vec3 line = target.position().subtract(mob.position()).multiply(1, 0, 1);
        if (line.lengthSqr() < .01) line = Vec3.directionFromRotation(0, mob.getYRot());
        line = line.normalize();
        Vec3 side = new Vec3(-line.z, 0, line.x);
        int first = mob.getRandom().nextBoolean() ? 1 : -1;
        for (int sign : new int[]{first, -first}) {
            Vec3 to = mob.position().add(side.scale(sign * WAVE_LEAP_REACH));
            var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
            if (path == null || !path.canReach() || path.getNodeCount() > WAVE_LEAP_REACH + 3) continue;
            if (!mob.leap(side.scale(sign), WAVE_LEAP_REACH)) return false;
            mob.getNavigation().stop();
            dodgeTo = null;
            leapingClear = true;
            leapStart = mob.tickCount;
            leapUntil = mob.tickCount + 40;
            mob.countDodge();
            mob.countSkill("leap_dodge");
            Constants.LOG.debug("[tactics] {} leaps clear of the {} (side {})", mob.getType().toShortString(), why, sign);
            return true;
        }
        return false;
    }

    /**
     * Whether a shot, flying on as it flies now, would pass over the body's lowered box (a roll's tucked box, else the
     * crouch's; its hit parts lowered with it) but strike its standing one.
     */
    private boolean shotPassesOver(Projectile shot, boolean tucked) {
        Vec3 at = shot.position(), v = shot.getDeltaMovement();
        double level = v.x * v.x + v.z * v.z;
        double t = level < 1.0E-8 ? 0 : ((mob.getX() - at.x) * v.x + (mob.getZ() - at.z) * v.z) / level;
        if (t < 0) return false;
        double gravity = shot instanceof com.digicube.entity.KineticProjectileEntity ? 0 : shot.getGravity();
        double y = at.y + v.y * t - .5 * gravity * t * t;
        double[] reach = verticalReach(shot);
        return clearsLow(y - reach[0], tucked) && y + reach[1] > mob.getY();
    }

    /** A strike whose lowest point is {@code bottom} (world height) passes over the lowered body and into the standing one. */
    private boolean clearsLow(double bottom, boolean tucked) {
        var agility = mob.agility();
        return bottom > mob.getY() + agility.lowTop(tucked) + DUCK_MARGIN && bottom < mob.getY() + agility.standingTop();
    }

    /** How far a shot's striking volume reaches below and above its position, blocks. */
    private static double[] verticalReach(Projectile shot) {
        Vec3 at = shot.position(), v = shot.getDeltaMovement();
        if (shot instanceof com.digicube.entity.KineticProjectileEntity kinetic && kinetic.definition() != null && v.lengthSqr() > 1.0E-8) {
            var d = kinetic.definition();
            var local = d.projectileMotion() == null ? d.projectileBoxes() : d.projectileMotion().sample(kinetic.effectTick(0));
            double below = 0, above = 0;
            boolean any = false;
            for (var box : local) {
                if (Math.abs(box.x().dot(box.y().cross(box.z()))) <= 1e-10) continue;
                var bounds = com.digicube.entity.KineticGeometry.flightBox(box, at, v).bounds();
                below = Math.max(below, at.y - bounds.minY);
                above = Math.max(above, bounds.maxY - at.y);
                any = true;
            }
            if (any) return new double[]{below, above};
        }
        AABB box = shot.getBoundingBox();
        return new double[]{at.y - box.minY, box.maxY - at.y};
    }

    /**
     * Whether the target's blow under way would pass over the body's lowered box but strike its standing one: an authored
     * blow's volumes through its blows (as aimed now, nearest the body when any come near it), or a fist's or a horn's
     * contact segment. A blow that homes on its prey (a pounce, a wrap), a summoned strike, a leap or a volley is not ducked.
     */
    private boolean blowPassesOver(DigimonEntity other, boolean tucked) {
        DigimonAttack attack = other.getActiveAttack();
        if (attack == null || attack.motion() == null) return false;
        var authored = com.digicube.digimon.AuthoredAttacks.get(attack);
        Vec3 feet = other.position();
        float yaw = other.getYRot();
        AABB near = mob.getBoundingBox().inflate(1.5, 0, 1.5);
        double lowest = Double.MAX_VALUE, lowestNear = Double.MAX_VALUE;
        int from = Math.max(other.currentAttackTick(), attack.motion().activeFrom()), until = attack.motion().activeUntil();
        if (authored != null) {
            if (authored.fires() || authored.anchored() || authored.leap() != null) return false;
            float pitch = other.getAttackAimPitch(1);
            boolean mirrored = other.contactMirrored(attack);
            for (double time = from; time <= until; time += .5) {
                if (!authored.hitWindows().isEmpty() && authored.beat(time) < 0) continue;
                for (var local : authored.sample(time, other.isInWater(), mirrored)) {
                    if (local == null || Math.abs(local.x().dot(local.y().cross(local.z()))) <= 1e-8) continue;
                    AABB box = com.digicube.entity.AuthoredVolumeAttack.aimed(local, attack, time, pitch).world(feet, yaw, 0).bounds();
                    lowest = Math.min(lowest, box.minY);
                    if (box.maxX >= near.minX && box.minX <= near.maxX && box.maxZ >= near.minZ && box.minZ <= near.maxZ)
                        lowestNear = Math.min(lowestNear, box.minY);
                }
            }
        } else if (attack.kind() == DigimonAttack.Kind.FIST || attack.kind() == DigimonAttack.Kind.HORN_RAM) {
            double radius = attack.motion().contactRadius();
            for (double time = Math.max(from, attack.hitTick()); time <= until; time += .5) {
                var frame = attack.motion().sample(time);
                Vec3 base = AttackGeometry.world(feet, frame.hornBase(), yaw), tip = AttackGeometry.world(feet, frame.hornTip(), yaw);
                double bottom = Math.min(base.y, tip.y) - radius;
                lowest = Math.min(lowest, bottom);
                AABB segment = new AABB(base, tip).inflate(radius);
                if (segment.maxX >= near.minX && segment.minX <= near.maxX && segment.maxZ >= near.minZ && segment.minZ <= near.maxZ)
                    lowestNear = Math.min(lowestNear, bottom);
            }
        } else return false;
        double bottom = lowestNear < Double.MAX_VALUE ? lowestNear : lowest;
        return bottom < Double.MAX_VALUE && clearsLow(bottom, tucked);
    }

    // --- range holding -------------------------------------------------------------------

    /** Blocks between the bodies that a Digimon with every move on manual keeps while it waits for orders. */
    private static final double SHADOW_GAP = 4.0;

    /**
     * Every move on manual and no order: the fight is followed, nothing is started. A range holder keeps its band; anyone
     * else stays within {@link #SHADOW_GAP} of the target, facing it, so an order finds it close.
     */
    private void shadow(LivingEntity target, DigimonTactics tactics, double speedModifier) {
        double speed = tactics.fightSpeed() > 0 ? tactics.fightSpeed() : speedModifier;
        if (tactics.holdsRange() && holdRange(target, tactics, Math.max(speed, mob.getLocomotion().runSpeed()))) return;
        double reach = SHADOW_GAP + (mob.getBbWidth() + target.getBbWidth()) * .5;
        if (mob.distanceToSqr(target) <= reach * reach) {
            mob.getNavigation().stop();
            return;
        }
        if (--ticksUntilPathRecalc > 0 && !mob.getNavigation().isDone()) return;
        ticksUntilPathRecalc = adjustedTickDelay(10);
        mob.getNavigation().moveTo(target, speed);
    }

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
        // Footwork steps facing the target: backing steps, side steps.
        boolean steps = tactics.footwork() && !mob.isInWater() && mob.getMoveControl() instanceof DigimonMoveControl;
        float facingYaw = (float) (Mth.atan2(target.getZ() - mob.getZ(), target.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90;
        if (distance < tactics.holdMin()) {
            // A brawler that has closed in is left behind on the jet, where it has room.
            if (tactics.dashEscape() && brawls(target) && jetAway(mob, target, target.position(), "jet_getaway")) return true;
            // Back off past the band's inner edge; if straight back is blocked, angle away.
            double want = tactics.holdMin() + 1 - distance;
            for (float turn : new float[]{0, 45, -45, 90, -90}) {
                Vec3 to = mob.position().add(away.yRot((float) Math.toRadians(turn)).scale(want));
                var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
                if (path == null || !path.canReach()) continue;
                if (steps ? facing().to(to, BACK_PACE, facingYaw) : mob.getNavigation().moveTo(path, speed)) return true;
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
        // A fighter circles anything that can hurt it, stepping sideways and facing it, now and then low.
        if (tactics.strafe() && steps) { circleFacing(tactics, away, distance, facingYaw); return true; }
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

    /**
     * A combo of blows up close has ended: with {@code spacing}'s chance the body gets out of reach for a beat, a hop back
     * where it leaps (facing the target, landing out of its reach), else backing steps, and keeps the band until the beat
     * is over; then it goes back in (a strike from a run, where its moves have one).
     */
    private void comboEnded(LivingEntity target, DigimonTactics tactics) {
        DigimonAttack strike = lastStrike;
        lastStrike = null;
        // A combo broken off spaces for sure; one that ran out of strikes, at spacing's chance.
        if (tactics.spacing() <= 0 || !tactics.footwork() || !tactics.holdsRange() || strike == null || strike.isRanged()
                || mob.hasStandingOrder() || !threatens(target) || !comboBroken && mob.getRandom().nextFloat() >= tactics.spacing()) return;
        double distance = mob.position().subtract(target.position()).horizontalDistance();
        if (distance >= tactics.holdMin() + 1 || Math.abs(target.getY() - mob.getY()) > .6) return;
        spaceUntil = mob.tickCount + SPACE_TICKS + mob.getRandom().nextInt(SPACE_TICKS + 1);
        mob.countSkill("spacing");
        Vec3 away = mob.position().subtract(target.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < .01) away = Vec3.directionFromRotation(0, mob.getYRot() + 180);
        away = away.normalize();
        // A hop back over open ground: the leap flies on its own, the face kept to the target.
        if (mob.getRandom().nextBoolean() && mob.agility().canLeap() && openRun(mob, away, HOP_BACK_REACH)
                && mob.leap(away, HOP_BACK_REACH)) {
            mob.getNavigation().stop();
            dodgeTo = null;
            leapingClear = true;
            leapStart = mob.tickCount;
            leapUntil = mob.tickCount + 30;
            mob.countSkill("hop_back");
            Constants.LOG.debug("[tactics] {} hops back out of reach", mob.getType().toShortString());
        }
    }

    /**
     * Whether the combo under way stops chaining here: once it has run to its length (three or four strikes), a fighter that
     * spaces its combos breaks it off at {@code spacing}'s chance, decided once a combo.
     */
    private boolean breaksCombo(DigimonTactics tactics) {
        if (tactics.spacing() <= 0 || !tactics.footwork() || !tactics.holdsRange() || mob.hasStandingOrder() || comboStrikes < comboLength) return false;
        if (!comboDecided) {
            comboDecided = true;
            comboBroken = mob.getRandom().nextFloat() < tactics.spacing();
        }
        return comboBroken;
    }

    /** The walk that faces the target while it steps. */
    private FacingWalk facing() { return facing != null ? facing : (facing = new FacingWalk(mob)); }

    /**
     * Footwork in the band: side steps round the target, facing it, drifting back to the band's middle and changing way now
     * and then; a stretch of it is crouched ({@code stalk}): low, slow and ready to spring back in when a move is.
     */
    private void circleFacing(DigimonTactics tactics, Vec3 away, double distance, float facingYaw) {
        if (mob.tickCount >= strafeUntil) {
            strafeSign = mob.getRandom().nextBoolean() ? 1 : -1;
            strafeUntil = mob.tickCount + STRAFE_TICKS + mob.getRandom().nextInt(STRAFE_TICKS);
            boolean was = stalkStretch;
            stalkStretch = mob.getBody().crouch() != null && mob.getRandom().nextFloat() < tactics.stalk();
            if (stalkStretch && !was) mob.countSkill("stalk");
            circleGoal = null;
        }
        if (stalkStretch && mob.onGround() && mob.crouch(true)) stalking = true;
        if (circleGoal == null || mob.tickCount >= circleAt) {
            circleAt = mob.tickCount + CIRCLE_TICKS;
            double drift = Math.clamp(((tactics.holdMin() + tactics.holdMax()) * .5 - distance) * .5, -1.5, 1.5);
            circleGoal = mob.position().add(new Vec3(-away.z, 0, away.x).scale(strafeSign * 2.5)).add(away.scale(drift));
        }
        if (!facing().to(circleGoal, CIRCLE_PACE, facingYaw)) {
            // There, or no way on: the other way round.
            strafeSign = -strafeSign;
            circleGoal = null;
            facing().halt(facingYaw);
        }
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
        if (tactics.dashEngage() <= 0 || !mob.aiMayUse(mob.jetMove())
                || !(com.digicube.digimon.ExposedMark.exposed(target) || DigimonEntity.impaired(target))
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
