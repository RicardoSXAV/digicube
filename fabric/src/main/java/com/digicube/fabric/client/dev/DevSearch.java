package com.digicube.fabric.client.dev;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The panel's config search: one entry per tab, section, row and section keyword, each knowing
 * where it lives. Every word of the query must appear in the entry's label or breadcrumb;
 * labels that start with the first word come first, then labels that contain it.
 */
public final class DevSearch {
    /** {@code row} is -1 for a tab, a section or a keyword. */
    public record Entry(String label, String crumb, int tab, int section, int row) {}

    public static final int LIMIT = 7;

    private DevSearch() {}

    public static List<Entry> index(DevTabs tabs) {
        List<Entry> entries = new ArrayList<>();
        for (int t = 0; t < tabs.tabs().size(); t++) {
            DevTabs.Tab tab = tabs.tabs().get(t);
            entries.add(new Entry(tab.name(), "TAB", t, 0, -1));
            for (int s = 0; s < tab.sections().size(); s++) {
                DevTabs.Section section = tab.sections().get(s);
                String crumb = tab.name() + " > " + section.name();
                entries.add(new Entry(section.name(), tab.name(), t, s, -1));
                for (int r = 0; r < section.rows().size(); r++) entries.add(new Entry(section.rows().get(r).label(), crumb, t, s, r));
                for (String word : section.keywords()) entries.add(new Entry(word.toUpperCase(Locale.ROOT), crumb, t, s, -1));
            }
        }
        return entries;
    }

    public static List<Entry> query(List<Entry> index, String text) {
        String[] words = text.trim().toUpperCase(Locale.ROOT).split("\\s+");
        if (words[0].isEmpty()) return List.of();
        String first = words[0];
        return index.stream()
                .filter(entry -> {
                    String hay = entry.label() + " " + entry.crumb();
                    for (String word : words) if (!hay.contains(word)) return false;
                    return true;
                })
                .sorted(Comparator.comparingInt((Entry entry) -> entry.label().startsWith(first) ? 0 : entry.label().contains(first) ? 1 : 2))
                .limit(LIMIT).toList();
    }

    /** The first query word, as the results highlight it. */
    public static String firstWord(String text) {
        return text.trim().toUpperCase(Locale.ROOT).split("\\s+")[0];
    }
}
