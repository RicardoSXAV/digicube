package com.digicube.fabric.client.party;

import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.EvolutionRules;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.dev.DevGear;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.fabric.client.party.CommandWheelReadout.Module;
import com.digicube.fabric.client.party.CommandWheelReadout.Order;
import com.digicube.fabric.client.party.CommandWheelReadout.Pick;
import com.digicube.fabric.client.party.CommandWheelReadout.Refusal;
import com.digicube.fabric.client.party.CommandWheelReadout.Target;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartyRoster;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * The command wheel: held open by its key, it shows the selected partner's panel at the centre (slot tabs, its sprite,
 * its name, its attacks with an AUTO switch under each, one caption line) between four order keys, one in each quarter
 * of the screen, with the Digivice key under the panel. Outside the panel the whole quarter the cursor is in picks its
 * key; inside it only the attack tiles and their switches are targets. Letting go of the key gives what is pointed: an
 * order, a cast, a switch flip, the Digivice; pointing at nothing, it closes. A quick tap keeps the wheel open for
 * clicks, and flipping a switch by click keeps it open so several can be set. Q and E cast the first and second attack,
 * Space steps to the next partner. From the saddle the tiles are the mount's rider attacks and have no switches. The
 * cursor is a cross while the wheel is open. The party strip stays readable behind the veil.
 */
public final class CommandWheelScreen extends Screen {
    private static final int VEIL_ALPHA = 0x80;
    private static final int OPEN_TICKS = 3;
    /** A release this soon after opening, pointing at nothing, is a tap. */
    private static final int TAP_TICKS = 6;
    private static final int SCAN_PERIOD_TICKS = 160;
    private static final int SCAN_SWEEP_TICKS = 72;
    private static final int LAMP_BREATH_TICKS = 50;
    /** Tiles a panel shows at most; the first ones take the attack keys. */
    private static final int MAX_TILES = 4;
    private static final int ICON = CommandIcons.SIZE;

    private final PartyClient client;
    private int ticks;
    /** Opened by a tap rather than a hold: stays open and takes clicks. */
    private boolean latched;
    /** Ticks the cursor has rested on the developer gear; development environments only. */
    private int gearDwell;
    /** The selected partner as this client sees it, looked up once a tick; null while it is out of sight. */
    private DigimonEntity partner;

    CommandWheelScreen(PartyClient client) {
        super(Component.translatable("gui.digicube.wheel.title"));
        this.client = client;
    }

    @Override public boolean isPauseScreen() { return false; }

