package com.digicube.party;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.ManualAttacks;
import com.digicube.digimon.Progression;
import com.digicube.dev.DevActionPayload;
import com.digicube.dev.DevActions;
import com.digicube.dev.DevStatePayload;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        PartyMember second = roster.inSlot(owner, 1);
        check(roster.select(owner, first.id(), 1) && first.slot() == 1 && second.slot() == 0 && roster.party(owner).size() == 3, "two party members trade places instead of one leaving");
        check(roster.select(owner, first.id(), 0) && first.slot() == 0 && second.slot() == 1, "and trade back");
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
        check(restored.level() == 4 && restored.xp() == 120, "level and XP of a reserve partner survive reload");
        CompoundTag legacy = (CompoundTag) PartyMember.CODEC.encodeStart(NbtOps.INSTANCE, first).getOrThrow();
        legacy.remove("level");
        legacy.remove("xp");
        PartyMember beforeProgression = PartyMember.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
        check(beforeProgression.level() == 1 && beforeProgression.xp() == 0, "rosters saved before progression existed load at level 1");
        legacy.putBoolean("stowed", true);
        PartyMember recalled = PartyMember.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
        check(!recalled.active(), "a partner an older save kept recalled in its party slot loads inside the Digivice");
        check(!((CompoundTag) PartyMember.CODEC.encodeStart(NbtOps.INSTANCE, recalled).getOrThrow()).contains("stowed"), "and that state is never written again");
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
        CompoundTag fellBurning = dead.entityData();
        fellBurning.putShort("Fire", (short) 90);
        fellBurning.putInt("TicksFrozen", 40);
        fellBurning.put("active_effects", new net.minecraft.nbt.ListTag());
        dead.capture(dead.species(), "", 0, 20, dead.level(), dead.xp(), fellBurning);
        roster.add(dead);
        dead.defeat(Progression.DEFEAT_REST_TICKS);
        check(!dead.active() && !roster.select(owner, dead.id(), 0), "party storage cannot resurrect dead partners");
        CompoundTag fellData = dead.entityData();
        check(!fellData.contains("Fire") && !fellData.contains("TicksFrozen") && !fellData.contains("active_effects")
                && fellData.getStringOr("FutureTrainingData", "").equals("retained"),
                "a partner that fell burning, iced or under effects keeps none of them, and the rest of its data");
        PartyMember full = member(owner);
        full.setHealth(20);
        roster.add(full);
        int resting = (int) roster.owned(owner).stream().filter(m -> m.health() > 0 && m.health() < m.maxHealth()).count();
        float pulse = 20.0F * Progression.RESERVE_REGEN_INTERVAL_TICKS / Progression.FULL_HEAL_TICKS;
        check(PartyManager.regenerateReserve(data, owner) == resting, "a pulse heals every resting partner below full");
        check(reserve.health() == 7.5F + pulse && reserve.entityData().getFloatOr("Health", 0) == reserve.health(),
                "a resting partner regains one pulse in its saved data");
        check(dead.health() == 0 && dead.resting()
                && dead.restTicks() == Progression.DEFEAT_REST_TICKS - Progression.RESERVE_REGEN_INTERVAL_TICKS, "a defeated partner rests instead of healing");
        check(full.health() == 20, "a full partner stays full");
        check(PartyManager.regenerateReserve(data, other) == 1 && PartyManager.regenerateReserve(data, UUID.randomUUID()) == 0,
                "regeneration is scoped to one owner");
        for (int i = 1; i < Progression.DEFEAT_REST_TICKS / Progression.RESERVE_REGEN_INTERVAL_TICKS; i++) PartyManager.regenerateReserve(data, owner);
        check(dead.health() == Progression.REVIVE_HEALTH && !dead.defeated() && dead.restTicks() == 0,
                "after thirty seconds of rest a defeated partner is back on its feet with one point of health");
        for (int i = 0; i < Progression.FULL_HEAL_TICKS / Progression.RESERVE_REGEN_INTERVAL_TICKS; i++) PartyManager.regenerateReserve(data, owner);
        check(dead.health() > Progression.REVIVE_HEALTH && !dead.defeated() && dead.restTicks() == 0, "and heals on in the Digivice from there");
        dead.setHealth(20);
        check(reserve.health() == 20 && PartyManager.regenerateReserve(data, owner) == 0, "regeneration stops at full health");
        reserve.setHealth(7.5F);
        dead.capture(dead.species(), "", 0, 20, dead.level(), dead.xp(), dead.entityData());
        dead.defeat(Progression.DEFEAT_REST_TICKS);
        check(dead.resting() && !roster.select(owner, dead.id(), 0), "a resting partner cannot be selected");
        CompoundTag rested = (CompoundTag) PartyMember.CODEC.encodeStart(NbtOps.INSTANCE, dead).getOrThrow();
        check(PartyMember.CODEC.parse(NbtOps.INSTANCE, rested).getOrThrow().restTicks() == Progression.DEFEAT_REST_TICKS, "the rest survives reload");
        rested.remove("rest_ticks");
        check(PartyMember.CODEC.parse(NbtOps.INSTANCE, rested).getOrThrow().restTicks() == 0, "rosters saved before rest existed load rested");
        int healed = PartyManager.healAll(data, owner);
        check(healed == roster.owned(owner).size(), "heal touches every owned partner");
        check(reserve.health() == 20 && reserve.entityData().getFloatOr("Health", 0) == 20, "reserve partners heal in their saved data");
        check(dead.health() == 20 && !dead.defeated() && !dead.active() && dead.restTicks() == 0, "a defeated partner revives into reserve without its rest");
        check(reserve.entityData().getStringOr("FutureTrainingData", "").equals("retained"), "heal keeps the rest of the saved data");
        check(roster.select(owner, dead.id(), -1), "a revived partner is selectable again");
        check(PartyManager.healAll(data, other) == 1 && PartyManager.healAll(data, UUID.randomUUID()) == 0, "heal is scoped to one owner");

        // A partner sent into the Digivice by a defeat or by the Digivice left behind goes back to its slot once it can.
        PartyMember fallen = roster.inSlot(owner, 2);
        check(fallen != null, "a partner stands in the third slot");
        fallen.stow();
        fallen.capture(fallen.species(), "", 0, 20, fallen.level(), fallen.xp(), fallen.entityData());
        fallen.defeat(Progression.DEFEAT_REST_TICKS);
        check(!fallen.active() && fallen.returnSlot() == 2 && fallen.resting(), "a fallen partner rests in the Digivice, remembering its slot");
        check(PartyManager.regroup(data, owner) == 0 && !fallen.active() && fallen.returnSlot() == 2, "resting, it does not go back yet");
        CompoundTag fallenSave = (CompoundTag) PartyMember.CODEC.encodeStart(NbtOps.INSTANCE, fallen).getOrThrow();
        check(PartyMember.CODEC.parse(NbtOps.INSTANCE, fallenSave).getOrThrow().returnSlot() == 2, "the slot to go back to survives reload");
        var rosterSave = PartyRoster.CODEC.encodeStart(NbtOps.INSTANCE, roster).getOrThrow();
        check(PartyRoster.CODEC.parse(NbtOps.INSTANCE, rosterSave).getOrThrow().get(fallen.id()).returnSlot() == 2, "and the roster's repair on load keeps it");
        fallenSave.putInt("return_slot", 7);
        check(PartyMember.CODEC.parse(NbtOps.INSTANCE, fallenSave).getOrThrow().returnSlot() == -1, "a slot that does not exist is dropped");
        for (int i = 0; i < Progression.DEFEAT_REST_TICKS / Progression.RESERVE_REGEN_INTERVAL_TICKS; i++) PartyManager.regenerateReserve(data, owner);
        check(fallen.health() == Progression.REVIVE_HEALTH && !fallen.active() && fallen.returnSlot() == 2, "rested, it has one point of health");
        check(PartyManager.regroup(data, owner) == 1 && fallen.slot() == 2 && fallen.returnSlot() == -1, "and goes back to its own slot");
        fallen.stow();
        PartyMember standIn = roster.owned(owner).stream().filter(m -> !m.active() && !m.defeated() && !m.egg() && m != fallen).findFirst().orElseThrow();
        check(roster.select(owner, standIn.id(), 2) && standIn.slot() == 2, "the tamer puts another partner in the empty slot");
        check(PartyManager.regroup(data, owner) == 0 && !fallen.active() && fallen.returnSlot() == -1, "a slot filled meanwhile keeps its new partner");
        fallen.stow();
        check(fallen.returnSlot() == -1, "stowing a partner already in the Digivice remembers nothing");
        List<PartyMember> formation = roster.party(owner);
        int[] places = formation.stream().mapToInt(PartyMember::slot).toArray();
        formation.forEach(PartyMember::stow);
        check(roster.party(owner).isEmpty(), "the Digivice away: the whole party is in it");
        check(PartyManager.regroup(data, owner) == formation.size(), "the Digivice back: every partner goes back");
        for (int index = 0; index < formation.size(); index++)
            check(formation.get(index).slot() == places[index] && formation.get(index).returnSlot() == -1, "each to the slot it stood in");
        formation.getFirst().stow();
        check(roster.select(owner, formation.getFirst().id(), -1) && formation.getFirst().returnSlot() == -1, "a choice by hand forgets the old slot");
        check(roster.select(owner, formation.getFirst().id(), places[0]), "and the tamer may put it back");
        PartyMember malformed = member(owner);
        malformed.setSlot(99);
        PartyMember duplicateSlot = member(owner);
        duplicateSlot.setSlot(first.slot());
        var invalidSave = PartyMember.CODEC.listOf().encodeStart(NbtOps.INSTANCE, List.of(first, malformed, duplicateSlot)).getOrThrow();
        PartyRoster repaired = PartyRoster.CODEC.parse(NbtOps.INSTANCE, invalidSave).getOrThrow();
        check(repaired.owned(owner).size() == 3 && repaired.party(owner).size() == 1, "bad save slots repaired without deleting partners");
        check(new PartySavedData().roster().all().isEmpty(), "separate world registries do not share state");

        PartyMemberView view = PartyMemberView.of(data, first);
        // Universal control: AUTO settings live in the stored entity data and travel as a mask in the snapshot.
        check(ManualAttacks.read(first.entityData()).isEmpty() && view.manual() == 0 && view.target() == -1, "every attack starts on AUTO, with no target");
        CompoundTag stored = new CompoundTag();
        ManualAttacks.write(stored, new LinkedHashSet<>(List.of(Constants.id("claw"))));
        check(ManualAttacks.read(stored).equals(Set.of(Constants.id("claw"))), "a manual attack survives being saved");
        ManualAttacks.write(stored, Set.of());
        check(!stored.contains(ManualAttacks.TAG), "an empty set leaves no key behind");
        first.setManual(Constants.id("claw"), true);
        check(ManualAttacks.read(first.entityData()).equals(Set.of(Constants.id("claw"))), "a stored partner's attack goes manual in its saved data");
        check(first.entityData().getStringOr("FutureTrainingData", "").equals("retained"), "and the rest of its data stays");
        first.setManual(Constants.id("claw"), false);
        check(ManualAttacks.read(first.entityData()).isEmpty(), "and back on AUTO");
        DigimonAttack bite = new DigimonAttack(Constants.id("bite"), DigimonAttack.Kind.MELEE, 1, 20, 10, 5, 2, false);
        DigimonAttack spit = new DigimonAttack(Constants.id("spit"), DigimonAttack.Kind.FIREBALL, 1, 20, 10, 5, 8, false);
        check(ManualAttacks.mask(List.of(bite, spit), Set.of(spit.id())) == 0b10 && ManualAttacks.manual(0b10, 1) && !ManualAttacks.manual(0b10, 0),
                "the mask has one bit per sheet slot");
        PartyMemberView manualView = view.withManual(1, true);
        check(manualView.manual(1) && !manualView.manual(0) && !manualView.withManual(1, false).manual(1) && manualView.slot() == view.slot(),
                "the wheel's flip shows at once and keeps the party slot");
        check(PartyActionPayload.autoValue(1, true) == 3 && PartyActionPayload.autoValue(1, false) >> 1 == 1
                && (PartyActionPayload.autoValue(0, true) & 1) == 1, "an AUTO flip carries the slot and the setting");
        PartySnapshotPayload snapshot = new PartySnapshotPayload(true, 2, 19, List.of(manualView), List.of(view), "", List.of(view.species()));
        check(PartyManager.knownSpecies(data, owner).contains(first.species()), "the Analyzer knows every species the tamer owns");
        check(new PartySnapshotPayload(false, 0, 0, List.of(), List.of(), "").known().isEmpty(), "a snapshot without the Digivice open names no species");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PartySnapshotPayload.STREAM_CODEC.encode(buffer, snapshot);
            PartySnapshotPayload received = PartySnapshotPayload.STREAM_CODEC.decode(buffer);
            check(received.equals(snapshot) && received.party().getFirst().manual(1), "network snapshot round-trip, AUTO settings included");
            buffer.clear();
            PartyActionPayload action = new PartyActionPayload(PartyActionPayload.SELECT, first.id(), -1);
            PartyActionPayload.STREAM_CODEC.encode(buffer, action);
            check(PartyActionPayload.STREAM_CODEC.decode(buffer).equals(action), "recall action round-trip");
            buffer.clear();
            PartyActionPayload order = new PartyActionPayload(PartyActionPayload.ATTACK, first.id(), 1);
            PartyActionPayload.STREAM_CODEC.encode(buffer, order);
            check(PartyActionPayload.STREAM_CODEC.decode(buffer).equals(order), "attack order round-trip");
            buffer.clear();
            CompoundTag devArgs = new CompoundTag();
            devArgs.put(DevActions.SIDE_A_ARG, com.digicube.dev.BattleRoster.write(List.of(new com.digicube.dev.BattleRoster.Entry("agumon", 7, 3))));
            DevActionPayload devAction = new DevActionPayload(DevActions.BATTLE_START, devArgs);
            DevActionPayload.STREAM_CODEC.encode(buffer, devAction);
            check(DevActionPayload.STREAM_CODEC.decode(buffer).equals(devAction), "dev action round-trip");
            buffer.clear();
            DevStatePayload devState = new DevStatePayload(devArgs, "agumon Lv 7 vs gabumon Lv 7");
            DevStatePayload.STREAM_CODEC.encode(buffer, devState);
            check(DevStatePayload.STREAM_CODEC.decode(buffer).equals(devState), "dev state round-trip");
            check(new DevStatePayload(devArgs, "x".repeat(400)).reply().length() == DevStatePayload.MAX_REPLY_LENGTH, "dev reply is bounded");
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
                4, 120, -1, 0, entity);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
