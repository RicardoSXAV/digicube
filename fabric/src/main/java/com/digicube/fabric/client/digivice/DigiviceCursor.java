package com.digicube.fabric.client.digivice;

import com.digicube.digivice.DigiviceCursorPayload;
import com.digicube.digivice.DigiviceRecallPayload;
import com.digicube.digivice.Digivices;
import com.digicube.registry.DCItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import java.util.Objects;
import java.util.UUID;

/**
 * Tells the server when the creative inventory's cursor, which only the client knows, carries the player's Digivice,
 * so dragging it between slots does not send the partners to the Digispace.
 */
public final class DigiviceCursor {
    private static UUID reported;
    private DigiviceCursor() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reported = null);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            UUID token = null;
            if (client.player != null && client.player.isCreative() && client.gui.screen() instanceof CreativeModeInventoryScreen screen) {
                var carried = screen.getMenu().getCarried();
                if (carried.is(DCItems.DIGIVICE)) token = Digivices.token(carried);
            }
            if (Objects.equals(token, reported) || client.player == null || !ClientPlayNetworking.canSend(DigiviceCursorPayload.TYPE)) return;
            reported = token;
            ClientPlayNetworking.send(new DigiviceCursorPayload(token == null ? DigiviceRecallPayload.NO_TOKEN : token));
        });
    }
}
