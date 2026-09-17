package com.digicube.fabric.client.dev;

import com.digicube.digimon.DigimonAttribute;
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

import java.util.Locale;

/**
 * Battle Testing's body: the two fighters side by side, each a card with its sprite, a name
 * field that opens the searchable Digimon list, and a level stepper (click, Shift-click for
 * ten, or the mouse wheel over the card). SWAP exchanges the sides.
 */
final class BattleTestingBody implements DevBody {
    private static final int CARD_HEIGHT = 42, VS_GAP = 38, LEVEL_JUMP = 10;
    private static final String SIDE_A = "a", SIDE_B = "b";

    private final DevClient client;

    BattleTestingBody(DevClient client) { this.client = client; }

    @Override public int height() { return CARD_HEIGHT; }

    @Override public String summary() {
        return (name(client.fighterA) + " " + client.levelA + " VS " + name(client.fighterB) + " " + client.levelB).toUpperCase(Locale.ROOT);
    }

    @Override public void draw(DevCanvas canvas, int x, int y, int width) {
        int card = (width - 12 - VS_GAP) / 2, mid = x + width / 2;
        card(canvas, x + 6, y, card, SIDE_A);
        card(canvas, x + width - 6 - card, y, card, SIDE_B);

        GuiGraphicsExtractor g = canvas.graphics();
        Font font = canvas.font();
        g.pose().pushMatrix();
        g.pose().translate(mid - font.width("VS"), y + 3);
        g.pose().scale(2, 2);
        g.text(font, "VS", 0, 0, DigiTheme.AMBER, true);
        g.pose().popMatrix();
        int sw = 34, sx = mid - sw / 2, sy = y + 27;
        boolean hover = canvas.over(sx, sy, sw, 12);
        DigiPanels.frame(g, sx, sy, sw, 12, DigiTheme.withAlpha(hover ? DigiTheme.PANEL_RAISED : DigiTheme.PANEL, 0xF0), 0, 2);
        DigiPanels.bevel(g, sx, sy, sw, 12, 2, hover ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        g.text(font, "SWAP", sx + (sw - font.width("SWAP")) / 2, sy + 2, hover ? DigiTheme.AMBER : DigiTheme.WHITE, false);
        canvas.click(sx, sy, sw, 12, () -> {
            Identifier species = client.fighterA;
            int level = client.levelA;
            client.fighterA = client.fighterB;
            client.levelA = client.levelB;
            client.fighterB = species;
            client.levelB = level;
        });
    }

    private void card(DevCanvas canvas, int x, int y, int w, String side) {
        GuiGraphicsExtractor g = canvas.graphics();
        Font font = canvas.font();
        boolean a = side.equals(SIDE_A), open = canvas.picking(side);
        Identifier id = a ? client.fighterA : client.fighterB;
        DigimonSpecies species = id == null ? null : DigimonSpeciesRegistry.get(id).orElse(null);
        int stripe = a ? DigiTheme.TEAL : DigiTheme.RED, level = a ? client.levelA : client.levelB;

        DigiPanels.frame(g, x - 1, y - 1, w + 2, CARD_HEIGHT + 2, 0, DigiTheme.withAlpha(DigiTheme.VOID, 0xB0), 3);
        DigiPanels.frame(g, x, y, w, CARD_HEIGHT, DigiTheme.withAlpha(DigiTheme.PANEL, 0xE6), 0, 2);
        g.fill(x + 1, y + 2, x + w - 1, y + 9, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, 0x90));
        DigiPanels.bevel(g, x, y, w, CARD_HEIGHT, 2, open ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        g.fill(x + 1, y + 3, x + 4, y + CARD_HEIGHT - 3, DigiTheme.withAlpha(stripe, 0xE0));
        g.fill(x + 1, y + 3, x + 2, y + CARD_HEIGHT - 3, DigiTheme.withAlpha(DigiTheme.WHITE, 0x28));

        int vx = x + 7, vy = y + 4;
        g.fill(vx - 1, vy - 1, vx + 35, vy + 35, DigiTheme.SHADOW);
        g.fill(vx, vy, vx + 34, vy + 34, DigiTheme.withAlpha(DigiTheme.VOID, 0xE6));
        DigiPanels.grid(g, vx + 1, vy + 1, 32, 32, 8, DigiTheme.withAlpha(DigiTheme.GRID, 0x3A));
        if (species != null) DigiPanels.icon(g, species.id(), vx + 1, vy + 1, 32);
        else g.centeredText(font, "?", vx + 17, vy + 13, DigiTheme.MUTED);

        g.text(font, a ? "FIGHTER A" : "FIGHTER B", x + 46, y + 4, stripe, true);
        if (species != null) {
            DigimonAttribute attribute = species.attribute();
            DigiPanels.attributeGlyph(g, attribute, x + w - 11, y + 5, DigiPanels.attributeColor(attribute));
        }

        // the name is the picker
        int nx = x + 44, ny = y + 14, nw = w - 48;
        boolean hover = canvas.over(nx, ny, nw, 12);
        g.fill(nx, ny, nx + nw, ny + 12, DigiTheme.withAlpha(DigiTheme.VOID, hover || open ? 0xF0 : 0xB0));
        g.outline(nx, ny, nw, 12, open || hover ? DigiTheme.AMBER : DigiTheme.EDGE_DIM);
        String label = species == null ? "PICK…" : DigiPanels.shortText(font, Component.translatable(species.translationKey()), nw - 14);
        g.text(font, label, nx + 3, ny + 2, species == null ? DigiTheme.AMBER : DigiTheme.WHITE, false);
        DevPanelScreen.chevron(g, nx + nw - 8, ny + 4, true, open || hover ? DigiTheme.AMBER : DigiTheme.MUTED);
        canvas.click(nx, ny, nw, 12, () -> canvas.pickSpecies(side, x, y, CARD_HEIGHT, id, picked -> {
            if (a) client.fighterA = picked; else client.fighterB = picked;
        }));

        g.text(font, "LV", x + 46, y + 30, DigiTheme.MUTED, true);
        int sx = x + 60, sy = y + 28;
        stepper(canvas, sx, sy, "-", () -> level(side, -1));
        stepper(canvas, sx + 38, sy, "+", () -> level(side, 1));
        g.fill(sx + 14, sy, sx + 37, sy + 12, DigiTheme.withAlpha(DigiTheme.VOID, 0xE6));
        g.outline(sx + 14, sy, 23, 12, DigiTheme.EDGE_DIM);
        String value = Integer.toString(level);
        g.text(font, value, sx + 14 + (23 - font.width(value)) / 2 + 1, sy + 2, DigiTheme.WHITE, false);
        canvas.wheel(x, y, w, CARD_HEIGHT, steps -> level(side, steps));
    }

    private void stepper(DevCanvas canvas, int x, int y, String sign, Runnable action) {
        GuiGraphicsExtractor g = canvas.graphics();
        boolean hover = canvas.over(x, y, 13, 12);
        g.fill(x, y, x + 13, y + 12, DigiTheme.withAlpha(hover ? DigiTheme.PANEL_RAISED : DigiTheme.PANEL, 0xF0));
        DigiPanels.bevel(g, x, y, 13, 12, 1, hover ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        g.text(canvas.font(), sign, x + 4, y + 2, hover ? DigiTheme.AMBER : DigiTheme.WHITE, false);
        canvas.click(x, y, 13, 12, action);
    }

    private void level(String side, int direction) {
        int step = Minecraft.getInstance().hasShiftDown() ? LEVEL_JUMP : 1;
        if (side.equals(SIDE_A)) client.levelA = Progression.clampLevel(client.levelA + direction * step);
        else client.levelB = Progression.clampLevel(client.levelB + direction * step);
    }

    private static String name(Identifier id) {
        return id == null ? "?" : DigimonSpeciesRegistry.get(id).map(species -> Component.translatable(species.translationKey()).getString()).orElse(id.getPath());
    }
}
