package com.digicube.fabric.client.digivice;

import com.digicube.digivice.DigiviceLocatorPayload;
import com.digicube.digivice.DigiviceRemovedPayload;
import com.digicube.digivice.DroppedDigivice;
import com.digicube.fabric.client.evolution.EvolutionRenderType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

/** No chunk tickets: saved positions draw even outside vanilla's item/entity tracking radius. */
public final class DigiviceLocator {
    private final DigiviceSignals signals = new DigiviceSignals();
    private final DigiviceWaypoint waypoint = new DigiviceWaypoint();
    public void init() {
        EvolutionRenderType.DIGIVICE_BEACON.pipeline();
        ClientPlayNetworking.registerGlobalReceiver(DigiviceLocatorPayload.TYPE, (payload, context) ->
                context.client().execute(() -> { signals.accept(payload); updateWaypoint(); }));
        ClientPlayNetworking.registerGlobalReceiver(DigiviceRemovedPayload.TYPE, (payload, context) ->
                context.client().execute(() -> { signals.remove(payload.entity()); updateWaypoint(); }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { signals.clear(); waypoint.update(null, null); });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> updateWaypoint());
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            var client = Minecraft.getInstance();
            var level = client.level;
            if (level == null) return;
            var camera = context.levelState().cameraRenderState.pos;
            float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            float seconds = (level.getGameTime() % 24000 + partial) / 20F;
            var loaded = new java.util.HashMap<java.util.UUID, DroppedDigivice>();
            for (var entity : level.entitiesForRendering()) if (entity instanceof DroppedDigivice drop) loaded.put(drop.getUUID(), drop);
            for (var marker : signals.markers(level.dimension().identifier())) {
                var point = marker.position();
                // Loaded drops use their interpolated position while falling; unloaded drops use the saved address.
                var entity = loaded.get(marker.entity());
                if (entity != null) point = entity.getPosition(partial);
                long beaconAt = entity == null ? marker.beaconAt() : entity.beaconAt();
                float strength = DroppedDigivice.beaconStrength(beaconAt, level.getGameTime() + partial);
                if (strength <= 0) continue;
                float distance = (float) camera.distanceTo(point);
                if (distance >= DigiviceLocatorPayload.RANGE) continue;
                var faces = DigiviceBeacon.frame(seconds, distance);
                var pose = context.poseStack(); pose.pushPose();
                pose.translate(point.x-camera.x, point.y-camera.y, point.z-camera.z);
                EvolutionRenderType.submit(context.submitNodeCollector(), pose, EvolutionRenderType.DIGIVICE_BEACON, (matrix, vertices) -> {
                    for (var face : faces) { var v = face.vertices(); for (int i = 0; i < v.length; i += 8)
                        vertices.addVertex(matrix,v[i],v[i+1],v[i+2]).setColor((face.color() & 0xffffff) | (Math.round((face.color() >>> 24) * strength) << 24)).setUv(v[i+3],v[i+4])
                                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(matrix,0,1,0);
                    }
                });
                pose.popPose();
            }
        });
    }
    private void updateWaypoint() {
        var client = Minecraft.getInstance();
        var marker = client.level == null ? null : signals.owned(client.level.dimension().identifier());
        waypoint.update(client.player == null ? null : client.player.connection.getWaypointManager(), marker);
    }
}
