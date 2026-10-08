package com.digicube.fabric.client.digivice;

import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.EvolutionRules;
import com.digicube.digimon.Progression;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.fabric.client.party.CommandWheelReadout;
import com.digicube.fabric.client.party.CommandWheelScreen;
import com.digicube.fabric.client.party.PartyHudReadout;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.digicube.fabric.client.digivice.DigiviceScreen.DISPLAY_HEIGHT;
import static com.digicube.fabric.client.digivice.DigiviceScreen.DISPLAY_WIDTH;
import static com.digicube.fabric.client.digivice.DigiviceScreen.DISPLAY_X;
import static com.digicube.fabric.client.digivice.DigiviceScreen.DISPLAY_Y;
import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * The DIGIVOLUTION sheet: a Digimon's family tree over the whole display, opened from its card in the Digispace,
 * from the command wheel's DIGIVOLVE, or for a species from the Analyzer. The column on the right says what the form
 * in focus means for this Digimon, what it fights with and, for a partner, what a digivolution spends.
 *
 * <p>Before a partner's first digivolution the sheet is a choice: the forms it can take now breathe amber, the one in
 * focus wears a white ring, and DIGIVOLVE asks whether the tamer is sure, since the choice cannot be undone. Once
 * bound, the other Champion carries a padlock and DIGIVOLVE goes straight. A Baby II's growth into a Rookie is the
 * same choice, asked the same way, and spends no DigiSoul.
 */
final class EvolutionSheet {
    /** Where BACK goes: the island, the world (the wheel opened the Digivice for this), or the Analyzer. */
    enum From { CARD, WHEEL, ANALYZER }

    private static final int READOUT_WIDTH = 116, RISE = 5;

    private final DigiviceScreen screen;
    private UUID member;
    private Identifier species;
    private From from = From.CARD;
    private boolean open;
    private int shown, lastShown;
    private Identifier focus, unfolded, asking;
    /** The forms that were news when this tree opened: they wear NEW for this one look. */
    private Set<Identifier> fresh = Set.of();

    EvolutionSheet(DigiviceScreen screen) { this.screen = screen; }

    boolean open() { return open; }
    /** Covering the display: the tabs and the island under it take no input. */
    boolean covering() { return open || shown > 0; }
    From from() { return from; }

    /** Opens {@code m}'s tree; its news becomes this look's NEW tags, and the server hears it was seen. */
    void open(PartyMemberView m, From from) {
        member = m.id(); species = null; this.from = from; open = true; unfolded = null; asking = null;
        fresh = EvolutionTree.fresh(m, screen.seen(m.id()));
        focus = EvolutionTree.focus(m, fresh);
        if (!fresh.isEmpty()) {
            screen.noticed(m.id(), EvolutionTree.readyMask(m));
            screen.send(new PartyActionPayload(PartyActionPayload.NOTICE, m.id(), 0));
        }
    }

    void open(Identifier species) {
        member = null; this.species = species; from = From.ANALYZER; open = true; unfolded = null; asking = null; fresh = Set.of();
        focus = species;
    }

    void close() { open = false; asking = null; }

    /** BACK: off the question first, then to where the sheet came from; opened by the wheel, straight back to the world. */
    void back() {
        if (asking != null) { asking = null; return; }
        if (from == From.WHEEL) screen.onClose(); else close();
    }

    void tick() {
        lastShown = shown;
        shown = Math.clamp(shown + (open ? 1 : -1), 0, RISE);
        PartyMemberView m = member();
        if (open && member != null && m == null) close();
        if (asking != null && (m == null || !EvolutionTree.chooses(m))) asking = null;
    }

    private PartyMemberView member() { return member == null ? null : screen.member(member); }

    // ---------- input ----------

