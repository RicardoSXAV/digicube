package com.digicube.spawn;

import com.digicube.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime knobs of the wild spawner, edited with {@code /digicube wild} and saved per
 * world in the overworld's data storage, like the party roster. Every setter normalises
 * its input and marks the data dirty; fields missing on disk take the defaults. The last
 * spawn attempt per dimension is kept here as well, but never saved.
 */
public final class WildSpawnSettings extends SavedData {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_INTERVAL_TICKS = 400;
    public static final int DEFAULT_MAX_PER_PLAYER = 4;
    public static final int DEFAULT_MAX_PER_LEVEL = 24;
    public static final int DEFAULT_MIN_DISTANCE = 24;
    public static final int DEFAULT_MAX_DISTANCE = 48;
    public static final int DEFAULT_LEVEL_BONUS_PER_500_BLOCKS = 0;
    public static final boolean DEFAULT_DEBUG = false;

    public static final int MIN_INTERVAL_TICKS = 20;
    /** Farther spots fall outside the entity-ticking range of the anchor player anyway. */
    public static final int MAX_DISTANCE = 128;

    public static final Codec<WildSpawnSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("enabled", DEFAULT_ENABLED).forGetter(WildSpawnSettings::enabled),
            Codec.INT.optionalFieldOf("interval_ticks", DEFAULT_INTERVAL_TICKS).forGetter(WildSpawnSettings::intervalTicks),
            Codec.INT.optionalFieldOf("max_per_player", DEFAULT_MAX_PER_PLAYER).forGetter(WildSpawnSettings::maxPerPlayer),
            Codec.INT.optionalFieldOf("max_per_level", DEFAULT_MAX_PER_LEVEL).forGetter(WildSpawnSettings::maxPerLevel),
            Codec.INT.optionalFieldOf("min_distance", DEFAULT_MIN_DISTANCE).forGetter(WildSpawnSettings::minDistance),
            Codec.INT.optionalFieldOf("max_distance", DEFAULT_MAX_DISTANCE).forGetter(WildSpawnSettings::maxDistance),
            Codec.INT.optionalFieldOf("level_bonus_per_500_blocks", DEFAULT_LEVEL_BONUS_PER_500_BLOCKS)
                    .forGetter(WildSpawnSettings::levelBonusPer500Blocks),
            Codec.BOOL.optionalFieldOf("debug", DEFAULT_DEBUG).forGetter(WildSpawnSettings::debug)
    ).apply(instance, WildSpawnSettings::new));
    public static final SavedDataType<WildSpawnSettings> TYPE = new SavedDataType<>(
            Constants.id("wild"), WildSpawnSettings::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private boolean enabled;
    private int intervalTicks;
    private int maxPerPlayer;
    private int maxPerLevel;
    private int minDistance;
    private int maxDistance;
    private int levelBonusPer500Blocks;
    private boolean debug;
    private final Map<ResourceKey<Level>, SpawnAttempt> lastAttempts = new HashMap<>();

    public WildSpawnSettings() {
        this(DEFAULT_ENABLED, DEFAULT_INTERVAL_TICKS, DEFAULT_MAX_PER_PLAYER, DEFAULT_MAX_PER_LEVEL,
                DEFAULT_MIN_DISTANCE, DEFAULT_MAX_DISTANCE, DEFAULT_LEVEL_BONUS_PER_500_BLOCKS, DEFAULT_DEBUG);
    }

    private WildSpawnSettings(boolean enabled, int intervalTicks, int maxPerPlayer, int maxPerLevel,
                              int minDistance, int maxDistance, int levelBonusPer500Blocks, boolean debug) {
        this.enabled = enabled;
        this.intervalTicks = Math.max(MIN_INTERVAL_TICKS, intervalTicks);
        this.maxPerPlayer = Math.max(0, maxPerPlayer);
        this.maxPerLevel = Math.max(0, maxPerLevel);
        this.minDistance = Math.clamp(minDistance, 1, MAX_DISTANCE);
        this.maxDistance = Math.clamp(maxDistance, this.minDistance, MAX_DISTANCE);
        this.levelBonusPer500Blocks = Math.max(0, levelBonusPer500Blocks);
        this.debug = debug;
    }

    public static WildSpawnSettings get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public boolean enabled() { return enabled; }
    public int intervalTicks() { return intervalTicks; }
    public int maxPerPlayer() { return maxPerPlayer; }
    public int maxPerLevel() { return maxPerLevel; }
    public int minDistance() { return minDistance; }
    public int maxDistance() { return maxDistance; }
    public int levelBonusPer500Blocks() { return levelBonusPer500Blocks; }
    public boolean debug() { return debug; }

    /** Most wild Digimon a dimension may hold with {@code players} in it. */
    public int cap(int players) {
        return Math.min(maxPerPlayer * players, maxPerLevel);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setDirty();
    }

    /** @return the interval actually applied, never below {@link #MIN_INTERVAL_TICKS} */
    public int setIntervalTicks(int ticks) {
        intervalTicks = Math.max(MIN_INTERVAL_TICKS, ticks);
        setDirty();
        return intervalTicks;
    }

    public void setCaps(int perPlayer, int perLevel) {
        maxPerPlayer = Math.max(0, perPlayer);
        maxPerLevel = Math.max(0, perLevel);
        setDirty();
    }

    /** Clamps both to {@code 1..}{@link #MAX_DISTANCE} and keeps the maximum at or above the minimum. */
    public void setDistance(int min, int max) {
        minDistance = Math.clamp(min, 1, MAX_DISTANCE);
        maxDistance = Math.clamp(max, minDistance, MAX_DISTANCE);
        setDirty();
    }

    public void setLevelBonusPer500Blocks(int bonus) {
        levelBonusPer500Blocks = Math.max(0, bonus);
        setDirty();
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        setDirty();
    }

    /** Remembers the newest attempt of a dimension for {@code /digicube wild status}. Not saved. */
    public void record(ResourceKey<Level> dimension, SpawnAttempt attempt) {
        lastAttempts.put(dimension, attempt);
    }

    public Optional<SpawnAttempt> lastAttempt(ResourceKey<Level> dimension) {
        return Optional.ofNullable(lastAttempts.get(dimension));
    }
}
