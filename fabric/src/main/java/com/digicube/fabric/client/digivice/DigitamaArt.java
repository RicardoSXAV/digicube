package com.digicube.fabric.client.digivice;

import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * A family's Digitama as the Digivice draws it, from its item sprite ({@code textures/item/digitama/<first form>.png},
 * sixteen texels square in the shape of a spawn egg). On the SCAN page the egg is the bar: its colours rise from the
 * bottom as data comes in and the rest is ink, the Analyzer's silhouette, with data glinting along the line between
 * them; a ready one breathes amber. On the island it rocks now and then and cracks before it hatches, swells and bursts
 * as the Baby comes out ({@link #swell}, {@link #hatch}), and one just taken in from the hand comes together there out
 * of the golden data it broke into ({@link #assemble}).
 */
final class DigitamaArt {
    /** The ink of a shell not filled yet: the Analyzer's silhouette, a lighter edge. */
    static final int INK = 0xFF1B3556, INK_EDGE = 0xFF2B5282;
    static final int SIZE = 16;
    /** Ticks, and seconds, a Digitama taken in from the hand takes to come together on the island. */
    static final int ASSEMBLE_TICKS = 18;
    static final float ASSEMBLE = ASSEMBLE_TICKS / 20F;
    /** The golden data of the recall chip's break, which the egg broke into in the hand. */
    private static final int GOLD = 0xFFDF72, GOLD_GLOW = 0xFFD653, DATA_BLUE = 0x8FE3FF;
    /** Cracks before hatching, as sprite texels {column, row}: a few first, then one across the shell. */
    private static final int[][][] CRACKS = {{}, {{7, 4}, {8, 5}, {7, 6}, {8, 7}},
            {{3, 7}, {4, 6}, {5, 7}, {6, 6}, {7, 5}, {8, 6}, {9, 5}, {10, 6}, {11, 7}, {12, 6}, {7, 4}, {8, 7}}};
    /** The row of the crack across the shell in each sprite column: above it is the cap the Baby knocks off. */
    private static final int[] CRACK_ROW = {7, 7, 7, 7, 6, 7, 6, 5, 6, 5, 6, 7, 6, 6, 6, 6};
    /** Ticks, and seconds, a hatching Digitama swells white before its shell bursts ({@link #swell}). */
    static final int SWELL_TICKS = 6;
    static final float SWELL = SWELL_TICKS / 20F;
    /** Seconds the burst shell takes to be gone ({@link #hatch}). */
    static final float BURST = .9F;

    private DigitamaArt() {}

    static Identifier texture(Identifier family) { return family.withPath(path -> "textures/item/digitama/" + path + ".png"); }

    /** How cracked a Digitama with {@code hatchTicks} left is: 0, 1 at eleven seconds, 2 at three and a half. */
    static int crack(int hatchTicks) { return hatchTicks < 70 ? 2 : hatchTicks < 220 ? 1 : 0; }

    /** Now and then it rocks, in the last half minute more often, cracked through all but constantly: a sideways offset in island units, one of its texels there. */
    static float wobble(int hatchTicks, int ticks, int seed) {
        int period = hatchTicks < 70 ? 10 : hatchTicks < 600 ? 26 : 80, phase = Math.floorMod(ticks + seed * 17, period);
        return phase < 8 ? new float[]{0.5F, 0, -0.5F, 0}[phase >> 1] : 0;
    }

    /**
     * The egg with each texel {@code k} units wide.
     * @param frac  how much of it is in its colours, from the bottom up; 1 is the whole sprite
     * @param glow  0 to 1: an amber halo round a shell ready to convert
     * @param crack 0 to 2, see {@link #crack}
     * @param flash 0 to 1: the shell washed white, as data pours into it or it hatches
     * @param seed  shifts the glints, so eggs side by side do not twinkle together
     */
    static void draw(GuiGraphicsExtractor g, Identifier family, int x, int y, int k, float frac, float glow, int crack, float flash, int seed, int ticks) {
        DigiviceArt.Pixels pixels = DigiviceArt.pixels(texture(family));
        if (pixels == null) { DigiPanels.icon(g, family, x, y, SIZE * k); return; }
        int[] px = pixels.argb();
        int w = pixels.width(), h = pixels.height(), top = h, bottom = 0;
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) if (on(px, w, h, c, r)) { top = Math.min(top, r); bottom = Math.max(bottom, r); }
        if (top > bottom) return;
        frac = Math.clamp(frac, 0, 1);
        int line = y + Math.round(k * (bottom + 1 - (bottom + 1 - top) * frac));
        if (glow > 0) {
            int halo = withAlpha(DigiTheme.AMBER, 0x30 + Math.round(0xB0 * Math.min(1, glow)));
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
                if (on(px, w, h, c, r)) continue;
                if (on(px, w, h, c, r - 1) || on(px, w, h, c, r + 1) || on(px, w, h, c - 1, r) || on(px, w, h, c + 1, r)) fill(g, x + c * k, y + r * k, k, k, halo);
            }
        }
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            if (!on(px, w, h, c, r)) continue;
            int color = px[r * w + c], tx = x + c * k, ty = y + r * k;
            int ink = edge(px, w, h, c, r) ? INK_EDGE : INK;
            if (ty >= line) fill(g, tx, ty, k, k, color);
            else if (ty + k <= line) fill(g, tx, ty, k, k, ink);
            else { fill(g, tx, ty, k, line - ty, ink); fill(g, tx, line, k, ty + k - line, color); }
        }
        if (frac > 0 && frac < 1) {
            int row = Math.clamp((line - y) / Math.max(1, k), 0, h - 1);
            for (int c = 0; c < w; c++) {
                if (!on(px, w, h, c, row)) continue;
                double glint = DigispaceWorld.hash(c + seed, ticks >> 1);
                fill(g, x + c * k, line - 1, k, 1, glint > 0.7 ? DigiTheme.WHITE : glint > 0.35 ? DigiTheme.CYAN : DigiTheme.DATA_LIGHT);
            }
        }
        if (flash > 0) {
            int white = withAlpha(DigiTheme.WHITE, Math.round(0xFF * Math.min(1, flash)));
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) if (on(px, w, h, c, r)) fill(g, x + c * k, y + r * k, k, k, white);
        }
        int dark = px[Math.min(bottom, h - 1) * w + w / 2 - 1] | 0xFF000000;
        for (int[] at : CRACKS[Math.clamp(crack, 0, CRACKS.length - 1)]) if (on(px, w, h, at[0], at[1])) fill(g, x + at[0] * k, y + at[1] * k, k, k, dark);
    }

    /**
     * The egg coming together out of golden data, {@code t} seconds in: the recall chip's break played backwards on the
     * egg's texels. Each one falls into place from above, the top rows first, golden until it lands and then in its own
     * colour, while motes of data circle in round it. Whole at {@link #ASSEMBLE}; {@code x}, {@code y} as for
     * {@link #draw} with one unit a texel.
     */
    static void assemble(GuiGraphicsExtractor g, Identifier family, int x, int y, float t) {
        DigiviceArt.Pixels pixels = DigiviceArt.pixels(texture(family));
        if (pixels == null) return;
        int[] px = pixels.argb();
        int w = pixels.width(), h = pixels.height();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        // Quarter texels, so a falling texel glides instead of stepping a whole texel at a time.
        g.pose().scale(.25F, .25F);
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            if (!on(px, w, h, c, r)) continue;
            int seed = c * 31 + r * 17;
            float land = RecallMotion.smooth((t - (.05F + r * .02F + seed % 5 * .025F)) / .38F);
            if (land <= 0) continue;
            float fall = 1 - land;
            float fx = c + fall * ((c - 7.5F) * .8F + (seed % 11 - 5) * .6F), fy = r - fall * fall * (12 + seed % 7 * 2F);
            int color = blend(GOLD, px[r * w + c], RecallMotion.smooth(land * 2 - 1));
            int alpha = Math.round(255 * Math.min(1, land * 4)), size = land < 1 ? 3 : 4;
            int qx = Math.round(fx * 4), qy = Math.round(fy * 4);
            g.fill(qx, qy, qx + size, qy + size, alpha << 24 | color);
        }
        float in = Math.clamp(t / ASSEMBLE, 0, 1);
        for (int k = 0; k < 14; k++) {
            double a = DigispaceWorld.hash(k, 7) * Math.PI * 2 + t * 5, reach = (1 - in) * (22 + DigispaceWorld.hash(k, 3) * 10) + 3;
            int mx = (int) Math.round((8 + Math.cos(a) * reach) * 4), my = (int) Math.round((9 + Math.sin(a) * reach * .6) * 4);
            g.fill(mx, my, mx + 4, my + 4, Math.round(0xE0 * (1 - in)) << 24 | (k % 3 == 0 ? 0xFFFFFF : GOLD_GLOW));
        }
        g.pose().popMatrix();
    }

    /** The egg just whole, {@code since} seconds after {@link #ASSEMBLE}: a ring of data runs out from under it at ({@code cx}, {@code cy}). */
    static void landed(GuiGraphicsExtractor g, int cx, int cy, float since) {
        if (since < 0 || since >= .5F) return;
        float p = since / .5F, reach = 3 + 13 * (1 - (1 - p) * (1 - p));
        int alpha = Math.round(0xE0 * (1 - p));
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            int x = cx + (int) Math.round(Math.cos(a) * reach), y = cy + (int) Math.round(Math.sin(a) * reach * .5);
            g.fill(x, y, x + 1, y + 1, alpha << 24 | (i % 3 == 0 ? 0xFFFFFF : i % 2 == 0 ? DATA_BLUE : GOLD_GLOW));
        }
    }

    /**
     * A hatching Digitama the moment before its shell bursts, {@code t} seconds into its {@link #SWELL}: cracked through,
     * it shudders, swells about its foot and washes white. {@code x}, {@code y} as for {@link #draw} with one unit a texel.
     */
    static void swell(GuiGraphicsExtractor g, Identifier family, int x, int y, float t, int ticks) {
        float p = Math.clamp(t / SWELL, 0, 1), grow = 1 + .16F * p * p;
        g.pose().pushMatrix();
        g.pose().translate(x + 8 + ((ticks & 1) == 0 ? .5F : -.5F), y + 15);
        g.pose().scale(grow, grow);
        draw(g, family, -8, -15, 1, 1, 0, 2, .5F + .5F * p, 0, ticks);
        g.pose().popMatrix();
    }

    /**
     * The shell bursting as the Baby hatches out of it, {@code t} seconds after the burst, in two layers round the Baby.
     * Behind it ({@code front} false), the cap above the crack, thrown up and aside ({@code side} 1 right, -1 left) and
     * tumbling as it falls. In front of it, the rest of the shell, standing a moment as a cup round the Baby's feet before
     * each texel lets go as a mote of data, rising and fading, and a ring of data running out over the ground. Both wash
     * white in the burst's flash. Gone at {@link #BURST}; {@code x}, {@code y} as for {@link #draw} with one unit a texel.
     */
    static void hatch(GuiGraphicsExtractor g, Identifier family, int x, int y, float t, int side, boolean front) {
        DigiviceArt.Pixels pixels = DigiviceArt.pixels(texture(family));
        if (pixels == null || t < 0 || t >= BURST) return;
        int[] px = pixels.argb();
        int w = pixels.width(), h = pixels.height();
        float white = 1 - Math.clamp(t / .12F, 0, 1);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        // Quarter texels, so the pieces glide instead of stepping a whole texel at a time.
        g.pose().scale(.25F, .25F);
        float capAlpha = 1 - Math.clamp((t - .3F) / .25F, 0, 1);
        if (!front && capAlpha > 0) {
            g.pose().pushMatrix();
            g.pose().translate((7.5F + side * t * 55) * 4, (3.5F - t * 55 + t * t * 110) * 4);
            g.pose().rotate(side * t * 10);
            int alpha = Math.round(255 * capAlpha) << 24;
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
                if (!on(px, w, h, c, r) || r >= CRACK_ROW[Math.min(c, CRACK_ROW.length - 1)]) continue;
                int qx = Math.round((c - 7.5F) * 4), qy = Math.round((r - 3.5F) * 4);
                g.fill(qx, qy, qx + 4, qy + 4, alpha | blend(px[r * w + c], 0xFFFFFF, white));
            }
            g.pose().popMatrix();
        }
        if (front) for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            if (!on(px, w, h, c, r) || r <= CRACK_ROW[Math.min(c, CRACK_ROW.length - 1)]) continue;
            int seed = c * 31 + r * 17;
            // the rim lets go first, the foot last
            float since = t - (.12F + (r - 6) * .03F + seed % 7 * .025F);
            if (since < 0) {
                g.fill(c * 4, r * 4, c * 4 + 4, r * 4 + 4, 0xFF000000 | blend(px[r * w + c], 0xFFFFFF, white));
                continue;
            }
            float p = since / .35F;
            if (p >= 1) continue;
            int color = blend(px[r * w + c], seed % 3 == 0 ? 0xFFFFFF : DATA_BLUE, Math.min(1, p * 2.5F));
            int qx = Math.round((c + (seed % 5 - 2) * p * .9F) * 4), qy = Math.round((r - p * (5 + seed % 4)) * 4);
            g.fill(qx, qy, qx + 3, qy + 3, Math.round(255 * (1 - p)) << 24 | color);
        }
        if (front && t < .55F) {
            float p = t / .55F, reach = 5 + 22 * (1 - (1 - p) * (1 - p));
            int alpha = Math.round(0xE0 * (1 - p));
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                int mx = Math.round((8 + (float) Math.cos(a) * reach) * 4), my = Math.round((14.5F + (float) Math.sin(a) * reach * .45F) * 4);
                g.fill(mx, my, mx + 4, my + 4, alpha << 24 | (i % 4 == 0 ? 0xFFFFFF : i % 2 == 0 ? DATA_BLUE : GOLD_GLOW));
            }
        }
        g.pose().popMatrix();
    }

    /** RGB from {@code a} to {@code b}'s colour by {@code p}, opaque. */
    private static int blend(int a, int b, float p) {
        int rgb = 0;
        for (int shift : new int[]{0, 8, 16}) rgb |= Math.round(((a >> shift) & 255) * (1 - p) + ((b >> shift) & 255) * p) << shift;
        return rgb;
    }

    /** The whole sprite scaled into a {@code size} square: in its colours, or as an ink shape for a family not met yet. */
    static void icon(GuiGraphicsExtractor g, Identifier family, int x, int y, int size, boolean seen) {
        Identifier texture = texture(family);
        if (!seen) {
            DigiviceArt.Texture shape = DigiviceArt.shape(texture);
            if (shape != null) shape.draw(g, x, y, size, size, INK_EDGE);
            return;
        }
        if (DigiviceArt.pixels(texture) == null) { DigiPanels.icon(g, family, x, y, size); return; }
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, SIZE, SIZE, SIZE, SIZE);
    }

    /** The egg's shadow under it and a ring of data, lit while it is ready. */
    static void pedestal(GuiGraphicsExtractor g, int cx, int y, boolean lit) {
        int[][] rows = {{13, 0}, {17, 1}, {17, 2}, {13, 3}};
        for (int[] row : rows) g.fill(cx - row[0], y + row[1], cx + row[0], y + row[1] + 1, withAlpha(DigiTheme.VOID, 0xB0));
        for (int i = -22; i < 22; i += 2) {
            float a = 1 - Math.abs(i) / 22F;
            g.fill(cx + i, y + 5, cx + i + 1, y + 6, withAlpha(lit ? DigiTheme.CYAN : DigiTheme.EDGE_LIGHT, 0x30 + Math.round(0xA0 * a)));
        }
    }

    private static boolean on(int[] px, int w, int h, int c, int r) {
        return c >= 0 && r >= 0 && c < w && r < h && px[r * w + c] >>> 24 >= 0x60;
    }

    private static boolean edge(int[] px, int w, int h, int c, int r) {
        return !on(px, w, h, c, r - 1) || !on(px, w, h, c, r + 1) || !on(px, w, h, c - 1, r) || !on(px, w, h, c + 1, r);
    }

    private static void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w > 0 && h > 0) g.fill(x, y, x + w, y + h, color);
    }
}
