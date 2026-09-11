package com.digicube.party;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Small public summary. Full entity NBT and other players' collections never leave the
 * server. Level and XP come from the live entity while deployed and from the saved
 * roster entry otherwise; the client computes the XP requirement from the same formula.
 * {@code restTicks} is the rest a defeated partner still owes before it regenerates.
 */
public record PartyMemberView(UUID id, Identifier species, String nickname, float health, float maxHealth,
                              int level, int xp, int slot, boolean deployed, int restTicks) {
    public static PartyMemberView of(PartySavedData data, PartyMember member) {
        String name = member.nickname();
        // The saved entity snapshot is intentionally less frequent than UI health updates.
        var live = data.live.get(member.id());
        return new PartyMemberView(member.id(), member.species(), name.substring(0, Math.min(name.length(), 128)),
                live == null ? member.health() : live.getHealth(),
                live == null ? member.maxHealth() : live.getMaxHealth(),
                live == null ? member.level() : live.getLevel(),
                live == null ? member.xp() : live.getXp(),
                member.slot(), live != null, live == null ? member.restTicks() : 0);
    }

    public static PartyMemberView read(FriendlyByteBuf buffer) {
        return new PartyMemberView(buffer.readUUID(), buffer.readIdentifier(), buffer.readUtf(128),
                buffer.readFloat(), buffer.readFloat(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeIdentifier(species);
        buffer.writeUtf(nickname, 128);
        buffer.writeFloat(health);
        buffer.writeFloat(maxHealth);
        buffer.writeVarInt(level);
        buffer.writeVarInt(xp);
        buffer.writeVarInt(slot);
        buffer.writeBoolean(deployed);
        buffer.writeVarInt(restTicks);
    }

    /** The same individual with fresher health, keeping identity and progression as they were. */
    public PartyMemberView withHealth(float health, float maxHealth) {
        return new PartyMemberView(id, species, nickname, health, maxHealth, level, xp, slot, deployed, restTicks);
    }
}
