package com.digicube.fabric.client.digivice;

import com.digicube.digimon.CombatMark;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiTheme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_WIDTH;
import static com.digicube.fabric.client.digivice.DigiviceScreen.CONTENT_X;
import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * The Analyzer's guide to the combat marks: the marks in a list, and for the selected one its emblem living through
 * a run on a stage, one sentence on what it does, how it ends, and the Digimon that leave it. A mark the tamer has not
 * witnessed is a shape that says so once; the Digimon behind it stay hidden with it.
 */
final class MarksPage {
    private static final int ROW = 22, LIST_WIDTH = 126, STAGE_WIDTH = 80, STAGE_HEIGHT = 92, EMBLEM = 64, THUMB = 24;
    /** Appliers beside each other once they no longer fit under each other, and how many slots there are then. */
    private static final int COLUMNS = 2, SLOTS = 4;

    private final DigiviceScreen screen;
    private final AnalyzerTab tab;
    private final MarkGuide guide = new MarkGuide(DigimonSpeciesRegistry.all());
    private CombatMark selected = CombatMark.values()[0];
    /** Ticks since the selection changed: the sentence decodes and the emblem starts its run. */
    private int decode;

    MarksPage(DigiviceScreen screen, AnalyzerTab tab) {
        this.screen = screen;
        this.tab = tab;
    }

    void tick() { decode++; }

    void select(CombatMark mark) {
        if (mark != selected) { selected = mark; decode = 0; }
        tab.opened(mark);
    }

    void draw(GuiGraphicsExtractor g, Font font, int y) {
        list(g, font, y);
        MarkGuide.Entry entry = guide.get(selected);
        boolean known = tab.knows(selected);
        int x = CONTENT_X + 132, w = CONTENT_WIDTH - 132, tone = known ? MarkEmblems.color(selected) : DigiTheme.EDGE;
        stage(g, font, entry, known, x, y, tone);

        int tx = x + STAGE_WIDTH + 8, tw = w - STAGE_WIDTH - 8;
        String title = known ? Component.translatable(selected.translationKey()).getString() : DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.not_seen"));
        g.text(font, title, tx, y + 1, known ? DigiTheme.WHITE : DigiTheme.AMBER, true);
        DigiviceKit.rule(g, tx, y + 11, tw, known ? tone : DigiTheme.AMBER);
        if (!known) {
            int line = 0;
            for (FormattedText part : font.splitIgnoringLanguage(FormattedText.of(DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.not_seen.mark"))), tw)) {
                g.text(font, part.getString(), tx, y + 17 + line++ * 9, DigiTheme.MUTED, false);
            }
            return;
        }
        Object[] numbers = MarkGuide.numbers(entry, (from, to) -> I18n.get("gui.digicube.marks.span", from, to));
        String sentence = I18n.get(selected.translationKey() + ".guide", numbers).toUpperCase(Locale.ROOT);
        List<String> lines = AnalyzerTab.decoded(font, sentence, tw, 5, decode, screen.ticks());
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), tx, y + 17 + i * 9, withAlpha(DigiTheme.WHITE, 0xE0), false);
        int ey = y + 17 + lines.size() * 9 + 5;
        String ends = DigiviceScreen.upper(Component.translatable("gui.digicube.marks.ends"));
        g.text(font, ends, tx, ey, DigiTheme.CYAN, false);
        String when = DigiviceScreen.upper(Component.translatable(selected.translationKey() + ".ends"));
        // Beside its label where it fits, under it where it does not.
        if (font.width(ends) + 6 + font.width(when) <= tw) g.text(font, when, tx + font.width(ends) + 6, ey, DigiTheme.WHITE, false);
        else g.text(font, font.plainSubstrByWidth(when, tw), tx, ey + 9, DigiTheme.WHITE, false);

        appliers(g, font, entry, x, y + 101, w);
    }

    /** Left: the marks, each with its emblem, its name and what it does in two or three words. */
    private void list(GuiGraphicsExtractor g, Font font, int y) {
        int x = CONTENT_X, w = LIST_WIDTH - 5;
        CombatMark[] marks = CombatMark.values();
        g.fill(x, y, x + LIST_WIDTH - 4, y + marks.length * ROW, withAlpha(DigiTheme.PANEL, 0x90));
        for (int i = 0; i < marks.length; i++) {
            CombatMark mark = marks[i];
            int ry = y + i * ROW;
            boolean chosen = mark == selected, known = tab.knows(mark);
            if (chosen) {
                g.fill(x, ry, x + w, ry + ROW - 1, withAlpha(DigiviceArt.KEY, 0x50));
                g.fill(x, ry, x + 2, ry + ROW - 1, DigiTheme.AMBER);
                g.fill(x + w - 1, ry, x + w, ry + ROW - 1, withAlpha(DigiTheme.AMBER, 0x80));
            } else if (screen.over(x, ry, w, ROW)) g.fill(x, ry, x + w, ry + ROW - 1, withAlpha(DigiTheme.PANEL_RAISED, 0xE0));
            g.fill(x, ry + ROW - 1, x + w, ry + ROW, withAlpha(DigiTheme.EDGE_DIM, 0x80));
            screen.hit(x, ry, w, ROW, () -> select(mark));
            if (!known) {
                MarkEmblems.silhouette(g, mark, x + 5, ry + 2, 16, AnalyzerTab.UNSEEN);
                g.text(font, "? ? ? ?", x + 26, ry + 7, withAlpha(DigiTheme.EDGE_LIGHT, 0xC0), false);
                continue;
            }
            MarkEmblems.lit(g, mark, x + 5, ry + 2, 16);
            g.text(font, DigiviceScreen.upper(Component.translatable(mark.translationKey())), x + 26, ry + 2, chosen ? DigiTheme.AMBER : DigiTheme.WHITE, false);
            g.text(font, font.plainSubstrByWidth(DigiviceScreen.upper(Component.translatable(mark.translationKey() + ".effect")), w - 30), x + 26, ry + 12, withAlpha(DigiTheme.MUTED, 0xE0), false);
            if (tab.fresh(mark)) {
                String tag = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.new"));
                DigiviceKit.newTag(g, font, tag, x + w - 3 - DigiviceKit.newTagWidth(font, tag), ry + 1);
            }
        }
        DigiviceKit.scrollbar(g, x + LIST_WIDTH - 3, y, marks.length * ROW, marks.length, marks.length, 0);
    }

