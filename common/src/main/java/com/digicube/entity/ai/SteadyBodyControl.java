package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.BodyRotationControl;

/**
 * The body of a Digimon that steps round on its paws (a gait with a pivot, {@link DigimonEntity#steadyTurnRate}) turns as
 * an animal does: never faster than its pivot plants its paws, gathering into the turn and braking out of it, and only
 * when it has a reason. On a path its move control turns it onto the way ({@link DigimonMoveControl}); an attack keeps
 * its own facing. Standing, it turns only to face what it looks at, and only once that lies beyond the neck's reach
 * ({@code body.head_turn}) for a moment ({@link #PATIENCE}), or far round ({@link #URGENT}) at once: the head looks round,
 * the body stays (vanilla's body came round after every glance, and after a nudge as small as a push). In a fight it
 * squares up to its enemy. A body pushed keeps its facing; one carried on a facing of its move's own (a roll, a leap)
 * comes round to it. The server turns it, and its facing ({@code yRot}, which every client is sent) is that body: a
 * client draws the body at the facing it is sent, where vanilla's clients worked their own out from the head.
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
    /** Ticks what it looks at stays beyond the neck's reach before a standing body turns to face it. */
    static final int PATIENCE = 12;
    /** Degrees round past which a standing body turns to what it looks at straight away. */
    static final float URGENT = 110;
    /** Ticks after a turn on the spot before another one starts, unless urgent. */
    static final int SETTLE = 20;
    /** Degrees off its enemy past which a standing body in a fight squares up to it at once. */
    static final float SQUARE_UP = 4;
    /** Degrees short of its turn at which a standing body counts as come round. */
    private static final float ARRIVED = 2;

    private final DigimonEntity body;
    /** A turn on the spot under way, to {@link #turnTo}. */
    private boolean turning;
    private float turnTo;
    private int pending, settle;

    public SteadyBodyControl(DigimonEntity body) {
        super(body);
        this.body = body;
    }

    @Override
    public void clientTick() {
        float from = body.yBodyRot;
        if (body.turnsSteadily() && body.level().isClientSide()) {
            // The server's body, sent as its facing (a rider's own client turns it the same way in tickRidden).
            body.yBodyRot = body.getYRot();
        } else if (body.steadyTurnRate() > 0) {
            // An attack keeps its own facing, and a path the move control turned it onto keeps that one.
            if (body.isAttacking() || body.takeSteered()) {
                body.yBodyRot = body.getYRot();
                turning = false;
                pending = 0;
            } else {
                // A body carried along on a facing of its move's own (a roll, a leap) comes round to it; otherwise (standing,
                // pushed, coasting to a stop) it turns only as standingFacing says.
                float set = body.getYRot();
                float want = moving() && Math.abs(Mth.wrapDegrees(set - from)) > .01F ? set : standingFacing(from);
                body.yBodyRot = from + ease(body.bodyTurn(), Mth.wrapDegrees(want - from), body.steadyTurnRate());
                body.setYRot(body.yBodyRot);
            }
        } else super.clientTick();
        body.bodyTurned();
    }

    /** Whether the body moved this tick (vanilla's measure: more than a two-thousandth of a block). */
    private boolean moving() {
        double dx = body.getX() - body.xo, dz = body.getZ() - body.zo;
        return dx * dx + dz * dz > 2.5000003E-7F;
    }

    /** Where a standing body faces this tick: where it faces, or what it looks at once that is out of the neck's reach. */
    private float standingFacing(float from) {
        if (settle > 0) settle--;
        // what it looks at (its look's target, or with none where its head points: a head turned by hand, a body with no AI)
        var look = body.getLookControl();
        float yaw = body.yHeadRot;
        if (look.isLookingAtTarget()) {
            double dx = look.getWantedX() - body.getX(), dz = look.getWantedZ() - body.getZ();
            if (dx * dx + dz * dz > .09) yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
        }
        // in a fight it keeps square to its enemy (an aim, a breath, a charge go where the body faces)
        var enemy = body.getTarget();
        boolean fighting = enemy != null;
        if (fighting) {
            double dx = enemy.getX() - body.getX(), dz = enemy.getZ() - body.getZ();
            if (dx * dx + dz * dz > .09) yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
        }
        float off = Math.abs(Mth.wrapDegrees(yaw - from));
        if (turning) turnTo = yaw;
        else {
            pending = off > body.getMaxHeadYRot() ? pending + 1 : 0;
            if (off > URGENT || fighting && off > SQUARE_UP || pending >= PATIENCE && settle == 0) { turning = true; turnTo = yaw; }
        }
        if (!turning) {
            // a turn cut short (its path dropped half way round) brakes to a stop instead of halting dead
            // (the turn left is what this tick's slower step and the braking after it still cover, so ease takes it down a step)
            float speed = body.bodyTurn(), gather = body.steadyTurnRate() / EASE_TICKS;
            float next = Math.max(0, Math.abs(speed) - gather);
            return next <= 0 ? from : from + Math.signum(speed) * Math.max(next * next / (2 * gather), next);
        }
        if (Math.abs(Mth.wrapDegrees(turnTo - from)) < ARRIVED && Math.abs(body.bodyTurn()) < .5F) {
            turning = false;
            pending = 0;
            settle = fighting ? 0 : SETTLE;
        }
        return turnTo;
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
