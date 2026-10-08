package com.digicube.fabric.client.digivice;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Evolution;
import com.digicube.digimon.EvolutionRules;
import com.digicube.fabric.client.party.CommandWheelReadout;
import com.digicube.party.PartyMemberView;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The digivolution tree as the Digivice shows it, without drawing, so its rules are checked headless: a family's
 * forms in a column per stage with one Rookie's branch open at a time, where a Digimon stands in it, which forms it
 * can take now (a Baby II's growth into a Rookie, a Rookie's digivolution into a Champion), which one its first
 * digivolution bound it to and which that ruled out, what is news, and the sentence the sheet says about each form.
 *
 * <p>Coordinates are GUI units from the tree's top-left: the open Rookie sits between its two Champions
 * ({@link #SPREAD} above and below), the folded Rookie {@link #FOLD} beyond it.
 */
public final class EvolutionTree {
    private EvolutionTree() {}

    public static final int[] COLUMNS = {8, 100, 190};
    public static final int TOP = 35, SPREAD = 40, FOLD = 64, WIDTH = 250, HEIGHT = 176, NODE = 34;

    /** A step into {@code id}, open from {@code level}. */
    public record Branch(Identifier id, int level) {}
    /** A form on the sheet. {@code folded}: the forms hidden under a folded Rookie, 0 when its branch is open. */
    public record Node(Identifier id, int x, int y, int column, int level, int folded, List<Node> kids) {}
    /** {@code forks}: the nodes with arrows out of them. */
    public record Layout(Node top, List<Node> forks, List<Node> all) {}

    // ---------- the family ----------

    /** The forms {@code id} grows or digivolves into, in sheet order, with the level each opens at. */
    public static List<Branch> kids(Identifier id) {
        DigimonSpecies species = DigimonSpeciesRegistry.get(id).orElse(null);
        if (species == null) return List.of();
        List<Branch> kids = new ArrayList<>();
        for (Evolution e : species.evolutions()) {
            if (DigimonSpeciesRegistry.get(e.target()).isEmpty() || kids.stream().anyMatch(k -> k.id().equals(e.target()))) continue;
            kids.add(new Branch(e.target(), EvolutionRules.chooser(id) ? EvolutionRules.level(e) : e.minLevel()));
        }
        return kids;
    }

    /** The form {@code id} comes from, with the level of that step, or null for the first form of a family. */
    public static Branch parent(Identifier id) {
        for (DigimonSpecies species : DigimonSpeciesRegistry.all())
            for (Branch kid : kids(species.id())) if (kid.id().equals(id)) return new Branch(species.id(), kid.level());
        return null;
    }

    /** The first form of {@code id}'s family. */
    public static Identifier family(Identifier id) {
        Identifier root = id;
        for (int i = 0; i < 6; i++) { Branch up = parent(root); if (up == null) break; root = up.id(); }
        return root;
    }

    /** Whether {@code earlier} comes before {@code later} in their family. */
    public static boolean ancestor(Identifier earlier, Identifier later) {
        Branch up = parent(later);
        for (int i = 0; up != null && i < 6; up = parent(up.id()), i++) if (up.id().equals(earlier)) return true;
        return false;
    }

    /**
     * The sheet's tree for {@code root}'s family at {@code x}, {@code y}: one Rookie's branch open ({@code open}), the
     * other folded; with {@code open} null every branch opens, as on a poster.
     */
    public static Layout layout(Identifier root, int x, int y, Identifier open) {
        List<Branch> rookies = kids(root);
        boolean every = open == null;
        int openIndex = 0;
        for (int i = 0; i < rookies.size(); i++) if (rookies.get(i).id().equals(open)) openIndex = i;
        int[] centres = new int[rookies.size()];
        for (int i = 0; i < centres.length; i++) {
            if (every) centres[i] = TOP + SPREAD + i * (2 * SPREAD + 62);
            else if (rookies.size() == 1) centres[i] = TOP + SPREAD;
            // the open branch takes the room it needs, the folded ones queue up beyond it
            else centres[i] = openIndex == 0 ? TOP + SPREAD + i * FOLD : i == openIndex ? TOP + FOLD : TOP + (i < openIndex ? i : i - 1) * FOLD;
        }
        List<Node> rookieNodes = new ArrayList<>(), all = new ArrayList<>();
        for (int i = 0; i < rookies.size(); i++) {
            Branch rookie = rookies.get(i);
            boolean shown = every || i == openIndex || rookies.size() == 1;
            List<Branch> champions = kids(rookie.id());
            List<Node> kids = new ArrayList<>();
            if (shown) for (int j = 0; j < champions.size(); j++) {
                int cy = champions.size() == 1 ? centres[i] : centres[i] - SPREAD + j * 2 * SPREAD / (champions.size() - 1);
                kids.add(node(champions.get(j).id(), x, y, 2, cy, champions.get(j).level(), 0, List.of()));
            }
            rookieNodes.add(node(rookie.id(), x, y, 1, centres[i], rookie.level(), shown ? 0 : champions.size(), kids));
        }
        int middle = rookies.isEmpty() ? TOP + SPREAD : (centres[0] + centres[every || rookies.size() < 2 ? centres.length - 1 : Math.min(1, centres.length - 1)]) / 2;
        Node top = node(root, x, y, 0, middle, 0, 0, rookieNodes);
        List<Node> forks = new ArrayList<>();
        if (!rookieNodes.isEmpty()) forks.add(top);
        for (Node n : rookieNodes) if (!n.kids().isEmpty()) forks.add(n);
        all.add(top);
        all.addAll(rookieNodes);
        for (Node n : rookieNodes) all.addAll(n.kids());
        return new Layout(top, forks, all);
    }

    /** How far down a form reaches: its box, the name under it and, folded, the note under that. */
    public static int extent(Node n) { return n.folded() > 0 ? 54 : 45; }

    private static Node node(Identifier id, int x, int y, int column, int centre, int level, int folded, List<Node> kids) {
        return new Node(id, x + COLUMNS[column], y + centre - NODE / 2, column, level, folded, kids);
    }

    /** Which Rookie's branch is open: the one unfolded by hand, else the Digimon's own (or the species'), else the first. */
    public static Identifier openBranch(PartyMemberView member, Identifier species, Identifier root, Identifier unfolded) {
        if (unfolded != null) return unfolded;
        List<Identifier> rookies = kids(root).stream().map(Branch::id).toList();
        Identifier id = member != null ? member.species() : species;
        if (rookies.contains(id)) return id;
        Identifier own = member != null ? rookie(member) : null;
        if (own != null && rookies.contains(own)) return own;
        Branch up = id == null ? null : parent(id);
        if (up != null && rookies.contains(up.id())) return up.id();
        return rookies.isEmpty() ? null : rookies.getFirst();
    }

    // ---------- where a Digimon stands ----------

    public static boolean inParty(PartyMemberView m) { return m.slot() >= 0; }
    public static boolean evolved(PartyMemberView m) { return "EVOLVED".equals(m.phase()) && origin(m) != null; }
    public static Identifier origin(PartyMemberView m) { return m.origin().isEmpty() ? null : Identifier.tryParse(m.origin()); }

    /** The Rookie behind {@code m}: itself, or the Rookie form of a Champion; null for a Champion without one. */
    public static Identifier rookie(PartyMemberView m) {
        if (EvolutionRules.rookie(m.species())) return m.species();
        Identifier origin = origin(m);
        return origin != null && EvolutionRules.validOrigin(origin, m.species()) ? origin : null;
    }

    /** The Champion {@code m} is bound to, or null while its choice is open. */
    public static Identifier line(PartyMemberView m) { return m.lineId(); }

    /** The forms {@code m} could take now at its level: a Baby II's Rookies, a Rookie's Champions (its line once bound). */
    public static List<Identifier> ready(PartyMemberView m) {
        if (!EvolutionRules.chooser(m.species())) return List.of();
        Identifier line = line(m);
        return EvolutionRules.targets(m.species(), m.level()).stream().filter(t -> line == null || t.equals(line)).toList();
    }

    /** A Champion of the same Rookie as {@code m}'s line: its first digivolution took the other way. */
    public static boolean blocked(PartyMemberView m, Identifier id) {
        Identifier line = m == null ? null : line(m);
        if (line == null || line.equals(id)) return false;
        Branch a = parent(id), b = parent(line);
        return a != null && b != null && a.id().equals(b.id()) && EvolutionRules.champion(id);
    }

    /** A partner at rest with a form it can take now. */
    public static boolean offers(PartyMemberView m) {
        return m != null && inParty(m) && "RESTING".equals(m.phase()) && !ready(m).isEmpty();
    }

    /** Before its first digivolution the sheet is a choice: DIGIVOLVE asks first, because the answer binds it. */
    public static boolean chooses(PartyMemberView m) { return offers(m) && line(m) == null; }

    /** The bits of {@code m}'s ready routes, by index among its own routes. */
    public static int readyMask(PartyMemberView m) {
        if (!EvolutionRules.chooser(m.species())) return 0;
        List<Evolution> routes = EvolutionRules.routes(m.species());
        List<Identifier> ready = ready(m);
        int mask = 0;
        for (int i = 0; i < Math.min(31, routes.size()); i++) if (ready.contains(routes.get(i).target())) mask |= 1 << i;
        return mask;
    }

    /** The forms {@code m} can choose now that the tamer has not seen ready yet ({@code seen}: bits looked at on this client). */
    public static Set<Identifier> fresh(PartyMemberView m, int seen) {
        if (line(m) != null || !EvolutionRules.chooser(m.species())) return Set.of();
        List<Evolution> routes = EvolutionRules.routes(m.species());
        List<Identifier> ready = ready(m);
        Set<Identifier> fresh = new HashSet<>();
        for (int i = 0; i < Math.min(31, routes.size()); i++) {
            Identifier t = routes.get(i).target();
            if (ready.contains(t) && !m.noticed(i) && (seen & 1 << i) == 0) fresh.add(t);
        }
        return fresh;
    }

    /** A digivolution {@code m} has not been shown yet: its balloon, the dots, and the NEW tags on its first look. */
    public static boolean news(PartyMemberView m, int seen) { return !fresh(m, seen).isEmpty(); }

    /** The form its tree opens on: a Champion's Rookie form while it has none, its line, a fresh form, a ready one, its first step. */
    public static Identifier focus(PartyMemberView m, Set<Identifier> fresh) {
        if (m.originRequired()) { List<Identifier> o = EvolutionRules.origins(m.species()); if (!o.isEmpty()) return o.getFirst(); }
        Identifier line = line(m);
        if (line != null && EvolutionRules.rookie(m.species())) return line;
        List<Identifier> ready = ready(m);
        for (Identifier t : ready) if (fresh.contains(t)) return t;
        if (!ready.isEmpty()) return ready.getFirst();
        List<Branch> kids = kids(m.species());
        return kids.isEmpty() ? m.species() : kids.getFirst().id();
    }

    /**
     * The forms that belong to {@code m}: its family's first form, its Rookie, that Rookie's forms, itself; for a Baby II
     * the whole family, all of it still ahead. The rest is dimmed.
     */
    public static Set<Identifier> scope(PartyMemberView m) {
        if (EvolutionRules.baby(m.species())) return new HashSet<>(com.digicube.digimon.DigimonFamilies.members(m.species()));
        Identifier rookie = rookie(m);
        if (rookie == null && m.originRequired()) { List<Identifier> o = EvolutionRules.origins(m.species()); rookie = o.isEmpty() ? null : o.getFirst(); }
        Set<Identifier> scope = new HashSet<>(List.of(family(m.species()), m.species()));
        if (rookie != null) { scope.add(rookie); kids(rookie).forEach(k -> scope.add(k.id())); }
        return scope;
    }

    /** Whether the form is one {@code m} has been: its line, or the Rookie form it digivolved from. */
    public static boolean reached(PartyMemberView m, Identifier id) {
        return id.equals(line(m)) && !id.equals(m.species()) || evolved(m) && id.equals(origin(m));
    }

    // ---------- what the sheet says ----------

    /**
     * One sentence about a form: a translation key under {@code gui.digicube.tree.} and its arguments (species ids
     * become names, unseen ones a phrase that gives nothing away; a {@link CommandWheelReadout.Reason} becomes the
     * wheel's words). {@code amber} when it speaks of something to do now; {@code record} adds that an unseen form is
     * recorded by meeting it.
     */
    public record Sentence(String key, List<Object> args, boolean amber, boolean record) {
        static Sentence of(String key, boolean amber, Object... args) { return new Sentence(key, List.of(args), amber, false); }
        Sentence recorded() { return new Sentence(key, args, amber, true); }
    }

    /**
     * What the sheet says about {@code form}: for {@code m} when there is one, else for the species. {@code block} is
     * why {@code m} cannot digivolve right now ({@link CommandWheelReadout.Reason#NONE} when it can), as the wheel says it.
     */
    public static Sentence sentence(PartyMemberView m, Identifier form, Predicate<Identifier> known, CommandWheelReadout.Reason block) {
        Branch up = parent(form);
        boolean seen = known.test(form);
        Sentence s;
        boolean told = false;
        if (m == null) {
            s = up == null ? Sentence.of("first", false) : Sentence.of(EvolutionRules.rookie(form) ? "grows_from" : "digivolves_from", false, up.id(), up.level());
        } else if (form.equals(m.species())) {
            if (evolved(m)) s = Sentence.of("evolved", false, origin(m));
            else if (m.originRequired()) s = Sentence.of(EvolutionRules.origins(m.species()).isEmpty() ? "no_rookie_route" : "no_rookie", true);
            else if (chooses(m)) s = Sentence.of(EvolutionRules.baby(m.species()) ? "choose_growth" : "choose", true);
            else if (line(m) != null && EvolutionRules.rookie(m.species())) s = Sentence.of("bound", false, line(m));
            else s = Sentence.of(kids(m.species()).isEmpty() ? "no_route" : "current", false);
        } else if (m.originRequired() && EvolutionRules.origins(m.species()).contains(form)) {
            s = Sentence.of("set", true, form, m.level(), m.species());
        } else if (blocked(m, form)) {
            s = Sentence.of("blocked", false, line(m));
            told = true;
        } else if (up != null && up.id().equals(m.species())) {
            if (EvolutionRules.chooser(m.species()) && m.level() >= up.level()) {
                told = true;
                boolean chosen = form.equals(line(m)), growth = EvolutionRules.baby(m.species());
                if (!inParty(m)) s = Sentence.of(growth ? "ready_reserve_growth" : "ready_reserve", true);
                else if (block != CommandWheelReadout.Reason.NONE) s = Sentence.of("ready_but", true, block);
                else if (!seen) s = Sentence.of(growth ? "blind_growth" : "blind", true);
                else s = Sentence.of(growth ? "grow" : chosen ? "digivolve_chosen" : "digivolve", true, form);
            } else {
                int left = up.level() - m.level();
                s = Sentence.of((EvolutionRules.rookie(form) ? "grows_at" : "digivolves_at") + (left == 1 ? "_one" : ""), false, up.level(), left);
            }
        } else if (evolved(m) && form.equals(origin(m))) s = Sentence.of("rookie_form", false);
        else if (evolved(m) && up != null && up.id().equals(origin(m))) s = Sentence.of("also", false, origin(m), up.level());
        else if (ancestor(form, m.species())) s = Sentence.of("earlier", false);
        else if (up != null && ancestor(m.species(), up.id())) s = Sentence.of("later", false, up.id(), up.level());
        else s = Sentence.of("other", false);
        return !seen && !told ? s.recorded() : s;
    }
}
