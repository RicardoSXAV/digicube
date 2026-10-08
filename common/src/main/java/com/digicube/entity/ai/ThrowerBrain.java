package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonTactics;
import com.digicube.digimon.ThrownAttacks;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.Ballistics;
import com.digicube.entity.BoomerangEntity;
import com.digicube.entity.BoomerangPath;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.IcicleEntity;
import com.digicube.entity.ThrowerState;
import com.digicube.registry.DCEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The fighting mind of a thrower (Mojyamon): a returning bone and a charged icicle, and where to stand for both.
 *
 * <p><b>The bone.</b> A throw is planned, not aimed: over a fan of headings, both curve sides and the whole range of
 * turns, each flight is walked tick by tick against where every enemy will be (their pace, a sidestep if they dodge,
 * none while they are locked in a move of their own), counting a hit per enemy on the way out and one on the way
 * back, and weighed against where it will come home: the catch point must be walkable ground that can be reached
 * before the bone gets there. The best throw goes; once it is away the thrower walks, facing the fight, to the spot
 * where its hand meets the returning bone and takes it out of the air. A bone it cannot reach in time drops; it is
 * picked up when that beats waiting for a new one, and never from under an enemy that fights up close.
 *
 * <p><b>The icicle.</b> The charge is a bet on the target standing still: a snap throw is quick and flat and hard to
 * avoid, a full one slow and heavy and devastating. The expected damage per tick of every charge is weighed against
 * how far the target can move before the spear arrives (nothing while it is frozen, held, blinded or swinging, its
 * pace otherwise, less when it is big). While holding, the thrower walks (never runs) and throws the moment an opening
 * appears (the target commits to a move, closes in, or the charge is enough), leading the arc to where the target will
 * be. It never starts a hold that would still be in its hands when the bone comes home.
 *
 * <p><b>Harder throws.</b> The plan also weighs holding the bone's wind-up (a charged throw reaches further, flies
 * faster and hits harder, at the price of the ticks it is held for) and throwing from a leap (the whole body in it: a
 * third more pace, reach and bite, and a higher start over cover). The icicle is weighed thrown standing or from a
 * leap the same way. A leap is timed so the weapon leaves the hand near the top of it, and is not spent in front of
 * a brawler or more often than {@link #LEAP_EVERY}.
 *
 * <p><b>The rest</b> is the species' tactics: a band of range kept facing the enemy, side steps inside it, backing off
 * a brawler, and the ordinary sidestep of a wind-up or an incoming shot.
 */
public final class ThrowerBrain {
    private static final int PLAN_EVERY = 5, CATCH_EVERY = 3;
    private static final float[] YAW_FAN = {-30, -15, 0, 15, 30};
    private static final int RANGES = 7;
    /** Charges a planned bone throw is weighed at. */
    private static final float[] BONE_CHARGES = {0, .5F, 1};
    /** Ticks from a leap's takeoff to its top, where a thrown weapon should leave the hand, and how high the hand is by then. */
    public static final int LEAP_LEAD = 6;
    private static final double LEAP_HEIGHT = 1.3;
    /** Fewest ticks between two leaps of the AI, and what a leap must win by to be worth the show. */
    private static final int LEAP_EVERY = 160;
    private static final double LEAP_COST = .12;
    /** Score a throw needs before it goes: about one sure hit on the target. */
    private static final double THROW_SCORE = .75;
    private final DigimonEntity mob;
    private final FacingWalk walk;
    private Plan plan;
    private int planAt, catchAt, strafeUntil, strafeSign = 1, stuckTicks;
    private Vec3 catchStand;
    private float catchYaw;
    private boolean catchRun;
    private Vec3 lastPos;
    /** The throw in hand is to leave from a leap; when the last leap was. */
    private boolean leapBone, leapIce;
    private int leapAt = -LEAP_EVERY;
    /** Why the last tick did what it did, for the combat trace. */
    private String doing = "";

    public ThrowerBrain(DigimonEntity mob) { this.mob = mob; this.walk = new FacingWalk(mob); }

    public static boolean handles(DigimonEntity mob) { return mob.thrower().active(); }
    public String doing() { return doing; }

    /** One tick of the fight against {@code target}. {@code dodge} runs the goal's ordinary sidestep; true when it moved. */
    public void tick(LivingEntity target, DigimonTactics tactics, java.util.function.BooleanSupplier dodge) {
        decide(target, tactics, dodge);
        if (TRACE && mob.tickCount % 5 == 0) {
            var bone = mob.thrower().bone();
            double pace = tracedAt == null ? 0 : mob.position().subtract(tracedAt).horizontalDistance() / 5;
            tracedAt = mob.position();
            Constants.LOG.info("[thrower-trace] t={} {} stage={} charge={} pace={} d={} bone={} stand={}", mob.tickCount, doing, mob.thrower().stage(),
                    String.format("%.2f", mob.thrower().charge()), String.format("%.3f", pace),
                    String.format("%.1f", mob.distanceTo(target)), bone == null ? "-" : bone.phase() + "@" + bone.flight() + "/" + bone.path().ticks(),
                    catchStand == null ? "-" : String.format("%.1f", catchStand.subtract(mob.position()).horizontalDistance()) + (catchRun ? " run" : " walk"));
        }
    }
    private static final boolean TRACE = Boolean.parseBoolean(System.getenv("DIGICUBE_THROWER_TRACE"));
    private Vec3 tracedAt;

    private void decide(LivingEntity target, DigimonTactics tactics, java.util.function.BooleanSupplier dodge) {
        var thrower = mob.thrower();
        var bone = thrower.bone();
        mob.getLookControl().setLookAt(target, 30, 30);
        if (thrower.stage() == ThrowerState.Stage.PICKUP) { doing = "pickup"; return; }
        List<LivingEntity> enemies = enemies(target);
        double walkSpeed = mob.getLocomotion().walkSpeed(), runSpeed = Math.max(walkSpeed, mob.getLocomotion().runSpeed());
        float faceTarget = AttackGeometry.yaw(mob.position(), target.position());
        // 1. The charged throw: keep aiming, and let it go at the right moment.
        if (thrower.charging() || thrower.stage() == ThrowerState.Stage.ICE_RELEASE) aimIcicle(target, tactics);
        // A throw that is to leave from a leap takes off so the weapon goes near the top of it.
        leapForThrow(thrower);
        // 2. The bone in the air: be where it comes home.
        if (bone != null && bone.phase() == BoomerangEntity.Phase.FLYING) {
            if (mob.tickCount >= catchAt || catchStand == null) planCatch(bone, walkSpeed);
            if (!thrower.charging() && thrower.stage() == ThrowerState.Stage.NONE) maybeIcicle(target, enemies, tactics, bone);
            if (catchStand != null) {
                double to = catchStand.subtract(mob.position()).horizontalDistance();
                // Close to the spot, turn to meet the bone: the catching fist is ahead and to the right (right is +yaw).
                var path = bone.path();
                boolean coming = path.catchable(path.lengthAt(bone.flight() + 6));
                float yaw = coming ? AttackGeometry.yaw(mob.position(), bone.position()) - 35 : to < 2.5 ? catchYaw : faceTarget;
                double speed = thrower.charging() ? walkSpeed : catchRun ? runSpeed : walkSpeed;
                if (!walk.to(catchStand, speed, yaw)) walk.halt(yaw);
                doing = "catch " + String.format("%.1f", to) + (TRACE ? String.format(" yaw=%.0f body=%.0f node=%s", yaw, mob.getYRot(), walk.steeredAt == null ? "-" : String.format("%.1f,%.1f", walk.steeredAt.x, walk.steeredAt.z)) : "");
                return;
            }
            doing = "bone lost, fighting";
        } else if (bone != null && bone.phase() == BoomerangEntity.Phase.GROUNDED && !thrower.busy()) {
            // 3. The bone on the ground: fetch it when that beats waiting for another.
            if (fetch(bone, enemies, runSpeed)) return;
        } else if (bone != null && bone.phase() == BoomerangEntity.Phase.CATCHING) {
            walk.halt(catchYaw); doing = "catching"; return;
        }
        catchStand = null;
        // 4. Sidestep a wind-up or a shot (not with a spear held high: that one is thrown at it instead).
        if (!thrower.charging() && !thrower.windingUp() && thrower.stage() != ThrowerState.Stage.BONE_RELEASE && dodge.getAsBoolean()) { walk.clear(); doing = "dodge"; return; }
        // 5. The bone in hand: throw when a flight pays, unless it waits for an order. An order throws the first flight
        // that would hit at all.
        if (thrower.boneReady() && mob.aiMayUse(thrower.returning().attack())) {
            if (plan == null || mob.tickCount >= planAt) { plan = planThrow(target, enemies, walkSpeed, runSpeed); planAt = mob.tickCount + PLAN_EVERY; }
            double needed = mob.hasStandingOrder() ? Double.MIN_VALUE : THROW_SCORE;
            if (plan != null && plan.worth >= needed && thrower.startThrow(target, plan.yaw, plan.range, plan.side, plan.lift)) {
                throwYaw = plan.yaw;
                if (plan.charge > 0) thrower.wantCharge(plan.charge);
                leapBone = plan.leap;
                Constants.LOG.info("[thrower] {} throws: score={} range={} side={} charge={}{} lift={} hits={} catch in {}t {}", mob.getSpeciesId().getPath(),
                        String.format("%.2f", plan.score), String.format("%.1f", plan.range), plan.side, String.format("%.1f", plan.charge),
                        plan.leap ? " from a leap" : "", String.format("%.1f", plan.lift), plan.hits, plan.ticks,
                        plan.run ? "running" : "walking");
                catchStand = null;
                plan = null;
            }
        }
        // 6. The icicle.
        if (thrower.stage() == ThrowerState.Stage.NONE) maybeIcicle(target, enemies, tactics, null);
        // 7. Where to stand: plant the feet for the throw (it was planned from here), keep the band otherwise.
        if (thrower.windingUp() || thrower.stage() == ThrowerState.Stage.BONE_RELEASE) {
            walk.halt(throwYaw); doing = thrower.holdingBone() ? "hold the throw " + String.format("%.2f", thrower.charge()) : "throw"; return;
        }
        position(target, tactics, thrower.charging() ? walkSpeed : walkSpeed * 1.15, runSpeed, faceTarget);
    }
    private float throwYaw;

    /**
     * Out of a fight (its target gone while the bone was away): still catch the bone coming home, or fetch it from
     * the ground when that beats growing another. True while it is busy doing so.
     */
    public boolean tickIdle() {
        var thrower = mob.thrower();
        var bone = thrower.bone();
        if (bone == null || thrower.stage() == ThrowerState.Stage.PICKUP) return bone != null;
        double walkSpeed = mob.getLocomotion().walkSpeed();
        switch (bone.phase()) {
            case FLYING -> {
                if (mob.tickCount >= catchAt || catchStand == null) planCatch(bone, walkSpeed);
                if (catchStand == null) return false;
                float yaw = AttackGeometry.yaw(mob.position(), bone.position()) - 35;
                if (!walk.to(catchStand, catchRun ? Math.max(walkSpeed, mob.getLocomotion().runSpeed()) : walkSpeed, yaw)) walk.halt(yaw);
                return true;
            }
            case CATCHING -> { walk.halt(catchYaw); return true; }
            case GROUNDED -> { return !thrower.busy() && fetch(bone, List.of(), Math.max(walkSpeed, mob.getLocomotion().runSpeed())); }
            default -> { return false; }
        }
    }

    // --- enemies ----------------------------------------------------------------------------------------------

    private List<LivingEntity> enemies(LivingEntity target) {
        var result = new ArrayList<LivingEntity>();
        result.add(target);
        var owner = mob.getOwner();
        for (var e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(20),
                e -> e != mob && e != target && e.isAlive() && mob.canAttack(e) && !mob.isAllyOf(e))) {
            boolean hostile = e instanceof DigimonEntity d && (d.getTarget() == mob || owner != null && d.getTarget() == owner
                    || d.battleSide() != 0 && mob.battleSide() != 0 && d.battleSide() != mob.battleSide())
                    || e instanceof net.minecraft.world.entity.Mob m && (m.getTarget() == mob || owner != null && m.getTarget() == owner);
            if (hostile) result.add(e);
        }
        return result;
    }

    /** Where {@code e} will be {@code ticks} from now at its current pace (capped: nobody runs straight forever). */
    private static Vec3 predicted(LivingEntity e, double ticks) {
        Vec3 v = e.position().subtract(e.xOld, e.yOld, e.zOld).multiply(1, 0, 1);
        if (v.lengthSqr() > .09) v = v.normalize().scale(.3);
        return e.position().add(v.scale(Math.min(ticks, 30)));
    }

    /** The target is locked (frozen, held, or in a move of its own) for at least {@code ticks} more. */
    private static boolean locked(LivingEntity e, double ticks) { return lockedFor(e) >= ticks; }

    /** Ticks the target cannot step aside: what is left of a freeze, a hold, or the move it is committed to. */
    private static int lockedFor(LivingEntity e) {
        int locked = 0;
        var frozen = e.getEffect(DCEffects.FROZEN); var held = e.getEffect(DCEffects.CONSTRICTED);
        if (frozen != null) locked = frozen.getDuration();
        if (held != null) locked = Math.max(locked, held.getDuration());
        if (e instanceof DigimonEntity d && d.isAttacking() && d.getActiveAttack() != null && !ThrownAttacks.handles(d.getActiveAttack()))
            locked = Math.max(locked, d.getActiveAttack().durationTicks() - d.currentAttackTick());
        return locked;
    }

    /** Chance an enemy steps out of a projectile's way. */
    private static double evasion(LivingEntity e) {
        if (e instanceof DigimonEntity d) return com.digicube.digimon.ExposedMark.exposed(e) ? 0 : d.tactics().dodgeChance();
        return e instanceof Player ? .35 : .1;
    }

    /** Most an enemy walks a tick (the fight pace of a Digimon, a sprint for a player). */
    private static double pace(LivingEntity e) {
        if (DigimonEntity.impaired(e)) return .04;
        if (e instanceof DigimonEntity d) {
            var g = d.getLocomotion().groundGait();
            double run = g == null ? .2 : Math.max(g.fullSpeed(d.getBody().modelScale()), g.runSpeed(d.getBody().modelScale()));
            return Math.clamp(run, .08, .35);
        }
        return e instanceof Player ? .28 : .15;
    }

    // --- the bone: a rider's throw ------------------------------------------------------------------------------

    /** Heading, far turn and the turn's lift of a rider's throw. */
    public record RiderThrow(float yaw, double range, double lift) {}

    /**
     * Where a rider's bone goes: through the soft target if a heading within 20 degrees of it and a turn inside the reach
     * do that (its box where it will be when the bone gets there), else through the crosshair's spot, else along the
     * crosshair as far as it reaches. The reach is the charge in hand with the body's impulse ({@link ThrowerState#boneReach}):
     * holding the button longer throws further. The far turn climbs or sinks to the aim's height. A flight through it
     * both ways beats one way; then the one closest to the straight line, then the shortest goes. {@code windUp} is
     * what is left of the wind-up if the button were let go now.
     */
    public static RiderThrow riderThrow(DigimonEntity mob, Vec3 aimPoint, LivingEntity target, int side, int windUp, float charge) {
        var thrower = mob.thrower();
        var spec = thrower.returning();
        Vec3 feet = mob.position();
        Vec3 release = spec.releasePoint(charge);
        // A spot on the ground is aimed at what would stand there; a body at its chest.
        Vec3 goal = target != null ? predicted(target, windUp + 6).add(0, target.getBbHeight() * .5, 0) : aimPoint.add(0, .9, 0);
        float direct = AttackGeometry.yaw(ThrowerState.local(feet, release, AttackGeometry.yaw(feet, goal)), goal);
        double distance = ThrowerState.local(feet, release, direct).subtract(goal).horizontalDistance();
        double reach = thrower.boneReach(charge, direct), floor = thrower.groundY();
        double lift = Math.clamp(goal.y - (floor + spec.cruiseHeight()), spec.lift()[0], spec.lift()[1]);
        double pace = spec.mix(spec.chargeSpeed(), charge) * thrower.boneReach(charge, direct) / spec.reach(charge);
        double step = Math.max(.5, (reach - spec.minRange()) / 40);
        RiderThrow best = null;
        double bestScore = 0;
        for (float off = -20; off <= 20; off += 2.5F) for (double range = spec.minRange(); range <= reach + 1.0E-6; range += step) {
            float yaw = direct + off;
            var path = BoomerangPath.of(ThrowerState.local(feet, release, yaw), yaw, range, side, spec, floor, pace, lift);
            boolean out = false, back = false;
            for (int k = 1; k <= path.ticks() && !(out && back); k++) {
                double s = path.lengthAt(k);
                boolean returning = path.returning(s);
                if (returning ? back : out) continue;
                Vec3 b = path.at(s);
                boolean touch;
                if (target != null) {
                    AABB body = target.getBoundingBox().move(predicted(target, windUp + k).subtract(target.position())).inflate(.1);
                    touch = new AABB(b, b).inflate(spec.hitRadius(), spec.hitHeight(), spec.hitRadius()).intersects(body);
                } else touch = b.subtract(goal).horizontalDistance() < spec.hitRadius() * .6;
                if (touch) { if (returning) back = true; else out = true; }
            }
            // Through it on the way out and again on the way back is worth most.
            double value = (out ? 1 : 0) + (back ? spec.returnPower() : 0);
            if (value <= 0) continue;
            double score = value - Math.abs(off) * .01 - range * .004;
            if (best == null || score > bestScore) { best = new RiderThrow(yaw, range, lift); bestScore = score; }
        }
        return best != null ? best : new RiderThrow(direct, Math.clamp(distance + 1.5, spec.minRange(), reach), lift);
    }

    // --- the bone: planning ------------------------------------------------------------------------------------

    /** A throw: {@code worth} is what it wins (the hits, less a catch that is out of reach), {@code score} ranks throws by it and their time. */
    private record Plan(float yaw, double range, int side, double score, double worth, int ticks, boolean run, String hits, float charge,
                        boolean leap, double lift) {}

    /** The best throw from here: every heading of the fan, both curves, turns out to the reach, each charge, standing or from a leap. */
    private Plan planThrow(LivingEntity target, List<LivingEntity> enemies, double walkSpeed, double runSpeed) {
        Plan best = null;
        boolean leapable = canLeap(enemies);
        for (boolean leap : leapable ? new boolean[]{false, true} : new boolean[]{false})
            for (float charge : BONE_CHARGES) {
                var plan = planThrow(target, enemies, walkSpeed, runSpeed, charge, leap);
                if (plan != null && (best == null || plan.score > best.score)) best = plan;
            }
        return best;
    }

    private Plan planThrow(LivingEntity target, List<LivingEntity> enemies, double walkSpeed, double runSpeed, float charge, boolean leap) {
        var spec = mob.thrower().returning();
        int hold = charge > 0 ? spec.holdFor(charge) : -1;
        int windUp = spec.releaseAfter(hold);
        float impulse = leap ? 1 + spec.airBoost() : 1;
        double pace = spec.mix(spec.chargeSpeed(), charge) * impulse, reach = spec.reach(charge) * impulse;
        float power = spec.mix(spec.chargePower(), charge) * com.digicube.digimon.ThrownAttacks.impulsePower(impulse);
        Vec3 feet = mob.position();
        Vec3 aim = predicted(target, windUp + 8);
        float base = AttackGeometry.yaw(feet, aim);
        double floor = feet.y;
        double lift = Math.clamp(AttackGeometry.chest(target.getBoundingBox()).y - (floor + spec.cruiseHeight()), spec.lift()[0], spec.lift()[1]);
        double walkPace = paceOf(walkSpeed), runPace = paceOf(runSpeed);
        // Holding the wind-up and taking off are time the enemy has to see it coming, and a leap is a show of its own.
        double cost = .006 * Math.max(0, hold) + (leap ? LEAP_COST : 0);
        Plan best = null;
        for (int side : new int[]{1, -1}) for (float off : YAW_FAN) for (int i = 0; i < RANGES; i++) {
            float yaw = base + off;
            double range = Mth.lerp(i / (double) (RANGES - 1), spec.minRange(), reach);
            // A charge that would turn the bone short of a tap's reach anyway is not worth holding.
            if (charge > 0 && range <= spec.reach(charge - .5F) * impulse && !locked(target, windUp + 10)) continue;
            Vec3 start = ThrowerState.local(feet, spec.releasePoint(charge), yaw).add(0, leap ? LEAP_HEIGHT : 0, 0);
            var path = BoomerangPath.of(start, yaw, range, side, spec, floor, pace, lift);
            int ticks = path.ticks();
            if (blocked(path)) continue;
            // Home: the spot the thrower's fist meets the bone at the end of its flight.
            Vec3 end = path.catchPoint();
            float faceIn = AttackGeometry.yaw(end, end.subtract(path.tangent(path.total())));
            Vec3 stand = standFor(end, faceIn, spec);
            double need = stand == null ? Double.POSITIVE_INFINITY : stand.subtract(feet).horizontalDistance();
            // In place a contact's lead before it comes home (the catch clip sets and reaches first), after the first
            // steps out of the follow-through (and down from a leap).
            double time = ticks - spec.catchClip().event() - 3 - (leap ? LEAP_LEAD : 0);
            double slack = spec.catchRadius() * .5;   // the fist reaches the bone from anywhere this close to the spot
            boolean walkable = need <= walkPace * time * .85 + slack, runnable = need <= runPace * time * .85 + slack;
            double homeCost = walkable ? 0 : runnable ? .15 : .9;
            // Danger at home: a brawler standing where the catch is.
            for (var e : enemies) if (closeFighter(e) && predicted(e, time).distanceTo(end) < 2.6) homeCost += .35;
            double value = 0;
            var names = new StringBuilder();
            for (var e : enemies) {
                boolean out = false, back = false;
                AABB body = e.getBoundingBox();
                for (int k = 1; k <= ticks && !(out && back); k += 1) {
                    double s = path.lengthAt(k);
                    boolean returning = path.returning(s);
                    if (returning ? back : out) continue;
                    Vec3 b = path.at(s);
                    Vec3 shift = predicted(e, windUp + k).subtract(e.position());
                    AABB box = new AABB(b, b).inflate(spec.hitRadius(), spec.hitHeight(), spec.hitRadius());
                    if (box.intersects(body.move(shift).inflate(.1))) { if (returning) back = true; else out = true; }
                }
                if (!out && !back) continue;
                double sure = locked(e, windUp + 10) ? 1 : 1 - .55 * evasion(e);
                double weight = e == target ? 1 : .8;
                value += weight * sure * power * ((out ? 1 : 0) + (back ? spec.returnPower() : 0));
                names.append(out ? "o" : "").append(back ? "b" : "").append(':').append(e.getType().toShortString()).append(' ');
            }
            if (value <= 0) continue;
            // A quick turnaround is worth a little against a single target.
            double score = value - homeCost - .004 * (ticks + Math.max(0, hold)) - cost;
            if (best == null || score > best.score) best = new Plan(yaw, range, side, score, value - homeCost, ticks, !walkable,
                    names.toString().trim(), charge, leap, lift);
        }
        return best;
    }

    /** A leap is there to take: the species leaps, it stands on the ground, none lately, and no brawler is close enough to punish it. */
    private boolean canLeap(List<LivingEntity> enemies) {
        if (mob.leapPower() <= 0 || !mob.onGround() || mob.isInWater() || mob.tickCount - leapAt < LEAP_EVERY) return false;
        for (var e : enemies) if (closeFighter(e) && !locked(e, 20) && e.distanceTo(mob) < 4.5) return false;
        return true;
    }

    /** Takes off when the weapon in hand is to leave from a leap, so it leaves near the top. */
    private void leapForThrow(ThrowerState thrower) {
        int in = -1;
        if (leapBone) {
            in = thrower.boneReleaseIn();
            if (!thrower.windingUp()) leapBone = false;
        } else if (leapIce) {
            in = iceReleaseIn(thrower);
            if (in < 0) leapIce = false;
        }
        if (in >= 0 && in <= LEAP_LEAD && mob.onGround() && mob.leapForThrow()) {
            leapAt = mob.tickCount; leapBone = false; leapIce = false;
            mob.countSkill("thrower_leap");
        }
    }

    /** Ticks until the icicle leaves the hand, going by the charge the AI wants; -1 without one in hand. */
    private int iceReleaseIn(ThrowerState thrower) {
        var c = thrower.charged();
        return switch (thrower.stage()) {
            case ICE_FORM -> c.form().length() - thrower.stageTick() + c.holdFor(thrower.wantedCharge()) + c.release().event();
            case ICE_HOLD -> Math.max(0, c.holdFor(thrower.wantedCharge()) - thrower.holdTicks()) + c.release().event();
            case ICE_RELEASE -> thrower.stageTick() < c.release().event() ? c.release().event() - thrower.stageTick() : -1;
            default -> -1;
        };
    }

    private boolean closeFighter(LivingEntity e) {
        return e instanceof DigimonEntity d ? d.hasCloseAttack() && !DigimonEntity.impaired(e) : e instanceof Player;
    }

    /**
     * Blocks a tick at a movement speed modifier. Ground travel goes with the square of the modifier (vanilla applies
     * the speed to the input and again to the acceleration); the gait's walk is the pace at the species' walk modifier.
     */
    private double paceOf(double modifier) {
        var g = mob.getLocomotion().groundGait();
        double walk = g == null ? .1 : g.fullSpeed(mob.getBody().modelScale()), ratio = modifier / Math.max(.01, mob.getLocomotion().walkSpeed());
        return walk * ratio * ratio;
    }

    private boolean blocked(BoomerangPath path) {
        var level = mob.level();
        var spec = path.spec();
        for (int k = 2; k <= path.ticks(); k += 2) {
            Vec3 b = path.atTick(k);
            AABB box = new AABB(b, b).inflate(spec.hitRadius() - .12, spec.hitHeight() - .08, spec.hitRadius() - .12);
            if (level.getBlockCollisions(null, box).iterator().hasNext()) return true;
        }
        return false;
    }

    /** Feet position whose catching fist is at {@code point} facing {@code yaw}: on walkable ground, or null. */
    private Vec3 standFor(Vec3 point, float yaw, ThrownAttacks.Returning spec) {
        Vec3 hand = ThrowerState.local(Vec3.ZERO, spec.catchPoint(), yaw);
        Vec3 feet = point.subtract(hand);
        return ground(feet);
    }

    /** The floor under (or just above) {@code feet}, where the body fits; null over a drop, water or lava. */
    private Vec3 ground(Vec3 feet) {
        var level = mob.level();
        BlockPos pos = BlockPos.containing(feet.x, feet.y + 1.2, feet.z);
        for (int i = 0; i < 5; i++, pos = pos.below()) {
            BlockState below = level.getBlockState(pos.below());
            if (!below.getCollisionShape(level, pos.below()).isEmpty() && below.getFluidState().isEmpty()) {
                if (!level.getFluidState(pos).isEmpty()) return null;
                Vec3 at = new Vec3(feet.x, pos.getY() + below.getCollisionShape(level, pos.below()).max(net.minecraft.core.Direction.Axis.Y) - 1, feet.z);
                AABB body = mob.getDimensions(mob.getPose()).makeBoundingBox(at).deflate(.05);
                return level.noCollision(mob, body) ? at : null;
            }
        }
        return null;
    }

    // --- the bone: catching and fetching ---------------------------------------------------------------------

    private void planCatch(BoomerangEntity bone, double walkSpeed) {
        catchAt = mob.tickCount + CATCH_EVERY;
        var path = bone.path();
        var spec = path.spec();
        double walkPace = paceOf(walkSpeed), runPace = paceOf(mob.getLocomotion().runSpeed());
        int now = bone.flight(), from = Math.max(now + 1, path.catchableFrom());
        Vec3 feet = mob.position();
        Vec3 walkStand = null, runStand = null; float walkYaw = 0, runYaw = 0;
        int walkK = -1, runK = -1;
        for (int k = from; k <= path.ticks(); k++) {
            double s = path.lengthAt(k);
            Vec3 b = path.at(s);
            float faceIn = AttackGeometry.yaw(b, b.subtract(path.tangent(s)));
            Vec3 stand = standFor(b, faceIn, spec);
            if (stand == null) continue;
            // The fist must be there when the clip starts, a contact's lead before the bone is.
            double need = stand.subtract(feet).horizontalDistance() - spec.catchRadius() * .6, time = k - now - 1 - spec.catchClip().event();
            if (walkStand == null && need <= walkPace * time * .9) { walkStand = stand; walkYaw = faceIn; walkK = k; }
            if (runStand == null && need <= runPace * time * .9) { runStand = stand; runYaw = faceIn; runK = k; }
            if (walkStand != null) break;
        }
        // Walking there is calmer, but never at the price of the catch: run when only a run arrives in time.
        if (walkStand != null && (runStand == null || walkStand.distanceToSqr(runStand) < 16)) { catchStand = walkStand; catchYaw = walkYaw; catchRun = false; }
        else if (runStand != null) { catchStand = runStand; catchYaw = runYaw; catchRun = true; }
        // Nothing reachable in time any more: keep going for the last spot at a run; the bone may still pass in reach.
        else if (catchStand != null) catchRun = true;
        if (TRACE) Constants.LOG.info("[thrower-trace] plan catch at bone tick {}: walk k={} run k={} stand={} feet={}", now, walkK, runK,
                catchStand == null ? "-" : String.format("%.1f,%.1f", catchStand.x, catchStand.z), String.format("%.1f,%.1f", feet.x, feet.z));
    }

    private boolean fetch(BoomerangEntity bone, List<LivingEntity> enemies, double runSpeed) {
        var spec = mob.thrower().returning();
        double distance = bone.position().subtract(mob.position()).horizontalDistance();
        int regrow = mob.thrower().regrowIn();
        double eta = distance / Math.max(.03, paceOf(runSpeed)) + spec.pickupClip().event();
        if (regrow >= 0 && eta + 10 > regrow) return false;
        for (var e : enemies) if (closeFighter(e) && e.position().distanceTo(bone.position()) < 3.5) return false;
        float yaw = AttackGeometry.yaw(mob.position(), bone.position());
        if (distance <= spec.pickupRadius() - .4 && mob.thrower().beginPickup(bone)) { walk.halt(yaw); doing = "pickup"; return true; }
        walk.to(bone.position(), runSpeed, yaw);
        doing = "fetch " + String.format("%.1f", distance);
        return true;
    }

    // --- the icicle -------------------------------------------------------------------------------------------

    /** Start a throw when one is worth it, snap or charged; never a hold that would still be in hand when the bone comes home. */
    private void maybeIcicle(LivingEntity target, List<LivingEntity> enemies, DigimonTactics tactics, BoomerangEntity inFlight) {
        var thrower = mob.thrower();
        var spec = thrower.charged();
        if (spec == null || !thrower.icicleReady() || !mob.aiMayUse(spec.attack())) return;
        double distance = mob.position().distanceTo(target.position());
        if (distance > spec.attack().range() || !mob.hasLineOfSight(target)) return;
        float best = -1; double bestRate = 0;
        boolean leap = false, leapable = canLeap(enemies);
        // A brawler within a few steps would knock a heavy charge out of the hands before it is thrown.
        boolean pressed = false;
        for (var e : enemies) if (closeFighter(e) && !locked(e, 20) && e.distanceTo(mob) < 5.5) pressed = true;
        for (float c : new float[]{0, .35F, .7F, 1}) {
            double rate = rate(spec, c, target, false) * (pressed && c > ThrowerState.BREAKS_ABOVE ? .35 : 1);
            if (rate > bestRate) { bestRate = rate; best = c; leap = false; }
            // From a leap it flies faster and flatter and hits harder: worth it when it is clearly better.
            double high = leapable ? rate(spec, c, target, true) * (pressed && c > ThrowerState.BREAKS_ABOVE ? .35 : 1) : 0;
            if (high > bestRate * (1 + LEAP_COST * 2)) { bestRate = high; best = c; leap = true; }
        }
        if (best < 0) return;
        if (inFlight != null) {
            // Hands free before the bone comes home: form, hold and the release's own tick, with a few to spare.
            var path = inFlight.path();
            int home = path.catchableFrom() - inFlight.flight();
            int busy = spec.releaseAfter(Math.round(best * spec.chargeTicks())) + 3;
            while (best > 0 && busy > home) { best = Math.max(0, best - .35F); busy = spec.releaseAfter(Math.round(best * spec.chargeTicks())) + 3; }
            if (busy > home) return;
        }
        if (thrower.startIcicle(target, best)) {
            leapIce = leap;
            Constants.LOG.info("[thrower] {} forms an icicle, charging to {}{} (d={} {})", mob.getSpeciesId().getPath(),
                    String.format("%.2f", best), leap ? " for a leap" : "", String.format("%.1f", distance), target.getType().toShortString());
        }
    }

    /** Expected damage a tick of a throw charged to {@code c} against {@code target} from here, standing or from a leap. */
    private double rate(ThrownAttacks.Charged spec, float c, LivingEntity target, boolean leap) {
        double ticksToRelease = spec.releaseAfter(Math.round(c * spec.chargeTicks()));
        float impulse = leap ? 1 + spec.airBoost() : 1;
        Vec3 origin = ThrowerState.local(mob.position(), spec.releasePoint(c), AttackGeometry.yaw(mob.position(), target.position()))
                .add(0, leap ? LEAP_HEIGHT : 0, 0);
        double speed = spec.mix(spec.speed(), c) * impulse, gravity = spec.mix(spec.gravity(), c);
        Vec3 chest = AttackGeometry.chest(target.getBoundingBox());
        Vec3 v = Ballistics.launch(origin, chest, speed, gravity);
        if (v == null) return 0;
        double flight = Ballistics.flightTicks(origin, chest, v);
        // How far the target can be from our lead when the spear arrives.
        double exposure = locked(target, ticksToRelease + flight) ? 0 : pace(target) * flight * (.35 + .65 * evasion(target));
        double reach = spec.mix(spec.halfWidth(), c) + target.getBbWidth() * .5 + .15;
        double sure = reach / (reach + exposure);
        return sure * spec.mix(spec.power(), c) * ThrownAttacks.impulsePower(impulse) / (ticksToRelease + spec.attack().cooldownTicks());
    }

    /** While the icicle is in hand: lead the arc, and throw at the opening. */
    private void aimIcicle(LivingEntity target, DigimonTactics tactics) {
        var thrower = mob.thrower();
        var spec = thrower.charged();
        float c = Math.max(thrower.charge(), .01F);
        // The throw leaves harder from the leap it is planned from (or is already in).
        float impulse = leapIce || thrower.airborne() ? 1 + spec.airBoost() : 1;
        double speed = spec.mix(spec.speed(), c) * impulse, gravity = spec.mix(spec.gravity(), c);
        Vec3 origin = ThrowerState.local(mob.position(), spec.releasePoint(c), mob.getYRot());
        Vec3 chest = AttackGeometry.chest(target.getBoundingBox());
        // Lead: fly to where the target will be after the rest of the release and the flight.
        int toRelease = thrower.stage() == ThrowerState.Stage.ICE_RELEASE ? Math.max(0, spec.release().event() - thrower.stageTick()) : spec.release().event() + 1;
        Vec3 aim = chest;
        for (int pass = 0; pass < 3; pass++) {
            Vec3 v = Ballistics.launch(origin, aim, speed, gravity);
            if (v == null) break;
            double flight = Ballistics.flightTicks(origin, aim, v);
            aim = predicted(target, toRelease + flight).add(chest.subtract(target.position()));
        }
        thrower.aim(aim);
        if (thrower.stage() != ThrowerState.Stage.ICE_HOLD) return;
        double distance = mob.position().distanceTo(target.position());
        // The openings: it commits to a move, it walks into our reach, or it is already as good as it gets.
        double flight = Ballistics.flightTicks(origin, aim, Ballistics.farthest(origin, aim, speed));
        // A lock that outlasts the rest of the charge is waited out; one that ends sooner is used now.
        int lock = lockedFor(target);
        double rest = Math.max(0, thrower.wantedCharge() - thrower.charge()) * spec.chargeTicks() + spec.release().event() + flight;
        if (thrower.charge() >= .35F && lock >= spec.release().event() + flight && lock < rest) { thrower.releaseNow(); mob.countSkill("icicle_opening"); return; }
        if (distance < 3.5 && thrower.charge() >= .2F) { thrower.releaseNow(); mob.countSkill("icicle_point_blank"); return; }
        // Re-weigh as it grows: a target that stopped moving is worth the full charge.
        float want = thrower.wantedCharge();
        if (want < 1 && rate(spec, 1, target, leapIce) > rate(spec, want, target, leapIce) * 1.15) thrower.wantCharge(1);
    }

    // --- standing ----------------------------------------------------------------------------------------------

    /**
     * The band: close in when too far, side-step and pause inside it, give ground to a pursuer but not forever (a
     * thrower stands and trades once it has backed off a while; no endless kiting). With ice in hand it never backs
     * off: it side-steps at its heavy walk and lets the throw answer.
     */
    private void position(LivingEntity target, DigimonTactics tactics, double speed, double runSpeed, float yaw) {
        Vec3 at = mob.position();
        double distance = at.subtract(target.position()).horizontalDistance();
        Vec3 away = at.subtract(target.position()).multiply(1, 0, 1);
        away = away.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0, mob.getYRot() + 180) : away.normalize();
        Vec3 side = new Vec3(-away.z, 0, away.x);
        double min = tactics.holdsRange() ? tactics.holdMin() : 5, max = tactics.holdsRange() ? tactics.holdMax() : 10;
        boolean charging = mob.thrower().charging();
        if (mob.tickCount >= strafeUntil) {
            strafeSign = mob.getRandom().nextBoolean() ? 1 : -1;
            strafeUntil = mob.tickCount + 30 + mob.getRandom().nextInt(30);
            pausing = mob.getRandom().nextFloat() < .4F;
        }
        Vec3 goal;
        if (distance > max) { goal = target.position().add(away.scale(max - 1)); doing = "close in"; retreat = Math.max(0, retreat - 1); }
        else if (distance < min && !charging && retreat < RETREAT_BUDGET) {
            goal = at.add(away.scale(min + 1 - distance)).add(side.scale(strafeSign * 1.5)); doing = "back off"; retreat += 2;
        } else if (pausing && !charging || distance < min) {
            retreat = Math.max(0, retreat - 1);
            // Ice in hand with the enemy close: brace and trade, the throw is the answer. Otherwise a side step.
            if (distance < min && !charging) { goal = at.add(side.scale(strafeSign * 2)); doing = "stand and side-step"; }
            else { walk.halt(yaw); doing = charging ? "brace" : "hold"; lastPos = at; return; }
        } else {
            retreat = Math.max(0, retreat - 1);
            goal = at.add(side.scale(strafeSign * 2.5)).add(away.scale(((min + max) * .5 - distance) * .5)); doing = "strafe";
        }
        // Stuck against something: turn the strafe round.
        if (lastPos != null && at.distanceToSqr(lastPos) < 1.0E-4) { if (++stuckTicks > 10) { strafeSign = -strafeSign; stuckTicks = 0; } }
        else stuckTicks = 0;
        lastPos = at;
        double pace = distance > max + 4 && !charging ? runSpeed : speed;
        if (!walk.to(goal, pace, yaw)) { walk.halt(yaw); strafeSign = -strafeSign; }
    }
    /** Ticks of giving ground the thrower allows itself before it stands (two per tick backing off, one back per tick not). */
    private static final int RETREAT_BUDGET = 30;
    private int retreat;
    private boolean pausing;
}
