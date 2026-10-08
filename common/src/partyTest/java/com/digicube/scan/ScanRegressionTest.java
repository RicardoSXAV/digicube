package com.digicube.scan;

import com.digicube.Constants;
import com.digicube.digimon.DigimonFamilies;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.DigimonStage;
import com.digicube.digimon.Progression;
import com.digicube.party.PartySnapshotPayload;
import com.digicube.spawn.BundledSpawnTableLoader;
import com.digicube.spawn.SpawnDanger;
import com.digicube.spawn.SpawnHabitat;
import com.digicube.spawn.SpawnTable;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pins the scan without a game: the bar's rules, the pace, the families, the data split, the habitat line, saving and the packets. */
public final class ScanRegressionTest {
    private static final Identifier KOROMON = Constants.id("koromon"), TSUNOMON = Constants.id("tsunomon"),
            BUKAMON = Constants.id("pukamon"), MOTIMON = Constants.id("mochimon");
    private static int checks;

    private ScanRegressionTest() {}

    /** @param args unused */
    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            DigimonSpeciesBootstrap.registerBuiltIn();
            checkNumbers();
            checkBar();
            checkPace();
            checkFamilies();
            checkSplit();
            checkHabitat();
            checkSaving();
            checkPackets();
            Constants.LOG.info("Scan regression checks passed ({} checks): numbers, bar, pace, families, split, habitat, saving and packets.", checks);
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static void checkNumbers() {
        check(Progression.DIGITAMA_DATA == 100 && Progression.FIRST_SIGHTING_DATA == 30 && Progression.SCAN_CAPACITY == 200,
                "a Digitama is 100 data, a first sighting 30, a bar holds 200");
        check(Progression.DIGITAMA_HATCH_TICKS == 12000 && Progression.GROWTH_LEVEL == 10 && Progression.CHAMPION_LEVEL == 20,
                "ten minutes to hatch, growth at 10, Champions at 20");
    }

    private static void checkBar() {
        ScanRecord record = new ScanRecord();
        check(record.data(KOROMON) == 0 && !record.seen(KOROMON) && !record.ready(KOROMON), "a fresh scan is empty");
        ScanRecord.Gain sighted = record.sight(KOROMON);
        check(sighted.added() == 30 && sighted.lost() == 0 && !sighted.readied() && record.seen(KOROMON), "the first sighting gives 30");
        check(record.sight(KOROMON) == ScanRecord.Gain.NONE && record.data(KOROMON) == 30, "a second sighting gives nothing");
        ScanRecord.Gain toReady = record.defeat(KOROMON, 70);
        check(toReady.added() == 70 && toReady.readied() && record.ready(KOROMON), "reaching 100 readies the Digitama");
        check(!record.defeat(KOROMON, 5).readied(), "readied only once on the way up");
        ScanRecord.Gain over = record.defeat(KOROMON, 120);
        check(over.added() == 95 && over.lost() == 25 && record.data(KOROMON) == 200, "past 200 the rest is lost");
        check(record.convert(KOROMON) && record.data(KOROMON) == 100 && record.convert(KOROMON) && record.data(KOROMON) == 0,
                "two Digitama out of a full bar");
        check(!record.convert(KOROMON) && record.data(KOROMON) == 0, "an empty bar converts nothing");
        check(record.defeat(KOROMON, 0) == ScanRecord.Gain.NONE && record.defeat(KOROMON, -3) == ScanRecord.Gain.NONE, "nothing gained is no gain");
        record.set(TSUNOMON, 999);
        check(record.data(TSUNOMON) == 200 && record.seen(TSUNOMON), "set clamps to the bar and sights the family");
    }

    private static void checkPace() {
        ScanRecord record = new ScanRecord();
        check(record.defeatsLeft(KOROMON) == -1, "no data lately: no pace");
        record.defeat(KOROMON, 10);
        record.defeat(TSUNOMON, 7);
        // Koromon's family: 10 data in 2 defeats, 90 to go: 18 defeats
        check(record.defeatsLeft(KOROMON) == 18, "the mean counts every defeat, another family's as nothing: " + record.defeatsLeft(KOROMON));
        check(record.defeatsLeft(TSUNOMON) == 27, "Tsunomon's 93 to go at 3.5 a defeat is 27");
        for (int i = 0; i < Progression.SCAN_PACE_DEFEATS; i++) record.defeat(TSUNOMON, 5);
        check(record.defeatsLeft(KOROMON) == -1, "after twenty other defeats Koromon's falls out of the window");
        record.set(KOROMON, 100);
        check(record.defeatsLeft(KOROMON) == 0, "a ready bar has no defeats left");
    }

    private static void checkFamilies() {
        check(DigimonFamilies.all().equals(List.of(KOROMON, TSUNOMON, BUKAMON, MOTIMON)), "four families, Koromon's, Tsunomon's, Bukamon's and Motimon's");
        check(DigimonFamilies.of(Constants.id("greymon")).equals(KOROMON) && DigimonFamilies.of(Constants.id("darktyrannomon")).equals(TSUNOMON)
                && DigimonFamilies.of(Constants.id("shellmon")).equals(BUKAMON) && DigimonFamilies.of(Constants.id("digmon")).equals(MOTIMON)
                && DigimonFamilies.of(KOROMON).equals(KOROMON), "every form belongs to its first form's family");
        int members = 0;
        for (Identifier family : DigimonFamilies.all()) members += DigimonFamilies.members(family).size();
        check(members == DigimonSpeciesRegistry.size(), "the families hold every species once");
        check(DigimonFamilies.members(KOROMON).size() == 7 && DigimonFamilies.members(TSUNOMON).size() == 7
                && DigimonFamilies.members(KOROMON).getFirst().equals(KOROMON), "seven Koromon forms, seven Tsunomon forms, the first form first");
        check(DigimonFamilies.index(BUKAMON) == 2 && DigimonFamilies.index(Constants.id("agumon")) == -1, "a family's index; a Rookie is no family");
    }

