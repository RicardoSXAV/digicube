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

    /** This tick's facing walk (see {@link #walkFacing}); consumed by the next {@link #tick}. */
    private boolean facing;
    private float facingYaw, facingForward, facingLeft;
    private double facingSpeed;

    /**
     * Walk along ({@code forward}, {@code left}), a direction in the body's own frame, while the body turns to
     * {@code yaw}: a thrower side-stepping to its catch or backing off with its weapon raised, watching its enemy.
     * Valid for one tick; the directional gait plays the steps. The pace matches an ordinary move at {@code speed}.
     */
    public void walkFacing(float yaw, double forward, double left, double speed) {
        facing = true; facingYaw = yaw; facingForward = (float) forward; facingLeft = (float) left; facingSpeed = speed;
        operation = Operation.WAIT;
    }

    /** The heading of the path being walked, kept through a jump (vanilla's JUMPING has no wanted position). */
    private float travelYaw;
    /** This control set a sideways input last tick, which vanilla never clears. */
    private boolean sideInput;

    /**
     * A body with {@code locomotion.travel_facing} (Crabmon) keeps walking its path but looks elsewhere: at its enemy
     * close by, side-on when it hurries, otherwise along its travel, turning there at the facing's rate. Vanilla has
     * already put the whole input forward along the path; it is split here in the body's own frame, so the pace is the
     * same and the directional gait plays forward, backward or sideways steps.
     */
    private void faceWhileTravelling(Operation operation, float before) {
        var rule = mob.getLocomotion().travelFacing();
        if (rule == null || mob.zza == 0 && mob.xxa == 0) return;
        if (operation == Operation.MOVE_TO) {
            double dx = wantedX - mob.getX(), dz = wantedZ - mob.getZ();
            if (dx * dx + dz * dz > 1.0E-6) travelYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
            else return;
        }
        float want = travelYaw;
        net.minecraft.world.entity.LivingEntity enemy = mob.getTarget();
        if (enemy != null && enemy.isAlive() && mob.distanceToSqr(enemy) < rule.faceTargetWithin() * rule.faceTargetWithin()) {
            want = (float) (Mth.atan2(enemy.getZ() - mob.getZ(), enemy.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90;
        } else if (speedModifier >= rule.sideOnFrom()) {
            // Whichever flank is nearer leads, so a bend in the path never swings the body round.
            float a = travelYaw + 90, b = travelYaw - 90;
            want = Math.abs(Mth.wrapDegrees(a - before)) <= Math.abs(Mth.wrapDegrees(b - before)) ? a : b;
        }
        float yaw = rotlerp(before, want, rule.turnRate());
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        float input = mob.zza;
        double off = (travelYaw - yaw) * Mth.DEG_TO_RAD;
        mob.setZza((float) (Math.cos(off) * input));
        mob.setXxa((float) (-Math.sin(off) * input));
        sideInput = true;
    }

    @Override
    public void tick() {
        if (facing && !mob.combatControlsLocked() && (!mob.canSwim() || !mob.isInWater())) {
            facing = false;
            mob.setYRot(rotlerp(mob.getYRot(), facingYaw, 24));
            mob.yBodyRot = mob.getYRot();
            float speed = (float) (facingSpeed * mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
            float length = Mth.sqrt(facingForward * facingForward + facingLeft * facingLeft);
            mob.setSpeed(speed);
            if (length < 1.0E-3) { mob.setZza(0); mob.setXxa(0); return; }
            // Mob input carries the speed (as vanilla's MOVE_TO does); travel applies it once more.
            mob.setZza(facingForward / length * speed);
            mob.setXxa(facingLeft / length * speed);
            return;
        }
        facing = false;
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
            float facingBefore = mob.getYRot();
            // Vanilla's moves set only the forward input: a sideways share left from the last tick would drift on.
            if (sideInput) { mob.setXxa(0); sideInput = false; }
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
            if (groundOperation == Operation.MOVE_TO || groundOperation == Operation.JUMPING) faceWhileTravelling(groundOperation, facingBefore);
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
