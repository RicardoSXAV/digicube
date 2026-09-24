package com.digicube.digivice;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

/** Withdraw visuals before the entity removal packet, without waiting for a periodic snapshot. */
public record DigiviceRemovedPayload(UUID entity) implements CustomPacketPayload {
    public static final Type<DigiviceRemovedPayload> TYPE = new Type<>(Constants.id("digivice_removed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DigiviceRemovedPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeUUID(value.entity()), buffer -> new DigiviceRemovedPayload(buffer.readUUID()));
    @Override public Type<DigiviceRemovedPayload> type() { return TYPE; }
}
