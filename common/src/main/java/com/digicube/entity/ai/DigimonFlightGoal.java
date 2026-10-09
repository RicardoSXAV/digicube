package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import com.digicube.digimon.DigimonAttack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Short, server-authoritative flights for catching up, clearing obstacles, escaping danger and fighting on the wing. */
public final class DigimonFlightGoal extends Goal {
    private final DigimonEntity mob;
    private PathNavigation groundNavigation;
    private MoveControl<?> groundControl;
    private Vec3 departure;
    private Vec3 landing;
    private Vec3 previousPosition;
    private int repath;
    private int stalledTicks;
    private int failedPaths;
    private int nextAttempt;
    private int blockedFollowTicks;
    private boolean escaping;
    private boolean finished;
    /** This flight is a sortie: it fights on the wing (the sheet's flight.sortie) rather than catching up or escaping. */
    private boolean sortie;
    /** Where a sortie is going: up to its perch for a shot (and to wait there), or in beside the prey for a blow. */
    private enum Plan { PERCH, STRIKE }
    private Plan plan = Plan.PERCH;
    /**
     * The tick of the sortie's last cast and of its plan, the ticks it has hovered within its perch's reach and the ticks
     * it has had no prey.
     */
    private int lastWingCast, planned, perched, preyless;
    /** This flight began as a landing asked for: it never turns into a sortie. */
    private boolean grounding;
    /**
     * Where the sortie is flying to, where the prey stood then and the plan it was found for; the way out from the prey a
     * swoop came in along (held through the swoop, so its place stays put).
     */
    private Vec3 perch, perchPrey, strikeWay;
    private Plan perchPlan;

