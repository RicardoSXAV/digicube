package com.digicube.fabric.client.dev;

import com.digicube.dev.BattleRoster;
import com.digicube.dev.BattleTest;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.fabric.client.party.PartyHudReadout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * The bar at the top of the screen while a Battle Testing fight runs: both sides, the health of each side as one bar,
 * the fight clock, and the winner once there is one. A side of more than one fighter lists its kinds under the bar
 * with how many of each still stand. The clock counts locally between the server's readouts, so it never jumps.
 * The bar keeps clear of the party strip in the top left corner: it slides right of it, and narrows its health bars
 * on a screen too small for both.
 */
final class BattleReadout {
    private static final int WIDTH = 340, MIN_WIDTH = 240, HEIGHT = 32, TOP = 6, BAR = 110, KIND_ICON = 9;
    /** Units kept free after the party strip and before the right edge of the screen. */
    private static final int STRIP_GAP = 6, EDGE = 4;

    private BattleReadout() {}

    static void draw(GuiGraphicsExtractor g, Font font, int screenWidth, int guiScale, CompoundTag battle, int age) {
        int left = PartyHudReadout.stripRight(guiScale) + STRIP_GAP, right = screenWidth - EDGE;
        int width = Math.clamp(right - left, MIN_WIDTH, WIDTH), bar = BAR - (WIDTH - width) / 2;
        // centred when it fits beside the strip, else pushed just past it
        int x = Math.max(left, Math.min(screenWidth / 2 - width / 2, right - width)), cx = x + width / 2, y = TOP;
        String winner = battle.getStringOr(BattleTest.WINNER, "");
        DigiPanels.frame(g, x - 1, y - 1, width + 2, HEIGHT + 2, DigiTheme.withAlpha(DigiTheme.PANEL, 0xB0), DigiTheme.withAlpha(DigiTheme.VOID, 0xB0), 3);
        DigiPanels.bevel(g, x, y, width, HEIGHT, 2, DigiTheme.EDGE_DIM, DigiTheme.SHADOW);
        side(g, font, battle.getCompoundOrEmpty(BattleTest.SIDE_A), BattleTest.SIDE_A, x, width, bar, false, winner.equals(BattleTest.SIDE_B));
        side(g, font, battle.getCompoundOrEmpty(BattleTest.SIDE_B), BattleTest.SIDE_B, x, width, bar, true, winner.equals(BattleTest.SIDE_A));

        int ticks = battle.getIntOr(BattleTest.TICKS, 0) + (winner.isEmpty() ? age : 0), seconds = ticks / 20;
        String clock = seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
        g.text(font, clock, cx - font.width(clock) / 2, y + 12, winner.isEmpty() ? DigiTheme.CYAN : DigiTheme.AMBER, true);

        if (winner.isEmpty()) return;
        boolean draw = !winner.equals(BattleTest.SIDE_A) && !winner.equals(BattleTest.SIDE_B);
        String result = draw ? "DOUBLE KNOCKOUT" : "WINNER  " + label(battle.getCompoundOrEmpty(winner), winner).toUpperCase(Locale.ROOT);
        int w = font.width(result) + 16;
        DigiPanels.frame(g, cx - w / 2, y + HEIGHT + 6, w, 15, DigiTheme.withAlpha(DigiTheme.PANEL, 0xE0), DigiTheme.AMBER, 2);
        g.text(font, result, cx - w / 2 + 8, y + HEIGHT + 10, DigiTheme.AMBER, true);
    }

