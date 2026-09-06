package com.digicube.party;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-connection baseline and coalesced health changes, separate from entity persistence. */
public final class PartySyncState {
    private PartySnapshotPayload snapshot;
    private boolean needsSnapshot = true;
    private final Map<UUID, PartyHealthPayload.Health> pendingHealth = new LinkedHashMap<>();

    public boolean needsSnapshot() { return needsSnapshot; }

    /** Membership or deployment changed, so health deltas alone no longer describe the party. */
    public void invalidate() { needsSnapshot = true; }

    /** Returns whether this snapshot needs sending; unchanged periodic checks stay silent. */
    public boolean updateSnapshot(PartySnapshotPayload next) {
        PartySnapshotPayload state = new PartySnapshotPayload(false, next.page(), next.total(),
                next.party(), next.collection(), "");
        boolean send = next.openScreen() || !next.message().isEmpty() || !state.equals(snapshot);
        snapshot = state;
        needsSnapshot = false;
        pendingHealth.clear();
        return send;
    }

    /** At most three comparisons, allocating only when a tracked member's health changes. */
    public void recordHealth(UUID id, float health, float maxHealth) {
        if (snapshot != null) {
            for (PartyMemberView member : snapshot.party()) {
                if (!member.id().equals(id)) continue;
                if (member.health() == health && member.maxHealth() == maxHealth) pendingHealth.remove(id);
                else pendingHealth.put(id, new PartyHealthPayload.Health(id, health, maxHealth));
                return;
            }
        }
        invalidate();
    }

    /** Drains one batch per tick; a full snapshot takes precedence over pending health changes. */
    public PartyHealthPayload takeHealthChanges() {
        if (needsSnapshot || pendingHealth.isEmpty()) return null;
        PartyHealthPayload payload = new PartyHealthPayload(List.copyOf(pendingHealth.values()));
        snapshot = payload.apply(snapshot);
        pendingHealth.clear();
        return payload;
    }
}
