package com.digicube.mode;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client, on join: the world's DigiCube Mode, so the HUD, the Digivice and the tree draw its rules. */
public record DigiCubeModePayload(DigiCubeMode mode) implements CustomPacketPayload {
    public static final Type<DigiCubeModePayload> TYPE = new Type<>(Constants.id("mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DigiCubeModePayload> STREAM_CODEC =
            StreamCodec.ofMember(DigiCubeModePayload::write, buffer -> new DigiCubeModePayload(read(buffer.readVarInt())));

    private static DigiCubeMode read(int ordinal) {
        DigiCubeMode[] modes = DigiCubeMode.values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : DigiCubeMode.UNSET;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(mode.ordinal());
    }

    @Override public Type<DigiCubeModePayload> type() { return TYPE; }
}
