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
    public static final Codec<DigiviceSavedData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Device.CODEC.listOf().optionalFieldOf("devices", List.of()).forGetter(DigiviceSavedData::devices)).apply(i, DigiviceSavedData::new));
    public static final SavedDataType<DigiviceSavedData> TYPE = new SavedDataType<>(
            Constants.id("digivices"), DigiviceSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private final Map<UUID, Device> devices = new LinkedHashMap<>();
    private long revision;
    private long publishedRevision = -1;
    // A same-tick safety snapshot; never saved. Only a confirmed death consumes it.
    private final Map<UUID, net.minecraft.world.item.ItemStack> deaths = new java.util.HashMap<>();

    public DigiviceSavedData() {}
    private DigiviceSavedData(List<Device> entries) { for (var e : entries) devices.putIfAbsent(e.owner(), e); }
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
