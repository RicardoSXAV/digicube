package com.digicube.starter;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: link with a species, or put the choice off. Carries an id and an
 * action only; eligibility and the species are validated again on the server.
 */
public record StarterChoicePayload(int action, Identifier species) implements CustomPacketPayload {
    public static final int CHOOSE = 0;
    public static final int DEFER = 1;
    /** Placeholder species for actions that name none. */
    public static final Identifier NONE = Constants.id("none");
    public static final Type<StarterChoicePayload> TYPE = new Type<>(Constants.id("starter_choice"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StarterChoicePayload> STREAM_CODEC =
            StreamCodec.ofMember(StarterChoicePayload::write, buffer ->
                    new StarterChoicePayload(buffer.readVarInt(), buffer.readIdentifier()));

    public static StarterChoicePayload choose(Identifier species) {
        return new StarterChoicePayload(CHOOSE, species);
    }

    public static StarterChoicePayload defer() {
        return new StarterChoicePayload(DEFER, NONE);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(action);
        buffer.writeIdentifier(species);
    }

    @Override public Type<StarterChoicePayload> type() { return TYPE; }
}
