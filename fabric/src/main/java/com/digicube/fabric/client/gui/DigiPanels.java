package com.digicube.fabric.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing primitives of the DigiCube GUI language: chamfered frames, corner brackets,
 * the data grid, breathing data squares, platforms, the header block and buttons. All of
 * it is {@code fill} calls on integer GUI units, so it is crisp at every GUI scale and
 * needs no sprites.
 */
public final class DigiPanels {
    private DigiPanels() {}

    /** A rectangle in GUI units. */
    public record Area(int x, int y, int width, int height) {}

    /** One ambient data square, chosen once per screen size; only its alpha moves. */
    public record DataCell(int x, int y, float phase, boolean bright) {}

    public enum ButtonState { DISABLED, REST, HOVER, PRIMARY, PRIMARY_HOVER }

    /** The screen ground: {@link DigiTheme#VOID} at the given alpha over the world. */
    public static void ground(GuiGraphicsExtractor graphics, int width, int height, int alpha) {
        graphics.fill(0, 0, width, height, DigiTheme.withAlpha(DigiTheme.VOID, alpha));
    }

    /** Lines every {@code cell} units, clipped to the rectangle. {@code color} carries its own alpha. */
    public static void grid(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int cell, int color) {
        for (int gx = x + cell; gx < x + width; gx += cell) graphics.fill(gx, y, gx + 1, y + height, color);
        for (int gy = y + cell; gy < y + height; gy += cell) graphics.fill(x, gy, x + width, gy + 1, color);
    }

    /**
     * Picks the data squares of a screen once, so the pattern is stable and each frame is
     * a few hundred fills at most.
     * @param seed    anything stable per layout, e.g. the screen size
     * @param density percentage of candidate cells that are used, 0..100
     * @param areas   where squares may appear; they never overlap text or cards
     */
    public static List<DataCell> dataCells(int seed, int density, List<Area> areas) {
        List<DataCell> cells = new ArrayList<>();
        for (Area area : areas) {
            for (int cy = area.y(); cy + DigiTheme.DATA_CELL <= area.y() + area.height(); cy += DigiTheme.DATA_PITCH) {
                for (int cx = area.x(); cx + DigiTheme.DATA_CELL <= area.x() + area.width(); cx += DigiTheme.DATA_PITCH) {
                    int hash = hash(seed, cx, cy);
                    if (Math.floorMod(hash, 100) >= density) continue;
                    float phase = Math.floorMod(hash >>> 8, 628) / 100.0F;
                    cells.add(new DataCell(cx, cy, phase, Math.floorMod(hash >>> 16, 12) == 0));
                }
            }
        }
        return List.copyOf(cells);
    }

    /** Breathing squares: alpha follows a sine of {@code time} (ticks) with the cell's own phase. */
    public static void dataSquares(GuiGraphicsExtractor graphics, List<DataCell> cells, float time) {
        double omega = 2 * Math.PI / DigiTheme.BREATH_TICKS;
        for (DataCell cell : cells) {
            double breath = 0.5 + 0.5 * Math.sin(time * omega + cell.phase());
            int alpha = (int) Math.round(DigiTheme.DATA_ALPHA_MIN + (DigiTheme.DATA_ALPHA_MAX - DigiTheme.DATA_ALPHA_MIN) * breath);
            graphics.fill(cell.x(), cell.y(), cell.x() + DigiTheme.DATA_CELL, cell.y() + DigiTheme.DATA_CELL,
                    DigiTheme.withAlpha(cell.bright() ? DigiTheme.DATA_LIGHT : DigiTheme.DATA, alpha));
        }
    }

    /**
     * A rectangle with its corners cut at 45 degrees.
     * @param fill    fill colour; alpha 0 draws no fill
     * @param edge    one-unit edge colour; alpha 0 draws no edge
     * @param chamfer corner cut in units, 0 for a plain rectangle
     */
    public static void frame(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int fill, int edge, int chamfer) {
        int c = Math.clamp(chamfer, 0, Math.min(width, height) / 2);
        if ((fill >>> 24) != 0) {
            graphics.fill(x, y + c, x + width, y + height - c, fill);
            for (int i = 0; i < c; i++) {
                int inset = c - 1 - i;
                graphics.fill(x + inset, y + i, x + width - inset, y + i + 1, fill);
                graphics.fill(x + inset, y + height - 1 - i, x + width - inset, y + height - i, fill);
            }
        }
        if ((edge >>> 24) == 0) return;
        graphics.fill(x + c, y, x + width - c, y + 1, edge);
        graphics.fill(x + c, y + height - 1, x + width - c, y + height, edge);
        graphics.fill(x, y + c, x + 1, y + height - c, edge);
        graphics.fill(x + width - 1, y + c, x + width, y + height - c, edge);
        for (int i = 0; i < c; i++) {
            int inset = c - 1 - i;
            graphics.fill(x + inset, y + i, x + inset + 1, y + i + 1, edge);
            graphics.fill(x + width - 1 - inset, y + i, x + width - inset, y + i + 1, edge);
            graphics.fill(x + inset, y + height - 1 - i, x + inset + 1, y + height - i, edge);
            graphics.fill(x + width - 1 - inset, y + height - 1 - i, x + width - inset, y + height - i, edge);
        }
    }

