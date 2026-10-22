package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.LookControl;

import java.util.Optional;

/**
 * The head of a Digimon with authored controls: it turns as a head does, gathering into the turn and braking out of it
 * (vanilla's head started and stopped at its full rate, and its pitch was set anew every tick), and only as far off the
 * body as its neck reaches ({@code body.head_turn}): where it wants to look further, the body comes round after it
 * ({@link SteadyBodyControl}). A look that ends holds a moment before the head comes back to the front, so goals handing
 * the look to each other never flick it back and forth. Walking, the head keeps to a narrower cone round its way. In a
 * fight the head turns at vanilla's pace (an aim is no glance); a swimmer keeps vanilla's look, its pitch being its body's
 * in the water (left to its move control there, reset on land).
 */
public final class DigimonLookControl extends LookControl {
    /** Ticks a head takes to gather its full turn rate, and to brake from it. */
    private static final float GATHER = 5;
    /** Degrees a tick a head comes back to the front at most, with nothing to look at. */
    private static final float FRONT_RATE = 6;
    /** Ticks a look that ended still holds before the head comes back to the front. */
    private static final int HOLD = 12;
    /** Share of its reach a walking head looks off its way. */
    private static final float WALKING_REACH = .6F;
    /** The pitch a head looks up or down at most, outside a fight. */
    private static final float PITCH_REACH = 35;

    private final DigimonEntity body;
    private float yawSpeed, pitchSpeed, heldYaw, heldPitch;
    private int hold;
    /** Vanilla's look runs this tick (a fight, the water): it resets the pitch on land as it always did. */
    private boolean vanilla;

    public DigimonLookControl(DigimonEntity mob) {
        super(mob);
        this.body = mob;
    }

    @Override
    public void tick() {
        if (body.constrictionHeadingLocked()) {
            lookAtCooldown = 0;
            mob.yHeadRot = mob.yBodyRot = mob.getYRot();
            mob.setXRot(0);
            yawSpeed = pitchSpeed = 0;
            return;
        }
        if (body.getTarget() != null || body.isAttacking() || body.canSwim()) {
            // An aim follows its target at vanilla's pace; a swimmer's pitch is its body's (its move control's under water).
            float yaw = mob.yHeadRot, pitch = mob.getXRot();
            vanilla = true;
            super.tick();
            vanilla = false;
            yawSpeed = Mth.wrapDegrees(mob.yHeadRot - yaw);
            pitchSpeed = mob.getXRot() - pitch;
            hold = 0;
            return;
        }
        float rate, wantYaw, wantPitch;
        if (lookAtCooldown > 0) {
            lookAtCooldown--;
            wantYaw = getYRotD().orElse(mob.yHeadRot);
            wantPitch = Mth.clamp(getXRotD().orElse(mob.getXRot()), -PITCH_REACH, PITCH_REACH);
            rate = Math.max(1, yMaxRotSpeed);
            heldYaw = wantYaw; heldPitch = wantPitch; hold = HOLD;
        } else if (hold > 0) {
            hold--;
            wantYaw = heldYaw; wantPitch = heldPitch; rate = FRONT_RATE;
        } else {
            wantYaw = mob.yBodyRot; wantPitch = 0; rate = FRONT_RATE;
        }
        // as far off the body as the neck reaches; walking, a narrower cone round the way it goes
        float reach = mob.getMaxHeadYRot();
        if (!mob.getNavigation().isDone()) reach *= WALKING_REACH;
        float aim = mob.yBodyRot + Mth.clamp(Mth.wrapDegrees(wantYaw - mob.yBodyRot), -reach, reach);
        yawSpeed = SteadyBodyControl.ease(yawSpeed, Mth.wrapDegrees(aim - mob.yHeadRot), rate, GATHER);
        mob.yHeadRot += yawSpeed;
        pitchSpeed = SteadyBodyControl.ease(pitchSpeed, wantPitch - mob.getXRot(), Math.max(1, Math.min(rate, xMaxRotAngle)), GATHER);
        mob.setXRot(mob.getXRot() + pitchSpeed);
    }

    @Override
    protected boolean resetXRotOnTick() {
        return vanilla && !mob.isInWater();
    }

    @Override
    protected Optional<Float> getXRotD() {
        return mob.isInWater() ? Optional.empty() : super.getXRotD();
    }
}
