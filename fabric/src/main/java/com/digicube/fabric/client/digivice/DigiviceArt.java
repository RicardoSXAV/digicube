package com.digicube.fabric.client.digivice;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttribute;
import com.digicube.fabric.client.gui.DigiTheme;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Digivice's own colours and pixel art. Everything here is painted in code into small textures the first time it
 * is drawn and kept for the session: the device keys, the attribute emblems and marks, the tab icons, the glove, and
 * the Digispace terrain and props. One GUI unit is one texel, except in the Digispace, which is drawn scaled.
 */
public final class DigiviceArt {
    private DigiviceArt() {}

    // --- the device ---
    public static final int SHELL = 0xFFA9C4E0, SHELL_LIGHT = 0xFFD3E4F4, SHELL_MID = 0xFF8FB0D2, SHELL_DARK = 0xFF5F84AE, SHELL_LINE = 0xFF2B4262;
    public static final int RECESS = 0xFF0A111C;
    public static final int KEY = 0xFF2F6BFF, KEY_LIGHT = 0xFF8DB8FF, KEY_DARK = 0xFF1A3FA8, KEY_DEEP = 0xFF0F2566;
    public static final int LCD = 0xFFB4C0A2, LCD_LIGHT = 0xFFC6D1B4, LCD_DARK = 0xFF9AA78A, INK = 0xFF232B20;