    private static void checkSplit() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        check(Progression.scanSplit(DigimonStage.CHILD, 10, List.of(new Progression.Contributor(a, 10, 30))).get(a) == 10,
                "a Rookie of the partner's level gives 10, whatever its level");
        check(Progression.scanSplit(DigimonStage.BABY_II, 1, List.of(new Progression.Contributor(a, 30, 5))).get(a) == 3,
                "a far weaker wild still gives half: 6 x 50 %");
        check(Progression.scanSplit(DigimonStage.ADULT, 20, List.of(new Progression.Contributor(a, 5, 9))).get(a) == 27,
                "a far stronger wild gives 150 %: 18 x 1.5");
        Map<UUID, Integer> shared = Progression.scanSplit(DigimonStage.CHILD, 8, List.of(
                new Progression.Contributor(a, 8, 70), new Progression.Contributor(b, 8, 30)));
        check(shared.get(a) == 7 && shared.get(b) == 3, "two tamers' partners split by damage, as XP");
        shared = Progression.scanSplit(DigimonStage.CHILD, 8, List.of(new Progression.Contributor(a, 8, 99), new Progression.Contributor(b, 8, 1)));
        check(shared.get(b) == 1, "a tap earns 1");
        check(Progression.scanSplit(DigimonStage.CHILD, 8, List.of()).isEmpty(), "no damage, no data");
    }

    private static void checkHabitat() {
        List<SpawnTable> tables = BundledSpawnTableLoader.load(id -> DigimonSpeciesRegistry.get(id).isPresent());
        List<SpawnHabitat.Place> koromon = SpawnHabitat.of(tables, DigimonFamilies.members(KOROMON));
        check(!koromon.isEmpty() && koromon.getFirst().region().danger() == SpawnDanger.CALM
                && koromon.getLast().region().danger() == SpawnDanger.DANGEROUS, "calm land first, dangerous land last");
        for (int i = 1; i < koromon.size(); i++) check(koromon.get(i - 1).region().danger().ordinal() <= koromon.get(i).region().danger().ordinal(), "ordered by danger");
        SpawnHabitat.Place plains = koromon.stream().filter(p -> p.region().id().equals("plains")).findFirst().orElseThrow();
        check(plains.minLevel() == 1 && plains.maxLevel() == 11, "plains: Koromon 1-6 and Agumon 3-11");
        SpawnHabitat.Place badlands = koromon.stream().filter(p -> p.region().id().equals("badlands")).findFirst().orElseThrow();
        check(badlands.minLevel() == 5 && badlands.maxLevel() == 22, "badlands: Koromon 5-10 up to Greymon and Meramon at 22");
        for (Identifier family : DigimonFamilies.all()) {
            List<SpawnHabitat.Place> places = SpawnHabitat.of(tables, DigimonFamilies.members(family));
            check(places.stream().anyMatch(p -> p.region().danger() == SpawnDanger.CALM)
                    && places.stream().anyMatch(p -> p.region().danger() == SpawnDanger.DANGEROUS), family + " lives in calm and in dangerous land");
        }
    }

    private static void checkSaving() {
        ScanSavedData data = new ScanSavedData();
        UUID player = UUID.randomUUID();
        ScanRecord record = data.record(player);
        record.sight(KOROMON);
        record.defeat(KOROMON, 12);
        record.defeat(TSUNOMON, 4);
        var encoded = ScanSavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        ScanRecord loaded = ScanSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow().record(player);
        check(loaded.data(KOROMON) == 42 && loaded.seen(KOROMON) && loaded.data(TSUNOMON) == 4 && !loaded.seen(TSUNOMON),
                "bars and sightings are saved");
        check(loaded.defeatsLeft(KOROMON) == record.defeatsLeft(KOROMON) && loaded.defeatsLeft(TSUNOMON) == record.defeatsLeft(TSUNOMON),
                "the pace window is saved");
        check(ScanSavedData.CODEC.parse(NbtOps.INSTANCE, new net.minecraft.nbt.CompoundTag()).getOrThrow().record(player).data(KOROMON) == 0,
                "an empty save loads empty");
    }

    private static void checkPackets() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            List<ScanBar> bars = List.of(new ScanBar(KOROMON, 130, true, 0), new ScanBar(TSUNOMON, 0, false, -1));
            PartySnapshotPayload snapshot = new PartySnapshotPayload(true, 0, 0, List.of(), List.of(), "", List.of(), 0, bars);
            PartySnapshotPayload.STREAM_CODEC.encode(buffer, snapshot);
            check(PartySnapshotPayload.STREAM_CODEC.decode(buffer).scan().equals(bars), "the snapshot carries the bars");
            buffer.clear();
            ScanToastPayload toast = new ScanToastPayload(ScanToastPayload.Kind.READY, KOROMON, 0, 100);
            ScanToastPayload.STREAM_CODEC.encode(buffer, toast);
            check(ScanToastPayload.STREAM_CODEC.decode(buffer).equals(toast), "a toast round-trips");
            check(new PartySnapshotPayload(false, 0, 0, List.of(), List.of(), "").scan().isEmpty(), "an older snapshot constructor has no bars");
        } finally {
            buffer.release();
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
