package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/** Steering uses the flight navigation's collision-aware waypoints, with gradual turns and arrival braking. */
public final class DigimonFlightMoveControl extends MoveControl<DigimonEntity> {
    public DigimonFlightMoveControl(DigimonEntity mob) { super(mob); }

    @Override public void tick() {
        mob.setSpeed(0);
        mob.setXxa(0);
        mob.setYya(0);
        mob.setZza(0);
        if (!mob.isFlyingMovement()) return;
        Vec3 desired = Vec3.ZERO;
        if (operation == Operation.MOVE_TO) {
            Vec3 offset = new Vec3(wantedX, wantedY, wantedZ).subtract(mob.position());
            double distance = offset.length();
            if (distance > .02) {
                float yaw = (float) (Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90);
                if (offset.horizontalDistanceSqr() > .01) mob.setYRot(rotlerp(mob.getYRot(), yaw, 8));
                mob.yBodyRot = mob.getYRot();
                mob.yHeadRot = mob.getYRot();
                double turn = Mth.clamp(1 - Math.abs(Mth.wrapDegrees(yaw - mob.getYRot())) / 150, .2, 1);
                double speed = mob.getLocomotion().flight().speed() * Math.clamp(speedModifier, 0, 1);
                desired = offset.scale(Math.min(speed * turn, distance * .3) / distance);
            }
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
