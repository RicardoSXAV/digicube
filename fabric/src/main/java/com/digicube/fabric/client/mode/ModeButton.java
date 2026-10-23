package com.digicube.fabric.client.mode;

import com.digicube.mode.DigiCubeMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * One of the section's two mode buttons, a vanilla button. Picked, a check stands before its name; once one is picked
 * the other's name turns grey. A turned-away Enter flashes it yellow.
 */
final class ModeButton extends AbstractButton {
    /** The check before the picked name, 7 x 6. */
    private static final String[] CHECK = {"......#", ".....##", "#...##.", "##.##..", ".###...", "..#...."};
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, YELLOW = 0xFFFFFF55;

    final ModeSection section;
    private final DigiCubeMode mode;

    ModeButton(ModeSection section, DigiCubeMode mode, int width, int height) {
        super(0, 0, width, height, Component.translatable(mode.translationKey()));
        this.section = section;
        this.mode = mode;
    }

    @Override public void onPress(InputWithModifiers input) {
        section.pick(mode);
    }

    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        extractDefaultSprite(g);
        Font font = Minecraft.getInstance().font;
        DigiCubeMode picked = section.picked();
        boolean mine = picked == mode, other = picked.chosen() && !mine;
        int color = !active || other ? GREY : WHITE, ty = getY() + (getHeight() - 8) / 2;
        Component name = getMessage();
        if (mine) {
            int w = 7 + 4 + font.width(name), x = getX() + (getWidth() - w) / 2;
            stamp(g, CHECK, x + 1, ty + 1, shadow(WHITE));
            stamp(g, CHECK, x, ty, WHITE);
            g.text(font, name, x + 11, ty, color, true);
        } else {
            g.text(font, name, getX() + (getWidth() - font.width(name)) / 2, ty, color, true);
        }
        if (section.flashing()) outline(g, getX(), getY(), getWidth(), getHeight(), YELLOW);
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    /** Vanilla's text shadow of {@code color}: a quarter of each channel. */
    static int shadow(int color) {
        return 0xFF000000 | (color & 0xFCFCFC) >> 2;
    }

    static void stamp(GuiGraphicsExtractor g, String[] rows, int x, int y, int color) {
        for (int ry = 0; ry < rows.length; ry++)
            for (int rx = 0; rx < rows[ry].length(); rx++)
                if (rows[ry].charAt(rx) == '#') g.fill(x + rx, y + ry, x + rx + 1, y + ry + 1, color);
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }
}
