package com.digicube.analyzer;

import com.digicube.Constants;
import com.digicube.digimon.CombatMark;
import com.digicube.digimon.CrackMark;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.FreezeMark;
import com.digicube.digimon.IceCombo;
import com.digicube.entity.CombatMarkState;
import com.digicube.entity.MegaFlameEntity;
import com.digicube.entity.PepperBreathEntity;
import com.digicube.party.PartySnapshotPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pins the Analyzer's record without a world: which mark each move leaves and how long it lasts, which emblems a body
 * shows, what counts as in view, the record's save and its packets.
 */
public final class AnalyzerRegressionTest {
    private static int checks;

    private AnalyzerRegressionTest() {}

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            DigimonSpeciesBootstrap.registerBuiltIn();
            marks();
            witness();
            record();
            packets();
            Constants.LOG.info("[analyzer-test] PASS {} checks", checks);
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static DigimonAttack attack(String species, String move) {
        DigimonSpecies sheet = DigimonSpeciesRegistry.getOrThrow(Constants.id(species));
        return sheet.attacks().stream().filter(a -> a.id().getPath().equals(move)).findFirst()
                .orElseThrow(() -> new IllegalStateException(species + " has no " + move));
    }

    private static void marks() {
        Map<String, CombatMark> expected = Map.of("garurumon/freeze_fang", CombatMark.FREEZE, "garurumon/howling_blaster", CombatMark.FREEZE,
                "seadramon/ice_blast", CombatMark.COLD, "seadramon/constriction", CombatMark.HELD, "gesomon/deadly_shade", CombatMark.INKED,
                "golemon/rock_punch", CombatMark.CRACK, "golemon/tectonic_fist", CombatMark.CRACK, "centalmon/hunting_cannon", CombatMark.EXPOSED,
                "agumon/pepper_breath", CombatMark.BURN, "greymon/mega_flame", CombatMark.BURN);
        for (var entry : expected.entrySet()) {
            String[] parts = entry.getKey().split("/");
            check(CombatMark.of(attack(parts[0], parts[1])) == entry.getValue(), parts[1] + " leaves " + entry.getValue());
        }
        check(CombatMark.of(attack("agumon", "claw")) == null && CombatMark.of(null) == null, "a claw leaves no mark");
        check(CombatMark.ticks(attack("garurumon", "freeze_fang")) == FreezeMark.FROZEN_TICKS && CombatMark.ticks(attack("seadramon", "ice_blast")) == IceCombo.COLD_TICKS
                && CombatMark.ticks(attack("golemon", "rock_punch")) == CrackMark.CRACKED_TICKS && CombatMark.ticks(attack("seadramon", "constriction")) == 0,
                "a mark with its own length reports it; Held lasts as long as the hold");
        check(CombatMark.ticks(attack("gesomon", "deadly_shade")) == 40 && CombatMark.ticks(attack("centalmon", "hunting_cannon")) == 80,
                "Inked and Exposed last what the shot's data says");
        check(CombatMark.ticks(attack("agumon", "pepper_breath")) == PepperBreathEntity.BURN_TICKS && CombatMark.ticks(attack("greymon", "mega_flame")) == MegaFlameEntity.BURN_TICKS
                && PepperBreathEntity.BURN_TICKS < MegaFlameEntity.BURN_TICKS, "the two fire shots burn for their own lengths");
        StringBuilder guide = new StringBuilder();
        for (DigimonSpecies species : DigimonSpeciesRegistry.all()) for (DigimonAttack move : species.attacks()) {
            CombatMark mark = CombatMark.of(move);
            if (mark != null) guide.append(species.id().getPath()).append('/').append(move.id().getPath()).append('=').append(mark.id()).append(' ');
        }
        Constants.LOG.info("[analyzer-test] moves that leave a mark: {}", guide.toString().trim());

        check(CombatMark.showing(0, 0).isEmpty(), "a clean body shows nothing");
        int held = CombatMarkState.pack(true, 0, 0, 0, 0, 0), burning = CombatMarkState.pack2(0, false, .5F);
        check(CombatMark.showing(held, burning).equals(List.of(CombatMark.HELD, CombatMark.BURN)), "Held and Burn show, in the emblems' order");
        int everything = CombatMarkState.pack(true, 5, 60, .5F, 1, .5F), everything2 = CombatMarkState.pack2(.5F, false, .5F, .5F, 0, false, false);
        check(CombatMark.showing(everything, everything2).equals(List.of(CombatMark.FREEZE, CombatMark.COLD, CombatMark.CRACK)),
                "only the first " + CombatMark.MAX_SHOWN + " emblems show, build-ups first");
        check(CombatMark.showing(0, CombatMarkState.pack2(0, false, 0, 0, 0, true, false)).equals(List.of(CombatMark.FREEZE)), "the ice's blink counts as the Freeze emblem");
        check(CombatMark.showing(0, CombatMarkState.pack2(0, false, 0, 0, 0, false, true)).isEmpty(), "a resistance alone is no mark");
        Set<CombatMark> some = Set.of(CombatMark.FREEZE, CombatMark.BURN);
        check(CombatMark.unmask(CombatMark.mask(some)).equals(some) && CombatMark.mask(List.of()) == 0 && CombatMark.unmask(-1).size() == CombatMark.values().length,
                "a set of marks travels as bits");
        check(CombatMark.byId("crack").orElseThrow() == CombatMark.CRACK && CombatMark.byId("provoked").isEmpty(), "marks are found by their id");
    }

