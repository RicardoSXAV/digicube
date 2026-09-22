package com.digicube.fabric.client.digivice;

import com.digicube.digimon.DigimonAttribute;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * The Digivice's components, drawn with {@code fill} in integer GUI units over the DigiCube GUI language: the primary
 * action of a view wears the device's own blue key, everything else stays navy; a text field is a dark slot whose rule
 * turns amber in focus and red on no match; tabs are cartridges docked in the top of the display.
 */
public final class DigiviceKit {
    private DigiviceKit() {}

    public enum State { REST, HOVER, DOWN, OFF }

    /** REST, HOVER or DOWN from where the pointer is and whether this control holds the press. */
    public static State state(boolean enabled, boolean hovered, boolean pressed) {
        return !enabled ? State.OFF : pressed ? State.DOWN : hovered ? State.HOVER : State.REST;
    }

    private static int textY(int y, int height) { return y + (height - 7) / 2; }

    /** @param arrow a play arrow before the label, for the one action that leaves the flat page */
    public static void keyButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label, boolean primary, boolean arrow, State state) {
        boolean down = state == State.DOWN, hover = state == State.HOVER;
        DigiPanels.frame(g, x - 1, y - 1, w + 2, h + 2, 0, withAlpha(DigiTheme.VOID, 0xC0), 3);
        if (state == State.OFF) {
            DigiPanels.frame(g, x, y, w, h, withAlpha(DigiTheme.PANEL, 0xC0), DigiTheme.EDGE_DIM, 2);
            g.text(font, label, x + (w - font.width(label)) / 2, textY(y, h), withAlpha(DigiTheme.MUTED, 0x80), false);
            return;
        }
        if (primary) {
            int top = down ? DigiviceArt.KEY_DARK : hover ? DigiTheme.mix(DigiviceArt.KEY_LIGHT, 0xFFFFFFFF, 0.25F) : DigiviceArt.KEY_LIGHT;
            int bottom = down ? DigiviceArt.KEY_DEEP : hover ? DigiviceArt.KEY : DigiviceArt.KEY_DARK;
            DigiPanels.frame(g, x, y, w, h, bottom, DigiviceArt.KEY_DEEP, 2);
            int half = h / 2;
            for (int i = 0; i < half; i++) g.fill(x + 2, y + 1 + i, x + w - 2, y + 2 + i, DigiTheme.mix(top, down ? DigiviceArt.KEY_DARK : DigiviceArt.KEY, (float) i / half));
            if (!down) g.fill(x + 3, y + 1, x + w - 3, y + 2, withAlpha(DigiTheme.WHITE, 0x90));
        } else {
            DigiPanels.frame(g, x, y, w, h, withAlpha(hover ? DigiTheme.PANEL_RAISED : DigiTheme.PANEL, 0xF0), 0, 2);
            g.fill(x + 1, y + 2, x + w - 1, y + 6, withAlpha(DigiTheme.PANEL_RAISED, 0x90));
            DigiPanels.bevel(g, x, y, w, h, 2, hover || down ? DigiTheme.CYAN : DigiTheme.EDGE_LIGHT, DigiTheme.SHADOW);
        }
        int push = down ? 1 : 0, width = font.width(label) + (arrow ? 9 : 0), tx = x + (w - width) / 2 + push, ty = textY(y, h) + push;
        if (arrow) { for (int i = 0; i < 4; i++) g.fill(tx + i, ty + i, tx + i + 1, ty + 7 - i, DigiTheme.WHITE); tx += 9; }
        g.text(font, label, tx + 1, ty + 1, withAlpha(DigiTheme.VOID, 0xA0), false);
        g.text(font, label, tx, ty, primary || hover ? DigiTheme.WHITE : withAlpha(DigiTheme.WHITE, 0xE0), false);
    }

    /** A secondary key held on: what a toggle looks like while its panel is pinned open. */
    public static void lit(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label) {
        DigiPanels.frame(g, x, y, w, h, withAlpha(DigiTheme.CYAN, 0x30), DigiTheme.CYAN, 2);
        g.fill(x + 3, y + h - 2, x + w - 3, y + h - 1, DigiTheme.CYAN);
        g.text(font, label, x + (w - font.width(label)) / 2, textY(y, h), DigiTheme.WHITE, false);
    }

    /**
     * @param count  shown on the right, e.g. how many entries match; negative hides it
     * @param caret  whether the blinking caret is in its lit half
     * @param bad    nothing matches what was typed
     */
    public static void field(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String text, String placeholder, boolean focus, boolean caret, int count, boolean bad) {
        int accent = bad ? DigiTheme.RED : focus ? DigiTheme.AMBER : DigiTheme.MUTED;
        g.fill(x, y, x + w, y + h, withAlpha(DigiTheme.VOID, 0xF0));
        g.fill(x, y, x + w, y + 1, DigiTheme.SHADOW);
        g.fill(x, y, x + 1, y + h, DigiTheme.SHADOW);
        g.fill(x + 1, y + h - 1, x + w, y + h, bad ? DigiTheme.RED : focus ? DigiTheme.AMBER : DigiTheme.EDGE);
        g.fill(x + w - 1, y + 1, x + w, y + h, DigiTheme.EDGE_DIM);
        if (focus) { g.fill(x, y + h - 1, x + 3, y + h, DigiTheme.WHITE); g.fill(x + w - 3, y + h - 1, x + w, y + h, DigiTheme.WHITE); }
        DigiviceArt.lens(g, x + 4, y + 3, accent);
        String counter = count < 0 ? "" : Integer.toString(count);
        int room = w - 21 - font.width(counter);
        String shown = text.isEmpty() ? (focus ? "" : placeholder) : font.plainSubstrByWidth(text, room, true);
        g.text(font, shown, x + 15, textY(y, h), text.isEmpty() ? withAlpha(DigiTheme.MUTED, 0x80) : DigiTheme.WHITE, false);
        if (focus && caret) g.fill(x + 15 + (text.isEmpty() ? 0 : font.width(shown) + 1), y + 2, x + 16 + (text.isEmpty() ? 0 : font.width(shown) + 1), y + h - 3, DigiTheme.AMBER);
        if (count >= 0) g.text(font, counter, x + w - 4 - font.width(counter), textY(y, h), withAlpha(bad ? DigiTheme.RED : DigiTheme.MUTED, 0xC0), false);
    }

    /** A filter chip: an attribute mark, or a word when {@code attribute} is null. */
    public static void chip(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label, DigimonAttribute attribute, boolean on, boolean hover) {
        int color = attribute == null ? DigiTheme.CYAN : DigiviceArt.color(attribute);
        DigiPanels.frame(g, x, y, w, h, on ? withAlpha(color, 0x30) : withAlpha(DigiTheme.VOID, 0xC0), on ? color : hover ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM, 1);
        if (attribute != null) DigiviceArt.mark(g, attribute, x + (w - 7) / 2, y + (h - 7) / 2, on ? color : hover ? DigiTheme.WHITE : withAlpha(color, 0x90));
        else g.text(font, label, x + (w - font.width(label)) / 2, textY(y, h), on ? DigiTheme.WHITE : DigiTheme.MUTED, false);
        if (on) g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
    }

    /** An empty bay in the tab row: more cartridges will dock here. */
    public static void emptyBay(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        for (int i = 0; i < w; i += 4) { g.fill(x + i, y + 2, x + i + 2, y + 3, DigiTheme.EDGE_DIM); g.fill(x + i, y + h - 1, x + i + 2, y + h, DigiTheme.EDGE_DIM); }
        g.fill(x, y + 2, x + 1, y + h, DigiTheme.EDGE_DIM);
        g.fill(x + w - 1, y + 2, x + w, y + h, DigiTheme.EDGE_DIM);
        for (int i = 0; i < 3; i++) g.fill(x + w / 2 - 7 + i * 6, y + 9, x + w / 2 - 4 + i * 6, y + 10, withAlpha(DigiTheme.EDGE, 0xC0));
    }

    /** @return the colour the tab's icon and label should take, after the cartridge is drawn */
    public static int tab(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active, boolean hover) {
        if (active) {
            DigiPanels.frame(g, x, y, w, h + 1, DigiTheme.PANEL_RAISED, 0, 2);
            g.fill(x + 2, y, x + w - 2, y + 2, DigiTheme.CYAN);
            g.fill(x, y + 2, x + 1, y + h + 1, DigiTheme.EDGE_LIGHT);
            g.fill(x + w - 1, y + 2, x + w, y + h + 1, DigiTheme.SHADOW);
            for (int i = 0; i < w - 8; i += 3) g.fill(x + 4 + i, y + h - 2, x + 6 + i, y + h - 1, withAlpha(DigiTheme.CYAN, 0x70));
            return DigiTheme.WHITE;
        }
        DigiPanels.frame(g, x, y + 2, w, h - 2, withAlpha(DigiTheme.PANEL, hover ? 0xF0 : 0xA0), 0, 2);
        g.fill(x + 2, y + 2, x + w - 2, y + 3, hover ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM);
        return hover ? DigiTheme.WHITE : DigiTheme.MUTED;
    }

    /** A fourteen-cell bar; the last lit cell fades with the fraction. */
    public static void statBar(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label, float fraction, String value, int color) {
        int cells = 14, cell = (w - 44) / cells, bx = x + 24;
        g.text(font, label, x, y, DigiTheme.MUTED, false);
        for (int i = 0; i < cells; i++) {
            float f = Math.clamp(fraction * cells - i, 0, 1);
            g.fill(bx + i * cell, y, bx + i * cell + cell - 1, y + 7, withAlpha(DigiTheme.EDGE_DIM, 0x90));
            if (f <= 0) continue;
            int alpha = 0x50 + (int) (0xAF * f);
            g.fill(bx + i * cell, y, bx + i * cell + cell - 1, y + 7, withAlpha(color, alpha));
            g.fill(bx + i * cell, y, bx + i * cell + cell - 1, y + 2, withAlpha(DigiTheme.mix(color, 0xFFFFFFFF, 0.4F), alpha));
        }
        g.text(font, value, x + w - font.width(value), y, DigiTheme.WHITE, false);
    }

    /** A cyan section label with a dotted rule fading to the right. */
    public static void label(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w) {
        g.text(font, text, x, y, DigiTheme.CYAN, false);
        for (int i = font.width(text) + 4; i < w; i += 3) g.fill(x + i, y + 3, x + i + 2, y + 4, withAlpha(DigiTheme.CYAN, (int) (0x50 * (1 - (float) i / w)) + 0x20));
    }

    public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int total, int shown, int at) {
        g.fill(x, y, x + 2, y + h, withAlpha(DigiTheme.EDGE_DIM, 0xC0));
        if (total <= shown) return;
        int thumb = Math.max(10, Math.round((float) h * shown / total)), ty = y + Math.round((float) (h - thumb) * at / (total - shown));
        g.fill(x, ty, x + 2, ty + thumb, DigiTheme.CYAN);
    }

    /** The grey-green LCD like the toy's own screen: a dot grid, a shaded top-left, a lit bottom edge. */
    public static void lcd(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, DigiTheme.SHADOW);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF3A4556);
        g.fill(x, y, x + w, y + h, DigiviceArt.LCD);
        for (int i = 2; i < h; i += 3) g.fill(x, y + i, x + w, y + i + 1, withAlpha(DigiviceArt.LCD_DARK, 0x60));
        for (int i = 2; i < w; i += 3) g.fill(x + i, y, x + i + 1, y + h, withAlpha(DigiviceArt.LCD_DARK, 0x38));
        g.fill(x, y, x + w, y + 2, withAlpha(DigiviceArt.INK, 0x30));
        g.fill(x, y, x + 2, y + h, withAlpha(DigiviceArt.INK, 0x24));
        g.fill(x, y + h - 1, x + w, y + h, DigiviceArt.LCD_LIGHT);
    }

    /** A small framed caption: hover names, what letting go would do, a refusal. */
    public static void tag(GuiGraphicsExtractor g, Font font, String text, int x, int y, int edge, int color) {
        DigiPanels.frame(g, x, y, font.width(text) + 9, 11, withAlpha(DigiTheme.VOID, 0xE8), edge, 1);
        g.text(font, text, x + 5, y + 2, color, false);
    }
    public static int tagWidth(Font font, String text) { return font.width(text) + 9; }
}
