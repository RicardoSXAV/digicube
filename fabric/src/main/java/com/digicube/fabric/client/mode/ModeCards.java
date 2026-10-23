package com.digicube.fabric.client.mode;

import com.digicube.Constants;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.mode.DigiCubeMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Locale;

/**
 * The info key's large tooltip: one card per mode, Classic left and Tamer right as the buttons stand, in DigiCube's
 * frame (void ground, chamfered edge), centred on the screen with its foot three units above the key. A card is a
 * viewport (the mode's scene over a perspective data floor), a name plate and three lines; the picked mode's card turns
 * amber. The scenes are 140 x 72 textures, two frames for TAMER's (wings and data eggs bob every second) and three for
 * CLASSIC's (the egg rocks now and then).
 */
final class ModeCards {
    private static final List<DigiCubeMode> ORDER = List.of(DigiCubeMode.CLASSIC, DigiCubeMode.TAMER);
    private static final int CARD_HEIGHT = 136, VIEW_HEIGHT = 76, GAP = 6, PAD = 4, MAX_CARD_WIDTH = 172, MARGIN = 36;
    private static final int SCENE_WIDTH = 140, SCENE_HEIGHT = 72, LINES = 3;
    /** The floor: horizon 30 down, rows closing up toward it, five lines converging from 60 apart, starting 4 below it. */
    private static final int HORIZON = 30, LINE_START = 4, LINE_PAIRS = 2, SPREAD = 60;
    private static final int[] ROWS = {4, 10, 18, 29, 44};
    private static final Identifier TAMER_0 = scene("tamer_0"), TAMER_1 = scene("tamer_1"),
            CLASSIC = scene("classic"), CLASSIC_LEFT = scene("classic_left"), CLASSIC_RIGHT = scene("classic_right");

    private ModeCards() {}

    private static Identifier scene(String name) {
        return Constants.id("textures/gui/mode/" + name + ".png");
    }

    /** Draws the tooltip on top of everything, for an info key at {@code keyY} on a screen {@code screenWidth} wide. */
    static void draw(GuiGraphicsExtractor g, Font font, int screenWidth, int keyY, DigiCubeMode picked) {
        int cardWidth = Math.min(MAX_CARD_WIDTH, (screenWidth - MARGIN) / 2);
        cardWidth -= cardWidth % 2;
        int width = cardWidth * 2 + GAP + PAD * 2, height = CARD_HEIGHT + PAD * 2;
        int x = (screenWidth - width) / 2, y = Math.max(2, keyY - 3 - height);
        g.nextStratum();
        DigiPanels.frame(g, x - 1, y - 1, width + 2, height + 2, DigiTheme.withAlpha(DigiTheme.VOID, 0xF4), DigiTheme.EDGE_LIGHT, 4);
        long ticks = Util.getMillis() / 50;
        for (int i = 0; i < ORDER.size(); i++) {
            DigiCubeMode mode = ORDER.get(i);
            card(g, font, x + PAD + i * (cardWidth + GAP), y + PAD, cardWidth, mode, picked == mode, ticks);
        }
    }

    private static void card(GuiGraphicsExtractor g, Font font, int x, int y, int width, DigiCubeMode mode, boolean picked, long ticks) {
        DigiPanels.frame(g, x, y, width, CARD_HEIGHT, DigiTheme.PANEL, picked ? DigiTheme.AMBER : DigiTheme.EDGE, 3);
        if (picked) DigiPanels.frame(g, x + 1, y + 1, width - 2, CARD_HEIGHT - 2, 0, DigiTheme.AMBER, 2);
        int vx = x + 4, vy = y + 4, vw = width - 8;
        DigiPanels.frame(g, vx, vy, vw, VIEW_HEIGHT, DigiTheme.VOID, DigiTheme.EDGE_DIM, 2);
        g.enableScissor(vx + 1, vy + 1, vx + vw - 1, vy + VIEW_HEIGHT - 1);
        floor(g, vx, vy, vw, VIEW_HEIGHT, picked ? 0x50 : 0x40, picked ? DigiTheme.GRID_BRIGHT : DigiTheme.GRID, picked ? 0xC0 : 0x70);
        int sx = vx + (vw - SCENE_WIDTH) / 2, sy = vy + VIEW_HEIGHT - SCENE_HEIGHT - 2;
        g.blit(RenderPipelines.GUI_TEXTURED, frame(mode, ticks), sx, sy, 0, 0, SCENE_WIDTH, SCENE_HEIGHT, SCENE_WIDTH, SCENE_HEIGHT, SCENE_WIDTH, SCENE_HEIGHT);
        g.disableScissor();
        int px = x + 4, py = y + 84, plate = width - 8;
        DigiPanels.frame(g, px, py, plate, 15, picked ? DigiTheme.AMBER : DigiTheme.PANEL_RAISED, 0, 2);
        MutableComponent name = Component.literal(Component.translatable(mode.translationKey()).getString().toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.BOLD);
        g.text(font, name, px + (plate - font.width(name)) / 2, py + 4, picked ? DigiTheme.VOID : DigiTheme.WHITE, false);
        for (int k = 0; k < LINES; k++) {
            String line = DigiPanels.shortText(font, line(mode, k), width - 10);
            g.text(font, line, x + (width - font.width(line)) / 2, y + 104 + k * 10, k == 0 ? DigiTheme.WHITE : DigiTheme.MUTED, false);
        }
    }

    /** The scene's frame now: TAMER bobs every second; CLASSIC's egg leans left, then right, in a five-second loop. */
    private static Identifier frame(DigiCubeMode mode, long ticks) {
        if (mode == DigiCubeMode.TAMER) return ticks / 20 % 2 == 0 ? TAMER_0 : TAMER_1;
        long t = ticks % 100;
        return t >= 60 && t < 66 ? CLASSIC_LEFT : t >= 72 && t < 78 ? CLASSIC_RIGHT : CLASSIC;
    }

    /** The viewport's floor: a horizon, rows closing up toward it, lines converging on its middle. */
    private static void floor(GuiGraphicsExtractor g, int x, int y, int width, int height, int alpha, int color, int horizonAlpha) {
        int horizon = y + HORIZON, bottom = y + height, cx = x + width / 2, span = bottom - horizon;
        g.fill(x, horizon, x + width, horizon + 1, DigiTheme.withAlpha(color, horizonAlpha));
        int tone = DigiTheme.withAlpha(color, alpha);
        for (int row : ROWS) if (horizon + row < bottom) g.fill(x, horizon + row, x + width, horizon + row + 1, tone);
        for (int k = -LINE_PAIRS; k <= LINE_PAIRS; k++) {
            int base = cx + k * SPREAD;
            for (int yy = horizon + LINE_START; yy < bottom; yy++) {
                if (isRow(yy - horizon)) continue;
                int px = Math.round(cx + (base - cx) * (yy - horizon) / (float) span);
                if (px >= x && px < x + width) g.fill(px, yy, px + 1, yy + 1, tone);
            }
        }
    }

    private static boolean isRow(int offset) {
        for (int row : ROWS) if (row == offset) return true;
        return false;
    }

    private static Component line(DigiCubeMode mode, int k) {
        return Component.translatable("gui.digicube.mode.card." + mode.getSerializedName() + "." + (k + 1));
    }

    /** Both cards read out, for the info key's narration. */
    static Component narration() {
        MutableComponent text = Component.empty();
        for (DigiCubeMode mode : ORDER) {
            text.append(Component.translatable(mode.translationKey())).append(". ");
            for (int k = 0; k < LINES; k++) text.append(line(mode, k)).append(" ");
        }
        return text;
    }
}
