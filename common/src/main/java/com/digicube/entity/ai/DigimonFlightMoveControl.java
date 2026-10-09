package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Steering uses the flight navigation's collision-aware waypoints, with gradual turns and arrival braking. A point to
 * face ({@link #face}) turns the body to it instead of the way it flies, and lets it fly any way at full speed (a fighter
 * sidling round its prey).
 */
public final class DigimonFlightMoveControl extends MoveControl<DigimonEntity> {
    private Vec3 facing;

    public DigimonFlightMoveControl(DigimonEntity mob) { super(mob); }

    /** For this tick: face {@code point} rather than the way it flies. */
    public void face(Vec3 point) { facing = point; }

    @Override public void tick() {
        mob.setSpeed(0);
        mob.setXxa(0);
        mob.setYya(0);
        mob.setZza(0);
        if (!mob.isFlyingMovement()) { facing = null; return; }
        Vec3 desired = Vec3.ZERO;
        if (operation == Operation.MOVE_TO) {
            Vec3 offset = new Vec3(wantedX, wantedY, wantedZ).subtract(mob.position());
            double distance = offset.length();
            if (distance > .02) {
                float yaw = (float) (Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90);
                if (facing == null && offset.horizontalDistanceSqr() > .01) mob.setYRot(rotlerp(mob.getYRot(), yaw, 8));
                mob.yBodyRot = mob.getYRot();
                mob.yHeadRot = mob.getYRot();
                double turn = facing != null ? 1 : Mth.clamp(1 - Math.abs(Mth.wrapDegrees(yaw - mob.getYRot())) / 150, .2, 1);
                double speed = mob.getLocomotion().flight().speed() * Math.clamp(speedModifier, 0, 1);
                desired = offset.scale(Math.min(speed * turn, distance * .3) / distance);
            }
        }
        if (facing != null) {
            Vec3 to = facing.subtract(mob.position());
            if (to.horizontalDistanceSqr() > .01) mob.setYRot(rotlerp(mob.getYRot(), (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90), 12));
            mob.yBodyRot = mob.yHeadRot = mob.getYRot();
            facing = null;
        }
        operation = Operation.WAIT;
        Vec3 velocity = mob.getDeltaMovement().lerp(desired, .24);
        if (mob.flightReserve().exhausted()) {
            // Fuel exhaustion permits a controlled descent, never an unlimited hover.
            velocity = new Vec3(velocity.x * .94, Math.min(velocity.y, -.12), velocity.z * .94);
        }
        mob.setDeltaMovement(velocity);
        mob.setXRot(0);
    }
}
