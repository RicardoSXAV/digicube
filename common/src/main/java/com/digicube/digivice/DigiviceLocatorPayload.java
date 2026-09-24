package com.digicube.digivice;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** A dimension-local, bounded list of nearby saved drop addresses, independent of entity tracking. */
public record DigiviceLocatorPayload(Identifier dimension, List<Marker> markers) implements CustomPacketPayload {
    public record Marker(UUID entity, Vec3 position, long beaconAt, boolean owned) {}
    public static final int RANGE = 512, MAX_MARKERS = 64;
    public static final Type<DigiviceLocatorPayload> TYPE = new Type<>(Constants.id("digivice_locator"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DigiviceLocatorPayload> STREAM_CODEC = StreamCodec.ofMember(
            DigiviceLocatorPayload::write, b -> {
                Identifier dimension = b.readIdentifier();
                int count = b.readVarInt();
                if (count < 0 || count > MAX_MARKERS) throw new IllegalArgumentException("Invalid Digivice marker count");
                var entries = new java.util.ArrayList<Marker>(count);
                for (int i = 0; i < count; i++) entries.add(new Marker(b.readUUID(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readLong(), b.readBoolean()));
                return new DigiviceLocatorPayload(dimension, List.copyOf(entries));
            });
    private void write(RegistryFriendlyByteBuf b) {
        b.writeIdentifier(dimension); b.writeVarInt(markers.size());
        for (var marker : markers) {
            b.writeUUID(marker.entity()); b.writeDouble(marker.position().x); b.writeDouble(marker.position().y); b.writeDouble(marker.position().z);
            b.writeLong(marker.beaconAt()); b.writeBoolean(marker.owned());
        }
    }
    @Override public Type<DigiviceLocatorPayload> type() { return TYPE; }
}
