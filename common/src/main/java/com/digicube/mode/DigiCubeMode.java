package com.digicube.mode;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * A world's DigiCube Mode, chosen once when the world is created ({@link DigiCubeModes}). {@link #UNSET} exists only
 * before that choice: in Create World while no mode is picked, and in the game rule of a world made without the
 * screen (an old world, a dedicated server), which plays as {@link #TAMER}.
 */
public enum DigiCubeMode implements StringRepresentable {
    UNSET, TAMER, CLASSIC;

    public static final Codec<DigiCubeMode> CODEC = StringRepresentable.fromEnum(DigiCubeMode::values);

    /** Whether a world can be in this mode: TAMER or CLASSIC. */
    public boolean chosen() {
        return this != UNSET;
    }

    /** The lang key of the mode's name, {@code mode.digicube.tamer}. */
    public String translationKey() {
        return "mode.digicube." + getSerializedName();
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
