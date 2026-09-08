package com.digicube.fabric.client.starter;

import com.digicube.starter.StarterChoicePayload;
import com.digicube.starter.StarterOfferPayload;
import com.digicube.starter.StarterResultPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Client side of the first-partner prompt. An offer waits until the world is actually on
 * screen: no other screen open, the player in a level and alive for a second. No loading
 * screen class is special-cased; the gate alone does it.
 */
public final class StarterClient {
    /** Ticks the player must have spent in the world before the prompt opens. */
    private static final int SETTLE_TICKS = 20;

    private StarterOfferPayload pending;

    public void init() {
        ClientPlayNetworking.registerGlobalReceiver(StarterOfferPayload.TYPE, (payload, context) ->
                context.client().execute(() -> pending = payload));
        ClientPlayNetworking.registerGlobalReceiver(StarterResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().gui.screen() instanceof StarterScreen screen) screen.receive(payload);
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pending = null);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (pending == null || client.gui.screen() != null || client.player == null || client.level == null
                || !client.player.isAlive() || client.player.tickCount < SETTLE_TICKS) return;
        StarterOfferPayload offer = pending;
        pending = null;
        client.gui.setScreen(new StarterScreen(this, offer));
    }

    /** The screen went away without an answer (death, another screen): show it again later. */
    void repend(StarterOfferPayload offer) {
        pending = offer;
    }

    void clearPending() {
        pending = null;
    }

    void send(StarterChoicePayload payload) {
        if (ClientPlayNetworking.canSend(StarterChoicePayload.TYPE)) ClientPlayNetworking.send(payload);
    }
}
