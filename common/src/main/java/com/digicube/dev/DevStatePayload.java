package com.digicube.dev;

import com.digicube.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the developer panel's readout after an action, and the one-line
 * result of that action. The state is the tag {@link DevState#capture} builds; the reply
 * is plain text because this is a tool for the developer, not a player-facing screen.
 */
public record DevStatePayload(CompoundTag state, String reply) implements CustomPacketPayload {
    public static final int MAX_REPLY_LENGTH = 256;
    public static final Type<DevStatePayload> TYPE = new Type<>(Constants.id("dev_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DevStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.COMPOUND_TAG, DevStatePayload::state,
            ByteBufCodecs.stringUtf8(MAX_REPLY_LENGTH), DevStatePayload::reply,
            DevStatePayload::new);

    public DevStatePayload {
        state = state.copy();
        if (reply.length() > MAX_REPLY_LENGTH) reply = reply.substring(0, MAX_REPLY_LENGTH);
    }

    @Override public Type<DevStatePayload> type() { return TYPE; }
}
