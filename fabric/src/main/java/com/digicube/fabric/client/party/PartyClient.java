package com.digicube.fabric.client.party;

import com.digicube.Constants;
import com.digicube.registry.DCItems;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyHealthPayload;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartyRoster;
import com.digicube.party.PartySnapshotPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Client receiver, party keys and HUD registration. State is connection-scoped and reset
 * on disconnect. One party slot is <em>selected</em>: the arrow keys move the selection
 * through the filled slots, the Digivolve key acts on the selected partner and holding the
 * command wheel key opens {@link CommandWheelScreen} for it.
 */
public final class PartyClient {
    private PartySnapshotPayload snapshot = empty();
    /** Client ticks since {@link #snapshot} arrived, so a countdown can continue between server updates. */
    private int snapshotAge;
    /** The party slot the arrow keys have selected; always a filled slot while the party is not empty. */
    private int selected;
    private final PartyHud hud = new PartyHud();
    private KeyMapping evolveKey;
    private KeyMapping previousKey;
    private KeyMapping nextKey;
    private KeyMapping wheelKey;

    private static PartySnapshotPayload empty() {
        return new PartySnapshotPayload(false, 0, 0, List.of(), List.of(), "");
    }

    public void init() {
        KeyMapping.Category category = KeyMapping.Category.register(Constants.id("party"));
        evolveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.evolve", InputConstants.KEY_V, category));
        previousKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.party_previous", InputConstants.KEY_UP, category));
        nextKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.party_next", InputConstants.KEY_DOWN, category));
        wheelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.command_wheel", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_MIDDLE, category));
        ClientTickEvents.END_CLIENT_TICK.register(this::handleKeys);
        ClientPlayNetworking.registerGlobalReceiver(PartySnapshotPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    snapshot = payload;
                    snapshotAge = 0;
                    selected = PartyHudReadout.normalizeSelection(filled(), selected);
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            snapshot = empty();
            selected = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            snapshotAge++;
            hud.tick(snapshot);
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Constants.id("party"),
                (graphics, delta) -> hud.draw(graphics, delta, snapshot, snapshotAge, selected, evolveKey));
    }

    /** Arrow keys move the selection; the Digivolve key asks the server to evolve the selected resting partner. */
    private void handleKeys(Minecraft client) {
        boolean inWorld = client.player != null && client.gui.screen() == null;
        while (previousKey.consumeClick()) if (inWorld) selected = PartyHudReadout.nextSelection(filled(), selected, -1);
        while (nextKey.consumeClick()) if (inWorld) selected = PartyHudReadout.nextSelection(filled(), selected, 1);
        while (wheelKey.consumeClick()) {
            // The wheel is the Digivice's: no device in the inventory, no wheel. The server checks the same.
            if (inWorld && member(selected) != null && client.player.getInventory().contains(stack -> stack.is(DCItems.DIGIVICE))) {
                client.gui.setScreen(new CommandWheelScreen(this));
            }
        }
        while (evolveKey.consumeClick()) {
            if (!inWorld) continue;
            snapshot.party().stream().filter(m -> m.slot() == selected && m.phase().equals("RESTING")).findFirst()
                    .ifPresent(m -> send(new PartyActionPayload(PartyActionPayload.EVOLVE, m.id(), 0, m.generation(), m.sequence())));
        }
    }

    private boolean[] filled() {
        boolean[] filled = new boolean[PartyRoster.PARTY_SIZE];
        for (PartyMemberView member : snapshot.party()) {
            if (member.slot() >= 0 && member.slot() < filled.length) filled[member.slot()] = true;
        }
        return filled;
    }

    /** The party member in {@code slot}, or null for an empty slot. */
    PartyMemberView member(int slot) {
        for (PartyMemberView member : snapshot.party()) if (member.slot() == slot) return member;
        return null;
    }

    /** The filled slot {@code step} places from the selection, wrapping; the selection itself when it is the only one. */
    int neighbour(int step) { return PartyHudReadout.nextSelection(filled(), selected, step); }
    void selectNext() { selected = neighbour(1); }
    int selected() { return selected; }
    KeyMapping wheelKey() { return wheelKey; }

    PartySnapshotPayload snapshot() { return snapshot; }
    int snapshotAge() { return snapshotAge; }

    void send(PartyActionPayload payload) {
        if (ClientPlayNetworking.canSend(PartyActionPayload.TYPE)) ClientPlayNetworking.send(payload);
    }
}
