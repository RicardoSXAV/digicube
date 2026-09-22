package com.digicube.fabric.client.digivice;

import com.digicube.digimon.DigimonAttribute;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonStage;
import com.digicube.digimon.Evolution;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * The Analyzer's numbered list of every species, in stage order, and the questions the tab asks of it: which entries
 * pass the attribute filter and the search, who evolves into whom, and how long a stat bar is. An entry the tamer has
 * not {@code known} yet keeps its number but gives nothing else away, so a search never finds it.
 */
public final class AnalyzerIndex {
    /** {@code number} is 1-based and stable for a given registry. */
    public record Entry(int number, DigimonSpecies species, boolean known) {
        public Identifier id() { return species.id(); }
        public String label() { return String.format(Locale.ROOT, "%03d", number); }
    }
    /** One thumbnail of an evolution line; {@code level} is what it takes to get there, 0 for the entry itself and its parent. */
    public record Step(Entry entry, int level) {}

    private final List<Entry> entries = new ArrayList<>();
    private final int maxHealth, maxAttack, maxDefence;
    private final float maxSpeed;

    public AnalyzerIndex(Collection<DigimonSpecies> species, Set<Identifier> known) {
        List<DigimonSpecies> sorted = new ArrayList<>(species);
        sorted.sort(Comparator.comparingInt((DigimonSpecies s) -> order(s.stage())).thenComparing(s -> s.id().toString()));
        int health = 1, attack = 1, defence = 1;
        float speed = 0.01F;
        for (DigimonSpecies s : sorted) {
            entries.add(new Entry(entries.size() + 1, s, known.contains(s.id())));
            health = Math.max(health, s.baseHealth()); attack = Math.max(attack, s.baseAttack());
            defence = Math.max(defence, s.baseDefence()); speed = Math.max(speed, s.baseSpeed());
        }
        maxHealth = health; maxAttack = attack; maxDefence = defence; maxSpeed = speed;
    }

    /** Stages off the main ladder (Armor, Hybrid) come last. */
    private static int order(DigimonStage stage) { return stage.ordinal(); }

    public List<Entry> entries() { return entries; }
    public int knownCount() { return (int) entries.stream().filter(Entry::known).count(); }
    public Entry get(Identifier id) { for (Entry e : entries) if (e.id().equals(id)) return e; return null; }

    /**
     * @param attribute null for every attribute
     * @param query     matched against name, stage, attribute and number; every word must be found
     * @param name      the display name of a species, as the player reads it
     */
    public List<Entry> filter(DigimonAttribute attribute, String query, Function<DigimonSpecies, String> name) {
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        boolean searching = !query.isBlank();
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries) {
            if (attribute != null && (!e.known() || e.species().attribute() != attribute)) continue;
            if (searching) {
                if (!e.known()) continue;
                String hay = (name.apply(e.species()) + " " + e.species().stage().getId() + " " + e.species().attribute().getId() + " " + e.label()).toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String word : words) all &= hay.contains(word);
                if (!all) continue;
            }
            result.add(e);
        }
        return result;
    }

    /** First parent, the entry, then up to two evolutions: what fits beside the stats. */
    public List<Step> line(Entry entry) {
        List<Step> line = new ArrayList<>();
        for (Entry e : entries) {
            if (e.species().evolutions().stream().anyMatch(route -> route.target().equals(entry.id()))) { line.add(new Step(e, 0)); break; }
        }
        line.add(new Step(entry, 0));
        int shown = 0;
        for (Evolution route : entry.species().evolutions()) {
            Entry target = get(route.target());
            if (target == null || shown++ >= 2) continue;
            line.add(new Step(target, route.minLevel()));
        }
        return line;
    }

    public float healthFraction(DigimonSpecies s) { return (float) s.baseHealth() / maxHealth; }
    public float attackFraction(DigimonSpecies s) { return (float) s.baseAttack() / maxAttack; }
    public float defenceFraction(DigimonSpecies s) { return (float) s.baseDefence() / maxDefence; }
    public float speedFraction(DigimonSpecies s) { return s.baseSpeed() / maxSpeed; }
}
