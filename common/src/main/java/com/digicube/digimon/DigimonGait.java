package com.digicube.digimon;

/**
 * Authored ground-cycle measurements and an optional limit on visual playback speed. A gait with its own
 * {@code sideStride} or {@code backStride} is directional: the model has planted clips for walking backwards and
 * for stepping sideways (shorter steps than forwards), all on one shared phase.
 */
public record DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride,
                          double sideStride, double backStride) {
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
                || !Double.isFinite(sideStride) || sideStride <= 0 || !Double.isFinite(backStride) || backStride <= 0) {
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

    /** Native animation ticks per game tick. The cap affects presentation, never travel. */
    public float advance(double travel, float amount, float modelScale) {
        // Very small authored strides still need their full cadence to match
        // travel. Only guard the divisor near zero, rather than clamping to 1/8.
        return (float) Math.min(maxPlaybackRate,
                travel / fullSpeed(modelScale) / Math.max(.001F, amount));
    }
}
