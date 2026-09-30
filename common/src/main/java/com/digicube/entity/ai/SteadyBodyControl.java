package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.BodyRotationControl;

/**
 * The body of a Digimon that steps round on its paws (a gait with a pivot, {@link DigimonEntity#steadyTurnRate}) turns as
 * an animal does: never faster than its pivot plants its paws, gathering into the turn and braking out of it. Vanilla
 * still decides where the body should face (its travel, or after its head as it looks round); the body comes round there
 * at its own pace on the server, and its facing ({@code yRot}, which every client is sent) is that body. A client draws the
 * body at the facing it is sent, where vanilla's clients worked their own out from the head and a turn snapped.
 */
public final class SteadyBodyControl extends BodyRotationControl {
    /**
     * Ticks a turn takes to gather its full rate, and to brake from it: as long as the gait takes to reach its full
     * amplitude, so the pivot keeps one cadence all through the gathering rather than fluttering its paws while its stride
     * grows.
     */
    public static final float EASE_TICKS = 1 / DigimonEntity.AMPLITUDE_EASE;
    /** How much faster than its standing turn a hurried body (a run, a chase, a fight) comes round, at most. */
    public static final float BRISK = 1.5F;
    private final DigimonEntity body;

    public SteadyBodyControl(DigimonEntity body) {
        super(body);
        this.body = body;
    }

    @Override
    public void clientTick() {
        float from = body.yBodyRot;
        super.clientTick();
        if (body.turnsSteadily() && body.level().isClientSide()) {
            // The server's body, sent as its facing (a rider's own client turns it the same way in tickRidden).
            body.yBodyRot = body.getYRot();
        } else if (body.steadyTurnRate() > 0) {
            // An attack keeps its own facing, and a path the move control turned it onto keeps that one.
            if (body.isAttacking() || body.takeSteered()) body.yBodyRot = body.getYRot();
            else {
                body.yBodyRot = from + ease(body.bodyTurn(), Mth.wrapDegrees(body.yBodyRot - from), body.steadyTurnRate());
                body.setYRot(body.yBodyRot);
            }
        }
        body.bodyTurned();
    }

    /**
     * This tick's turn, degrees, for a body that turned {@code speed} degrees last tick and still has {@code remaining} to
     * go, at most {@code rate} a tick: it gathers its turn over {@link #EASE_TICKS} and brakes ahead of the end so it comes
     * to rest there, never past it nor away from it.
     */
    public static float ease(float speed, float remaining, float rate) {
        return ease(speed, remaining, rate, EASE_TICKS);
    }

    /** {@link #ease(float, float, float)} gathering its full rate over {@code ticks}. */
    public static float ease(float speed, float remaining, float rate, float ticks) {
        float gather = rate / ticks;
        float brake = (float) Math.sqrt(2 * gather * Math.abs(remaining));
        float step = Mth.approach(speed, Math.signum(remaining) * Math.min(rate, brake), gather);
        return remaining >= 0 ? Mth.clamp(step, 0, remaining) : Mth.clamp(step, remaining, 0);
    }
}
