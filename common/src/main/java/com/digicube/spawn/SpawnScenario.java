package com.digicube.spawn;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Headless check of wild spawning in the real world: {@code DIGICUBE_SCENARIO=spawn_checks}. For each probed region it
 * finds the nearest biome of that region from the world spawn, loads the ground there and makes {@link #ATTEMPTS}
 * attempts at random columns around it ({@link WildSpawner#spawnAt}). Every Digimon put down must belong to one of the
 * entries of the region it stands in, at a level inside that entry's range lifted by the region's band. Attempts that
 * picked a species but found no room for its body are counted per species: big bodies in dense land spawn less often
 * than their weight says. Each region logs a {@code [spawn-checks]} line; the verdict starts with
 * {@code [spawn-checks] RESULT}.
 */
public final class SpawnScenario {
    private static final String[] PROBES = {"plains", "forests", "savannas", "taigas", "dark_forests", "badlands", "jungles", "mountains",
            "beaches", "deep_oceans"};
    private static final int ATTEMPTS = 60, PER_TICK = 6, LOAD_TIMEOUT = 600, SEARCH_RADIUS = 6400, AREA = 20;

    private enum Phase { FIND, LOAD, RUN }

    private static boolean done;
    private static int probe, ticks, attempts;
    private static Phase phase = Phase.FIND;
    private static BlockPos centre;
    private static SpawnRegion region;
    private static final Map<String, int[]> seen = new TreeMap<>();
    private static final Map<String, int[]> roomless = new TreeMap<>();
    private static final List<String> wrong = new ArrayList<>();
    private static final List<DigimonEntity> placed = new ArrayList<>();
    private static int passed, failed, spawned, empty;

    private SpawnScenario() {}

    public static void tick(ServerLevel level) {
        if (done || level.dimension() != Level.OVERWORLD) return;
        try {
            if (probe >= PROBES.length) { finish(level); return; }
            SpawnTable table = SpawnTables.get(Level.OVERWORLD).orElseThrow();
            switch (phase) {
                case FIND -> find(level, table);
                case LOAD -> load(level);
                case RUN -> run(level, table);
            }
        } catch (RuntimeException e) {
            Constants.LOG.error("[spawn-checks] aborted", e);
            wrong.add("aborted: " + e);
            failed++;
            finish(level);
        }
    }

    private static void find(ServerLevel level, SpawnTable table) {
        region = table.region(PROBES[probe]);
        if (region == null) throw new IllegalStateException("no region " + PROBES[probe]);
        BlockPos spawn = level.getRespawnData().pos();
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(holder -> holder.unwrapKey()
                .map(key -> region.biomes().contains(key.identifier())).orElse(false), spawn, SEARCH_RADIUS, 32, 64);
        if (found == null) {
            Constants.LOG.info("[spawn-checks] SKIP {}: none of its biomes within {} blocks", region.id(), SEARCH_RADIUS);
            next(level);
            return;
        }
        centre = new BlockPos(found.getFirst().getX(), 64, found.getFirst().getZ());
        for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) level.setChunkForced((centre.getX() >> 4) + cx, (centre.getZ() >> 4) + cz, true);
        phase = Phase.LOAD;
        ticks = 0;
    }

    private static void load(ServerLevel level) {
        boolean ready = true;
        for (int dx = -AREA; dx <= AREA; dx += AREA) for (int dz = -AREA; dz <= AREA; dz += AREA) ready &= level.isPositionEntityTicking(centre.offset(dx, 0, dz));
        if (ready) { phase = Phase.RUN; attempts = 0; seen.clear(); roomless.clear(); spawned = 0; empty = 0; return; }
        if (++ticks > LOAD_TIMEOUT) {
            wrong.add(region.id() + ": the ground did not load");
            failed++;
            next(level);
        }
    }

    private static void run(ServerLevel level, SpawnTable table) {
        var random = level.getRandom();
        for (int i = 0; i < PER_TICK && attempts < ATTEMPTS; i++, attempts++) {
            BlockPos column = centre.offset(random.nextIntBetweenInclusive(-AREA, AREA), 0, random.nextIntBetweenInclusive(-AREA, AREA));
            WildSpawner.Spawn spawn = WildSpawner.spawnAt(level, column);
            if (spawn.attempt().result() == SpawnAttempt.Result.FAILED) {
                String species = spawn.attempt().detail().split(" at ")[0];
                roomless.computeIfAbsent(species, ignored -> new int[1])[0]++;
            }
            if (spawn.attempt().result() == SpawnAttempt.Result.NO_ENTRY) empty++;
            // A pack shares the spot its attempt picked: its region and band, even where a member stands over a border. The
            // Digimon stay until the region is done, so later attempts meet the crowd a real player's land would hold.
            for (DigimonEntity digimon : spawn.spawned()) {
                check(table, spawn.region(), digimon);
                placed.add(digimon);
            }
        }
        if (attempts < ATTEMPTS) return;
        StringBuilder found = new StringBuilder();
        seen.forEach((species, range) -> found.append(found.isEmpty() ? "" : ", ").append(species).append(" x").append(range[0])
                .append(" L").append(range[1]).append('-').append(range[2]));
        StringBuilder room = new StringBuilder();
        roomless.forEach((species, count) -> room.append(room.isEmpty() ? "" : ", ").append(species).append(' ').append(count[0]));
        boolean ok = spawned > 0 && wrong.stream().noneMatch(w -> w.startsWith(region.id() + ":"));
        if (ok) passed++; else failed++;
        if (spawned == 0) wrong.add(region.id() + ": nothing spawned in " + ATTEMPTS + " attempts (" + empty + " with no entry for the spot)");
        Constants.LOG.info("[spawn-checks] {} {} ({}) near {} {}: {} Digimon in {} attempts: {}; no room: {}", ok ? "PASS" : "FAIL", region.id(),
                region.danger().getId(), centre.getX(), centre.getZ(), spawned, ATTEMPTS, found, room.isEmpty() ? "none" : room);
        next(level);
    }

    /** The Digimon must be one of the entries of the region it spawned in, at a level inside the entry's range plus the region's band. */
    private static void check(SpawnTable table, SpawnRegion at, DigimonEntity digimon) {
        spawned++;
        DigimonSpecies species = DigimonSpeciesRegistry.get(digimon.getSpeciesId()).orElseThrow();
        SpawnDanger danger = at == null ? SpawnDanger.CALM : at.danger();
        boolean fits = false;
        for (SpawnEntry entry : table.entries()) {
            if (!entry.species().equals(species.id())) continue;
            if (at != null && !entry.regions().contains(at.id())) continue;
            int bonus = table.levelBonus(danger, species.stage());
            if (digimon.getLevel() >= entry.minLevel() + bonus && digimon.getLevel() <= entry.maxLevel() + bonus) fits = true;
        }
        if (!fits) wrong.add(region.id() + ": " + species.name() + " L" + digimon.getLevel() + " in " + (at == null ? "no region" : at.id()));
        int[] range = seen.computeIfAbsent(species.name(), ignored -> new int[]{0, Integer.MAX_VALUE, 0});
        range[0]++;
        range[1] = Math.min(range[1], digimon.getLevel());
        range[2] = Math.max(range[2], digimon.getLevel());
    }

    private static void next(ServerLevel level) {
        placed.forEach(DigimonEntity::discard);
        placed.clear();
        if (centre != null) for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++)
            level.setChunkForced((centre.getX() >> 4) + cx, (centre.getZ() >> 4) + cz, false);
        centre = null;
        probe++;
        phase = Phase.FIND;
    }

    private static void finish(ServerLevel level) {
        done = true;
        wrong.forEach(line -> Constants.LOG.info("[spawn-checks] WRONG {}", line));
        Constants.LOG.info("[spawn-checks] RESULT {} {} of {} regions passed", failed == 0 ? "PASS" : "FAIL", passed, passed + failed);
        level.getServer().halt(false);
    }
}
