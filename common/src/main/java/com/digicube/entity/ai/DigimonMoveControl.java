package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * Vanilla ground control plus gradual three-dimensional steering for aquatic species, and for a body that steps round on
 * its paws a turn onto its path at its own pace ({@link #steerSteadily}).
 */
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

    /** A body coming round to its path keeps its whole pace within SLOW_FROM degrees of it and none past SLOW_TO. */
    private static final float SLOW_FROM = 30, SLOW_TO = 90;
    /** Share of its pace a serpent keeps however far round it has to come. */
    private static final float SLITHER = .25F;
    /** Degrees a tick a swimmer turns at most, and the share of its pace it glides on while it comes round past SLOW_TO. */
    public static final float SWIM_TURN = 7, GLIDE = .1F;
    /** Share of the pace whose turn just meets a node that a swimmer keeps: it carries some speed on into the turn. */
    private static final float MEET = .7F;
    /**
     * Degrees a tick a swimmer pitches at most, how steeply it swims up or down, and the blocks off to the side from
     * which it steers toward a node (nearer, it swims straight up or down to it).
     */
    private static final float PITCH_TURN = 4, SWIM_PITCH = 80, STEER_FROM = .75F;
    /** Blocks aside a node up on a bank (out of the water) has to be for a swimmer to turn to face it. */
    private static final float BANK_ASIDE = .25F;

    /**
     * A body that steps round on its paws ({@link DigimonEntity#steadyTurnRate}) comes round to its path from where its
     * body faces no faster than its pivot plants them, gathering into the turn and braking out of it (vanilla turned it up
     * to 90 degrees a tick); a hurried one (a navigation pace over 1) up to {@link SteadyBodyControl#BRISK} times as fast.
     * Well off its path it slows, and from SLOW_TO off it steps round on the spot before it sets off, as an animal does.
     */
    private void steerSteadily() {
        double dx = wantedX - mob.getX(), dz = wantedZ - mob.getZ();
        if (dx * dx + dz * dz < 1.0E-6) return;
        float from = mob.yBodyRot, off = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90 - from);
        float step = SteadyBodyControl.ease(mob.bodyTurn(), off, mob.steadyTurnRate() * (float) Mth.clamp(speedModifier, 1, SteadyBodyControl.BRISK));
        mob.setYRot(from + step);
        mob.steered();
        float left = Math.abs(off - step);
        // A serpent turns on the spot no further than its neck lets its head (DigimonEntity.holdNeck): it slithers on
        // through the turn, slowly, its body curling after its head; a node close beside it counts as reached from further
        // off (DigimonAmphibiousNavigation), so it never circles one inside its turn.
        float keep = mob.serpent() != null ? SLITHER : 0;
        mob.setZza(mob.zza * Math.max(keep, (float) Mth.smoothstep(Mth.clamp((SLOW_TO - left) / (SLOW_TO - SLOW_FROM), 0, 1))));
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
            if (groundOperation == Operation.MOVE_TO && mob.steadyTurnRate() > 0 && mob.getLocomotion().travelFacing() == null) steerSteadily();
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
        // A swimmer gathers into a turn and eases out of it, and one well off its way all but stops to come round (a
        // serpent's body curls on the spot): carried on at speed it circled a node inside its turn for ever. A node
        // more above or below it than aside is swum up or down to on the heading it has: the way to a node overhead
        // means nothing, and chasing it spun the body round on the spot, or round and round down to it. A node up on a
        // bank it still turns to face, to press into the bank and climb out: swum up along the bank it waited under it.
        double distance = Math.sqrt(horizontal * horizontal + dy * dy);
        boolean bank = dy > 0 && horizontal > BANK_ASIDE
                && !mob.level().getFluidState(BlockPos.containing(wantedX, wantedY, wantedZ)).is(FluidTags.WATER);
        boolean steers = bank || horizontal > Math.max(STEER_FROM, Math.abs(dy));
        float yaw = steers ? (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90 : mob.getYRot();
        float pitch = Mth.clamp((float) (-Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG), -SWIM_PITCH, SWIM_PITCH);
        mob.setYRot(mob.getYRot() + SteadyBodyControl.ease(mob.bodyTurn(), Mth.wrapDegrees(yaw - mob.getYRot()), mob.swimTurnRate()));
        mob.setXRot(Mth.approachDegrees(mob.getXRot(), pitch, PITCH_TURN));
        mob.yBodyRot = mob.getYRot();
        mob.steered();
        // How far round (up, down or aside) the node still is from where it heads.
        var heading = net.minecraft.world.phys.Vec3.directionFromRotation(mob.getXRot(), mob.getYRot());
        float left = (float) Math.toDegrees(Math.acos(Mth.clamp((heading.x * dx + heading.y * dy + heading.z * dz) / distance, -1, 1)));
        float turn = (float) Mth.smoothstep(Mth.clamp((SLOW_TO - left) / (SLOW_TO - SLOW_FROM), 0, 1));
        // No faster than the circle its turn can draw through the node: a turn wider than that goes round it.
        double fits = Math.min(mob.swimTurnRate(), PITCH_TURN) * Mth.DEG_TO_RAD * distance / (2 * Math.max(1.0E-3, Math.sin(left * Mth.DEG_TO_RAD)));
        turn = Math.max(GLIDE, Math.min(turn, (float) (MEET * fits / Math.max(1.0E-3, mob.getLocomotion().swimSpeed()))));
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
