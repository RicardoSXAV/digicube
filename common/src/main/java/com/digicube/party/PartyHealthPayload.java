package com.digicube.party;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Health-only changes for at most three deployed partners; no entity NBT is transmitted. */
public record PartyHealthPayload(List<Health> members) implements CustomPacketPayload {
    public static final Type<PartyHealthPayload> TYPE = new Type<>(Constants.id("party_health"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PartyHealthPayload> STREAM_CODEC =
            StreamCodec.ofMember(PartyHealthPayload::write, PartyHealthPayload::read);

    public PartyHealthPayload {
        if (members.size() > PartyRoster.PARTY_SIZE) throw new IllegalArgumentException("Too many health updates");
        members = List.copyOf(members);
    }

    /** One individual's current server-authoritative health. */
    public record Health(UUID id, float health, float maxHealth) {}

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(members.size());
        for (Health member : members) {
            buffer.writeUUID(member.id());
            buffer.writeFloat(member.health());
            buffer.writeFloat(member.maxHealth());
        }
    }

    private static PartyHealthPayload read(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > PartyRoster.PARTY_SIZE) throw new IllegalArgumentException("Invalid health update count");
        List<Health> members = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            members.add(new Health(buffer.readUUID(), buffer.readFloat(), buffer.readFloat()));
        }
        return new PartyHealthPayload(members);
    }

    /** Updates known members only, preserving membership and all non-health fields. */
    public PartyMemberView update(PartyMemberView member) {
        for (Health health : members) {
            if (health.id().equals(member.id())) {
                if (health.health() == member.health() && health.maxHealth() == member.maxHealth()) return member;
                return new PartyMemberView(member.id(), member.species(), member.nickname(), health.health(),
                        health.maxHealth(), member.slot(), member.deployed());
            }
        }
        return member;
    }

    /** Patches both views of an individual without replaying a screen-open request or feedback. */
    public PartySnapshotPayload apply(PartySnapshotPayload snapshot) {
        return new PartySnapshotPayload(false, snapshot.page(), snapshot.total(),
                snapshot.party().stream().map(this::update).toList(),
                snapshot.collection().stream().map(this::update).toList(), "");
    }

    @Override public Type<PartyHealthPayload> type() { return TYPE; }
}
