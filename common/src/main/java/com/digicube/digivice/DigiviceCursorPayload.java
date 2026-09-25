package com.digicube.digivice;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/**
 * The creative inventory moves items on a cursor the server never sees: while a creative player drags their Digivice
 * there, the client reports its credential ({@link DigiviceRecallPayload#NO_TOKEN} once it is put down), so the device
 * still counts as with them and their partners stay out. Only believed in creative, and only for the current credential.
 */
public record DigiviceCursorPayload(UUID token) implements CustomPacketPayload {
    public static final Type<DigiviceCursorPayload> TYPE = new Type<>(Constants.id("digivice_cursor"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DigiviceCursorPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeUUID(value.token()), buffer -> new DigiviceCursorPayload(buffer.readUUID()));
    @Override public Type<DigiviceCursorPayload> type() { return TYPE; }

    public static void handle(ServerPlayer player, DigiviceCursorPayload payload) {
        boolean held = player.isCreative() && !payload.token().equals(DigiviceRecallPayload.NO_TOKEN);
        DigiviceSavedData.get(player.level().getServer()).creativeCursor(player.getUUID(), held ? payload.token() : null);
    }
}
