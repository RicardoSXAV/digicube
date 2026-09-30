package com.digicube.analyzer;

import com.digicube.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every tamer's {@link AnalyzerRecord}, saved per world in the overworld's data storage like the party roster. A
 * record belongs to the player, not to a life: it survives death and game mode changes. How long each tamer has been
 * looking at a species not yet recorded is kept here as well, but it is connection-local and never saved.
 */
public final class AnalyzerSavedData extends SavedData {
    private record Entry(UUID player, List<Identifier> species, List<String> marks) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Entry::player),
                Identifier.CODEC.listOf().optionalFieldOf("species", List.of()).forGetter(Entry::species),
                Codec.STRING.listOf().optionalFieldOf("marks", List.of()).forGetter(Entry::marks)
        ).apply(instance, Entry::new));
    }

    public static final Codec<AnalyzerSavedData> CODEC = Entry.CODEC.listOf().optionalFieldOf("records", List.of()).codec()
            .xmap(AnalyzerSavedData::new, AnalyzerSavedData::entries);
    public static final SavedDataType<AnalyzerSavedData> TYPE = new SavedDataType<>(
            Constants.id("analyzer"), AnalyzerSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, AnalyzerRecord> records = new LinkedHashMap<>();
    private final Map<UUID, Map<Identifier, Integer>> watches = new HashMap<>();

    public AnalyzerSavedData() {}

    private AnalyzerSavedData(List<Entry> entries) {
        // A duplicated player in a hand-edited file keeps its first entry.
        for (Entry entry : entries) records.putIfAbsent(entry.player(), new AnalyzerRecord(entry.species(), entry.marks()));
    }

    private List<Entry> entries() {
        return records.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue().species(), e.getValue().markIds())).toList();
    }

    public static AnalyzerSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The tamer's record, or null before {@link #open} made one. */
    public AnalyzerRecord record(UUID player) { return records.get(player); }

    /** Starts the tamer's record over with {@code species} in it and no mark. */
    public AnalyzerRecord open(UUID player, Collection<Identifier> species) {
        AnalyzerRecord record = new AnalyzerRecord(species, List.of());
        records.put(player, record);
        watches.remove(player);
        setDirty();
        return record;
    }

    /** Ticks the tamer has kept each species not yet recorded in sight, without a break. */
    Map<Identifier, Integer> watch(UUID player) { return watches.computeIfAbsent(player, ignored -> new HashMap<>()); }

    public void forgetSession(UUID player) { watches.remove(player); }
}
