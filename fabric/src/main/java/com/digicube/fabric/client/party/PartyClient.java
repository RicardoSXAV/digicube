package com.digicube.fabric.client.party;

import com.digicube.Constants;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyHealthPayload;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartyRoster;
import com.digicube.party.PartySnapshotPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;

import java.util.List;

/** Client receiver/HUD registration. State is connection-scoped and reset on disconnect. */
public final class PartyClient {
    private PartySnapshotPayload snapshot = empty();

    private static PartySnapshotPayload empty() {
        return new PartySnapshotPayload(false, 0, 0, List.of(), List.of(), "");
    }

    public void init() {
        ClientPlayNetworking.registerGlobalReceiver(PartySnapshotPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    snapshot = payload;
                    if (payload.openScreen() && !(context.client().gui.screen() instanceof DigiviceScreen)) {
                        context.client().gui.setScreen(new DigiviceScreen(this));
                    } else if (context.client().gui.screen() instanceof DigiviceScreen screen) {
                        screen.receive(payload);
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(PartyHealthPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    snapshot = payload.apply(snapshot);
                    if (context.client().gui.screen() instanceof DigiviceScreen screen) {
                        screen.receiveHealth(snapshot, payload);
                    }
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> snapshot = empty());
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Constants.id("party"),
                (graphics, delta) -> drawHud(graphics));
    }

    PartySnapshotPayload snapshot() { return snapshot; }

    void send(PartyActionPayload payload) {
        if (ClientPlayNetworking.canSend(PartyActionPayload.TYPE)) ClientPlayNetworking.send(payload);
    }

    private void drawHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.isSpectator() || client.gui.hud.isHidden() || snapshot.total() == 0) return;
        int hotbarLeft = graphics.guiWidth() / 2 - 91;
        boolean offhandLeft = !client.player.getOffhandItem().isEmpty() && client.player.getMainArm() == HumanoidArm.RIGHT;
        int right = hotbarLeft - (offhandLeft ? 32 : 5);
        int tile = 24;
        int gap = 3;
        int x = right - (tile + gap) * PartyRoster.PARTY_SIZE + gap;
        int y = graphics.guiHeight() - 27;
        boolean vertical = x < 4;
        var flightFuel = new java.util.HashMap<java.util.UUID, Float>();
        if (client.level != null) for (var entity : client.level.entitiesForRendering()) {
            if (entity instanceof com.digicube.entity.DigimonEntity digimon && digimon.canFly()) {
                flightFuel.put(entity.getUUID(), digimon.getFlightFuel());
            }
        }
        // At high GUI scales, a vertical strip preserves the offhand, armor and hearts.
        if (vertical) { x = 4; y = graphics.guiHeight() - 108; }
        for (int slot = 0; slot < PartyRoster.PARTY_SIZE; slot++) {
            int currentSlot = slot;
            PartyMemberView member = snapshot.party().stream().filter(entry -> entry.slot() == currentSlot).findFirst().orElse(null);
            int sx = x + (vertical ? 0 : slot * (tile + gap));
            int sy = y + (vertical ? slot * (tile + gap) : 0);
            graphics.fill(sx, sy, sx + tile, sy + tile, 0xDA101B28);
            graphics.outline(sx, sy, tile, tile, member == null ? 0x99405262 : 0xFF588779);
            if (member != null) {
                PartyGraphics.icon(graphics, member, sx + 1, sy, 22);
                PartyGraphics.health(graphics, member, sx + 3, sy + tile - 4, tile - 6);
                Float fuel = flightFuel.get(member.id());
                if (fuel != null) {
                    graphics.fill(sx + 3, sy + tile - 2, sx + tile - 3, sy + tile - 1, PartyGraphics.INK);
                    graphics.fill(sx + 3, sy + tile - 2, sx + 3 + Math.round((tile - 6) * fuel), sy + tile - 1, 0xFF79BFFF);
                }
                if (!member.deployed()) graphics.fill(sx + tile - 5, sy + 3, sx + tile - 3, sy + 5, PartyGraphics.ORANGE);
                // The level sits beside the tile: above it along the hotbar, to its right in the vertical strip.
                String level = Component.translatable("gui.digicube.party.level", member.level()).getString();
                if (vertical) graphics.text(client.font, level, sx + tile + 3, sy + 8, PartyGraphics.WHITE, true);
                else graphics.centeredText(client.font, level, sx + tile / 2, sy - 10, PartyGraphics.WHITE);
            } else graphics.centeredText(client.font, Integer.toString(slot + 1), sx + tile / 2, sy + 8, PartyGraphics.MUTED);
        }
    }
}
