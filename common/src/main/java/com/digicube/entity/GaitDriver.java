package com.digicube.entity;

import com.digicube.digimon.DigimonGait;
import net.minecraft.util.Mth;

/**
 * Client: how a ground gait plays what a body is seen to do: the gait clips' clock (phase), their amplitude, the run's
 * share, the directional shares (ahead, back, left, right), the pivot's share and the run lattice's column. It holds only
 * its own numbers and is fed once a tick from what the client sees of the body ({@link Step}), so a headless check can feed
 * it a server's trace and read back exactly what a client would draw.
 *
 * <p>The legs play where the body is seen to go, never what its velocity says: a body pushing at a wall or nudged by a
 * neighbour has a velocity and goes nowhere, and stepping then was a treadmill. The move is smoothed over a couple of ticks
 * ({@link #SMOOTH}), and under {@link #STILL} of the walk's pace the body stands (a drift that small is taken up in the
 * legs, not stepped). Its way in the body's frame plays the walk straight ahead within {@link #AHEAD} degrees of the body
 * and straight back within as much of its tail, the sidesteps only beyond (a body walking a bend is never a crab), and only
 * from {@link #SIDE_STILL} of the walk's pace (a tamer's nudge slides it, it does not scuttle). A turn
 * slower than {@link #TURN_STILL} degrees a tick is taken up in the hips, not stepped round.
 */
public final class GaitDriver {
    /** Share of its full amplitude the ground gait gains or loses a tick. */
    public static final float AMPLITUDE_EASE = .125F;
    /** Blocks in a tick past which a ground body's move is a teleport, not a stride: its gait's phase skips it. */
    static final double GAIT_SNAP = 3;
    /** Share of the walk's full pace from which a turning body walks its turn instead of pivoting on the spot. */
    static final double PIVOT_WALK = .5;
    /** Share of the new move the smoothed one takes a tick. */
    static final double SMOOTH = .5;
    /** Share of the walk's full pace under which the body stands. */
    static final double STILL = .12;
    /** Share of the walk's full pace under which a way that plays sidesteps does not step at all (a nudge, a creep). */
    static final double SIDE_STILL = .3;
    /** Degrees off straight ahead (and off straight back) its way plays the walk (the walk back) alone. */
    static final double AHEAD = 30;
    /** Degrees a tick of turn the hips take up without a step. */
    static final float TURN_STILL = .6F;

    /**
     * One tick of what the client sees of the body.
     * @param dx              its move along x this tick, blocks
     * @param dz              its move along z this tick, blocks
     * @param legsX           where its legs take it along x (its move, but for a slip on ice: {@code IceSlip})
     * @param legsZ           where its legs take it along z
     * @param slipping        it is slipping on ice: the gait plays its legs, not its slide
     * @param bodyYaw         the body's facing, degrees
     * @param turned          degrees the body turned this tick
     * @param onGround        it ended the tick on the ground
     * @param supported       its travel clip plays: on the ground, or a hovering body out of the water
     * @param swimming        it swims (its swimming pose takes over from the gait)
     * @param holdStride      a ridden leap: the stride is held mid-air instead of settling into the standing pose
     * @param bound           it is wrapped up by a constriction: its legs do not walk
     */
    public record Step(double dx, double dz, double legsX, double legsZ, boolean slipping,
                       float bodyYaw, float turned, boolean onGround, boolean supported, boolean swimming, boolean holdStride,
                       boolean bound) {}

    private float phase, previousPhase, amount, previousAmount, run, previousRun;
    private final float[] shares = {1, 0, 0, 0}, previousShares = {1, 0, 0, 0};
    /** A gait that changes all at once is in its run (DigimonGait.runFrom). */
    private boolean running;
    /** The share of the gait a turn on the spot takes, positive turning right (DigimonGait.pivotReach). */
    private float pivotTurn, previousPivotTurn;
    /** The run lattice's column, the pace against the run's own (DigimonGait.runShare); -1 for a run that is one clip. */
    private float runShare = -1, previousRunShare = -1;
    /** The move smoothed over the last ticks ({@link #SMOOTH}), blocks a tick along the world's axes. */
    private double moveX, moveZ;

    /** Start of a client tick: this tick's values become the previous ones the frames between ticks lerp from. */
    public void beginTick() {
        previousPhase = phase;
        previousAmount = amount;
        previousRun = run;
    }

    /** Advance the clock by {@code step} native ticks (a flier walking on the ground, a swimmer's paws on land). */
    public void advancePhase(float step) { phase += step; }