    /** The emblem as it sits over a body, through one run and again, with what is going on under it. */
    private void stage(GuiGraphicsExtractor g, Font font, MarkGuide.Entry entry, boolean known, int x, int y, int tone) {
        DigiviceKit.slot(g, x, y, STAGE_WIDTH, STAGE_HEIGHT, known ? withAlpha(tone, 0xC0) : tone);
        int ex = x + (STAGE_WIDTH - EMBLEM) / 2, ey = y + 6;
        if (!known) { MarkEmblems.silhouette(g, selected, ex, ey, EMBLEM, AnalyzerTab.UNSEEN); return; }
        MarkLife.Frame frame = MarkLife.at(entry, decode);
        MarkEmblems.draw(g, selected, frame, ex, ey, EMBLEM);
        String caption = font.plainSubstrByWidth(I18n.get("gui.digicube.marks.life." + frame.caption(), frame.args()).toUpperCase(Locale.ROOT), STAGE_WIDTH - 6);
        g.text(font, caption, x + (STAGE_WIDTH - font.width(caption)) / 2, y + 73, frame.draw() == MarkLife.Draw.NONE ? DigiTheme.MUTED : DigiTheme.WHITE, false);
        g.fill(ex, y + 84, ex + EMBLEM, y + 87, withAlpha(DigiTheme.EDGE_DIM, 0xC0));
        if (frame.bar() > 0) g.fill(ex, y + 84, ex + Math.round(EMBLEM * frame.bar()), y + 87, tone);
    }

    /** Under the stage: who leaves the mark and with which moves; a thumb opens the Digimon. */
    private void appliers(GuiGraphicsExtractor g, Font font, MarkGuide.Entry entry, int x, int y, int w) {
        DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.marks.applied_by")), x, y, w);
        List<MarkGuide.Applier> appliers = entry.appliers();
        if (appliers.isEmpty()) { g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.no_attacks")), x, y + 11, DigiTheme.MUTED, false); return; }
        boolean wide = appliers.size() <= SLOTS / COLUMNS;
        int slot = wide ? w : (w - 6) / COLUMNS, shown = Math.min(appliers.size(), wide ? appliers.size() : SLOTS);
        for (int i = 0; i < shown; i++) {
            MarkGuide.Applier applier = appliers.get(i);
            int ax = wide ? x : x + i % COLUMNS * (slot + 6), ay = y + 10 + (wide ? i : i / COLUMNS) * (THUMB + 4), room = slot - THUMB - 5;
            Identifier species = applier.species().id();
            boolean known = tab.knows(species), hover = screen.over(ax, ay, THUMB, THUMB);
            tab.thumb(g, font, species, known, ax, ay, hover ? DigiTheme.WHITE : DigiTheme.EDGE);
            screen.hit(ax, ay, THUMB, THUMB, () -> tab.select(species));
            if (!known) { g.text(font, "? ? ? ? ?", ax + THUMB + 5, ay + 8, withAlpha(DigiTheme.EDGE_LIGHT, 0xC0), false); continue; }
            // The last slot stands for everyone it has no room for.
            boolean rest = i == shown - 1 && appliers.size() > shown;
            String name = rest ? "+" + (appliers.size() - shown + 1) : AnalyzerTab.name(applier.species());
            g.text(font, font.plainSubstrByWidth(name, room), ax + THUMB + 5, ay + 3, hover ? DigiTheme.AMBER : DigiTheme.WHITE, true);
            if (rest) continue;
            String moves = applier.attacks().stream().map(MarksPage::moveName).collect(Collectors.joining(" · "));
            g.text(font, font.plainSubstrByWidth(moves, room), ax + THUMB + 5, ay + 13, withAlpha(DigiTheme.MUTED, 0xE0), false);
        }
    }

    private static String moveName(DigimonAttack attack) {
        Identifier id = attack.id();
        return Component.translatable("attack." + id.getNamespace() + "." + id.getPath()).getString().toUpperCase(Locale.ROOT);
    }

    boolean keyPressed(int key) {
        if (key != InputConstants.KEY_UP && key != InputConstants.KEY_DOWN) return false;
        CombatMark[] marks = CombatMark.values();
        select(marks[Math.clamp(selected.ordinal() + (key == InputConstants.KEY_DOWN ? 1 : -1), 0, marks.length - 1)]);
        return true;
    }
}
