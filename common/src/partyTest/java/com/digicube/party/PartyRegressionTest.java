package com.digicube.party;

import com.digicube.Constants;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Standalone server-safe regression suite; requires no game, test framework or extra dependency. */
public final class PartyRegressionTest {
    private PartyRegressionTest() {}

    public static void main(String[] args) throws IOException {
        try {
            run();
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static void run() throws IOException {
        SharedConstants.tryDetectVersion();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PartySavedData data = new PartySavedData();
        PartyRoster roster = data.roster();
        for (int index = 0; index < 19; index++) check(roster.add(member(owner)), "new individual accepted");
        roster.add(member(other));
        check(roster.party(owner).size() == 3, "only first three gifts active");
        check(roster.owned(owner).size() == 19, "overflow retained without a collection limit");
        check(roster.party(other).size() == 1, "per-player cap");
        PartyMember first = roster.inSlot(owner, 0);
        PartyMember reserve = roster.owned(owner).get(5);
        check(!roster.add(first), "duplicate entity admission refused");
        check(!roster.select(other, reserve.id(), 0), "foreign player cannot edit ownership");
        check(!roster.select(owner, UUID.randomUUID(), 0), "unknown id refused");
        check(!roster.select(owner, reserve.id(), 3), "fourth slot refused");
        check(!roster.select(owner, reserve.id(), -2), "invalid negative slot refused");
        check(roster.inSlot(owner, 0) == first && !reserve.active(), "invalid requests leave state intact");
        check(roster.select(owner, reserve.id(), 0), "reserve can replace a full slot");
        check(!first.active() && reserve.slot() == 0 && roster.party(owner).size() == 3, "swap is atomic");
        check(roster.select(owner, reserve.id(), -1), "recall clears party slot");
        check(roster.owned(owner).size() == 19 && roster.party(owner).size() == 2, "recall preserves individual");
        check(roster.select(owner, first.id(), 0), "stored partner can return");
        long oldGeneration = first.generation();
        check(roster.accepts(first.id(), owner, oldGeneration), "selected incarnation admitted");
        first.nextGeneration();
        check(!roster.accepts(first.id(), owner, oldGeneration), "old chunk incarnation refused after redeployment");
        check(roster.accepts(first.id(), owner, first.generation()), "new incarnation admitted");
        check(!roster.accepts(first.id(), other, first.generation()), "forged entity owner refused");
        check(!roster.accepts(reserve.id(), owner, reserve.generation()), "reserve entity cannot load into world");

        var encoded = PartySavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        PartySavedData decoded = PartySavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        PartyMember restored = decoded.roster().get(first.id());
        check(restored.owner().equals(owner) && restored.id().equals(first.id()), "stable owner and partner identity on reload");
        check(restored.generation() == first.generation(), "generation survives reload");
        check(restored.slot() == 0 && decoded.roster().party(owner).size() == 3, "selection survives reload");
        check(restored.entityData().equals(first.entityData()) && restored.health() == 7.5F, "health, nickname and future entity data preserved");
        // Exercise Minecraft's actual disk API too: a valid codec alone does not verify
        // SavedDataType/data-fixer configuration or asynchronous write completion.
        Path directory = Files.createTempDirectory("digicube-party-test-").toAbsolutePath().normalize();
        try {
            try (var storage = new SavedDataStorage(directory, DataFixers.getDataFixer(), RegistryAccess.EMPTY)) {
                storage.set(PartySavedData.TYPE, data);
                storage.saveAndJoin();
            }
            try (var storage = new SavedDataStorage(directory, DataFixers.getDataFixer(), RegistryAccess.EMPTY)) {
                PartySavedData disk = storage.get(PartySavedData.TYPE);
                check(disk != null && disk.roster().get(first.id()).entityData().equals(first.entityData()), "Minecraft disk storage round-trip");
                check(disk.roster().party(owner).size() == 3, "disk reload retains party cap and selection");
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (!file.toAbsolutePath().normalize().startsWith(directory)) throw new IOException("Unexpected test cleanup path");
                    Files.deleteIfExists(file);
                }
            }
        }
        CompoundTag external = restored.entityData();
        external.putString("FutureTrainingData", "tampered");
        check(!external.equals(restored.entityData()), "snapshots cannot be mutated through accessors");

        PartyMember dead = member(owner);
        dead.capture(dead.species(), "", 0, 20, dead.entityData());
        roster.add(dead);
        check(!dead.active() && !roster.select(owner, dead.id(), 0), "party storage cannot resurrect dead partners");
        PartyMember malformed = member(owner);
        malformed.setSlot(99);
        PartyMember duplicateSlot = member(owner);
        duplicateSlot.setSlot(first.slot());
        var invalidSave = PartyMember.CODEC.listOf().encodeStart(NbtOps.INSTANCE, List.of(first, malformed, duplicateSlot)).getOrThrow();
        PartyRoster repaired = PartyRoster.CODEC.parse(NbtOps.INSTANCE, invalidSave).getOrThrow();
        check(repaired.owned(owner).size() == 3 && repaired.party(owner).size() == 1, "bad save slots repaired without deleting partners");
        check(new PartySavedData().roster().all().isEmpty(), "separate world registries do not share state");

        PartyMemberView view = PartyMemberView.of(data, first);
        PartySnapshotPayload snapshot = new PartySnapshotPayload(true, 2, 19, List.of(view), List.of(view), "");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PartySnapshotPayload.STREAM_CODEC.encode(buffer, snapshot);
            check(PartySnapshotPayload.STREAM_CODEC.decode(buffer).equals(snapshot), "network snapshot round-trip");
            buffer.clear();
            PartyActionPayload action = new PartyActionPayload(PartyActionPayload.SELECT, first.id(), -1);
            PartyActionPayload.STREAM_CODEC.encode(buffer, action);
            check(PartyActionPayload.STREAM_CODEC.decode(buffer).equals(action), "recall action round-trip");
            buffer.clear();
            buffer.writeBoolean(false);
            buffer.writeVarInt(0);
            buffer.writeVarInt(1000);
            buffer.writeVarInt(1000);
            boolean rejected = false;
            try { PartySnapshotPayload.STREAM_CODEC.decode(buffer); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "oversized network member lists rejected before allocation");
        } finally { buffer.release(); }
        PartyHealthRegressionTest.run();
        DigimonAnimationRegressionTest.run();
        Constants.LOG.info("Party regression checks passed: cap, ownership, swaps, persistence, generation, defeat and packets.");
    }

    private static PartyMember member(UUID owner) {
        CompoundTag entity = new CompoundTag();
        entity.putFloat("Health", 7.5F);
        entity.putString("CustomName", "My partner");
        entity.putString("FutureTrainingData", "retained");
        return new PartyMember(UUID.randomUUID(), owner, Constants.id("agumon"), "My partner", 7.5F, 20,
                -1, 0, entity);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
