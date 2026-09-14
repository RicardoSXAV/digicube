package com.digicube.digimon;

/** Authored ground-cycle measurements and an optional limit on visual playback speed. */
public record DigimonGait(float cycleTicks, double stride, float maxPlaybackRate, float runCycleTicks, double runStride) {
    public DigimonGait(float cycleTicks, double stride, float maxPlaybackRate) {
        this(cycleTicks, stride, maxPlaybackRate, cycleTicks, stride);
    }
    public DigimonGait(float cycleTicks, double stride) {
        this(cycleTicks, stride, Float.MAX_VALUE);
    }

    public DigimonGait {
        if (!Float.isFinite(cycleTicks) || cycleTicks <= 0 || !Double.isFinite(stride) || stride <= 0
                || !Float.isFinite(maxPlaybackRate) || maxPlaybackRate <= 0
                || !Float.isFinite(runCycleTicks) || runCycleTicks <= 0 || !Double.isFinite(runStride) || runStride <= 0) {
            throw new IllegalArgumentException("Invalid authored ground gait");
        }
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
