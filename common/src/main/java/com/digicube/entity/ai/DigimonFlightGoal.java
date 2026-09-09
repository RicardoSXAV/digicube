package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
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

/** Short, server-authoritative flights for catching up, clearing obstacles and escaping danger. */
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
        escaping = danger() != null || mob.isOnFire();
        if (owner != null && mob.distanceToSqr(owner) > 36 && mob.getNavigation().isDone()) blockedFollowTicks++;
        else blockedFollowTicks = 0;
        var data = mob.getLocomotion().flight();
        boolean catchUp = owner != null && (mob.distanceToSqr(owner) >= data.startDistance() * data.startDistance()
                || owner.isSprinting() && mob.distanceToSqr(owner) > 25
                || Math.abs(owner.getY() - mob.getY()) > 2 && mob.distanceToSqr(owner) > 36
                || blockedFollowTicks >= 20);
        if (!escaping && !catchUp) return false;
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

    private LivingEntity owner() {
        LivingEntity owner = mob.getOwner();
        return owner != null && owner.isAlive() && !owner.isSpectator() && owner.level() == mob.level() ? owner : null;
    }

    private LivingEntity danger() {
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
