package com.digicube.fabric.client.digivice;

import com.digicube.digivice.DigiviceLocatorPayload;
import net.minecraft.resources.Identifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Client visual state. Removal messages update both beam and HUD in the same event. */
public final class DigiviceSignals {
    private Identifier dimension;
    private final LinkedHashMap<UUID, DigiviceLocatorPayload.Marker> markers = new LinkedHashMap<>();
    public void accept(DigiviceLocatorPayload payload) {
        dimension = payload.dimension(); markers.clear();
        for (var marker : payload.markers()) markers.put(marker.entity(), marker);
    }
    public void remove(UUID entity) { markers.remove(entity); }
    public void clear() { dimension = null; markers.clear(); }
    public List<DigiviceLocatorPayload.Marker> markers(Identifier currentDimension) {
        return currentDimension.equals(dimension) ? List.copyOf(markers.values()) : List.of();
    }
    public DigiviceLocatorPayload.Marker owned(Identifier currentDimension) {
        for (var marker : markers(currentDimension)) if (marker.owned()) return marker;
        return null;
    }
}
