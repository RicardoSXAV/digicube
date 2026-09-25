package com.digicube.fabric.client.dev;

import com.digicube.dev.BattleRoster;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Battle Testing's body: side A above side B, each a card listing its kinds, one row per kind with its sprite, a
 * name field that opens the searchable Digimon list, how many of it and its level (click, Shift-click for more, or
 * the mouse wheel over the number). + ADD appends a kind and opens the list for it, the cross removes one, SWAP
 * exchanges the sides. A kind that can carry a rider is tagged RIDE: in the fight a right click mounts it.
 */
final class BattleTestingBody implements DevBody {
    private static final int SIDE_HEADER = 15, ROW = 20, SIDE_PAD = 3, VS_HEIGHT = 22;
    private static final int STEP = 13, LEVEL_BOX = 21, COUNT_BOX = 19, REMOVE = 11;
    private static final int LEVEL_JUMP = 10, COUNT_JUMP = 5;
    private static final String SIDE_A = "a", SIDE_B = "b";

    private final DevClient client;

    BattleTestingBody(DevClient client) { this.client = client; }

    @Override public int height() { return side(client.sideA) + VS_HEIGHT + side(client.sideB); }

    private static int side(List<DevClient.Fighter> fighters) { return SIDE_HEADER + fighters.size() * ROW + SIDE_PAD; }

    @Override public String summary() {
        return (summary(client.sideA) + " VS " + summary(client.sideB)).toUpperCase(Locale.ROOT);
    }

    private static String summary(List<DevClient.Fighter> side) {
        if (side.size() != 1) return total(side) + " DIGIMON";
        DevClient.Fighter only = side.getFirst();
        return (only.count > 1 ? only.count + " " : "") + name(only.species) + " " + only.level;
    }

