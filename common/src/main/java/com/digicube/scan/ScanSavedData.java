package com.digicube.scan;

import com.digicube.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every tamer's {@link ScanRecord}, saved per world in the overworld's data storage like the Analyzer record. A scan
 * belongs to the player, not to a life: it survives death and game mode changes.
 */
public final class ScanSavedData extends SavedData {
    private record Bar(Identifier family, int data, boolean seen) {
        static final Codec<Bar> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("family").forGetter(Bar::family),
                Codec.INT.optionalFieldOf("data", 0).forGetter(Bar::data),
                Codec.BOOL.optionalFieldOf("seen", false).forGetter(Bar::seen)
        ).apply(instance, Bar::new));
    }

    private static final Codec<ScanRecord.Defeat> DEFEAT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("family").forGetter(ScanRecord.Defeat::family),
            Codec.INT.fieldOf("data").forGetter(ScanRecord.Defeat::data)
    ).apply(instance, ScanRecord.Defeat::new));

    private record Entry(UUID player, List<Bar> bars, List<ScanRecord.Defeat> recent) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Entry::player),
                Bar.CODEC.listOf().optionalFieldOf("bars", List.of()).forGetter(Entry::bars),
                DEFEAT_CODEC.listOf().optionalFieldOf("recent", List.of()).forGetter(Entry::recent)
        ).apply(instance, Entry::new));
    }

    public static final Codec<ScanSavedData> CODEC = Entry.CODEC.listOf().optionalFieldOf("scans", List.of()).codec()
            .xmap(ScanSavedData::new, ScanSavedData::entries);
    public static final SavedDataType<ScanSavedData> TYPE = new SavedDataType<>(
            Constants.id("scan"), ScanSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, ScanRecord> records = new LinkedHashMap<>();

    public ScanSavedData() {}

    private ScanSavedData(List<Entry> entries) {
        for (Entry entry : entries) {
            Map<Identifier, Integer> data = new LinkedHashMap<>();
            Set<Identifier> seen = new LinkedHashSet<>();
            for (Bar bar : entry.bars()) {
                data.put(bar.family(), bar.data());
                if (bar.seen()) seen.add(bar.family());
            }
            records.putIfAbsent(entry.player(), new ScanRecord(data, seen, entry.recent()));
        }
    }

    private List<Entry> entries() {
        return records.entrySet().stream().map(e -> {
            ScanRecord record = e.getValue();
            Set<Identifier> families = new LinkedHashSet<>(record.dataMap().keySet());
            families.addAll(record.seenSet());
            return new Entry(e.getKey(), families.stream().map(f -> new Bar(f, record.data(f), record.seen(f))).toList(), record.recentList());
        }).toList();
    }

    public static ScanSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The tamer's scan, made empty on first use. */
    public ScanRecord record(UUID player) {
        return records.computeIfAbsent(player, ignored -> new ScanRecord());
    }
}
