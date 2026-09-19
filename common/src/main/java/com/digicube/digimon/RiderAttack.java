package com.digicube.digimon;

import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Objects;

/**
 * How a rider casts one of the mount's attacks: the species sheet lists them in slot order under
 * {@code body.mount.rider_attacks}. A rider has no target, so every entry says where the attack goes instead.
 *
 * @param attack one of the species' attacks
 * @param aim    where it goes without a target
 * @param input  what the button does
 * @param cone   half-angle in degrees, around the rider's view, in which the attack turns to a nearby enemy; 0 for none
 * @param reach  how far that enemy may be, in blocks beyond the mount's body; 0 uses the attack's own range
 * @param move   whether the mount keeps walking while it casts
 */
public record RiderAttack(Identifier attack, Aim aim, Input input, float cone, float reach, boolean move) {
    public enum Aim {
        /** A strike at what stands before the mount: it turns to the soft target, or swings along the view. */
        SWEEP,
        /** Runs along the ground where the rider looks; held, it shows where first. */
        LINE,
        /** Flies where the rider looks, pitch included, or at the soft target. */
        SHOT,
        /** Sustained along the rider's view for as long as the button is held. */
        STREAM
    }

    public enum Input {
        /** Cast on the press; a held button repeats. */
        TAP,
        /** Held to aim or to sustain, released to cast or to stop. */
        HOLD
    }

    public RiderAttack {
        Objects.requireNonNull(attack, "attack");
        Objects.requireNonNull(aim, "aim");
        Objects.requireNonNull(input, "input");
        if (!Float.isFinite(cone) || cone < 0 || cone > 90 || !Float.isFinite(reach) || reach < 0) throw new IllegalArgumentException("Invalid rider attack aim");
    }

    static <E extends Enum<E>> E parse(Class<E> type, String name) {
        return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
    }
}
