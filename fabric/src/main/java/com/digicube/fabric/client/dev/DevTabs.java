package com.digicube.fabric.client.dev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * What the developer panel holds: tabs, their sections, each section's rows and actions. The
 * screen draws whatever is declared here (tab row, section index, cards, search entries,
 * changed markers, action bars), so a plain tuning section needs no interface code:
 *
 * <pre>{@code
 * tabs.tab("COMBAT").section("DAMAGE")
 *         .number("ATTRIBUTE ADVANTAGE", 1, 3, 0.05, "x", value)
 *         .toggle("SHOW DAMAGE NUMBERS", value)
 *         .action("RESET", ...).primary("WRITE TO REPO", ...);
 * }</pre>
 *
 * A section with a special body supplies a {@link DevBody} and still gets the card, the fold,
 * the search entries and the action bar. No Minecraft types here, so the rules test headless.
 */
public final class DevTabs {
    public enum Kind { NUMBER, TOGGLE, CHOICE }

    /** One config. {@code options} is only for a choice; {@code min}/{@code max}/{@code step} only for a number. */
    public record Row(Kind kind, String label, double min, double max, double step, String unit, List<String> options, DevValue value) {
        public boolean changed() { return value.changed(); }
    }

    /** A button in a section's own action bar. {@code run} returns the line the bar then shows, or an empty string. */
    public record Action(String label, boolean primary, BooleanSupplier enabled, Supplier<String> run) {}

    public static final class Section {
        private final Tab tab;
        private final String name;
        private final List<Row> rows = new ArrayList<>();
        private final List<Action> actions = new ArrayList<>();
        private final List<String> keywords = new ArrayList<>();
        private boolean example;
        private DevBody body;
        private Supplier<String> note;
        /** The last action's result; shown in the action bar until a row changes. */
        private String status = "";

        private Section(Tab tab, String name) { this.tab = tab; this.name = name; }

        public Section number(String label, double min, double max, double step, String unit, DevValue value) {
            rows.add(new Row(Kind.NUMBER, label, min, max, step, unit, List.of(), value));
            return this;
        }

        public Section toggle(String label, DevValue value) {
            rows.add(new Row(Kind.TOGGLE, label, 0, 1, 1, "", List.of(), value));
            return this;
        }

        public Section choice(String label, List<String> options, DevValue value) {
            rows.add(new Row(Kind.CHOICE, label, 0, options.size() - 1, 1, "", List.copyOf(options), value));
            return this;
        }

        public Section action(String label, BooleanSupplier enabled, Supplier<String> run) {
            actions.add(new Action(label, false, enabled, run));
            return this;
        }

        /** The section's main action: amber, rightmost. */
        public Section primary(String label, BooleanSupplier enabled, Supplier<String> run) {
            actions.add(new Action(label, true, enabled, run));
            return this;
        }

        /** RESET and WRITE TO REPO over this section's rows; {@code write} receives nothing and reports what it did. */
        public Section tuning(Supplier<String> write) {
            action("RESET", () -> changed() > 0, () -> { rows.forEach(row -> row.value().set(row.value().defaultValue())); return ""; });
            return primary("WRITE TO REPO", () -> changed() > 0, () -> { String result = write.get(); rows.forEach(row -> row.value().commit()); return result; });
        }

        /** A hand-drawn body in place of rows. */
        public Section body(DevBody body) { this.body = body; return this; }

        /** Extra search terms: what a body shows that the row list cannot tell the search. */
        public Section keywords(String... words) { keywords.addAll(List.of(words)); return this; }

        /** Invented to show scale; tagged in the header and not wired to the game. */
        public Section example() { example = true; return this; }

        /** The action bar's resting line. */
        public Section note(Supplier<String> note) { this.note = note; return this; }

        /** Continue with the next section of the same tab. */
        public Section section(String next) { return tab.section(next); }

        public String name() { return name; }
        public List<Row> rows() { return Collections.unmodifiableList(rows); }
        public List<Action> actions() { return Collections.unmodifiableList(actions); }
        public List<String> keywords() { return Collections.unmodifiableList(keywords); }
        public boolean isExample() { return example; }
        public DevBody customBody() { return body; }
        public int changed() { return (int) rows.stream().filter(Row::changed).count(); }
        public String status() { return status; }
        public void status(String status) { this.status = status == null ? "" : status; }

        /** What the action bar says: the last result, else the section's note, else the state of its rows. */
        public String line() {
            if (!status.isEmpty()) return status;
            if (note != null) return note.get();
            if (example) return changed() > 0 ? "EXAMPLE · " + changed() + " CHANGED, NOT WIRED" : "EXAMPLE · NOT WIRED TO THE GAME";
            return changed() > 0 ? "LIVE · NOT SAVED" : "MATCHES THE REPO";
        }

        /** The collapsed header's summary. */
        public String summary() {
            if (body != null) return body.summary();
            return rows.size() + " CONFIGS" + (changed() > 0 ? " · " + changed() + " CHANGED" : "");
        }
    }

    public static final class Tab {
        private final String name;
        private final List<Section> sections = new ArrayList<>();

        private Tab(String name) { this.name = name; }

        /** The section called {@code name}, created at the end of the tab on first use. */
        public Section section(String name) {
            for (Section section : sections) if (section.name.equals(name)) return section;
            Section section = new Section(this, name);
            sections.add(section);
            return section;
        }

        public String name() { return name; }
        public List<Section> sections() { return Collections.unmodifiableList(sections); }
        public boolean changed() { return sections.stream().anyMatch(section -> section.changed() > 0); }
    }

    private final List<Tab> tabs = new ArrayList<>();

    /** The tab called {@code name}, created at the end of the row on first use. */
    public Tab tab(String name) {
        for (Tab tab : tabs) if (tab.name.equals(name)) return tab;
        Tab tab = new Tab(name);
        tabs.add(tab);
        return tab;
    }

    public List<Tab> tabs() { return Collections.unmodifiableList(tabs); }
}
