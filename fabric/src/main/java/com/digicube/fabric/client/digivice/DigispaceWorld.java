package com.digicube.fabric.client.digivice;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The Digispace island: what each spot of ground is, where the props stand, and the painted terrain.
 * Pure data, generated once from fixed noise, so every client sees the same island. The ground is painted
 * at {@link #TEXELS} texels per world unit, the density of the 32 px Digimon icons shown at 16 units;
 * light falls from the top left. World units are what the camera, the walkers and the props use.
 */
public final class DigispaceWorld {
    public static final int TILE = 8, TILES_X = 60, TILES_Y = 28;
    public static final int WIDTH = TILES_X * TILE, HEIGHT = TILES_Y * TILE;
    public static final int TEXELS = 2;
    /** The painted image; taller than the island by the cliff that hangs under its southern edges. */
    public static final int IMAGE_WIDTH = WIDTH * TEXELS, IMAGE_HEIGHT = (HEIGHT + 16) * TEXELS;
    private static final int GROUND_ROWS = HEIGHT * TEXELS;

    public static final byte VOID = 0, GRASS = 1, SAND = 2, WATER = 3, PATH = 4;

    public enum PropType { OAK, OAK_PALE, PINE, ROCK, CRYSTAL, FLOWERS;
        /** Flowers are painted into the ground; everything else stands up and is drawn in depth order with the Digimon. */
        public boolean solid() { return this != FLOWERS; }
        boolean tree() { return this == OAK || this == OAK_PALE || this == PINE; }
    }
    /** {@code x}, {@code y}: where the prop touches the ground, in world units. */
    public record Prop(PropType type, int x, int y, int seed) {}
    /** A sparkle on deep water, in world units; {@code phase} staggers the animation. */
    public record Glint(float x, float y, int phase) {}

    private final byte[] kind = new byte[IMAGE_WIDTH * IMAGE_HEIGHT];
    private final float[] depth = new float[IMAGE_WIDTH * IMAGE_HEIGHT];
    private final boolean[] land = new boolean[TILES_X * TILES_Y];
    private final BitSet blocked = new BitSet(TILES_X * TILES_Y);
    private final List<Prop> props = new ArrayList<>();
    private final List<Glint> glints = new ArrayList<>();

    private DigispaceWorld() {}

    public static DigispaceWorld generate() {
        DigispaceWorld world = new DigispaceWorld();
        world.shape();
        world.plant();
        world.sparkle();
        return world;
    }

    /** Integer lattice hash in 0..1, the one noise source of the island. */
    static double hash(int x, int y) {
        int h = (int) (x * 374761393L + y * 668265263L) ^ 0x5bd1e995;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967295.0;
    }

    /** Smooth value noise over {@link #hash} with a lattice pitch of {@code scale}. */
    static double noise(int x, int y, int scale) {
        double fx = (double) x / scale, fy = (double) y / scale;
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
        double tx = fx - ix, ty = fy - iy, sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
        double a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy;
    }

    private static double pathY(double u) { return 12 + 2.5 * Math.sin(u / 5); }

    /** An ellipse with a wavy rim, two ponds and one winding path, decided per texel so every outline is organic. */
    private void shape() {
        double midY = (TILES_Y - 1) / 2.0;
        for (int py = 0; py < GROUND_ROWS; py++) for (int px = 0; px < IMAGE_WIDTH; px++) {
            double u = px / 16.0 - 0.5, v = py / 16.0 - 0.5;
            double f = 1 - Math.pow((u - TILES_X / 2.0) / (TILES_X / 2.0 - 1), 2) - Math.pow((v - midY) / (TILES_Y / 2.0 - 1), 2)
                    + 0.16 * Math.sin(u * 0.9) + 0.14 * Math.cos(v * 1.3 + u * 0.4) + (noise(px, py, 10) - 0.5) * 0.06;
            byte k = GRASS;
            if (f < 0.12) k = VOID;
            else if (f < 0.24) k = SAND;
            else {
                double pond = Math.min(Math.hypot(u - 20 + Math.sin(v) * 0.6, (v - 16) * 1.3) - 4.2, Math.hypot(u - 43, (v - 8) * 1.2) - 2.6)
                        + (noise(px, py, 7) - 0.5) * 0.5;
                if (pond < 0) { k = WATER; depth[py * IMAGE_WIDTH + px] = (float) -pond; }
                else {
                    double cu = Math.max(9.5, Math.min(TILES_X - 10.5, u));
                    double off = Math.hypot(u - cu, v - pathY(cu)) + (noise(px + 99, py, 6) - 0.5) * 0.5;
                    if (off < 0.85 && pond > 0.7) k = PATH;
                }
            }
            kind[py * IMAGE_WIDTH + px] = k;
        }
    }

    /** Trees gather in groves; rocks, crystals and flower patches are scattered. A prop needs a tile of plain grass. */
    private void plant() {
        int[][] probes = {{1, 1}, {14, 1}, {1, 14}, {14, 14}, {8, 8}};
        for (int y = 0; y < TILES_Y; y++) for (int x = 0; x < TILES_X; x++) {
            land[y * TILES_X + x] = texel(x * 16 + 8, y * 16 + 8) != VOID;
            boolean grass = true;
            for (int[] probe : probes) grass &= texel(x * 16 + probe[0], y * 16 + probe[1]) == GRASS;
            if (!grass) continue;
            double h = hash(x, y);
            boolean grove = noise(x, y, 4) > 0.58;
            PropType type = null;
            if (h > (grove ? 0.72 : 0.965)) { double pick = hash(x + 9, y + 3); type = pick < 0.3 ? PropType.PINE : pick < 0.65 ? PropType.OAK : PropType.OAK_PALE; }
            else if (hash(x + 40, y) > 0.985) type = PropType.CRYSTAL;
            else if (hash(x + 17, y + 5) > 0.972) type = PropType.ROCK;
            else if (h < 0.07) type = PropType.FLOWERS;
            if (type == null) continue;
            if (type.solid()) blocked.set(y * TILES_X + x);
            props.add(new Prop(type, x * TILE + 4 + ((int) (hash(x, y + 50) * 3) - 1), y * TILE + 7, (int) (h * 1000)));
        }
    }

    private void sparkle() {
        for (int i = 0; i < 900 && glints.size() < 120; i++) {
            int x = (int) (hash(i, 501) * IMAGE_WIDTH), y = (int) (hash(i, 502) * GROUND_ROWS);
            if (texel(x, y) == WATER && texel(x + 5, y) == WATER && depth[y * IMAGE_WIDTH + x] > 0.35)
                glints.add(new Glint(x / (float) TEXELS, y / (float) TEXELS, (int) (hash(i, 503) * 6)));
        }
    }

    byte texel(int x, int y) { return x < 0 || y < 0 || x >= IMAGE_WIDTH || y >= IMAGE_HEIGHT ? VOID : kind[y * IMAGE_WIDTH + x]; }

    /** What the ground is at a world position. */
    public byte kindAt(double x, double y) { return texel((int) Math.floor(x * TEXELS), (int) Math.floor(y * TEXELS)); }

    /** Whether a Digimon may stand here: on grass, sand or the path, and not inside a tree, rock or crystal. */
    public boolean walkable(double x, double y) {
        byte k = kindAt(x, y);
        if (k != GRASS && k != SAND && k != PATH) return false;
        int tx = (int) Math.floor(x / TILE), ty = (int) Math.floor(y / TILE);
        return tx >= 0 && ty >= 0 && tx < TILES_X && ty < TILES_Y && !blocked.get(ty * TILES_X + tx);
    }

    /** Whether the tile holds any ground at its centre; the net's loose data gathers around these. */
    public boolean land(int tileX, int tileY) { return tileX >= 0 && tileY >= 0 && tileX < TILES_X && tileY < TILES_Y && land[tileY * TILES_X + tileX]; }
    public List<Prop> props() { return props; }
    public List<Glint> glints() { return glints; }

    // ---------- painting ----------

    private static int pick(double n, int[] tones, double[] cuts) {
        for (int i = 0; i < cuts.length; i++) if (n < cuts[i]) return tones[i];
        return tones[tones.length - 1];
    }
    private static boolean soft(byte k) { return k == SAND || k == PATH; }
    static int mix(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = (int) Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = (int) Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static final int[] GRASS_TONES = {0xFF2F8A42, 0xFF3A9A49, 0xFF45AA50, 0xFF55BA5A};
    private static final int[] SAND_TONES = {0xFFCBB26A, 0xFFD9C27A, 0xFFE6D28E};
    private static final int[] PATH_TONES = {0xFFA58650, 0xFFB3955C, 0xFFC2A56C};
    private static final int[] WATER_TONES = {0xFF6FC0F5, 0xFF4499EC, 0xFF2E7ADC, 0xFF2264C6};
    private static final int[] EARTH_TONES = {0xFF4A3522, 0xFF5A4126, 0xFF6B4E2E};

    /** The terrain as ARGB, {@link #IMAGE_WIDTH} by {@link #IMAGE_HEIGHT}; zero where the net shows through. */
    public int[] paint() {
        int[] px = new int[IMAGE_WIDTH * IMAGE_HEIGHT];
        for (int y = 0; y < GROUND_ROWS; y++) for (int x = 0; x < IMAGE_WIDTH; x++) {
            byte k = kind[y * IMAGE_WIDTH + x];
            if (k == VOID) continue;
            double h = hash(x, y);
            int c;
            if (k == GRASS) {
                double n = noise(x, y, 30) * 0.55 + noise(x + 50, y + 20, 9) * 0.3 + h * 0.15;
                c = pick(n, GRASS_TONES, new double[]{0.36, 0.5, 0.64});
                byte below = texel(x, y + 1), below2 = texel(x, y + 2), above = texel(x, y - 1);
                // a north bank shows its face: turf lip, earth, wet earth
                if (below == WATER) c = 0xFF3B2A1A; else if (below2 == WATER) c = 0xFF5A4126; else if (texel(x, y + 3) == WATER) c = 0xFF245F30;
                // the turf's shaded lip and its lit edge
                else if (soft(below) || soft(below2)) c = 0xFF256F36; else if (soft(texel(x + 1, y))) c = 0xFF2A7A3C;
                else if (soft(above) || above == WATER || soft(texel(x - 1, y))) c = 0xFF77CF6B;
                else if (texel(x + 1, y) == WATER || texel(x - 1, y) == WATER) c = 0xFF245F30;
            } else if (k == SAND) {
                c = pick(noise(x, y, 14) * 0.7 + h * 0.3, SAND_TONES, new double[]{0.4, 0.66});
                if (h > 0.975) c = 0xFFB0935A; else if (h < 0.015) c = 0xFFF6EBBB;
                if (texel(x, y - 1) == GRASS || texel(x, y - 2) == GRASS || texel(x - 1, y - 1) == GRASS) c = 0xFFB39A5C;
            } else if (k == PATH) {
                c = pick(noise(x, y, 12) * 0.7 + h * 0.3, PATH_TONES, new double[]{0.4, 0.68});
                if (h > 0.978) c = 0xFF84693C; else if (hash(x + 1, y + 1) > 0.978) c = 0xFFDCC891;
                if (texel(x, y - 1) == GRASS || texel(x, y - 2) == GRASS || texel(x - 1, y) == GRASS) c = 0xFF8A6F42;
                else if (y % 7 == 3 && hash(x >> 3, y) > 0.7) c = 0xFF9C7E4A;
            } else {
                double d = depth[y * IMAGE_WIDTH + x] + (h - 0.5) * 0.14;
                c = pick(d, WATER_TONES, new double[]{0.22, 0.75, 1.7});
                if (d < 0.1 && hash(x >> 1, y >> 1) > 0.35) c = 0xFFDDF3FF;
                // the bank's shadow on the water, then the odd still ripple further out
                if (texel(x, y - 1) != WATER || texel(x, y - 2) != WATER || texel(x, y - 3) != WATER) c = mix(c, 0xFF10305E, 0.45);
                else if (d > 0.5 && (y + ((x >> 3) & 1) * 3) % 6 == 0 && hash(x >> 2, y) > 0.62) c = mix(c, 0xFFBFE4FF, 0.35);
            }
            px[y * IMAGE_WIDTH + x] = c;
        }
        tufts(px);
        flowers(px);
        lilies(px);
        shadows(px);
        underside(px);
        return px;
    }

    private static void set(int[] px, int x, int y, int c) { if (x >= 0 && y >= 0 && x < IMAGE_WIDTH && y < IMAGE_HEIGHT) px[y * IMAGE_WIDTH + x] = c; }
    private static void dark(int[] px, int x, int y, double f) {
        if (x < 0 || y < 0 || x >= IMAGE_WIDTH || y >= IMAGE_HEIGHT) return;
        int c = px[y * IMAGE_WIDTH + x];
        if (c != 0) px[y * IMAGE_WIDTH + x] = 0xFF000000 | (int) (((c >> 16) & 255) * f) << 16 | (int) (((c >> 8) & 255) * f) << 8 | (int) ((c & 255) * f);
    }
    private boolean all(int x, int y, int[][] offsets, byte wanted) {
        for (int[] o : offsets) if (texel(x + o[0], y + o[1]) != wanted) return false;
        return true;
    }

    /** Grass tufts and pebbles: small shapes with a light and a dark side. */
    private void tufts(int[] px) {
        int[][] room = {{0, 0}, {-1, -2}, {1, -2}, {2, 0}, {-2, 0}};
        for (int gy = 2; gy < GROUND_ROWS - 3; gy += 5) for (int gx = 2; gx < IMAGE_WIDTH - 3; gx += 5) {
            double h = hash(gx * 3, gy * 5);
            int x = gx + (int) (h * 40) % 4, y = gy + (int) (h * 400) % 4;
            if (!all(x, y, room, GRASS)) continue;
            if (h > 0.62) {
                set(px, x, y, 0xFF256F36); set(px, x - 1, y - 1, 0xFF77CF6B); set(px, x + 1, y - 1, 0xFF5CC05E); set(px, x, y - 2, 0xFF8FDD7C);
                if (h > 0.85) { set(px, x + 2, y, 0xFF256F36); set(px, x + 2, y - 1, 0xFF77CF6B); }
            } else if (h < 0.05) {
                set(px, x, y, 0xFF9AA5B1); set(px, x + 1, y, 0xFF6E7A88); set(px, x, y - 1, 0xFFC9D1DA); dark(px, x + 1, y + 1, 0.75); dark(px, x, y + 1, 0.75);
            }
        }
    }

    private static final int[][] PETALS = {{0xFFFF8FB0, 0xFFFFD1DF}, {0xFFFFE27A, 0xFFFFF4C2}, {0xFFF1F5EB, 0xFFFFFFFF}, {0xFFB79CFF, 0xFFDCCBFF}};
    private void flowers(int[] px) {
        for (Prop p : props) {
            if (p.type() != PropType.FLOWERS) continue;
            int[] tones = PETALS[p.seed() % 4];
            for (int i = 0; i < 5; i++) {
                int x = (p.x() - 4) * TEXELS + 2 + (int) (hash(p.seed(), i) * 11), y = (p.y() - 7) * TEXELS + 3 + (int) (hash(i, p.seed()) * 10);
                if (texel(x, y) != GRASS) continue;
                set(px, x, y + 2, 0xFF256F36); set(px, x, y + 1, 0xFF2F8A42); set(px, x - 1, y, tones[0]); set(px, x + 1, y, tones[0]);
                set(px, x, y - 1, tones[1]); set(px, x, y, 0xFFFFB84A); dark(px, x + 1, y + 2, 0.8);
            }
        }
    }

    private void lilies(int[] px) {
        int[][] room = {{0, 0}, {3, 0}, {0, 2}, {3, 2}, {-2, 0}, {5, 1}};
        for (int i = 0; i < 26; i++) {
            int x = (int) (hash(i, 301) * IMAGE_WIDTH), y = (int) (hash(i, 302) * GROUND_ROWS);
            if (!all(x, y, room, WATER) || depth[y * IMAGE_WIDTH + x] < 0.6) continue;
            for (int b = 0; b < 3; b++) for (int a = 0; a < 4; a++) if (!((a == 0 || a == 3) && b != 1)) set(px, x + a, y + b, b == 0 ? 0xFF6CCB6A : a == 3 ? 0xFF2F8A42 : 0xFF45AA50);
            set(px, x + 2, y + 1, 0xFF2264C6);
            for (int a = 0; a < 4; a++) dark(px, x + a + 1, y + 3, 0.78);
            if (i % 3 == 0) { set(px, x + 1, y, 0xFFFFD1DF); set(px, x + 1, y - 1, 0xFFFF8FB0); }
        }
    }

    /** Cast shadows of the standing props, thrown to the lower right. */
    private void shadows(int[] px) {
        for (Prop p : props) {
            if (!p.type().solid()) continue;
            boolean big = p.type().tree();
            int rx = big ? 11 : 6, ry = big ? 4 : 2, cx = p.x() * TEXELS + (big ? 4 : 2), cy = p.y() * TEXELS + 1;
            for (int y = -ry; y <= ry; y++) for (int x = -rx; x <= rx; x++) {
                double r = Math.pow((double) x / rx, 2) + Math.pow((double) y / ry, 2);
                if (r <= 1) dark(px, cx + x, cy + y, r < 0.55 ? 0.66 : 0.78);
            }
        }
    }

    /** The underside: a lip of turf or sand, strata of earth that darken, then the rock breaks up into data. */
    private void underside(int[] px) {
        for (int x = 0; x < IMAGE_WIDTH; x++) for (int y = 0; y < GROUND_ROWS; y++) {
            byte k = kind[y * IMAGE_WIDTH + x];
            if (k == VOID || texel(x, y + 1) != VOID) continue;
            int height = 20 + (int) (hash(x >> 2, 9) * 9);
            double streak = hash(x >> 1, 7);
            for (int d = 1; d <= height; d++) {
                if (texel(x, y + d) != VOID) break;
                double t = (double) d / height;
                int c = d <= 2 ? (k == SAND ? (d == 1 ? 0xFFB39A5C : 0xFF9A8248) : (d == 1 ? 0xFF256F36 : 0xFF1B5229)) : pick(streak, EARTH_TONES, new double[]{0.3, 0.7});
                if (d > 2) {
                    if ((d + ((x >> 3) & 3)) % 6 == 0) c = mix(c, 0xFF2A1C10, 0.5);
                    if (hash(x, y + d) > 0.93) c = mix(c, 0xFF9C7E4A, 0.6);
                    c = mix(c, 0xFF0B1626, t * 0.62);
                    if (t > 0.6) {
                        if (hash(x >> 1, (y + d) >> 1) < (t - 0.6) / 0.4) continue;
                        if (hash((x >> 1) + 5, (y + d) >> 1) > 0.8) c = hash(x >> 1, y + d) > 0.5 ? 0xFF2FD6E8 : 0xFF2FBF8F;
                    }
                }
                set(px, x, y + d, c);
            }
        }
    }
}
