package com.digicube.scan;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Something happened to one of the tamer's Digitama: the client shows it as a toast over the world and the Digivice.
 * @param kind   what happened
 * @param family the family's first form
 * @param amount the data gained or lost ({@link Kind#DATA}, {@link Kind#SIGHTED}, {@link Kind#LOST}); 0 otherwise
 * @param data   the bar's data after it
 */
public record ScanToastPayload(Kind kind, Identifier family, int amount, int data) implements CustomPacketPayload {
    public enum Kind {
        /** A defeat filled the bar. */
        DATA,
        /** The first sighting of the family filled the bar. */
        SIGHTED,
        /** The bar was full: data was thrown away. */
        LOST,
        /** The bar reached a Digitama's worth: CONVERT is open. */
        READY,
        /** A Digitama of the family hatched in the Digivice. */
        HATCHED
    }

    public static final Type<ScanToastPayload> TYPE = new Type<>(Constants.id("scan_toast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScanToastPayload> STREAM_CODEC =
            StreamCodec.ofMember(ScanToastPayload::write, ScanToastPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(kind);
        buffer.writeIdentifier(family);
        buffer.writeVarInt(amount);
        buffer.writeVarInt(data);
    }

    private static ScanToastPayload read(RegistryFriendlyByteBuf buffer) {
        return new ScanToastPayload(buffer.readEnum(Kind.class), buffer.readIdentifier(), buffer.readVarInt(), buffer.readVarInt());
    }

    @Override public Type<ScanToastPayload> type() { return TYPE; }
}
