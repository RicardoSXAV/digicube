package com.digicube.fabric.digivice;

import com.digicube.digivice.DigiviceLocatorPayload;
import com.digicube.digivice.DigiviceSavedData;
import com.digicube.digivice.Digivices;
import com.digicube.digivice.DigiviceRemovedPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricDigivices {
    private FabricDigivices() {}
    public static void init() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity,source,amount) -> {
            if (entity instanceof net.minecraft.server.level.ServerPlayer player) Digivices.beforeDeath(player);
            return true;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity,source) -> {
            if (entity instanceof net.minecraft.server.level.ServerPlayer player) Digivices.afterDeath(player);
        });
        PayloadTypeRegistry.clientboundPlay().register(DigiviceLocatorPayload.TYPE, DigiviceLocatorPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DigiviceRemovedPayload.TYPE, DigiviceRemovedPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(com.digicube.digivice.DigiviceRecallPayload.TYPE,
                com.digicube.digivice.DigiviceRecallPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(com.digicube.digivice.DigiviceCursorPayload.TYPE,
                com.digicube.digivice.DigiviceCursorPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(com.digicube.digivice.DigiviceCursorPayload.TYPE,
                (payload, context) -> context.server().execute(() -> com.digicube.digivice.DigiviceCursorPayload.handle(context.player(), payload)));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> DigiviceSavedData.get(server).creativeCursor(handler.getPlayer().getUUID(), null)));
        ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, existing) -> Digivices.allowLoad(entity, level));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DigiviceSavedData.get(server).clearDeaths();
            com.digicube.digivice.DigiviceStorage.tick(server);
            for (var player : server.getPlayerList().getPlayers()) Digivices.reconcile(player);
            boolean changed = DigiviceSavedData.get(server).takeLocatorChanges();
            for (var player : server.getPlayerList().getPlayers()) {
                if ((!changed && server.getTickCount() % 10 != 0) || !ServerPlayNetworking.canSend(player, DigiviceLocatorPayload.TYPE)) continue;
                ServerPlayNetworking.send(player, snapshot(player));
            }
        });
        ServerTickEvents.END_LEVEL_TICK.register(new com.digicube.fabric.dev.DigiviceScenario()::tick);
        ServerTickEvents.END_LEVEL_TICK.register(new com.digicube.fabric.dev.RecallScenario()::tick);
    }
    public static DigiviceLocatorPayload snapshot(net.minecraft.server.level.ServerPlayer player) {
        var dimension = player.level().dimension().identifier();
        var markers = DigiviceSavedData.get(player.level().getServer()).devices().stream()
                .filter(d -> d.drop().isPresent() && d.drop().get().dimension().equals(dimension))
                .filter(d -> d.owner().equals(player.getUUID()) || d.drop().get().position().distanceToSqr(player.position()) < 512 * 512)
                // Reserve the first slot for the owner's direction marker, including beyond beam range.
                .sorted(java.util.Comparator.<DigiviceSavedData.Device>comparingInt(d -> d.owner().equals(player.getUUID()) ? 0 : 1)
                        .thenComparingDouble(d -> d.drop().get().position().distanceToSqr(player.position())))
                .limit(DigiviceLocatorPayload.MAX_MARKERS)
                .map(d -> { var drop = d.drop().orElseThrow(); return new DigiviceLocatorPayload.Marker(
                        drop.entity(), drop.position(), drop.beaconAt(), d.owner().equals(player.getUUID())); }).toList();
        return new DigiviceLocatorPayload(dimension, markers);
    }
}
