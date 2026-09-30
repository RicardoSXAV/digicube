package com.digicube.fabric.client.digivice;

import com.digicube.Constants;
import com.digicube.digimon.CombatMark;
import com.digicube.fabric.client.gui.DigiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Map;

/**
 * The combat mark emblems on a screen, from the same textures that float over a body and drawn the same three ways:
 * whole, risen from the bottom (a build-up), and as a pie that drains clockwise from the top (a timer). The risen and
 * the pie forms cut the texture on whole texels, so the emblem has to be drawn at a multiple of its size.
 */
public final class MarkEmblems {
    /** The variants of an emblem texture; not every mark has every one. */
    public static final String LIT = "", SPENT = "_spent", OFF = "_off", FLASH = "_flash", RESIST = "_resist";
    /** The emblem texture is 32 px; its glyph fills rows 4..27 and a charge rises through those. */
    private static final int TEXELS = 32, GLYPH_TOP = 4, GLYPH_ROWS = 24;
    private static final int HURT_TINT = 0xFFFF8A8A;
    private static final Map<CombatMark, Integer> COLORS = new EnumMap<>(CombatMark.class);

    private MarkEmblems() {}

    public static Identifier texture(CombatMark mark, String variant) {
        return Constants.id("textures/entity/status/mark_" + mark.id() + variant + ".png");
    }

    public static void full(GuiGraphicsExtractor g, Identifier texture, int x, int y, int size, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, TEXELS, TEXELS, TEXELS, TEXELS, color);
    }

    /** The lit emblem of {@code mark}. */
    public static void lit(GuiGraphicsExtractor g, CombatMark mark, int x, int y, int size) {
        full(g, texture(mark, LIT), x, y, size, 0xFFFFFFFF);
    }

    /** The shape of the emblem in {@code color}: a mark the tamer has not seen yet. */
    public static void silhouette(GuiGraphicsExtractor g, CombatMark mark, int x, int y, int size, int color) {
        DigiviceArt.Texture shape = DigiviceArt.shape(texture(mark, LIT));
        if (shape != null) shape.draw(g, x, y, size, size, color);
    }

    /** {@code texture} from the bottom of its glyph up to {@code charge}, on whole texture rows. */
    public static void risen(GuiGraphicsExtractor g, Identifier texture, int x, int y, int size, float charge) {
        int rows = Math.round(GLYPH_ROWS * Math.clamp(charge, 0, 1));
        if (rows == 0) return;
        int top = TEXELS - GLYPH_TOP - rows, unit = size / TEXELS;
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y + top * unit, 0, top, size, (TEXELS - top) * unit, TEXELS, TEXELS - top, TEXELS, TEXELS);
    }

    /** What is left of {@code texture} as a pie: it empties clockwise from straight up as {@code remaining} falls. */
    public static void wedge(GuiGraphicsExtractor g, Identifier texture, int x, int y, int size, float remaining) {
        float start = (1 - Math.clamp(remaining, 0, 1)) * 360;
        if (start >= 360) return;
        if (start <= 0) { full(g, texture, x, y, size, 0xFFFFFFFF); return; }
        int unit = size / TEXELS;
        for (int row = 0; row < TEXELS; row++) {
            int from = -1;
            // One strip per run of texels still inside the pie: two a row at most.
            for (int column = 0; column <= TEXELS; column++) {
                boolean inside = column < TEXELS && angle(column, row) >= start;
                if (inside && from < 0) from = column;
                else if (!inside && from >= 0) {
                    g.blit(RenderPipelines.GUI_TEXTURED, texture, x + from * unit, y + row * unit, from, row, (column - from) * unit, unit, column - from, 1, TEXELS, TEXELS);
                    from = -1;
                }
            }
        }
    }

    /** Degrees clockwise from straight up of a texel's middle, seen from the emblem's. */
    static float angle(int column, int row) {
        double degrees = Math.toDegrees(Math.atan2(column + .5 - TEXELS / 2.0, TEXELS / 2.0 - row - .5));
        return (float) (degrees < 0 ? degrees + 360 : degrees);
    }

    /** One moment of a mark's run, the way the world draws it. */
    public static void draw(GuiGraphicsExtractor g, CombatMark mark, MarkLife.Frame frame, int x, int y, int size) {
        switch (frame.draw()) {
            case NONE -> { }
            case BUILD -> {
                full(g, texture(mark, OFF), x, y, size, 0xFFFFFFFF);
                risen(g, texture(mark, SPENT), x, y, size, frame.amount());
            }
            case TIMER -> {
                full(g, texture(mark, SPENT), x, y, size, 0xFFFFFFFF);
                wedge(g, texture(mark, LIT), x, y, size, frame.amount());
            }
            case FLASH -> full(g, texture(mark, FLASH), x, y, size, 0xFFFFFFFF);
            case WHOLE -> full(g, texture(mark, LIT), x, y, size, 0xFFFFFFFF);
            case HURT -> full(g, texture(mark, LIT), x, y, size, HURT_TINT);
            // A resistance is drawn at half size, a little lower than an emblem.
            case RESIST -> full(g, texture(mark, RESIST), x + size / 4, y + size / 4 + size / 16, size / 2, 0xFFFFFFFF);
        }
    }

    /** The colour a mark wears in the Digivice, taken from the vivid part of its own emblem. */
    public static int color(CombatMark mark) {
        return COLORS.computeIfAbsent(mark, key -> {
            DigiviceArt.Pixels pixels = DigiviceArt.pixels(texture(key, LIT));
            if (pixels == null) return DigiTheme.CYAN;
            long red = 0, green = 0, blue = 0;
            int count = 0;
            for (int argb : pixels.argb()) {
                int r = argb >> 16 & 0xFF, gr = argb >> 8 & 0xFF, b = argb & 0xFF, high = Math.max(r, Math.max(gr, b)), low = Math.min(r, Math.min(gr, b));
                if (argb >>> 24 < 200 || high < 150 || (high - low) * 100 < high * 35) continue;
                red += r; green += gr; blue += b; count++;
            }
            if (count == 0) return DigiTheme.CYAN;
            return DigiTheme.mix(0xFF000000 | (int) (red / count) << 16 | (int) (green / count) << 8 | (int) (blue / count), 0xFFFFFFFF, .2F);
        });
    }
}
