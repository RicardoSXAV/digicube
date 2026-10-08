package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-owned flight lifecycle with vanilla rider prediction and collision. */
public final class AerialRiding {
    private final DigimonEntity mob;
    private AerialInput controls = AerialInput.NONE;
    private int lastInput = -100;
    private boolean wasRidden;
    private boolean jumpHeld;
    private int unsupportedTicks;
    private int nearGroundTicks;
    /**
     * Server: how the body moved over the last tick, read from where it went since the last {@link #serverTick} (a ridden
     * body is moved by its rider's client between ticks, so the tick's own old position has already caught up with it).
     */
    private Vec3 moved = Vec3.ZERO;
    private Vec3 lastPosition;
    /** A step longer than this is a teleport, not flight. */
    private static final double MOST_MOVED = 4;

    /** Bind the controller to one creature.
     * @param mob the single creature whose lifecycle is managed */
    public AerialRiding(DigimonEntity mob) { this.mob = mob; }
    /** Receive the latest rider input.
     * @param input validated controlling-rider state; refreshes the input timeout */
    public void accept(AerialInput input) { controls = input; lastInput = mob.tickCount; }
    /** Expire missing inputs into a neutral state.
     * @return current controls, or neutral input after ten missing ticks */
    public AerialInput input() { return mob.tickCount - lastInput <= 10 ? controls : AerialInput.NONE; }

    /** Agile flight (the view-led dive, boost, slide and roll) for this body's sheet. */
    private boolean agile() { return mob.aerialMount() != null && mob.aerialMount().agility() != null; }

    /** Advance server-owned permission, support, stamina and flight phases. */
    public void serverTick() {
        var p = mob.aerialMount();
        if (p == null || mob.level().isClientSide()) return;
        Vec3 here = mob.position();
        moved = lastPosition == null ? Vec3.ZERO : here.subtract(lastPosition);
        if (moved.lengthSqr() > MOST_MOVED * MOST_MOVED) moved = Vec3.ZERO;
        lastPosition = here;
        boolean ridden = mob.getControllingPassenger() instanceof Player;
        if (!ridden) {
            if (wasRidden && mob.getFlightPhase().airborne()) mob.requestFlightLanding();
            wasRidden = jumpHeld = false;
            nearGroundTicks = unsupportedTicks = 0;
            controls = AerialInput.NONE;
            return;
        }
        wasRidden = true;
        if (!mob.isAlive() || mob.isInWater() || mob.isInLava() || mob.isPassenger()) {
            mob.setNoGravity(false);
            mob.setFlightPhase(FlightPhase.GROUNDED);
            return;
        }
        var in = input();
        var phase = mob.getFlightPhase();
        float t = mob.getFlightPhaseTime(0);
        boolean launchPress = in.ascend() && !jumpHeld;
        jumpHeld = in.ascend();
        // onGround alone can be false on a stationary client-driven vehicle. Probe actual blocks.
        boolean supported = supported();
        unsupportedTicks = supported ? 0 : unsupportedTicks + 1;
        var reserve = mob.flightReserve();
        if (phase == FlightPhase.GROUNDED) {
            mob.setNoGravity(false);
            nearGroundTicks = 0;
            if (supported) reserve.rest(mob.flightRecharge());
            if (unsupportedTicks >= 4) {
                mob.setFlightPhase(FlightPhase.APPROACH);
            } else if (supported && launchPress && reserve.ready() && clear(new Vec3(0, 1.5, 0))) {
                mob.getNavigation().stop();
                mob.clearFlightLandingRequest();
                reserve.spend(reserve.costs().takeoff());
                mob.setFlightPhase(FlightPhase.TAKEOFF);
            }
            return;
        }
        if (phase == FlightPhase.LANDING) {
            mob.setNoGravity(false);
            if (unsupportedTicks >= 4) mob.setFlightPhase(FlightPhase.APPROACH);
            else if (t >= p.landingTicks()) {
                mob.setFlightPhase(FlightPhase.GROUNDED);
                reserve.landed();
            }
            return;
        }
        reserve.consume(agile() ? rate(in, reserve.costs()) : 1);
        if (phase == FlightPhase.TAKEOFF) {
            if (t >= p.takeoffTicks()) {
                mob.setFlightPhase(FlightPhase.FLYING);
                nearGroundTicks = 0;
            }
        } else if (phase == FlightPhase.FLYING) {
            // Settle close to terrain without a landing key. A brief stable approach prevents flicker. An agile flyer
            // skimming the ground at speed never lands: only a slow one settles.
            double approach = AerialHandling.landingDistance(p, agile() ? moved : mob.getDeltaMovement());
            boolean near = !in.ascend() && (agile() ? moved.y <= .04 && moved.horizontalDistance() < SETTLE_PACE : mob.getDeltaMovement().y <= .04)
                    && groundDistance(approach + .25) < approach;
            nearGroundTicks = near ? nearGroundTicks + 1 : 0;
            if (reserve.mustLand() || nearGroundTicks >= 3) mob.setFlightPhase(FlightPhase.APPROACH);
        } else if (phase == FlightPhase.APPROACH) {
            if (in.ascend() && !reserve.mustLand()) {
                mob.setFlightPhase(FlightPhase.FLYING);
                nearGroundTicks = 0;
            } else if (supported) {
                mob.setFlightPhase(FlightPhase.LANDING);
            }
        }
        mob.setNoGravity(mob.isFlyingMovement());
        mob.resetFallDistance();
    }

