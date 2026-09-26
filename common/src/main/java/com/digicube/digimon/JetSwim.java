package com.digicube.digimon;

import net.minecraft.util.Mth;

/**
 * A swimmer that moves in pulses, as a squid does ({@code locomotion.jet} on the species sheet, Gesomon): each pulse
 * squeezes water out over its first {@code squeeze} share and shoots the body forward, then it glides while it refills.
 * The thrust over a pulse averages the species' ordinary swimming speed, so it goes as far as a steady swimmer, only
 * in surges; {@code glide} of it is steady (fins), the rest comes in the squeeze. The swim clip is one pulse,
 * {@code clipTicks} long, played on the pulse's own clock.
 *
 * @param pulseTicks       ticks a pulse lasts while cruising
 * @param surgePulseTicks  ticks a pulse lasts while a rider surges (the sprint key)
 * @param squeeze          share of a pulse over which it thrusts
 * @param glide            share of the thrust that is steady rather than in the squeeze
 * @param hoverRate        share of the cruising pulse rate it keeps pulsing at when it is not going anywhere
 * @param clipTicks        length of the swim clip, one pulse
 */
public record JetSwim(float pulseTicks, float surgePulseTicks, float squeeze, float glide, float hoverRate, float clipTicks) {
    public JetSwim {
        if (!(pulseTicks >= 4 && pulseTicks <= 120) || !(surgePulseTicks >= 4 && surgePulseTicks <= pulseTicks)
                || !(squeeze > 0 && squeeze < 1) || !(glide >= 0 && glide < 1) || !(hoverRate >= 0 && hoverRate <= 1) || !(clipTicks > 0))
            throw new IllegalArgumentException("Invalid jet swim");
    }

    /** Thrust at {@code phase} of a pulse (0 to 1), as a multiple of the steady speed; it averages 1 over a pulse. */
    public float thrust(float phase) {
        if (phase < 0 || phase >= squeeze) return glide;
        float s = Mth.sin(Mth.PI * phase / squeeze);
        return glide + (1 - glide) * 2 / squeeze * s * s;
    }

    /** Whether {@code phase} is still in the squeeze. */
    public boolean squeezing(float phase) {
        return phase >= 0 && phase < squeeze;
    }
}
