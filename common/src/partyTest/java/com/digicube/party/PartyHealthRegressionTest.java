package com.digicube.party;

import com.digicube.Constants;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;
import java.util.UUID;

/** Checks health latency, batching and connection isolation without a running world. */
final class PartyHealthRegressionTest {
    private PartyHealthRegressionTest() {}

    static void run() {
        PartyMemberView first = member(0);
        PartyMemberView second = member(1);
        PartyMemberView third = member(2);
        List<PartyMemberView> party = List.of(first, second, third);
        PartySnapshotPayload initial = new PartySnapshotPayload(false, 0, 3, party, party, "");
        PartySyncState sync = new PartySyncState();
        check(sync.needsSnapshot() && sync.updateSnapshot(initial), "new connection receives an initial snapshot");
        check(!sync.needsSnapshot() && !sync.updateSnapshot(initial), "unchanged periodic snapshots are suppressed");
        for (int tick = 0; tick < 100; tick++) {
            for (PartyMemberView member : party) sync.recordHealth(member.id(), member.health(), member.maxHealth());
            check(sync.takeHealthChanges() == null, "idle party sends no health packets");
        }

        sync.recordHealth(first.id(), 6, 20);
        sync.recordHealth(first.id(), 4, 20);
        sync.recordHealth(second.id(), 10, 20);
        sync.recordHealth(third.id(), 7.5F, 30);
        PartyHealthPayload health = sync.takeHealthChanges();
        check(health != null && health.members().size() == 3, "one batch covers damage, healing and max-health changes");
        check(health.update(first).health() == 4, "multiple changes within a tick coalesce to the latest health");
        check(health.update(second).health() == 10 && health.update(third).maxHealth() == 30,
                "healing and max health do not wait for the periodic snapshot");
        check(sync.takeHealthChanges() == null, "batch is sent only once");

        PartySnapshotPayload patched = health.apply(new PartySnapshotPayload(true, 0, 3, party, party, "feedback"));
        check(patched.party().getFirst().health() == 4 && patched.collection().getFirst().health() == 4,
                "HUD and visible collection receive the same current health");
        check(!patched.openScreen() && patched.message().isEmpty(), "health does not replay screen or feedback events");
        check(patched.party().getFirst().slot() == first.slot()
                        && patched.party().getFirst().species().equals(first.species())
                        && patched.party().getFirst().nickname().equals(first.nickname())
                        && patched.party().getFirst().level() == first.level()
                        && patched.party().getFirst().xp() == first.xp(),
                "health leaves party identity, presentation and progression intact");
        check(!sync.updateSnapshot(patched), "periodic snapshot does not resend already delivered health");

        sync.recordHealth(first.id(), 3, 20);
        PartyHealthPayload followingTick = sync.takeHealthChanges();
        check(followingTick != null && followingTick.update(first).health() == 3,
                "another hit on the next tick sends immediately, with no one-second gate");
        sync.recordHealth(first.id(), 1, 20);
        sync.recordHealth(first.id(), 3, 20);
        check(sync.takeHealthChanges() == null, "a change reversed within the tick needs no packet");

        PartyMemberView unknown = member(0);
        PartyHealthPayload foreign = new PartyHealthPayload(List.of(new PartyHealthPayload.Health(unknown.id(), 1, 20)));
        check(foreign.apply(patched).equals(patched), "unknown ids cannot add party or collection members");
        sync.recordHealth(unknown.id(), 5, 20);
        check(sync.needsSnapshot() && sync.takeHealthChanges() == null, "newly deployed partner needs a full snapshot");

        sync.updateSnapshot(patched);
        sync.recordHealth(first.id(), 0, 20);
        sync.invalidate();
        check(sync.takeHealthChanges() == null, "death or recall prioritizes membership over queued health");
        PartyMemberView defeated = new PartyMemberView(first.id(), first.species(), first.nickname(), 0, 20,
                first.level(), first.xp(), -1, false, 6000);
        PartySnapshotPayload afterDeath = new PartySnapshotPayload(false, 0, 3,
                List.of(patched.party().get(1), patched.party().get(2)),
                List.of(defeated, patched.collection().get(1), patched.collection().get(2)), "");
        check(sync.updateSnapshot(afterDeath), "defeat immediately publishes the changed party");
        check(sync.takeHealthChanges() == null, "full snapshot discards obsolete queued health");

        PartySavedData data = new PartySavedData();
        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        data.session(owner).sync.updateSnapshot(initial);
        data.session(owner).sync.recordHealth(first.id(), 2, 20);
        check(data.session(otherOwner).sync.takeHealthChanges() == null, "health stays in its owner's session");
        data.forgetSession(owner);
        check(data.session(owner).sync.needsSnapshot() && data.session(owner).sync.takeHealthChanges() == null,
                "reconnect starts with no previous connection's health baseline or pending changes");

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PartyHealthPayload.STREAM_CODEC.encode(buffer, health);
            check(buffer.readableBytes() == 73, "three health updates use 73 bytes before packet framing");
            check(PartyHealthPayload.STREAM_CODEC.decode(buffer).equals(health), "health payload round-trip");
            for (int count : new int[]{-1, PartyRoster.PARTY_SIZE + 1}) {
                buffer.clear();
                buffer.writeVarInt(count);
                boolean rejected = false;
                try { PartyHealthPayload.STREAM_CODEC.decode(buffer); }
                catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "invalid health list size rejected before reading or allocating members");
            }
        } finally {
            buffer.release();
        }
        Constants.LOG.info("Party health checks passed: per-tick damage/healing, batching, idle traffic, views, defeat, sessions and codec.");
    }

    private static PartyMemberView member(int slot) {
        return new PartyMemberView(UUID.randomUUID(), Constants.id("agumon"), "Partner " + slot, 7.5F, 20, 3, 40, slot, true, 0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
