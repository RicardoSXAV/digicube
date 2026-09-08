package com.digicube.starter;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: open the partner choice with these candidates. The client renders
 * exactly this list; it never consults its own copy of the starter data.
 */
public record StarterOfferPayload(List<Identifier> species, int level) implements CustomPacketPayload {
    public static final Type<StarterOfferPayload> TYPE = new Type<>(Constants.id("starter_offer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StarterOfferPayload> STREAM_CODEC =
            StreamCodec.ofMember(StarterOfferPayload::write, StarterOfferPayload::read);

    public StarterOfferPayload {
        species = List.copyOf(species);
        if (species.size() > StarterSet.MAX_STARTERS) {
            throw new IllegalArgumentException("Too many starters offered: " + species.size());
        }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(species.size());
        species.forEach(buffer::writeIdentifier);
        buffer.writeVarInt(level);
    }

    private static StarterOfferPayload read(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > StarterSet.MAX_STARTERS) throw new IllegalArgumentException("Invalid starter count: " + size);
        List<Identifier> species = new ArrayList<>(size);
        for (int index = 0; index < size; index++) species.add(buffer.readIdentifier());
        return new StarterOfferPayload(species, buffer.readVarInt());
    }

    @Override public Type<StarterOfferPayload> type() { return TYPE; }
}
