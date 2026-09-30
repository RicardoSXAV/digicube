package com.digicube.fabric.client.digivice;

import com.digicube.digimon.CombatMark;
import net.minecraft.resources.Identifier;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * The entries recorded since the tamer connected that they have not opened in the Analyzer yet: each wears a NEW tag
 * there until it is opened. Connection-scoped, like the party snapshot.
 */
public final class AnalyzerNews {
    private final Set<Identifier> species = new HashSet<>();
    private final Set<CombatMark> marks = EnumSet.noneOf(CombatMark.class);

    public void add(Identifier id) { species.add(id); }
    public void add(CombatMark mark) { marks.add(mark); }
    public boolean fresh(Identifier id) { return species.contains(id); }
    public boolean fresh(CombatMark mark) { return marks.contains(mark); }
    /** The entry was opened: it is news no longer. */
    public void opened(Identifier id) { species.remove(id); }
    public void opened(CombatMark mark) { marks.remove(mark); }
    public boolean anySpecies() { return !species.isEmpty(); }
    public boolean anyMarks() { return !marks.isEmpty(); }
    public void clear() { species.clear(); marks.clear(); }
}