    public DigimonFlightGoal(DigimonEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override public boolean canUse() {
        if (!mob.canFly() || !mob.isAlive() || mob.isNoAi() || mob.isPassenger() || mob.isVehicle()
                || mob.isLeashed() || mob.isInWater() || mob.isInLava() || mob.isAttacking()) return false;
        if (mob.needsFlightLanding()) return true;
        if (mob.tickCount < nextAttempt || !mob.onGround() || !mob.flightReserve().ready()) return false;
        LivingEntity owner = owner();
        // Burning, an armed flyer with prey still fights it (the sky puts no fire out); an unarmed one takes off.
        LivingEntity prey = mob.getTarget();
        escaping = danger() != null || mob.isOnFire() && !(mob.hasAttacks() && prey != null && prey.isAlive());
        if (owner != null && mob.distanceToSqr(owner) > 36 && mob.getNavigation().isDone()) blockedFollowTicks++;
        else blockedFollowTicks = 0;
        var data = mob.getLocomotion().flight();
        boolean catchUp = owner != null && (mob.distanceToSqr(owner) >= data.startDistance() * data.startDistance()
                || owner.isSprinting() && mob.distanceToSqr(owner) > 25
                || Math.abs(owner.getY() - mob.getY()) > 2 && mob.distanceToSqr(owner) > 36
                || blockedFollowTicks >= 20);
        sortie = !escaping && sortieWanted();
        if (!escaping && !catchUp && !sortie) return false;
        // Leave room for the raised covers and a full body, not merely a ray through the ceiling.
        double width = Math.max(mob.getBbWidth(), data.clearanceWidth());
        AABB launchSpace = new AABB(mob.getX()-width/2, mob.getY(), mob.getZ()-width/2,
                mob.getX()+width/2, mob.getY()+Math.max(mob.getBbHeight(), data.clearanceHeight())+data.cruiseHeight(), mob.getZ()+width/2);
        if (!loaded(launchSpace) || !mob.level().noCollision(mob, launchSpace)
                || !mob.level().getWorldBorder().isWithinBounds(launchSpace)) {
            nextAttempt = mob.tickCount + 40;
            return false;
        }
        return true;
    }

    @Override public boolean canContinueToUse() {
        return !finished && mob.canFly() && mob.isAlive() && !mob.isNoAi() && !mob.isPassenger()
                && !mob.isVehicle() && !mob.isLeashed() && !mob.isInWater() && !mob.isInLava();
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void start() {
        finished = false;
        landing = null;
        repath = stalledTicks = failedPaths = 0;
        departure = previousPosition = mob.position();
        groundNavigation = mob.getNavigation();
        groundControl = mob.getMoveControl();
        groundNavigation.stop();
        mob.setRunningToOwner(false);
        var navigation = new FlyingPathNavigation(mob, mob.level());
        navigation.setCanFloat(false);
        navigation.setCanOpenDoors(false);
        mob.useFlightNavigation(navigation, new DigimonFlightMoveControl(mob));
        boolean recovering = mob.needsFlightLanding();
        if (recovering) sortie = false;
        grounding = recovering;
        lastWingCast = planned = mob.tickCount;
        perched = preyless = 0;
        plan = Plan.PERCH;
        perch = null;
        mob.clearFlightLandingRequest();
        mob.setFlightPhase(recovering ? FlightPhase.APPROACH : FlightPhase.TAKEOFF);
    }

    @Override public void stop() {
        if (groundNavigation != null) {
            mob.getNavigation().stop();
            mob.useFlightNavigation(groundNavigation, groundControl);
        }
        boolean handedToRider=mob.aerialMount()!=null && mob.getControllingPassenger()!=null && mob.getFlightPhase().airborne();
        if (!handedToRider) {
            mob.setNoGravity(false);
            mob.setFlightPhase(FlightPhase.GROUNDED);
        }
        mob.setSpeed(0);
        mob.setXxa(0);
        mob.setYya(0);
        mob.setZza(0);
        mob.setXRot(0);
        if (!handedToRider && mob.flightReserve() != null) mob.flightReserve().landed();
        mob.refreshSpeciesData();
        nextAttempt = mob.tickCount + 20;
        groundNavigation = null;
    }

    @Override public void tick() {
        var phase = mob.getFlightPhase();
        var data = mob.getLocomotion().flight();
        int elapsed = (int) mob.getFlightPhaseTime(0);
        mob.flightReserve().consume();
        if (phase == FlightPhase.LANDING) {
            mob.getNavigation().stop();
            mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
            if (!mob.onGround()) {
                // A ledge broken during shell closure must resume descent, not remain suspended.
                mob.setFlightPhase(FlightPhase.APPROACH);
                landing = null;
                repath = 0;
                return;
            }
            if (elapsed >= mob.flightLandingTicks()) finished = true;
            return;
        }
        if (phase == FlightPhase.TAKEOFF) {
            if (elapsed >= mob.flightLiftTick()) {
                mob.setNoGravity(true);
                mob.getMoveControl().setWantedPosition(departure.x, departure.y + data.cruiseHeight(), departure.z, .45);
            }
            if (elapsed >= mob.flightTakeoffTicks()) mob.setFlightPhase(FlightPhase.FLYING);
            return;
        }
        mob.setNoGravity(true);
        mob.resetFallDistance();
        if (mob.position().distanceToSqr(previousPosition) < .0025 && !mob.getNavigation().isDone()) stalledTicks++;
        else stalledTicks = Math.max(0, stalledTicks - 2);
        previousPosition = mob.position();
        LivingEntity owner = owner();
        LivingEntity threat = danger();
        if (threat != null || mob.isOnFire()) escaping = true;
        // a flight under way joins a fight on the wing as its prey turns up
        if (phase == FlightPhase.FLYING && !sortie && !escaping && !grounding && sortieWanted()) join();
        if (phase == FlightPhase.FLYING && sortie) {
            fight(data);
            return;
        }
        if (phase == FlightPhase.FLYING) {
            boolean arrived = owner == null || mob.distanceToSqr(owner) < data.stopDistance() * data.stopDistance() + data.cruiseHeight() * data.cruiseHeight();
            boolean settled = escaping ? threat == null && !mob.isOnFire() : arrived;
            if (mob.flightReserve().mustLand() || stalledTicks >= 40 || failedPaths >= 3
                    || elapsed >= data.minimumFlightTicks() && settled) {
                mob.getNavigation().stop();
                mob.setFlightPhase(FlightPhase.APPROACH);
                repath = 0;
                return;
            }
            if (--repath <= 0) {
                repath = 10;
                Vec3 target;
                if (escaping) {
                    Vec3 away = threat == null ? mob.position().subtract(departure).multiply(1, 0, 1)
                            : mob.position().subtract(threat.position()).multiply(1, 0, 1);
                    if (away.lengthSqr() < .01) away = new Vec3(0, 0, 1);
                    target = departure.add(away.normalize().scale(8)).add(0, data.cruiseHeight() + 1, 0);
                } else {
                    if (owner == null) return;
                    target = owner.position().add(0, data.cruiseHeight(), 0);
                }
                // A rookie follows nearby terrain, not a player soaring high above it.
                target = new Vec3(target.x, Math.clamp(target.y, departure.y - 4, departure.y + 6), target.z);
                if (!loaded(boxAt(target)) || !mob.level().getWorldBorder().isWithinBounds(boxAt(target))
                        || !mob.getNavigation().moveTo(target.x, target.y, target.z, 1)) failedPaths++;
                else failedPaths = 0;
            }
        } else if (phase == FlightPhase.APPROACH) {
            // still in the air, prey turning up takes it back up to fight rather than down
            if (!mob.onGround() && !escaping && !grounding && sortieWanted()) {
                join();
                mob.setFlightPhase(FlightPhase.FLYING);
                return;
            }
            if (mob.onGround()) {
                mob.getNavigation().stop();
                mob.setDeltaMovement(0, -.02, 0);
                mob.setNoGravity(false);
                // Preserve wing phase while settling, then begin the authored close at the hover seam.
                if (mob.getFlightLoopTime(0) % mob.flightLoopTicks() < 1 || mob.flightReserve().exhausted()) {
                    mob.setFlightPhase(FlightPhase.LANDING);
                }
                return;
            }
            if (--repath <= 0) {
                repath = 10;
                if (landing == null || !safeLanding(landing)) landing = findLanding();
                if (landing != null) {
                    Vec3 above = landing.add(0, .7, 0);
                    if (!mob.getNavigation().moveTo(above.x, above.y, above.z, .6)) landing = null;
                }
            }
            if (landing != null && mob.position().subtract(landing).horizontalDistanceSqr() < .36) {
                mob.getNavigation().stop();
                mob.getMoveControl().setWantedPosition(landing.x, landing.y - .08, landing.z, .4);
            } else if (landing == null) {
                mob.getNavigation().stop();
                // No valid perch: descend against real collision; exhaustion cannot freeze him in the air.
                mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY() - 1, mob.getZ(), .35);
            }
        }
    }

    /**
     * Whether to take off to fight on the wing: a sheet with a sortie and prey it may attack within the sortie's reach (and
     * a little more, to fly in); a sortie that holds the air needs only something it fights with on the wing, any other a
     * move cast only on the wing ready and the sortie's share of the reserve.
     */
    private boolean sortieWanted() {
        var data = mob.getLocomotion().flight();
        var s = data == null ? null : data.sortie();
        if (s == null || mob.aerialMount() != null) return false;
        LivingEntity prey = mob.getTarget();
        if (prey == null || !prey.isAlive() || !mob.canAttack(prey) || mob.isAllyOf(prey)) return false;
        if (s.hold() ? mob.wingReadyIn(true) == Integer.MAX_VALUE && (s.strike() <= 0 || mob.wingBlow() == null)
                : mob.flightReserve().fraction() < s.reserve() || !mob.wingOnlyReadyWithin(0)) return false;
        double reach = s.to() + (s.hold() ? HOLD_REACH : 6);
        return mob.distanceToSqr(prey) < reach * reach;
    }

    /** A flight turning into a sortie: a fresh plan, from where it is. */
    private void join() {
        sortie = true;
        plan = Plan.PERCH;
        perch = null;
        perched = preyless = 0;
        lastWingCast = planned = mob.tickCount;
        repath = 0;
    }

    /** Ticks a sortie hovers at its perch, steadying, before it casts; blocks from it within which it flies straight to it. */
    private static final int PERCH_TICKS = 3;
    private static final double PERCH_STEER = 3;
    /** Blocks past the sortie's range within which prey draws a sortie that holds the air off the ground. */
    private static final double HOLD_REACH = 12;
    /**
     * Ticks before its blow is ready that a sortie swoops in for it (to wait beside the prey), and the longest a swoop for a
     * ready blow goes on before a ready shot takes it back up.
     */
    private static final int SWOOP_LEAD = 20, SWOOP_TICKS = 40;
    /**
     * Blocks between the body's side and the prey's at which a swoop looks for a place to strike from, best first (the
     * body's own width, not its wings': the boxes may overlap, as a hovering body's open wings reach over its prey).
     */
    private static final double[] STRIKE_GAPS = {.15, .3, .05, .45};
    /** The share of its speed a body keeps each tick as a cast on the wing begins (the wings brake it to a hover). */
    private static final double CAST_BRAKE = .5;

    /**
     * A sortie on the wing. A cast holds it still. Otherwise it plans: a blow it may also cast on the wing that reaches the
     * prey from where it is goes in at once; a ready shot sends it up to its perch over the prey, at the sortie's height and
     * range, in the open and in sight, to cast once steady there; a blow coming ready before the shot sends it swooping in
     * beside the prey at the sortie's {@code strike} height (where the blow reaches) to wait for it; else it waits at its
     * perch. Over its last few blocks it faces the prey. It comes down once the prey has been gone for the sortie's linger
     * (holding the air), or once nothing cast on the wing is ready within it, or the reserve runs low.
     */
    private void fight(com.digicube.digimon.DigimonFlight data) {
        var s = data.sortie();
        LivingEntity prey = mob.getTarget();
        boolean preyOk = prey != null && prey.isAlive() && mob.canAttack(prey) && !mob.isAllyOf(prey);
        // badly hurt or burning, the sortie turns into an escape (the ordinary flight's)
        if (escaping && !mob.isAttacking()) { sortie = false; repath = 0; return; }
        if (mob.isAttacking()) {
            // the cast owns the body: the wings brake it to a hover there, never under a swoop's height over the prey
            mob.getNavigation().stop();
            mob.setDeltaMovement(mob.getDeltaMovement().scale(CAST_BRAKE));
            double floor = preyOk && s.strike() > 0 ? prey.getY() + s.strike() : Double.NEGATIVE_INFINITY;
            if (mob.getY() < floor - .05 && mob.distanceToSqr(prey) < 16) mob.getMoveControl().setWantedPosition(mob.getX(), floor, mob.getZ(), .3);
            lastWingCast = mob.tickCount;
            preyless = 0;
            return;
        }
        preyless = preyOk ? 0 : preyless + 1;
        boolean over = s.hold() ? preyless > s.linger()
                : !(preyOk && mob.wingOnlyReadyWithin(s.linger())) && mob.tickCount - lastWingCast > 6;
        if (mob.flightReserve().mustLand() || over || stalledTicks >= 60 || failedPaths >= 4) {
            sortie = false;
            mob.getNavigation().stop();
            mob.setFlightPhase(FlightPhase.APPROACH);
            repath = 0;
            return;
        }
        if (!preyOk) {
            // between prey it hangs where it is
            mob.getNavigation().stop();
            return;
        }
        mob.getLookControl().setLookAt(prey, 30.0F, 30.0F);
        DigimonAttack blow = s.strike() > 0 ? mob.wingBlow() : null;
        int shotIn = mob.wingReadyIn(true), blowIn = blow == null ? Integer.MAX_VALUE : mob.wingReadyIn(false);
        double near = blow == null ? 0 : blow.range() + 1;
        boolean blowNow = blowIn == 0 && mob.distanceToSqr(prey) < near * near && mob.canAttackFrom(blow, prey, mob.position());
        replan(shotIn, blowIn, blowNow);
        if (perch == null || --repath <= 0 || perchPlan != plan || prey.position().distanceToSqr(perchPrey) > .5) {
            repath = 8;
            perchPlan = plan;
            perchPrey = prey.position();
            perch = plan == Plan.STRIKE ? strikeFor(prey, blow, s) : perchFor(prey, s);
        }
        double away = mob.position().distanceTo(perch);
        if (away > PERCH_STEER) {
            // far off, the flight's paths carry it round what stands between
            if (mob.getNavigation().isDone() || repath == 8) {
                if (!loaded(boxAt(perch)) || !mob.level().getWorldBorder().isWithinBounds(boxAt(perch))
                        || !mob.getNavigation().moveTo(perch.x, perch.y, perch.z, 1)) failedPaths++;
                else failedPaths = 0;
            }
        } else {
            // the last stretch in the open is flown straight, facing the prey, so it settles on the place itself
            mob.getNavigation().stop();
            if (away > .15) mob.getMoveControl().setWantedPosition(perch.x, perch.y, perch.z, plan == Plan.STRIKE ? .85 : .6);
            if (mob.getMoveControl() instanceof DigimonFlightMoveControl steer) steer.face(prey.getEyePosition());
            failedPaths = 0;
        }
        boolean there = away < .8 && mob.getDeltaMovement().length() < .15;
        perched = there ? perched + 1 : 0;
        // a blow as soon as it reaches; a shot from the steadied perch (a blow in reach, without a swoop, goes in too)
        DigimonAttack move = plan == Plan.STRIKE ? blowNow ? blow : null : mob.chooseWingAttack(prey);
        if (move != null && (!com.digicube.digimon.WingCasts.only(move) || plan == Plan.PERCH && perched >= PERCH_TICKS)) {
            mob.getNavigation().stop();
            mob.startAttack(move, prey);
            if (mob.isAttacking()) lastWingCast = mob.tickCount;
        }
    }

    /**
     * The sortie's plan this tick: in for a blow that reaches now; up for a ready shot (a swoop for a ready blow finishing
     * first, for a while); in for a blow coming ready before the shot; else up to wait at the perch.
     */
    private void replan(int shotIn, int blowIn, boolean blowNow) {
        Plan want;
        if (blowNow) want = Plan.STRIKE;
        else if (shotIn == 0) want = plan == Plan.STRIKE && blowIn == 0 && mob.tickCount - planned < SWOOP_TICKS ? Plan.STRIKE : Plan.PERCH;
        else want = blowIn <= SWOOP_LEAD && blowIn < shotIn ? Plan.STRIKE : Plan.PERCH;
        if (want != plan) {
            plan = want;
            planned = mob.tickCount;
            perched = 0;
            strikeWay = null;
        }
    }

    /**
     * Where a swoop hovers to strike: the sortie's {@code strike} height over the prey's feet, beside it on the side the
     * body came in from (turned round it a little at a time), as close as leaves the bodies themselves apart, in the open
     * and where the blow reaches the prey; the first open place when the blow reaches from none.
     */
    private Vec3 strikeFor(LivingEntity prey, DigimonAttack blow, com.digicube.digimon.DigimonFlight.Sortie s) {
        Vec3 at = prey.position();
        Vec3 flat = strikeWay;
        if (flat == null) {
            flat = mob.position().subtract(at).multiply(1, 0, 1);
            double distance = flat.length();
            flat = distance < 1.0E-3 ? Vec3.directionFromRotation(0, mob.getYRot() + 180) : flat.scale(1 / distance);
        }
        double side = (prey.getBbWidth() + mob.getBody().dimensions().width()) / 2;
        Vec3 open = null;
        for (int k = 0; k < 9; k++) {
            Vec3 way = flat.yRot((float) Math.toRadians((k + 1) / 2 * 30 * (k % 2 == 0 ? 1 : -1)));
            for (double gap : STRIKE_GAPS) {
                Vec3 p = at.add(way.scale(side + gap)).add(0, s.strike(), 0);
                if (!loaded(boxAt(p)) || !mob.level().noCollision(mob, boxAt(p))) continue;
                if (open == null) open = p;
                if (mob.canAttackFrom(blow, prey, p)) {
                    strikeWay = way;
                    return p;
                }
            }
        }
        return open != null ? open : at.add(flat.scale(side + STRIKE_GAPS[0])).add(0, s.strike(), 0);
    }

    /**
     * Where a sortie hovers to cast: on the line from the prey out to the body, the sortie's range from it (the nearer end
     * when closer, the further when further), its height over the prey's feet, and turned round the prey a little at a time
     * when that place is shut in.
     */
    private Vec3 perchFor(LivingEntity prey, com.digicube.digimon.DigimonFlight.Sortie s) {
        Vec3 at = prey.position();
        Vec3 flat = mob.position().subtract(at).multiply(1, 0, 1);
        double distance = flat.length();
        flat = distance < 1.0E-3 ? Vec3.directionFromRotation(0, mob.getYRot() + 180) : flat.scale(1 / distance);
        double want = Math.clamp(distance, s.from() + .5, s.to() - .5);
        for (int k = 0; k < 7; k++) {
            double turn = Math.toRadians((k + 1) / 2 * 30 * (k % 2 == 0 ? 1 : -1));
            Vec3 way = flat.yRot((float) turn);
            Vec3 p = at.add(way.scale(want)).add(0, s.height(), 0);
            if (loaded(boxAt(p)) && mob.level().noCollision(mob, boxAt(p).inflate(.25)) && clearSight(p, prey)) return p;
        }
        return at.add(flat.scale(want)).add(0, s.height(), 0);
    }

    private boolean clearSight(Vec3 feet, LivingEntity prey) {
        Vec3 eye = feet.add(0, mob.getBbHeight() * .7, 0), chest = prey.position().add(0, prey.getBbHeight() * .55, 0);
        return mob.level().clip(new net.minecraft.world.level.ClipContext(eye, chest, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mob)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private LivingEntity owner() {
        LivingEntity owner = mob.getOwner();
        return owner != null && !mob.isHolding() && owner.isAlive() && !owner.isSpectator() && owner.level() == mob.level() ? owner : null;
    }

    private LivingEntity danger() {
        // Armed flyers retaliate while healthy; repeated chip damage must not monopolize their AI.
        if (mob.hasAttacks() && mob.getHealth() >= mob.getMaxHealth() * .4F) return null;
        LivingEntity threat = mob.getLastHurtByMob();
        if (threat != null && threat.isAlive() && mob.tickCount - mob.getLastHurtByMobTimestamp() < 100
                && mob.distanceToSqr(threat) < 100 && !mob.isAllyOf(threat)) return threat;
        threat = mob.getTarget();
        return mob.getHealth() < mob.getMaxHealth() * .4F && threat != null && threat.isAlive()
                && mob.distanceToSqr(threat) < 64 && !mob.isAllyOf(threat) ? threat : null;
    }

    private AABB boxAt(Vec3 position) { return mob.getBoundingBox().move(position.subtract(mob.position())); }
    private boolean loaded(AABB box) {
        for (int x = (int) Math.floor(box.minX) >> 4; x <= (int) Math.floor(box.maxX) >> 4; x++) {
            for (int z = (int) Math.floor(box.minZ) >> 4; z <= (int) Math.floor(box.maxZ) >> 4; z++) {
                if (!mob.level().hasChunk(x, z)) return false;
            }
        }
        return true;
    }

    private boolean safeLanding(Vec3 point) {
        AABB box = boxAt(point).inflate(.05, 0, .05);
        if (!loaded(box.expandTowards(0, -1, 0)) || !mob.level().getWorldBorder().isWithinBounds(box)
                || !mob.level().noCollision(mob, box)) return false;
        // Check every column under the feet, including hazards adjoining the central block.
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                BlockPos feet = BlockPos.containing(x, point.y + .01, z);
                if (!mob.level().getFluidState(feet).isEmpty()
                        || !mob.level().getBlockState(feet.below()).isFaceSturdy(mob.level(), feet.below(), Direction.UP)
                        || WalkNodeEvaluator.getPathTypeStatic(mob, feet) != PathType.WALKABLE) return false;
            }
        }
        return true;
    }

    private Vec3 findLanding() {
        var candidates = new java.util.ArrayList<Vec3>();
        BlockPos origin = mob.blockPosition();
        LivingEntity threat = danger();
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            for (int dy = 1; dy >= -12; dy--) {
                Vec3 candidate = Vec3.atBottomCenterOf(origin.offset(dx, dy, dz));
                if (!safeLanding(candidate)) continue;
                if (threat != null && candidate.distanceToSqr(threat.position()) < 16) continue;
                candidates.add(candidate);
                break;
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(p -> mob.position().distanceToSqr(p)));
        // Bound expensive path searches; a nearer valid perch gets the first attempt.
        for (Vec3 candidate : candidates.subList(0, Math.min(4, candidates.size()))) {
            var path = mob.getNavigation().createPath(candidate.x, candidate.y + .7, candidate.z, 0);
            if (path != null && path.canReach()) return candidate;
        }
        return null;
    }
}