    /** In a choice the arrows walk the ready forms and Enter asks; asked, Enter digivolves. Esc is BACK. */
    boolean keyPressed(int key) {
        if (!open) return false;
        if (key == InputConstants.KEY_ESCAPE) { back(); return true; }
        PartyMemberView m = member();
        if (m == null) return true;
        boolean enter = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
        if (asking != null) { if (enter) digivolve(m, asking); return true; }
        List<Identifier> ready = EvolutionTree.offers(m) ? EvolutionTree.ready(m) : List.of();
        if (ready.isEmpty()) return true;
        int at = ready.indexOf(focus);
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN || key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
            int step = key == InputConstants.KEY_UP || key == InputConstants.KEY_LEFT ? -1 : 1;
            focus = ready.get(at < 0 ? 0 : Math.floorMod(at + step, ready.size()));
            unfolded = null;
        } else if (enter && at >= 0) take(m, focus);
        return true;
    }

    private CommandWheelReadout.Reason block(PartyMemberView m) {
        if (m == null || !EvolutionTree.inParty(m)) return CommandWheelReadout.Reason.NO_SPACE;
        boolean route = EvolutionRules.target(m.species(), m.level()).isPresent();
        return CommandWheelReadout.modules(m, screen.client().snapshotAge(), route)[CommandWheelReadout.BOTTOM_RIGHT].reason();
    }

    /** DIGIVOLVE on a form: the first time it asks, because the answer binds the Digimon; bound already, it digivolves. */
    private void take(PartyMemberView m, Identifier form) {
        if (block(m) != CommandWheelReadout.Reason.NONE || !EvolutionTree.ready(m).contains(form)) return;
        if (EvolutionTree.chooses(m)) { asking = form; focus = form; }
        else digivolve(m, form);
    }

    /** Sends the digivolution and puts the Digivice away, so the change is seen in the world. */
    private void digivolve(PartyMemberView m, Identifier form) {
        asking = null;
        screen.send(new PartyActionPayload(PartyActionPayload.EVOLVE, m.id(), PartyActionPayload.routeValue(m.species(), form), m.generation(), m.sequence()));
        screen.onClose();
    }

    // ---------- words ----------

    private static String name(Identifier id) {
        DigimonSpecies species = DigimonSpeciesRegistry.get(id).orElse(null);
        return species == null ? id.getPath() : Component.translatable(species.translationKey()).getString();
    }

    private String shownName(Identifier id) {
        return screen.knows(id) ? name(id) : Component.translatable("gui.digicube.tree.unknown").getString();
    }

    private String text(EvolutionTree.Sentence s, PartyMemberView m) {
        Object[] args = s.args().stream().map(a -> a instanceof Identifier id ? shownName(id)
                : a instanceof CommandWheelReadout.Reason reason ? CommandWheelScreen.reasonText(reason, m, screen.client().snapshotAge()) : a).toArray();
        String text = Component.translatable("gui.digicube.tree." + s.key(), args).getString();
        if (s.record()) text += " " + Component.translatable("gui.digicube.tree.record").getString();
        return text.toUpperCase(Locale.ROOT);
    }

    private static List<String> wrap(Font font, String text, int width) {
        List<String> lines = new ArrayList<>();
        for (FormattedText part : font.splitIgnoringLanguage(FormattedText.of(text), width)) lines.add(part.getString());
        return lines;
    }

    // ---------- drawing ----------

    private static float ease(float t) { return t * t * (3 - 2 * t); }

    void draw(GuiGraphicsExtractor g) {
        if (!covering() && lastShown == 0) return;
        PartyMemberView m = member();
        if (m == null && species == null) return;
        Font font = screen.font();
        float e = ease(Math.clamp((lastShown + (shown - lastShown) * screen.partial()) / RISE, 0, 1));
        int sx = DISPLAY_X + 3, sw = DISPLAY_WIDTH - 6, sh = DISPLAY_HEIGHT - 6, sy = DISPLAY_Y + 3 + Math.round((1 - e) * (sh + 8));
        boolean live = shown >= RISE - 2, ask = asking != null && m != null;
        rect(g, DISPLAY_X, DISPLAY_Y, DISPLAY_WIDTH, DISPLAY_HEIGHT, withAlpha(DigiTheme.VOID, Math.round(0x90 * e)));
        screen.hit(DISPLAY_X, DISPLAY_Y, DISPLAY_WIDTH, DISPLAY_HEIGHT, () -> {});
        screen.wheel(DISPLAY_X, DISPLAY_Y, DISPLAY_WIDTH, DISPLAY_HEIGHT, steps -> {});
        // under the question nothing reacts to the pointer
        screen.blind(ask);
        DigiPanels.frame(g, sx, sy, sw, sh, withAlpha(DigiTheme.PANEL, 0xFA), DigiTheme.EDGE, 3);
        rect(g, sx + 4, sy, sw - 8, 1, DigiTheme.AMBER);

        String back = DigiviceScreen.upper(Component.translatable("gui.digicube.tree.back"));
        int bw = font.width(back) + 14;
        DigiviceKit.keyButton(g, font, sx + 5, sy + 5, bw, 14, back, false, false, DigiviceKit.state(true, screen.over(sx + 5, sy + 5, bw, 14), screen.pressed("tree_back")));
        if (live) screen.hit(sx + 5, sy + 5, bw, 14, "tree_back", this::back);
        int hx = sx + 12 + bw;
        if (m != null) {
            String nm = (m.nickname().isEmpty() ? name(m.species()) : m.nickname()).toUpperCase(Locale.ROOT);
            g.text(font, nm, hx, sy + 8, DigiTheme.WHITE, true);
            hx += font.width(nm) + 6;
            if (!m.nickname().isEmpty()) { String sn = name(m.species()).toUpperCase(Locale.ROOT); g.text(font, sn, hx, sy + 8, DigiTheme.MUTED, false); hx += font.width(sn) + 6; }
            DigiPanels.readout(g, hx, sy + 10, "L" + m.level(), DigiTheme.AMBER);
        } else {
            String nm = name(species).toUpperCase(Locale.ROOT);
            g.text(font, nm, hx, sy + 8, DigiTheme.WHITE, true);
            g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.tree.in_analyzer")), hx + font.width(nm) + 6, sy + 8, DigiTheme.MUTED, false);
        }
        boolean choosing = EvolutionTree.chooses(m);
        String title = DigiviceScreen.upper(Component.translatable(choosing ? "gui.digicube.wheel.digivolve" : "gui.digicube.tree.title"));
        int tc = choosing ? DigiTheme.AMBER : DigiTheme.CYAN, tx = sx + sw - 8 - font.width(title);
        if (choosing) DigiviceArt.chevronIcon(g, tx - 15, sy + 6, tc); else DigiviceArt.treeIcon(g, tx - 15, sy + 6, tc);
        g.text(font, title, tx, sy + 8, tc, false);
        rect(g, sx + 5, sy + 22, sw - 10, 1, DigiTheme.EDGE_DIM);

        Identifier root = EvolutionTree.family(m != null ? m.species() : species);
        Identifier openBranch = EvolutionTree.openBranch(m, species, root, unfolded);
        tree(g, font, m, root, sx + 10, sy + 28, openBranch, live && !ask);
        readout(g, font, m, focus, sx + sw - 6 - READOUT_WIDTH, sy + 28, READOUT_WIDTH, sh - 34, live && !ask);
        screen.blind(false);
        if (ask) confirm(g, font, m, asking);
    }

    private enum Kind { PLAIN, NEXT, LIVE }
    private record Style(Kind kind, boolean ready, float fraction, boolean dim) {}

    private static int linkColor(Style s) {
        return s.ready() || s.kind() == Kind.LIVE ? DigiTheme.AMBER : s.kind() == Kind.NEXT ? DigiTheme.WHITE : s.dim() ? DigiTheme.EDGE_DIM : DigiTheme.EDGE_LIGHT;
    }

    /** The family's forms, the arrows between them with the level each step needs, and where {@code m} stands. */
    private void tree(GuiGraphicsExtractor g, Font font, PartyMemberView m, Identifier root, int x, int y, Identifier openBranch, boolean live) {
        EvolutionTree.Layout layout = EvolutionTree.layout(root, x, y, openBranch);
        Set<Identifier> scope = m == null ? null : EvolutionTree.scope(m);
        List<Identifier> choices = EvolutionTree.offers(m) ? EvolutionTree.ready(m) : List.of();
        List<Identifier> candidates = m != null && m.originRequired() ? EvolutionRules.origins(m.species()) : List.of();
        String[] heads = {"in_training", "rookie", "champion"};
        for (int i = 0; i < heads.length; i++) g.text(font, DigiviceScreen.upper(Component.translatable("gui.digicube.tree." + heads[i])), x + EvolutionTree.COLUMNS[i], y, withAlpha(DigiTheme.MUTED, 0xB0), false);
        for (EvolutionTree.Node fork : layout.forks()) fork(g, font, fork, m, scope);
        float pulse = 0.5F + 0.5F * (float) Math.sin(screen.time() * Math.PI * 2 / DigiTheme.BREATH_TICKS);
        for (EvolutionTree.Node n : layout.all()) {
            boolean seen = screen.knows(n.id());
            String label = seen ? name(n.id()).toUpperCase(Locale.ROOT) : DigiviceScreen.upper(Component.translatable("gui.digicube.tree.no_data"));
            int lw = Math.max(EvolutionTree.NODE, font.width(label)), bx = Math.min(n.x(), n.x() + EvolutionTree.NODE / 2 - lw / 2), bh = EvolutionTree.extent(n);
            boolean hover = live && screen.over(bx, n.y(), lw, bh), choice = choices.contains(n.id());
            boolean locked = EvolutionTree.blocked(m, n.id()), dim = scope != null && !scope.contains(n.id()) || locked;
            node(g, font, n, label, seen, dim, locked, hover, choice, choice && n.id().equals(focus), n.id().equals(focus), candidates.contains(n.id()),
                    m != null && n.id().equals(m.species()), m != null && EvolutionTree.reached(m, n.id()), fresh.contains(n.id()), pulse);
            if (live) {
                Identifier id = n.id();
                if (n.folded() > 0) screen.hit(bx, n.y(), lw, bh, () -> { unfolded = id; focus = id; });
                else screen.hit(bx, n.y(), lw, bh, () -> focus = id);
            }
        }
    }

    private Style style(PartyMemberView m, Set<Identifier> scope, EvolutionTree.Node from, EvolutionTree.Node to) {
        if (EvolutionTree.blocked(m, to.id())) return new Style(Kind.PLAIN, false, 0, true);
        if (m != null && from.id().equals(m.species()))
            return new Style(Kind.NEXT, EvolutionRules.chooser(m.species()) && m.level() >= to.level(), to.level() <= 0 ? 1 : (float) m.level() / to.level(), false);
        if (m != null && EvolutionTree.evolved(m) && to.id().equals(m.species()) && from.id().equals(EvolutionTree.origin(m))) return new Style(Kind.LIVE, false, 1, false);
        return new Style(Kind.PLAIN, false, 0, scope != null && !scope.contains(to.id()));
    }

    private void fork(GuiGraphicsExtractor g, Font font, EvolutionTree.Node p, PartyMemberView m, Set<Identifier> scope) {
        int sx = p.x() + EvolutionTree.NODE, sy = p.y() + EvolutionTree.NODE / 2, fx = sx + 12;
        List<Style> styles = p.kids().stream().map(k -> style(m, scope, p, k)).toList();
        Style best = styles.getFirst();
        for (Style s : styles) if (rank(s) > rank(best)) best = s;
        int c = linkColor(best), top = sy, bottom = sy;
        for (EvolutionTree.Node k : p.kids()) { top = Math.min(top, k.y() + EvolutionTree.NODE / 2); bottom = Math.max(bottom, k.y() + EvolutionTree.NODE / 2); }
        rect(g, sx + 1, sy, fx - sx - 1, 1, c);
        rect(g, fx, top, 1, bottom - top + 1, c);
        for (int i = 0; i < p.kids().size(); i++) { EvolutionTree.Node k = p.kids().get(i); branch(g, fx, k.x() - 2, k.y() + EvolutionTree.NODE / 2, k.level(), styles.get(i)); }
    }

    private static int rank(Style s) { return s.ready() || s.kind() == Kind.LIVE ? 3 : s.kind() == Kind.NEXT ? 2 : s.dim() ? 0 : 1; }

    /** One arrow into a form with the level it needs: dashed with a progress bar while it is the next step, amber once ready. */
    private static void branch(GuiGraphicsExtractor g, int x0, int x1, int y, int level, Style s) {
        int c = linkColor(s), len = x1 - x0;
        if (s.kind() == Kind.NEXT && !s.ready()) for (int k = 1; k < len - 3; k += 3) rect(g, x0 + k, y, 2, 1, c);
        else rect(g, x0 + 1, y, len - 4, 1, c);
        rect(g, x1 - 3, y - 2, 1, 5, c); rect(g, x1 - 2, y - 1, 1, 3, c); rect(g, x1 - 1, y, 1, 1, c);
        if (s.kind() == Kind.LIVE) { rect(g, x0 + 1, y, 1, 1, c); rect(g, x0 + 2, y - 1, 1, 3, c); rect(g, x0 + 3, y - 2, 1, 5, c); }
        String lv = "L" + level;
        int tw = lv.length() * 4 - 1;
        DigiPanels.readout(g, x0 + 1 + (len - 1 - tw) / 2, y - 7, lv, s.kind() == Kind.PLAIN ? withAlpha(DigiTheme.MUTED, s.dim() ? 0x50 : 0xFF) : c);
        if (s.kind() == Kind.NEXT) {
            int bw = len - 5;
            rect(g, x0 + 1, y + 3, bw, 2, withAlpha(DigiTheme.EDGE_DIM, 0xD0));
            rect(g, x0 + 1, y + 3, Math.round(bw * Math.min(1, s.fraction())), 2, s.ready() ? DigiTheme.AMBER : DigiTheme.CYAN);
        }
    }

    /** One form: its sprite or its shape, a frame that says what it is to this Digimon, its name under it. */
    private void node(GuiGraphicsExtractor g, Font font, EvolutionTree.Node n, String label, boolean seen, boolean dim, boolean locked, boolean hover, boolean choice,
                      boolean selected, boolean focused, boolean candidate, boolean current, boolean reached, boolean isNew, float pulse) {
        int x = n.x(), y = n.y(), size = EvolutionTree.NODE;
        rect(g, x, y, size, size, withAlpha(DigiTheme.VOID, dim ? 0xA0 : 0xF0));
        if (!dim) DigiPanels.grid(g, x + 1, y + 1, size - 2, size - 2, 8, withAlpha(DigiTheme.GRID, 0x2C));
        if (seen) DigiPanels.icon(g, n.id(), x + 1, y + 1, size - 2, dim ? 0x48FFFFFF : 0xFFFFFFFF);
        else if (!DigiviceArt.silhouette(g, n.id(), x + 1, y + 1, size - 2, withAlpha(0xFF244775, dim ? 0x60 : 0xFF)))
            g.text(font, "?", x + 15, y + 13, DigiTheme.EDGE_LIGHT, false);
        int edge = focused ? DigiTheme.WHITE : choice ? DigiTheme.mix(DigiTheme.EDGE, DigiTheme.AMBER, 0.55F + 0.45F * pulse) : reached ? DigiTheme.CYAN
                : hover ? DigiTheme.EDGE_LIGHT : dim ? DigiTheme.EDGE_DIM : DigiTheme.EDGE;
        DigiPanels.frame(g, x, y, size, size, 0, edge, 1);
        // the form DIGIVOLVE would take: a white ring two units clear of the frame
        if (selected) DigiPanels.frame(g, x - 3, y - 3, size + 6, size + 6, 0, DigiTheme.WHITE, 2);
        // a Rookie this Champion can take as its Rookie form: a dashed amber slot round it
        else if (candidate) {
            int c = withAlpha(DigiTheme.AMBER, 0x90 + (int) (0x6F * pulse));
            for (int k = 0; k < size + 6; k += 4) { rect(g, x - 3 + k, y - 3, 2, 1, c); rect(g, x - 3 + k, y + size + 2, 2, 1, c); rect(g, x - 3, y - 3 + k, 1, 2, c); rect(g, x + size + 2, y - 3 + k, 1, 2, c); }
        }
        if (current) DigiPanels.brackets(g, x, y, size, size, 6, 3, DigiTheme.AMBER);
        if (reached) {
            rect(g, x + 24, y + 1, 9, 8, DigiTheme.CYAN);
            int[][] tick = {{1, 4}, {2, 5}, {3, 4}, {4, 3}, {5, 2}, {6, 1}};
            for (int[] p : tick) rect(g, x + 25 + p[0], y + 1 + p[1], 1, 1, DigiTheme.VOID);
        }
        // blocked: a padlock where the tick would be
        if (locked) DigiviceArt.lock(g, x + 24, y + 1, DigiTheme.VOID, DigiTheme.MUTED);
        if (isNew) { String tag = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.new")); DigiviceKit.newTag(g, font, tag, x + (size - DigiviceKit.newTagWidth(font, tag)) / 2, y + size - 11); }
        int nc = current ? DigiTheme.AMBER : dim ? withAlpha(seen ? DigiTheme.MUTED : DigiTheme.EDGE_LIGHT, 0x60) : !seen ? DigiTheme.EDGE_LIGHT : DigiTheme.WHITE;
        g.text(font, label, x + size / 2 - font.width(label) / 2, y + 38, nc, false);
        if (n.folded() > 0) {
            String note = DigiviceScreen.upper(Component.translatable(n.folded() == 1 ? "gui.digicube.tree.more_one" : "gui.digicube.tree.more", n.folded()));
            g.text(font, note, x + size / 2 - font.width(note) / 2, y + 47, hover ? DigiTheme.WHITE : withAlpha(dim ? DigiTheme.MUTED : DigiTheme.EDGE_LIGHT, dim ? 0x90 : 0xFF), false);
        }
    }

    /** The column on the right: the form in focus, what it means here, its attacks, a partner's DigiSoul and the keys. */
    private void readout(GuiGraphicsExtractor g, Font font, PartyMemberView m, Identifier f, int x, int y, int w, int h, boolean live) {
        if (f == null) return;
        DigimonSpecies d = DigimonSpeciesRegistry.get(f).orElse(null);
        if (d == null) return;
        boolean seen = screen.knows(f), partner = m != null && EvolutionTree.inParty(m);
        boolean candidate = m != null && m.originRequired() && EvolutionRules.origins(m.species()).contains(f);
        CommandWheelReadout.Reason block = block(m);
        boolean choice = EvolutionTree.offers(m) && EvolutionTree.ready(m).contains(f), go = choice && block == CommandWheelReadout.Reason.NONE;
        DigiPanels.frame(g, x, y, w, h, withAlpha(DigiTheme.PANEL, 0xE8), DigiTheme.EDGE_DIM, 2);
        rect(g, x + 3, y, w - 6, 1, withAlpha(DigiTheme.CYAN, 0x70));
        String nm = seen ? name(f).toUpperCase(Locale.ROOT) : DigiviceScreen.upper(Component.translatable("gui.digicube.tree.no_data"));
        g.text(font, DigiPanels.shortText(font, Component.literal(nm), w - 12), x + 6, y + 6, seen ? DigiTheme.WHITE : DigiTheme.EDGE_LIGHT, true);
        String stage = DigiviceScreen.upper(Component.translatable("digicube.stage." + d.stage().getId()));
        g.text(font, stage, x + 6, y + 16, DigiTheme.MUTED, false);
        if (seen) DigiviceArt.mark(g, d.attribute(), x + 10 + font.width(stage), y + 16, DigiviceArt.color(d.attribute()));
        rect(g, x + 6, y + 26, w - 12, 1, DigiTheme.EDGE_DIM);

        // keys from the bottom up, the primary one lowest
        int ky = y + h - 21;
        if (candidate) {
            String set = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.origin_set"));
            List<Identifier> origins = EvolutionRules.origins(m.species());
            int index = origins.indexOf(f);
            key(g, font, x + 6, ky, w - 12, set, true, true, "tree_set", live, () -> screen.send(new PartyActionPayload(PartyActionPayload.ORIGIN, m.id(), index, m.generation(), m.sequence())));
            ky -= 19;
        } else if (partner && EvolutionRules.chooser(m.species()) && !EvolutionRules.routes(m.species()).isEmpty()) {
            String dg = DigiviceScreen.upper(Component.translatable("gui.digicube.wheel.digivolve"));
            key(g, font, x + 6, ky, w - 12, dg, true, go, "tree_digivolve", live, () -> take(m, f));
            ky -= 19;
        }
        String an = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.analyze"));
        key(g, font, x + 6, ky, w - 12, an, false, seen, "tree_analyze", live, () -> { close(); screen.analyze(f); });
        int bottom = ky - 6;

        // a partner's DigiSoul, and what a digivolution would spend of it
        if (partner && (EvolutionRules.rookie(m.species()) || EvolutionTree.evolved(m))) {
            int sy = bottom - 18;
            DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.tree.digisoul")), x + 6, sy, w - 12);
            soul(g, font, x + 6, sy + 11, w - 12, m, go);
            if (go) {
                String fee = "-" + Progression.DIGISOUL_FEE * 100 / Progression.DIGISOUL_CAPACITY + "%";
                int fw = font.width(fee);
                rect(g, x + w - 9 - fw, sy - 1, fw + 4, 9, withAlpha(DigiTheme.PANEL, 0xF0));
                g.text(font, fee, x + w - 7 - fw, sy, DigiTheme.AMBER, false);
            }
            bottom = sy - 5;
        }
        // what the form fights with: a choice is easier with its attacks in view
        List<DigimonAttack> attacks = d.attacks();
        if (seen && !attacks.isEmpty()) {
            int n = Math.min(2, attacks.size()), ay0 = bottom - 10 - n * 12;
            DigiviceKit.label(g, font, DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.attacks")), x + 6, ay0, w - 12);
            for (int i = 0; i < n; i++) {
                int ay = ay0 + 10 + i * 12;
                Identifier id = attacks.get(i).id();
                rect(g, x + 6, ay, w - 12, 11, withAlpha(DigiTheme.VOID, 0x90));
                rect(g, x + 6, ay, 2, 11, DigiviceArt.color(d.attribute()));
                g.text(font, DigiPanels.shortText(font, Component.literal(DigiviceScreen.upper(Component.translatable("attack." + id.getNamespace() + "." + id.getPath()))), w - 20), x + 12, ay + 2, DigiTheme.WHITE, false);
            }
            bottom = ay0 - 5;
        }
        EvolutionTree.Sentence sentence = EvolutionTree.sentence(m, f, screen::knows, block);
        int rows = Math.max(1, (bottom - (y + 31)) / 9);
        List<String> lines = wrap(font, text(sentence, m), w - 12);
        for (int i = 0; i < Math.min(rows, lines.size()); i++) g.text(font, lines.get(i), x + 6, y + 31 + i * 9, sentence.amber() ? DigiTheme.AMBER : DigiTheme.MUTED, false);
    }

    private void key(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label, boolean primary, boolean on, String press, boolean live, Runnable action) {
        DigiviceKit.keyButton(g, font, x, y, w, 15, label, primary, false, DigiviceKit.state(on, screen.over(x, y, w, 15), screen.pressed(press)));
        if (on && live) screen.hit(x, y, w, 15, press, action);
    }

    /** DigiSoul in ten cells; while a digivolution is in reach, the cell it would spend blinks amber. */
    private void soul(GuiGraphicsExtractor g, Font font, int x, int y, int w, PartyMemberView m, boolean spend) {
        int cells = 10, cw = (w - 26) / cells, soul = PartyHudReadout.soul(m, screen.client().snapshotAge());
        float lit = soul * (float) cells / Progression.DIGISOUL_CAPACITY, fee = Progression.DIGISOUL_FEE * (float) cells / Progression.DIGISOUL_CAPACITY;
        float blink = 0.5F + 0.5F * (float) Math.sin(screen.time() * Math.PI * 2 / 16);
        for (int i = 0; i < cells; i++) {
            int cx = x + i * cw;
            float part = Math.clamp(lit - i, 0, 1);
            rect(g, cx, y, cw - 1, 5, withAlpha(DigiTheme.EDGE_DIM, 0xC0));
            if (part <= 0) continue;
            boolean spent = spend && i + 1 > lit - fee + 0.001F;
            int c = spent ? withAlpha(DigiTheme.AMBER, 0x70 + (int) (0x8F * blink)) : soul >= Progression.DIGISOUL_MINIMUM || EvolutionTree.evolved(m) ? DigiTheme.DATA_LIGHT : DigiTheme.MUTED;
            rect(g, cx, y, Math.max(1, Math.round((cw - 1) * part)), 5, c);
        }
        String pct = soul * 100 / Progression.DIGISOUL_CAPACITY + "%";
        g.text(font, pct, x + w - font.width(pct), y - 1, spend ? DigiTheme.AMBER : DigiTheme.WHITE, false);
    }

    /** Are you sure: the Digimon, the form, and that the choice cannot be undone. BACK returns to the choice. */
    private void confirm(GuiGraphicsExtractor g, Font font, PartyMemberView m, Identifier f) {
        int w = 236, inner = w - 16;
        String who = (m.nickname().isEmpty() ? name(m.species()) : m.nickname());
        String into = EvolutionRules.baby(m.species()) ? "gui.digicube.tree.confirm.grows" : "gui.digicube.tree.confirm.into";
        List<String> say = balanced(font, DigiviceScreen.upper(Component.translatable(into, who, shownName(f))), inner);
        List<String> warn = balanced(font, DigiviceScreen.upper(Component.translatable("gui.digicube.tree.confirm.final")), inner);
        int h = 84 + (say.size() + warn.size()) * 9 + 27, x = DISPLAY_X + (DISPLAY_WIDTH - w) / 2, y = DISPLAY_Y + (DISPLAY_HEIGHT - h) / 2;
        rect(g, DISPLAY_X, DISPLAY_Y, DISPLAY_WIDTH, DISPLAY_HEIGHT, withAlpha(DigiTheme.VOID, 0xD0));
        screen.hit(DISPLAY_X, DISPLAY_Y, DISPLAY_WIDTH, DISPLAY_HEIGHT, () -> {});
        DigiPanels.frame(g, x, y, w, h, withAlpha(DigiTheme.PANEL, 0xFC), DigiTheme.EDGE, 3);
        rect(g, x + 4, y, w - 8, 1, DigiTheme.AMBER);
        String q = DigiviceScreen.upper(Component.translatable("gui.digicube.tree.confirm.title"));
        g.text(font, q, x + (w - font.width(q)) / 2, y + 8, DigiTheme.WHITE, true);
        rect(g, x + 6, y + 20, w - 12, 1, DigiTheme.EDGE_DIM);
        // the Digimon as it is, an arrow, the form it would take
        int ax = x + w / 2, ny = y + 27;
        float pulse = 0.5F + 0.5F * (float) Math.sin(screen.time() * Math.PI * 2 / DigiTheme.BREATH_TICKS);
        node(g, font, new EvolutionTree.Node(m.species(), ax - 57, ny, 1, 0, 0, List.of()), name(m.species()).toUpperCase(Locale.ROOT), screen.knows(m.species()), false, false, false, false, false, false, false, true, false, false, pulse);
        node(g, font, new EvolutionTree.Node(f, ax + 23, ny, 2, 0, 0, List.of()), screen.knows(f) ? name(f).toUpperCase(Locale.ROOT) : DigiviceScreen.upper(Component.translatable("gui.digicube.tree.no_data")),
                screen.knows(f), false, false, false, true, false, false, false, false, false, false, pulse);
        rect(g, ax - 16, ny + 17, 30, 1, DigiTheme.AMBER); rect(g, ax + 13, ny + 15, 1, 5, DigiTheme.AMBER); rect(g, ax + 14, ny + 16, 1, 3, DigiTheme.AMBER); rect(g, ax + 15, ny + 17, 1, 1, DigiTheme.AMBER);
        int ty = y + 78;
        for (String line : say) { g.text(font, line, x + (w - font.width(line)) / 2, ty, DigiTheme.WHITE, false); ty += 9; }
        for (String line : warn) { g.text(font, line, x + (w - font.width(line)) / 2, ty, DigiTheme.AMBER, false); ty += 9; }
        int ky = y + h - 22;
        String back = DigiviceScreen.upper(Component.translatable("gui.digicube.tree.back")), dg = DigiviceScreen.upper(Component.translatable("gui.digicube.wheel.digivolve"));
        DigiviceKit.keyButton(g, font, x + 8, ky, 64, 15, back, false, false, DigiviceKit.state(true, screen.over(x + 8, ky, 64, 15), screen.pressed("confirm_back")));
        screen.hit(x + 8, ky, 64, 15, "confirm_back", () -> asking = null);
        DigiviceKit.keyButton(g, font, x + w - 128, ky, 120, 15, dg, true, false, DigiviceKit.state(true, screen.over(x + w - 128, ky, 120, 15), screen.pressed("confirm_digivolve")));
        screen.hit(x + w - 128, ky, 120, 15, "confirm_digivolve", () -> digivolve(m, f));
    }

    /** Centred lines: as many as wrapping needs, as even as they can be. */
    private static List<String> balanced(Font font, String text, int width) {
        int lines = wrap(font, text, width).size(), w = width;
        while (w > 12 && wrap(font, text, w - 6).size() == lines) w -= 6;
        return wrap(font, text, w);
    }

    private static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, color);
    }
}