    /**
     * Four L-shaped corner brackets, two units thick, {@code inset} units outside the
     * rectangle. Animate by varying {@code inset}.
     */
    public static void brackets(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int length, int inset, int color) {
        int left = x - inset;
        int top = y - inset;
        int right = x + width + inset;
        int bottom = y + height + inset;
        graphics.fill(left, top, left + length, top + 2, color);
        graphics.fill(left, top, left + 2, top + length, color);
        graphics.fill(right - length, top, right, top + 2, color);
        graphics.fill(right - 2, top, right, top + length, color);
        graphics.fill(left, bottom - 2, left + length, bottom, color);
        graphics.fill(left, bottom - length, left + 2, bottom, color);
        graphics.fill(right - length, bottom - 2, right, bottom, color);
        graphics.fill(right - 2, bottom - length, right, bottom, color);
    }

    /**
     * The lit plate a model stands on: a flat rectangle with a two-unit outline and a
     * translucent centre, centred on {@code centerX} with its top edge at {@code y}.
     */
    public static void platform(GuiGraphicsExtractor graphics, int centerX, int y, int width, int color) {
        int x = centerX - width / 2;
        int height = 6;
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, DigiTheme.withAlpha(color, 0x40));
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 2, x + width, y + height, color);
        graphics.fill(x, y, x + 2, y + height, color);
        graphics.fill(x + width - 2, y, x + width, y + height, color);
    }

    /** The three-line header block: eyebrow, bold title, muted subtitle, centred. */
    public static void header(GuiGraphicsExtractor graphics, Font font, int centerX, int y,
                              Component eyebrow, Component title, Component subtitle, int maxWidth) {
        graphics.centeredText(font, shortText(font, eyebrow, maxWidth), centerX, y, DigiTheme.CYAN);
        graphics.centeredText(font, title, centerX, y + 12, DigiTheme.WHITE);
        graphics.centeredText(font, shortText(font, subtitle, maxWidth), centerX, y + 26, DigiTheme.MUTED);
    }

    /** A chamfered button. The primary state is amber with dark text; everything else is navy. */
    public static void button(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
                              Component label, ButtonState state) {
        int fill = switch (state) {
            case DISABLED -> DigiTheme.PANEL;
            case REST, HOVER -> DigiTheme.PANEL_RAISED;
            case PRIMARY -> DigiTheme.AMBER;
            case PRIMARY_HOVER -> 0xFFFFD08F;
        };
        int edge = switch (state) {
            case DISABLED -> DigiTheme.EDGE_DIM;
            case REST -> DigiTheme.EDGE;
            case HOVER -> DigiTheme.CYAN;
            case PRIMARY -> DigiTheme.AMBER;
            case PRIMARY_HOVER -> DigiTheme.WHITE;
        };
        int text = switch (state) {
            case DISABLED -> DigiTheme.MUTED;
            case REST, HOVER -> DigiTheme.WHITE;
            case PRIMARY, PRIMARY_HOVER -> DigiTheme.VOID;
        };
        frame(graphics, x, y, width, height, fill, edge, DigiTheme.CHAMFER_BUTTON);
        // No font shadow: a dark shadow under dark text on amber only smears it.
        String value = shortText(font, label, width - 8);
        graphics.text(font, value, x + (width - font.width(value)) / 2, y + (height - 8) / 2, text, false);
    }

    /** A one-unit horizontal rule in the dim edge colour. */
    public static void separator(GuiGraphicsExtractor graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 1, DigiTheme.EDGE_DIM);
    }

    /**
     * The 32 x 32 species icon from {@code textures/gui/digimon/}, scaled to {@code size};
     * a framed question mark when the species has none yet.
     */
    public static void icon(GuiGraphicsExtractor graphics, Identifier species, int x, int y, int size) {
        Identifier texture = species.withPath(path -> "textures/gui/digimon/" + path + ".png");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getResourceManager().getResource(texture).isPresent()) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, 32, 32, 32, 32);
        } else {
            graphics.outline(x + 2, y + 2, size - 4, size - 4, DigiTheme.EDGE);
            graphics.centeredText(minecraft.font, "?", x + size / 2, y + size / 2 - 4, DigiTheme.MUTED);
        }
    }

    /** Truncates with an ellipsis when the text is wider than {@code width}. */
    public static String shortText(Font font, Component text, int width) {
        String value = text.getString();
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, Math.max(0, width - 6)) + "…";
    }

    private static int hash(int seed, int x, int y) {
        int h = seed * 0x9E3779B1 + x * 0x85EBCA77 + y * 0xC2B2AE3D;
        h ^= h >>> 15;
        h *= 0x27D4EB2F;
        h ^= h >>> 13;
        return h;
    }
}
