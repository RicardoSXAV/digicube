package com.digicube.fabric.client.digivice;

import com.digicube.digimon.DigimonFamilies;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;
import com.digicube.scan.DigitamaItem;
import com.digicube.scan.ScanBar;
import com.digicube.spawn.SpawnDanger;
import com.digicube.spawn.SpawnHabitat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_WIDTH;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_X;
import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * The Analyzer's SCAN page: one Digitama per family, side by side, each egg its own bar, and the selected family
 * below: its forms (each defeat of any of them fills this egg), where it lives and how dangerous each place is, the
 * data, the defeats left at the tamer's recent pace, and CONVERT, open whenever the bar holds a Digitama's worth and the
 * inventory has a free slot. The new Digitama sinks through its pedestal into the tamer's inventory; used from there, it
 * goes to the island, where the Digispace shows it hatching.
 */
final class ScanPage {
    static final int BAY_WIDTH = 94, BAY_HEIGHT = 84, BAY_STEP = 98, PANEL_HEIGHT = 71, EGG_SCALE = 3, THUMB = 18;
    /** Ticks the data takes to pour into a converted egg, before it leaves for the inventory. */
    private static final int POUR_TICKS = 24, CONVERT_TIMEOUT = 80;
    /** Ticks the converted egg takes to sink through its pedestal, and a refusal stays on the panel's sentence. */
    private static final int SENT_TICKS = 30, NOTE_TICKS = 80;

    private final DigiviceScreen screen;
    private final AnalyzerTab tab;
    private Identifier selected;
    private Identifier converting;
    private int convertTicks;
    /** How many Digitama of the converting family the inventory held when CONVERT was pressed. */
    private int heldBefore;
    /** The family whose Digitama just left for the inventory, and the ticks since. */
    private Identifier sent;
    private int sentTicks;
    /** The server's reason for turning the last CONVERT down, while it is shown. */
    private String note = "";
    private int noteTicks;
    /** What the pointer is over, drawn last so nothing covers it. */
    private String tip;
    private int tipX, tipY;

    ScanPage(DigiviceScreen screen, AnalyzerTab tab) {
        this.screen = screen;
        this.tab = tab;
    }

    /** Opens the page on {@code family}. */
    void select(Identifier family) {
        if (DigimonFamilies.all().contains(family)) selected = family;
    }

    private Identifier selected() {
        List<Identifier> families = DigimonFamilies.all();
        if (selected == null || !families.contains(selected)) {
            // Open on the first family ready to convert, else the first one sighted.
            selected = families.isEmpty() ? null : families.getFirst();
            for (ScanBar bar : bars()) if (bar.seen()) { selected = bar.family(); break; }
            for (ScanBar bar : bars()) if (ready(bar)) { selected = bar.family(); break; }
        }
        return selected;
    }

    private List<ScanBar> bars() { return screen.snapshot().scan(); }

    private ScanBar bar(Identifier family) {
        for (ScanBar bar : bars()) if (bar.family().equals(family)) return bar;
        return new ScanBar(family, 0, false, -1);
    }

    static boolean ready(ScanBar bar) { return bar.seen() && bar.data() >= Progression.DIGITAMA_DATA; }

    /** The strip's counter: the fullest bar the tamer has sighted. */
    String count() {
        int best = 0;
        for (ScanBar bar : bars()) if (bar.seen()) best = Math.max(best, bar.data());
        return best * 100 / Progression.DIGITAMA_DATA + "%";
    }

    /** The newest Digitama of {@code family} in the Digivice, or null. */
    private PartyMemberView egg(Identifier family) {
        PartyMemberView newest = null;
        for (PartyMemberView member : screen.snapshot().collection())
            if (member.egg() && family.equals(DigimonFamilies.of(member.species())) && (newest == null || member.hatchTicks() > newest.hatchTicks())) newest = member;
        return newest;
    }

