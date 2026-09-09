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

    /** Bind the controller to one creature.
     * @param mob the single creature whose lifecycle is managed */
    public AerialRiding(DigimonEntity mob) { this.mob = mob; }
    /** Receive the latest rider input.
     * @param input validated controlling-rider state; refreshes the input timeout */
    public void accept(AerialInput input) { controls = input; lastInput = mob.tickCount; }
    /** Expire missing inputs into a neutral state.
     * @return current controls, or neutral input after ten missing ticks */
    public AerialInput input() { return mob.tickCount - lastInput <= 10 ? controls : AerialInput.NONE; }

    /** Advance server-owned permission, support, stamina and flight phases. */
    public void serverTick() {
        var p = mob.aerialMount();
        if (p == null || mob.level().isClientSide()) return;
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
        if (phase == FlightPhase.GROUNDED) {
            mob.setNoGravity(false);
            nearGroundTicks = 0;
            if (supported) mob.flightReserve().rest();
            if (unsupportedTicks >= 4) {
                mob.setFlightPhase(FlightPhase.APPROACH);
            } else if (supported && launchPress && mob.flightReserve().ready() && clear(new Vec3(0, 1.5, 0))) {
                mob.getNavigation().stop();
                mob.clearFlightLandingRequest();
                mob.setFlightPhase(FlightPhase.TAKEOFF);
            }
            return;
        }
        if (phase == FlightPhase.LANDING) {
            mob.setNoGravity(false);
            if (unsupportedTicks >= 4) mob.setFlightPhase(FlightPhase.APPROACH);
            else if (t >= p.landingTicks()) {
                mob.setFlightPhase(FlightPhase.GROUNDED);
                mob.flightReserve().landed();
            }
            return;
        }
        mob.flightReserve().consume();
        if (phase == FlightPhase.TAKEOFF) {
            if (t >= p.takeoffTicks()) {
                mob.setFlightPhase(FlightPhase.FLYING);
                nearGroundTicks = 0;
            }
        } else if (phase == FlightPhase.FLYING) {
            // Settle close to terrain without a landing key. A brief stable approach prevents flicker.
            double approach = AerialHandling.landingDistance(p, mob.getDeltaMovement());
            boolean near = !in.ascend() && mob.getDeltaMovement().y <= .04
                    && groundDistance(approach + .25) < approach;
            nearGroundTicks = near ? nearGroundTicks + 1 : 0;
            if (mob.flightReserve().mustLand() || nearGroundTicks >= 3) mob.setFlightPhase(FlightPhase.APPROACH);
        } else if (phase == FlightPhase.APPROACH) {
            if (in.ascend() && !mob.flightReserve().mustLand()) {
                mob.setFlightPhase(FlightPhase.FLYING);
                nearGroundTicks = 0;
            } else if (supported) {
                mob.setFlightPhase(FlightPhase.LANDING);
            }
        }
        mob.setNoGravity(mob.isFlyingMovement());
        mob.resetFallDistance();
    }

    /** Turn toward the rider's view at the species turn rate.
     * @param rider controlling player whose look direction sets the turn target */
    public void steer(Player rider) {
        mob.setYRot(Mth.approachDegrees(mob.getYRot(), rider.getYRot(), mob.aerialMount().turnDegrees()));
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
            target = new Vec3(target.x * .3, p.climbSpeed() * power, target.z * .3);
        }
        if (phase == FlightPhase.APPROACH) {
            double down = Math.min(p.descendSpeed(), .07 + groundDistance(3) * .12);
            target = new Vec3(target.x * .3, -down, target.z * .3);
        }
        if (mob.getFlightFuel() <= 0) target = new Vec3(target.x * .3, -p.descendSpeed(), target.z * .3);
        boolean freeFlight = phase == FlightPhase.FLYING && mob.getFlightFuel() > 0 && !in.brake() && rider.zza > 0;
        Vec3 next = freeFlight
                ? AerialHandling.flightStep(p, mob.getDeltaMovement(), target,
                        rider.zza > 0 && rider.getXRot() > 0 && !in.ascend())
                : AerialHandling.step(p, mob.getDeltaMovement(), target,
                        in.brake() || phase == FlightPhase.APPROACH);
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
     * @return distance to solid support, in eighth-block increments, or the limit */
    public double groundDistance(double limit) {
        AABB feet = mob.getBoundingBox();
        for (double d = .125; d <= limit; d += .125) {
            AABB probe = new AABB(feet.minX + .1, feet.minY - d, feet.minZ + .1,
                    feet.maxX - .1, feet.minY - d + .125, feet.maxZ - .1);
            if (mob.level().getBlockCollisions(mob, probe).iterator().hasNext()) return d;
        }
        return limit;
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
