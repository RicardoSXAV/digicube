package com.digicube.fabric.client.party;

import com.digicube.digimon.EvolutionRules;
import com.digicube.fabric.client.dev.DevGear;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.fabric.client.party.CommandWheelReadout.Module;
import com.digicube.fabric.client.party.CommandWheelReadout.Order;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The command wheel: held open by its key, it puts the selected partner at the centre of
 * the screen and one order in each quarter around it. Pointing anywhere into a quarter
 * selects its order; letting go of the key gives it, or cancels when the cursor is still
 * in the dead zone or the order is unavailable. Under the orders sits the Digivice key, which opens
 * the Digivice; with nobody in the party it is all the wheel offers. Space steps to the next partner. The party
 * strip stays visible behind the veil, so the partner is shown here by its icon alone.
 * Design: {@code design/command-wheel.md}.
 */
public final class CommandWheelScreen extends Screen {
    private static final int W = CommandWheelReadout.MODULE_WIDTH;
    private static final int H = CommandWheelReadout.MODULE_HEIGHT;
    private static final int HUB = CommandWheelReadout.HUB;
    private static final int THUMB = 26;
    private static final int THUMB_GAP = 10;
    private static final int ICON_WELL = 26;
    private static final int TEXT_X = 38;
    private static final int TEXT_W = W - TEXT_X - 4;
    private static final int VEIL_ALPHA = 0x80;
    private static final int OPEN_TICKS = 3;
    /** A release this soon after opening, without leaving the dead zone, is a tap. */
    private static final int TAP_TICKS = 6;
    private static final int SCAN_PERIOD_TICKS = 160;
    private static final int SCAN_SWEEP_TICKS = 72;
    /** Which way each sector's module steps out, and which hub corner its pointer leaves from. */
    private static final int[][] OUTWARD = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};

    private final PartyClient client;
    private int ticks;
    /** Opened by a tap rather than a hold: stays open and takes a click. */
    private boolean latched;
    /** Ticks the cursor has rested on the developer gear; development environments only. */
    private int gearDwell;

    CommandWheelScreen(PartyClient client) {
        super(Component.translatable("gui.digicube.wheel.title"));
        this.client = client;
    }

    @Override public boolean isPauseScreen() { return false; }

    /** The wheel draws its own thin veil: no blur, so the world and the party strip stay readable. */
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void tick() {
        ticks++;
        if (minecraft.player == null || !minecraft.player.isAlive()) {
            onClose();
            return;
        }
        // Resting on the developer gear opens the panel, which replaces the wheel.
        gearDwell = DevGear.over(cursorX(), cursorY(), width, height) ? gearDwell + 1 : 0;
        if (gearDwell > DevGear.DWELL_TICKS) {
            DevGear.open();
            return;
        }
        // The release event is lost when the key comes up before the wheel has opened (it opens on the
        // next client tick), so the real key state is the authority, not the event.
        if (!latched && !held()) released(cursorX(), cursorY());
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (!client.wheelKey().matchesMouse(event)) return super.mouseReleased(event);
        if (!latched) released(event.x(), event.y());
        return true;
    }

    @Override public boolean keyReleased(KeyEvent event) {
        if (!client.wheelKey().matches(event)) return super.keyReleased(event);
        if (!latched) released(cursorX(), cursorY());
        return true;
    }

    /** A latched wheel takes a click instead of a release: on an order to give it, anywhere else to close. */
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!latched) return super.mouseClicked(event, doubleClick);
        give(event.x(), event.y());
        return true;
    }

    /**
     * The key came up. After a real hold that gives the order under the cursor. A quick tap that never
     * left the dead zone latches the wheel open instead, so tapping works as well as holding.
     */
    private void released(double mouseX, double mouseY) {
        boolean moved = CommandWheelReadout.sector(mouseX - width / 2, mouseY - height / 2) != CommandWheelReadout.NONE || onDigivice(mouseX, mouseY);
        if (!moved && ticks <= TAP_TICKS) latched = true;
        else give(mouseX, mouseY);
    }

    /** Whether the wheel key is physically down right now, whatever it is bound to. */
    private boolean held() {
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(client.wheelKey());
        if (key.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        return key.getType() == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(minecraft.getWindow(), key.getValue());
    }

    private double cursorX() { return minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()); }
    private double cursorY() { return minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()); }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_SPACE) {
            client.selectNext();
            return true;
        }
        // Mounted combat: the rider's attack keys. A cast closes the wheel so the strike is seen; a cooling attack does nothing.
        com.digicube.entity.DigimonEntity mount = RiderAttacks.mount(minecraft);
        int slot = event.key() == InputConstants.KEY_Q ? 0 : event.key() == InputConstants.KEY_E ? 1 : -1;
        if (mount != null && slot >= 0 && slot < mount.riderAttacks().size()) {
            if (mount.seenCooldown(mount.riderAttacks().get(slot)) == 0) {
                client.send(new PartyActionPayload(PartyActionPayload.RIDER_ATTACK, PartyActionPayload.NO_MEMBER, slot));
                onClose();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    /** Letting go: the order under the cursor goes to the server if it is available, and the wheel closes either way. */
    private void give(double mouseX, double mouseY) {
        if (DevGear.over(mouseX, mouseY, width, height)) {
            DevGear.open();
            return;
        }
        if (onDigivice(mouseX, mouseY)) {
            // The server opens the screen, as it does for the item: the snapshot it answers with carries the reserve.
            client.send(new PartyActionPayload(PartyActionPayload.OPEN, PartyActionPayload.NO_MEMBER, 0));
            onClose();
            return;
        }
        PartyMemberView member = member();
        int sector = CommandWheelReadout.sector(mouseX - width / 2, mouseY - height / 2);
        if (member != null && sector != CommandWheelReadout.NONE) {
            Module module = modules(member)[sector];
            if (module.enabled()) {
                Order order = module.order();
                client.send(order.evolution()
                        ? new PartyActionPayload(order.action(), member.id(), 0, member.generation(), member.sequence())
                        : new PartyActionPayload(order.action(), member.id(), 0));
            }
        }
        onClose();
    }

    private boolean onDigivice(double mouseX, double mouseY) {
        return CommandWheelReadout.overDigivice(mouseX - width / 2, mouseY - height / 2);
    }

    private PartyMemberView member() {
        return client.member(client.selected());
    }

    private Module[] modules(PartyMemberView member) {
        return CommandWheelReadout.modules(member, client.snapshotAge(), EvolutionRules.target(member.species(), member.level()).isPresent(), client.rideable(member));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        PartyMemberView member = member();
        float time = ticks + partialTick;
        float fade = Math.min(1.0F, time / OPEN_TICKS);
        int cx = width / 2, cy = height / 2;
        rect(g, 0, 0, width, height, tint(DigiTheme.VOID, VEIL_ALPHA, fade));
        boolean onGear = DevGear.over(mouseX, mouseY, width, height);
        boolean onDigivice = !onGear && onDigivice(mouseX, mouseY);
        digiviceKey(g, CommandWheelReadout.digiviceX(cx), CommandWheelReadout.digiviceY(cy), onDigivice, fade);
        if (member == null) {
            emptyHub(g, cx - HUB / 2, cy - HUB / 2, fade);
            String none = Component.translatable("gui.digicube.wheel.no_partner").getString();
            g.text(font, none, cx - font.width(none) / 2, cy + HUB / 2 + 4, tint(DigiTheme.MUTED, 0xFF, fade), true);
            if (DevGear.available()) DevGear.draw(g, font, width, height, onGear, onGear ? gearDwell + partialTick : 0, fade);
            return;
        }

        Module[] modules = modules(member);
        int pointed = CommandWheelReadout.sector(mouseX - cx, mouseY - cy);
        int chosen = !onGear && !onDigivice && pointed != CommandWheelReadout.NONE && modules[pointed].enabled() ? pointed : CommandWheelReadout.NONE;

        hub(g, cx - HUB / 2, cy - HUB / 2, member, time, fade);
        neighbours(g, cx, cy, time, fade);
        String name = DigiPanels.shortText(font, PartyGraphics.name(member), CommandWheelReadout.COLUMN_GAP + 2 * W);
        g.text(font, name, cx - font.width(name) / 2, cy + HUB / 2 + 4, tint(DigiTheme.WHITE, 0xFF, fade), true);

        // sector divider, fading away from the hub band
        int topRow = CommandWheelReadout.moduleY(CommandWheelReadout.TOP_LEFT, cy), bottomRow = CommandWheelReadout.moduleY(CommandWheelReadout.BOTTOM_LEFT, cy);
        for (int i = 0; i < 40; i += 4) {
            int color = tint(DigiTheme.EDGE_LIGHT, Math.round(0xA0 * (1 - i / 40.0F)), fade);
            rect(g, cx, topRow + H - 4 - i, 1, 4, color);
            rect(g, cx, bottomRow + 4 + i, 1, 4, color);
        }
        groupLabel(g, cx, topRow - 12, Component.translatable("gui.digicube.wheel.behavior").getString(), fade);
        attacks(g, cx, cy, partialTick, fade);
        groupLabel(g, cx, bottomRow + H + 5, Component.translatable("gui.digicube.wheel.basic").getString(), fade);
        if (latched) {
            String hint = Component.translatable("gui.digicube.wheel.click_hint").getString();
            g.text(font, hint, cx - font.width(hint) / 2, CommandWheelReadout.digiviceY(cy) + CommandWheelReadout.DIGIVICE_HEIGHT + 5, tint(DigiTheme.MUTED, 0xFF, fade), true);
        }

        for (int sector = 0; sector < modules.length; sector++) {
            boolean selected = sector == chosen;
            int x = CommandWheelReadout.moduleX(sector, cx) + (selected ? OUTWARD[sector][0] * CommandWheelReadout.STEP_OUT : 0);
            int y = CommandWheelReadout.moduleY(sector, cy) + (selected ? OUTWARD[sector][1] * CommandWheelReadout.STEP_OUT : 0);
            module(g, x, y, modules[sector], member, selected, time, fade);
        }
        if (chosen != CommandWheelReadout.NONE) pointer(g, cx, cy, chosen, fade);
        if (DevGear.available()) DevGear.draw(g, font, width, height, onGear, onGear ? gearDwell + partialTick : 0, fade);
    }

    // --- pieces ----------------------------------------------------------------------------

    /** The partner: its sprite on the data grid, with the party strip's amber selection corners. */
    private void hub(GuiGraphicsExtractor g, int x, int y, PartyMemberView m, float time, float fade) {
        boolean lit = m.health() > 0 && m.deployed();
        DigiPanels.frame(g, x - 1, y - 1, HUB + 2, HUB + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 2);
        rect(g, x, y, HUB, HUB, tint(DigiTheme.VOID, 0xE0, fade));
        DigiPanels.grid(g, x + 1, y + 1, HUB - 2, HUB - 2, 9, tint(DigiTheme.GRID, 0x3A, fade));
        DigiPanels.bevel(g, x, y, HUB, HUB, 1, tint(DigiTheme.EDGE_LIGHT, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, x + 8, y + 33, 22, 1, lit ? tint(DigiTheme.GRID_BRIGHT, 0x80, fade) : tint(DigiTheme.GRID, 0x40, fade));
        DigiPanels.icon(g, m.species(), x + 3, y + 2, 32, DigiTheme.withAlpha(DigiTheme.WHITE, Math.round((lit ? 255 : 140) * fade)));
        int scan = Math.floorMod(ticks, SCAN_PERIOD_TICKS);
        if (scan < SCAN_SWEEP_TICKS) rect(g, x + 1, y + 1 + scan / 2, HUB - 2, 1, tint(DigiTheme.GRID_BRIGHT, 0x2C, fade));
        DigiPanels.brackets(g, x, y, HUB, HUB, 7, 2, tint(DigiTheme.AMBER, 0xFF, fade));
    }

    /** The hub with nobody in it: an empty bay, like the ones in the Digispace dock. */
    private void emptyHub(GuiGraphicsExtractor g, int x, int y, float fade) {
        DigiPanels.frame(g, x - 1, y - 1, HUB + 2, HUB + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 2);
        rect(g, x, y, HUB, HUB, tint(DigiTheme.VOID, 0xE0, fade));
        DigiPanels.grid(g, x + 1, y + 1, HUB - 2, HUB - 2, 9, tint(DigiTheme.GRID, 0x24, fade));
        DigiPanels.bevel(g, x, y, HUB, HUB, 1, tint(DigiTheme.EDGE_DIM, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, x + HUB / 2 - 4, y + HUB / 2, 9, 1, tint(DigiTheme.EDGE, 0xFF, fade));
        rect(g, x + HUB / 2, y + HUB / 2 - 4, 1, 9, tint(DigiTheme.EDGE, 0xFF, fade));
    }

    /** The Digivice key: a small module of its own under the orders, picked by pointing at it. */
    private void digiviceKey(GuiGraphicsExtractor g, int x, int y, boolean selected, float fade) {
        int w = CommandWheelReadout.DIGIVICE_WIDTH, h = CommandWheelReadout.DIGIVICE_HEIGHT;
        if (selected) y += CommandWheelReadout.STEP_OUT;
        DigiPanels.frame(g, x - 1, y - 1, w + 2, h + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 3);
        DigiPanels.frame(g, x, y, w, h, tint(DigiTheme.PANEL, 0xE6, fade), 0, 2);
        rect(g, x + 1, y + 2, w - 2, 5, tint(DigiTheme.PANEL_RAISED, 0x90, fade));
        if (selected) rect(g, x + 1, y + 1, w - 2, h - 2, tint(DigiTheme.AMBER, 0x14, fade));
        DigiPanels.bevel(g, x, y, w, h, 2, tint(selected ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        int body = selected ? DigiTheme.AMBER : DigiTheme.WHITE, drop = tint(DigiTheme.VOID, 0xC0, fade);
        CommandIcons.draw(g, CommandIcons.device(), x + 7, y + 5, drop, drop, 0);
        CommandIcons.draw(g, CommandIcons.device(), x + 6, y + 4, tint(body, 0xFF, fade), tint(DigiTheme.CYAN, 0xFF, fade), tint(DigiTheme.mix(DigiTheme.CYAN, DigiTheme.VOID, 0.65F), 0xFF, fade));
        String label = Component.translatable("gui.digicube.wheel.digivice").getString();
        g.text(font, label, x + 30 + (w - 34 - font.width(label)) / 2, y + 6, tint(body, 0xFF, fade), true);
        if (selected) DigiPanels.brackets(g, x, y, w, h, 6, 2, tint(DigiTheme.AMBER, 0xFF, fade));
    }

    /** Previous and next partner either side of the hub, with the key that steps to the next one. */
    private void neighbours(GuiGraphicsExtractor g, int cx, int cy, float time, float fade) {
        int next = client.neighbour(1), previous = client.neighbour(-1), selected = client.selected();
        if (next == selected) return;
        int y = cy - THUMB / 2, right = cx + HUB / 2 + THUMB_GAP;
        if (previous != next) thumb(g, cx - HUB / 2 - THUMB_GAP - THUMB, y, client.member(previous), fade * 0.8F);
        thumb(g, right, y, client.member(next), fade);
        int lead = (int) (time / 6) % 2;
        for (int i = 0; i < 2; i++) {
            int ax = cx + HUB / 2 + 2 + i * 4, color = tint(DigiTheme.AMBER, i == lead ? 0xFF : 0x70, fade);
            rect(g, ax, cy - 2, 1, 5, color);
            rect(g, ax + 1, cy - 1, 1, 3, color);
            rect(g, ax + 2, cy, 1, 1, color);
        }
        String key = Component.translatable("gui.digicube.wheel.next_key").getString();
        int kx = right + THUMB + 6, ky = cy - 5, kw = font.width(key) + 6;
        rect(g, kx - 1, ky - 1, kw + 2, 12, tint(DigiTheme.SHADOW, 0xE0, fade));
        rect(g, kx, ky, kw, 10, tint(DigiTheme.PANEL_RAISED, 0xF0, fade));
        DigiPanels.bevel(g, kx, ky, kw, 10, 1, tint(DigiTheme.EDGE_LIGHT, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        g.text(font, key, kx + 3, ky + 1, tint(DigiTheme.WHITE, 0xFF, fade), false);
    }

    private void thumb(GuiGraphicsExtractor g, int x, int y, PartyMemberView m, float fade) {
        if (m == null) return;
        DigiPanels.frame(g, x - 1, y - 1, THUMB + 2, THUMB + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 2);
        rect(g, x, y, THUMB, THUMB, tint(DigiTheme.VOID, 0xD0, fade));
        DigiPanels.grid(g, x + 1, y + 1, THUMB - 2, THUMB - 2, 6, tint(DigiTheme.GRID, 0x30, fade));
        DigiPanels.bevel(g, x, y, THUMB, THUMB, 1, tint(DigiTheme.EDGE_DIM, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        DigiPanels.icon(g, m.species(), x + 3, y + 2, 20, DigiTheme.withAlpha(DigiTheme.WHITE, Math.round(150 * fade)));
        rect(g, x + 18, y + 17, 7, 8, tint(DigiTheme.PANEL_RAISED, 0xFF, fade));
        DigiPanels.readout(g, x + 20, y + 19, Integer.toString(m.slot() + 1), tint(DigiTheme.MUTED, 0xFF, fade));
    }

    /**
     * Mounted combat: the mount's attack tiles either side of the orders, level with the hub, each with the key
     * that casts it (Q left, E right, as on the keyboard). The sides always have room; above the orders they
     * were cut off on short screens.
     */
    private void attacks(GuiGraphicsExtractor g, int cx, int cy, float partialTick, float fade) {
        com.digicube.entity.DigimonEntity mount = RiderAttacks.mount(minecraft);
        if (mount == null) return;
        java.util.List<com.digicube.digimon.DigimonAttack> attacks = mount.riderAttacks();
        int count = Math.min(attacks.size(), RiderAttacks.KEYS.length), tile = RiderAttacks.TEXTURE, alpha = Math.round(0xFF * fade);
        int reach = CommandWheelReadout.COLUMN_GAP / 2 + W + 14, y = cy - (tile + 15) / 2;
        for (int slot = 0; slot < count; slot++) {
            int x = slot == 0 ? Math.max(4, cx - reach - tile) : Math.min(width - 4 - tile, cx + reach);
            DigiPanels.frame(g, x - 2, y - 2, tile + 4, tile + 4, 0, tint(DigiTheme.VOID, 0xB0, fade), 2);
            RiderAttacks.tile(g, font, mount, attacks.get(slot), x, y, tile, partialTick, alpha);
            String key = RiderAttacks.KEYS[slot];
            RiderAttacks.keyCap(g, font, key, x + (tile - font.width(key) - 6) / 2, y + tile + 5, alpha);
        }
    }

    /** One order: group stripe, icon well, the order's name, and a second line only when it says something. */
    private void module(GuiGraphicsExtractor g, int x, int y, Module module, PartyMemberView m, boolean selected, float time, float fade) {
        boolean on = module.enabled();
        Order order = module.order();
        boolean behavior = order == Order.STAND_STILL || order == Order.FOLLOW || order == Order.CANCEL_TARGET || order == Order.RIDE;
        int accent = order == Order.CANCEL_TARGET ? DigiTheme.RED : order.evolution() ? DigiTheme.DATA_LIGHT : DigiTheme.CYAN;
        int stripe = order == Order.CANCEL_TARGET && on ? DigiTheme.RED : behavior ? DigiTheme.CYAN : DigiTheme.DATA_LIGHT;
        int light = selected ? DigiTheme.AMBER : on ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM;
        // an order that is ready to give and worth noticing breathes: Digivolve with the DigiSoul to spend
        if (order == Order.DIGIVOLVE && on && !selected) light = DigiTheme.mix(DigiTheme.EDGE_LIGHT, DigiTheme.AMBER, breath(time, DigiTheme.BREATH_TICKS));

        DigiPanels.frame(g, x - 1, y - 1, W + 2, H + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 3);
        DigiPanels.frame(g, x, y, W, H, tint(DigiTheme.PANEL, on ? 0xE6 : 0xA8, fade), 0, 2);
        if (on) rect(g, x + 1, y + 2, W - 2, 6, tint(DigiTheme.PANEL_RAISED, 0x90, fade));
        rect(g, x + 1, y + H - 7, W - 2, 5, tint(DigiTheme.VOID, 0x60, fade));
        if (selected) rect(g, x + 1, y + 1, W - 2, H - 2, tint(DigiTheme.AMBER, 0x14, fade));
        DigiPanels.bevel(g, x, y, W, H, 2, tint(light, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, x + 1, y + 3, 3, H - 6, tint(on ? stripe : DigiTheme.EDGE_DIM, on ? 0xE0 : 0xC0, fade));
        rect(g, x + 1, y + 3, 1, H - 6, tint(DigiTheme.WHITE, 0x28, fade));

        int wx = x + 6, wy = y + 3;
        rect(g, wx - 1, wy - 1, ICON_WELL + 2, ICON_WELL + 2, tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, wx, wy, ICON_WELL, ICON_WELL, tint(DigiTheme.VOID, 0xE6, fade));
        DigiPanels.grid(g, wx + 1, wy + 1, ICON_WELL - 2, ICON_WELL - 2, 8, tint(DigiTheme.GRID, on ? 0x34 : 0x1C, fade));
        int body = !on ? DigiTheme.EDGE : selected ? DigiTheme.AMBER : DigiTheme.WHITE;
        int tone = on ? accent : DigiTheme.EDGE_DIM;
        int drop = tint(DigiTheme.VOID, 0xC0, fade);
        CommandIcons.draw(g, order, wx + 4, wy + 4, drop, drop, 0);
        CommandIcons.draw(g, order, wx + 3, wy + 3, tint(body, 0xFF, fade), tint(tone, 0xFF, fade), tint(DigiTheme.mix(tone, DigiTheme.VOID, 0.65F), 0xFF, fade));

        int labelColor = on ? tint(selected ? DigiTheme.AMBER : DigiTheme.WHITE, 0xFF, fade) : tint(DigiTheme.MUTED, 0xA0, fade);
        String label = DigiPanels.shortText(font, Component.translatable("gui.digicube.wheel." + order.name().toLowerCase(java.util.Locale.ROOT)), TEXT_W);
        String detail = detail(module, m);
        if (detail.isEmpty()) {
            g.text(font, label, x + TEXT_X, y + 12, labelColor, true);
        } else {
            g.text(font, label, x + TEXT_X, y + 6, labelColor, true);
            g.text(font, DigiPanels.shortText(font, Component.literal(detail), TEXT_W), x + TEXT_X, y + 18, tint(detailColor(module, m), on ? 0xFF : 0xB0, fade), true);
        }
        if (selected) DigiPanels.brackets(g, x, y, W, H, 7, 2, tint(DigiTheme.AMBER, 0xFF, fade));
    }

    /** A stair of squares from the hub's corner toward the chosen module. */
    private void pointer(GuiGraphicsExtractor g, int cx, int cy, int sector, float fade) {
        int left = cx - HUB / 2, top = cy - HUB / 2;
        for (int k = 0; k < 4; k++) {
            int px = OUTWARD[sector][0] < 0 ? left - 4 - k * 3 : left + HUB + 2 + k * 3;
            int py = OUTWARD[sector][1] < 0 ? top - 4 - k * 2 : top + HUB + 2 + k * 2;
            rect(g, px, py, 2, 2, tint(DigiTheme.AMBER, 0xFF - k * 0x30, fade));
        }
    }

    private void groupLabel(GuiGraphicsExtractor g, int cx, int y, String text, float fade) {
        int tw = font.width(text), x = cx - tw / 2;
        g.text(font, text, x, y, tint(DigiTheme.CYAN, 0xD0, fade), true);
        for (int i = 0; i < 32; i += 4) {
            int color = tint(DigiTheme.CYAN, Math.round(0x90 * (1 - i / 32.0F)), fade);
            rect(g, x + tw + 4 + i, y + 3, 4, 1, color);
            rect(g, x - 8 - i, y + 3, 4, 1, color);
        }
    }

    // --- rules -----------------------------------------------------------------------------

    /** The module's second line: why it is unavailable, or the DigiSoul clock on Revert. Countdowns tick locally. */
    private String detail(Module module, PartyMemberView m) {
        int age = client.snapshotAge();
        if (module.order() == Order.REVERT) return Component.translatable("gui.digicube.hud.soul", PartyGraphics.clock(PartyHudReadout.soul(m, age))).getString();
        return switch (module.reason()) {
            case NONE -> "";
            case NO_SPACE -> Component.translatable("gui.digicube.wheel.reason.no_space").getString();
            case REST -> Component.translatable("gui.digicube.hud.rest", PartyGraphics.clock(PartyHudReadout.restTicks(m, age))).getString();
            case DEFEATED -> Component.translatable("gui.digicube.hud.defeated").getString();
            case NOT_ATTACKING -> Component.translatable("gui.digicube.wheel.reason.not_attacking").getString();
            case BUSY -> Component.translatable("REVERTING".equals(m.phase()) ? "gui.digicube.hud.reverting" : "gui.digicube.hud.evolving").getString();
            case NEEDS_LEVEL -> Component.translatable("gui.digicube.wheel.reason.needs_level", com.digicube.digimon.Progression.CHAMPION_LEVEL).getString();
            case NO_ROUTE -> Component.translatable("gui.digicube.wheel.reason.no_route").getString();
            case NEEDS_ORIGIN -> Component.translatable("gui.digicube.wheel.reason.needs_origin").getString();
            case COOLDOWN -> Component.translatable("gui.digicube.hud.cooldown", (PartyHudReadout.cooldown(m, age) + 19) / 20).getString();
            case SOUL -> Component.translatable("gui.digicube.wheel.reason.soul", CommandWheelReadout.soulPercent(PartyHudReadout.soul(m, age))).getString();
        };
    }

    private int detailColor(Module module, PartyMemberView m) {
        if (module.order() == Order.REVERT) {
            int soul = PartyHudReadout.soul(m, client.snapshotAge());
            return soul < PartyHudReadout.SOUL_CRITICAL_TICKS ? DigiTheme.RED : soul < PartyHudReadout.SOUL_WARN_TICKS ? DigiTheme.AMBER : DigiTheme.CYAN;
        }
        return module.reason() == CommandWheelReadout.Reason.BUSY ? DigiTheme.CYAN : DigiTheme.MUTED;
    }

    private static void rect(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
        if ((color >>> 24) == 0 || width <= 0 || height <= 0) return;
        g.fill(x, y, x + width, y + height, color);
    }

    private static int tint(int color, int alpha, float fade) {
        return DigiTheme.withAlpha(color, Math.round(alpha * fade));
    }

    private static float breath(float time, int periodTicks) {
        return 0.5F + 0.5F * Mth.sin(time * Mth.TWO_PI / periodTicks);
    }
}
