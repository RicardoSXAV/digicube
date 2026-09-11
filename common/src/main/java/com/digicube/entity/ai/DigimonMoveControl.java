package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;

/** Vanilla ground control plus gradual three-dimensional steering for aquatic species. */
public final class DigimonMoveControl extends MoveControl<DigimonEntity> {
    /** Water momentum retained each tick; acceleration reaches the data-defined cruise speed. */
    public static final double WATER_DRAG = 0.9;

    /**
     * Bind the controller after species data selects aquatic movement.
     * @param mob the shared Digimon entity
     */
    public DigimonMoveControl(DigimonEntity mob) {
        super(mob);
    }

    @Override
    public void tick() {
        // Mob runs controls after customServerAiStep. Stopping navigation alone
        // leaves MOVE_TO/JUMPING queued and can overwrite the cast's heading.
        if (mob.combatControlsLocked()) {
            operation = Operation.WAIT;
            mob.setSpeed(0);
            mob.setXxa(0);
            mob.setYya(0);
            mob.setZza(0);
            return;
        }
        if (!mob.canSwim() || !mob.isInWater()) {
            Operation groundOperation = operation;
            super.tick();
            if (mob.canSwim()) {
                // Mob.setSpeed also sets forward input to that speed. Ground
                // travel multiplies input by speed again, making a deliberately
                // slow species effectively stationary. Keep vanilla path turns
                // and jumping, but apply the configured speed only once.
                if ((groundOperation == Operation.MOVE_TO || groundOperation == Operation.JUMPING)
                        && mob.zza > 0) {
                    mob.setZza(1);
                }
                mob.setYya(0);
                mob.setXRot(Mth.approachDegrees(mob.getXRot(), 0, 4));
            }
            return;
        }
        mob.setXxa(0);
        if (operation != Operation.MOVE_TO || mob.getNavigation().isDone()) {
            mob.setSpeed(0);
            mob.setYya(0);
            mob.setZza(0);
            mob.setXRot(Mth.approachDegrees(mob.getXRot(), 0, 2));
            return;
        }
        double dx = wantedX - mob.getX();
        double dy = wantedY - mob.getY();
        double dz = wantedZ - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal * horizontal + dy * dy < 0.0025) {
            mob.setSpeed(0);
            mob.setYya(0);
            mob.setZza(0);
            return;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
        float pitch = Mth.clamp((float) (-Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG), -65, 65);
        mob.setYRot(rotlerp(mob.getYRot(), yaw, 7));
        mob.setXRot(Mth.approachDegrees(mob.getXRot(), pitch, 3));
        mob.yBodyRot = mob.getYRot();
        float turn = Mth.clamp(1 - Math.abs(Mth.wrapDegrees(yaw - mob.getYRot())) / 120, 0.15F, 1);
        float arrival = (float) Mth.clamp(Math.sqrt(horizontal * horizontal + dy * dy) / 1.2, 0.15, 1);
        // Inputs are unit directions. Applying the speed here exactly once avoids
        // the squared-speed effect of multiplying both acceleration and inputs.
        mob.setSpeed((float) (mob.getLocomotion().swimSpeed() * (1 - WATER_DRAG)
                * Mth.clamp(speedModifier, 0, 1) * turn * arrival));
        mob.setZza(Mth.cos(mob.getXRot() * Mth.DEG_TO_RAD));
        mob.setYya(-Mth.sin(mob.getXRot() * Mth.DEG_TO_RAD));
        if (mob.horizontalCollision && dy > 0
                && !mob.level().getFluidState(BlockPos.containing(wantedX, wantedY, wantedZ)).is(FluidTags.WATER)) {
            mob.getJumpControl().jump();
        }
    }
}
