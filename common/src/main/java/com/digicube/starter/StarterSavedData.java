package com.digicube.starter;

import com.digicube.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who chose which first partner, saved per world in the overworld's data storage like the
 * party roster. One record per player; the record is written before the partner exists,
 * so a crash in between leaves a record and no partner, which {@code /digicube starter
 * reset} repairs. The set of players currently shown the prompt is kept here too, but it
 * is connection-local and never saved.
 */
public final class StarterSavedData extends SavedData {

    /** @param player the tamer  @param species the chosen starter */
    public record StarterRecord(UUID player, Identifier species) {
        public static final Codec<StarterRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(StarterRecord::player),
                Identifier.CODEC.fieldOf("species").forGetter(StarterRecord::species)
        ).apply(instance, StarterRecord::new));
    }

    public static final Codec<StarterSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StarterRecord.CODEC.listOf().optionalFieldOf("records", List.of()).forGetter(StarterSavedData::records)
    ).apply(instance, StarterSavedData::new));
    public static final SavedDataType<StarterSavedData> TYPE = new SavedDataType<>(
            Constants.id("starters"), StarterSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Identifier> records = new LinkedHashMap<>();
    private final Set<UUID> offered = new HashSet<>();

    public StarterSavedData() {}

    private StarterSavedData(List<StarterRecord> records) {
        // A duplicated player in a hand-edited file keeps its first entry.
        for (StarterRecord record : records) this.records.putIfAbsent(record.player(), record.species());
    }

    public static StarterSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Every record in the order it was written. */
    public List<StarterRecord> records() {
        return records.entrySet().stream().map(entry -> new StarterRecord(entry.getKey(), entry.getValue())).toList();
    }

    public boolean hasRecord(UUID player) {
        return records.containsKey(player);
    }

    public Optional<Identifier> choice(UUID player) {
        return Optional.ofNullable(records.get(player));
    }

    /** Remembers a choice. Refuses silently to overwrite; callers check {@link #hasRecord} first. */
    public void record(UUID player, Identifier species) {
        if (records.putIfAbsent(player, species) == null) setDirty();
    }

    /** @return whether a record existed */
    public boolean reset(UUID player) {
        if (records.remove(player) == null) return false;
        setDirty();
        return true;
    }

    // --- prompt bookkeeping, per connection, never saved -------------------------------

    public void markOffered(UUID player) { offered.add(player); }
    public boolean isOffered(UUID player) { return offered.contains(player); }
    public void clearOffered(UUID player) { offered.remove(player); }
}
