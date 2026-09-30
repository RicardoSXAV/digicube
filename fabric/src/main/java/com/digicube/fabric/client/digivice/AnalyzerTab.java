package com.digicube.fabric.client.digivice;

import com.digicube.digimon.CombatMark;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonAttribute;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.fabric.client.gui.DigimonPreview;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_HEIGHT;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_WIDTH;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_X;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_Y;
import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * The Analyzer, two pages under a strip: DIGIMON, every species in a numbered index with the selected one on an LCD
 * like the toy's own screen and what the scan says beside it, and MARKS, the guide to the combat marks
 * ({@link MarksPage}). An entry the tamer has not witnessed yet is a silhouette that gives nothing away; in creative
 * every entry is open. An entry recorded since the tamer connected wears a NEW tag until it is opened.
 */
final class AnalyzerTab {
    /** The pages start this far under the content area's top: the strip and a gap. */
    static final int PAGE_TOP = 16;
    private static final int ROWS = 8, ROW = 17, LIST_WIDTH = 126, LCD_WIDTH = 118, LCD_HEIGHT = 92, ICON = 64, THUMB = 24, MAX_QUERY = 24;
    private static final int PAGE_SWAP_TICKS = 6;
    private static final int DIGIMON = 0, MARKS = 1;
    /** The colour of a shape not seen yet, on the dark panel. */
    static final int UNSEEN = DigiTheme.EDGE_DIM;
    private static final String NOISE = "01#$&*%=";

    private final DigiviceScreen screen;
    private final MarksPage marksPage;
    private AnalyzerIndex index;
    private Set<CombatMark> marks = EnumSet.noneOf(CombatMark.class);
    /** Creative: every entry is open, and nothing is news. */
    private boolean open;
    private int page, pageSwap = PAGE_SWAP_TICKS;
    private Identifier selected;
    private String query = "";
    private boolean focus;
    private DigimonAttribute filter;
    private int scroll;
    /** Ticks since the selection changed: the profile decodes and the bars grow. */
    private int decode;
    private boolean model;
    private DigimonPreview preview;
    private Identifier previewed;

    AnalyzerTab(DigiviceScreen screen) {
        this.screen = screen;
        this.marksPage = new MarksPage(screen, this);
        refresh();
        // Open on the first partner: the entry the tamer most likely wants to read.
        screen.snapshot().party().stream().findFirst().ifPresent(member -> selected = member.species());
        if (selected == null || index.get(selected) == null) selected = index.entries().isEmpty() ? null : index.entries().getFirst().id();
        if (selected != null) opened(selected);
    }