    /** Horizontal pace (blocks a tick) under which an agile flyer near the ground settles in to land. */
    private static final double SETTLE_PACE = .22;

    /** The reserve an agile flight spends this tick: the boost and the climb more, a dive or a glide less. */
    private double rate(AerialInput in, com.digicube.digimon.DigimonFlight.Costs costs) {
        if (in.boost() && moved.horizontalDistance() > mob.aerialMount().cruiseSpeed() * .5) return costs.boost();
        if (in.ascend()) return costs.climb();
        if (moved.y < -.2 && moved.length() > mob.aerialMount().cruiseSpeed()) return costs.glide();
        return 1;
    }

    /** Turn toward the rider's view at the species turn rate.
     * @param rider controlling player whose look direction sets the turn target */
    public void steer(Player rider) {
        var p = mob.aerialMount();
        float target = rider.getYRot(), rate = p.turnDegrees();
        if (p.agility() != null) {
            // Fast, the body points along its path (the view leads it round); slow, it turns to the view, briskly.
            Vec3 v = mob.getDeltaMovement();
            double speed = v.horizontalDistance();
            float over = (float) Mth.clamp((speed - p.cruiseSpeed()) / p.cruiseSpeed(), 0, 1);
            if (speed > 1.0E-3) target = Mth.rotLerp(over, target, (float) Math.toDegrees(Math.atan2(-v.x, v.z)));
            rate *= (float) (1.5 * Mth.clamp(p.cruiseSpeed() / Math.max(p.cruiseSpeed(), v.length()), p.agility().fastTurn(), 1));
        }
        mob.setYRot(Mth.approachDegrees(mob.getYRot(), target, rate));
        mob.setXRot(0);
        mob.yBodyRot = mob.getYRot();
        mob.yHeadRot = mob.getYRot();
    }

