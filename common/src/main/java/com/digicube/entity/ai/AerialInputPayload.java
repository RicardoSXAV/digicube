package com.digicube.entity.ai;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Extra jump/menu state; vanilla transports axes and view direction.
 * @param entityId controlling player's mounted entity
 * @param buttons two validated control bits
 */
public record AerialInputPayload(int entityId, int buttons) implements CustomPacketPayload {
    /** Serverbound aerial input channel. */
    public static final Type<AerialInputPayload> TYPE=new Type<>(Constants.id("aerial_input"));
    /** Compact entity and input encoding. */
    public static final StreamCodec<RegistryFriendlyByteBuf,AerialInputPayload> STREAM_CODEC=StreamCodec.composite(
            ByteBufCodecs.VAR_INT,AerialInputPayload::entityId,ByteBufCodecs.VAR_INT,AerialInputPayload::buttons,AerialInputPayload::new);
    @Override public Type<AerialInputPayload> type() { return TYPE; }
}
