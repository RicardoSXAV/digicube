package com.digicube.party;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Bounded pages keep a large collection below the custom-payload packet limit. */
public record PartySnapshotPayload(boolean openScreen, int page, int total,
                                   List<PartyMemberView> party, List<PartyMemberView> collection,
                                   String message) implements CustomPacketPayload {
    public static final int PAGE_SIZE = 6;
    public static final Type<PartySnapshotPayload> TYPE = new Type<>(Constants.id("party_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PartySnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(PartySnapshotPayload::write, PartySnapshotPayload::read);

    public PartySnapshotPayload {
        party = List.copyOf(party);
        collection = List.copyOf(collection);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(openScreen);
        buffer.writeVarInt(page);
        buffer.writeVarInt(total);
        writeMembers(buffer, party);
        writeMembers(buffer, collection);
        buffer.writeUtf(message, 128);
    }

    private static PartySnapshotPayload read(RegistryFriendlyByteBuf buffer) {
        return new PartySnapshotPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(),
                readMembers(buffer, PartyRoster.PARTY_SIZE), readMembers(buffer, PAGE_SIZE), buffer.readUtf(128));
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

    @Override public Type<PartySnapshotPayload> type() { return TYPE; }
}
