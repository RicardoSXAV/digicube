package com.digicube.party;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Small public summary. Full entity NBT and other players' collections never leave the server. */
public record PartyMemberView(UUID id, Identifier species, String nickname, float health,
                              float maxHealth, int slot, boolean deployed) {
    public static PartyMemberView of(PartySavedData data, PartyMember member) {
        String name = member.nickname();
        // The saved entity snapshot is intentionally less frequent than UI health updates.
        var live = data.live.get(member.id());
        return new PartyMemberView(member.id(), member.species(), name.substring(0, Math.min(name.length(), 128)),
                live == null ? member.health() : live.getHealth(),
                live == null ? member.maxHealth() : live.getMaxHealth(), member.slot(), live != null);
    }

    public static PartyMemberView read(FriendlyByteBuf buffer) {
        return new PartyMemberView(buffer.readUUID(), buffer.readIdentifier(), buffer.readUtf(128),
                buffer.readFloat(), buffer.readFloat(), buffer.readVarInt(), buffer.readBoolean());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeIdentifier(species);
        buffer.writeUtf(nickname, 128);
        buffer.writeFloat(health);
        buffer.writeFloat(maxHealth);
        buffer.writeVarInt(slot);
        buffer.writeBoolean(deployed);
    }
}