    private static void side(GuiGraphicsExtractor g, Font font, CompoundTag side, String key, int x, int width, int bar, boolean right, boolean lost) {
        int y = TOP;
        ListTag kinds = side.getListOrEmpty(BattleTest.KINDS);
        float max = Math.max(1, side.getFloatOr(BattleTest.MAX_HEALTH, 1)), health = Math.max(0, side.getFloatOr(BattleTest.HEALTH, 0));
        boolean single = fighters(kinds) <= 1;
        // a lone fighter's portrait sits at the edge; a side's kinds take the line under the bar and may reach there
        Identifier lead = kinds.isEmpty() ? null : Identifier.tryParse(kinds.getCompoundOrEmpty(0).getStringOr(BattleRoster.SPECIES, ""));
        if (single && lead != null) DigiPanels.icon(g, lead, right ? x + width - 29 : x + 5, y + 4, 24, DigiTheme.withAlpha(DigiTheme.WHITE, lost ? 0x60 : 0xFF));

        int bx = right ? x + width - 33 - bar : x + 33;
        String label = label(side, key), numbers = (int) Math.ceil(health) + "/" + Math.round(max);
        g.text(font, label, right ? bx + bar - font.width(label) : bx, y + 3, lost ? DigiTheme.MUTED : DigiTheme.WHITE, true);
        // one fighter keeps its numbers under the bar; a side's go beside its name while a narrowed bar leaves room
        int ny = single ? y + 21 : y + 3;
        if (single || font.width(label) + 6 + font.width(numbers) <= bar)
            g.text(font, numbers, right == single ? bx + bar - font.width(numbers) : bx, ny, DigiTheme.withAlpha(DigiTheme.MUTED, 0xC0), false);

        float fraction = Math.min(1, health / max);
        int color = fraction > 0.5F ? DigiTheme.TEAL : fraction > 0.25F ? DigiTheme.AMBER : DigiTheme.RED, filled = Math.round(bar * fraction);
        int fx = right ? bx + bar - filled : bx;
        g.fill(bx - 1, y + 13, bx + bar + 1, y + 19, DigiTheme.SHADOW);
        g.fill(bx, y + 14, bx + bar, y + 18, DigiTheme.VOID);
        if (filled > 0) {
            g.fill(fx, y + 14, fx + filled, y + 18, color);
            g.fill(fx, y + 14, fx + filled, y + 16, DigiTheme.mix(color, 0xFFFFFFFF, 0.3F));
        }
        for (int tick = bx + 10; tick < bx + bar; tick += 10) g.fill(tick, y + 14, tick + 1, y + 18, DigiTheme.withAlpha(DigiTheme.PANEL, 0xC0));

        // the kinds, each with how many still stand, reading outward from the clock
        if (single) return;
        int kx = right ? bx : bx + bar;
        for (int i = 0; i < kinds.size(); i++) {
            CompoundTag kind = kinds.getCompoundOrEmpty(i);
            Identifier species = Identifier.tryParse(kind.getStringOr(BattleRoster.SPECIES, ""));
            int alive = kind.getIntOr(BattleTest.ALIVE, 0);
            String count = "×" + alive;
            int w = KIND_ICON + 1 + font.width(count), at = right ? kx : kx - w;
            int tint = DigiTheme.withAlpha(DigiTheme.WHITE, alive == 0 ? 0x50 : 0xFF);
            if (species != null) DigiPanels.icon(g, species, at, y + 21, KIND_ICON, tint);
            g.text(font, count, at + KIND_ICON + 1, y + 22, alive == 0 ? DigiTheme.withAlpha(DigiTheme.MUTED, 0x80) : DigiTheme.WHITE, false);
            kx += right ? w + 6 : -(w + 6);
        }
    }

    private static int fighters(ListTag kinds) {
        int total = 0;
        for (int i = 0; i < kinds.size(); i++) total += kinds.getCompoundOrEmpty(i).getIntOr(BattleRoster.COUNT, 1);
        return total;
    }

    /** One fighter is named ("Golemon 30"); a side of several is just the side. */
    private static String label(CompoundTag side, String key) {
        ListTag kinds = side.getListOrEmpty(BattleTest.KINDS);
        if (fighters(kinds) == 1) {
            CompoundTag only = kinds.getCompoundOrEmpty(0);
            return name(only.getStringOr(BattleRoster.SPECIES, "")) + " " + only.getIntOr(BattleRoster.LEVEL, 1);
        }
        return "Side " + key.toUpperCase(Locale.ROOT);
    }

    private static String name(String id) {
        Identifier species = Identifier.tryParse(id);
        if (species == null) return "?";
        return DigimonSpeciesRegistry.get(species).map(found -> Component.translatable(found.translationKey()).getString()).orElse(species.getPath());
    }
}
