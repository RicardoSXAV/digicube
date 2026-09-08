package com.digicube.dev;

import com.digicube.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: run one developer action. The id names an entry of
 * {@link DevActions}; the arguments are a free-form tag so a new tool never needs a new
 * payload. Everything is validated again on the server.
 */
public record DevActionPayload(String action, CompoundTag args) implements CustomPacketPayload {
    public static final int MAX_ACTION_LENGTH = 64;
    public static final Type<DevActionPayload> TYPE = new Type<>(Constants.id("dev_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DevActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_ACTION_LENGTH), DevActionPayload::action,
            ByteBufCodecs.COMPOUND_TAG, DevActionPayload::args,
            DevActionPayload::new);

    public DevActionPayload {
        if (action.length() > MAX_ACTION_LENGTH) throw new IllegalArgumentException("Action id too long: " + action);
        args = args.copy();
    }

    public static DevActionPayload of(String action) {
        return new DevActionPayload(action, new CompoundTag());
    }

    @Override public Type<DevActionPayload> type() { return TYPE; }
}
