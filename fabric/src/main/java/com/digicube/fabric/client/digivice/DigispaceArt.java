package com.digicube.fabric.client.digivice;

import java.util.EnumMap;
import java.util.Map;

/**
 * The standing props of the Digispace, painted texel by texel at the terrain's density: two oaks, a pine, a boulder
 * and a data crystal. Every shape has a lit rim towards the top left, a dark outline and up to four tones.
 */
public final class DigispaceArt {
    private DigispaceArt() {}

    /** ARGB pixels; ({@code anchorX}, {@code anchorY}) is the texel that stands on the prop's world position. */
    public record Sprite(int width, int height, int anchorX, int anchorY, int[] pixels) {}

    @FunctionalInterface private interface Shape { boolean inside(int x, int y); }
    @FunctionalInterface private interface Shade { double at(int x, int y); }

    private static final Map<DigispaceWorld.PropType, Sprite> SPRITES = new EnumMap<>(DigispaceWorld.PropType.class);
    static {
        SPRITES.put(DigispaceWorld.PropType.OAK, oak(1));
        SPRITES.put(DigispaceWorld.PropType.OAK_PALE, oak(2));
        SPRITES.put(DigispaceWorld.PropType.PINE, pine(3));
        SPRITES.put(DigispaceWorld.PropType.ROCK, rock());
        SPRITES.put(DigispaceWorld.PropType.CRYSTAL, crystal());
    }

    /** The art of a standing prop; null for flowers, which are part of the ground. */
    public static Sprite of(DigispaceWorld.PropType type) { return SPRITES.get(type); }

    /** Fills a shape: outline where it meets the outside (softer on the lit side), otherwise one of four tones by {@code shade}. */
    private static void volume(int[] px, int width, int height, Shape shape, int[] tones, int outline, Shade shade) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (!shape.inside(x, y)) continue;
            boolean edge = !shape.inside(x - 1, y) || !shape.inside(x + 1, y) || !shape.inside(x, y - 1) || !shape.inside(x, y + 1);
            if (edge) { px[y * width + x] = !shape.inside(x, y - 1) || !shape.inside(x - 1, y) ? DigispaceWorld.mix(outline, tones[1], 0.35) : outline; continue; }
            double s = shade.at(x, y);
            px[y * width + x] = s < -0.42 ? tones[0] : s < 0 ? tones[1] : s < 0.42 ? tones[2] : tones[3];
        }
    }

    private static Sprite oak(int seed) {
        int w = 24, h = 31;
        int[] px = new int[w * h];
        for (int y = 17; y <= 29; y++) {
            int wide = y >= 28 ? 1 : 0;
            for (int x = 10 - wide; x <= 13 + wide; x++) px[y * w + x] = x <= 10 ? 0xFF8A6238 : x >= 13 ? 0xFF3E2916 : y < 21 ? 0xFF4E341E : 0xFF6B4A2B;
        }
        px[24 * w + 11] = 0xFF4E341E; px[26 * w + 12] = 0xFF4E341E;
        double[] j = new double[5];
        for (int n = 1; n <= 4; n++) j[n] = (DigispaceWorld.hash(seed, n) - 0.5) * 2;
        double[][] blobs = {{12, 10 + j[1], 9}, {6.5 + j[2], 14, 6}, {17.5 + j[3], 14, 6}, {12 + j[4], 5.5, 6}};
        Shape canopy = (x, y) -> { if (y >= 22) return false; for (double[] b : blobs) if (Math.pow(x - b[0], 2) + Math.pow(y - b[1], 2) * 1.15 < b[2] * b[2]) return true; return false; };
        int[] tones = seed % 2 == 1 ? new int[]{0xFF8FDD7C, 0xFF45A556, 0xFF23773D, 0xFF165230} : new int[]{0xFFA8DB6A, 0xFF5FAE4A, 0xFF2F7F3A, 0xFF1B5A2E};
        volume(px, w, h, canopy, tones, 0xFF0F3A20, (x, y) -> ((x - 12) * 0.55 + (y - 10) * 0.8) / 10
                + Math.sin(x * 1.15 + seed) * Math.cos(y * 1.35 + seed) * 0.4 + (DigispaceWorld.hash(x + seed, y) - 0.5) * 0.35);
        return new Sprite(w, h, 12, 29, px);
    }

    private static Sprite pine(int seed) {
        int w = 24, h = 35;
        int[] px = new int[w * h];
        for (int y = 24; y <= 33; y++) for (int x = 10; x <= 13; x++) px[y * w + x] = x == 10 ? 0xFF7A5430 : x == 13 ? 0xFF33200F : 0xFF593A1F;
        double[][] tiers = {{2, 4.5}, {8, 6.5}, {14, 8.5}};
        Shape needles = (x, y) -> { if (y >= 27) return false; for (double[] t : tiers) if (y >= t[0] && y < t[0] + 12 && Math.abs(x - 11.5) <= 0.6 + (y - t[0]) / 11 * t[1]) return true; return false; };
        volume(px, w, h, needles, new int[]{0xFF7FCF95, 0xFF2E8B57, 0xFF1D6B48, 0xFF124A33}, 0xFF0B3323, (x, y) -> {
            boolean tierEnd = y == 13 || y == 12 || y == 19 || y == 18 || y == 25 || y == 24;
            return (x - 11.5) / 7 + (tierEnd ? 0.5 : 0) + (DigispaceWorld.hash(x + seed, y) - 0.5) * 0.4 - 0.1;
        });
        return new Sprite(w, h, 12, 33, px);
    }

    private static Sprite rock() {
        int w = 14, h = 10;
        int[] px = new int[w * h];
        Shape stone = (x, y) -> x >= 0 && y >= 0 && x < w && y < h && Math.pow((x - 6.5) / 6.8, 2) + Math.pow((y - 5.5) / 4.4, 2) < 1;
        volume(px, w, h, stone, new int[]{0xFFDCE3EA, 0xFFA3AEBA, 0xFF75818F, 0xFF4B5563}, 0xFF2B323C,
                (x, y) -> ((x - 6) * 0.6 + (y - 4) * 0.9) / 6 + (DigispaceWorld.hash(x, y) - 0.5) * 0.3);
        px[2 * w + 4] = 0xFF5FAE4A; px[2 * w + 5] = 0xFF45A556; px[w + 5] = 0xFF8FDD7C;
        return new Sprite(w, h, 7, 9, px);
    }

    private static Sprite crystal() {
        int w = 10, h = 18;
        int[] px = new int[w * h];
        for (int y = 0; y < 17; y++) {
            double half = y < 5 ? 0.5 + y * 0.8 : y < 11 ? 4 : Math.max(0.5, (16 - y) * 0.75);
            for (int x = 0; x < w; x++) {
                double dx = x - 4.5;
                if (Math.abs(dx) > half) continue;
                boolean edge = Math.abs(dx) > half - 1;
                px[y * w + x] = edge ? (dx < 0 ? 0xFF2FA9C9 : 0xFF0B5D78) : dx < -1 ? 0xFFC9F8FF : dx < 1 ? 0xFF5FE0F5 : 0xFF1FA3C4;
            }
        }
        px[4 * w + 3] = 0xFFFFFFFF; px[5 * w + 3] = 0xFFFFFFFF; px[6 * w + 2] = 0xFFFFFFFF;
        return new Sprite(w, h, 5, 17, px);
    }
}