    /** The wheel draws its own thin veil: no blur, so the world and the party strip stay readable. */
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}

    @Override protected void init() {
        PartyMemberView member = member();
        partner = member == null ? null : client.partner(member.id());
    }

    @Override public void tick() {
        ticks++;
        if (minecraft.player == null || !minecraft.player.isAlive()) {
            onClose();
            return;
        }
        PartyMemberView member = member();
        partner = member == null ? null : client.partner(member.id());
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

    /** A latched wheel takes a click instead of a release: on a target to give it, anywhere else to close. */
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!latched) return super.mouseClicked(event, doubleClick);
        give(event.x(), event.y());
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_SPACE) {
            client.selectNext();
            PartyMemberView member = member();
            partner = member == null ? null : client.partner(member.id());
            return true;
        }
        int slot = event.key() == InputConstants.KEY_Q ? 0 : event.key() == InputConstants.KEY_E ? 1 : -1;
        if (slot >= 0) {
            // A cast closes the wheel so the strike is seen; one refused leaves it open, the caption saying why.
            if (cast(member(), slot)) onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * The key came up. After a real hold that gives what is pointed. A quick tap pointing at nothing latches the
     * wheel open instead, so tapping works as well as holding.
     */
    private void released(double mouseX, double mouseY) {
        if (pick(mouseX, mouseY).target() == Target.NOTHING && !DevGear.over(mouseX, mouseY, width, height) && ticks <= TAP_TICKS) latched = true;
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

    /**
     * Gives what is under the cursor, then closes; a switch flipped by a click, or a cast refused by a click, leaves a
     * latched wheel open.
     */
    private void give(double mouseX, double mouseY) {
        if (DevGear.over(mouseX, mouseY, width, height)) {
            DevGear.open();
            return;
        }
        PartyMemberView member = member();
        Pick pick = pick(mouseX, mouseY);
        switch (pick.target()) {
            case DIGIVICE -> {
                // The server opens the screen, as it does for the item: the snapshot it answers with carries the reserve.
                client.send(new PartyActionPayload(PartyActionPayload.OPEN, PartyActionPayload.NO_MEMBER, 0));
                onClose();
            }
            case SWITCH -> {
                client.setAuto(member, pick.index(), member.manual(pick.index()));
                if (!latched) onClose();
            }
            case TILE -> {
                if (cast(member, pick.index()) || !latched) onClose();
            }
            case KEY -> {
                Module module = modules(member)[pick.index()];
                // Before its first digivolution the partner's tree opens: the tamer picks the form, once and for good.
                if (module.enabled() && module.order() == Order.DIGIVOLVE && member.lineId() == null) client.openTree(member);
                else if (module.enabled()) {
                    Order order = module.order();
                    client.send(order.evolution()
                            ? new PartyActionPayload(order.action(), member.id(), 0, member.generation(), member.sequence())
                            : new PartyActionPayload(order.action(), member.id(), 0));
                }
                onClose();
            }
            case NOTHING -> onClose();
        }
    }

    /**
     * Casts the attack in {@code slot}: from the saddle the mount's rider attack, on foot an order to the partner. A
     * refused order leaves its reason on the caption and, once the wheel has closed, on the partner's card.
     * @return whether it went
     */
    private boolean cast(PartyMemberView member, int slot) {
        if (member == null) return false;
        DigimonEntity mount = mount(member);
        if (mount != null) return castRider(mount, slot);
        List<DigimonAttack> attacks = attacks(member, null);
        if (slot >= attacks.size()) return false;
        DigimonAttack attack = attacks.get(slot);
        float charging = charging(attack);
        Refusal refusal = CommandWheelReadout.attackRefusal(member, client.sighted() != null, readyIn(attack), charging);
        if (refusal != Refusal.NONE) {
            String key = "gui.digicube.wheel.refused." + refusal.name().toLowerCase(Locale.ROOT);
            client.notice(member.id(), (refusal == Refusal.CHARGING ? Component.translatable(key, CommandWheelReadout.percent(charging))
                    : Component.translatable(key)).getString());
            return false;
        }
        client.orderAttack(member, slot);
        return true;
    }

    /** Mounted combat: the rider's attack in {@code slot}, unless it is still cooling. A drawn shot is loosed at once (a key has no hold). */
    private boolean castRider(DigimonEntity mount, int slot) {
        List<DigimonAttack> attacks = mount.riderAttacks();
        if (slot >= attacks.size() || mount.seenCooldown(attacks.get(slot)) != 0) return false;
        client.send(new PartyActionPayload(PartyActionPayload.RIDER_ATTACK, PartyActionPayload.NO_MEMBER, slot));
        var spec = mount.riderSpec(attacks.get(slot));
        if (spec != null && spec.aim() == com.digicube.digimon.RiderAttack.Aim.SHOT && spec.input() == com.digicube.digimon.RiderAttack.Input.HOLD)
            client.send(new PartyActionPayload(PartyActionPayload.RIDER_RELEASE, PartyActionPayload.NO_MEMBER, slot));
        return true;
    }

    /** Ticks until the partner could start {@code attack}, as far as this client saw; -1 while it is out of sight. */
    private int readyIn(DigimonAttack attack) {
        if (partner == null) return -1;
        return attack.fuel() != null ? Math.round(partner.riderRefillTicks(attack)) : partner.seenCooldown(attack);
    }

    /** The share of {@code attack}'s gauge filled, as this client sees the partner; -1 for a move without one or out of sight. */
    private float charging(DigimonAttack attack) {
        var compound = com.digicube.digimon.CompoundAttacks.get(attack);
        return partner == null || compound == null || compound.gauge() == null ? -1 : partner.gaugeShare(attack);
    }

    // --- what is where ---------------------------------------------------------------------

    private PartyMemberView member() {
        return client.member(client.selected());
    }

    /** The mount under the player when it is {@code member}: the tiles are then its rider attacks. */
    private DigimonEntity mount(PartyMemberView member) {
        DigimonEntity mount = RiderAttacks.mount(minecraft);
        return member != null && mount != null && mount.getUUID().equals(member.id()) ? mount : null;
    }

    /** The tiles of {@code member}'s panel: the mount's rider attacks from the saddle, else the species' attacks in sheet order. */
    private List<DigimonAttack> attacks(PartyMemberView member, DigimonEntity mount) {
        if (member == null) return List.of();
        if (mount == null) mount = mount(member);
        List<DigimonAttack> attacks = mount != null ? mount.riderAttacks()
                : DigimonSpeciesRegistry.get(member.species()).map(DigimonSpecies::attacks).orElse(List.of());
        return attacks.size() > MAX_TILES ? attacks.subList(0, MAX_TILES) : attacks;
    }

    private Module[] modules(PartyMemberView member) {
        return CommandWheelReadout.modules(member, client.snapshotAge(), EvolutionRules.target(member.species(), member.level()).isPresent(), client.rideable(member));
    }

    /** The wheel's centre: the screen's, moved right when the party strip would sit under the left keys. */
    private int centerX() {
        boolean strip = client.snapshot().total() > 0 && !minecraft.gui.hud.isHidden();
        return strip ? CommandWheelReadout.centerX(width, PartyHudReadout.stripRight(minecraft.getWindow().getGuiScale())) : width / 2;
    }

    private int centerY() { return height / 2; }

    /** What the cursor at {@code mouseX}, {@code mouseY} points at; with nobody in the party, only the Digivice key. */
    private Pick pick(double mouseX, double mouseY) {
        PartyMemberView member = member();
        DigimonEntity mount = mount(member);
        Pick pick = CommandWheelReadout.pick(mouseX - centerX(), mouseY - centerY(), attacks(member, mount).size(), member != null && mount == null);
        return member == null && pick.target() != Target.DIGIVICE ? Pick.NOTHING : pick;
    }

    // --- drawing ---------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The cross the mockup was pointed with, instead of the arrow.
        g.requestCursor(CursorTypes.CROSSHAIR);
        float time = ticks + partialTick;
        float fade = Math.min(1.0F, time / OPEN_TICKS);
        int cx = centerX(), cy = centerY();
        rect(g, 0, 0, width, height, tint(DigiTheme.VOID, VEIL_ALPHA, fade));
        boolean onGear = DevGear.over(mouseX, mouseY, width, height);
        Pick pick = onGear ? Pick.NOTHING : pick(mouseX, mouseY);
        PartyMemberView member = member();
        DigimonEntity mount = mount(member);
        List<DigimonAttack> attacks = attacks(member, mount);

        if (member != null) {
            Module[] modules = modules(member);
            for (int key = 0; key < modules.length; key++) orderKey(g, cx, cy, key, modules[key], pick.is(Target.KEY, key), time, fade);
            panel(g, cx, cy, member, mount, attacks, modules, pick, time, partialTick, fade);
        } else {
            emptyPanel(g, cx, cy, time, fade);
        }
        int digiviceY = cy + CommandWheelReadout.DIGIVICE_Y;
        digiviceKey(g, cx - CommandWheelReadout.DIGIVICE_WIDTH / 2, digiviceY, pick.target() == Target.DIGIVICE, fade);
        int hintY = digiviceY + CommandWheelReadout.DIGIVICE_HEIGHT + 5;
        if (latched && hintY + 8 < height - 34) {
            String hint = Component.translatable("gui.digicube.wheel.click_hint").getString();
            g.text(font, hint, cx - font.width(hint) / 2, hintY, tint(DigiTheme.MUTED, 0xFF, fade), true);
        }
        if (DevGear.available()) DevGear.draw(g, font, width, height, onGear, onGear ? gearDwell + partialTick : 0, fade);
    }

    /** The partner panel: slot tabs, hub, name, attack tiles with their switches (or the riding line), caption. */
    private void panel(GuiGraphicsExtractor g, int cx, int cy, PartyMemberView member, DigimonEntity mount, List<DigimonAttack> attacks,
                       Module[] modules, Pick pick, float time, float partial, float fade) {
        int x = cx + CommandWheelReadout.PANEL_LEFT, y = cy + CommandWheelReadout.PANEL_TOP;
        panelBody(g, x, y, fade);
        tabs(g, cx, y + CommandWheelReadout.TABS_Y, fade);
        boolean lit = member.health() > 0 && member.deployed();
        hub(g, cx - CommandWheelReadout.HUB / 2, y + CommandWheelReadout.HUB_Y, member, lit, fade);
        String name = DigiPanels.shortText(font, Component.literal(upper(PartyGraphics.name(member).getString())), CommandWheelReadout.PANEL_WIDTH - 12);
        g.text(font, name, cx - font.width(name) / 2, y + CommandWheelReadout.NAME_Y, tint(DigiTheme.WHITE, 0xFF, fade), true);

        int count = attacks.size(), alpha = Math.round(0xFF * fade);
        for (int slot = 0; slot < count; slot++) {
            int tx = cx + CommandWheelReadout.tileX(slot, count), ty = y + CommandWheelReadout.TILES_Y;
            DigimonAttack attack = attacks.get(slot);
            boolean hot = pick.is(Target.TILE, slot);
            tileFrame(g, tx, ty, hot, fade);
            RiderAttacks.wheelTile(g, font, mount != null ? mount : partner, attack, tx, ty, CommandWheelReadout.TILE, partial, alpha, mount != null);
            if (slot < CommandWheelReadout.ATTACK_KEYS.length) keyCap(g, tx - 3, ty - 3, CommandWheelReadout.ATTACK_KEYS[slot], hot, fade);
            if (hot) DigiPanels.brackets(g, tx - 1, ty - 1, CommandWheelReadout.TILE + 2, CommandWheelReadout.TILE + 2, 5, 2, tint(DigiTheme.AMBER, 0xFF, fade));
            if (mount == null) autoSwitch(g, tx, y + CommandWheelReadout.SWITCHES_Y, !member.manual(slot), pick.is(Target.SWITCH, slot), time, fade);
        }
        if (mount != null && count > 0) {
            String riding = Component.translatable("gui.digicube.wheel.riding").getString();
            g.text(font, riding, cx - font.width(riding) / 2, y + CommandWheelReadout.SWITCHES_Y + 1, tint(DigiTheme.MUTED, 0xC0, fade), true);
        }
        caption(g, cx, y + CommandWheelReadout.CAPTION_Y, member, attacks, modules, pick, fade);
    }

    /** The panel with nobody in the party: an empty bay, as in the Digispace dock, and NO PARTNER OUT. */
    private void emptyPanel(GuiGraphicsExtractor g, int cx, int cy, float time, float fade) {
        int x = cx + CommandWheelReadout.PANEL_LEFT, y = cy + CommandWheelReadout.PANEL_TOP, hub = CommandWheelReadout.HUB;
        panelBody(g, x, y, fade);
        tabs(g, cx, y + CommandWheelReadout.TABS_Y, fade);
        int hx = cx - hub / 2, hy = y + CommandWheelReadout.HUB_Y;
        DigiPanels.frame(g, hx - 1, hy - 1, hub + 2, hub + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 2);
        rect(g, hx, hy, hub, hub, tint(DigiTheme.VOID, 0xE0, fade));
        DigiPanels.grid(g, hx + 1, hy + 1, hub - 2, hub - 2, 9, tint(DigiTheme.GRID, 0x24, fade));
        DigiPanels.bevel(g, hx, hy, hub, hub, 1, tint(DigiTheme.EDGE_DIM, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, hx + hub / 2 - 4, hy + hub / 2, 9, 1, tint(DigiTheme.EDGE, 0xFF, fade));
        rect(g, hx + hub / 2, hy + hub / 2 - 4, 1, 9, tint(DigiTheme.EDGE, 0xFF, fade));
        String none = Component.translatable("gui.digicube.wheel.no_partner").getString();
        g.text(font, none, cx - font.width(none) / 2, y + CommandWheelReadout.CAPTION_Y, tint(DigiTheme.MUTED, 0xFF, fade), true);
    }

    private void panelBody(GuiGraphicsExtractor g, int x, int y, float fade) {
        int w = CommandWheelReadout.PANEL_WIDTH, h = CommandWheelReadout.PANEL_HEIGHT;
        DigiPanels.frame(g, x - 1, y - 1, w + 2, h + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 3);
        DigiPanels.frame(g, x, y, w, h, tint(DigiTheme.PANEL, 0xE0, fade), 0, 2);
        rect(g, x + 1, y + 2, w - 2, 10, tint(DigiTheme.PANEL_RAISED, 0x70, fade));
        rect(g, x + 1, y + h - 12, w - 2, 10, tint(DigiTheme.VOID, 0x50, fade));
        DigiPanels.bevel(g, x, y, w, h, 2, tint(DigiTheme.EDGE_LIGHT, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
    }

    /** The party slots as small readouts: the wheel's one amber, empty ones dim. Space steps through them. */
    private void tabs(GuiGraphicsExtractor g, int cx, int y, float fade) {
        int pitch = CommandWheelReadout.TAB_PITCH, w = CommandWheelReadout.TAB_WIDTH, h = CommandWheelReadout.TAB_HEIGHT;
        int x0 = cx - (PartyRoster.PARTY_SIZE * pitch - (pitch - w)) / 2;
        for (int slot = 0; slot < PartyRoster.PARTY_SIZE; slot++) {
            int x = x0 + slot * pitch;
            boolean filled = client.member(slot) != null, selected = filled && slot == client.selected();
            rect(g, x, y, w, h, tint(selected ? DigiTheme.AMBER : filled ? DigiTheme.PANEL_RAISED : DigiTheme.VOID, selected ? 0xFF : filled ? 0xF0 : 0x80, fade));
            if (!selected) DigiPanels.bevel(g, x, y, w, h, 0, tint(filled ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM, filled ? 0xFF : 0xA0, fade),
                    tint(DigiTheme.SHADOW, 0xFF, fade));
            if (filled) DigiPanels.readout(g, x + 5, y + 2, Integer.toString(slot + 1), tint(selected ? DigiTheme.VOID : DigiTheme.MUTED, 0xFF, fade));
            else rect(g, x + 5, y + 4, 3, 1, tint(DigiTheme.EDGE_DIM, 0xFF, fade));
        }
    }

    /** The partner: its sprite on the data grid, with the party strip's amber selection corners. Dimmed while it is not out. */
    private void hub(GuiGraphicsExtractor g, int x, int y, PartyMemberView m, boolean lit, float fade) {
        int size = CommandWheelReadout.HUB;
        DigiPanels.frame(g, x - 1, y - 1, size + 2, size + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 2);
        rect(g, x, y, size, size, tint(DigiTheme.VOID, 0xE0, fade));
        DigiPanels.grid(g, x + 1, y + 1, size - 2, size - 2, 9, tint(DigiTheme.GRID, 0x3A, fade));
        DigiPanels.bevel(g, x, y, size, size, 1, tint(DigiTheme.EDGE_LIGHT, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, x + 9, y + 35, 22, 1, lit ? tint(DigiTheme.GRID_BRIGHT, 0x80, fade) : tint(DigiTheme.GRID, 0x40, fade));
        DigiPanels.icon(g, m.species(), x + 4, y + 3, 32, DigiTheme.withAlpha(DigiTheme.WHITE, Math.round((lit ? 255 : 140) * fade)));
        int scan = Math.floorMod(ticks, SCAN_PERIOD_TICKS);
        if (scan < SCAN_SWEEP_TICKS) rect(g, x + 1, y + 1 + scan / 2, size - 2, 1, tint(DigiTheme.GRID_BRIGHT, 0x2C, fade));
        DigiPanels.brackets(g, x, y, size, size, 7, 2, tint(DigiTheme.AMBER, 0xFF, fade));
    }

    /** The well an attack tile sits in, amber-rimmed while pointed. */
    private void tileFrame(GuiGraphicsExtractor g, int x, int y, boolean hot, float fade) {
        int size = CommandWheelReadout.TILE;
        rect(g, x - 2, y - 2, size + 4, size + 4, tint(DigiTheme.VOID, 0xB0, fade));
        DigiPanels.bevel(g, x - 1, y - 1, size + 2, size + 2, 0, tint(hot ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, 0xFF, fade),
                tint(hot ? DigiTheme.AMBER : DigiTheme.EDGE_DIM, 0xFF, fade));
        rect(g, x, y, size, size, tint(DigiTheme.VOID, 0xE6, fade));
    }

    /** The key that casts a tile, pocketed on its top left corner. */
    private void keyCap(GuiGraphicsExtractor g, int x, int y, String key, boolean hot, float fade) {
        rect(g, x - 1, y - 1, 11, 11, tint(DigiTheme.SHADOW, 0xE0, fade));
        rect(g, x, y, 9, 9, tint(DigiTheme.PANEL_RAISED, 0xF4, fade));
        DigiPanels.bevel(g, x, y, 9, 9, 1, tint(hot ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        g.text(font, key, x + 2, y + 1, tint(hot ? DigiTheme.AMBER : DigiTheme.WHITE, 0xFF, fade), false);
    }

    /**
     * An attack's AUTO switch: lit (a green lamp that breathes and a cyan readout on a raised pill), the Digimon uses the
     * move on its own; dark, it waits for an order.
     */
    private void autoSwitch(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hot, float time, float fade) {
        int w = CommandWheelReadout.TILE, h = CommandWheelReadout.SWITCH_HEIGHT;
        rect(g, x - 1, y - 1, w + 2, h + 2, tint(DigiTheme.SHADOW, 0xE0, fade));
        rect(g, x, y, w, h, tint(on ? DigiTheme.PANEL_RAISED : DigiTheme.VOID, on ? 0xF0 : 0xD0, fade));
        DigiPanels.bevel(g, x, y, w, h, 1, tint(hot ? DigiTheme.AMBER : on ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        int lamp = on ? tint(DigiTheme.GRID_BRIGHT, Math.round(0xC0 + 0x3F * breath(time, LAMP_BREATH_TICKS)), fade) : tint(DigiTheme.EDGE_DIM, 0x90, fade);
        rect(g, x + 2, y + 3, 3, 4, lamp);
        if (on) rect(g, x + 2, y + 3, 3, 1, tint(DigiTheme.WHITE, 0x80, fade));
        int ink = hot ? DigiTheme.AMBER : on ? DigiTheme.CYAN : DigiTheme.MUTED;
        DigiPanels.readout(g, x + 7, y + 3, "AUTO", tint(ink, on || hot ? 0xFF : 0x80, fade));
        if (hot) DigiPanels.brackets(g, x, y, w, h, 4, 1, tint(DigiTheme.AMBER, 0xFF, fade));
    }

    /**
     * The one line of words: a refused order says why (red); pointing, it names what letting go would give, or why it
     * cannot; at rest the partner's target (red), else the enemy on the crosshair an order would go at (amber), else
     * NO TARGET.
     */
    private void caption(GuiGraphicsExtractor g, int cx, int y, PartyMemberView member, List<DigimonAttack> attacks, Module[] modules,
                         Pick pick, float fade) {
        String text;
        int color = DigiTheme.MUTED;
        String notice = client.notice(member.id());
        if (notice != null) {
            text = notice;
            color = DigiTheme.RED;
        } else if (pick.target() == Target.KEY) {
            Module module = modules[pick.index()];
            text = module.enabled() ? module.order() == Order.DIGIVOLVE ? digivolveCaption(member) : label(module.order()) : reason(module, member);
            color = module.enabled() ? DigiTheme.AMBER : module.reason() == CommandWheelReadout.Reason.BUSY ? DigiTheme.CYAN : DigiTheme.MUTED;
        } else if (pick.target() == Target.TILE) {
            DigimonAttack attack = attacks.get(pick.index());
            // a move behind a gauge reads out its charge until it is full
            float charging = mount(member) == null ? charging(attack) : -1;
            boolean charged = charging < 0 || charging >= 1;
            text = charged ? attackName(attack) : Component.translatable("gui.digicube.wheel.refused.charging", CommandWheelReadout.percent(charging)).getString();
            int left = readyIn(attack);
            color = mount(member) == null && (left > 0 || !charged) ? DigiTheme.MUTED : DigiTheme.WHITE;
        } else if (pick.target() == Target.SWITCH) {
            boolean auto = !member.manual(pick.index());
            text = Component.translatable(auto ? "gui.digicube.wheel.auto_on" : "gui.digicube.wheel.auto_off").getString();
            color = auto ? DigiTheme.TEAL : DigiTheme.MUTED;
        } else if (pick.target() == Target.DIGIVICE) {
            text = Component.translatable("gui.digicube.wheel.open_digivice").getString();
            color = DigiTheme.AMBER;
        } else if (member.attacking()) {
            var target = minecraft.level == null || member.target() < 0 ? null : minecraft.level.getEntity(member.target());
            text = target == null ? Component.translatable("gui.digicube.wheel.fighting").getString()
                    : Component.translatable("gui.digicube.wheel.target", upper(target.getDisplayName().getString())).getString();
            color = DigiTheme.RED;
        } else if (client.sighted() != null) {
            text = Component.translatable("gui.digicube.wheel.in_sight", upper(client.sighted().getDisplayName().getString())).getString();
            color = DigiTheme.AMBER;
        } else {
            text = Component.translatable("gui.digicube.wheel.no_target").getString();
        }
        String line = DigiPanels.shortText(font, Component.literal(text), CommandWheelReadout.PANEL_WIDTH - 8);
        g.text(font, line, cx - font.width(line) / 2, y, tint(color, 0xFF, fade), true);
    }

    /**
     * One order key: icon on top, one word under it. The pointed key steps out with amber corners; an unavailable one
     * dims; Digivolve breathes amber while it can be given.
     */
    private void orderKey(GuiGraphicsExtractor g, int cx, int cy, int key, Module module, boolean pointed, float time, float fade) {
        boolean on = module.enabled(), selected = pointed && on;
        Order order = module.order();
        int step = selected ? CommandWheelReadout.STEP_OUT : 0;
        int x = cx + CommandWheelReadout.keyX(key) + ((key & 1) == 0 ? -step : step);
        int y = cy + CommandWheelReadout.keyY(key) + (key < CommandWheelReadout.BOTTOM_LEFT ? -step : step);
        int w = CommandWheelReadout.KEY_WIDTH, h = CommandWheelReadout.KEY_HEIGHT;
        int accent = order == Order.CANCEL_TARGET ? DigiTheme.RED : order == Order.STAND_STILL || order == Order.FOLLOW || order == Order.RIDE
                ? DigiTheme.CYAN : DigiTheme.DATA_LIGHT;
        int light = selected ? DigiTheme.AMBER : on ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM;
        if (order == Order.DIGIVOLVE && on && !selected) light = DigiTheme.mix(DigiTheme.EDGE_LIGHT, DigiTheme.AMBER, breath(time, DigiTheme.BREATH_TICKS));

        DigiPanels.frame(g, x - 1, y - 1, w + 2, h + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 3);
        DigiPanels.frame(g, x, y, w, h, tint(DigiTheme.PANEL, on ? 0xE6 : 0xA8, fade), 0, 2);
        if (on) rect(g, x + 1, y + 2, w - 2, 6, tint(DigiTheme.PANEL_RAISED, 0x90, fade));
        rect(g, x + 1, y + h - 7, w - 2, 5, tint(DigiTheme.VOID, 0x60, fade));
        if (selected) rect(g, x + 1, y + 1, w - 2, h - 2, tint(DigiTheme.AMBER, 0x14, fade));
        DigiPanels.bevel(g, x, y, w, h, 2, tint(light, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));

        int ix = x + (w - ICON) / 2, iy = y + 5;
        int body = !on ? DigiTheme.EDGE : selected ? DigiTheme.AMBER : DigiTheme.WHITE, tone = on ? accent : DigiTheme.EDGE_DIM, drop = tint(DigiTheme.VOID, 0xC0, fade);
        CommandIcons.draw(g, order, ix + 1, iy + 1, drop, drop, 0);
        CommandIcons.draw(g, order, ix, iy, tint(body, 0xFF, fade), tint(tone, 0xFF, fade), tint(DigiTheme.mix(tone, DigiTheme.VOID, 0.65F), 0xFF, fade));
        String label = DigiPanels.shortText(font, Component.literal(label(order)), w - 6);
        g.text(font, label, x + (w - font.width(label)) / 2, y + 31, on ? tint(selected ? DigiTheme.AMBER : DigiTheme.WHITE, 0xFF, fade) : tint(DigiTheme.MUTED, 0xA0, fade), true);
        if (selected) DigiPanels.brackets(g, x, y, w, h, 7, 2, tint(DigiTheme.AMBER, 0xFF, fade));
    }

    /** The Digivice key under the panel, picked by pointing at it; it steps down while pointed. */
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

    // --- words -----------------------------------------------------------------------------

    private static String label(Order order) {
        return Component.translatable("gui.digicube.wheel." + order.name().toLowerCase(Locale.ROOT)).getString();
    }

    /** Pointed at DIGIVOLVE: the choice still to make, or the form the partner is bound to. */
    private static String digivolveCaption(PartyMemberView member) {
        var line = member.lineId();
        var species = line == null ? null : DigimonSpeciesRegistry.get(line).orElse(null);
        return species == null ? Component.translatable("gui.digicube.wheel.choose_form").getString()
                : Component.translatable("gui.digicube.wheel.into", upper(Component.translatable(species.translationKey()).getString())).getString();
    }

    /** Why a key is unavailable, as the caption says it. Countdowns tick locally. */
    private String reason(Module module, PartyMemberView m) {
        return module.reason() == CommandWheelReadout.Reason.NONE ? label(module.order()) : reasonText(module.reason(), m, client.snapshotAge());
    }

    /** {@code reason} in the wheel's words for {@code m}, {@code age} ticks after its snapshot; the Digivice's tree says the same. */
    public static String reasonText(CommandWheelReadout.Reason reason, PartyMemberView m, int age) {
        return switch (reason) {
            case NONE -> label(Order.DIGIVOLVE);
            case NO_SPACE -> Component.translatable("gui.digicube.wheel.reason.no_space").getString();
            case REST -> Component.translatable("gui.digicube.hud.rest", PartyGraphics.clock(PartyHudReadout.restTicks(m, age))).getString();
            case DEFEATED -> Component.translatable("gui.digicube.hud.defeated").getString();
            case NOT_ATTACKING -> Component.translatable("gui.digicube.wheel.reason.not_attacking").getString();
            case BUSY -> Component.translatable("REVERTING".equals(m.phase()) ? "gui.digicube.hud.reverting" : "gui.digicube.hud.evolving").getString();
            case NEEDS_LEVEL -> Component.translatable("gui.digicube.wheel.reason.needs_level", com.digicube.digimon.EvolutionRules.minimumLevel(m.species())).getString();
            case NO_ROUTE -> Component.translatable("gui.digicube.wheel.reason.no_route").getString();
            case NEEDS_ORIGIN -> Component.translatable("gui.digicube.wheel.reason.needs_origin").getString();
            case COOLDOWN -> Component.translatable("gui.digicube.hud.cooldown", (PartyHudReadout.cooldown(m, age) + 19) / 20).getString();
            case SOUL -> Component.translatable("gui.digicube.wheel.reason.soul", CommandWheelReadout.soulPercent(PartyHudReadout.soul(m, age))).getString();
        };
    }

    /** An attack's name in capitals; its id, spaced, until it has a translation. */
    private static String attackName(DigimonAttack attack) {
        String key = "attack." + attack.id().getNamespace() + "." + attack.id().getPath();
        return upper(Language.getInstance().has(key) ? Component.translatable(key).getString() : attack.id().getPath().replace('_', ' '));
    }

    private static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    // --- primitives ------------------------------------------------------------------------

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
