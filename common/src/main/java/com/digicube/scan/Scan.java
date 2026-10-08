package com.digicube.scan;

import com.digicube.digimon.DigimonFamilies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.party.PartySavedData;
import com.digicube.platform.Services;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The scan, on the server: every defeat of a wild Digimon fills its family's bar for the tamers whose partners hurt it,
 * split as XP is ({@link com.digicube.digimon.Progression#scanSplit}); the first sighting of a family fills a part of
 * it; a full bar converts into a Digitama of the family, an item in the tamer's inventory ({@link DigitamaItem}) that,
 * once used, waits in the Digivice and hatches into the family's first form. Each change reaches the tamer as a toast
 * ({@link ScanToastPayload}) and in the next party snapshot.
 */
public final class Scan {
    private Scan() {}

    /**
     * A wild Digimon of {@code species} was defeated: each tamer in {@code byOwner} gets that much data in its family's
     * bar, after the family's first sighting if this is it. A species of no family feeds nothing.
     */
    public static void credit(MinecraftServer server, Identifier species, Map<UUID, Integer> byOwner) {
        Identifier family = DigimonFamilies.of(species);
        if (family == null || byOwner.isEmpty()) return;
        ScanSavedData data = ScanSavedData.get(server);
        byOwner.forEach((owner, amount) -> {
            ScanRecord record = data.record(owner);
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            // A family fought is a family seen, wherever the tamer was looking.
            if (!record.seen(family)) {
                ScanRecord.Gain sighted = record.sight(family);
                if (player != null) announce(player, family, sighted, ScanToastPayload.Kind.SIGHTED, record.data(family));
            }
            ScanRecord.Gain gain = record.defeat(family, amount);
            if (player != null) announce(player, family, gain, ScanToastPayload.Kind.DATA, record.data(family));
            PartySavedData.get(server).session(owner).sync.invalidate();
        });
        data.setDirty();
    }

    /** {@code player} has seen Digimon of {@code species}: each family seen for the first time fills its first-sighting part. */
    public static void sight(ServerPlayer player, Collection<Identifier> species) {
        MinecraftServer server = player.level().getServer();
        ScanSavedData data = ScanSavedData.get(server);
        ScanRecord record = data.record(player.getUUID());
        boolean changed = false;
        for (Identifier id : species) {
            Identifier family = DigimonFamilies.of(id);
            if (family == null || record.seen(family)) continue;
            announce(player, family, record.sight(family), ScanToastPayload.Kind.SIGHTED, record.data(family));
            changed = true;
        }
        if (!changed) return;
        data.setDirty();
        PartySavedData.get(server).session(player.getUUID()).sync.invalidate();
    }

    private static void announce(ServerPlayer player, Identifier family, ScanRecord.Gain gain, ScanToastPayload.Kind kind, int now) {
        if (gain.added() > 0) Services.PLATFORM.sendToPlayer(player, new ScanToastPayload(kind, family, gain.added(), now));
        if (gain.lost() > 0) Services.PLATFORM.sendToPlayer(player, new ScanToastPayload(ScanToastPayload.Kind.LOST, family, gain.lost(), now));
        if (gain.readied()) Services.PLATFORM.sendToPlayer(player, new ScanToastPayload(ScanToastPayload.Kind.READY, family, 0, now));
    }

    /** Operator tooling: {@code player}'s bar of {@code family} holds {@code data}, and the family counts as sighted. */
    public static void set(ServerPlayer player, Identifier family, int data) {
        MinecraftServer server = player.level().getServer();
        ScanSavedData saved = ScanSavedData.get(server);
        saved.record(player.getUUID()).set(family, data);
        saved.setDirty();
        PartySavedData.get(server).session(player.getUUID()).sync.invalidate();
    }

    /** A Digitama of {@code family} hatched in {@code player}'s Digivice. */
    public static void hatched(ServerPlayer player, Identifier family) {
        Services.PLATFORM.sendToPlayer(player, new ScanToastPayload(ScanToastPayload.Kind.HATCHED, family, 0,
                ScanSavedData.get(player.level().getServer()).record(player.getUUID()).data(family)));
    }

    /** The tamer's bars, one per family in {@link DigimonFamilies#all} order. */
    public static List<ScanBar> bars(MinecraftServer server, UUID player) {
        ScanRecord record = ScanSavedData.get(server).record(player);
        List<ScanBar> bars = new ArrayList<>();
        for (Identifier family : DigimonFamilies.all()) {
            bars.add(new ScanBar(family, record.data(family), record.seen(family), record.defeatsLeft(family)));
        }
        return bars;
    }

    /**
     * CONVERT: spends a Digitama's worth of the {@code index}th family's data and puts a Digitama of it in the tamer's
     * inventory, which needs a free slot.
     * @return a translation key on failure, or the empty string
     */
    public static String convert(ServerPlayer player, int index) {
        List<Identifier> families = DigimonFamilies.all();
        if (index < 0 || index >= families.size()) return "gui.digicube.party.invalid";
        Identifier family = families.get(index);
        MinecraftServer server = player.level().getServer();
        ScanSavedData data = ScanSavedData.get(server);
        ScanRecord record = data.record(player.getUUID());
        if (!record.ready(family)) return "gui.digicube.scan.not_ready";
        if (DigimonSpeciesRegistry.get(family).isEmpty()) return "gui.digicube.party.species_missing";
        if (player.getInventory().getFreeSlot() < 0 || !player.getInventory().add(DigitamaItem.of(family))) return "gui.digicube.scan.no_room";
        record.convert(family);
        data.setDirty();
        PartySavedData.get(server).session(player.getUUID()).sync.invalidate();
        return "";
    }
}