    /** A Digitama's time left, counted down locally between the server's updates. */
    int hatchLeft(PartyMemberView egg) { return Math.max(20, egg.hatchTicks() - screen.client().snapshotAge()); }

    /** The Digitama items of {@code family} in the tamer's inventory. */
    private static int held(Identifier family) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return 0;
        int held = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (family.equals(DigitamaItem.family(player.getInventory().getItem(i)))) held++;
        return held;
    }

    /** Whether the inventory has a free slot for a Digitama, as CONVERT needs (the server checks the same). */
    private static boolean room() {
        Player player = Minecraft.getInstance().player;
        return player != null && player.getInventory().getFreeSlot() >= 0;
    }

    void tick() {
        if (noteTicks > 0 && --noteTicks == 0) note = "";
        if (sent != null && ++sentTicks >= SENT_TICKS) sent = null;
        if (converting == null) return;
        convertTicks++;
        // Converted once its Digitama is in the inventory; the data has poured into the egg by then.
        if (convertTicks >= POUR_TICKS && held(converting) > heldBefore) {
            sent = converting;
            sentTicks = 0;
            converting = null;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.4F, .7F));
        } else if (convertTicks > CONVERT_TIMEOUT) converting = null;
    }

    /** The server's answer to the last action: a CONVERT it turned down stops pouring and says why. */
    void answer(String message) {
        if (converting == null || message.isEmpty() || message.equals("gui.digicube.scan.converted")) return;
        converting = null;
        note = Component.translatable(message).getString();
        noteTicks = NOTE_TICKS;
    }

    private void convert(Identifier family) {
        int index = DigimonFamilies.index(family);
        if (index < 0 || converting != null || !ready(bar(family)) || !room()) return;
        converting = family;
        convertTicks = 0;
        heldBefore = held(family);
        note = "";
        noteTicks = 0;
        screen.send(new PartyActionPayload(PartyActionPayload.CONVERT, PartyActionPayload.NO_MEMBER, index));
    }

    // ---------- drawing ----------
    void draw(GuiGraphicsExtractor g, Font font, int top) {
        tip = null;
        Identifier family = selected();
        if (family != null) screen.client().scanNews().opened(family);
        DigiviceArt.eggIcon(g, CONTENT_X, top - 1, DigiTheme.CYAN);
        g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.scan.rule")), CONTENT_X + 12, top + 1, DigiTheme.WHITE, false);
        g.fill(CONTENT_X, top + 11, CONTENT_X + CONTENT_WIDTH, top + 12, DigiTheme.EDGE_DIM);
        List<Identifier> families = DigimonFamilies.all();
        for (int i = 0; i < families.size() && i < 4; i++) bay(g, font, families.get(i), CONTENT_X + 1 + i * BAY_STEP, top + 15);
        if (family != null) panel(g, font, family, CONTENT_X, top + 19 + BAY_HEIGHT, CONTENT_WIDTH, PANEL_HEIGHT);
        if (tip != null) {
            int tw = DigiviceKit.tagWidth(font, tip);
            DigiviceKit.tag(g, font, tip, Math.clamp(tipX - tw / 2, CONTENT_X, CONTENT_X + CONTENT_WIDTH - tw), tipY - 13, DigiTheme.EDGE, DigiTheme.WHITE);
        }
    }

    private String name(Identifier species) {
        DigimonSpecies sheet = DigimonSpeciesRegistry.get(species).orElse(null);
        return sheet == null ? species.getPath() : AnalyzerTab.name(sheet);
    }

    private void bay(GuiGraphicsExtractor g, Font font, Identifier family, int x, int y) {
        ScanBar bar = bar(family);
        boolean seen = bar.seen(), on = family.equals(selected), hover = !on && screen.over(x, y, BAY_WIDTH, BAY_HEIGHT), ready = ready(bar);
        boolean pouring = family.equals(converting), leaving = family.equals(sent);
        DigiPanels.frame(g, x, y, BAY_WIDTH, BAY_HEIGHT, withAlpha(on ? DigiTheme.PANEL_RAISED : DigiTheme.PANEL, on ? 0xF0 : 0xB0),
                on ? DigiTheme.EDGE_LIGHT : hover ? DigiTheme.EDGE : DigiTheme.EDGE_DIM, 3);
        if (on) DigiPanels.brackets(g, x, y, BAY_WIDTH, BAY_HEIGHT, 6, 2, DigiTheme.AMBER);
        String title = seen ? name(family).toUpperCase(Locale.ROOT) : "? ? ?";
        g.text(font, title, x + (BAY_WIDTH - font.width(title)) / 2, y + 4, !seen ? DigiTheme.EDGE_LIGHT : on ? DigiTheme.AMBER : DigiTheme.WHITE, false);
        if (screen.client().scanNews().fresh(family)) DigiviceArt.dot(g, x + 2, y + 2);

        int size = DigitamaArt.SIZE * EGG_SCALE, ex = x + (BAY_WIDTH - size) / 2, ey = y + 12;
        float frac = seen ? Math.min(1, bar.data() / (float) Progression.DIGITAMA_DATA) : 0;
        float breath = 0.5F + 0.5F * (float) Math.sin(screen.time() * Math.PI * 2 / DigiTheme.BREATH_TICKS);
        float pour = pouring ? Math.min(1, (convertTicks + screen.partial()) / POUR_TICKS) : 0;
        DigitamaArt.pedestal(g, x + BAY_WIDTH / 2, ey + 45, ready || pouring);
        DigitamaArt.draw(g, family, ex, ey, EGG_SCALE, pouring ? 1 : frac, ready || pouring ? breath : 0, 0,
                pouring ? Math.min(1, pour * 1.4F) : 0, DigimonFamilies.index(family) * 5, screen.ticks());
        if (leaving) {
            // The converted egg sinks through its pedestal into the inventory, white at first.
            float q = Math.min(1, (sentTicks + screen.partial()) / SENT_TICKS);
            g.enableScissor(x, y, x + BAY_WIDTH, ey + 45);
            DigitamaArt.draw(g, family, ex, ey + Math.round(size * RecallMotion.smooth(q / .8F)), EGG_SCALE, 1, 0, 0,
                    Math.max(0, 1 - q * 4), 0, screen.ticks());
            g.disableScissor();
        }
        // converting: the data pours into the shell
        if (pouring) for (int k = 0; k < 18; k++) {
            double a = DigispaceWorld.hash(k, 7) * Math.PI * 2 + pour * 3, r = (1 - pour) * 40 + 4;
            int px = (int) Math.round(x + BAY_WIDTH / 2.0 + Math.cos(a) * r), py = (int) Math.round(ey + 24 + Math.sin(a) * r * 0.6);
            g.fill(px, py, px + 2, py + 2, DigispaceWorld.hash(k, 3) > 0.5 ? DigiTheme.CYAN : DigiTheme.WHITE);
        }
        PartyMemberView egg = egg(family);
        String status;
        int tone;
        if (!seen) {
            status = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.not_seen"));
            g.text(font, status, x + (BAY_WIDTH - font.width(status)) / 2, y + 64, DigiTheme.MUTED, false);
            status = "";
            tone = DigiTheme.MUTED;
        } else {
            String percent = bar.data() * 100 / Progression.DIGITAMA_DATA + "%";
            g.pose().pushMatrix();
            g.pose().translate(x + (BAY_WIDTH - font.width(percent) * 2) / 2F, y + 60);
            g.pose().scale(2, 2);
            g.text(font, percent, 0, 0, ready ? DigiTheme.AMBER : DigiTheme.WHITE, false);
            g.pose().popMatrix();
            if (pouring) { status = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.converting")); tone = DigiTheme.AMBER; }
            else if (leaving) { status = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.sent")); tone = DigiTheme.AMBER; }
            else if (egg != null && !ready) { status = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.hatching", clock(hatchLeft(egg)))); tone = DigiTheme.CYAN; }
            else if (ready) {
                status = DigiviceScreen.upper(Component.translatable(bar.data() >= Progression.SCAN_CAPACITY ? "gui.digicube.scan.full" : "gui.digicube.scan.ready"));
                tone = DigiTheme.AMBER;
            } else { status = bar.data() + " / " + Progression.DIGITAMA_DATA; tone = DigiTheme.MUTED; }
        }
        if (!status.isEmpty()) g.text(font, status, x + (BAY_WIDTH - font.width(status)) / 2, y + 75, tone, false);
        screen.hit(x, y, BAY_WIDTH, BAY_HEIGHT, () -> selected = family);
    }

    /** The selected family: its forms and where they live on the left, its numbers and CONVERT on the right. */
    private void panel(GuiGraphicsExtractor g, Font font, Identifier family, int x, int y, int w, int h) {
        ScanBar bar = bar(family);
        boolean seen = bar.seen(), ready = ready(bar), pouring = family.equals(converting), leaving = family.equals(sent), room = room();
        PartyMemberView egg = egg(family);
        int held = held(family);
        DigiPanels.frame(g, x, y, w, h, withAlpha(DigiTheme.PANEL, 0xE8), DigiTheme.EDGE_DIM, 2);
        g.fill(x + 3, y, x + w - 3, y + 1, withAlpha(ready ? DigiTheme.AMBER : DigiTheme.CYAN, 0x70));

        // left: the family, the forms that feed this egg
        int lx = x + 6, lw = w - 164;
        DigiviceKit.label(g, font, seen ? DigiviceScreen.upper(Component.translatable("gui.digicube.scan.family", name(family)))
                : DigiviceScreen.upper(Component.translatable("gui.digicube.scan.family_unknown")), lx, y + 4, lw);
        List<List<Identifier>> groups = new ArrayList<>();
        groups.add(List.of(family));
        List<Identifier> rookies = EvolutionTree.kids(family).stream().map(EvolutionTree.Branch::id).toList();
        groups.add(rookies);
        groups.add(rookies.stream().flatMap(r -> EvolutionTree.kids(r).stream().map(EvolutionTree.Branch::id)).toList());
        int mx = lx;
        for (int gi = 0; gi < groups.size(); gi++) {
            if (groups.get(gi).isEmpty()) continue;
            if (gi > 0) {
                g.fill(mx + 1, y + 23, mx + 4, y + 24, DigiTheme.EDGE_LIGHT);
                g.fill(mx + 3, y + 22, mx + 4, y + 25, DigiTheme.EDGE_LIGHT);
                mx += 7;
            }
            for (Identifier member : groups.get(gi)) {
                boolean known = tab.knows(member), over = screen.over(mx, y + 14, THUMB, THUMB);
                DigiviceKit.slot(g, mx, y + 14, THUMB, THUMB, over ? DigiTheme.WHITE : DigiTheme.EDGE);
                if (known) DigiPanels.icon(g, member, mx + 1, y + 15, THUMB - 2);
                else if (!DigiviceArt.silhouette(g, member, mx + 1, y + 15, THUMB - 2, AnalyzerTab.UNSEEN)) g.text(font, "?", mx + 7, y + 19, DigiTheme.EDGE_LIGHT, false);
                screen.hit(mx, y + 14, THUMB, THUMB, () -> tab.select(member));
                if (over) tip(known ? name(member).toUpperCase(Locale.ROOT) : DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_data")), mx + THUMB / 2, y + 14);
                mx += THUMB + 2;
            }
        }
        habitat(g, font, family, lx, y + 36, lw);
        String say;
        int sayTone = DigiTheme.MUTED;
        if (!note.isEmpty()) { say = note; sayTone = DigiTheme.RED; }
        else if (!seen) say = Component.translatable("gui.digicube.scan.say.unseen", Progression.FIRST_SIGHTING_DATA * 100 / Progression.DIGITAMA_DATA).getString();
        else if (pouring) { say = Component.translatable("gui.digicube.scan.say.converting", name(family)).getString(); sayTone = DigiTheme.AMBER; }
        else if (leaving) { say = Component.translatable("gui.digicube.scan.say.sent").getString(); sayTone = DigiTheme.AMBER; }
        else if (ready && !room) { say = Component.translatable("gui.digicube.scan.say.no_room").getString(); sayTone = DigiTheme.RED; }
        else if (bar.data() >= Progression.SCAN_CAPACITY) { say = Component.translatable("gui.digicube.scan.say.full").getString(); sayTone = DigiTheme.AMBER; }
        else if (ready) { say = Component.translatable("gui.digicube.scan.say.ready").getString(); sayTone = DigiTheme.AMBER; }
        else if (held > 0) { say = Component.translatable("gui.digicube.scan.say.held").getString(); sayTone = DigiTheme.CYAN; }
        else if (egg != null) { say = Component.translatable("gui.digicube.scan.say.egg").getString(); sayTone = DigiTheme.CYAN; }
        else say = Component.translatable("gui.digicube.scan.say.default").getString();
        g.text(font, font.plainSubstrByWidth(say.toUpperCase(Locale.ROOT), lw), lx, y + 58, sayTone, false);

        // right: the numbers, the danger legend and the key
        int rx = x + w - 152, rw = 146;
        g.fill(rx - 6, y + 5, rx - 5, y + h - 5, DigiTheme.EDGE_DIM);
        g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.scan.data")), rx, y + 5, DigiTheme.MUTED, false);
        String data = seen ? bar.data() + " / " + Progression.DIGITAMA_DATA : "- - -";
        g.text(font, data, rx + rw - font.width(data), y + 5, seen ? DigiTheme.WHITE : DigiTheme.MUTED, false);
        String first = "", second = "";
        int firstTone = DigiTheme.MUTED;
        boolean find = false;
        if (!seen) first = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.not_seen"));
        else if (egg != null && !ready) {
            first = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.on_island", clock(hatchLeft(egg))));
            firstTone = DigiTheme.CYAN;
            find = true;
            if (bar.defeatsLeft() > 0) second = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.next_in", bar.defeatsLeft()));
        } else if (held > 0 && !ready) {
            first = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.in_inventory"));
            firstTone = DigiTheme.CYAN;
            if (bar.defeatsLeft() > 0) second = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.next_in", bar.defeatsLeft()));
        } else if (ready) {
            boolean two = bar.data() >= Progression.SCAN_CAPACITY;
            first = DigiviceScreen.upper(Component.translatable(two ? "gui.digicube.scan.two_saved" : "gui.digicube.scan.ready_line"));
            firstTone = DigiTheme.AMBER;
            if (two) second = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.data_lost"));
            else if (bar.data() > Progression.DIGITAMA_DATA) second = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.holds", Progression.SCAN_CAPACITY * 100 / Progression.DIGITAMA_DATA));
        } else if (bar.defeatsLeft() > 0) {
            first = DigiviceScreen.upper(Component.translatable(bar.defeatsLeft() == 1 ? "gui.digicube.scan.defeats_left_one" : "gui.digicube.scan.defeats_left", bar.defeatsLeft()));
            second = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.at_pace"));
        } else {
            first = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.no_data_lately"));
            second = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.fight"));
        }
        if (find && egg != null) {
            boolean over = screen.over(rx, y + 15, rw, 9);
            g.text(font, first, rx, y + 16, over ? DigiTheme.WHITE : firstTone, false);
            if (over) g.fill(rx, y + 24, rx + font.width(first), y + 25, DigiTheme.WHITE);
            UUID id = egg.id();
            screen.hit(rx, y + 15, rw, 9, () -> screen.showEgg(id));
        } else g.text(font, first, rx, y + 16, firstTone, false);
        if (!second.isEmpty()) g.text(font, font.plainSubstrByWidth(second, rw), rx, y + 25, DigiTheme.MUTED, false);
        legend(g, font, rx, y + 37);
        String convert = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.convert"));
        int ky = y + h - 20;
        boolean can = ready && converting == null && room;
        DigiviceKit.keyButton(g, font, rx, ky, rw, 15, convert, true, false, DigiviceKit.state(can, screen.over(rx, ky, rw, 15), screen.pressed("convert")));
        if (can) screen.hit(rx, ky, rw, 15, "convert", () -> convert(family));
    }

    /** Where the family lives: each region in the colour of its danger, two lines at most; a region names its levels under the pointer. */
    private void habitat(GuiGraphicsExtractor g, Font font, Identifier family, int x, int y, int w) {
        List<SpawnHabitat.Place> places = SpawnHabitat.of(DigimonFamilies.members(family));
        String lives = DigiviceScreen.upper(Component.translatable("gui.digicube.scan.lives_in"));
        g.text(font, lives, x, y, DigiTheme.MUTED, false);
        if (places.isEmpty()) { g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.scan.nowhere")), x + font.width(lives) + 5, y, DigiTheme.MUTED, false); return; }
        int cx = x + font.width(lives) + 5, cy = y, line = 0, gap = font.width("  ");
        for (int i = 0; i < places.size(); i++) {
            SpawnHabitat.Place place = places.get(i);
            String text = DigiviceScreen.upper(Component.translatable(place.region().translationKey()));
            int tw = font.width(text);
            if (cx + tw > x + w) {
                if (++line > 1) {
                    String more = "+" + (places.size() - i);
                    g.text(font, more, Math.min(cx, x + w - font.width(more)), cy, DigiTheme.MUTED, false);
                    return;
                }
                cx = x;
                cy += 9;
            }
            boolean over = screen.over(cx, cy - 1, tw, 9);
            g.text(font, text, cx, cy, over ? DigiTheme.WHITE : color(place.region().danger()), false);
            if (over) {
                tip(DigiviceScreen.upper(Component.translatable("gui.digicube.scan.place", text,
                        Component.translatable(place.region().danger().translationKey()), place.minLevel(), place.maxLevel())), cx + tw / 2, cy);
                g.fill(cx, cy + 8, cx + tw, cy + 9, color(place.region().danger()));
            }
            cx += tw + gap;
        }
    }

    /** The three dangers in their colours: the key to the habitat line. */
    private void legend(GuiGraphicsExtractor g, Font font, int x, int y) {
        int cx = x;
        for (SpawnDanger danger : SpawnDanger.values()) {
            g.fill(cx, y + 2, cx + 5, y + 7, color(danger));
            String text = DigiviceScreen.upper(Component.translatable(danger.translationKey()));
            g.text(font, text, cx + 8, y, withAlpha(DigiTheme.MUTED, 0xE0), false);
            cx += 8 + font.width(text) + 8;
        }
    }

    static int color(SpawnDanger danger) {
        return switch (danger) {
            case CALM -> DigiTheme.TEAL;
            case WILD -> DigiTheme.AMBER;
            case DANGEROUS -> DigiTheme.RED;
        };
    }

    private void tip(String text, int x, int y) { tip = text; tipX = x; tipY = y; }

    static String clock(int ticks) {
        int seconds = (ticks + 19) / 20;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ---------- keys ----------
    boolean keyPressed(int key) {
        List<Identifier> families = DigimonFamilies.all();
        Identifier family = selected();
        if (family == null) return false;
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
            int step = key == InputConstants.KEY_LEFT || key == InputConstants.KEY_UP ? -1 : 1;
            selected = families.get(Math.floorMod(families.indexOf(family) + step, families.size()));
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) { convert(family); return true; }
        return false;
    }
}
