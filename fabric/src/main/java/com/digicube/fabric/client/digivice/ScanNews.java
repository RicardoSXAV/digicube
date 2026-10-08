package com.digicube.fabric.client.digivice;

import com.digicube.digimon.Progression;
import com.digicube.scan.ScanBar;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Digitama the tamer can convert and has not looked at on the SCAN page yet: an amber light on the page's tab and a
 * dot on the ANALYZER tab until the family is shown there. A bar that reaches a Digitama's worth between two
 * snapshots becomes news. Connection-scoped, like the party snapshot.
 */
public final class ScanNews {
    private final Set<Identifier> ready = new HashSet<>();
    private final Map<Identifier, Integer> last = new HashMap<>();

    /** A fresh snapshot's bars: a bar that crossed into a Digitama's worth is news; one that fell under it is not. */
    public void update(List<ScanBar> bars) {
        for (ScanBar bar : bars) {
            Integer before = last.put(bar.family(), bar.data());
            boolean now = bar.seen() && bar.data() >= Progression.DIGITAMA_DATA;
            if (!now) ready.remove(bar.family());
            else if (before == null || before < Progression.DIGITAMA_DATA) ready.add(bar.family());
        }
    }

    public boolean fresh(Identifier family) { return ready.contains(family); }
    public boolean any() { return !ready.isEmpty(); }
    /** The family was shown on the SCAN page. */
    public void opened(Identifier family) { ready.remove(family); }
    public void clear() { ready.clear(); last.clear(); }
}
