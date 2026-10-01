package com.digicube.fabric.client.render;

import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The Burn mark's own look ({@link BurnedVisuals}, {@link BurningFlames}, {@link BurnParticle}), in place of vanilla's
 * sheet of fire: every particle's sprites are there; the flame's frames are warm, stand on their base and grow to a lick
 * before dying down; a burning body always shows several flames, standing out of its silhouette, tall enough to read,
 * fewer as the fire burns down;
 * the view's band of flames is as the renderer cuts it, stays low and wraps seamlessly as it drifts; the body's glow
 * warms its tint without hiding it, and never goes out while the body burns.
 */
public final class BurnVisualsRegressionTest {
    private static int checks;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static BufferedImage image(String path) throws Exception {
        try (InputStream in = BurnVisualsRegressionTest.class.getResourceAsStream("/assets/digicube/" + path)) {
            check(in != null, "missing " + path);
            return javax.imageio.ImageIO.read(in);
        }
    }

    private static List<String> sprites(String particle) throws Exception {
        try (InputStream in = BurnVisualsRegressionTest.class.getResourceAsStream("/assets/digicube/particles/" + particle + ".json")) {
            check(in != null, "missing particle definition " + particle);
            List<String> out = new ArrayList<>();
            JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in))).getAsJsonObject().getAsJsonArray("textures")
                    .forEach(t -> out.add(t.getAsString()));
            return out;
        }
    }

    public static void main(String[] args) throws Exception {
        // Every particle's sprites exist; smoke and steam play four frames.
        int[] frames = {1, 4, 4};
        String[] names = {"burn_ember", "burn_smoke", "burn_steam"};
        for (int i = 0; i < names.length; i++) {
            var list = sprites(names[i]);
            check(list.size() == frames[i], names[i] + " has " + frames[i] + " sprites: " + list);
            for (String sprite : list) image("textures/particle/" + sprite.substring(sprite.indexOf(':') + 1) + ".png");
        }

        // The flame: warm pixels only, standing on the bottom of its quad (where it is set on the body), growing to a
        // full lick partway through and dying down to a wisp.
        BufferedImage strip = image(BurningFlames.TEXTURE.getPath());
        check(strip.getWidth() == BurningFlames.FRAMES * BurningFlames.FRAME_W && strip.getHeight() == BurningFlames.FRAME_H,
                "the flame strip is " + BurningFlames.FRAMES + " frames of " + BurningFlames.FRAME_W + " x " + BurningFlames.FRAME_H);
        int[] heights = new int[BurningFlames.FRAMES];
        for (int f = 0; f < BurningFlames.FRAMES; f++) {
            BufferedImage flame = strip.getSubimage(f * BurningFlames.FRAME_W, 0, BurningFlames.FRAME_W, BurningFlames.FRAME_H);
            int top = flame.getHeight(), bottom = -1;
            for (int y = 0; y < flame.getHeight(); y++) for (int x = 0; x < flame.getWidth(); x++) {
                int c = flame.getRGB(x, y);
                if ((c >>> 24) == 0) continue;
                int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
                check(r >= g && g >= b && r > 120 && (c >>> 24) == 255, "flame frame " + f + " is warm and solid at " + x + "," + y + ": " + Integer.toHexString(c));
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
            check(bottom == flame.getHeight() - 1, "flame frame " + f + " stands on its base: lowest row " + bottom);
            heights[f] = bottom - top + 1;
        }
        int tallest = 0;
        for (int f = 1; f < BurningFlames.FRAMES; f++) if (heights[f] > heights[tallest]) tallest = f;
        check(tallest >= 2 && tallest <= 5 && heights[0] <= 4 && heights[BurningFlames.FRAMES - 1] < heights[tallest],
                "the flame grows to a lick and dies down: heights " + java.util.Arrays.toString(heights));

        // A burning body, small (Agumon's box) or big (Golemon's), always shows several flames at once, some of them
        // in full lick, standing out of its silhouette (round its edge or above its top) and tall enough to read; fewer
        // as the fire burns down.
        int fewest = Integer.MAX_VALUE;
        float shortestLick = Float.MAX_VALUE;
        for (float[] body : new float[][]{{.8F, 1.4F}, {1.6F, 2.6F}}) {
            for (float t = 0; t < 300; t += .5F) {
                var flames = BurningFlames.layout(body[0], body[1], 1, 4242, t);
                int licking = 0;
                boolean above = false;
                for (var flame : flames) {
                    double out = Math.hypot(flame.x(), flame.z());
                    boolean top = flame.y() > body[1] - .1F;
                    check(top ? out <= body[0] * .45 : Math.abs(out - body[0] * .5) < 1e-3,
                            "a flame stands on the body's top or at its edge: " + flame + " for " + java.util.Arrays.toString(body));
                    if (flame.frame() < 2 || flame.frame() > 5) continue;
                    licking++;
                    shortestLick = Math.min(shortestLick, flame.height());
                    above |= flame.y() + flame.height() * .6F > body[1];
                }
                fewest = Math.min(fewest, flames.size());
                check(licking >= 2 && above, "at " + t + " the body shows its fire: " + licking + " flames in full lick, one of them over its top " + above);
            }
            check(BurningFlames.layout(body[0], body[1], .05F, 4242, 0).size() < BurningFlames.layout(body[0], body[1], 1, 4242, 0).size(),
                    "fewer flames as the fire burns down");
        }
        check(fewest >= 4 && shortestLick > .18F, "always at least four flames, a full lick at least 0.18 blocks tall: " + fewest + ", " + shortestLick);

        // The view's band: the renderer's frames, low (most of the view stays clear), seamless as it drifts.
        BufferedImage band = image(BurnedVisuals.BAND.getPath());
        check(band.getWidth() == BurnedVisuals.BAND_W && band.getHeight() == BurnedVisuals.BAND_H * BurnedVisuals.BAND_FRAMES,
                "the band is " + BurnedVisuals.BAND_FRAMES + " frames of " + BurnedVisuals.BAND_W + " x " + BurnedVisuals.BAND_H + ": " + band.getWidth() + " x " + band.getHeight());
        double covered = 0;
        for (int f = 0; f < BurnedVisuals.BAND_FRAMES; f++) {
            int y0 = f * BurnedVisuals.BAND_H, filled = 0;
            for (int y = 0; y < BurnedVisuals.BAND_H; y++) for (int x = 0; x < BurnedVisuals.BAND_W; x++) if ((band.getRGB(x, y0 + y) >>> 24) > 0) filled++;
            covered = Math.max(covered, filled / (double) (BurnedVisuals.BAND_W * BurnedVisuals.BAND_H));
            for (int x = 0; x < BurnedVisuals.BAND_W; x++) check((band.getRGB(x, y0 + BurnedVisuals.BAND_H - 1) >>> 24) > 0, "frame " + f + " burns along its bottom edge at " + x);
            int left = flameTop(band, 0, y0), right = flameTop(band, BurnedVisuals.BAND_W - 1, y0);
            check(Math.abs(left - right) <= 3, "frame " + f + " wraps seamlessly: tongues at " + left + " and " + right);
        }
        check(covered < .6, "the band leaves most of its own strip clear: " + covered);

        // The glow: warmer (red over green over blue), never so dark the body is lost, and alight while it burns.
        int warmest = BurnedVisuals.tint(0xFFFFFFFF, 1);
        int r = warmest >> 16 & 255, g = warmest >> 8 & 255, b = warmest & 255;
        check(r == 255 && g > b && g >= 190 && b >= 150, "the glow warms the body without hiding it: " + Integer.toHexString(warmest));
        check(BurnedVisuals.tint(0xFFFFFFFF, 0) == 0xFFFFFFFF, "no glow leaves the tint as it was");
        float least = 1, most = 0;
        for (float burn : new float[]{.01F, .5F, 1}) for (float t = 0; t < 200; t += .25F) {
            float heat = BurnedVisuals.heat(burn, t, 17);
            least = Math.min(least, heat);
            most = Math.max(most, heat);
        }
        check(least > .4F && most <= 1, "the glow flickers but never goes out while the body burns: " + least + " to " + most);

        System.out.printf(Locale.ROOT, "Burn visuals checks passed: %d checks, flame heights %s, at least %d flames, licks at least %.2f blocks tall, band covering at most %.0f%% of its strip, glow %s%n",
                checks, java.util.Arrays.toString(heights), fewest, shortestLick, covered * 100, Integer.toHexString(warmest));
    }

    /** The highest lit row of a band frame's column. */
    private static int flameTop(BufferedImage band, int x, int y0) {
        for (int y = 0; y < BurnedVisuals.BAND_H; y++) if ((band.getRGB(x, y0 + y) >>> 24) > 0) return y;
        return BurnedVisuals.BAND_H;
    }
}
