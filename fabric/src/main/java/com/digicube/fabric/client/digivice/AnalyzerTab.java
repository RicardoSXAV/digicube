package com.digicube.fabric.client.digivice;

import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonAttribute;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.dev.DevClient;
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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_WIDTH;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_X;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_Y;
import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * The Analyzer: every species in a numbered index on the left, the selected one on an LCD like the toy's own screen in
 * the middle, and what the scan says on the right. A species the tamer does not know yet is a silhouette with no data.
 * In a development environment every species is known, so the whole catalogue can be reviewed.
 */
final class AnalyzerTab {
    private static final int ROWS = 9, ROW = 17, LIST_WIDTH = 126, LCD_WIDTH = 118, LCD_HEIGHT = 104, MAX_QUERY = 24;
    private static final String NOISE = "01#$&*%=";

    private final DigiviceScreen screen;
    private AnalyzerIndex index;
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
        refresh();
        // Open on the first partner: the entry the tamer most likely wants to read.
        screen.snapshot().party().stream().findFirst().ifPresent(member -> selected = member.species());
        if (selected == null || index.get(selected) == null) selected = index.entries().isEmpty() ? null : index.entries().getFirst().id();
    }

    void refresh() {
        Set<Identifier> known = new HashSet<>(screen.snapshot().known());
        if (DevClient.get() != null) DigimonSpeciesRegistry.all().forEach(species -> known.add(species.id()));
        index = new AnalyzerIndex(DigimonSpeciesRegistry.all(), known);
    }

    void select(Identifier species) {
        if (index.get(species) == null) return;
        if (!species.equals(selected)) { selected = species; decode = 0; }
        filter = null;
        query = "";
        reveal();
    }

    private void reveal() {
        List<AnalyzerIndex.Entry> list = list();
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(selected)) {
            if (i < scroll) scroll = i;
            if (i >= scroll + ROWS) scroll = i - ROWS + 1;
        }
    }

    void blur() { focus = false; }
    void tick() { decode++; if (preview != null) preview.tick(); }

    private static String name(DigimonSpecies species) { return Component.translatable(species.translationKey()).getString(); }
    private List<AnalyzerIndex.Entry> list() { return index.filter(filter, query, AnalyzerTab::name); }

    // ---------- drawing ----------
    void draw(GuiGraphicsExtractor g) {
        Font font = screen.font();
        List<AnalyzerIndex.Entry> list = list();
        AnalyzerIndex.Entry entry = selected == null ? null : index.get(selected);
        index(g, font, list);
        if (entry == null) return;
        portrait(g, font, entry);
        scan(g, font, entry);
    }

    /** Left: the search, the attribute filter, the numbered rows. */
    private void index(GuiGraphicsExtractor g, Font font, List<AnalyzerIndex.Entry> list) {
        int x = CONTENT_X, y = CONTENT_Y;
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
            g.text(font, none, x + (LIST_WIDTH - 4 - font.width(none)) / 2, ly + 70, DigiTheme.MUTED, false);
        }
        for (int i = 0; i < ROWS && scroll + i < list.size(); i++) {
            AnalyzerIndex.Entry e = list.get(scroll + i);
            int ry = ly + i * ROW;
            row(g, font, x, ry, LIST_WIDTH - 5, e, e.id().equals(selected), screen.over(x, ry, LIST_WIDTH - 5, ROW));
            screen.hit(x, ry, LIST_WIDTH - 5, ROW, () -> { if (!e.id().equals(selected)) { selected = e.id(); decode = 0; } });
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
            g.text(font, "?", x + 25, y + 5, DigiTheme.EDGE, false);
            g.text(font, "? ? ? ? ?", x + 38, y + 5, withAlpha(DigiTheme.EDGE_LIGHT, 0xC0), false);
            return;
        }
        DigiPanels.icon(g, e.id(), x + 19, y, 16);
        g.text(font, font.plainSubstrByWidth(name(e.species()), w - 54), x + 38, y + 5, chosen ? DigiTheme.AMBER : DigiTheme.WHITE, true);
        DigiviceArt.mark(g, e.species().attribute(), x + w - 12, y + 5, DigiviceArt.color(e.species().attribute()));
    }

    /** Centre: the LCD, the stage ladder, the 3D switch and the profile. */
    private void portrait(GuiGraphicsExtractor g, Font font, AnalyzerIndex.Entry entry) {
        int x = CONTENT_X + 132, y = CONTENT_Y, w = LCD_WIDTH, h = LCD_HEIGHT;
        boolean known = entry.known();
        DigimonSpecies species = entry.species();
        DigiviceKit.lcd(g, x, y, w, h);
        if (known && model && preview(species.id()) != null) {
            int x1 = screen.screenX(x), y1 = screen.screenY(y + 12), x2 = screen.screenX(x + w), y2 = screen.screenY(y + 86);
            float turn = screen.time() * DigiTheme.TURNTABLE_DEGREES_PER_TICK;
            screen.unscaled(g, () -> preview.draw(g, x1, y1, x2, y2, y1 + Math.round((y2 - y1) * 0.92F), turn, 0, 0, screen.partial()));
        } else {
            int bob = (int) Math.round(Math.sin(screen.time() / 9) * 1.5), sx = x + (w - 72) / 2, sy = y + 11 + bob;
            DigiPanels.icon(g, species.id(), sx + 2, sy + 2, 72, known ? 0x47000000 : 0xD9000000);
            if (known) DigiPanels.icon(g, species.id(), sx, sy, 72);
        }
        g.text(font, "No." + entry.label(), x + 4, y + 4, withAlpha(DigiviceArt.INK, 0xD0), false);
        if (known) DigiviceArt.mark(g, species.attribute(), x + w - 11, y + 4, DigiviceArt.INK);
        g.fill(x + 30, y + 86, x + w - 30, y + 87, withAlpha(DigiviceArt.INK, 0x50));
        String title = known ? name(species).toUpperCase(Locale.ROOT) : DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_data"));
        int big = font.width(title) * 2 > w - 8 ? 1 : 2;
        g.pose().pushMatrix();
        g.pose().translate(x + (w - font.width(title) * big) / 2F, y + (big == 2 ? 88 : 92));
        g.pose().scale(big, big);
        g.text(font, title, 0, 0, DigiviceArt.INK, false);
        g.pose().popMatrix();
        int sweep = screen.ticks() % 120;
        if (sweep < 52) g.fill(x, y + sweep * 2, x + w, y + sweep * 2 + 2, withAlpha(DigiviceArt.LCD_LIGHT, 0x70));
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            int bx = x + (a == 1 ? w - 6 : 0), by = y + (b == 1 ? h - 6 : 0);
            g.fill(bx, by + (b == 1 ? 5 : 0), bx + 6, by + (b == 1 ? 6 : 1), withAlpha(DigiviceArt.INK, 0x90));
            g.fill(bx + (a == 1 ? 5 : 0), by, bx + (a == 1 ? 6 : 1), by + 6, withAlpha(DigiviceArt.INK, 0x90));
        }

        int sy = y + 107, tier = species.stage().getTier();
        g.text(font, known ? DigiviceScreen.upper(Component.translatable("digicube.stage." + species.stage().getId())) : "- - -", x, sy + 3, known ? DigiTheme.WHITE : DigiTheme.MUTED, false);
        for (int i = 0; i < 6; i++) {
            int bar = 3 + i * 2;
            g.fill(x + w - 40 + i * 7, sy + 12 - bar, x + w - 35 + i * 7, sy + 12, known && tier == i ? DigiTheme.AMBER : known && i < tier ? withAlpha(DigiTheme.CYAN, 0xB0) : DigiTheme.EDGE_DIM);
        }

        String label = DigiviceScreen.upper(Component.translatable(model ? "gui.digicube.digivice.view_2d" : "gui.digicube.digivice.view_3d"));
        int bx = x, by = y + 123, bw = 72, bh = 16;
        DigiviceKit.keyButton(g, font, bx, by, bw, bh, label, true, true, DigiviceKit.state(known, screen.over(bx, by, bw, bh), screen.pressed("view")));
        if (known) screen.hit(bx, by, bw, bh, "view", () -> model = !model);

        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.profile")), x, y + 143, w);
        String key = species.translationKey() + ".profile";
        String profile = known ? (Language.getInstance().has(key) ? I18n.get(key) : I18n.get("gui.digicube.digivice.profile_missing")) : I18n.get("gui.digicube.digivice.profile_unknown");
        int shown = known ? decode * 4 : Integer.MAX_VALUE, at = 0, line = 0;
        for (FormattedText part : font.splitIgnoringLanguage(FormattedText.of(profile.toUpperCase(Locale.ROOT)), w)) {
            if (line >= 4) break;
            String text = part.getString();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length(); i++, at++) {
                char ch = text.charAt(i);
                out.append(at < shown || ch == ' ' ? ch : NOISE.charAt((int) (DigispaceWorld.hash(at, screen.ticks() >> 1) * NOISE.length()) % NOISE.length()));
            }
            g.text(font, out.toString(), x, y + 153 + line++ * 9, known ? withAlpha(DigiTheme.WHITE, 0xD8) : DigiTheme.MUTED, false);
        }
    }

    private DigimonPreview preview(Identifier species) {
        if (!species.equals(previewed)) { previewed = species; preview = DigimonPreview.create(Minecraft.getInstance(), species); }
        return preview;
    }

    /** Right: attribute and its place in the triangle, base stats, attacks, the evolution line. */
    private void scan(GuiGraphicsExtractor g, Font font, AnalyzerIndex.Entry entry) {
        int x = CONTENT_X + 257, y = CONTENT_Y, w = CONTENT_WIDTH - 257;
        boolean known = entry.known();
        DigimonSpecies species = entry.species();
        g.text(font, known ? name(species) : "?????", x, y + 1, DigiTheme.WHITE, true);
        DigiPanels.readout(g, x + w - 12, y + 2, entry.label(), DigiTheme.MUTED);
        g.fill(x, y + 11, x + w, y + 12, DigiTheme.EDGE_DIM);
        for (int i = 0; i < 40; i += 4) g.fill(x + i, y + 11, x + i + 4, y + 12, withAlpha(DigiTheme.CYAN, (int) (0xE0 * (1 - i / 40F))));
        String[] stats = {"HP", "ATK", "DEF", "SPD"};
        if (!known) {
            g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.weak_signal")), x, y + 20, DigiTheme.MUTED, false);
            for (int i = 0; i < 4; i++) DigiviceKit.statBar(g, font, x, y + 54 + i * 10, w, stats[i], 0, "--", DigiTheme.EDGE);
            return;
        }
        DigimonAttribute attribute = species.attribute();
        int color = DigiviceArt.color(attribute);
        DigiPanels.frame(g, x, y + 16, 22, 22, withAlpha(DigiTheme.VOID, 0xE0), withAlpha(color, 0xA0), 2);
        DigiviceArt.emblem(attribute).draw(g, x + 3, y + 19);
        g.text(font, DigiviceScreen.upper(Component.translatable("digicube.attribute." + attribute.getId())), x + 27, y + 18, color, false);
        DigimonAttribute beats = attribute.strongAgainst(), loses = null;
        for (DigimonAttribute other : DigimonAttribute.values()) if (other.strongAgainst() == attribute) loses = other;
        if (beats != null && loses != null) {
            String edge = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.edge")), risk = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.risk"));
            int ex = x + 27;
            g.text(font, edge, ex, y + 29, DigiTheme.MUTED, false);
            DigiviceArt.mark(g, beats, ex + font.width(edge) + 3, y + 29, DigiviceArt.color(beats));
            int rx = ex + font.width(edge) + 17;
            g.text(font, risk, rx, y + 29, DigiTheme.MUTED, false);
            DigiviceArt.mark(g, loses, rx + font.width(risk) + 3, y + 29, DigiviceArt.color(loses));
        } else g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_triangle")), x + 27, y + 29, DigiTheme.MUTED, false);

        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.base_data")), x, y + 44, w);
        float grow = Math.min(1, (decode + screen.partial()) / 10F);
        DigiviceKit.statBar(g, font, x, y + 54, w, stats[0], index.healthFraction(species) * grow, Integer.toString(species.baseHealth()), DigiTheme.TEAL);
        DigiviceKit.statBar(g, font, x, y + 64, w, stats[1], index.attackFraction(species) * grow, Integer.toString(species.baseAttack()), DigiTheme.RED);
        DigiviceKit.statBar(g, font, x, y + 74, w, stats[2], index.defenceFraction(species) * grow, Integer.toString(species.baseDefence()), DigiTheme.DATA_LIGHT);
        DigiviceKit.statBar(g, font, x, y + 84, w, stats[3], index.speedFraction(species) * grow, String.format(Locale.ROOT, "%.2f", species.baseSpeed()), DigiTheme.AMBER);

        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.attacks")), x, y + 98, w);
        List<DigimonAttack> attacks = species.attacks();
        if (attacks.isEmpty()) g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_attacks")), x, y + 109, DigiTheme.MUTED, false);
        for (int i = 0; i < Math.min(3, attacks.size()); i++) {
            int ay = y + 108 + i * 11;
            Identifier id = attacks.get(i).id();
            String attack = DigiviceScreen.upper(Component.translatable("attack." + id.getNamespace() + "." + id.getPath()));
            if (i == 2 && attacks.size() > 3) attack = "+" + (attacks.size() - 2);
            g.fill(x, ay, x + w, ay + 10, withAlpha(DigiTheme.PANEL, 0xC0));
            g.fill(x, ay, x + 2, ay + 10, color);
            g.fill(x + 6, ay + 4, x + 9, ay + 7, DigiTheme.WHITE); g.fill(x + 7, ay + 3, x + 8, ay + 8, DigiTheme.WHITE); g.fill(x + 5, ay + 5, x + 10, ay + 6, DigiTheme.WHITE);
            g.text(font, font.plainSubstrByWidth(attack, w - 16), x + 14, ay + 1, DigiTheme.WHITE, false);
        }

        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.evolution_line")), x, y + 145, w);
        List<AnalyzerIndex.Step> line = index.line(entry);
        if (line.size() == 1) { g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_route")), x + 30, y + 164, DigiTheme.MUTED, false); }
        int step = line.size() > 3 ? 34 : 38, ex = x, ey = y + 155;
        for (int i = 0; i < line.size(); i++, ex += step) {
            AnalyzerIndex.Step s = line.get(i);
            boolean current = s.entry().id().equals(entry.id()), hover = !current && screen.over(ex, ey, 26, 26);
            if (i > 0) { int ax = ex - (step - 26) / 2 - 3; g.fill(ax, ey + 12, ax + 5, ey + 13, DigiTheme.CYAN); g.fill(ax + 3, ey + 10, ax + 4, ey + 15, DigiTheme.CYAN); g.fill(ax + 4, ey + 11, ax + 5, ey + 14, DigiTheme.CYAN); }
            g.fill(ex, ey, ex + 26, ey + 26, withAlpha(DigiTheme.VOID, 0xE0));
            DigiPanels.grid(g, ex + 1, ey + 1, 24, 24, 8, withAlpha(DigiTheme.GRID, 0x30));
            DigiPanels.frame(g, ex, ey, 26, 26, 0, current ? DigiTheme.AMBER : hover ? DigiTheme.WHITE : DigiTheme.EDGE, 1);
            if (s.entry().known()) DigiPanels.icon(g, s.entry().id(), ex + 1, ey + 1, 24);
            else g.text(font, "?", ex + 10, ey + 9, DigiTheme.EDGE_LIGHT, false);
            if (s.level() > 0) { String lv = "L" + s.level(); DigiPanels.readout(g, ex + (26 - (lv.length() * 4 - 1)) / 2, ey + 28, lv, DigiTheme.AMBER); }
            if (!current) { Identifier target = s.entry().id(); screen.hit(ex, ey, 26, 26, () -> select(target)); }
        }
    }

    // ---------- keys ----------
    boolean keyPressed(int key) {
        List<AnalyzerIndex.Entry> list = list();
        if (focus) {
            if (key == InputConstants.KEY_ESCAPE) { focus = false; query = ""; scroll = 0; return true; }
            if (key == InputConstants.KEY_BACKSPACE) { if (!query.isEmpty()) query = query.substring(0, query.length() - 1); scroll = 0; return true; }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) { if (!list.isEmpty()) { selected = list.getFirst().id(); decode = 0; } focus = false; return true; }
            // Letters belong to the field while it has the caret, so Q and E do not change tab.
            return key != InputConstants.KEY_UP && key != InputConstants.KEY_DOWN && key != InputConstants.KEY_TAB;
        }
        if (key == GLFW.GLFW_KEY_SLASH) { focus = true; return true; }
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
            if (list.isEmpty()) return true;
            int at = 0;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(selected)) at = i;
            AnalyzerIndex.Entry next = list.get(Math.clamp(at + (key == InputConstants.KEY_DOWN ? 1 : -1), 0, list.size() - 1));
            if (!next.id().equals(selected)) { selected = next.id(); decode = 0; }
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
