package com.digicube.fabric.client.dev;

import java.util.List;

/**
 * Pins the developer panel's rules without a window: what a declaration produces, the config
 * search, the scrolling card arithmetic, the tab row, and how a number row snaps and prints.
 */
public final class DevPanelRegressionTest {
    private static int checks;

    private DevPanelRegressionTest() {}

    public static void main(String[] args) {
        DevTabs tabs = new DevTabs();
        DevValue advantage = DevValue.local(1.5);
        boolean[] wrote = {false};
        tabs.tab("COMBAT").section("BATTLE TESTING")
                .body(new DevBody() {
                    @Override public int height() { return 42; }
                    @Override public void draw(DevCanvas canvas, int x, int y, int width) {}
                    @Override public String summary() { return "AGUMON 20 VS GABUMON 20"; }
                })
                .keywords("fighter a", "start battle")
                .action("CLEAR", () -> true, () -> "")
                .primary("START BATTLE", () -> true, () -> "");
        tabs.tab("COMBAT").section("DAMAGE").example()
                .number("ATTRIBUTE ADVANTAGE", 1, 3, 0.05, "x", advantage)
                .number("CRITICAL CHANCE", 0, 50, 1, "%", DevValue.local(5))
                .toggle("SHOW DAMAGE NUMBERS", DevValue.local(0))
                .tuning(() -> { wrote[0] = true; return "WROTE"; })
                .section("ATTACK TIMING")
                .number("GLOBAL COOLDOWN", 0, 100, 1, "t", DevValue.local(20))
                .choice("TARGET PICK", List.of("NEAREST", "WEAKEST"), DevValue.local(0));
        tabs.tab("EVOLUTION").section("DIGISOUL").number("COOLDOWN", 0, 120, 1, "s", DevValue.local(10));

        // declarations
        DevTabs.Tab combat = tabs.tabs().get(0);
        DevTabs.Section battle = combat.sections().get(0), damage = combat.sections().get(1), timing = combat.sections().get(2);
        check(tabs.tabs().size() == 2 && combat.sections().size() == 3, "tabs and sections are created once, in declaration order");
        check(tabs.tab("COMBAT") == combat && combat.section("DAMAGE") == damage, "naming a tab or a section again returns it");
        check(damage.isExample() && !timing.isExample() && damage.rows().size() == 3, "example mark and rows stay with their section");
        check(damage.actions().size() == 2 && damage.actions().get(1).primary() && !damage.actions().get(0).primary(), "tuning adds RESET and a primary WRITE TO REPO");
        check(battle.summary().equals("AGUMON 20 VS GABUMON 20") && damage.summary().equals("3 CONFIGS"), "a body writes its own summary, rows are counted");

        // changed markers and the tuning actions
        check(!damage.actions().get(1).enabled().getAsBoolean() && !combat.changed(), "nothing changed: the write is dark");
        advantage.set(1.65);
        check(damage.changed() == 1 && combat.changed() && damage.summary().equals("3 CONFIGS · 1 CHANGED"), "a changed row marks its section and tab");
        check(damage.actions().get(1).enabled().getAsBoolean() && damage.line().startsWith("EXAMPLE · 1 CHANGED"), "the write lights up and the bar says so");
        damage.status(damage.actions().get(1).run().get());
        check(wrote[0] && damage.changed() == 0 && advantage.defaultValue() == 1.65 && damage.line().equals("WROTE"), "writing commits the values and reports");
        advantage.set(2);
        damage.actions().get(0).run().get();
        check(advantage.get() == 1.65, "reset returns to the last written value");

        // search
        List<DevSearch.Entry> index = DevSearch.index(tabs);
        List<DevSearch.Entry> cool = DevSearch.query(index, "cool");
        check(cool.size() == 2 && cool.get(0).label().equals("COOLDOWN") && cool.get(0).crumb().equals("EVOLUTION > DIGISOUL"), "a label starting with the word comes first");
        check(cool.get(1).label().equals("GLOBAL COOLDOWN") && cool.get(1).tab() == 0 && cool.get(1).section() == 2 && cool.get(1).row() == 0, "a result knows its tab, section and row");
        check(DevSearch.query(index, "fighter").get(0).section() == 0 && DevSearch.query(index, "fighter").get(0).row() == -1, "body keywords are searchable");
        check(DevSearch.query(index, "combat chance").size() == 1, "every word must match, across label and breadcrumb");
        check(DevSearch.query(index, "  ").isEmpty() && DevSearch.query(index, "zzz").isEmpty(), "blank and unknown queries find nothing");
        check(DevSearch.query(index, "evolution").get(0).crumb().equals("TAB"), "tabs are results too");

        // cards
        check(DevLayout.sectionHeight(damage, true) == 15 && DevLayout.sectionHeight(damage, false) == 15 + 4 + 3 * 16 + 24, "a card is header, rows and its action bar");
        check(DevLayout.sectionHeight(battle, false) == 15 + 4 + 44 + 24, "a body card is sized by its body");
        check(DevLayout.sectionHeight(timing, false) == 15 + 4 + 2 * 16 + 4, "a card without actions ends in padding");
        check(DevLayout.sectionTop(combat, s -> false, 1) == 87 + 6 && DevLayout.sectionTop(combat, s -> s == battle, 1) == 15 + 6, "folding a card pulls the next one up");
        int total = DevLayout.contentHeight(combat, s -> false);
        check(total == 87 + 6 + 91 + 6 + 55, "content height has gaps between cards only");
        check(DevLayout.clampScroll(999, total, 192) == total - 192 && DevLayout.clampScroll(-5, total, 192) == 0 && DevLayout.clampScroll(40, 100, 192) == 0, "scroll stays inside the content");
        check(DevLayout.currentSection(combat, s -> false, 0) == 0 && DevLayout.currentSection(combat, s -> false, 93) == 1, "the index follows the scroll");

        // tab row
        int[] widths = {52, 64, 70, 52, 46, 46, 46};
        check(DevLayout.tabsShown(widths, 2, 0, 370) == 6 && DevLayout.tabsShown(widths, 2, 0, 40) == 1, "tabs fill the row; one always shows");
        check(DevLayout.firstTab(widths, 2, 0, 6, 370) == 1 && DevLayout.firstTab(widths, 2, 3, 1, 370) == 1 && DevLayout.firstTab(widths, 2, 0, 2, 370) == 0, "the row scrolls just far enough to show the active tab");

        // number rows
        DevTabs.Row row = damage.rows().get(0);
        advantage.set(2);
        check(DevLayout.snap(row, 1.6649) == 1.65 && DevLayout.snap(row, 9) == 3 && DevLayout.snap(row, -1) == 1, "values snap to the step and clamp to the range");
        check(DevLayout.fromSlider(row, 0.5) == 2 && DevLayout.fromSlider(row, 2) == 3 && DevLayout.fraction(row, 2) == 0.5, "the slider maps across the range");
        check(DevLayout.format(row).equals("2.00 x") && DevLayout.format(damage.rows().get(1)).equals("5 %") && DevLayout.format(timing.rows().get(0)).equals("20 t"), "numbers print by their step, with the unit");
        check(DevLayout.panelWidth(500) == 420 && DevLayout.panelWidth(320) == 300 && DevLayout.panelHeight(240) == 220, "the panel shrinks on a small screen");

        System.out.println("PASS: developer panel rules hold (" + checks + " checks)");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAIL: " + what);
    }
}