    @Override public void draw(DevCanvas canvas, int x, int y, int width) {
        int cx = x + 6, cw = width - 12;
        card(canvas, cx, y, cw, SIDE_A);
        int vy = y + side(client.sideA);
        card(canvas, cx, vy + VS_HEIGHT, cw, SIDE_B);

        GuiGraphicsExtractor g = canvas.graphics();
        Font font = canvas.font();
        int mid = x + width / 2;
        g.pose().pushMatrix();
        g.pose().translate(mid - font.width("VS"), vy + 3);
        g.pose().scale(2, 2);
        g.text(font, "VS", 0, 0, DigiTheme.AMBER, true);
        g.pose().popMatrix();
        String hint = DigiPanels.shortText(font, Component.literal("RIDE: RIGHT CLICK"), mid - font.width("VS") - 8 - (cx + 4));
        g.text(font, hint, cx + 4, vy + 8, DigiTheme.withAlpha(DigiTheme.MUTED, 0xB0), false);

        int sw = 34, sx = mid + font.width("VS") + 10, sy = vy + 5;
        boolean hover = canvas.over(sx, sy, sw, 12);
        DigiPanels.frame(g, sx, sy, sw, 12, DigiTheme.withAlpha(hover ? DigiTheme.PANEL_RAISED : DigiTheme.PANEL, 0xF0), 0, 2);
        DigiPanels.bevel(g, sx, sy, sw, 12, 2, hover ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        g.text(font, "SWAP", sx + (sw - font.width("SWAP")) / 2, sy + 2, hover ? DigiTheme.AMBER : DigiTheme.WHITE, false);
        canvas.click(sx, sy, sw, 12, () -> {
            List<DevClient.Fighter> a = new ArrayList<>(client.sideA);
            client.sideA.clear();
            client.sideA.addAll(client.sideB);
            client.sideB.clear();
            client.sideB.addAll(a);
        });
    }

    private void card(DevCanvas canvas, int x, int y, int w, String key) {
        GuiGraphicsExtractor g = canvas.graphics();
        Font font = canvas.font();
        boolean a = key.equals(SIDE_A);
        List<DevClient.Fighter> side = a ? client.sideA : client.sideB;
        int stripe = a ? DigiTheme.TEAL : DigiTheme.RED, h = side(side);

        DigiPanels.frame(g, x - 1, y - 1, w + 2, h + 2, 0, DigiTheme.withAlpha(DigiTheme.VOID, 0xB0), 3);
        DigiPanels.frame(g, x, y, w, h, DigiTheme.withAlpha(DigiTheme.PANEL, 0xE6), 0, 2);
        g.fill(x + 1, y + 2, x + w - 1, y + SIDE_HEADER - 2, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, 0x90));
        DigiPanels.bevel(g, x, y, w, h, 2, DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        g.fill(x + 1, y + 3, x + 4, y + h - 3, DigiTheme.withAlpha(stripe, 0xE0));
        g.fill(x + 1, y + 3, x + 2, y + h - 3, DigiTheme.withAlpha(DigiTheme.WHITE, 0x28));

        String title = a ? "SIDE A" : "SIDE B";
        g.text(font, title, x + 8, y + 4, stripe, true);
        int total = total(side);
        g.text(font, total + " DIGIMON", x + 14 + font.width(title), y + 4, DigiTheme.withAlpha(DigiTheme.MUTED, 0xC0), false);

        // + ADD: a new kind, straight into the Digimon list
        boolean room = side.size() < BattleRoster.MAX_KINDS && total < BattleRoster.MAX_SIDE;
        String add = "+ ADD";
        int aw = font.width(add) + 10, ax = x + w - aw - 5, ay = y + 2;
        boolean addHover = room && canvas.over(ax, ay, aw, 11);
        g.fill(ax, ay, ax + aw, ay + 11, DigiTheme.withAlpha(addHover ? DigiTheme.PANEL_RAISED : DigiTheme.VOID, room ? 0xE0 : 0x70));
        g.outline(ax, ay, aw, 11, addHover ? DigiTheme.AMBER : room ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM);
        g.text(font, add, ax + 5, ay + 2, addHover ? DigiTheme.AMBER : room ? DigiTheme.WHITE : DigiTheme.MUTED, false);
        if (room) canvas.click(ax, ay, aw, 11, () -> {
            DevClient.Fighter fighter = new DevClient.Fighter(null);
            if (!side.isEmpty()) fighter.level = side.getLast().level;
            side.add(fighter);
            int index = side.size() - 1;
            pick(canvas, key, side, fighter, index, x + 30, y + SIDE_HEADER + index * ROW);
        });

        for (int i = 0; i < side.size(); i++) row(canvas, key, side, i, x, y + SIDE_HEADER + i * ROW, w);
    }

    private void row(DevCanvas canvas, String key, List<DevClient.Fighter> side, int index, int x, int y, int w) {
        GuiGraphicsExtractor g = canvas.graphics();
        Font font = canvas.font();
        DevClient.Fighter fighter = side.get(index);
        DigimonSpecies species = fighter.species == null ? null : DigimonSpeciesRegistry.get(fighter.species).orElse(null);
        boolean open = canvas.picking(key + index);
        if (index > 0) g.fill(x + 8, y, x + w - 4, y + 1, DigiTheme.withAlpha(DigiTheme.EDGE_DIM, 0x80));

        int vx = x + 8, vy = y + 2;
        g.fill(vx - 1, vy - 1, vx + 17, vy + 17, DigiTheme.SHADOW);
        g.fill(vx, vy, vx + 16, vy + 16, DigiTheme.withAlpha(DigiTheme.VOID, 0xE6));
        if (species != null) DigiPanels.icon(g, species.id(), vx, vy, 16);
        else g.centeredText(font, "?", vx + 8, vy + 4, DigiTheme.MUTED);

        // right to left: remove, level, count; the name field takes what is left
        int removeX = x + w - 5 - REMOVE, levelX = removeX - 6 - (2 * STEP + LEVEL_BOX), countX = levelX - 18 - (2 * STEP + COUNT_BOX);
        int nx = x + 30, ny = y + 4, nw = countX - 12 - nx;

        boolean hover = canvas.over(nx, ny, nw, 12);
        g.fill(nx, ny, nx + nw, ny + 12, DigiTheme.withAlpha(DigiTheme.VOID, hover || open ? 0xF0 : 0xB0));
        g.outline(nx, ny, nw, 12, open || hover ? DigiTheme.AMBER : DigiTheme.EDGE_DIM);
        int room = nw - 14;
        if (species != null && species.body().mount().isPresent()) {
            int tw = font.width("RIDE");
            g.text(font, "RIDE", nx + nw - 11 - tw, ny + 2, DigiTheme.withAlpha(DigiTheme.CYAN, 0xC0), false);
            room -= tw + 4;
        }
        String label = species == null ? "PICK…" : DigiPanels.shortText(font, Component.translatable(species.translationKey()), room);
        g.text(font, label, nx + 3, ny + 2, species == null ? DigiTheme.AMBER : DigiTheme.WHITE, false);
        DevPanelScreen.chevron(g, nx + nw - 8, ny + 4, true, open || hover ? DigiTheme.AMBER : DigiTheme.MUTED);
        canvas.click(nx, ny, nw, 12, () -> pick(canvas, key, side, fighter, index, nx, y));

        g.text(font, "×", countX - 9, ny + 2, DigiTheme.MUTED, true);
        stepper(canvas, countX, ny, COUNT_BOX, Integer.toString(fighter.count), steps -> count(side, fighter, steps));
        g.text(font, "LV", levelX - 14, ny + 2, DigiTheme.MUTED, true);
        stepper(canvas, levelX, ny, LEVEL_BOX, Integer.toString(fighter.level), steps -> level(fighter, steps));

        if (side.size() > 1) {
            boolean over = canvas.over(removeX, ny, REMOVE, 12);
            g.fill(removeX, ny, removeX + REMOVE, ny + 12, DigiTheme.withAlpha(over ? DigiTheme.PANEL_RAISED : DigiTheme.VOID, 0xB0));
            g.outline(removeX, ny, REMOVE, 12, over ? DigiTheme.RED : DigiTheme.EDGE_DIM);
            g.text(font, "×", removeX + 3, ny + 2, over ? DigiTheme.RED : DigiTheme.MUTED, false);
            canvas.click(removeX, ny, REMOVE, 12, () -> side.remove(fighter));
        }
    }

    /** A number between − and + buttons; the wheel over any of it steps it too. */
    private void stepper(DevCanvas canvas, int x, int y, int box, String value, java.util.function.IntConsumer step) {
        GuiGraphicsExtractor g = canvas.graphics();
        button(canvas, x, y, "-", () -> step.accept(-1));
        button(canvas, x + STEP + box, y, "+", () -> step.accept(1));
        g.fill(x + STEP, y, x + STEP + box, y + 12, DigiTheme.withAlpha(DigiTheme.VOID, 0xE6));
        g.outline(x + STEP, y, box, 12, DigiTheme.EDGE_DIM);
        g.text(canvas.font(), value, x + STEP + (box - canvas.font().width(value)) / 2 + 1, y + 2, DigiTheme.WHITE, false);
        canvas.wheel(x, y, 2 * STEP + box, 12, step);
    }

    private void button(DevCanvas canvas, int x, int y, String sign, Runnable action) {
        GuiGraphicsExtractor g = canvas.graphics();
        boolean hover = canvas.over(x, y, STEP, 12);
        g.fill(x, y, x + STEP, y + 12, DigiTheme.withAlpha(hover ? DigiTheme.PANEL_RAISED : DigiTheme.PANEL, 0xF0));
        DigiPanels.bevel(g, x, y, STEP, 12, 1, hover ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        g.text(canvas.font(), sign, x + 4, y + 2, hover ? DigiTheme.AMBER : DigiTheme.WHITE, false);
        canvas.click(x, y, STEP, 12, action);
    }

    private void pick(DevCanvas canvas, String key, List<DevClient.Fighter> side, DevClient.Fighter fighter, int index, int x, int y) {
        canvas.pickSpecies(key + index, x, y, ROW, fighter.species, picked -> {
            if (side.contains(fighter)) fighter.species = picked;
        });
    }

    private static void level(DevClient.Fighter fighter, int direction) {
        int step = Minecraft.getInstance().hasShiftDown() ? LEVEL_JUMP : 1;
        fighter.level = Progression.clampLevel(fighter.level + direction * step);
    }

    /** Steps a kind's count, keeping it within one kind's cap and the side within its own. */
    private static void count(List<DevClient.Fighter> side, DevClient.Fighter fighter, int direction) {
        int step = Minecraft.getInstance().hasShiftDown() ? COUNT_JUMP : 1;
        int others = total(side) - fighter.count;
        fighter.count = Math.clamp(fighter.count + (long) direction * step, 1, Math.min(BattleRoster.MAX_COUNT, BattleRoster.MAX_SIDE - others));
    }

    private static int total(List<DevClient.Fighter> side) {
        return side.stream().mapToInt(fighter -> fighter.count).sum();
    }

    private static String name(Identifier id) {
        return id == null ? "?" : DigimonSpeciesRegistry.get(id).map(species -> Component.translatable(species.translationKey()).getString()).orElse(id.getPath());
    }
}
