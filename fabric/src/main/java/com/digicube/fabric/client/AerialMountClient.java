package com.digicube.fabric.client;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.ai.AerialInput;
import com.digicube.entity.ai.AerialInputPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;

/** Vanilla movement/look/jump controls, with a single flight reserve bar. */
public final class AerialMountClient {
    private int lastEntity = -1, lastButtons = -1, ticks;

    /** Create client-local input bookkeeping. */
    public AerialMountClient() {}

    /**
     * Register the existing jump input and the flight reserve bar. The mount hearts are {@code RiderAttacks}' slot (the
     * attack tiles for a mount that fights, nothing for any other Digimon), flying or not.
     */
    public void init() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null || !(client.player.getVehicle() instanceof DigimonEntity mount)
                    || mount.aerialMount() == null) {
                lastEntity = lastButtons = -1;
                return;
            }
            boolean active = client.gui.screen() == null;
            var input = new AerialInput(active && client.options.keyJump.isDown(), !active);
            mount.aerialRiding().accept(input);
            if (mount.getId() != lastEntity || input.bits() != lastButtons || ++ticks >= 4) {
                ClientPlayNetworking.send(new AerialInputPayload(mount.getId(), input.bits()));
                lastEntity = mount.getId();
                lastButtons = input.bits();
                ticks = 0;
            }
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Constants.id("aerial_mount"), (g, delta) -> {
            var client = Minecraft.getInstance();
            if (client.player == null || client.gui.hud.isHidden()
                    || !(client.player.getVehicle() instanceof DigimonEntity mount) || mount.aerialMount() == null) return;
            // On the experience bar's row, as vanilla's horse jump bar: the attack tiles stand just above it.
            int x = g.guiWidth() / 2 - 91, y = g.guiHeight() - 29;
            g.fill(x, y, x + 182, y + 5, 0xB5202C38);
            g.fill(x + 1, y + 1, x + 1 + Math.round(180 * mount.getFlightFuel()), y + 4,
                    mount.getFlightFuel() < .15 ? 0xFFE4AD62 : 0xFF89CFE8);
        });
    }
}
