package com.digicube.spawn;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Puts neutral wild Digimon into the world: one attempt per dimension every
 * {@link WildSpawnSettings#intervalTicks()} ticks, in a ring around a random player,
 * from that dimension's {@link SpawnTable}. Loader-neutral; the loader module calls
 * {@link #tick} at the end of every level tick. Each attempt records its outcome so
 * {@code /digicube wild status} can explain a quiet world.
 *
 * <p>Why not vanilla biome spawn entries: every species shares one entity type and
 * vanilla picks entity types, not species; and the creature category spawns mostly at
 * chunk generation and competes with cows and pigs for its cap.
 */
public final class WildSpawner {

    /** Pack members are placed within this many blocks of the pack origin. */
    private static final int PACK_SPREAD = 3;
    private static final double LEVEL_BONUS_STEP_BLOCKS = 500.0;

    private WildSpawner() {}

    /** End-of-tick hook for one dimension. Cheap when nothing is due. */
    public static void tick(ServerLevel level) {
        WildSpawnSettings settings = WildSpawnSettings.get(level.getServer());
        if (!settings.enabled() || !level.getGameRules().get(GameRules.SPAWN_MOBS)) return;
        if (level.getGameTime() % settings.intervalTicks() != 0) return;
        attempt(level, settings);
    }

    /** Runs one attempt now, whatever the interval or the enabled flag say, and records it. */
    public static SpawnAttempt attempt(ServerLevel level, WildSpawnSettings settings) {
        SpawnAttempt attempt = run(level, settings);
        settings.record(level.dimension(), attempt);
        if (settings.debug()) {
            Constants.LOG.info("Wild spawn attempt in {}: {} {}", level.dimension().identifier(), attempt.result(), attempt.detail());
        }
        return attempt;
    }

    /** Every unowned Digimon currently in the dimension. */
    public static List<? extends DigimonEntity> wild(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(DigimonEntity.class), digimon -> !digimon.isOwned());
    }

    /**
     * Discards every wild Digimon in the dimension.
     * @return how many were removed
     */
    public static int clear(ServerLevel level) {
        List<? extends DigimonEntity> wild = List.copyOf(wild(level));
        wild.forEach(DigimonEntity::discard);
        return wild.size();
    }

    private static SpawnAttempt run(ServerLevel level, WildSpawnSettings settings) {
        long now = level.getGameTime();
        SpawnTable table = SpawnTables.get(level.dimension()).orElse(null);
        if (table == null) return SpawnAttempt.of(now, SpawnAttempt.Result.NO_TABLE);
        ServerPlayer anchor = level.getRandomPlayer();
        if (anchor == null || anchor.isSpectator()) return SpawnAttempt.of(now, SpawnAttempt.Result.NO_PLAYER);
        int cap = settings.cap(level.players().size());
        int population = wild(level).size();
        if (population >= cap) return new SpawnAttempt(now, SpawnAttempt.Result.CAP, population + " / " + cap);

        RandomSource random = level.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = settings.minDistance() + random.nextDouble() * (settings.maxDistance() - settings.minDistance());
        BlockPos column = BlockPos.containing(anchor.getX() + Math.cos(angle) * distance, anchor.getY(),
                anchor.getZ() + Math.sin(angle) * distance);
        if (!level.isPositionEntityTicking(column)) return SpawnAttempt.of(now, SpawnAttempt.Result.UNLOADED);
        SpawnPlacement placement = placementAt(level, column);
        BlockPos origin = place(level, column, placement);
        if (origin == null) return new SpawnAttempt(now, SpawnAttempt.Result.PLACEMENT, placement.getId() + " at " + where(column));

        Holder<Biome> biome = level.getBiome(origin);
        List<SpawnEntry> candidates = table.candidates(placement, level.isBrightOutside(), level.isDarkOutside(), biome::is, biome::is);
        SpawnEntry entry = SpawnTable.pick(random, candidates);
        if (entry == null) return new SpawnAttempt(now, SpawnAttempt.Result.NO_ENTRY, placement.getId() + " at " + where(origin));
        DigimonSpecies species = DigimonSpeciesRegistry.get(entry.species()).orElse(null);
        if (species == null) return new SpawnAttempt(now, SpawnAttempt.Result.FAILED, entry.species().toString());

        int spawnLevel = Progression.clampLevel(entry.rollLevel(random) + levelBonus(level, settings, origin));
        int pack = entry.rollPack(random);
        int spawned = 0;
        for (int member = 0; member < pack; member++) {
            BlockPos pos = origin;
            if (member > 0) {
                BlockPos offset = column.offset(random.nextIntBetweenInclusive(-PACK_SPREAD, PACK_SPREAD), 0,
                        random.nextIntBetweenInclusive(-PACK_SPREAD, PACK_SPREAD));
                pos = level.isPositionEntityTicking(offset) ? place(level, offset, placement) : null;
            }
            if (pos != null && spawnOne(level, species, spawnLevel, pos, random)) spawned++;
        }
        if (spawned == 0) return new SpawnAttempt(now, SpawnAttempt.Result.FAILED, species.name() + " at " + where(origin));
        return new SpawnAttempt(now, SpawnAttempt.Result.SPAWNED,
                spawned + " x " + species.name() + " (level " + spawnLevel + ") at " + where(origin));
    }

    /** Water columns spawn swimmers; everything else is land. */
    private static SpawnPlacement placementAt(ServerLevel level, BlockPos column) {
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column).below();
        return level.getFluidState(surface).is(FluidTags.WATER) ? SpawnPlacement.WATER : SpawnPlacement.LAND;
    }

    /** The spawn position in a column for a placement kind, or null when the column offers none. */
    private static BlockPos place(ServerLevel level, BlockPos column, SpawnPlacement placement) {
        return switch (placement) {
            case LAND -> {
                BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
                boolean valid = NaturalSpawner.isValidEmptySpawnBlock(level, pos, level.getBlockState(pos),
                        level.getFluidState(pos), DCEntityTypes.DIGIMON)
                        && Mob.checkMobSpawnRules(DCEntityTypes.DIGIMON, level, EntitySpawnReason.NATURAL, pos, level.getRandom());
                yield valid ? pos : null;
            }
            case WATER -> {
                BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column).below();
                yield level.getFluidState(pos).is(FluidTags.WATER) && level.getFluidState(pos.below()).is(FluidTags.WATER)
                        ? pos : null;
            }
        };
    }

    private static boolean spawnOne(ServerLevel level, DigimonSpecies species, int spawnLevel, BlockPos pos, RandomSource random) {
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.NATURAL);
        if (digimon == null) return false;
        digimon.initializeAs(species, spawnLevel);
        digimon.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        float yaw = random.nextFloat() * 360.0F;
        digimon.setYRot(yaw);
        digimon.yBodyRot = yaw;
        digimon.yHeadRot = yaw;
        // Big species need room: a Greymon must not appear inside a tree trunk.
        if (!level.noCollision(digimon) || !level.getWorldBorder().isWithinBounds(digimon.getBoundingBox())) return false;
        digimon.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null);
        return level.addFreshEntity(digimon);
    }

    /** Optional difficulty ramp with distance from the world spawn; off by default. */
    private static int levelBonus(ServerLevel level, WildSpawnSettings settings, BlockPos pos) {
        if (settings.levelBonusPer500Blocks() <= 0) return 0;
        BlockPos spawn = level.getRespawnData().pos();
        double dx = pos.getX() - spawn.getX();
        double dz = pos.getZ() - spawn.getZ();
        return settings.levelBonusPer500Blocks() * (int) Math.floor(Math.sqrt(dx * dx + dz * dz) / LEVEL_BONUS_STEP_BLOCKS);
    }

    private static String where(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
