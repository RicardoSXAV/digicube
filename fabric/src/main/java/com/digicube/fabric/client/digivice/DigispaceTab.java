package com.digicube.fabric.client.digivice;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartyRoster;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_HEIGHT;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_WIDTH;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_X;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_Y;
import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * The Digispace: the island where the Digimon that are not with the tamer wander, and the place the party is managed.
 * A Digitama from the scan sits still on the island until it hatches, half a Digimon's size so its texels are the
 * island's, its time to hatching above it; it rocks now and then and cracks at the end, and its Baby bursts out of the
 * shell where it lay ({@link #hatching}). It can be moved about the island
 * but not carried to the party. One just taken in from the hand comes together out of golden data where it lies
 * ({@link #arrive}), the camera on it.
 * The hand is the player. A click selects a Digimon and slides its card up; dragging one slides the party dock up,
 * and letting go on a bay puts it in the party (whoever was there comes back to the island, rebuilt out of data on the
 * spot the other was picked up from). With the dock pinned open by the PARTY key, a partner can be carried out of its
 * bay and set down on the island, or moved to another bay. The server decides; the tab only asks.
 */
final class DigispaceTab {
    private static final int VX = CONTENT_X, VY = CONTENT_Y, VW = CONTENT_WIDTH, VH = CONTENT_HEIGHT;
    private static final int DOCK_WIDTH = 140, DOCK_HEIGHT = 32, CARD_HEIGHT = 24, DRAG_DISTANCE = 3, FLASH_TICKS = 12, NOTE_TICKS = 60;
    /** The island and who stands where outlive the screen, so reopening the Digivice finds everyone where they were. */
    private static DigispaceWorld world;
    private static DigispaceHerd herd;

    /** What the hand holds: a walker from the island, or a partner out of a party bay. */
    private record Carry(PartyMemberView member, int slot, double fromX, double fromY) {
        boolean fromBay() { return slot >= 0; }
    }
    private record Hold(UUID id, int slot, double x, double y) {}
    private record Pan(double x, double y, double centerX, double centerY) {}
    private record Sprite(double y, DigispaceWorld.Prop prop, DigispaceHerd.Walker walker) {}

    private final DigiviceScreen screen;
    private final DigispaceCamera camera = new DigispaceCamera(VX, VY, VW, VH);
    private final int[] flash = new int[PartyRoster.PARTY_SIZE];
    private final UUID[] bays = new UUID[PartyRoster.PARTY_SIZE];
    private Hold hold;
    private Carry carry;
    private Pan pan;
    private UUID picked, shown;
    private boolean pinned;
    private float dock, lastDock, card, lastCard;
    private String note = "";
    private int noteTicks;
    /** The Digitama in the last snapshot: one that is gone from it hatched, and the Baby comes out of its shell where it sat. */
    private final java.util.Set<UUID> eggs = new java.util.HashSet<>();
    /** How cracked each Digitama was last tick, so a new crack is heard. */
    private final java.util.Map<UUID, Integer> cracks = new java.util.HashMap<>();
    /** The Digitama coming together after it was taken in from the hand, and the ticks since it began (below 0: not yet). */
    private UUID arriving;
    private int arriveTicks, arriveWait;
    /** Ticks the white of a Digitama that just came together takes to fade. */
    private static final int ARRIVE_FLASH_TICKS = 8;

    DigispaceTab(DigiviceScreen screen) {
        this.screen = screen;
        if (world == null) { world = DigispaceWorld.generate(); herd = new DigispaceHerd(world); }
        for (PartyMemberView member : screen.snapshot().party()) if (member.slot() >= 0 && member.slot() < bays.length) bays[member.slot()] = member.id();
        refresh("");
    }

    // ---------- the roster as the tab sees it ----------
    private List<PartyMemberView> reserve() { return screen.snapshot().collection().stream().filter(member -> member.slot() < 0).toList(); }
    private PartyMemberView bay(int slot) { for (PartyMemberView member : screen.snapshot().party()) if (member.slot() == slot) return member; return null; }
    private PartyMemberView member(UUID id) {
        if (id == null) return null;
        for (PartyMemberView member : screen.snapshot().party()) if (member.id().equals(id)) return member;
        for (PartyMemberView member : screen.snapshot().collection()) if (member.id().equals(id)) return member;
        return null;
    }
    private static boolean asleep(PartyMemberView member) { return member.health() <= 0; }
    /** The family a Digitama hatches into, named by its first form. */
    private static Identifier eggFamily(PartyMemberView member) {
        Identifier family = com.digicube.digimon.DigimonFamilies.of(member.species());
        return family == null ? member.species() : family;
    }
    /** A Digitama's time left, counted down locally between the server's updates. */
    private int hatchLeft(PartyMemberView member) { return Math.max(20, member.hatchTicks() - screen.client().snapshotAge()); }
    private boolean isEgg(UUID id) { PartyMemberView member = member(id); return member != null && member.egg(); }
    private static DigimonSpecies species(PartyMemberView member) { return DigimonSpeciesRegistry.get(member.species()).orElse(null); }
    private static String name(PartyMemberView member) {
        if (!member.nickname().isEmpty()) return member.nickname();
        DigimonSpecies species = species(member);
        return species == null ? member.species().getPath() : Component.translatable(species.translationKey()).getString();
    }

    /** A fresh snapshot: the herd follows the reserve, a bay whose occupant changed flashes, a refusal is shown. */
    void refresh(String message) {
        List<DigispaceHerd.Entry> entries = new ArrayList<>();
        for (PartyMemberView member : reserve()) {
            DigimonSpecies species = species(member);
            // A Digitama lies still, like a sleeper, until it hatches.
            entries.add(new DigispaceHerd.Entry(member.id(), species != null && species.baseSpeed() > 0.2F, asleep(member) || member.egg()));
        }
        herd.sync(entries);
        for (PartyMemberView member : reserve()) {
            if (member.egg()) { eggs.add(member.id()); continue; }
            DigispaceHerd.Walker hatched = eggs.remove(member.id()) ? herd.get(member.id()) : null;
            if (hatched != null) herd.hatch(hatched);
        }
        for (int slot = 0; slot < bays.length; slot++) {
            PartyMemberView now = bay(slot);
            UUID id = now == null ? null : now.id();
            if (id != null && !id.equals(bays[slot])) flash[slot] = FLASH_TICKS;
            bays[slot] = id;
        }
        if (picked != null && member(picked) == null) picked = null;
        if (!message.isEmpty()) note(Component.translatable(message).getString());
    }

    private void note(String text) { note = text.toUpperCase(Locale.ROOT); noteTicks = NOTE_TICKS; }

    void release() { hold = null; carry = null; pan = null; }

    /**
     * {@code id}, a Digitama just taken in from the hand, comes together out of golden data where it lies after
     * {@code delay} ticks (the display powering on): the camera goes to it at once, its card comes up once it is whole.
     */
    void arrive(UUID id, int delay) {
        arriving = id;
        arriveTicks = -delay;
        arriveWait = 0;
        picked = null;
        DigispaceHerd.Walker walker = herd.get(id);
        if (walker == null) return;
        camera.focus(walker.x, walker.y, 3);
        // It comes together its own way, not rebuilt like a Digimon back from the party.
        walker.spawn = 0;
    }

    /** Seconds the arriving Digitama {@code id} has been coming together, or -1 when it is not arriving. */
    private float arrival(UUID id) {
        return id.equals(arriving) ? (arriveTicks + screen.partial()) / 20F : -1;
    }

    private void tickArrival() {
        if (arriving == null) return;
        DigispaceHerd.Walker walker = herd.get(arriving);
        if (walker == null) {
            // Not in a snapshot yet: wait a moment for it, then give up.
            if (++arriveWait > 60) arriving = null;
            return;
        }
        if (arriveWait > 0) { camera.focus(walker.x, walker.y, 3); walker.spawn = 0; arriveWait = 0; }
        arriveTicks++;
        if (arriveTicks == DigitamaArt.ASSEMBLE_TICKS) {
            picked = arriving;
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(com.digicube.registry.DCSounds.RECALL_CATCH, 1F, .95F));
        } else if (arriveTicks > DigitamaArt.ASSEMBLE_TICKS + ARRIVE_FLASH_TICKS * 2) arriving = null;
    }

    /** Picks {@code id} with its card up and the camera on it, as the SCAN page does for a new Digitama. */
    void focus(UUID id) {
        DigispaceHerd.Walker walker = herd.get(id);
        if (walker == null) return;
        picked = id;
        camera.focus(walker.x, walker.y, 3);
    }

    void tick(boolean active) {
        tickArrival();
        for (int i = 0; i < flash.length; i++) if (flash[i] > 0) flash[i]--;
        if (noteTicks > 0) noteTicks--;
        boolean flashing = false;
        for (int f : flash) flashing |= f > 0;
        lastDock = dock; lastCard = card;
        // a Digitama in the hand has no bay to go to: the dock stays down for it
        dock = Math.clamp(dock + (pinned || carry != null && !carry.member().egg() || flashing ? 0.25F : -0.2F), 0, 1);
        if (picked != null) shown = picked;
        card = Math.clamp(card + (picked != null && carry == null && !screen.sheetCovering() ? 0.25F : -0.25F), 0, 1);
        if (active) herd.step(screen.ticks(), carry != null && !carry.fromBay() ? carry.member().id() : hold != null && hold.slot() < 0 ? hold.id() : null);
        if (active) eggSounds();
    }

    /** A Digitama cracking further, and a shell bursting as its Baby hatches, are heard. */
    private void eggSounds() {
        for (PartyMemberView member : reserve()) {
            if (!member.egg()) continue;
            int crack = DigitamaArt.crack(hatchLeft(member));
            Integer was = cracks.put(member.id(), crack);
            if (was != null && crack > was) Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.SNIFFER_EGG_CRACK, 1.25F, .8F));
        }
        for (DigispaceHerd.Walker w : herd.walkers()) if (w.hatch == DigispaceHerd.HATCH_TICKS - DigitamaArt.SWELL_TICKS) {
            cracks.remove(w.id);
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.SNIFFER_EGG_HATCH, 1.2F, .9F));
        }
    }

    private static float ease(float t) { return t * t * (3 - 2 * t); }
    private float dockShown() { return ease(lastDock + (dock - lastDock) * screen.partial()); }
    private float cardShown() { return ease(lastCard + (card - lastCard) * screen.partial()); }

    // ---------- geometry ----------
    private int dockX() { return VX + (VW - DOCK_WIDTH) / 2; }
    private int dockY() { return VY + VH - DOCK_HEIGHT - 3 + Math.round((1 - dockShown()) * (DOCK_HEIGHT + 6)); }
    private int[] bayRect(int slot) { return new int[]{dockX() + 42 + slot * 32, dockY() + 2, 28, 28}; }
    private int bayAt(double x, double y) {
        if (dock < 0.75F) return -1;
        for (int slot = 0; slot < bays.length; slot++) { int[] r = bayRect(slot); if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) return slot; }
        return -1;
    }
    private int cardWidth() { return 286; }
    private int cardX() { return VX + (VW - cardWidth()) / 2; }
    private int cardY() { return VY + VH - CARD_HEIGHT - 3 - Math.round(dockShown() * (DOCK_HEIGHT + 3)) + Math.round((1 - cardShown()) * (CARD_HEIGHT + DOCK_HEIGHT + 12)); }
    private boolean overPanels(double x, double y) {
        return dock > 0.5F && x >= dockX() && x < dockX() + DOCK_WIDTH && y >= dockY() && y < dockY() + DOCK_HEIGHT
                || card > 0.5F && x >= cardX() && x < cardX() + cardWidth() && y >= cardY() && y < cardY() + CARD_HEIGHT;
    }
    private boolean onIsland(double x, double y) { return camera.contains(x, y) && !overPanels(x, y); }

    private DigispaceHerd.Walker walkerAt(double x, double y) {
        if (!onIsland(x, y)) return null;
        float z = camera.scale();
        DigispaceHerd.Walker best = null;
        for (DigispaceHerd.Walker w : herd.walkers()) {
            double sx = camera.screenX(w.x), sy = camera.screenY(w.y);
            boolean egg = isEgg(w.id);
            int half = egg ? 6 : 8, top = egg ? 10 : 15;
            if (x >= sx - half * z && x < sx + half * z && y >= sy - top * z && y < sy + z && (best == null || w.y > best.y)) best = w;
        }
        return best;
    }

    /** Where a carried Digimon's feet are: it hangs under the fist (a Digitama, half as tall, nearer it). */
    private double dropX(double x) { return camera.worldX(x); }
    private double dropY(double y, Carry carried) { return camera.worldY(y + 2 + (carried.member().egg() ? 8 : 15) * camera.scale()); }

    // ---------- the hand ----------
    boolean gloveActive() { return screen.mouseX() >= 0 && (carry != null || pan != null || camera.contains(screen.mouseX(), screen.mouseY())); }

    void drawGlove(GuiGraphicsExtractor g) {
        double x = screen.mouseX(), y = screen.mouseY();
        int slot = bayAt(x, y);
        DigiviceArt.Glove glove = carry != null || pan != null ? DigiviceArt.Glove.GRAB
                : walkerAt(x, y) != null || slot >= 0 && bay(slot) != null ? DigiviceArt.Glove.OPEN : DigiviceArt.Glove.POINT;
        DigiviceArt.glove(g, glove, (int) Math.round(x), (int) Math.round(y));
    }

    void mouseDown(double x, double y) {
        if (!onIsland(x, y)) return;
        DigispaceHerd.Walker walker = walkerAt(x, y);
        if (walker != null) hold = new Hold(walker.id, -1, x, y);
        else { picked = null; pan = new Pan(x, y, camera.centerX(), camera.centerY()); }
    }

    private void dockDown() {
        int slot = bayAt(screen.mouseX(), screen.mouseY());
        if (slot >= 0 && bay(slot) != null) hold = new Hold(bay(slot).id(), slot, screen.mouseX(), screen.mouseY());
    }

    void mouseMove(double x, double y) {
        if (hold != null && Math.hypot(x - hold.x(), y - hold.y()) > DRAG_DISTANCE) {
            PartyMemberView member = member(hold.id());
            DigispaceHerd.Walker walker = herd.get(hold.id());
            if (member != null && member.egg()) { carry = new Carry(member, -1, walker == null ? 0 : walker.x, walker == null ? 0 : walker.y); picked = null; }
            else if (member != null && asleep(member)) note(Component.translatable("gui.digicube.party.defeated").getString());
            else if (member != null && member.originRequired()) note(Component.translatable("gui.digicube.evolution.origin").getString());
            else if (member != null) { carry = new Carry(member, hold.slot(), walker == null ? 0 : walker.x, walker == null ? 0 : walker.y); picked = null; }
            hold = null;
        }
        if (pan != null) camera.pan(pan.centerX(), pan.centerY(), x - pan.x(), y - pan.y());
    }

    /** Let go: on a bay the server is asked for the slot, on open ground the Digimon is set down, anywhere else nothing changes. A plain click picks. */
    void mouseUp(double x, double y) {
        if (hold != null) picked = hold.id().equals(picked) ? null : hold.id();
        Carry held = carry;
        hold = null; pan = null; carry = null;
        if (held == null) return;
        int slot = bayAt(x, y);
        double wx = dropX(x), wy = dropY(y, held);
        boolean ground = slot < 0 && onIsland(x, y) && world.walkable(wx, wy);
        UUID id = held.member().id();
        if (held.member().egg()) {
            // A Digitama moves about the island; the party is no place for it.
            DigispaceHerd.Walker egg = herd.get(id);
            if (slot >= 0) note(Component.translatable("gui.digicube.scan.egg_party").getString());
            else if (egg != null && ground) herd.settle(egg, wx, wy);
            return;
        }
        if (held.fromBay()) {
            if (slot >= 0 && slot != held.slot()) screen.send(new PartyActionPayload(PartyActionPayload.SELECT, id, slot));
            else if (ground) {
                herd.reserve(id, wx, wy);
                screen.send(new PartyActionPayload(PartyActionPayload.SELECT, id, -1));
            }
            return;
        }
        DigispaceHerd.Walker walker = herd.get(id);
        if (slot >= 0) {
            PartyMemberView leaving = bay(slot);
            if (leaving != null) herd.reserve(leaving.id(), held.fromX(), held.fromY());
            screen.send(new PartyActionPayload(PartyActionPayload.SELECT, id, slot));
        } else if (walker != null && ground) herd.settle(walker, wx, wy);
    }

    boolean keyPressed(int key) {
        if (key == InputConstants.KEY_P) { pinned = !pinned; return true; }
        if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) { camera.zoomBy(1, screen.mouseX(), screen.mouseY()); return true; }
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) { camera.zoomBy(-1, screen.mouseX(), screen.mouseY()); return true; }
        return false;
    }

    // ---------- drawing ----------
    void draw(GuiGraphicsExtractor g) {
        Font font = screen.font();
        double mx = screen.mouseX(), my = screen.mouseY();
        float z = camera.scale(), time = screen.time();
        g.fill(VX, VY, VX + VW, VY + VH, DigiTheme.VOID);
        g.enableScissor(VX, VY, VX + VW, VY + VH);

        // the net's grid moves with the ground but keeps a hairline weight at every zoom
        int pitch = DigispaceWorld.TILE * 2;
        for (int x = (int) Math.floor(camera.worldX(VX) / pitch) * pitch; camera.screenX(x) < VX + VW; x += pitch) { int sx = (int) Math.floor(camera.screenX(x)); g.fill(sx, VY, sx + 1, VY + VH, withAlpha(DigiTheme.DATA, 0x20)); }
        for (int y = (int) Math.floor(camera.worldY(VY) / pitch) * pitch; camera.screenY(y) < VY + VH; y += pitch) { int sy = (int) Math.floor(camera.screenY(y)); g.fill(VX, sy, VX + VW, sy + 1, withAlpha(DigiTheme.DATA, 0x20)); }

        DigispaceHerd.Walker hovered = carry == null && pan == null ? walkerAt(mx, my) : null;
        g.pose().pushMatrix();
        g.pose().translate((float) camera.screenX(0), (float) camera.screenY(0));
        g.pose().scale(z, z);
        looseData(g, time);
        DigiviceArt.terrain(world).draw(g, 0, 0, DigispaceWorld.WIDTH, DigispaceWorld.IMAGE_HEIGHT / DigispaceWorld.TEXELS);
        glints(g);
        if (carry != null && mx >= 0 && bayAt(mx, my) < 0) {
            int dx = (int) Math.round(dropX(mx)), dy = (int) Math.round(dropY(my, carry));
            int color = onIsland(mx, my) && world.walkable(dropX(mx), dropY(my, carry)) ? DigiTheme.CYAN : DigiTheme.RED;
            g.fill(dx - 6, dy - 1, dx + 6, dy + 2, withAlpha(color, 0x90));
            g.fill(dx - 4, dy - 2, dx + 4, dy + 3, withAlpha(color, 0x70));
        }
        List<Sprite> sprites = new ArrayList<>();
        for (DigispaceWorld.Prop prop : world.props()) if (prop.type().solid()) sprites.add(new Sprite(prop.y(), prop, null));
        for (DigispaceHerd.Walker walker : herd.walkers()) if (carry == null || carry.fromBay() || !carry.member().id().equals(walker.id)) sprites.add(new Sprite(walker.y, null, walker));
        sprites.sort(Comparator.comparingDouble(Sprite::y));
        for (Sprite sprite : sprites) {
            if (sprite.prop() != null) prop(g, sprite.prop(), time);
            else walker(g, sprite.walker(), sprite.walker() == hovered, time);
        }
        // slow cloud shadows: the light on the island is never quite still
        for (int i = 0; i < 3; i++) {
            int cx = (int) ((time * 0.12 + i * 230) % (DigispaceWorld.WIDTH + 240)) - 120, cy = 36 + i * 72;
            for (int r = -5; r <= 5; r++) { int half = (int) Math.round(Math.sqrt(1 - Math.pow(r / 6.0, 2)) * (52 + i * 8) / 4) * 4; g.fill(cx - half, cy + r * 4, cx + half, cy + r * 4 + 4, 0x14061020); }
        }
        g.pose().popMatrix();
        // news: a "!" balloon over every Digimon with a digivolution it has not been shown, the same size at every zoom
        int lift = floating();
        for (DigispaceHerd.Walker w : herd.walkers()) {
            if (carry != null && !carry.fromBay() && carry.member().id().equals(w.id) || !screen.news(member(w.id))) continue;
            double wx = w.lastX + (w.x - w.lastX) * screen.partial(), wy = w.lastY + (w.y - w.lastY) * screen.partial();
            int bob = w.moving() ? (int) Math.round(Math.abs(Math.sin(time / 3)) * 2) : 0;
            DigiviceArt.balloon(g, (int) Math.round(camera.screenX(wx)), (int) Math.round(camera.screenY(wy - 15 - bob)) - 2 - lift);
        }
        // every Digitama wears its time to hatching in the pointer's tag, the same size at every zoom; under the pointer
        // the tag names it as well
        for (DigispaceHerd.Walker w : herd.walkers()) {
            PartyMemberView member = member(w.id);
            if (member == null || !member.egg() || w == hovered && !w.id.equals(picked)
                    || carry != null && carry.member().id().equals(w.id) || w.id.equals(arriving) && arrival(w.id) < DigitamaArt.ASSEMBLE) continue;
            String clock = ScanPage.clock(hatchLeft(member));
            int tw = DigiviceKit.tagWidth(font, clock), sx = (int) Math.round(camera.screenX(w.x)), sy = (int) Math.round(camera.screenY(w.y));
            int up = Math.round(sy - 10 * z - 13);
            DigiviceKit.tag(g, font, clock, Math.clamp(sx - tw / 2, VX + 2, VX + VW - tw - 2), up < VY + 20 ? sy + 4 : up, DigiTheme.EDGE, DigiTheme.WHITE);
        }
        g.disableScissor();
        DigiPanels.frame(g, VX, VY, VW, VH, 0, DigiTheme.EDGE, 1);

        tools(g, font);
        g.enableScissor(VX, VY, VX + VW, VY + VH - 1);
        dock(g, font, mx, my);
        card(g, font);
        g.disableScissor();
        // a refusal, the server's or the tab's own, sits just above whatever panel is up
        if (noteTicks > 0) {
            int tw = DigiviceKit.tagWidth(font, note), top = Math.min(dock > 0 ? dockY() : VY + VH, card > 0 && member(shown) != null ? cardY() : VY + VH);
            DigiviceKit.tag(g, font, note, VX + (VW - tw) / 2, Math.min(top, VY + VH - 4) - 14, DigiTheme.RED, DigiTheme.RED);
        }

        if (hovered != null && !hovered.id.equals(picked)) {
            PartyMemberView member = member(hovered.id);
            if (member != null) {
                String text = member.egg() ? eggName(member) + "  " + ScanPage.clock(hatchLeft(member))
                        : name(member).toUpperCase(Locale.ROOT) + "  L" + member.level();
                int tw = DigiviceKit.tagWidth(font, text), sx = (int) Math.round(camera.screenX(hovered.x)), sy = (int) Math.round(camera.screenY(hovered.y));
                int up = Math.round(sy - (member.egg() ? 10 : 15) * z - (screen.news(member) ? DigiviceArt.BALLOON_HEIGHT + 15 : 13));
                DigiviceKit.tag(g, font, text, Math.clamp(sx - tw / 2, VX + 2, VX + VW - tw - 2), up < VY + 20 ? sy + 4 : up, DigiTheme.EDGE, DigiTheme.WHITE);
            }
        }
        if (carry != null && mx >= 0) {
            int size = Math.round(16 * z), sway = (int) Math.round(Math.sin(time / 2));
            if (carry.member().egg()) {
                // a Digitama hangs under the fist at its size on the island
                g.pose().pushMatrix();
                g.pose().translate((float) mx + sway, (float) my + 2);
                g.pose().scale(z / 2, z / 2);
                DigitamaArt.draw(g, eggFamily(carry.member()), -8, 0, 1, 1, 0, DigitamaArt.crack(hatchLeft(carry.member())), 0, 0, screen.ticks());
                g.pose().popMatrix();
            } else DigiPanels.icon(g, carry.member().species(), (int) Math.round(mx - size / 2.0) + sway, (int) Math.round(my) + 2, size);
        }
    }

    /** The news balloons float a unit up and down. */
    private int floating() { return screen.ticks() / 8 % 2; }

    /** Loose squares of data gather around the island's rim. */
    private void looseData(GuiGraphicsExtractor g, float time) {
        for (int y = -1; y <= DigispaceWorld.TILES_Y; y++) for (int x = -2; x < DigispaceWorld.TILES_X + 2; x++) {
            if (world.land(x, y)) continue;
            if (!(world.land(x - 1, y) || world.land(x + 1, y) || world.land(x, y - 1) || world.land(x, y + 1) || world.land(x - 2, y) || world.land(x + 2, y))) continue;
            double h = DigispaceWorld.hash(x, y);
            if (h <= 0.35) continue;
            int px = x * DigispaceWorld.TILE + (int) (DigispaceWorld.hash(y, x) * 5), py = y * DigispaceWorld.TILE + (int) (DigispaceWorld.hash(x, y + 1) * 5);
            double pulse = 0.5 + 0.5 * Math.sin((time + h * 200) / 14);
            g.fill(px, py, px + 2, py + 2, withAlpha(h > 0.7 ? DigiTheme.CYAN : DigiTheme.GRID_BRIGHT, 0x30 + (int) (0x90 * pulse)));
        }
    }

    private void glints(GuiGraphicsExtractor g) {
        g.pose().pushMatrix();
        g.pose().scale(1F / DigispaceWorld.TEXELS, 1F / DigispaceWorld.TEXELS);
        for (DigispaceWorld.Glint glint : world.glints()) {
            int frame = (screen.ticks() / 7 + glint.phase()) % 6;
            if (frame >= 3) continue;
            int x = Math.round(glint.x() * DigispaceWorld.TEXELS) + frame, y = Math.round(glint.y() * DigispaceWorld.TEXELS);
            g.fill(x, y, x + (frame == 1 ? 5 : 3), y + 1, frame == 1 ? 0xFFE6F6FF : 0xFF9FD0FF);
        }
        g.pose().popMatrix();
    }

    private void prop(GuiGraphicsExtractor g, DigispaceWorld.Prop prop, float time) {
        if (prop.type() == DigispaceWorld.PropType.CRYSTAL) {
            double pulse = 0.5 + 0.5 * Math.sin((time + prop.seed()) / 9);
            g.fill(prop.x() - 5, prop.y() - 2, prop.x() + 5, prop.y() + 1, withAlpha(DigiTheme.CYAN, 0x28 + (int) (0x38 * pulse)));
            g.fill(prop.x() - 3, prop.y() - 3, prop.x() + 3, prop.y() + 2, withAlpha(DigiTheme.CYAN, 0x20 + (int) (0x30 * pulse)));
        }
        DigispaceArt.Sprite art = DigispaceArt.of(prop.type());
        g.pose().pushMatrix();
        g.pose().scale(1F / DigispaceWorld.TEXELS, 1F / DigispaceWorld.TEXELS);
        DigiviceArt.prop(prop.type()).draw(g, prop.x() * DigispaceWorld.TEXELS - art.anchorX(), prop.y() * DigispaceWorld.TEXELS - art.anchorY());
        g.pose().popMatrix();
    }

    private void walker(GuiGraphicsExtractor g, DigispaceHerd.Walker w, boolean hovered, float time) {
        PartyMemberView member = member(w.id);
        if (member == null) return;
        double x = w.lastX + (w.x - w.lastX) * screen.partial(), y = w.lastY + (w.y - w.lastY) * screen.partial();
        int bob = w.moving() ? (int) Math.round(Math.abs(Math.sin(time / 3)) * 2) : 0;
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        if (member.egg()) egg(g, w, member, hovered);
        else if (w.hatch > 0) hatching(g, w, member);
        else {
            g.fill(-5, -1, 5, 1, 0x50000000);
            g.fill(-3, -2, 3, 2, 0x40000000);
            int tint = w.spawn > 0 ? withAlpha(0xFFFFFF, (int) (255 * (1 - (float) w.spawn / DigispaceHerd.SPAWN_TICKS))) : w.asleep ? 0xFF7A8794 : 0xFFFFFFFF;
            icon(g, member.species(), -8, -15 - bob, 16, tint, w.flip);
            // coming back from the party: the body is rebuilt out of data blocks
            if (w.spawn > 0) for (int k = 0; k < 14; k++) if (DigispaceWorld.hash(k, w.spawn) > 0.45) {
                int bx = -8 + (int) (DigispaceWorld.hash(k, 3) * 7) * 2, by = -15 + (int) (DigispaceWorld.hash(k, 5) * 8) * 2;
                g.fill(bx, by, bx + 2, by + 2, DigispaceWorld.hash(k, 9) > 0.5 ? DigiTheme.CYAN : DigiTheme.WHITE);
            }
            if (w.id.equals(picked)) DigiPanels.brackets(g, -8, -15, 16, 16, 4, 1, DigiTheme.AMBER);
            else if (hovered) DigiPanels.brackets(g, -8, -15, 16, 16, 4, 1, withAlpha(DigiTheme.WHITE, 0xC0));
        }
        g.pose().popMatrix();
    }

    /**
     * A Baby hatching where its Digitama lay ({@link DigispaceHerd#hatch}), at its feet: the egg swells white, the shell
     * bursts ({@link DigitamaArt#hatch}) and the Baby hops up out of it, growing from the egg's size to its own, and lands
     * with a squash.
     */
    private void hatching(GuiGraphicsExtractor g, DigispaceHerd.Walker w, PartyMemberView member) {
        float t = (DigispaceHerd.HATCH_TICKS - w.hatch + screen.partial()) / 20F, since = t - DigitamaArt.SWELL;
        Identifier family = eggFamily(member);
        g.fill(-5, -1, 5, 1, 0x50000000);
        g.fill(-3, -2, 3, 2, 0x40000000);
        if (since < 0) {
            g.pose().pushMatrix();
            g.pose().scale(0.5F, 0.5F);
            DigitamaArt.swell(g, family, -8, -15, t, screen.ticks());
            g.pose().popMatrix();
            return;
        }
        // the cap flies off behind the Baby, the cup stays in front of its feet
        g.pose().pushMatrix();
        g.pose().scale(0.5F, 0.5F);
        DigitamaArt.hatch(g, family, -8, -15, since, w.flip ? -1 : 1, false);
        g.pose().popMatrix();
        float rise = Math.clamp(since / .55F, 0, 1), land = since - .55F;
        float grow = .5F + .5F * ease(Math.clamp(since / .35F, 0, 1));
        float stretch = .12F * (float) Math.sin(Math.PI * rise), squash = land > 0 && land < .25F ? .2F * (float) Math.sin(Math.PI * land / .25F) : 0;
        g.pose().pushMatrix();
        g.pose().translate(0, -(float) Math.sin(Math.PI * rise) * 5);
        g.pose().scale(grow * (1 + squash - stretch * .5F), grow * (1 - squash + stretch));
        icon(g, member.species(), -8, -15, 16, 0xFFFFFFFF, w.flip);
        g.pose().popMatrix();
        g.pose().pushMatrix();
        g.pose().scale(0.5F, 0.5F);
        DigitamaArt.hatch(g, family, -8, -15, since, w.flip ? -1 : 1, true);
        g.pose().popMatrix();
    }

    /**
     * A Digitama on the island at its feet, drawn in half units: half a Digimon's size, so each of its texels is one of
     * the island's. It rocks now and then, flashes as it hatches, and one just taken in from the hand comes together
     * first ({@link #arrive}).
     */
    private void egg(GuiGraphicsExtractor g, DigispaceHerd.Walker w, PartyMemberView member, boolean hovered) {
        float arrival = arrival(w.id);
        // Not on the island yet: the display is still powering on.
        if (arrival < 0 && w.id.equals(arriving)) return;
        int left = hatchLeft(member);
        Identifier family = eggFamily(member);
        g.pose().pushMatrix();
        g.pose().scale(0.5F, 0.5F);
        if (arrival >= 0 && arrival < DigitamaArt.ASSEMBLE) DigitamaArt.assemble(g, family, -8, -15, arrival);
        else {
            g.fill(-8, -2, 8, 2, 0x50000000);
            g.fill(-6, -3, 6, 3, 0x30000000);
            // Just whole: white at first, and a ring of data runs out from under it.
            float landed = arrival < 0 ? 0 : 1 - (arrival - DigitamaArt.ASSEMBLE) * 20 / ARRIVE_FLASH_TICKS;
            if (arrival >= 0) DigitamaArt.landed(g, 0, 0, arrival - DigitamaArt.ASSEMBLE);
            float white = w.spawn > 0 ? (float) w.spawn / DigispaceHerd.SPAWN_TICKS : left < 40 ? 1 - left / 40F : 0;
            g.pose().pushMatrix();
            g.pose().translate(DigitamaArt.wobble(left, screen.ticks(), w.id.hashCode() & 0xFF) * 2, 0);
            DigitamaArt.draw(g, family, -8, -15, 1, 1, 0, DigitamaArt.crack(left), Math.max(white, Math.clamp(landed, 0, 1)), 0, screen.ticks());
            g.pose().popMatrix();
        }
        // set down on the island: rebuilt out of data blocks
        if (w.spawn > 0) for (int k = 0; k < 18; k++) if (DigispaceWorld.hash(k, w.spawn) > 0.4) {
            int bx = -14 + (int) (DigispaceWorld.hash(k, 3) * 14) * 2, by = -24 + (int) (DigispaceWorld.hash(k, 5) * 12) * 2;
            g.fill(bx, by, bx + 2, by + 2, DigispaceWorld.hash(k, 9) > 0.5 ? DigiTheme.CYAN : DigiTheme.WHITE);
        }
        if (w.id.equals(picked)) DigiPanels.brackets(g, -10, -19, 20, 20, 6, 2, DigiTheme.AMBER);
        else if (hovered) DigiPanels.brackets(g, -10, -19, 20, 20, 6, 2, withAlpha(DigiTheme.WHITE, 0xC0));
        g.pose().popMatrix();
    }

    /** The species icon, mirrored through its texture coordinates when the Digimon walks left. */
    private static void icon(GuiGraphicsExtractor g, Identifier species, int x, int y, int size, int color, boolean flip) {
        Identifier texture = species.withPath(path -> "textures/gui/digimon/" + path + ".png");
        if (!flip || Minecraft.getInstance().getResourceManager().getResource(texture).isEmpty()) DigiPanels.icon(g, species, x, y, size, color);
        else g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 32, 0, size, size, -32, 32, 32, 32, color);
    }

    /** The top bar: square tools on the left (for now the PARTY key), zoom on the right. */
    private void tools(GuiGraphicsExtractor g, Font font) {
        String party = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.party"));
        int tx = VX + 6, ty = VY + 4, tw = font.width(party) + 12, th = 13;
        boolean over = screen.over(tx, ty, tw, th);
        g.fill(tx - 3, ty - 2, tx + tw + 3, ty + th + 2, withAlpha(DigiTheme.VOID, 0xC0));
        DigiviceKit.keyButton(g, font, tx, ty, tw, th, party, false, false, DigiviceKit.state(true, over, screen.pressed("party")));
        if (pinned) DigiviceKit.lit(g, font, tx, ty, tw, th, party);
        // a partner has a digivolution it has not been shown
        if (screen.anyNews(screen.snapshot().party())) DigiviceArt.dot(g, tx + tw - 4, ty - 3);
        screen.hit(tx, ty, tw, th, "party", () -> pinned = !pinned);
        if (over) DigiviceKit.tag(g, font, DigiviceScreen.upper(Component.translatable(pinned ? "gui.digicube.digivice.party_hide" : "gui.digicube.digivice.party_show")), tx + tw + 5, ty + 1, DigiTheme.EDGE, DigiTheme.WHITE);

        int zx = VX + VW - 70, zy = VY + 4;
        g.fill(zx - 3, zy - 2, zx + 69, zy + 15, withAlpha(DigiTheme.VOID, 0xC0));
        DigiviceKit.keyButton(g, font, zx, zy, 14, 13, "-", false, false, DigiviceKit.state(camera.zoom() > 0, screen.over(zx, zy, 14, 13), screen.pressed("zoom_out")));
        DigiviceKit.keyButton(g, font, zx + 52, zy, 14, 13, "+", false, false, DigiviceKit.state(camera.zoom() < camera.zoomSteps() - 1, screen.over(zx + 52, zy, 14, 13), screen.pressed("zoom_in")));
        screen.hit(zx, zy, 14, 13, "zoom_out", () -> camera.zoomBy(-1, -1, -1));
        screen.hit(zx + 52, zy, 14, 13, "zoom_in", () -> camera.zoomBy(1, -1, -1));
        for (int i = 0; i < camera.zoomSteps(); i++) g.fill(zx + 18 + i * 6, zy + 9 - i, zx + 22 + i * 6, zy + 11, i <= camera.zoom() ? DigiTheme.CYAN : DigiTheme.EDGE_DIM);
        screen.wheel(VX, VY, VW, VH, steps -> camera.zoomBy(steps, screen.mouseX(), screen.mouseY()));
    }

    /** The party: three bays. While a Digimon is carried they open up, and the one under the hand says what letting go would do. */
    private void dock(GuiGraphicsExtractor g, Font font, double mx, double my) {
        if (dock <= 0 && lastDock <= 0) return;
        // a carried Digitama has no bay to go to, so none lights up for it
        boolean bringing = carry != null && !carry.member().egg();
        int x = dockX(), y = dockY(), at = bringing ? bayAt(mx, my) : -1;
        DigiPanels.frame(g, x, y, DOCK_WIDTH, DOCK_HEIGHT, withAlpha(DigiTheme.PANEL, 0xF4), DigiTheme.EDGE, 3);
        g.fill(x + 4, y, x + DOCK_WIDTH - 4, y + 1, DigiviceArt.KEY_LIGHT);
        g.fill(x + 4, y + 1, x + DOCK_WIDTH - 4, y + 2, withAlpha(DigiviceArt.KEY, 0xA0));
        g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.party")), x + 7, y + 8, DigiTheme.CYAN, false);
        g.text(font, screen.snapshot().party().size() + "/" + PartyRoster.PARTY_SIZE, x + 7, y + 18, DigiTheme.MUTED, false);
        if (dock > 0.5F) screen.hit(x, y, DOCK_WIDTH, DOCK_HEIGHT, this::dockDown);
        float pulse = 0.5F + 0.5F * (float) Math.sin(screen.time() / 4);
        for (int slot = 0; slot < bays.length; slot++) {
            int[] r = bayRect(slot);
            int sx = r[0], sy = r[1], sw = r[2], sh = r[3];
            PartyMemberView member = bay(slot);
            boolean on = at == slot, lifted = carry != null && carry.slot() == slot, hover = carry == null && member != null && screen.over(sx, sy, sw, sh);
            g.fill(sx, sy, sx + sw, sy + sh, withAlpha(DigiTheme.VOID, 0xF0));
            DigiPanels.grid(g, sx + 1, sy + 1, sw - 2, sh - 2, 9, withAlpha(DigiTheme.GRID, 0x30));
            if (on) g.fill(sx, sy, sx + sw, sy + sh, withAlpha(DigiTheme.AMBER, 0x38));
            if (member == null) {
                for (int k = 2; k < sw - 2; k += 4) { g.fill(sx + k, sy + 2, sx + k + 2, sy + 3, DigiTheme.EDGE_DIM); g.fill(sx + k, sy + sh - 3, sx + k + 2, sy + sh - 2, DigiTheme.EDGE_DIM); g.fill(sx + 2, sy + k, sx + 3, sy + k + 2, DigiTheme.EDGE_DIM); g.fill(sx + sw - 3, sy + k, sx + sw - 2, sy + k + 2, DigiTheme.EDGE_DIM); }
                g.text(font, "+", sx + (sw - font.width("+")) / 2, sy + 10, on ? DigiTheme.AMBER : DigiTheme.EDGE, false);
            } else {
                DigiPanels.icon(g, member.species(), sx + 2, sy + 1, 24, withAlpha(0xFFFFFF, lifted ? 0x40 : on ? 0x73 : 0xFF));
                DigimonSpecies species = species(member);
                if (species != null) DigiviceArt.mark(g, species.attribute(), sx + 2, sy + 2, DigiviceArt.color(species.attribute()));
                String level = "L" + member.level();
                g.fill(sx + sw - 2 - level.length() * 4, sy + sh - 8, sx + sw - 1, sy + sh - 1, withAlpha(DigiTheme.VOID, 0xD0));
                DigiPanels.readout(g, sx + sw - 1 - level.length() * 4, sy + sh - 7, level, DigiTheme.AMBER);
                float health = member.maxHealth() <= 0 ? 0 : Math.clamp(member.health() / member.maxHealth(), 0, 1);
                g.fill(sx + 1, sy + sh - 2, sx + 1 + Math.round((sw - 2) * health), sy + sh - 1, health > 0.5F ? DigiTheme.TEAL : health > 0.25F ? DigiTheme.AMBER : DigiTheme.RED);
            }
            boolean chosen = member != null && member.id().equals(picked);
            DigiPanels.frame(g, sx, sy, sw, sh, 0, on || chosen ? DigiTheme.AMBER : bringing ? withAlpha(DigiTheme.AMBER, 0x50 + (int) (0x90 * pulse)) : hover ? DigiTheme.WHITE : DigiTheme.EDGE, 1);
            if (bringing) DigiPanels.brackets(g, sx, sy, sw, sh, 5, on ? 2 : 1, withAlpha(DigiTheme.AMBER, on ? 0xFF : 0x60 + (int) (0x80 * pulse)));
            else if (chosen) DigiPanels.brackets(g, sx, sy, sw, sh, 5, 1, DigiTheme.AMBER);
            if (flash[slot] > 0) g.fill(sx, sy, sx + sw, sy + sh, withAlpha(DigiTheme.WHITE, 0xE0 * flash[slot] / FLASH_TICKS));
        }
        // a partner with news wears its balloon on the bay, drawn after the bays so a neighbour never covers it
        for (int slot = 0; slot < bays.length; slot++) {
            PartyMemberView member = bay(slot);
            if (!screen.news(member) || carry != null && carry.slot() == slot) continue;
            int[] r = bayRect(slot);
            DigiviceArt.balloon(g, r[0] + r[2] - 2, r[1] + 3 - floating());
        }
        if (carry == null || noteTicks > 0 || dock < 1) return;
        String text;
        if (at >= 0) {
            PartyMemberView there = bay(at);
            String key = carry.fromBay() ? (at == carry.slot() ? "put_back" : there != null ? "trade" : "move") : there != null ? "swap" : "join";
            text = DigiviceScreen.upper(there != null && (key.equals("trade") || key.equals("swap")) ? Component.translatable("gui.digicube.digivice.drop." + key, name(there)) : Component.translatable("gui.digicube.digivice.drop." + key));
        } else text = DigiviceScreen.upper(Component.translatable(carry.fromBay() ? "gui.digicube.digivice.drop.island" : "gui.digicube.digivice.drop.slot"));
        DigiviceKit.tag(g, font, text, x + (DOCK_WIDTH - DigiviceKit.tagWidth(font, text)) / 2, y - 14, at >= 0 ? DigiTheme.AMBER : DigiTheme.EDGE, at >= 0 ? DigiTheme.AMBER : DigiTheme.MUTED);
    }

    /** A Digitama's name: its family's first form. */
    private static String eggName(PartyMemberView member) {
        DigimonSpecies species = species(member);
        String family = species == null ? member.species().getPath() : Component.translatable(species.translationKey()).getString();
        return DigiviceScreen.upper(Component.translatable("gui.digicube.scan.digitama", family));
    }

    /** A Digitama's card: what it will hatch into and when, SCAN for its family's bar and ANALYZE for the first form. */
    private void eggCard(GuiGraphicsExtractor g, Font font, PartyMemberView member, int x, int y, int w) {
        DigiPanels.frame(g, x, y, w, CARD_HEIGHT, withAlpha(DigiTheme.PANEL, 0xF4), DigiTheme.EDGE, 3);
        g.fill(x + 4, y, x + w - 4, y + 1, DigiTheme.AMBER);
        g.fill(x + 3, y + 3, x + 21, y + 21, withAlpha(DigiTheme.VOID, 0xE0));
        int left = hatchLeft(member);
        Identifier family = eggFamily(member);
        DigitamaArt.draw(g, family, x + 4, y + 4, 1, 1, 0, DigitamaArt.crack(left), 0, 0, screen.ticks());
        g.text(font, eggName(member), x + 26, y + 4, DigiTheme.WHITE, true);
        g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.scan.hatches_in", ScanPage.clock(left))), x + 26, y + 13, DigiTheme.CYAN, false);
        String scan = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.page.scan")), analyze = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.analyze"));
        int aw = font.width(analyze) + 14, sw = font.width(scan) + 14, ax = x + w - 4 - aw, sx = ax - 4 - sw, by = y + 4;
        DigiviceKit.keyButton(g, font, sx, by, sw, 16, scan, true, false, DigiviceKit.state(true, screen.over(sx, by, sw, 16), screen.pressed("egg_scan")));
        DigiviceKit.keyButton(g, font, ax, by, aw, 16, analyze, false, false, DigiviceKit.state(true, screen.over(ax, by, aw, 16), screen.pressed("analyze")));
        // the time it has waited, filling a hairline under the card
        float done = 1 - Math.clamp(left / (float) com.digicube.digimon.Progression.DIGITAMA_HATCH_TICKS, 0, 1);
        g.fill(x + 4, y + CARD_HEIGHT - 3, x + w - 4, y + CARD_HEIGHT - 2, withAlpha(DigiTheme.EDGE_DIM, 0xC0));
        g.fill(x + 4, y + CARD_HEIGHT - 3, x + 4 + Math.round((w - 8) * done), y + CARD_HEIGHT - 2, DigiTheme.CYAN);
        boolean live = card > 0.5F;
        Identifier scanned = family;
        if (live) screen.hit(x, y, w, CARD_HEIGHT, () -> {});
        if (live) screen.hit(sx, by, sw, 16, "egg_scan", () -> screen.scan(scanned));
        if (live) screen.hit(ax, by, aw, 16, "analyze", () -> screen.analyze(member.species()));
    }

    /** A click selects: the card says who it is and opens its digivolution tree and its Analyzer entry. */
    private void card(GuiGraphicsExtractor g, Font font) {
        PartyMemberView member = member(shown);
        DigispaceHerd.Walker walker = shown == null ? null : herd.get(shown);
        boolean partner = member != null && member.slot() >= 0;
        if (member == null || walker == null && !partner || (card <= 0 && lastCard <= 0)) return;
        int x = cardX(), y = cardY(), w = cardWidth();
        if (member.egg()) { eggCard(g, font, member, x, y, w); return; }
        DigiPanels.frame(g, x, y, w, CARD_HEIGHT, withAlpha(DigiTheme.PANEL, 0xF4), DigiTheme.EDGE, 3);
        g.fill(x + 4, y, x + w - 4, y + 1, DigiTheme.AMBER);
        g.fill(x + 3, y + 3, x + 21, y + 21, withAlpha(DigiTheme.VOID, 0xE0));
        DigiPanels.icon(g, member.species(), x + 4, y + 4, 16);
        String name = name(member);
        g.text(font, name, x + 26, y + 4, DigiTheme.WHITE, true);
        DigimonSpecies species = species(member);
        if (species != null) DigiviceArt.mark(g, species.attribute(), x + 30 + font.width(name), y + 4, DigiviceArt.color(species.attribute()));
        String doing;
        int tone = DigiTheme.MUTED;
        if (asleep(member)) {
            // The rest counts down every second here, between the server's slower updates.
            int left = Math.max(20, member.restTicks() - screen.client().snapshotAge()), seconds = (left + 19) / 20;
            doing = member.restTicks() > 0 ? Component.translatable("gui.digicube.party.resting", String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)).getString() : Component.translatable("gui.digicube.party.defeated").getString();
        } else if (member.originRequired()) { doing = Component.translatable("gui.digicube.digivice.no_rookie").getString(); tone = DigiTheme.AMBER; }
        else if (partner) doing = Component.translatable("gui.digicube.digivice.in_party").getString();
        else doing = Component.translatable(walker.moving() ? "gui.digicube.digivice.wandering" : "gui.digicube.digivice.idle").getString();
        String level = Component.translatable("gui.digicube.party.level", member.level()).getString().toUpperCase(Locale.ROOT) + "  ";
        g.text(font, level, x + 26, y + 13, DigiTheme.MUTED, false);
        g.text(font, doing.toUpperCase(Locale.ROOT), x + 26 + font.width(level), y + 13, tone, false);

        // DIGIVOLUTION opens its tree (where a Champion with no Rookie form gets one), ANALYZE its entry
        String tree = DigiviceScreen.upper(Component.translatable("gui.digicube.tree.title")), analyze = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.analyze"));
        int aw = font.width(analyze) + 14, tw = font.width(tree) + 14, ax = x + w - 4 - aw, tx = ax - 4 - tw, by = y + 4;
        DigiviceKit.keyButton(g, font, tx, by, tw, 16, tree, true, false, DigiviceKit.state(true, screen.over(tx, by, tw, 16), screen.pressed("tree")));
        if (screen.news(member)) DigiviceArt.dot(g, tx + tw - 4, by - 3);
        DigiviceKit.keyButton(g, font, ax, by, aw, 16, analyze, false, false, DigiviceKit.state(true, screen.over(ax, by, aw, 16), screen.pressed("analyze")));
        boolean live = card > 0.5F;
        if (live) screen.hit(x, y, w, CARD_HEIGHT, () -> {});
        if (live) screen.hit(tx, by, tw, 16, "tree", () -> screen.openTree(member));
        if (live) screen.hit(ax, by, aw, 16, "analyze", () -> screen.analyze(member.species()));
    }
}