    /** Resolve the next collision-aware motion step.
     * @param rider controlling player
     * @return next velocity, respecting both creature and rider clearance */
    public Vec3 velocity(Player rider) {
        var p = mob.aerialMount();
        var in = input();
        var phase = mob.getFlightPhase();
        Vec3 target = AerialHandling.target(p, rider.zza, rider.xxa, mob.getYRot(), rider.getXRot(), in);
        if (phase == FlightPhase.TAKEOFF) {
            double power = Mth.clamp((mob.getFlightPhaseTime(0) - p.liftTick()) / 5, 0, 1);
            // a running takeoff keeps its way on
            Vec3 run = mob.getDeltaMovement().multiply(1, 0, 1);
            target = new Vec3(target.x * .3 + run.x * .7, p.climbSpeed() * power, target.z * .3 + run.z * .7);
        }
        if (phase == FlightPhase.APPROACH) {
            // Settles in rather than drops: about a second from where an approach begins, touching down at .035 a tick.
            double down = Math.min(p.descendSpeed(), .035 + groundDistance(3) * .07);
            target = new Vec3(target.x * .3, -down, target.z * .3);
        }
        if (mob.getFlightFuel() <= 0) target = new Vec3(target.x * .3, -p.descendSpeed(), target.z * .3);
        Vec3 next;
        if (p.agility() != null && phase == FlightPhase.FLYING && mob.getFlightFuel() > 0 && !in.brake()) {
            boolean boost = in.boost() || rider.isSprinting();
            next = AerialHandling.agile(p, mob.getDeltaMovement(), rider.zza, rider.xxa, rider.getYRot(), rider.getXRot(),
                    in.ascend(), in.descend(), boost);
            next = AerialHandling.skim(next, groundDistance(8));
        } else {
            boolean freeFlight = phase == FlightPhase.FLYING && mob.getFlightFuel() > 0 && !in.brake() && rider.zza > 0;
            next = freeFlight
                    ? AerialHandling.flightStep(p, mob.getDeltaMovement(), target,
                            rider.zza > 0 && rider.getXRot() > 0 && !in.ascend())
                    : AerialHandling.step(p, mob.getDeltaMovement(), target,
                            in.brake() || phase == FlightPhase.APPROACH);
        }
        if (!clear(next)) {
            Vec3 vertical = new Vec3(0, next.y, 0), horizontal = new Vec3(next.x, 0, next.z);
            next = clear(vertical) ? vertical : clear(horizontal) ? horizontal : Vec3.ZERO;
        }
        return next;
    }

    /** Probe foot support independently of the vehicle contact flag.
     * @return whether actual blocks support the creature without upward motion */
    public boolean supported() {
        AABB box = mob.getBoundingBox();
        AABB feet = new AABB(box.minX + .08, box.minY - .08, box.minZ + .08,
                box.maxX - .08, box.minY + .005, box.maxZ - .08);
        return mob.getDeltaMovement().y <= .08 && mob.level().getBlockCollisions(mob, feet).iterator().hasNext();
    }

    /** Measure the remaining descent to solid terrain.
     * @param limit maximum probe distance
     * @return distance from the feet down to the top of the highest supporting collision under them, or the limit */
    public double groundDistance(double limit) {
        AABB feet = mob.getBoundingBox();
        AABB probe = new AABB(feet.minX + .1, feet.minY - limit, feet.minZ + .1, feet.maxX - .1, feet.minY, feet.maxZ - .1);
        double top = Double.NEGATIVE_INFINITY;
        for (var shape : mob.level().getBlockCollisions(mob, probe))
            if (!shape.isEmpty()) top = Math.max(top, shape.max(net.minecraft.core.Direction.Axis.Y));
        return top == Double.NEGATIVE_INFINITY ? limit : Mth.clamp(feet.minY - top, 0, limit);
    }

    /** Check loaded space, borders, and the taller rider's clearance.
     * @param movement proposed translation
     * @return whether loaded space and the rider permit that translation */
    public boolean clear(Vec3 movement) {
        AABB body = mob.getBoundingBox().expandTowards(movement);
        if (!mob.level().getWorldBorder().isWithinBounds(body)) return false;
        for (int x = Mth.floor(body.minX) >> 4; x <= Mth.floor(body.maxX) >> 4; x++)
            for (int z = Mth.floor(body.minZ) >> 4; z <= Mth.floor(body.maxZ) >> 4; z++)
                if (!mob.level().hasChunk(x, z)) return false;
        if (movement.y > 0 && !mob.level().noCollision(mob, body.deflate(.01))) return false;
        var rider = mob.getControllingPassenger();
        return rider == null || !mob.level().getBlockCollisions(rider,
                rider.getBoundingBox().expandTowards(movement).deflate(.02)).iterator().hasNext();
    }
}
