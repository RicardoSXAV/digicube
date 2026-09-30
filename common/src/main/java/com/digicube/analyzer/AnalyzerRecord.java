package com.digicube.analyzer;

import com.digicube.digimon.CombatMark;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one tamer has witnessed: the species and the combat marks the Analyzer may describe. It only grows in play;
 * nothing is ever taken out of it again.
 */
public final class AnalyzerRecord {
    private final Set<Identifier> species = new TreeSet<>();
    private final EnumSet<CombatMark> marks = EnumSet.noneOf(CombatMark.class);

    public AnalyzerRecord() {}

    /** @param marks mark ids as saved; one this version does not know is dropped */
    AnalyzerRecord(Collection<Identifier> species, Collection<String> marks) {
        this.species.addAll(species);
        for (String id : marks) CombatMark.byId(id).ifPresent(this.marks::add);
    }

    /** @return whether the species is new to the record */
    public boolean add(Identifier id) { return species.add(id); }
    /** @return whether the mark is new to the record */
    public boolean add(CombatMark mark) { return marks.add(mark); }
    public boolean knows(Identifier id) { return species.contains(id); }
    public boolean knows(CombatMark mark) { return marks.contains(mark); }

    /** Sorted by id. */
    public List<Identifier> species() { return List.copyOf(species); }
    public Set<CombatMark> marks() { return EnumSet.copyOf(marks); }
    List<String> markIds() { return marks.stream().map(CombatMark::id).toList(); }
}
