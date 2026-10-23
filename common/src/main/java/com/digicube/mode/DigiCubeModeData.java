package com.digicube.mode;

import com.digicube.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The world's DigiCube Mode, saved in the overworld's data storage like the party roster: the authority every rule
 * reads through {@link DigiCubeModes}. Written once, on the world's first start ({@link DigiCubeModes#adopt}); never
 * changed by play. {@code adopted} marks a world that had no mode chosen for it (an old world, a dedicated server's),
 * and {@code greeted} the tamers who were told the world's mode on joining.
 */
public final class DigiCubeModeData extends SavedData {

    public static final Codec<DigiCubeModeData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DigiCubeMode.CODEC.optionalFieldOf("mode", DigiCubeMode.UNSET).forGetter(DigiCubeModeData::mode),
            Codec.LONG.optionalFieldOf("set_at", 0L).forGetter(DigiCubeModeData::setAt),
            Codec.BOOL.optionalFieldOf("adopted", false).forGetter(DigiCubeModeData::adopted),
            UUIDUtil.CODEC.listOf().optionalFieldOf("greeted", List.of()).forGetter(data -> List.copyOf(data.greeted))
    ).apply(instance, DigiCubeModeData::new));
    public static final SavedDataType<DigiCubeModeData> TYPE = new SavedDataType<>(
            Constants.id("mode"), DigiCubeModeData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private DigiCubeMode mode = DigiCubeMode.UNSET;
    private long setAt;
    private boolean adopted;
    private final Set<UUID> greeted = new LinkedHashSet<>();

    public DigiCubeModeData() {}

    DigiCubeModeData(DigiCubeMode mode, long setAt, boolean adopted, List<UUID> greeted) {
        this.mode = mode;
        this.setAt = setAt;
        this.adopted = adopted;
        this.greeted.addAll(greeted);
    }

    /**
     * The mode saved in a world's folder, read from this data's file without loading the world (the world list shows
     * it): {@link DigiCubeMode#UNSET} for a world that has none yet, or whose file can't be read.
     */
    public static DigiCubeMode read(Path world) {
        Path file = file(world);
        if (!Files.isRegularFile(file)) return DigiCubeMode.UNSET;
        try {
            CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.defaultQuota()).getCompoundOrEmpty("data");
            return CODEC.parse(NbtOps.INSTANCE, data).result().map(DigiCubeModeData::mode).orElse(DigiCubeMode.UNSET);
        } catch (IOException | NbtException | ReportedNbtException unreadable) {
            Constants.LOG.warn("Can't read the DigiCube Mode saved in {}: {}", file, unreadable.toString());
            return DigiCubeMode.UNSET;
        }
    }

    /** Where a world's folder keeps this data: the overworld's data storage, as its server saves it. */
    public static Path file(Path world) {
        Path storage = DimensionType.getStorageFolder(Level.OVERWORLD, world).resolve("data");
        return TYPE.id().withSuffix(".dat").resolveAgainst(storage);
    }

    /** The world's mode; {@link DigiCubeMode#UNSET} only before {@link DigiCubeModes#adopt} wrote it. */
    public DigiCubeMode mode() { return mode; }

    /** When the mode was written, in milliseconds since the epoch; 0 before. */
    public long setAt() { return setAt; }

    /** Whether the world had no mode chosen for it and plays as TAMER for that. */
    public boolean adopted() { return adopted; }

    /** Writes the world's mode. Only {@link DigiCubeModes} calls this: on the first start, and in a headless scenario. */
    void write(DigiCubeMode mode, boolean adopted, long now) {
        if (!mode.chosen()) throw new IllegalArgumentException("A world plays as TAMER or CLASSIC, not " + mode);
        this.mode = mode;
        this.adopted = adopted;
        this.setAt = now;
        setDirty();
    }

    /** @return whether the tamer had not been told the mode yet, and is now marked as told */
    boolean greet(UUID player) {
        if (!greeted.add(player)) return false;
        setDirty();
        return true;
    }

    boolean greeted(UUID player) {
        return greeted.contains(player);
    }

    void forget(UUID player) {
        if (greeted.remove(player)) setDirty();
    }
}