    private static boolean creative() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.isCreative();
    }

    void refresh() {
        open = creative();
        Set<Identifier> known = new HashSet<>(screen.snapshot().known());
        if (open) DigimonSpeciesRegistry.all().forEach(species -> known.add(species.id()));
        index = new AnalyzerIndex(DigimonSpeciesRegistry.all(), known);
        marks = open ? EnumSet.allOf(CombatMark.class) : CombatMark.unmask(screen.snapshot().marks());
    }

    // ---------- what the pages ask ----------
    boolean knows(Identifier species) { AnalyzerIndex.Entry entry = index.get(species); return entry != null && entry.known(); }
    boolean knows(CombatMark mark) { return marks.contains(mark); }
    boolean fresh(Identifier species) { return !open && screen.client().news().fresh(species); }
    boolean fresh(CombatMark mark) { return !open && screen.client().news().fresh(mark); }
    /** The tamer has the entry in front of them: once it is known, its NEW tag goes. */
    void opened(Identifier species) { if (knows(species)) screen.client().news().opened(species); }
    void opened(CombatMark mark) { if (knows(mark)) screen.client().news().opened(mark); }

    /** Opens the DIGIMON page on {@code species}. */
    void select(Identifier species) {
        if (index.get(species) == null) return;
        show(DIGIMON);
        if (!species.equals(selected)) { selected = species; decode = 0; }
        opened(species);
        filter = null;
        query = "";
        reveal();
    }

    /** Opens the MARKS page on {@code mark}. */
    void select(CombatMark mark) {
        show(MARKS);
        marksPage.select(mark);
    }

    private void choose(Identifier species) {
        if (!species.equals(selected)) { selected = species; decode = 0; }
        opened(species);
    }

    private void show(int next) {
        if (next == page) return;
        page = next;
        pageSwap = 0;
        focus = false;
    }

    private void reveal() {
        List<AnalyzerIndex.Entry> list = list();
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(selected)) {
            if (i < scroll) scroll = i;
            if (i >= scroll + ROWS) scroll = i - ROWS + 1;
        }
    }

    void blur() { focus = false; }

    void tick() {
        decode++;
        pageSwap++;
        marksPage.tick();
        if (preview != null) preview.tick();
        if (creative() != open) refresh();
    }

    static String name(DigimonSpecies species) { return Component.translatable(species.translationKey()).getString(); }
    private List<AnalyzerIndex.Entry> list() { return index.filter(filter, query, AnalyzerTab::name); }

    // ---------- drawing ----------
    void draw(GuiGraphicsExtractor g) {
        Font font = screen.font();
        strip(g, font);
        int top = CONTENT_Y + PAGE_TOP;
        if (page == MARKS) marksPage.draw(g, font, top);
        else {
            AnalyzerIndex.Entry entry = selected == null ? null : index.get(selected);
            index(g, font, list(), top);
            if (entry != null) {
                portrait(g, font, entry, top);
                scan(g, font, entry, top);
            }
        }
        // Changing page breaks the old one into blocks, a lighter version of the device changing tab.
        float swapped = (pageSwap + screen.partial()) / PAGE_SWAP_TICKS;
        if (swapped < 1) {
            int block = 10, bottom = CONTENT_Y + CONTENT_HEIGHT;
            for (int y = 0; y < CONTENT_HEIGHT - PAGE_TOP; y += block) for (int x = 0; x < CONTENT_WIDTH; x += block) {
                if (DigispaceWorld.hash(x + 7, y) <= swapped) continue;
                g.fill(CONTENT_X + x, top + y, Math.min(CONTENT_X + CONTENT_WIDTH, CONTENT_X + x + block), Math.min(bottom, top + y + block),
                        DigispaceWorld.hash(y, x + 7) > 0.85 ? withAlpha(DigiTheme.CYAN, 0xA0) : withAlpha(DigiTheme.VOID, 0xE0));
            }
        }
    }

    /** The two pages, each with how much of it is on record; in creative, how much there is. */
    private void strip(GuiGraphicsExtractor g, Font font) {
        int x = CONTENT_X, y = CONTENT_Y, h = DigiviceKit.PAGE_TAB_HEIGHT;
        g.fill(x, y + h - 1, x + CONTENT_WIDTH, y + h, DigiTheme.EDGE_DIM);
        AnalyzerNews news = screen.client().news();
        for (int i = 0; i < 2; i++) {
            String label = DigiviceScreen.upper(Component.translatable(i == DIGIMON ? "gui.digicube.digivice.page.digimon" : "gui.digicube.digivice.page.marks"));
            int total = i == DIGIMON ? index.entries().size() : CombatMark.values().length, known = i == DIGIMON ? index.knownCount() : marks.size();
            String count = open ? Integer.toString(total) : known + "/" + total;
            int w = DigiviceKit.pageTabWidth(font, label, count), at = i;
            boolean active = page == i, pending = !open && (i == DIGIMON ? news.anySpecies() : news.anyMarks()) && screen.ticks() % 20 < 14;
            DigiviceKit.pageTab(g, font, x, y, i == DIGIMON ? DigiviceArt::pawIcon : DigiviceArt::badgeIcon, label, count, active, !active && screen.over(x, y, w, h), pending);
            screen.hit(x, y, w, h, () -> show(at));
            x += w + 4;
        }
    }

    /** Left: the search, the attribute filter, the numbered rows. */
    private void index(GuiGraphicsExtractor g, Font font, List<AnalyzerIndex.Entry> list, int y) {
        int x = CONTENT_X;
        boolean bad = !query.isEmpty() && list.isEmpty();
        DigiviceKit.field(g, font, x, y, LIST_WIDTH, 14, query.toUpperCase(Locale.ROOT), DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.search")), focus, screen.ticks() % 20 < 10, list.size(), bad);
        screen.hit(x, y, LIST_WIDTH, 14, () -> focus = true);

        int fx = x;
        DigimonAttribute[] kinds = {null, DigimonAttribute.VACCINE, DigimonAttribute.DATA, DigimonAttribute.VIRUS, DigimonAttribute.FREE};
        for (DigimonAttribute kind : kinds) {
            int w = kind == null ? 30 : 21;
            DigiviceKit.chip(g, font, fx, y + 17, w, 13, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.all")), kind, filter == kind, screen.over(fx, y + 17, w, 13));
            screen.hit(fx, y + 17, w, 13, () -> { filter = kind; scroll = 0; });
            fx += w + 3;
        }

        int ly = y + 33;
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - ROWS));
        g.fill(x, ly, x + LIST_WIDTH - 4, ly + ROWS * ROW, withAlpha(DigiTheme.PANEL, 0x90));
        if (list.isEmpty()) {
            String none = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_match"));
            g.text(font, none, x + (LIST_WIDTH - 4 - font.width(none)) / 2, ly + 62, DigiTheme.MUTED, false);
        }
        for (int i = 0; i < ROWS && scroll + i < list.size(); i++) {
            AnalyzerIndex.Entry e = list.get(scroll + i);
            int ry = ly + i * ROW;
            row(g, font, x, ry, LIST_WIDTH - 5, e, e.id().equals(selected), screen.over(x, ry, LIST_WIDTH - 5, ROW));
            screen.hit(x, ry, LIST_WIDTH - 5, ROW, () -> choose(e.id()));
        }
        DigiviceKit.scrollbar(g, x + LIST_WIDTH - 3, ly, ROWS * ROW, list.size(), ROWS, scroll);
        screen.wheel(x, ly, LIST_WIDTH, ROWS * ROW, steps -> scroll -= steps);
    }

    private void row(GuiGraphicsExtractor g, Font font, int x, int y, int w, AnalyzerIndex.Entry e, boolean chosen, boolean hover) {
        if (chosen) {
            g.fill(x, y, x + w, y + 16, withAlpha(DigiviceArt.KEY, 0x50));
            g.fill(x, y, x + 2, y + 16, DigiTheme.AMBER);
            g.fill(x + w - 1, y, x + w, y + 16, withAlpha(DigiTheme.AMBER, 0x80));
        } else if (hover) g.fill(x, y, x + w, y + 16, withAlpha(DigiTheme.PANEL_RAISED, 0xE0));
        g.fill(x, y + 16, x + w, y + 17, withAlpha(DigiTheme.EDGE_DIM, 0x80));
        DigiPanels.readout(g, x + 5, y + 6, e.label(), withAlpha(chosen ? DigiTheme.AMBER : DigiTheme.MUTED, e.known() ? 0xFF : 0x80));
        if (!e.known()) {
            if (!DigiviceArt.silhouette(g, e.id(), x + 19, y, 16, UNSEEN)) g.text(font, "?", x + 25, y + 5, DigiTheme.EDGE, false);
            g.text(font, "? ? ? ? ?", x + 38, y + 5, withAlpha(DigiTheme.EDGE_LIGHT, 0xC0), false);
            return;
        }
        DigiPanels.icon(g, e.id(), x + 19, y, 16);
        int room = w - 52;
        if (fresh(e.id())) {
            String tag = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.new"));
            int tw = DigiviceKit.newTagWidth(font, tag);
            DigiviceKit.newTag(g, font, tag, x + w - 15 - tw, y + 3);
            room -= tw + 3;
        }
        g.text(font, font.plainSubstrByWidth(name(e.species()), room), x + 38, y + 5, chosen ? DigiTheme.AMBER : DigiTheme.WHITE, true);
        DigiviceArt.mark(g, e.species().attribute(), x + w - 12, y + 5, DigiviceArt.color(e.species().attribute()));
    }

    /** Centre: the LCD, the stage ladder, the 3D switch and the profile. */
    private void portrait(GuiGraphicsExtractor g, Font font, AnalyzerIndex.Entry entry, int y) {
        int x = CONTENT_X + 132, w = LCD_WIDTH, h = LCD_HEIGHT;
        boolean known = entry.known();
        DigimonSpecies species = entry.species();
        DigiviceKit.lcd(g, x, y, w, h);
        if (known && model && preview(species.id()) != null) {
            int x1 = screen.screenX(x), y1 = screen.screenY(y + 10), x2 = screen.screenX(x + w), y2 = screen.screenY(y + 75);
            float turn = screen.time() * DigiTheme.TURNTABLE_DEGREES_PER_TICK;
            screen.unscaled(g, () -> preview.draw(g, x1, y1, x2, y2, y1 + Math.round((y2 - y1) * 0.92F), turn, 0, 0, screen.partial()));
        } else {
            int bob = (int) Math.round(Math.sin(screen.time() / 9) * 1.5), sx = x + (w - ICON) / 2, sy = y + 9 + bob;
            DigiPanels.icon(g, species.id(), sx + 2, sy + 2, ICON, known ? 0x47000000 : 0xD9000000);
            if (known) DigiPanels.icon(g, species.id(), sx, sy, ICON);
        }
        g.text(font, "No." + entry.label(), x + 4, y + 4, withAlpha(DigiviceArt.INK, 0xD0), false);
        if (known) DigiviceArt.mark(g, species.attribute(), x + w - 11, y + 4, DigiviceArt.INK);
        g.fill(x + 30, y + 75, x + w - 30, y + 76, withAlpha(DigiviceArt.INK, 0x50));
        String title = known ? name(species).toUpperCase(Locale.ROOT) : DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_data"));
        int big = font.width(title) * 2 > w - 8 ? 1 : 2;
        g.pose().pushMatrix();
        g.pose().translate(x + (w - font.width(title) * big) / 2F, y + (big == 2 ? 77 : 81));
        g.pose().scale(big, big);
        g.text(font, title, 0, 0, DigiviceArt.INK, false);
        g.pose().popMatrix();
        int sweep = screen.ticks() % 110;
        if (sweep < h / 2) g.fill(x, y + sweep * 2, x + w, y + sweep * 2 + 2, withAlpha(DigiviceArt.LCD_LIGHT, 0x70));
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            int bx = x + (a == 1 ? w - 6 : 0), by = y + (b == 1 ? h - 6 : 0);
            g.fill(bx, by + (b == 1 ? 5 : 0), bx + 6, by + (b == 1 ? 6 : 1), withAlpha(DigiviceArt.INK, 0x90));
            g.fill(bx + (a == 1 ? 5 : 0), by, bx + (a == 1 ? 6 : 1), by + 6, withAlpha(DigiviceArt.INK, 0x90));
        }

        int sy = y + 95, tier = species.stage().getTier();
        g.text(font, known ? DigiviceScreen.upper(Component.translatable("digicube.stage." + species.stage().getId())) : "- - -", x, sy + 3, known ? DigiTheme.WHITE : DigiTheme.MUTED, false);
        for (int i = 0; i < 6; i++) {
            int bar = 3 + i * 2;
            g.fill(x + w - 40 + i * 7, sy + 12 - bar, x + w - 35 + i * 7, sy + 12, known && tier == i ? DigiTheme.AMBER : known && i < tier ? withAlpha(DigiTheme.CYAN, 0xB0) : DigiTheme.EDGE_DIM);
        }

        String label = DigiviceScreen.upper(Component.translatable(model ? "gui.digicube.digivice.view_2d" : "gui.digicube.digivice.view_3d"));
        int bx = x, by = y + 110, bw = 72, bh = 15;
        DigiviceKit.keyButton(g, font, bx, by, bw, bh, label, true, true, DigiviceKit.state(known, screen.over(bx, by, bw, bh), screen.pressed("view")));
        if (known) screen.hit(bx, by, bw, bh, "view", () -> model = !model);

        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.profile")), x, y + 129, w);
        if (!known) { g.text(font, "- - -", x, y + 139, DigiTheme.MUTED, false); return; }
        String key = species.translationKey() + ".profile";
        String profile = Language.getInstance().has(key) ? I18n.get(key) : I18n.get("gui.digicube.digivice.profile_missing");
        List<String> lines = decoded(font, profile.toUpperCase(Locale.ROOT), w, 4, decode, screen.ticks());
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x, y + 139 + i * 9, withAlpha(DigiTheme.WHITE, 0xD8), false);
    }

    /**
     * {@code text} wrapped to {@code width}, at most {@code most} lines, decoding from noise at four characters a tick:
     * what a record looks like while the device reads it.
     */
    static List<String> decoded(Font font, String text, int width, int most, int decode, int ticks) {
        List<String> lines = new ArrayList<>();
        int shown = decode * 4, at = 0;
        for (FormattedText part : font.splitIgnoringLanguage(FormattedText.of(text), width)) {
            if (lines.size() >= most) break;
            String line = part.getString();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < line.length(); i++, at++) {
                char ch = line.charAt(i);
                out.append(at < shown || ch == ' ' ? ch : NOISE.charAt((int) (DigispaceWorld.hash(at, ticks >> 1) * NOISE.length()) % NOISE.length()));
            }
            lines.add(out.toString());
        }
        return lines;
    }

    private DigimonPreview preview(Identifier species) {
        if (!species.equals(previewed)) { previewed = species; preview = DigimonPreview.create(Minecraft.getInstance(), species); }
        return preview;
    }

    /** Right: attribute and its place in the triangle, base stats, attacks with the mark each leaves, the evolution line. */
    private void scan(GuiGraphicsExtractor g, Font font, AnalyzerIndex.Entry entry, int y) {
        int x = CONTENT_X + 257, w = CONTENT_WIDTH - 257;
        DigimonSpecies species = entry.species();
        if (!entry.known()) {
            // One sentence, once: the LCD already shows that there is no data.
            g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.not_seen")), x, y + 1, DigiTheme.AMBER, true);
            DigiviceKit.rule(g, x, y + 11, w, DigiTheme.AMBER);
            int line = 0;
            for (FormattedText part : font.splitIgnoringLanguage(FormattedText.of(DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.not_seen.digimon"))), w)) {
                g.text(font, part.getString(), x, y + 17 + line++ * 9, DigiTheme.MUTED, false);
            }
            return;
        }
        g.text(font, font.plainSubstrByWidth(name(species), w - 16), x, y + 1, DigiTheme.WHITE, true);
        DigiPanels.readout(g, x + w - 12, y + 2, entry.label(), DigiTheme.MUTED);
        DigiviceKit.rule(g, x, y + 11, w, DigiTheme.CYAN);
        DigimonAttribute attribute = species.attribute();
        int color = DigiviceArt.color(attribute);
        DigiPanels.frame(g, x, y + 15, 22, 22, withAlpha(DigiTheme.VOID, 0xE0), withAlpha(color, 0xA0), 2);
        DigiviceArt.emblem(attribute).draw(g, x + 3, y + 18);
        g.text(font, DigiviceScreen.upper(Component.translatable("digicube.attribute." + attribute.getId())), x + 27, y + 17, color, false);
        DigimonAttribute beats = attribute.strongAgainst(), loses = null;
        for (DigimonAttribute other : DigimonAttribute.values()) if (other.strongAgainst() == attribute) loses = other;
        if (beats != null && loses != null) {
            String edge = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.edge")), risk = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.risk"));
            int ex = x + 27;
            g.text(font, edge, ex, y + 28, DigiTheme.MUTED, false);
            DigiviceArt.mark(g, beats, ex + font.width(edge) + 3, y + 28, DigiviceArt.color(beats));
            int rx = ex + font.width(edge) + 17;
            g.text(font, risk, rx, y + 28, DigiTheme.MUTED, false);
            DigiviceArt.mark(g, loses, rx + font.width(risk) + 3, y + 28, DigiviceArt.color(loses));
        } else g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_triangle")), x + 27, y + 28, DigiTheme.MUTED, false);

        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.base_data")), x, y + 41, w);
        float grow = Math.min(1, (decode + screen.partial()) / 10F);
        DigiviceKit.statBar(g, font, x, y + 51, w, "HP", index.healthFraction(species) * grow, Integer.toString(species.baseHealth()), DigiTheme.TEAL);
        DigiviceKit.statBar(g, font, x, y + 60, w, "ATK", index.attackFraction(species) * grow, Integer.toString(species.baseAttack()), DigiTheme.RED);
        DigiviceKit.statBar(g, font, x, y + 69, w, "DEF", index.defenceFraction(species) * grow, Integer.toString(species.baseDefence()), DigiTheme.DATA_LIGHT);
        DigiviceKit.statBar(g, font, x, y + 78, w, "SPD", index.speedFraction(species) * grow, String.format(Locale.ROOT, "%.2f", species.baseSpeed()), DigiTheme.AMBER);

        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.attacks")), x, y + 88, w);
        List<DigimonAttack> attacks = species.attacks();
        if (attacks.isEmpty()) g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_attacks")), x, y + 99, DigiTheme.MUTED, false);
        for (int i = 0; i < Math.min(3, attacks.size()); i++) {
            int ay = y + 98 + i * 11;
            Identifier id = attacks.get(i).id();
            boolean more = i == 2 && attacks.size() > 3;
            String attack = more ? "+" + (attacks.size() - 2) : DigiviceScreen.upper(Component.translatable("attack." + id.getNamespace() + "." + id.getPath()));
            g.fill(x, ay, x + w, ay + 10, withAlpha(DigiTheme.PANEL, 0xC0));
            g.fill(x, ay, x + 2, ay + 10, color);
            g.fill(x + 6, ay + 4, x + 9, ay + 7, DigiTheme.WHITE); g.fill(x + 7, ay + 3, x + 8, ay + 8, DigiTheme.WHITE); g.fill(x + 5, ay + 5, x + 10, ay + 6, DigiTheme.WHITE);
            int room = w - 16;
            CombatMark leaves = more ? null : CombatMark.of(attacks.get(i));
            if (leaves != null) {
                // The emblem of the mark the move leaves: what the tamer sees over a body, and the way to its entry.
                int tx = x + w - 13;
                boolean seen = knows(leaves), hover = screen.over(tx, ay, 12, 10);
                int tone = seen ? MarkEmblems.color(leaves) : DigiTheme.EDGE_LIGHT;
                DigiPanels.frame(g, tx, ay, 12, 10, withAlpha(tone, hover ? 0x60 : 0x24), withAlpha(tone, hover ? 0xFF : 0xA0), 1);
                if (seen) MarkEmblems.lit(g, leaves, tx + 1, ay, 10);
                else MarkEmblems.silhouette(g, leaves, tx + 1, ay, 10, UNSEEN);
                screen.hit(tx, ay, 12, 10, () -> select(leaves));
                room -= 15;
            }
            g.text(font, font.plainSubstrByWidth(attack, room), x + 14, ay + 1, DigiTheme.WHITE, false);
        }

        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.evolution_line")), x, y + 132, w);
        List<AnalyzerIndex.Step> line = index.line(entry);
        if (line.size() == 1) { g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_route")), x + 28, y + 150, DigiTheme.MUTED, false); }
        int step = line.size() > 3 ? 34 : 37, ex = x, ey = y + 142;
        for (int i = 0; i < line.size(); i++, ex += step) {
            AnalyzerIndex.Step s = line.get(i);
            boolean current = s.entry().id().equals(entry.id()), hover = !current && screen.over(ex, ey, THUMB, THUMB);
            if (i > 0) { int ax = ex - (step - THUMB) / 2 - 3; g.fill(ax, ey + 11, ax + 5, ey + 12, DigiTheme.CYAN); g.fill(ax + 3, ey + 9, ax + 4, ey + 14, DigiTheme.CYAN); g.fill(ax + 4, ey + 10, ax + 5, ey + 13, DigiTheme.CYAN); }
            thumb(g, font, s.entry().id(), s.entry().known(), ex, ey, current ? DigiTheme.AMBER : hover ? DigiTheme.WHITE : DigiTheme.EDGE);
            if (s.level() > 0) { String lv = "L" + s.level(); DigiPanels.readout(g, ex + (THUMB - (lv.length() * 4 - 1)) / 2, ey + THUMB + 2, lv, DigiTheme.AMBER); }
            if (!current) { Identifier target = s.entry().id(); screen.hit(ex, ey, THUMB, THUMB, () -> select(target)); }
        }
    }

    /** A species in a small slot: its sprite, or only its shape while the tamer has not seen it. */
    void thumb(GuiGraphicsExtractor g, Font font, Identifier species, boolean known, int x, int y, int edge) {
        DigiviceKit.slot(g, x, y, THUMB, THUMB, edge);
        if (known) DigiPanels.icon(g, species, x + 1, y + 1, THUMB - 2);
        else if (!DigiviceArt.silhouette(g, species, x + 1, y + 1, THUMB - 2, UNSEEN)) g.text(font, "?", x + 9, y + 8, DigiTheme.EDGE_LIGHT, false);
    }

    // ---------- keys ----------
    boolean keyPressed(int key) {
        if (focus) {
            List<AnalyzerIndex.Entry> list = list();
            if (key == InputConstants.KEY_ESCAPE) { focus = false; query = ""; scroll = 0; return true; }
            if (key == InputConstants.KEY_BACKSPACE) { if (!query.isEmpty()) query = query.substring(0, query.length() - 1); scroll = 0; return true; }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) { if (!list.isEmpty()) choose(list.getFirst().id()); focus = false; return true; }
            // Letters belong to the field while it has the caret, so Q and E do not change tab.
            return key != InputConstants.KEY_UP && key != InputConstants.KEY_DOWN && key != InputConstants.KEY_TAB;
        }
        if (key == InputConstants.KEY_TAB) { show(page == DIGIMON ? MARKS : DIGIMON); return true; }
        if (page == MARKS) return marksPage.keyPressed(key);
        if (key == GLFW.GLFW_KEY_SLASH) { focus = true; return true; }
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
            List<AnalyzerIndex.Entry> list = list();
            if (list.isEmpty()) return true;
            int at = 0;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(selected)) at = i;
            choose(list.get(Math.clamp(at + (key == InputConstants.KEY_DOWN ? 1 : -1), 0, list.size() - 1)).id());
            reveal();
            return true;
        }
        return false;
    }

    boolean charTyped(CharacterEvent event) {
        if (!focus || !event.isAllowedChatCharacter() || query.length() >= MAX_QUERY) return focus;
        String typed = event.codepointAsString();
        if (typed.equals("/") && query.isEmpty()) return true;
        query += typed;
        scroll = 0;
        return true;
    }
}
