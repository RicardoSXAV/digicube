package com.digicube.digimon;

/**
 * Authored ground-cycle measurements and an optional limit on visual playback speed. A gait with its own
 * {@code sideStride} or {@code backStride} is directional: the model has planted clips for walking backwards and
 * for stepping sideways (shorter steps than forwards), all on one shared phase. A gait with {@code footfalls} sounds
 * its own feet on its phase (the client's hoof beats), so the body makes none of vanilla's step-per-block sounds.
 * A gait with {@code runFrom} breaks into its run as an animal changes gait, all at once: the run takes over from that
 * pace up and hands back to the walk under {@code runUntil}, so no pace between plays half of each (their feet land on
 * different beats, and a paw mixed from one standing and one swinging never touches the ground). Without it the run
 * blends in over the paces between the walk's authored speed and the run's. A gait with {@code pivotReach} (blocks from
 * the body's centre to its farthest standing paws: a quadruped's fore toe line, a biped's ankles) steps round on the spot
 * as the body turns: the model's {@code pivot_left} and {@code pivot_right} (lattice blends, or plain looping clips) go
 * round the centre as far a cycle as that reach sweeps {@code pivotStride} at full amplitude, one paw at a time, each
 * standing still on the ground while the body turns over it; the turn is paid on the phase at that stride. Standing, such
 * a body turns no faster than its pivot steps round at {@code pivotCadence} times the walk's cadence
 * ({@link #pivotTurnRate}); without a pivot a body turning on the spot slides its feet round. A pivot with
 * {@code pivotWalk} steps on the walk's own beats (a biped's: each foot down when the walk puts it down), so a body that
 * turns as it walks mixes the two by their shares and goes round in an arc on planted feet, at any pace of the walk.
 * A run with {@code runLattice} is a lattice over its pace ({@link #runShare}): each column planted on its own stride, the
 * run's stride times its share, so a jog and a sprint keep the run's cadence instead of one stride sped up or slowed down.
 */
