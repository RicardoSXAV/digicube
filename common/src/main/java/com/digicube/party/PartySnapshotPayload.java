package com.digicube.party;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded pages keep a large collection below the custom-payload packet limit. A page is large enough that the
 * Digispace shows a whole ordinary reserve at once. {@code known} lists the species the Analyzer may describe; like
 * the collection it is only filled while the Digivice is open.
 */
public record PartySnapshotPayload(boolean openScreen, int page, int total,
                                   List<PartyMemberView> party, List<PartyMemberView> collection,
                                   String message, List<Identifier> known) implements CustomPacketPayload {
    public static final int PAGE_SIZE = 64;
    private static final int MAX_KNOWN = 1024;
    public static final Type<PartySnapshotPayload> TYPE = new Type<>(Constants.id("party_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PartySnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(PartySnapshotPayload::write, PartySnapshotPayload::read);

    public PartySnapshotPayload {
        party = List.copyOf(party);
        collection = List.copyOf(collection);
        known = List.copyOf(known);
    }

    public PartySnapshotPayload(boolean openScreen, int page, int total, List<PartyMemberView> party, List<PartyMemberView> collection, String message) {
        this(openScreen, page, total, party, collection, message, List.of());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(openScreen);
        buffer.writeVarInt(page);
        buffer.writeVarInt(total);
        writeMembers(buffer, party);
        writeMembers(buffer, collection);
        buffer.writeUtf(message, 128);
        buffer.writeVarInt(known.size());
        known.forEach(buffer::writeIdentifier);
    }

    private static PartySnapshotPayload read(RegistryFriendlyByteBuf buffer) {
        return new PartySnapshotPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(),
                readMembers(buffer, PartyRoster.PARTY_SIZE), readMembers(buffer, PAGE_SIZE), buffer.readUtf(128), readKnown(buffer));
    }

    private static void writeMembers(RegistryFriendlyByteBuf buffer, List<PartyMemberView> members) {
        buffer.writeVarInt(members.size());
        members.forEach(member -> member.write(buffer));
    }

    private static List<PartyMemberView> readMembers(RegistryFriendlyByteBuf buffer, int maximum) {
        int size = buffer.readVarInt();
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Invalid party page size: " + size);
        List<PartyMemberView> members = new ArrayList<>(size);
        for (int index = 0; index < size; index++) members.add(PartyMemberView.read(buffer));
        return members;
    }

    private static List<Identifier> readKnown(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_KNOWN) throw new IllegalArgumentException("Invalid known species count: " + size);
        List<Identifier> known = new ArrayList<>(size);
        for (int index = 0; index < size; index++) known.add(buffer.readIdentifier());
        return known;
    }

    @Override public Type<PartySnapshotPayload> type() { return TYPE; }
}
