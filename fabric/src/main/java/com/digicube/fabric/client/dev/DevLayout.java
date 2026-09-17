package com.digicube.fabric.client.dev;

import java.util.Locale;
import java.util.function.Predicate;

/** The panel's numbers and arithmetic, apart from the drawing so they can be tested headless. */
public final class DevLayout {
    public static final int PANEL_WIDTH = 420, PANEL_HEIGHT = 238, MARGIN = 10;
    public static final int RAIL_WIDTH = 100, BODY_TOP = 39, BODY_BOTTOM = 7;
    public static final int HEADER = 15, ROW = 16, ACTIONS = 24, SECTION_GAP = 6, SECTION_PAD = 4;
    public static final int SCROLL_STEP = 16;

    private DevLayout() {}

    public static int panelWidth(int screenWidth) { return Math.min(PANEL_WIDTH, screenWidth - 2 * MARGIN); }
    public static int panelHeight(int screenHeight) { return Math.min(PANEL_HEIGHT, screenHeight - 2 * MARGIN); }

    public static int sectionHeight(DevTabs.Section section, boolean collapsed) {
        if (collapsed) return HEADER;
        int body = section.customBody() != null ? section.customBody().height() + 2 : section.rows().size() * ROW;
        return HEADER + SECTION_PAD + body + (section.actions().isEmpty() ? SECTION_PAD : ACTIONS);
    }

    /** Distance from the top of the content to the top of section {@code index}, or to the end for {@code size}. */
    public static int sectionTop(DevTabs.Tab tab, Predicate<DevTabs.Section> collapsed, int index) {
        int y = 0;
        for (int i = 0; i < index && i < tab.sections().size(); i++) {
            y += sectionHeight(tab.sections().get(i), collapsed.test(tab.sections().get(i))) + SECTION_GAP;
        }
        return y;
    }

    public static int contentHeight(DevTabs.Tab tab, Predicate<DevTabs.Section> collapsed) {
        return Math.max(0, sectionTop(tab, collapsed, tab.sections().size()) - SECTION_GAP);
    }

    public static int clampScroll(int scroll, int contentHeight, int viewHeight) {
        return Math.max(0, Math.min(scroll, contentHeight - viewHeight));
    }

    /** The section the index highlights: the last one whose top has reached the top of the view. */
    public static int currentSection(DevTabs.Tab tab, Predicate<DevTabs.Section> collapsed, int scroll) {
        int current = 0;
        for (int i = 0; i < tab.sections().size(); i++) if (sectionTop(tab, collapsed, i) <= scroll + 12) current = i;
        return current;
    }

    /** The first tab to show so that {@code active} fits in {@code available}; never later than needed. */
    public static int firstTab(int[] widths, int gap, int first, int active, int available) {
        first = Math.max(0, Math.min(first, active));
        while (first < active && span(widths, gap, first, active) > available) first++;
        return first;
    }

    /** How many tabs fit from {@code first}; at least one. */
    public static int tabsShown(int[] widths, int gap, int first, int available) {
        int shown = 0;
        while (first + shown < widths.length && span(widths, gap, first, first + shown) <= available) shown++;
        return Math.max(1, shown);
    }

    private static int span(int[] widths, int gap, int from, int to) {
        int total = 0;
        for (int i = from; i <= to; i++) total += widths[i] + (i > from ? gap : 0);
        return total;
    }

    /** A raw number snapped to the step and range of its row; landing on the default returns it exactly. */
    public static double snap(DevTabs.Row row, double raw) {
        double value = Math.round(raw / row.step()) * row.step();
        value = Math.round(Math.max(row.min(), Math.min(row.max(), value)) * 10_000.0) / 10_000.0;
        return Math.abs(value - row.value().defaultValue()) < 1.0E-9 ? row.value().defaultValue() : value;
    }

    public static double fromSlider(DevTabs.Row row, double fraction) {
        return snap(row, row.min() + Math.max(0, Math.min(1, fraction)) * (row.max() - row.min()));
    }

    public static double fraction(DevTabs.Row row, double value) {
        return row.max() <= row.min() ? 0 : Math.max(0, Math.min(1, (value - row.min()) / (row.max() - row.min())));
    }

    public static String format(DevTabs.Row row) {
        double value = row.value().get();
        String number = row.step() >= 1 ? Long.toString(Math.round(value)) : String.format(Locale.ROOT, row.step() < 0.1 ? "%.2f" : "%.1f", value);
        return row.unit().isEmpty() ? number : number + " " + row.unit();
    }
}
