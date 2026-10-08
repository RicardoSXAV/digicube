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
import net.minecraft.util.Mth;

/**
 * Vanilla movement/look/jump controls (plus the sprint key's boost and the dive key's descent on an agile flyer), with
 * the flight reserve's gauge on the experience bar's row.
 */
public final class AerialMountClient {
    private int lastEntity = -1, lastButtons = -1, ticks;
    /** The gauge as last drawn, and the reserve a cast or a roll just took, shown fading off its end. */
    private float shown = -1, spent, spentAge = 100;

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
                shown = -1;
                return;
            }
            boolean active = client.gui.screen() == null;
            var input = new AerialInput(active && client.options.keyJump.isDown(), !active,
                    active && client.options.keySprint.isDown(), active && DigimonEntity.localRiderDives);
            mount.aerialRiding().accept(input);
            if (mount.getId() != lastEntity || input.bits() != lastButtons || ++ticks >= 4) {
                ClientPlayNetworking.send(new AerialInputPayload(mount.getId(), input.bits()));
                lastEntity = mount.getId();
                lastButtons = input.bits();
                ticks = 0;
            }
            // a sudden drop of the reserve (a cast on the wing, a roll) shows as a bright stretch fading off the gauge
            float now = mount.getFlightFuel();
            if (shown >= 0 && shown - now > .03F) { spent = shown - now + (spentAge < 12 ? spent * (1 - spentAge / 12) : 0); spentAge = 0; }
            spentAge++;
            shown = now;
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Constants.id("aerial_mount"), (g, delta) -> {
            var client = Minecraft.getInstance();
            if (client.player == null || client.gui.hud.isHidden()
                    || !(client.player.getVehicle() instanceof DigimonEntity mount) || mount.aerialMount() == null) return;
            // On the experience bar's row, as vanilla's horse jump bar: the attack tiles stand just above it.
            int x = g.guiWidth() / 2 - 91, y = g.guiHeight() - 29;
            float fuel = mount.getFlightFuel();
            boolean fighting = mount.flightFighting();
            g.fill(x, y, x + 182, y + 5, fighting ? 0xC0402028 : 0xB5202C38);
            int fill = Math.round(180 * fuel);
            int color = fuel < .15F ? 0xFFE4AD62 : fighting ? 0xFFB79BE8 : 0xFF89CFE8;
            g.fill(x + 1, y + 1, x + 1 + fill, y + 4, color);
            // a lighter top line, as the tiles' frames are lit
            g.fill(x + 1, y + 1, x + 1 + fill, y + 2, fuel < .15F ? 0xFFF4D29A : fighting ? 0xFFD9C8F6 : 0xFFC9ECF8);
            // what a cast or a roll just took, fading off the gauge's end
            if (spentAge < 12 && spent > 0) {
                int a = (int) (255 * (1 - spentAge / 12F));
                g.fill(x + 1 + fill, y + 1, x + 1 + Math.min(180, fill + Math.round(180 * spent)), y + 4, a << 24 | 0xFFFFFF);
            }
            // a notch for every cast on the wing the full reserve affords (Mega Blaster, Beet Horn: about three)
            var costs = mount.getLocomotion().flight() == null ? null : mount.getLocomotion().flight().costs();
            if (costs != null && costs.attack() > 0 && mount.aerialMount().agility() != null) {
                for (float share = 1 - costs.attack(); share > .02F; share -= costs.attack()) {
                    int at = x + 1 + Math.round(180 * share);
                    g.fill(at, y, at + 1, y + 5, Mth.floor(share * 180) < fill ? 0xFF2A3A4A : 0x80FFFFFF);
                }
            }
        });
    }
}