    /** Play this tick's {@link Step} on {@code gait}. */
    public void tick(DigimonGait gait, float modelScale, Step s) {
        double horizontalTravel = Math.sqrt(s.dx() * s.dx() + s.dz() * s.dz());
        // On ice the gait plays where the legs take the body, not where it slides.
        double mx = s.slipping() ? s.legsX() : s.dx(), mz = s.slipping() ? s.legsZ() : s.dz();
        if (s.bound() || horizontalTravel >= GAIT_SNAP) mx = mz = 0;
        moveX = Mth.lerp(SMOOTH, moveX, mx);
        moveZ = Mth.lerp(SMOOTH, moveZ, mz);
        double pace = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (pace < STILL * gait.fullSpeed(modelScale)) pace = 0;
        System.arraycopy(shares, 0, previousShares, 0, 4);
        DigimonGait.Drive drive = gait.drive(pace, 0, modelScale, running);
        if (gait.directional() && pace > 0) {
            // Which way the body moves in its own frame decides which planted clips play, and how fast the shared phase runs.
            double yaw = s.bodyYaw() * Mth.DEG_TO_RAD;
            double forward = -moveX * Math.sin(yaw) + moveZ * Math.cos(yaw), left = moveX * Math.cos(yaw) + moveZ * Math.sin(yaw);
            double way = Math.toRadians(settled(Math.toDegrees(Math.atan2(left, forward))));
            if (Math.abs(Math.sin(way)) > .5 && pace < SIDE_STILL * gait.fullSpeed(modelScale)) pace = 0;
            drive = gait.drive(Math.cos(way) * pace, Math.sin(way) * pace, modelScale, running);
            for (int i = 0; i < 4; i++) shares[i] = Mth.approach(shares[i], (float) drive.shares()[i], .2F);
        }
        // The run is held through a leap and a stumble: only the ground's own pace changes the gait.
        if (s.onGround()) running = drive.running();
        run = Mth.approach(run, drive.run(), gait.runEase());
        double travel = drive.travel(run);
        // Turning on the spot the paws step round the body's centre (a gait with pivot_reach): the turn is paid on the phase
        // with the travel, and its share of the two goes to the model, signed by the turn's way. Only a body all but standing
        // pivots; one walking turns along its stride (the two step on different beats). A pivot on the walk's own beats
        // (pivotWalk) mixes into the walk at any pace: the body walks its turn in an arc.
        float turned = Math.abs(s.turned()) <= TURN_STILL ? 0 : s.turned() - Math.signum(s.turned()) * TURN_STILL;
        double pivot = s.onGround() && !s.swimming() ? gait.pivotTravel(turned, run)
                * (gait.pivotWalk() ? 1 : Mth.clamp(1 - travel / (PIVOT_WALK * gait.fullSpeed(modelScale)), 0, 1)) : 0;
        previousPivotTurn = pivotTurn;
        pivotTurn = Mth.approach(pivotTurn, pivot < 1.0E-4 ? 0 : (float) (Math.signum(turned) * pivot / (pivot + travel)), .25F);
        travel += pivot;
        float wanted = s.supported() && !s.swimming() ? gait.walkAmount(travel, modelScale) : 0;
        if (s.holdStride()) wanted = amount;
        amount = Mth.approach(amount, wanted, AMPLITUDE_EASE);
        // A run lattice's column follows the pace on the ground and holds through a leap, as the stride does.
        previousRunShare = runShare;
        if (!gait.runLattice()) runShare = -1;
        else if (s.supported() && !s.swimming()) runShare = Mth.approach(Math.max(0, runShare), gait.runShare(travel, modelScale), AMPLITUDE_EASE);
        else runShare = Math.max(0, runShare);
        // Any pace a body can run at turns the legs over (a sprint on ice once froze them mid-stride past a block a tick);
        // only a jump no gait covers, a teleport, is skipped.
        if (s.supported() && horizontalTravel < GAIT_SNAP) {
            float step = gait.advance(travel, amount, modelScale, run, Math.max(0, runShare));
            // A gait with no clip of its own for going back plays its walk backwards, which plants the feet as well.
            double yaw = s.bodyYaw() * Mth.DEG_TO_RAD;
            boolean backing = !gait.directional() && pace > 0 && -moveX * Math.sin(yaw) + moveZ * Math.cos(yaw) < -.5 * pace;
            phase += backing ? -step : step;
            if (phase < 0) {
                // Clips read no negative clock; whole laps keep the pose where it is.
                float laps = gait.cycleTicks() * 64;
                phase += laps;
                previousPhase += laps;
            }
        }
    }

    /**
     * The way it plays for a way {@code degrees} off straight ahead in the body's frame (positive to its left): straight
     * ahead within {@link #AHEAD} of it, straight back within as much of its tail, the sidesteps' range spread over what is
     * left between.
     */
    static double settled(double degrees) {
        double off = Math.abs(degrees), side = 90 - AHEAD;
        double way = off <= AHEAD ? 0 : off >= 180 - AHEAD ? 180 : off <= 90 ? (off - AHEAD) * 90 / side : 90 + (off - 90) * 90 / side;
        return Math.copySign(way, degrees);
    }

    public float phase(float partial) { return Mth.lerp(partial, previousPhase, phase); }
    public float amount(float partial) { return Mth.lerp(partial, previousAmount, amount); }
    public float run(float partial) { return Mth.lerp(partial, previousRun, run); }
    public float pivotTurn(float partial) { return Mth.lerp(partial, previousPivotTurn, pivotTurn); }
    public float runShare(float partial) { return runShare < 0 ? -1 : Mth.lerp(partial, Math.max(0, previousRunShare), runShare); }

    /** Shares of a directional gait spent forwards, backwards, left and right; they sum to one. */
    public float[] shares(float partial) {
        float[] out = new float[4];
        float sum = 0;
        for (int i = 0; i < 4; i++) sum += out[i] = Mth.lerp(partial, previousShares[i], shares[i]);
        if (sum < 1.0E-4F) return new float[]{1, 0, 0, 0};
        for (int i = 0; i < 4; i++) out[i] /= sum;
        return out;
    }
}
