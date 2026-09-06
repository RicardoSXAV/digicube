package com.digicube.party;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Requests contain no owner or entity NBT; the authenticated sender owns the operation. */
public record PartyActionPayload(int action, UUID member, int value) implements CustomPacketPayload {
    public static final int CLOSE = 0;
    public static final int PAGE = 1;
    public static final int SELECT = 2;
    public static final UUID NO_MEMBER = new UUID(0, 0);
    public static final Type<PartyActionPayload> TYPE = new Type<>(Constants.id("party_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PartyActionPayload> STREAM_CODEC =
            StreamCodec.ofMember(PartyActionPayload::write, buffer ->
                    new PartyActionPayload(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt()));

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(action);
        buffer.writeUUID(member);
        buffer.writeVarInt(value);
    }

    @Override public Type<PartyActionPayload> type() { return TYPE; }
}
