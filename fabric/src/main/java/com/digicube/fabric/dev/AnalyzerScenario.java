package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.analyzer.AnalyzerRecord;
import com.digicube.analyzer.AnalyzerSavedData;
import com.digicube.analyzer.AnalyzerWitness;
import com.digicube.digimon.CombatMark;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.ExposedMark;
import com.digicube.entity.CombatMarkState;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Headless check of the Analyzer's record: {@code DIGICUBE_SCENARIO=analyzer_checks} stands a fake player on a stone
 * floor at y=300 with a Gabumon in front, a Betamon behind its back and a Tsunomon behind a wall, and looks around
 * every {@link AnalyzerWitness#INTERVAL_TICKS} ticks. The Gabumon goes on record after a second in sight, the other two
 * do not; a Burn on the Gabumon is recorded the moment its emblem shows, an Exposed on the hidden Tsunomon only once
 * the wall is gone, and the Tsunomon with it. The developer panel's actions then empty the record and fill it whole.
 * The verdict line starts with {@code [analyzer-checks] RESULT}.
 */
public final class AnalyzerScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int SETUP = 20, SEEN = SETUP + 40, BURNED = SEEN + 20, UNWALLED = BURNED + 40, ACTIONS = UNWALLED + 10;

    private static int tick;
    private static ServerPlayer player;
    private static DigimonEntity ahead, behind, hidden;
    private static boolean done;
    private static final List<String> passed = new ArrayList<>(), failures = new ArrayList<>();

    private AnalyzerScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"analyzer_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            tick++;
            if (tick == 1) {
                for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) level.setChunkForced(cx, cz, true);
            } else if (tick == SETUP) {
                setup(level);
            } else if (tick > SETUP) {
                if (tick % AnalyzerWitness.INTERVAL_TICKS == 0) AnalyzerWitness.observe(player);
                if (tick == SETUP + AnalyzerWitness.INTERVAL_TICKS) {
                    AnalyzerRecord record = record(level);
                    check(record != null && record.species().isEmpty() && record.marks().isEmpty(), "the first look opens an empty record for a tamer with no Digimon");
                } else if (tick == SEEN) {
                    seen(level);
                } else if (tick == BURNED) {
                    AnalyzerRecord record = record(level);
                    check(record.knows(CombatMark.BURN), "a Burn over the Gabumon is recorded the moment its emblem shows");
                    check(!record.knows(CombatMark.EXPOSED), "an Exposed over the hidden Tsunomon is not");
                    wall(level, Blocks.AIR);
                } else if (tick == UNWALLED) {
                    AnalyzerRecord record = record(level);
                    check(record.knows(hidden.getSpeciesId()), "with the wall gone the Tsunomon is recorded");
                    check(record.knows(CombatMark.EXPOSED), "and the Exposed over it with it");
                    check(!record.knows(behind.getSpeciesId()), "the Betamon behind the player's back still is not");
                } else if (tick == ACTIONS) {
                    actions(level);
                    finish(level);
                }
            }
        } catch (RuntimeException e) {
            Constants.LOG.error("[analyzer-checks] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static AnalyzerRecord record(ServerLevel level) {
        return AnalyzerSavedData.get(level.getServer()).record(player.getUUID());
    }

    private static void setup(ServerLevel level) {
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            level.setBlock(new BlockPos(x, 299, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 300; y < 312; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        wall(level, Blocks.STONE);
        player = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "AnalyzerTester"));
        // Facing +z: yaw 0.
        player.snapTo(0.5, 300, -12.5, 0, 0);
        player.setYHeadRot(0);
        ahead = still(DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.resolve("gabumon").orElseThrow(), 10, new Vec3(0.5, 300, -2.5)), 180);
        behind = still(DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.resolve("betamon").orElseThrow(), 10, new Vec3(0.5, 300, -20.5)), 0);
        hidden = still(DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.resolve("tsunomon").orElseThrow(), 10, new Vec3(0.5, 300, 6.5)), 180);
        check(ahead != null && behind != null && hidden != null, "three wild Digimon are staged around the player");
        check(AnalyzerWitness.sees(player, ahead) && !AnalyzerWitness.sees(player, behind) && !AnalyzerWitness.sees(player, hidden),
                "the player sees the Gabumon ahead, not the Betamon behind nor the Tsunomon behind the wall");
    }

    /** A wall across the floor at z=2, or the air where it was. */
    private static void wall(ServerLevel level, net.minecraft.world.level.block.Block block) {
        for (int x = -6; x <= 6; x++) for (int y = 300; y < 306; y++) level.setBlock(new BlockPos(x, y, 2), block.defaultBlockState(), 3);
    }

    private static DigimonEntity still(DigimonEntity digimon, float yaw) {
        if (digimon == null) return null;
        digimon.setNoAi(true);
        digimon.setYRot(yaw);
        digimon.setYHeadRot(yaw);
        return digimon;
    }

    private static void seen(ServerLevel level) {
        AnalyzerRecord record = record(level);
        check(record.knows(ahead.getSpeciesId()), "the Gabumon in sight for a second is on record");
        check(!record.knows(behind.getSpeciesId()) && !record.knows(hidden.getSpeciesId()), "the Betamon behind the player and the Tsunomon behind the wall are not");
        check(record.marks().isEmpty(), "no mark yet: none has shown");
        check(AnalyzerWitness.species(player).equals(List.of(ahead.getSpeciesId())) && AnalyzerWitness.marks(player) == 0, "the snapshot names exactly what is on record");
        ahead.igniteForSeconds(4);
        ((CombatMarkState) ahead).digicube$burn(80);
        ExposedMark.expose(hidden, 80);
    }

    private static void actions(ServerLevel level) {
        String forgot = AnalyzerWitness.forgetAll(level.getServer(), player, new CompoundTag());
        AnalyzerRecord record = record(level);
        check(!forgot.isEmpty() && record.species().isEmpty() && record.marks().isEmpty(), "FORGET ALL starts the record over: " + forgot);
        String revealed = AnalyzerWitness.revealAll(level.getServer(), player, new CompoundTag());
        record = record(level);
        check(record.species().size() == DigimonSpeciesRegistry.size() && record.marks().size() == CombatMark.values().length, "REVEAL ALL records every Digimon and every mark: " + revealed);
        check(CombatMark.unmask(AnalyzerWitness.marks(player)).size() == CombatMark.values().length, "and the snapshot carries them all");
    }

    private static void check(boolean condition, String what) {
        (condition ? passed : failures).add(what);
        Constants.LOG.info("[analyzer-checks] {} {}", condition ? "ok" : "FAIL", what);
    }

    private static void finish(ServerLevel level) {
        done = true;
        for (DigimonEntity digimon : new DigimonEntity[]{ahead, behind, hidden}) if (digimon != null) digimon.discard();
        Constants.LOG.info("[analyzer-checks] RESULT {} of {} checks passed{}", passed.size(), passed.size() + failures.size(),
                failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
