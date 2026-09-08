package com.digicube.fabric.starter;

import com.digicube.starter.StarterChoicePayload;
import com.digicube.starter.StarterFlow;
import com.digicube.starter.StarterOfferPayload;
import com.digicube.starter.StarterResultPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Fabric transport for the first-partner prompt. The rules live in {@link StarterFlow}. */
public final class FabricStarterNetworking {
    private FabricStarterNetworking() {}

    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(StarterOfferPayload.TYPE, StarterOfferPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StarterResultPayload.TYPE, StarterResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StarterChoicePayload.TYPE, StarterChoicePayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StarterChoicePayload.TYPE, (payload, context) ->
                context.server().execute(() -> StarterFlow.handle(context.server(), context.player(), payload)));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> StarterFlow.offer(server, handler.player, false));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> StarterFlow.disconnect(server, handler.player));
    }
}