public record DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride,
                          double sideStride, double backStride, boolean footfalls, double runFrom, double runUntil, double pivotReach,
                          double pivotStride, float pivotCadence, boolean pivotWalk, boolean runLattice) {
    /** The pivot's cadence, times the walk's, when the sheet names none. */
    public static final float PIVOT_CADENCE = 1.5F;

    public DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride,
                       double sideStride, double backStride, boolean footfalls, double runFrom, double runUntil, double pivotReach,
                       double pivotStride, float pivotCadence) {
        this(cycleTicks, stride, maxPlaybackRate, runCycleTicks, runStride, sideStride, backStride, footfalls, runFrom, runUntil, pivotReach,
                pivotStride, pivotCadence, false, false);
    }

    public DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride,
                       double sideStride, double backStride, boolean footfalls, double runFrom, double runUntil, double pivotReach) {
        this(cycleTicks, stride, maxPlaybackRate, runCycleTicks, runStride, sideStride, backStride, footfalls, runFrom, runUntil, pivotReach,
                sideStride, PIVOT_CADENCE);
    }
    public DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride,
                       double sideStride, double backStride, boolean footfalls, double runFrom, double runUntil) {
        this(cycleTicks, stride, maxPlaybackRate, runCycleTicks, runStride, sideStride, backStride, footfalls, runFrom, runUntil, 0);
    }
    public DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride,
                       double sideStride, double backStride, boolean footfalls) {
        this(cycleTicks, stride, maxPlaybackRate, runCycleTicks, runStride, sideStride, backStride, footfalls, 0, 0);
    }
    public DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride,
                       double sideStride, double backStride) {
        this(cycleTicks, stride, maxPlaybackRate, runCycleTicks, runStride, sideStride, backStride, false);
    }
    public DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride) {
        this(cycleTicks, stride, maxPlaybackRate, runCycleTicks, runStride, stride, stride);
    }
    public DigimonGait(float cycleTicks, double stride, float maxPlaybackRate) {
        this(cycleTicks, stride, maxPlaybackRate, cycleTicks, stride);
    }
    public DigimonGait(float cycleTicks, double stride) {
        this(cycleTicks, stride, Float.MAX_VALUE);
    }

    public DigimonGait {
        if (!Float.isFinite(cycleTicks) || cycleTicks <= 0 || !Double.isFinite(stride) || stride <= 0
                || !Float.isFinite(maxPlaybackRate) || maxPlaybackRate <= 0
                || !Float.isFinite(runCycleTicks) || runCycleTicks <= 0 || !Double.isFinite(runStride) || runStride <= 0
                || !Double.isFinite(sideStride) || sideStride <= 0 || !Double.isFinite(backStride) || backStride <= 0
                || !Double.isFinite(runFrom) || runFrom < 0 || !Double.isFinite(runUntil) || runUntil < 0 || runUntil > runFrom
                || !Double.isFinite(pivotReach) || pivotReach < 0 || !Double.isFinite(pivotStride) || pivotStride <= 0
                || !Float.isFinite(pivotCadence) || pivotCadence <= 0) {
            throw new IllegalArgumentException("Invalid authored ground gait");
        }
    }

    public boolean directional() { return sideStride != stride || backStride != stride; }

    /**
     * Shares of the cycle's effort spent forwards, backwards, to the left and to the right for a movement in the
     * body's frame, and in slot 4 the forward speed that costs the same effort. Feeding that speed to
     * {@link #advance} keeps every direction's feet planted: a share times its own stride is the ground it covers.
     */
    public double[] directions(double forward, double left) {
        double f = Math.max(0, forward) / stride, b = Math.max(0, -forward) / backStride, s = Math.abs(left) / sideStride, effort = f + b + s;
        if (effort < 1.0E-9) return new double[]{1, 0, 0, 0, 0};
        return new double[]{f / effort, b / effort, left > 0 ? s / effort : 0, left > 0 ? 0 : s / effort, effort * stride};
    }

    /**
     * What a tick's travel does to the gait, in the body's frame: the shares of the directional clips
     * ({@link #directions}), how much of the run it asks for, whether it is running (a gait that changes all at once,
     * {@link #runFrom}), the effort the walk's clips are paid in (a share times its own stride) and the ground covered.
     * A run is a gallop along the body: all of it while the travel is within about 25 degrees of straight ahead, none from
     * 60 degrees off, so a body drifting through a bend at a gallop keeps galloping instead of mixing its walk's
     * sidesteps into the stride.
     */
    public record Drive(double[] shares, float run, boolean running, double effort, double speed) {
        /** The travel the shared phase is paid in, for a run share of {@code run}: the walk's effort blended into the ground covered. */
        public double travel(float run) { return effort + (speed - effort) * run; }
    }
    private static final double RUN_AHEAD = Math.cos(Math.toRadians(25)), RUN_ASIDE = Math.cos(Math.toRadians(60));

    /** @param running whether the body was running last tick (a gait that changes all at once holds its run down to {@link #runUntil}) */
    public Drive drive(double forward, double left, float modelScale, boolean running) {
        double speed = Math.sqrt(forward * forward + left * left);
        boolean runs = runFrom > 0 && (speed >= runFrom || running && speed > runUntil);
        float run = runFrom > 0 ? runs ? 1 : 0 : runAmount(speed, modelScale);
        if (!directional() || speed < 1.0E-9) return new Drive(new double[]{1, 0, 0, 0}, run, runs, speed, speed);
        double[] d = directions(forward, left);
        float ahead = (float) Math.clamp((forward / speed - RUN_ASIDE) / (RUN_AHEAD - RUN_ASIDE), 0, 1);
        return new Drive(new double[]{d[0], d[1], d[2], d[3]}, run * ahead, runs, d[4], speed);
    }

    /**
     * A turn on the spot as travel for the gait: {@code degrees} of turn this tick sweep the farthest paws {@link #pivotReach}
     * round the centre, a share of the pivot's stride a cycle ({@link #pivotStride}); returned as the forward-equivalent
     * travel, the currency of {@link Drive#travel}. Zero for a gait that does not pivot, or for a body that runs (a gallop
     * turns in an arc).
     */
    public double pivotTravel(float degrees, float run) {
        if (pivotReach <= 0) return 0;
        return Math.abs(degrees) * Math.PI / 180 * pivotReach / pivotStride * stride * (1 - Math.clamp(run, 0, 1));
    }

    /**
     * The fastest turn on the spot, degrees a tick: the pivot stepping round at {@link #pivotCadence} times the walk's
     * cadence (its stride a cycle over the reach), so its paws stay planted and it never hurries them (zero for a gait
     * that does not pivot).
     */
    public float pivotTurnRate(float modelScale) {
        if (pivotReach <= 0) return 0;
        return (float) Math.toDegrees(Math.min(maxPlaybackRate, pivotCadence) * pivotStride * modelScale / cycleTicks / pivotReach);
    }

    /** Share of the run a tick of travel moves the blend: a gait that changes all at once crosses over in four ticks, a blended one in eight. */
    public float runEase() { return runFrom > 0 ? .25F : .125F; }

    /** Full-amplitude native speed in blocks per tick, after the model scale. */
    public double fullSpeed(float modelScale) { return stride * modelScale / cycleTicks; }
    public double runSpeed(float modelScale) { return runStride * modelScale / runCycleTicks; }
    public float runAmount(double speed, float modelScale) {
        double range = runSpeed(modelScale) - fullSpeed(modelScale);
        return range <= 0 ? 0 : (float) Math.clamp((speed - fullSpeed(modelScale)) / range, 0, 1);
    }
    public float advance(double travel, float amount, float modelScale, float run) {
        double blendedStride = stride + (runStride - stride) * run;
        return (float) Math.min(maxPlaybackRate, travel * cycleTicks / (blendedStride * modelScale * Math.max(.001F, amount)));
    }

    /** The run lattice's column for a pace ({@link #runLattice}): the ground covered against the run's own pace, at most 1. */
    public float runShare(double travel, float modelScale) {
        return runLattice ? (float) Math.clamp(travel / runSpeed(modelScale), 0, 1) : 1;
    }

    /** As {@link #advance(double, float, float, float)}, a run lattice paying the run's share of its stride ({@code share}). */
    public float advance(double travel, float amount, float modelScale, float run, float share) {
        if (!runLattice) return advance(travel, amount, modelScale, run);
        double paid = stride * Math.max(.001F, amount) * (1 - run) + runStride * Math.max(.001F, share) * run;
        return (float) Math.min(maxPlaybackRate, travel * cycleTicks / (paid * modelScale));
    }

    /** Native animation ticks per game tick. The cap affects presentation, never travel. */
    public float advance(double travel, float amount, float modelScale) {
        // Very small authored strides still need their full cadence to match
        // travel. Only guard the divisor near zero, rather than clamping to 1/8.
        return (float) Math.min(maxPlaybackRate,
                travel / fullSpeed(modelScale) / Math.max(.001F, amount));
    }
}
