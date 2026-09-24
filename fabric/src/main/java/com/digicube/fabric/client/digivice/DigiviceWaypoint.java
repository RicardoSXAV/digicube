package com.digicube.fabric.client.digivice;

import com.digicube.Constants;
import com.digicube.digivice.DigiviceLocatorPayload;
import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointStyleAssets;
import java.util.Optional;
import java.util.UUID;

/** Uses the vanilla locator bar's bearing, height arrows, camera rules and gamerule visibility. */
public final class DigiviceWaypoint {
    private ClientWaypointManager manager;
    private UUID tracked;
    public static TrackedWaypoint create(DigiviceLocatorPayload.Marker marker) {
        var icon = new Waypoint.Icon();
        icon.style = ResourceKey.create(WaypointStyleAssets.ROOT_ID, Constants.id("digivice"));
        icon.color = Optional.of(0xffffffff); // Preserve the authored icon colors.
        return TrackedWaypoint.setPosition(marker.entity(), icon, BlockPos.containing(marker.position()));
    }
    public void update(ClientWaypointManager nextManager, DigiviceLocatorPayload.Marker marker) {
        UUID next = marker == null ? null : marker.entity();
        if (manager != nextManager || !java.util.Objects.equals(tracked, next)) {
            if (manager != null && tracked != null) manager.untrackWaypoint(TrackedWaypoint.empty(tracked));
            manager = nextManager; tracked = next;
            if (manager != null && marker != null) manager.trackWaypoint(create(marker));
        } else if (manager != null && marker != null) manager.updateWaypoint(create(marker));
    }
}
