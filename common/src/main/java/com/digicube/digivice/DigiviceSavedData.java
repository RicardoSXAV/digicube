package com.digicube.digivice;

import com.digicube.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One physical device per owner. Drop addresses survive chunk unloads and server restarts. */
public final class DigiviceSavedData extends SavedData {
    public record Drop(UUID entity, Identifier dimension, Vec3 position, long beaconAt) {
        public static final Codec<Drop> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("entity").forGetter(Drop::entity),
                Identifier.CODEC.fieldOf("dimension").forGetter(Drop::dimension),
                Vec3.CODEC.fieldOf("position").forGetter(Drop::position),
                Codec.LONG.optionalFieldOf("beacon_at", 0L).forGetter(Drop::beaconAt)).apply(i, Drop::new));
    }
    public record Device(UUID owner, UUID token, Optional<Drop> drop) {
        public static final Codec<Device> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Device::owner),
                UUIDUtil.CODEC.fieldOf("token").forGetter(Device::token),
                Drop.CODEC.optionalFieldOf("drop").forGetter(Device::drop)).apply(i, Device::new));
    }
    public record Snapshot(UUID owner, net.minecraft.world.item.ItemStack stack) {
        static final Codec<Snapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Snapshot::owner),
                net.minecraft.world.item.ItemStack.CODEC.fieldOf("stack").forGetter(Snapshot::stack)).apply(i, Snapshot::new));
    }
    /** Where a device was last seen in someone's hands or menu: the Recall Chip searches the containers around it. */
    public record Seen(UUID owner, Identifier dimension, Vec3 position) {
        static final Codec<Seen> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Seen::owner),
                Identifier.CODEC.fieldOf("dimension").forGetter(Seen::dimension),
                Vec3.CODEC.fieldOf("position").forGetter(Seen::position)).apply(i, Seen::new));
    }
    public static final Codec<DigiviceSavedData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Device.CODEC.listOf().optionalFieldOf("devices", List.of()).forGetter(DigiviceSavedData::devices),
            Snapshot.CODEC.listOf().optionalFieldOf("snapshots", List.of()).forGetter(d -> List.copyOf(d.snapshots.values())),
            Seen.CODEC.listOf().optionalFieldOf("seen", List.of()).forGetter(d -> List.copyOf(d.seen.values())))
            .apply(i, DigiviceSavedData::new));
    public static final SavedDataType<DigiviceSavedData> TYPE = new SavedDataType<>(
            Constants.id("digivices"), DigiviceSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private final Map<UUID, Device> devices = new LinkedHashMap<>();
    private final Map<UUID, Snapshot> snapshots = new LinkedHashMap<>();
    private long revision;
    private long publishedRevision = -1;
    // A same-tick safety snapshot; never saved. Only a confirmed death consumes it.
    private final Map<UUID, net.minecraft.world.item.ItemStack> deaths = new java.util.HashMap<>();
    private final Map<UUID, Seen> seen = new LinkedHashMap<>();
    // Never saved: the credential a creative client reports on its inventory cursor, which the server cannot see.
    private final Map<UUID, UUID> creativeCursors = new java.util.HashMap<>();

    public DigiviceSavedData() {}
    private DigiviceSavedData(List<Device> entries, List<Snapshot> saved, List<Seen> places) {
        for (var e : entries) devices.putIfAbsent(e.owner(), e);
        for (var s : saved) snapshots.put(s.owner(), s);
        for (var s : places) seen.put(s.owner(), s);
    }
    public Seen seen(UUID owner) { return seen.get(owner); }
    /** Saved only when it moved a block or more, so a device in hand does not dirty the world every tick. */
    public void see(UUID owner, Identifier dimension, Vec3 position) {
        var old = seen.get(owner);
        if (old != null && old.dimension().equals(dimension) && old.position().distanceToSqr(position) < 1) return;
        seen.put(owner, new Seen(owner, dimension, position));
        setDirty();
    }
    public UUID creativeCursor(UUID player) { return creativeCursors.get(player); }
    public void creativeCursor(UUID player, UUID token) {
        if (token == null) creativeCursors.remove(player); else creativeCursors.put(player, token);
    }
    public void rememberStack(UUID owner, net.minecraft.world.item.ItemStack stack) {
        var old = snapshots.get(owner);
        if (old == null || !net.minecraft.world.item.ItemStack.matches(old.stack(), stack)) {
            snapshots.put(owner, new Snapshot(owner, stack.copyWithCount(1))); setDirty();
        }
    }
    public net.minecraft.world.item.ItemStack snapshot(UUID owner) {
        var saved = snapshots.get(owner);
        return saved == null ? net.minecraft.world.item.ItemStack.EMPTY : saved.stack().copy();
    }
    public static DigiviceSavedData get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }
    public List<Device> devices() { return List.copyOf(devices.values()); }
    public Device device(UUID owner) { return devices.get(owner); }
    public long revision() { return revision; }
    public boolean takeLocatorChanges() {
        if (publishedRevision == revision) return false;
        publishedRevision = revision;
        return true;
    }
    public void beforeDeath(UUID player, net.minecraft.world.item.ItemStack stack) { deaths.put(player, stack.copy()); }
    public net.minecraft.world.item.ItemStack takeDeath(UUID player) { return deaths.remove(player); }
    public void clearDeaths() { deaths.clear(); }
    public void put(Device device) {
        if (!device.equals(devices.put(device.owner(), device))) { revision++; setDirty(); }
    }
}
