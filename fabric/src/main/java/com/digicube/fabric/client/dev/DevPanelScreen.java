package com.digicube.fabric.client.dev;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The developer panel: centred, translucent, one job (calibrating mechanics). It draws what
 * {@link DevTabs} declares: a tab row, the tab's sections as folding cards that scroll, an
 * index of them on the left, and in the title bar a search over every tab, section and config.
 * Each card ends in its own action bar, so a button never outlives its context.
 *
 * <p>Controls are immediate: every frame registers the rectangles it drew, and a click or a
 * scroll goes to the topmost one under the cursor. Opened from {@link DevGear}; Esc closes.
 */
public final class DevPanelScreen extends Screen implements DevCanvas {
    private static final int VEIL_ALPHA = 0x80, TAB_GAP = 2, TAB_PAD = 16, ARROWS = 30;
    private static final int PICKER_WIDTH = 150, PICKER_ROWS = 6, LIST_ROW = 14, RESULT_ROW = 15;
    private static final int FLASH_TICKS = 40, SEARCH_LIMIT = 24, PICKER_LIMIT = 16;
    private static final int NO_CLIP = Integer.MIN_VALUE;

    private record Hit(int x, int y, int w, int h, Runnable click, IntConsumer wheel, int clipTop, int clipBottom) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h && (clipTop == NO_CLIP || my >= clipTop && my < clipBottom);
        }
    }

    /** One line of the Digimon list. */
    private record Choice(Identifier id, String name, String stage, String haystack, DigimonSpecies species) {}

    private static final class Picker {
        Object owner;
        int x, y, anchorHeight, scroll;
        Identifier current;
        Consumer<Identifier> pick;
        String query = "";
    }

    private final DevClient client;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Choice> choices = new ArrayList<>();
    private int ticks, mouseX, mouseY;
    /** While set, {@link #over} answers false below it: an open list covers what is under it. */
    private boolean covered;
    private int clipTop = NO_CLIP, clipBottom;

    private boolean searching = true;
    private String search = "";
    private int searchSelected;
    private Picker picker;
    private DevTabs.Row dragging;
    private int dragX, dragWidth;
    private DevTabs.Row flashRow;
    private int flashStart;

    DevPanelScreen(DevClient client) {
        super(Component.literal("Dev Panel"));
        this.client = client;
    }

    @Override protected void init() {
        choices.clear();
        for (DigimonSpecies species : DigimonSpeciesRegistry.all()) {
            if (!species.canFight()) continue;
            String name = Component.translatable(species.translationKey()).getString();
            String stage = Component.translatable("digicube.stage." + species.stage().name().toLowerCase(Locale.ROOT)).getString().toUpperCase(Locale.ROOT);
            String haystack = (name + " " + stage + " " + species.stage().name() + " " + species.attribute().name()).toLowerCase(Locale.ROOT);
            choices.add(new Choice(species.id(), name, stage, haystack, species));
        }
        choices.sort(Comparator.comparing(Choice::name));
    }

    @Override public boolean isPauseScreen() { return false; }

    /** The panel draws its own veil: no blur, the world stays readable behind it. */
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void tick() {
        ticks++;
        if (minecraft.player == null) onClose();
    }

    // --- DevCanvas ---------------------------------------------------------------------------

    private GuiGraphicsExtractor graphics;

    @Override public GuiGraphicsExtractor graphics() { return graphics; }
    @Override public Font font() { return font; }

    @Override public boolean over(int x, int y, int w, int h) {
        if (covered) return false;
        if (clipTop != NO_CLIP && (mouseY < clipTop || mouseY >= clipBottom)) return false;
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override public void click(int x, int y, int w, int h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action, null, clipTop, clipBottom));
    }

    @Override public void wheel(int x, int y, int w, int h, IntConsumer steps) {
        hits.add(new Hit(x, y, w, h, null, steps, clipTop, clipBottom));
    }

    @Override public void pickSpecies(Object owner, int x, int y, int anchorHeight, Identifier current, Consumer<Identifier> pick) {
        picker = new Picker();
        picker.owner = owner;
        picker.x = x;
        picker.y = y;
        picker.anchorHeight = anchorHeight;
        picker.current = current;
        picker.pick = pick;
        searching = false;
    }

    @Override public boolean picking(Object owner) { return picker != null && picker.owner.equals(owner); }

    // --- drawing -----------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        graphics = g;
        mouseX = mx;
        mouseY = my;
        hits.clear();
        List<DevSearch.Entry> results = searching && !search.isEmpty() ? DevSearch.query(client.index, search) : List.of();
        boolean overlay = picker != null || (searching && !search.isEmpty());
        covered = overlay;

        int pw = DevLayout.panelWidth(width), ph = DevLayout.panelHeight(height), px = (width - pw) / 2, py = (height - ph) / 2;
        rect(g, 0, 0, width, height, DigiTheme.withAlpha(DigiTheme.VOID, VEIL_ALPHA));
        DigiPanels.frame(g, px - 1, py - 1, pw + 2, ph + 2, 0, DigiTheme.withAlpha(DigiTheme.VOID, 0xC0), 4);
        DigiPanels.frame(g, px, py, pw, ph, DigiTheme.withAlpha(DigiTheme.PANEL, 0xCC), 0, 3);
        rect(g, px + 1, py + 3, pw - 2, 14, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, 0x90));
        DigiPanels.bevel(g, px, py, pw, ph, 3, DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);

        // title bar: name, the config search, how to leave
        g.text(font, "DEV PANEL", px + 10, py + 6, DigiTheme.CYAN, true);
        int closeX = px + pw - 10 - font.width("CLOSE"), keyX = closeX - 6 - (font.width("ESC") + 6);
        keycap(g, keyX, py + 4, "ESC");
        g.text(font, "CLOSE", closeX, py + 6, DigiTheme.MUTED, true);
        int qx = px + 16 + font.width("DEV PANEL"), qw = keyX - 10 - qx;
        field(g, qx, py + 3, qw, 13, search, "SEARCH EVERY CONFIG", searching);
        click(qx, py + 3, qw, 13, () -> { searching = true; picker = null; });

        tabs(g, px, py, pw);
        body(g, px, py, pw, ph);

        covered = false;
        if (picker != null) picker(g, px, py, pw, ph);
        else if (searching && !search.isEmpty()) results(g, results, qx, py + 17, Math.max(qw, 236));
    }

    private void tabs(GuiGraphicsExtractor g, int px, int py, int pw) {
        List<DevTabs.Tab> tabs = client.tabs.tabs();
        int[] widths = tabWidths();
        int ty = py + 20, available = pw - 20 - ARROWS;
        rect(g, px + 1, ty + 14, pw - 2, 1, DigiTheme.EDGE_DIM);
        client.firstTab = Math.max(0, Math.min(client.firstTab, tabs.size() - 1));
        int shown = DevLayout.tabsShown(widths, TAB_GAP, client.firstTab, available), tx = px + 10;
        for (int i = client.firstTab; i < client.firstTab + shown; i++) {
            DevTabs.Tab tab = tabs.get(i);
            int w = widths[i], index = i;
            boolean active = i == client.tab, hover = !active && over(tx, ty, w, 14);
            if (active) {
                rect(g, tx, ty, w, 15, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, 0xF0));
                rect(g, tx, ty, w, 2, DigiTheme.CYAN);
                rect(g, tx, ty, 1, 15, DigiTheme.EDGE_DIM);
                rect(g, tx + w - 1, ty, 1, 15, DigiTheme.EDGE_DIM);
            }
            g.text(font, tab.name(), tx + TAB_PAD / 2, ty + 4, active || hover ? DigiTheme.WHITE : DigiTheme.MUTED, true);
            if (tab.changed()) rect(g, tx + w - 6, ty + 3, 2, 2, DigiTheme.AMBER);
            click(tx, ty, w, 14, () -> showTab(index));
            tx += w + TAB_GAP;
        }
        boolean less = client.firstTab > 0, more = client.firstTab + shown < tabs.size();
        if (!less && !more) return;
        arrow(g, px + pw - 10 - 26, ty + 1, "<", less, -1);
        arrow(g, px + pw - 10 - 12, ty + 1, ">", more, 1);
    }

    private void arrow(GuiGraphicsExtractor g, int x, int y, String glyph, boolean enabled, int step) {
        boolean hover = enabled && over(x, y, 12, 12);
        rect(g, x, y, 12, 12, DigiTheme.withAlpha(hover ? DigiTheme.PANEL_RAISED : DigiTheme.PANEL, 0xF0));
        g.outline(x, y, 12, 12, !enabled ? DigiTheme.EDGE_DIM : hover ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT);
        g.text(font, glyph, x + 4, y + 2, enabled ? DigiTheme.WHITE : DigiTheme.withAlpha(DigiTheme.MUTED, 0x70), false);
        if (enabled) click(x, y, 12, 12, () -> client.firstTab += step);
    }

    /** The section index on the left, the section cards on the right. */
    private void body(GuiGraphicsExtractor g, int px, int py, int pw, int ph) {
        DevTabs.Tab tab = tab();
        int top = py + DevLayout.BODY_TOP, viewHeight = ph - DevLayout.BODY_TOP - DevLayout.BODY_BOTTOM;
        int cx = px + DevLayout.RAIL_WIDTH + 16, cw = pw - DevLayout.RAIL_WIDTH - 16 - 14;
        int total = DevLayout.contentHeight(tab, client.collapsed::contains);
        int scroll = DevLayout.clampScroll(client.scroll.getOrDefault(tab, 0), total, viewHeight);
        client.scroll.put(tab, scroll);

        g.text(font, "SECTIONS", px + 10, top, DigiTheme.withAlpha(DigiTheme.MUTED, 0xB0), false);
        rect(g, px + DevLayout.RAIL_WIDTH + 10, top, 1, viewHeight, DigiTheme.EDGE_DIM);
        int current = DevLayout.currentSection(tab, client.collapsed::contains, scroll);
        for (int i = 0; i < tab.sections().size(); i++) {
            DevTabs.Section section = tab.sections().get(i);
            int ry = top + 11 + i * 13, index = i;
            if (ry + 13 > top + viewHeight) break;
            boolean hover = over(px + 6, ry, DevLayout.RAIL_WIDTH, 13), on = i == current;
            if (hover) rect(g, px + 6, ry, DevLayout.RAIL_WIDTH, 13, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, 0xC0));
            if (on) rect(g, px + 6, ry + 2, 2, 9, DigiTheme.CYAN);
            g.text(font, fit(section.name(), DevLayout.RAIL_WIDTH - 14), px + 12, ry + 3, on || hover ? DigiTheme.WHITE : DigiTheme.MUTED, true);
            if (section.changed() > 0) rect(g, px + DevLayout.RAIL_WIDTH, ry + 5, 3, 3, DigiTheme.AMBER);
            click(px + 6, ry, DevLayout.RAIL_WIDTH, 13, () -> reveal(tab, index));
        }

        wheel(cx, top, cw + 8, viewHeight, steps -> client.scroll.merge(tab, -steps * DevLayout.SCROLL_STEP, Integer::sum));
        g.enableScissor(cx - 2, top, cx + cw + 2, top + viewHeight);
        clipTop = top;
        clipBottom = top + viewHeight;
        for (int i = 0; i < tab.sections().size(); i++) {
            DevTabs.Section section = tab.sections().get(i);
            int y = top + DevLayout.sectionTop(tab, client.collapsed::contains, i) - scroll;
            int h = DevLayout.sectionHeight(section, client.collapsed.contains(section));
            if (y < top + viewHeight && y + h > top) section(g, section, cx, y, cw, h);
        }
        clipTop = NO_CLIP;
        g.disableScissor();

        if (total > viewHeight) {
            int thumb = Math.max(12, viewHeight * viewHeight / total), ty = top + (viewHeight - thumb) * scroll / (total - viewHeight);
            rect(g, px + pw - 9, top, 2, viewHeight, DigiTheme.withAlpha(DigiTheme.EDGE_DIM, 0xC0));
            rect(g, px + pw - 9, ty, 2, thumb, DigiTheme.EDGE_LIGHT);
        }
    }

    /** One card: folding header with a summary, the rows or the custom body, then the actions of this section only. */
    private void section(GuiGraphicsExtractor g, DevTabs.Section section, int x, int y, int w, int h) {
        boolean shut = client.collapsed.contains(section), hover = over(x, y, w, DevLayout.HEADER);
        int changed = section.changed();
        DigiPanels.frame(g, x, y, w, h, DigiTheme.withAlpha(DigiTheme.VOID, 0x70), DigiTheme.EDGE_DIM, 2);
        rect(g, x + 1, y + 1, w - 2, 13, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, hover ? 0xF0 : 0xB0));
        rect(g, x + 1, y + 1, 2, 13, DigiTheme.CYAN);
        chevron(g, x + 7, y + 5, !shut, hover ? DigiTheme.AMBER : DigiTheme.CYAN);
        g.text(font, section.name(), x + 16, y + 4, DigiTheme.CYAN, true);
        int after = x + 16 + font.width(section.name()) + 6;
        if (section.isExample()) {
            int ew = font.width("EXAMPLE") + 6;
            g.outline(after, y + 2, ew, 11, DigiTheme.withAlpha(DigiTheme.VIRUS, 0xC0));
            g.text(font, "EXAMPLE", after + 3, y + 4, DigiTheme.VIRUS, false);
            after += ew + 6;
        }
        String summary = fit(section.summary(), x + w - 6 - after);
        g.text(font, summary, x + w - 6 - font.width(summary), y + 4, changed > 0 ? DigiTheme.AMBER : DigiTheme.withAlpha(DigiTheme.MUTED, 0xB0), false);
        click(x, y, w, DevLayout.HEADER, () -> { if (!client.collapsed.remove(section)) client.collapsed.add(section); });
        if (shut) return;

        int by = y + DevLayout.HEADER + DevLayout.SECTION_PAD;
        if (section.customBody() != null) {
            section.customBody().draw(this, x, by, w);
            by += section.customBody().height() + 2;
        } else {
            for (DevTabs.Row row : section.rows()) {
                row(g, section, row, x, by, w);
                by += DevLayout.ROW;
            }
        }
        if (section.actions().isEmpty()) return;

        rect(g, x + 1, by + 2, w - 2, 1, DigiTheme.EDGE_DIM);
        rect(g, x + 1, by + 3, w - 2, 20, DigiTheme.withAlpha(DigiTheme.VOID, 0x60));
        int bx = x + w - 6;
        for (int i = section.actions().size() - 1; i >= 0; i--) {
            DevTabs.Action action = section.actions().get(i);
            int bw = font.width(action.label()) + 16;
            bx -= bw;
            boolean enabled = action.enabled().getAsBoolean();
            button(g, bx, by + 5, bw, 16, action.label(), action.primary(), enabled);
            if (enabled) click(bx, by + 5, bw, 16, () -> section.status(action.run().get()));
            bx -= 5;
        }
        boolean attention = changed > 0 || (section.status().isEmpty() && section.customBody() != null && !section.actions().getLast().enabled().getAsBoolean());
        g.text(font, fit(section.line(), bx - x - 10), x + 8, by + 9, attention ? DigiTheme.AMBER : DigiTheme.MUTED, true);
    }

    private void row(GuiGraphicsExtractor g, DevTabs.Section section, DevTabs.Row row, int x, int y, int w) {
        boolean changed = row.changed();
        int vx = x + w - 92;
        float flash = row == flashRow ? Math.max(0, 1 - (ticks - flashStart) / (float) FLASH_TICKS) : 0;
        if (flash > 0) rect(g, x + 1, y, w - 2, DevLayout.ROW, DigiTheme.withAlpha(DigiTheme.AMBER, Math.round(0x50 * flash)));
        else if (over(x, y, w, DevLayout.ROW)) rect(g, x + 1, y, w - 2, DevLayout.ROW, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, 0x70));
        if (changed) rect(g, x + 5, y + 6, 3, 3, DigiTheme.AMBER);
        int labelWidth = (row.kind() == DevTabs.Kind.NUMBER ? vx - 64 : vx) - (x + 12) - 6;
        g.text(font, fit(row.label(), labelWidth), x + 12, y + 4, changed ? DigiTheme.AMBER : DigiTheme.WHITE, true);

        switch (row.kind()) {
            case NUMBER -> {
                int rx = vx - 64, rw = 56;
                int at = (int) Math.round(rw * DevLayout.fraction(row, row.value().get())), mark = (int) Math.round(rw * DevLayout.fraction(row, row.value().defaultValue()));
                rect(g, rx, y + 7, rw, 2, DigiTheme.EDGE_DIM);
                rect(g, rx, y + 7, at, 2, changed ? DigiTheme.AMBER : DigiTheme.CYAN);
                rect(g, rx + mark, y + 4, 1, 8, DigiTheme.withAlpha(DigiTheme.MUTED, 0xC0));
                rect(g, rx + at - 2, y + 3, 4, 10, DigiTheme.SHADOW);
                rect(g, rx + at - 2, y + 4, 3, 8, changed ? DigiTheme.AMBER : DigiTheme.WHITE);
                click(rx - 3, y + 1, rw + 6, 14, () -> {
                    dragging = row;
                    dragX = rx;
                    dragWidth = rw;
                    set(section, row, DevLayout.fromSlider(row, (mouseX - rx) / (double) rw));
                });
                rect(g, vx, y + 2, 50, 12, DigiTheme.withAlpha(DigiTheme.VOID, 0xE6));
                g.outline(vx, y + 2, 50, 12, DigiTheme.EDGE_DIM);
                String value = DevLayout.format(row);
                g.text(font, value, vx + 47 - font.width(value), y + 4, DigiTheme.WHITE, false);
                wheel(rx - 3, y, vx + 50 - rx + 3, DevLayout.ROW, steps -> set(section, row, DevLayout.snap(row, row.value().get() + steps * row.step())));
            }
            case TOGGLE -> {
                boolean on = row.value().get() > 0.5;
                int tx = vx + 24;
                rect(g, tx, y + 3, 26, 10, on ? DigiTheme.withAlpha(DigiTheme.TEAL, 0x60) : DigiTheme.withAlpha(DigiTheme.VOID, 0xE6));
                g.outline(tx, y + 3, 26, 10, on ? DigiTheme.TEAL : DigiTheme.EDGE_DIM);
                rect(g, on ? tx + 15 : tx + 2, y + 5, 9, 6, on ? DigiTheme.WHITE : DigiTheme.MUTED);
                String state = on ? "ON" : "OFF";
                g.text(font, state, tx - 5 - font.width(state), y + 4, on ? DigiTheme.TEAL : DigiTheme.MUTED, false);
                click(vx, y + 2, 50, 12, () -> set(section, row, on ? 0 : 1));
            }
            case CHOICE -> {
                int ox = vx + 50;
                for (int i = row.options().size() - 1; i >= 0; i--) {
                    String option = row.options().get(i);
                    int ow = font.width(option) + 8, index = i;
                    ox -= ow;
                    boolean on = Math.round(row.value().get()) == i, hover = over(ox, y + 2, ow, 12);
                    rect(g, ox, y + 2, ow, 12, on ? DigiTheme.withAlpha(DigiTheme.CYAN, 0x38) : DigiTheme.withAlpha(DigiTheme.VOID, 0xC0));
                    g.outline(ox, y + 2, ow, 12, on ? DigiTheme.CYAN : hover ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM);
                    g.text(font, option, ox + 4, y + 4, on ? DigiTheme.WHITE : DigiTheme.MUTED, false);
                    click(ox, y + 2, ow, 12, () -> set(section, row, index));
                    ox += 1;
                }
            }
        }
        if (changed) {
            int bx = x + w - 38;
            g.text(font, "RESET", bx, y + 4, over(bx - 2, y + 2, 34, 12) ? DigiTheme.AMBER : DigiTheme.MUTED, false);
            click(bx - 2, y + 2, 34, 12, () -> set(section, row, row.value().defaultValue()));
        }
    }

    /** The searchable Digimon list, under its anchor or above it when there is no room below. */
    private void picker(GuiGraphicsExtractor g, int px, int py, int pw, int ph) {
        int w = PICKER_WIDTH, h = 16 + PICKER_ROWS * LIST_ROW + 2;
        int x = Math.min(picker.x, px + pw - 8 - w), y = picker.y + picker.anchorHeight + 1;
        if (y + h > py + ph - 4) y = picker.y - h - 1;
        List<Choice> list = filtered();
        picker.scroll = Math.max(0, Math.min(picker.scroll, list.size() - PICKER_ROWS));
        // anything outside the list closes it and goes no further
        hits.add(new Hit(0, 0, width, height, () -> picker = null, steps -> {}, NO_CLIP, 0));
        hits.add(new Hit(x, y, w, h, () -> {}, steps -> picker.scroll -= steps, NO_CLIP, 0));

        DigiPanels.frame(g, x - 1, y - 1, w + 2, h + 2, DigiTheme.VOID, DigiTheme.AMBER, 2);
        rect(g, x + 1, y + 1, w - 2, 14, DigiTheme.PANEL_RAISED);
        lens(g, x + 5, y + 4, DigiTheme.AMBER);
        if (picker.query.isEmpty()) g.text(font, "NAME, STAGE, ATTRIBUTE", x + 16, y + 4, DigiTheme.withAlpha(DigiTheme.MUTED, 0x90), false);
        else g.text(font, picker.query.toUpperCase(Locale.ROOT), x + 16, y + 4, DigiTheme.WHITE, false);
        if (ticks % 20 < 10) rect(g, picker.query.isEmpty() ? x + 13 : x + 18 + font.width(picker.query.toUpperCase(Locale.ROOT)), y + 3, 1, 9, DigiTheme.AMBER);
        if (list.isEmpty()) g.centeredText(font, "NO MATCH", x + w / 2, y + 50, DigiTheme.MUTED);
        for (int i = 0; i < PICKER_ROWS && picker.scroll + i < list.size(); i++) {
            Choice choice = list.get(picker.scroll + i);
            int ry = y + 16 + i * LIST_ROW;
            boolean hover = over(x + 1, ry, w - 6, LIST_ROW), first = !picker.query.isEmpty() && picker.scroll + i == 0;
            if (hover) rect(g, x + 1, ry, w - 6, LIST_ROW, DigiTheme.PANEL_RAISED);
            if (choice.id().equals(picker.current)) rect(g, x + 1, ry + 1, 2, LIST_ROW - 2, DigiTheme.AMBER);
            DigiPanels.attributeGlyph(g, choice.species().attribute(), x + 7, ry + 5, DigiPanels.attributeColor(choice.species().attribute()));
            g.text(font, fit(choice.name(), w - 30 - font.width(choice.stage())), x + 16, ry + 3, first || choice.id().equals(picker.current) ? DigiTheme.AMBER : DigiTheme.WHITE, true);
            g.text(font, choice.stage(), x + w - 9 - font.width(choice.stage()), ry + 3, DigiTheme.withAlpha(DigiTheme.MUTED, 0xB0), false);
            click(x + 1, ry, w - 6, LIST_ROW, () -> choose(choice));
        }
        if (list.size() > PICKER_ROWS) {
            int lh = PICKER_ROWS * LIST_ROW, thumb = Math.max(8, lh * PICKER_ROWS / list.size());
            rect(g, x + w - 4, y + 16, 2, lh, DigiTheme.EDGE_DIM);
            rect(g, x + w - 4, y + 16 + (lh - thumb) * picker.scroll / (list.size() - PICKER_ROWS), 2, thumb, DigiTheme.EDGE_LIGHT);
        }
    }

    private void results(GuiGraphicsExtractor g, List<DevSearch.Entry> list, int x, int y, int w) {
        int h = Math.max(1, list.size()) * RESULT_ROW + 2 + (list.isEmpty() ? 0 : 11);
        searchSelected = Math.max(0, Math.min(searchSelected, list.size() - 1));
        hits.add(new Hit(0, 0, width, height, () -> searching = false, steps -> {}, NO_CLIP, 0));
        DigiPanels.frame(g, x - 1, y - 1, w + 2, h + 2, DigiTheme.VOID, DigiTheme.AMBER, 2);
        if (list.isEmpty()) {
            g.text(font, "NOTHING CALLED THAT", x + 8, y + 4, DigiTheme.MUTED, false);
            return;
        }
        String first = DevSearch.firstWord(search);
        for (int i = 0; i < list.size(); i++) {
            DevSearch.Entry entry = list.get(i);
            int ry = y + 1 + i * RESULT_ROW;
            if (i == searchSelected || over(x, ry, w, RESULT_ROW)) rect(g, x + 1, ry, w - 2, RESULT_ROW, DigiTheme.PANEL_RAISED);
            if (i == searchSelected) rect(g, x + 1, ry + 2, 2, 11, DigiTheme.AMBER);
            g.text(font, entry.label(), x + 8, ry + 4, DigiTheme.WHITE, true);
            int at = entry.label().indexOf(first);
            if (at >= 0) g.text(font, first, x + 8 + font.width(entry.label().substring(0, at)), ry + 4, DigiTheme.AMBER, false);
            String crumb = fit(entry.crumb(), w - 24 - font.width(entry.label()));
            g.text(font, crumb, x + w - 8 - font.width(crumb), ry + 4, DigiTheme.withAlpha(DigiTheme.CYAN, 0xB0), false);
            click(x, ry, w, RESULT_ROW, () -> go(entry));
        }
        rect(g, x + 1, y + h - 11, w - 2, 1, DigiTheme.EDGE_DIM);
        String hint = "UP DOWN PICKS · ENTER GOES THERE";
        g.text(font, hint, x + w - 6 - font.width(hint), y + h - 9, DigiTheme.withAlpha(DigiTheme.MUTED, 0xC0), false);
    }

    // --- pieces ------------------------------------------------------------------------------

    private void field(GuiGraphicsExtractor g, int x, int y, int w, int h, String value, String placeholder, boolean focused) {
        rect(g, x, y, w, h, DigiTheme.withAlpha(DigiTheme.VOID, 0xE6));
        g.outline(x, y, w, h, focused ? DigiTheme.AMBER : DigiTheme.EDGE);
        lens(g, x + 4, y + 3, focused ? DigiTheme.AMBER : DigiTheme.MUTED);
        String text = value.toUpperCase(Locale.ROOT);
        if (value.isEmpty()) g.text(font, fit(placeholder, w - 20), x + 15, y + 3, DigiTheme.withAlpha(DigiTheme.MUTED, 0x90), false);
        else g.text(font, text, x + 15, y + 3, DigiTheme.WHITE, false);
        if (focused && ticks % 20 < 10) rect(g, value.isEmpty() ? x + 12 : x + 17 + font.width(text), y + 2, 1, 9, DigiTheme.AMBER);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean primary, boolean enabled) {
        boolean hover = enabled && over(x, y, w, h);
        int fill = primary && enabled ? DigiTheme.withAlpha(DigiTheme.mix(DigiTheme.PANEL, DigiTheme.AMBER, hover ? 0.42F : 0.26F), 0xF0)
                : DigiTheme.withAlpha(hover ? DigiTheme.PANEL_RAISED : DigiTheme.PANEL, enabled ? 0xF0 : 0xA0);
        DigiPanels.frame(g, x - 1, y - 1, w + 2, h + 2, 0, DigiTheme.withAlpha(DigiTheme.VOID, 0xB0), 3);
        DigiPanels.frame(g, x, y, w, h, fill, 0, 2);
        DigiPanels.bevel(g, x, y, w, h, 2, !enabled ? DigiTheme.EDGE_DIM : primary || hover ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        int color = !enabled ? DigiTheme.withAlpha(DigiTheme.MUTED, 0x90) : primary || hover ? DigiTheme.AMBER : DigiTheme.WHITE;
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, color, true);
    }

    private void keycap(GuiGraphicsExtractor g, int x, int y, String label) {
        int w = font.width(label) + 6;
        rect(g, x - 1, y - 1, w + 2, 12, DigiTheme.withAlpha(DigiTheme.SHADOW, 0xE0));
        rect(g, x, y, w, 10, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, 0xF0));
        DigiPanels.bevel(g, x, y, w, 10, 1, DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        g.text(font, label, x + 3, y + 1, DigiTheme.WHITE, false);
    }

    /** A 5 x 3 fold mark: pointing down when open, right when shut. */
    static void chevron(GuiGraphicsExtractor g, int x, int y, boolean open, int color) {
        for (int i = 0; i < 3; i++) {
            if (open) rect(g, x + i, y + 1 + i, 5 - 2 * i, 1, color);
            else rect(g, x + 1 + i, y + i, 1, 5 - 2 * i, color);
        }
    }

    private static void lens(GuiGraphicsExtractor g, int x, int y, int color) {
        rect(g, x + 1, y, 3, 1, color);
        rect(g, x + 1, y + 4, 3, 1, color);
        rect(g, x, y + 1, 1, 3, color);
        rect(g, x + 4, y + 1, 1, 3, color);
        rect(g, x + 4, y + 5, 1, 1, color);
        rect(g, x + 5, y + 6, 2, 2, color);
    }

    private String fit(String text, int maxWidth) {
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, Math.max(0, maxWidth - 6)) + "…";
    }

    private static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, color);
    }

    // --- state -------------------------------------------------------------------------------

    private DevTabs.Tab tab() {
        List<DevTabs.Tab> tabs = client.tabs.tabs();
        client.tab = Math.max(0, Math.min(client.tab, tabs.size() - 1));
        return tabs.get(client.tab);
    }

    private int[] tabWidths() {
        return client.tabs.tabs().stream().mapToInt(tab -> font.width(tab.name()) + TAB_PAD).toArray();
    }

    private void showTab(int index) {
        client.tab = index;
        client.firstTab = DevLayout.firstTab(tabWidths(), TAB_GAP, client.firstTab, index, DevLayout.panelWidth(width) - 20 - ARROWS);
    }

    /** Unfold a section and bring its header to the top of the view. */
    private void reveal(DevTabs.Tab tab, int index) {
        client.collapsed.remove(tab.sections().get(index));
        client.scroll.put(tab, DevLayout.sectionTop(tab, client.collapsed::contains, index));
    }

    private void go(DevSearch.Entry entry) {
        showTab(entry.tab());
        DevTabs.Tab tab = tab();
        reveal(tab, entry.section());
        if (entry.row() >= 0) {
            flashRow = tab.sections().get(entry.section()).rows().get(entry.row());
            flashStart = ticks;
        }
        search = "";
        searching = false;
    }

    private void set(DevTabs.Section section, DevTabs.Row row, double value) {
        row.value().set(value);
        section.status("");
    }

    private List<Choice> filtered() {
        String needle = picker.query.trim().toLowerCase(Locale.ROOT);
        return needle.isEmpty() ? choices : choices.stream().filter(choice -> choice.haystack().contains(needle)).toList();
    }

    private void choose(Choice choice) {
        picker.pick.accept(choice.id());
        picker = null;
    }

    // --- input -------------------------------------------------------------------------------

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return true;
        mouseX = (int) event.x();
        mouseY = (int) event.y();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.click() == null || !hit.contains(event.x(), event.y())) continue;
            hit.click().run();
            return true;
        }
        searching = false;
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dragDeltaX, double dragDeltaY) {
        if (dragging == null) return false;
        dragging.value().set(DevLayout.fromSlider(dragging, (event.x() - dragX) / dragWidth));
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        int steps = (int) Math.signum(vertical);
        if (steps == 0) return true;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.wheel() == null || !hit.contains(x, y)) continue;
            hit.wheel().accept(steps);
            break;
        }
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            // one layer at a time: the list, then the query, then the panel
            if (picker != null) picker = null;
            else if (searching && !search.isEmpty()) search = "";
            else onClose();
            return true;
        }
        boolean enter = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
        if (picker != null) {
            if (key == InputConstants.KEY_BACKSPACE && !picker.query.isEmpty()) picker.query = picker.query.substring(0, picker.query.length() - 1);
            else if (key == InputConstants.KEY_DOWN) picker.scroll++;
            else if (key == InputConstants.KEY_UP) picker.scroll--;
            else if (enter && !filtered().isEmpty()) choose(filtered().get(Math.min(picker.scroll, filtered().size() - 1)));
            return true;
        }
        if (searching) {
            if (key == InputConstants.KEY_BACKSPACE && !search.isEmpty()) { search = search.substring(0, search.length() - 1); searchSelected = 0; }
            else if (key == InputConstants.KEY_DOWN) searchSelected++;
            else if (key == InputConstants.KEY_UP) searchSelected--;
            else if (enter) {
                List<DevSearch.Entry> list = DevSearch.query(client.index, search);
                if (!list.isEmpty()) go(list.get(Math.max(0, Math.min(searchSelected, list.size() - 1))));
            }
        }
        return true;
    }

    /** Typing goes to the open list, else to the config search: no need to click it first. */
    @Override public boolean charTyped(CharacterEvent event) {
        if (!event.isAllowedChatCharacter()) return false;
        String typed = event.codepointAsString();
        if (picker != null) {
            if (picker.query.length() < PICKER_LIMIT) { picker.query += typed; picker.scroll = 0; }
            return true;
        }
        if (!searching) { searching = true; search = ""; }
        if (search.length() < SEARCH_LIMIT) { search += typed; searchSelected = 0; }
        return true;
    }
}
