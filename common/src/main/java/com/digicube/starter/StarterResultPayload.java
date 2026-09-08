package com.digicube.starter;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the answer to a choice. {@code ok} closes the screen; otherwise
 * {@code messageKey} is a translation key the screen shows on its hint line.
 */
public record StarterResultPayload(boolean ok, String messageKey) implements CustomPacketPayload {
    public static final int MAX_KEY_LENGTH = 64;
    public static final Type<StarterResultPayload> TYPE = new Type<>(Constants.id("starter_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StarterResultPayload> STREAM_CODEC =
            StreamCodec.ofMember(StarterResultPayload::write, buffer ->
                    new StarterResultPayload(buffer.readBoolean(), buffer.readUtf(MAX_KEY_LENGTH)));

    public StarterResultPayload {
        if (messageKey.length() > MAX_KEY_LENGTH) throw new IllegalArgumentException("Message key too long: " + messageKey);
    }

    public static StarterResultPayload linked() {
        return new StarterResultPayload(true, "");
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(ok);
        buffer.writeUtf(messageKey, MAX_KEY_LENGTH);
    }

    @Override public Type<StarterResultPayload> type() { return TYPE; }
}
