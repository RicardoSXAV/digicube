package com.digicube.party;

import com.mojang.serialization.Codec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ordered ownership registry. All party selections pass through this three-slot gate. */
public final class PartyRoster {
    public static final int PARTY_SIZE = 3;
    public static final Codec<PartyRoster> CODEC = PartyMember.CODEC.listOf().xmap(PartyRoster::new,
            roster -> List.copyOf(roster.members.values()));

    private final Map<UUID, PartyMember> members = new LinkedHashMap<>();

    public PartyRoster() {}

    private PartyRoster(List<PartyMember> saved) {
        // Repair invalid/duplicate slots deterministically without losing an individual.
        for (PartyMember member : saved) {
            if (member.slot() < -1 || member.slot() >= PARTY_SIZE || member.defeated()
                    || member.active() && inSlot(member.owner(), member.slot()) != null) {
                member.setSlot(-1);
            }
            members.putIfAbsent(member.id(), member);
        }
    }

    public PartyMember get(UUID id) { return members.get(id); }

    /** Whether a world entity is the current, selected incarnation of an owned member. */
    public boolean accepts(UUID id, UUID owner, long generation) {
        PartyMember member = members.get(id);
        return member != null && member.active() && !member.defeated()
                && member.owner().equals(owner) && member.generation() == generation;
    }

    public List<PartyMember> owned(UUID owner) {
        return members.values().stream().filter(member -> member.owner().equals(owner)).toList();
    }

    public List<PartyMember> party(UUID owner) {
        return owned(owner).stream().filter(PartyMember::active)
                .sorted(Comparator.comparingInt(PartyMember::slot)).toList();
    }

    public List<PartyMember> all() { return new ArrayList<>(members.values()); }

    public PartyMember inSlot(UUID owner, int slot) {
        return owned(owner).stream().filter(member -> member.slot() == slot).findFirst().orElse(null);
    }

    public int freeSlot(UUID owner) {
        for (int slot = 0; slot < PARTY_SIZE; slot++) {
            if (inSlot(owner, slot) == null) return slot;
        }
        return -1;
    }

    public boolean add(PartyMember member) {
        if (members.containsKey(member.id())) return false;
        member.setSlot(member.defeated() ? -1 : freeSlot(member.owner()));
        members.put(member.id(), member);
        return true;
    }

    /** Replaces a slot atomically; a forged owner/id or slot never changes anything. */
    public boolean select(UUID owner, UUID id, int slot) {
        PartyMember member = members.get(id);
        if (member == null || !member.owner().equals(owner) || member.defeated()
                || slot < -1 || slot >= PARTY_SIZE) return false;
        if (slot >= 0) {
            PartyMember previous = inSlot(owner, slot);
            if (previous != null) previous.setSlot(-1);
        }
        member.setSlot(slot);
        return true;
    }
}