    public record Texture(Identifier id, int width, int height) {
        public void draw(GuiGraphicsExtractor g, int x, int y) { draw(g, x, y, 0xFFFFFFFF); }
        /** Multiplied by {@code color}: a white texture takes the colour, white with alpha fades it. */
        public void draw(GuiGraphicsExtractor g, int x, int y, int color) {
            g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, width, height, width, height, width, height, color);
        }
        public void draw(GuiGraphicsExtractor g, int x, int y, int drawWidth, int drawHeight) {
            g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, drawWidth, drawHeight, width, height, width, height);
        }
    }

    private static final Map<String, Texture> TEXTURES = new HashMap<>();

    /** The texture called {@code name}, painted by {@code pixels} (ARGB, row by row) the first time it is asked for. */
    static Texture texture(String name, int width, int height, Supplier<int[]> pixels) {
        return TEXTURES.computeIfAbsent(name, key -> {
            NativeImage image = new NativeImage(width, height, false);
            int[] argb = pixels.get();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setPixel(x, y, argb[y * width + x]);
            Identifier id = Constants.id("digivice/" + key);
            DynamicTexture texture = new DynamicTexture(id::toString, image);
            texture.upload();
            Minecraft.getInstance().getTextureManager().register(id, texture);
            return new Texture(id, width, height);
        });
    }

    private static Texture bitmap(String name, String[] rows) {
        return texture(name, rows[0].length(), rows.length, () -> {
            int[] px = new int[rows[0].length() * rows.length];
            for (int y = 0; y < rows.length; y++) for (int x = 0; x < rows[y].length(); x++) if (rows[y].charAt(x) == '#') px[y * rows[0].length() + x] = 0xFFFFFFFF;
            return px;
        });
    }

    // --- device keys ---
    public enum KeyState { REST, HOVER, DOWN }

    /** A domed blue key of {@code radius}; the texture is {@code 2 * radius + 5} square, the key centred in it. */
    public static Texture key(int radius, KeyState state) {
        int size = 2 * radius + 5, r = radius;
        return texture("key_" + radius + "_" + state.name().toLowerCase(java.util.Locale.ROOT), size, size, () -> {
            int[] px = new int[size * size];
            boolean down = state == KeyState.DOWN, hover = state == KeyState.HOVER;
            for (int y = -r - 2; y <= r + 2; y++) for (int x = -r - 2; x <= r + 2; x++) {
                double d = Math.hypot(x, y);
                if (d > r + 2.4) continue;
                int c;
                if (d > r + 0.5) c = d > r + 1.5 ? SHELL_LINE : hover ? DigiTheme.AMBER : KEY_DEEP;
                else {
                    float t = (float) Math.min(1, Math.hypot(x + r * 0.35, y + r * 0.4) / (r * 1.5));
                    c = DigiTheme.mix(down ? KEY : KEY_LIGHT, down ? KEY_DEEP : KEY_DARK, t);
                    if (d > r - 1.2) c = DigiTheme.mix(c, KEY_DEEP, 0.5F);
                }
                px[(y + r + 2) * size + x + r + 2] = c;
            }
            if (!down) {
                int hx = (int) (-r * 0.45) + r + 2, hy = (int) (-r * 0.55) + r + 2;
                for (int i = 0; i < 3; i++) px[hy * size + hx + i] = 0xFFEAF2FF;
                int vx = (int) (-r * 0.55) + r + 2, vy = (int) (-r * 0.4) + r + 2;
                px[vy * size + vx] = 0xFFD6E6FF; px[(vy + 1) * size + vx] = 0xFFD6E6FF;
            }
            return px;
        });
    }

    // --- attributes ---
    private static final String[] VACCINE = {"................", "..############..", ".##############.", ".######++######.", ".######++######.", ".###++++++++###.", ".###++++++++###.", ".######++######.", ".######++######.", "..#####++#####..", "..############..", "...##########...", "....########....", ".....######.....", "......####......", ".......##......."};
    private static final String[] DATA = {"................", "....########....", "..############..", ".###++++++++###.", ".##############.", "..############..", ".#..########..#.", ".###........###.", ".##############.", "..############..", ".#..########..#.", ".###........###.", ".##############.", "..############..", "....########....", "................"};

    private static String[] virus() {
        String[] rows = new String[16];
        for (int y = 0; y < 16; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < 16; x++) {
                double dx = x - 7.5, dy = y - 7.5, d = Math.hypot(dx, dy), a = Math.atan2(dy, dx);
                long k = Math.round(a / (Math.PI / 4));
                double across = Math.abs(a - k * Math.PI / 4) * d;
                boolean spike = d >= 4 && d < 7.9 && across < (d > 6.3 ? 1.3 : 0.75);
                row.append(d < 1.9 ? '+' : d < 4.7 || spike ? '#' : '.');
            }
            rows[y] = row.toString();
        }
        return rows;
    }

    private static String[] free() {
        String[] rows = new String[16];
        for (int y = 0; y < 16; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < 16; x++) {
                double d = Math.hypot(x - 7.5, y - 7.5), degrees = Math.toDegrees(Math.atan2(y - 7.5, x - 7.5));
                boolean gap = degrees > -75 && degrees < -15;
                row.append(d > 4.2 && d < 6.9 && !gap ? '#' : Math.hypot(x - 11.5, y - 3.5) < 1.6 ? '+' : '.');
            }
            rows[y] = row.toString();
        }
        return rows;
    }

    private static String kind(DigimonAttribute attribute) {
        return switch (attribute) { case VACCINE -> "vaccine"; case DATA -> "data"; case VIRUS -> "virus"; default -> "free"; };
    }

    /** The colour an attribute wears in the Digivice. */
    public static int color(DigimonAttribute attribute) {
        return switch (attribute) { case VACCINE -> DigiTheme.TEAL; case DATA -> DigiTheme.DATA_LIGHT; case VIRUS -> DigiTheme.VIRUS; default -> DigiTheme.MUTED; };
    }

    /**
     * The 16-unit emblem: Vaccine a shield with a cross, Data a stack of platters, Virus a spiked cell with an eye,
     * anything else an open ring with a particle leaving it. The right and bottom rim is shaded.
     */
    public static Texture emblem(DigimonAttribute attribute) {
        String kind = kind(attribute);
        return texture("emblem_" + kind, 16, 16, () -> {
            String[] rows = switch (kind) { case "vaccine" -> VACCINE; case "data" -> DATA; case "virus" -> virus(); default -> free(); };
            int body = color(attribute), shade = DigiTheme.mix(body, DigiTheme.VOID, 0.45F), cut = kind.equals("free") ? DigiTheme.WHITE : DigiTheme.VOID;
            int[] px = new int[256];
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                char p = rows[y].charAt(x);
                if (p == '.') continue;
                boolean rim = y == 15 || rows[y + 1].charAt(x) == '.' || x == 15 || rows[y].charAt(x + 1) == '.';
                px[y * 16 + x] = p == '+' ? cut : rim ? shade : body;
            }
            return px;
        });
    }

    private static final Map<String, String[]> MARKS = Map.of(
            "vaccine", "#######|###.###|##...##|###.###|.#####.|..###..|...#...".split("\\|"),
            "data", ".#####.|#######|.......|#######|.......|#######|.#####.".split("\\|"),
            "virus", "#..#..#|.#####.|.##.##.|###.###|.##.##.|.#####.|#..#..#".split("\\|"),
            "free", ".####..|#....#.|#......|#.....#|#.....#|#.....#|.#####.".split("\\|"));

    /** The 7-unit mark for lists and tags, in {@code color}. */
    public static void mark(GuiGraphicsExtractor g, DigimonAttribute attribute, int x, int y, int color) {
        String kind = kind(attribute);
        bitmap("mark_" + kind, MARKS.get(kind)).draw(g, x, y, color);
    }

    // --- tabs ---
    private static final String[] ANALYZER_ICON = {"...####....", "..#....#...", ".#..##..#..", ".#.#..#.#..", ".#.#..#.#..", ".#..##..#..", "..#....##..", "...####.##.", ".........##", "..........#", "..........."};
    private static final String[] DIGISPACE_ICON = {"...#####...", "..#..#..#..", ".#...#...#.", ".#########.", "#....#....#", "#....#....#", ".#########.", ".#...#...#.", "..#..#..#..", "...#####...", "..........."};
    public static void analyzerIcon(GuiGraphicsExtractor g, int x, int y, int color) { bitmap("tab_analyzer", ANALYZER_ICON).draw(g, x, y, color); }
    public static void digispaceIcon(GuiGraphicsExtractor g, int x, int y, int color) { bitmap("tab_digispace", DIGISPACE_ICON).draw(g, x, y, color); }

    private static final String[] LENS = {".###...", "#...#..", "#...#..", "#...#..", ".###...", "....#..", ".....##", ".....##"};
    public static void lens(GuiGraphicsExtractor g, int x, int y, int color) { bitmap("lens", LENS).draw(g, x, y, color); }

    private static final String[] RUNES = {"#####.#|#...#.#|#.#.#.#|#.#...#|#.#####|#......|#######", "###.###|#.#.#.#|#.###.#|#.....#|#.###.#|#.#.#.#|###.###", "#######|......#|.####.#|.#..#.#|.#.##.#|.#....#|.######",
            "#.#####|#.#....|#.#.###|#.#.#.#|#.###.#|#.....#|#######", "..###..|.#...#.|#..#..#|#.###.#|#..#..#|.#...#.|..###..", "#######|#.....#|#.###.#|#.#.#.#|#.#.###|#.#....|#.#####",
            "#.....#|##...##|#.#.#.#|#..#..#|#.#.#.#|##...##|#.....#", "####...|...#...|.###.##|.#...#.|##.###.|...#...|...####"};
    /** Seven-unit glyphs in the spirit of DigiCode (invented shapes, not real letters); they run ahead of the power-on line. */
    public static void rune(GuiGraphicsExtractor g, int index, int x, int y, int color) {
        int i = Math.floorMod(index, RUNES.length);
        bitmap("rune_" + i, RUNES[i].split("\\|")).draw(g, x, y, color);
    }

    // --- the hand ---
    public enum Glove {
        POINT(4, 0, "....##..........", "...#oo#.........", "...#oo#.........", "...#oo#.........", "...#oo###.......", "...#oo#oo###....", "...#oo#oo#oo##..", ".###oo#oo#oo#o#.", "#oo#oooooooooo#.", "#oo#oooooooooo#.", ".#oooooooooooo#.", ".#ooooooooooos#.", "..#oooooooooss#.", "...#sssssssss#..", "...#bbbbbbbbb#..", "....#########..."),
        OPEN(7, 5, "......##........", ".....#oo#.##....", "..##.#oo##oo#...", ".#oo##oo##oo###.", ".#oo##oo##oo#oo#", ".#oo##oo##oo#oo#", "..#oooooooooooo#", "###ooooooooooos#", "#oo#oooooooooos#", "#ooooooooooooos#", ".#oooooooooooss#", "..#oooooooooss#.", "...#sssssssss#..", "...#bbbbbbbbb#..", "...#bbbbbbbbb#..", "....#########..."),
        GRAB(7, 7, "................", "................", "................", "...##.##.##.....", "..#oo#oo#oo###..", "..#oo#oo#oo#oo#.", ".##oo#oo#oo#oo#.", "#o#oooooooooos#.", "#oo#ooooooooos#.", "#oooooooooooss#.", ".#ooooooooooss#.", "..#ooooooooss#..", "...#sssssssss#..", "...#bbbbbbbbb#..", "...#bbbbbbbbb#..", "....#########...");

        final int hotX, hotY;
        final String[] rows;
        Glove(int hotX, int hotY, String... rows) { this.hotX = hotX; this.hotY = hotY; this.rows = rows; }
    }

    /** The white glove with a blue cuff that stands for the player in the Digispace, its hot spot on ({@code x}, {@code y}). */
    public static void glove(GuiGraphicsExtractor g, Glove glove, int x, int y) {
        texture("glove_" + glove.name().toLowerCase(java.util.Locale.ROOT), 17, 17, () -> {
            int[] px = new int[17 * 17];
            for (int pass = 0; pass < 2; pass++) for (int ry = 0; ry < 16; ry++) for (int rx = 0; rx < 16; rx++) {
                char p = glove.rows[ry].charAt(rx);
                if (p == '.') continue;
                if (pass == 0) px[(ry + 1) * 17 + rx + 1] = 0x70000000;
                else px[ry * 17 + rx] = p == '#' ? 0xFF141A24 : p == 'o' ? 0xFFF4F7FB : p == 's' ? 0xFFB9C6DA : KEY;
            }
            return px;
        }).draw(g, x - glove.hotX, y - glove.hotY);
    }

    // --- the Digispace ---
    public static Texture terrain(DigispaceWorld world) {
        return texture("terrain", DigispaceWorld.IMAGE_WIDTH, DigispaceWorld.IMAGE_HEIGHT, world::paint);
    }

    public static Texture prop(DigispaceWorld.PropType type) {
        DigispaceArt.Sprite sprite = DigispaceArt.of(type);
        return texture("prop_" + type.name().toLowerCase(java.util.Locale.ROOT), sprite.width(), sprite.height(), sprite::pixels);
    }
}
