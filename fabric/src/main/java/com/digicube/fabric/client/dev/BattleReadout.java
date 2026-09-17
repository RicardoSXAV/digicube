package com.digicube.fabric.client.dev;

import com.digicube.dev.BattleTest;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * The bar at the top of the screen while a Battle Testing fight runs: both fighters, their
 * health, the fight clock, and the winner once there is one. The clock counts locally between
 * the server's readouts, so it never jumps.
 */
final class BattleReadout {
    private static final int WIDTH = 340, HEIGHT = 30, TOP = 6, BAR = 110;

    private BattleReadout() {}

    static void draw(GuiGraphicsExtractor g, Font font, int screenWidth, CompoundTag battle, int age) {
        int cx = screenWidth / 2, x = cx - WIDTH / 2, y = TOP;
        String winner = battle.getStringOr(BattleTest.WINNER, "");
        DigiPanels.frame(g, x - 1, y - 1, WIDTH + 2, HEIGHT + 2, DigiTheme.withAlpha(DigiTheme.PANEL, 0xB0), DigiTheme.withAlpha(DigiTheme.VOID, 0xB0), 3);
        DigiPanels.bevel(g, x, y, WIDTH, HEIGHT, 2, DigiTheme.EDGE_DIM, DigiTheme.SHADOW);
        side(g, font, battle.getCompoundOrEmpty(BattleTest.SIDE_A), x, false, winner.equals(BattleTest.SIDE_B));
        side(g, font, battle.getCompoundOrEmpty(BattleTest.SIDE_B), x, true, winner.equals(BattleTest.SIDE_A));

        int ticks = battle.getIntOr(BattleTest.TICKS, 0) + (winner.isEmpty() ? age : 0), seconds = ticks / 20;
        String clock = seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
        g.text(font, clock, cx - font.width(clock) / 2, y + 11, winner.isEmpty() ? DigiTheme.CYAN : DigiTheme.AMBER, true);

        if (winner.isEmpty()) return;
        boolean draw = !winner.equals(BattleTest.SIDE_A) && !winner.equals(BattleTest.SIDE_B);
        String result = draw ? "DOUBLE KNOCKOUT" : "WINNER  " + name(battle.getCompoundOrEmpty(winner)).toUpperCase(Locale.ROOT);
        int w = font.width(result) + 16;
        DigiPanels.frame(g, cx - w / 2, y + HEIGHT + 6, w, 15, DigiTheme.withAlpha(DigiTheme.PANEL, 0xE0), DigiTheme.AMBER, 2);
        g.text(font, result, cx - w / 2 + 8, y + HEIGHT + 10, DigiTheme.AMBER, true);
    }

    private static void side(GuiGraphicsExtractor g, Font font, CompoundTag fighter, int x, boolean right, boolean lost) {
        int y = TOP;
        Identifier species = Identifier.tryParse(fighter.getStringOr(BattleTest.SPECIES, ""));
        float max = Math.max(1, fighter.getFloatOr(BattleTest.MAX_HEALTH, 1)), health = Math.max(0, fighter.getFloatOr(BattleTest.HEALTH, 0));
        if (species != null) DigiPanels.icon(g, species, right ? x + WIDTH - 29 : x + 5, y + 3, 24, DigiTheme.withAlpha(DigiTheme.WHITE, lost ? 0x60 : 0xFF));
        String label = name(fighter) + " " + fighter.getIntOr(BattleTest.LEVEL, 1);
        String numbers = (int) Math.ceil(health) + "/" + Math.round(max);
        int bx = right ? x + WIDTH - 33 - BAR : x + 33;
        g.text(font, label, right ? bx + BAR - font.width(label) : bx, y + 3, lost ? DigiTheme.MUTED : DigiTheme.WHITE, true);

        float fraction = Math.min(1, health / max);
        int color = fraction > 0.5F ? DigiTheme.TEAL : fraction > 0.25F ? DigiTheme.AMBER : DigiTheme.RED, filled = Math.round(BAR * fraction);
        int fx = right ? bx + BAR - filled : bx;
        g.fill(bx - 1, y + 14, bx + BAR + 1, y + 20, DigiTheme.SHADOW);
        g.fill(bx, y + 15, bx + BAR, y + 19, DigiTheme.VOID);
        if (filled > 0) {
            g.fill(fx, y + 15, fx + filled, y + 19, color);
            g.fill(fx, y + 15, fx + filled, y + 17, DigiTheme.mix(color, 0xFFFFFFFF, 0.3F));
        }
        for (int tick = bx + 10; tick < bx + BAR; tick += 10) g.fill(tick, y + 15, tick + 1, y + 19, DigiTheme.withAlpha(DigiTheme.PANEL, 0xC0));
        g.text(font, numbers, right ? bx + BAR - font.width(numbers) : bx, y + 21, DigiTheme.withAlpha(DigiTheme.MUTED, 0xC0), false);
    }

    private static String name(CompoundTag fighter) {
        Identifier species = Identifier.tryParse(fighter.getStringOr(BattleTest.SPECIES, ""));
        if (species == null) return "?";
        return DigimonSpeciesRegistry.get(species).map(found -> Component.translatable(found.translationKey()).getString()).orElse(species.getPath());
    }
}
