package com.digicube.fabric.dev;

import com.digicube.dev.DevActionPayload;
import com.digicube.dev.DevPanel;
import com.digicube.dev.DevStatePayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Fabric transport for the developer panel. The gate and the tools live in {@link DevPanel}. */
public final class FabricDevNetworking {
    private FabricDevNetworking() {}

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(DevActionPayload.TYPE, DevActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DevStatePayload.TYPE, DevStatePayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DevActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> DevPanel.handle(context.server(), context.player(), payload)));
    }
}
