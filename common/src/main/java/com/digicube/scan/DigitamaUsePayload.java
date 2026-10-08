package com.digicube.scan;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.UUID;

/**
 * A Digitama item was used and its Digitama is already in the Digivice: the egg breaks into data in the hand of the
 * player {@code recipient} (an entity id). The tamer's copy names the new Digitama ({@code egg}), so the Digivice opens
 * on it once the egg is gone; the players near enough to watch get {@link #NO_EGG}.
 */
public record DigitamaUsePayload(UUID egg, Identifier family, InteractionHand hand, int recipient) implements CustomPacketPayload {
    public static final UUID NO_EGG = new UUID(0, 0);
    /** Ticks from the use to the Digivice opening on the new Digitama: the egg breaking into data in the hand. */
    public static final int OPEN_TICKS = 18;
    /** The Digitama items' cooldown after a use, so the hand is free again once the Digivice is open. */
    public static final int COOLDOWN_TICKS = 20;
    /** How far away a player still sees someone's egg break, in blocks. */
    public static final double WATCH_RANGE = 64;

    public static final Type<DigitamaUsePayload> TYPE = new Type<>(Constants.id("digitama_use"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DigitamaUsePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.egg());
                buffer.writeIdentifier(payload.family());
                buffer.writeEnum(payload.hand());
                buffer.writeVarInt(payload.recipient());
            },
            buffer -> new DigitamaUsePayload(buffer.readUUID(), buffer.readIdentifier(), buffer.readEnum(InteractionHand.class), buffer.readVarInt()));

    /** The copy the watchers get: the same egg breaking, no Digitama named. */
    public DigitamaUsePayload withoutEgg() { return new DigitamaUsePayload(NO_EGG, family, hand, recipient); }

    @Override public Type<DigitamaUsePayload> type() { return TYPE; }
}