    private static void witness() {
        Vec3 ahead = new Vec3(0, 0, 1);
        check(AnalyzerWitness.inView(ahead, new Vec3(0, 0, 10)) && AnalyzerWitness.inView(ahead, new Vec3(5, 0, 10)), "a body ahead is in view");
        check(!AnalyzerWitness.inView(ahead, new Vec3(0, 0, -10)) && !AnalyzerWitness.inView(ahead, new Vec3(10, 0, 0)), "behind and straight to the side are not");
        check(!AnalyzerWitness.inView(ahead, new Vec3(0, 0, AnalyzerWitness.RANGE + 1)), "nor is beyond the range");
        check(AnalyzerWitness.inView(ahead, new Vec3(0, -1, 0)), "a body underfoot is, whichever way the tamer looks");
    }

    private static void record() {
        UUID tamer = UUID.randomUUID();
        AnalyzerSavedData data = new AnalyzerSavedData();
        check(data.record(tamer) == null, "a tamer has no record before the first look");
        AnalyzerRecord record = data.open(tamer, List.of(Constants.id("agumon")));
        check(record.knows(Constants.id("agumon")) && !record.add(Constants.id("agumon")) && record.add(Constants.id("gabumon")), "what is on record is known, and added once");
        check(record.add(CombatMark.BURN) && !record.add(CombatMark.BURN) && record.knows(CombatMark.BURN) && !record.knows(CombatMark.COLD), "the same for marks");
        var saved = AnalyzerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        AnalyzerRecord loaded = AnalyzerSavedData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow().record(tamer);
        check(loaded != null && loaded.species().equals(List.of(Constants.id("agumon"), Constants.id("gabumon"))) && loaded.marks().equals(Set.of(CombatMark.BURN)),
                "the record survives a save, species in id order");
        check(new AnalyzerRecord(List.of(), List.of("burn", "provoked")).marks().equals(Set.of(CombatMark.BURN)), "a saved mark this version does not know is dropped");
        check(data.open(tamer, List.of()).species().isEmpty() && data.record(tamer).marks().isEmpty(), "opening again starts the record over");
    }

    private static void packets() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            AnalyzerDiscoveryPayload mark = AnalyzerDiscoveryPayload.mark(CombatMark.EXPOSED), species = AnalyzerDiscoveryPayload.species(Constants.id("gabumon"));
            AnalyzerDiscoveryPayload.STREAM_CODEC.encode(buffer, mark);
            AnalyzerDiscoveryPayload back = AnalyzerDiscoveryPayload.STREAM_CODEC.decode(buffer);
            check(back.equals(mark) && back.combatMark() == CombatMark.EXPOSED, "a mark discovery round-trips and names its mark");
            buffer.clear();
            AnalyzerDiscoveryPayload.STREAM_CODEC.encode(buffer, species);
            back = AnalyzerDiscoveryPayload.STREAM_CODEC.decode(buffer);
            check(back.equals(species) && back.combatMark() == null && !back.mark(), "a species discovery round-trips as a species");
            buffer.clear();
            PartySnapshotPayload snapshot = new PartySnapshotPayload(true, 0, 0, List.of(), List.of(), "", List.of(Constants.id("agumon")), CombatMark.mask(Set.of(CombatMark.HELD)));
            PartySnapshotPayload.STREAM_CODEC.encode(buffer, snapshot);
            PartySnapshotPayload read = PartySnapshotPayload.STREAM_CODEC.decode(buffer);
            check(read.equals(snapshot) && CombatMark.unmask(read.marks()).equals(Set.of(CombatMark.HELD)), "the snapshot carries the recorded marks");
            check(new PartySnapshotPayload(false, 0, 0, List.of(), List.of(), "").marks() == 0, "a snapshot without the Digivice open names no mark");
        } finally {
            buffer.release();
        }
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("[analyzer-test] FAIL " + what);
        Constants.LOG.info("[analyzer-test] ok {}", what);
    }
}
