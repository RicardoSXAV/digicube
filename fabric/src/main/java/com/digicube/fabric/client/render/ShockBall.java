package com.digicube.fabric.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.digicube.fabric.client.render.GlowRods.mix;
import static com.digicube.fabric.client.render.GlowRods.next;
import static com.digicube.fabric.client.render.GlowRods.unit;

/**
 * An electric ball's lightning (a kinetic shot with {@code "shot_style": "electric"}, Kabuterimon's Mega Blaster), drawn
 * about the ball's own model as glowing rods ({@link GlowRods}) in the ball's art: a pale pink-white core, magenta
 * strands, white sparks, mint forks. Every part is dealt again several times a tick from the shot's own seed, so the
 * lightning crackles instead of turning, and every client draws the same:
 * <ul>
 *   <li>a halo of jagged magenta lightning round the ball's outline, always facing the camera, broken in places, with
 *   tendrils leaping out of it (the ball reads as one from every side);</li>
 *   <li>arcs crawling over the ball;</li>
 *   <li>bolts leaping off it into the air, mostly behind and aside, and every few ticks one earthing in the ground under
 *   it when it flies low ({@link #earths});</li>
 *   <li>feelers reaching for every body within its shock's reach, so the danger shows before the shock lands;</li>
 *   <li>a wake of lightning along the stretch it just flew, thinning away;</li>
 *   <li>as it leaves the hands, a ring bursting out where it left;</li>
 *   <li>where it bursts: a ring sweeping out round the spot, a ring of lightning running out over the ground under it, a
 *   star of long bolts (those that reach the ground earth there), and crackling about the spot after.</li>
 * </ul>
 * The shock's bolt to each body struck and the struck bodies' own crackle are drawn elsewhere
 * ({@code KineticProjectileRenderer}, {@link ShockedBodies}).
 */
public final class ShockBall {
    private ShockBall() {}

    static final int CORE = 0xFFFFE8FF, MAGENTA = 0xFFE02AF0, WHITE = 0xFFFFFFFF, MINT = 0xFF6CF2AD;
    /** Lightning deals a tick: the flicker. */
    private static final float DEALS = 2.5F;
    /** The halo's radius against the ball's, and its rods' width (blocks). */
    private static final float HALO = 1.22F, HALO_WIDTH = .06F;
    /** Bolts leaping off the ball: how many a tick and the ticks each lasts. */
    private static final int LEAPS = 2, LEAP_LIFE = 2;
    /** Blocks under the ball within which it earths, and the ticks an earthing bolt lasts. */
    public static final float EARTH_REACH = 4.2F;
    private static final int EARTH_LIFE = 2;
    /** The burst: blocks its rings and long bolts reach, and the ticks of the ring, the ground ring and the long bolts. */
    private static final float BURST_REACH = 3.2F, RING_TICKS = 6, GROUND_TICKS = 7, STAR_TICKS = 4.5F;
    private static final int BURST_BOLTS = 10;
    /** Ticks the ring where the ball left the hands lasts. */
    private static final float RELEASE_TICKS = 3.5F;

    /** One frame of a ball, positions relative to the ball as drawn (blocks). */
    public static final class State {
        public boolean drawn;
        public int seed;
        /** Ticks since the ball appeared on this client, ticks since it burst (negative while it flies), the burst's length. */
        public float time, impact = -1, burstTicks = 9;
        /** The ball's radius (blocks) and its flight (blocks a tick). */
        public float radius = .4F, vx, vy, vz;
        /** The ground under the ball, relative (NaN when none is within reach). */
        public float groundY = Float.NaN;
        /** Chest points of the bodies within its shock's reach. */
        public final float[] feelers = new float[3 * 8];
        public int feelerCount;
        /** Where the ball was over its last ticks, newest first. */
        public final float[] wake = new float[3 * 8];
        public int wakeCount;
        /** Where the ball left the hands, relative; valid when {@code released}. */
        public float rx, ry, rz;
        public boolean released;
        final GlowRods rods = new GlowRods();
    }

    /**
     * Whether a ball with this seed earths a bolt in the ground at this tick (when it flies within {@link #EARTH_REACH} of
     * it): about one tick in three, the same on every client and for its sound.
     */
    public static boolean earths(int seed, int tick) {
        return tick > 0 && Math.floorMod((int) (next(mix(seed, 7, tick)) >>> 41), 3) == 0;
    }

    /**
     * Draws a frame of the ball's lightning at {@code pose} (the ball's drawn position); {@code camera} is the view's
     * orientation and {@code tx, ty, tz} the unit way from the ball to the camera.
     */
    public static void submit(State s, PoseStack pose, SubmitNodeCollector collector, Quaternionf camera, float tx, float ty, float tz) {
        var rods = s.rods;
        rods.clear();
        Vector3f right = camera.transform(new Vector3f(1, 0, 0)), up = camera.transform(new Vector3f(0, 1, 0));
        float[] toward = {tx, ty, tz};
        if (s.impact < 0) flying(s, rods, right, up, toward);
        else burst(s, rods, right, up, toward);
        rods.submit(pose, collector);
    }

    private static void flying(State s, GlowRods rods, Vector3f right, Vector3f up, float[] toward) {
        float t = s.time, r = s.radius;
        int deal = (int) (t * DEALS);
        // the halo, breathing a little
        float breathe = 1 + .05F * Mth.sin(t * 2.7F);
        ring(rods, 0, 0, 0, right.x, right.y, right.z, up.x, up.y, up.z, toward, r * HALO * breathe, HALO_WIDTH, .12F, 3, .1F, mix(s.seed, 1, deal));
        // arcs crawling over the ball, each dealt on its own beat
        for (int k = 0; k < 2; k++) crawl(rods, r, .045F, mix(s.seed, 10 + k, (int) (t * 1.6F + k * .5F)));
        // bolts leaping off it, mostly behind and aside
        float speed = Mth.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz);
        float bx = speed > 1.0E-3F ? -s.vx / speed : 0, by = speed > 1.0E-3F ? -s.vy / speed : 0, bz = speed > 1.0E-3F ? -s.vz / speed : 0;
        int now = (int) t;
        for (int age = 0; age < LEAP_LIFE; age++) {
            int born = now - age;
            if (born < 0) continue;
            float life = (t - born) / LEAP_LIFE;
            for (int j = 0; j < LEAPS; j++) {
                long rng = mix(s.seed, 100 + j, born);
                rng = next(rng); float dx = unit(rng) + bx * .7F;
                rng = next(rng); float dy = unit(rng) * .8F + by * .7F;
                rng = next(rng); float dz = unit(rng) + bz * .7F;
                float l = Mth.sqrt(dx * dx + dy * dy + dz * dz);
                if (l < 1.0E-3F) continue;
                dx /= l; dy /= l; dz /= l;
                rng = next(rng); float reach = r * (1.6F + 1.7F * (unit(rng) * .5F + .5F));
                float w = .05F * (1 - .6F * life);
                long jag = mix(s.seed, 200 + j, deal);
                jag = rods.bolt(dx * r, dy * r, dz * r, dx * (r + reach), dy * (r + reach), dz * (r + reach), w, CORE, .18F, .22F, jag, 1, MINT);
                rods.bolt(dx * r, dy * r, dz * r, dx * (r + reach), dy * (r + reach), dz * (r + reach), w * .55F, MAGENTA, .24F, .22F, jag, 0, 0);
            }
        }
        // a bolt earthing in the ground under it now and then, when it flies low
        if (!Float.isNaN(s.groundY) && s.groundY > -EARTH_REACH) {
            for (int age = 0; age < EARTH_LIFE; age++) {
                int born = now - age;
                if (!earths(s.seed, born)) continue;
                long rng = mix(s.seed, 300, born);
                rng = next(rng); float gx = unit(rng) * .9F;
                rng = next(rng); float gz = unit(rng) * .9F;
                float w = .065F * (1 - .5F * (t - born) / EARTH_LIFE);
                long jag = mix(s.seed, 301, deal);
                jag = rods.bolt(0, -r, 0, gx, s.groundY + .02F, gz, w, CORE, .3F, .3F, jag, 2, MINT);
                jag = rods.bolt(0, -r, 0, gx, s.groundY + .02F, gz, w * .55F, MAGENTA, .36F, .3F, jag, 0, 0);
                rods.star(gx, s.groundY + .04F, gz, .38F, w * .7F, MAGENTA, CORE, jag, 6);
            }
        }
        // feelers reaching for the bodies within its reach, flickering
        for (int i = 0; i < s.feelerCount; i++) {
            float fx = s.feelers[i * 3], fy = s.feelers[i * 3 + 1], fz = s.feelers[i * 3 + 2];
            float l = Mth.sqrt(fx * fx + fy * fy + fz * fz);
            if (l <= r * 1.2F) continue;
            long rng = mix(s.seed, 400 + i, deal);
            rng = next(rng);
            if (unit(rng) < -.3F) continue;
            rng = next(rng); float reach = .55F + .35F * (unit(rng) * .5F + .5F);
            float sx = fx / l * r * 1.05F, sy = fy / l * r * 1.05F, sz = fz / l * r * 1.05F;
            float ex = sx + (fx - sx) * reach, ey = sy + (fy - sy) * reach, ez = sz + (fz - sz) * reach;
            rng = rods.bolt(sx, sy, sz, ex, ey, ez, .04F, CORE, .2F, .28F, rng, 1, MINT);
            rods.bolt(sx, sy, sz, ex, ey, ez, .022F, MAGENTA, .26F, .28F, rng, 0, 0);
        }
        // the wake along the stretch it just flew
        float px = 0, py = 0, pz = 0;
        for (int k = 0; k < s.wakeCount; k++) {
            float qx = s.wake[k * 3], qy = s.wake[k * 3 + 1], qz = s.wake[k * 3 + 2];
            float w = .045F * (1 - (k + .5F) / s.wakeCount);
            if (w > .006F) {
                long rng = mix(s.seed, 500 + k, deal);
                rng = rods.bolt(px, py, pz, qx, qy, qz, w, CORE, .14F, .3F, rng, 0, 0);
                rods.bolt(px, py, pz, qx, qy, qz, w * .6F, MAGENTA, .2F, .3F, rng, 0, 0);
            }
            px = qx; py = qy; pz = qz;
        }
        // the ring where it left the hands
        if (s.released && t < RELEASE_TICKS) {
            float u = t / RELEASE_TICKS, eased = 1 - (1 - u) * (1 - u);
            long rng = mix(s.seed, 600, deal);
            ring(rods, s.rx, s.ry, s.rz, right.x, right.y, right.z, up.x, up.y, up.z, toward, r * (1.1F + 2.2F * eased),
                    .07F * (1 - u) + .012F, .15F + .5F * u, u < .4F ? 5 : 0, .14F, rng);
        }
    }

    private static void burst(State s, GlowRods rods, Vector3f right, Vector3f up, float[] toward) {
        float t = s.impact, r = s.radius;
        int deal = (int) (t * DEALS);
        // the ring sweeping out round the spot
        if (t < RING_TICKS) {
            float u = t / RING_TICKS, eased = 1 - (1 - u) * (1 - u) * (1 - u);
            ring(rods, 0, 0, 0, right.x, right.y, right.z, up.x, up.y, up.z, toward, r * HALO + (BURST_REACH * .85F - r * HALO) * eased,
                    .09F * (1 - u) + .014F, .08F + .55F * u, t < 2 ? 6 : 2, .12F, mix(s.seed, 700, deal));
        }
        // a ring of lightning running out over the ground under it
        boolean grounded = !Float.isNaN(s.groundY) && s.groundY > -2.8F;
        if (grounded && t < GROUND_TICKS) {
            float u = t / GROUND_TICKS, eased = 1 - (1 - u) * (1 - u);
            ring(rods, 0, s.groundY + .04F, 0, 1, 0, 0, 0, 0, 1, new float[]{0, 1, 0}, .4F + BURST_REACH * eased,
                    .075F * (1 - u) + .014F, .1F + .5F * u, 3, .1F, mix(s.seed, 710, deal));
        }
        // a star of long bolts; those that reach the ground earth there
        if (t < STAR_TICKS) {
            float out = Mth.clamp(t / 1.2F, 0, 1), grow = 1 - (1 - out) * (1 - out);
            float fade = t < 2.5F ? 1 : 1 - (t - 2.5F) / (STAR_TICKS - 2.5F);
            for (int i = 0; i < BURST_BOLTS; i++) {
                long rng = mix(s.seed, 800 + i, 0);
                rng = next(rng); float dx = unit(rng);
                rng = next(rng); float dy = unit(rng) * .65F - (grounded ? .15F : 0);
                rng = next(rng); float dz = unit(rng);
                float l = Mth.sqrt(dx * dx + dy * dy + dz * dz);
                if (l < 1.0E-3F) continue;
                dx /= l; dy /= l; dz /= l;
                rng = next(rng); float reach = BURST_REACH * (.55F + .45F * (unit(rng) * .5F + .5F)) * grow;
                float sx = dx * r * .8F, sy = dy * r * .8F, sz = dz * r * .8F;
                float ex = sx + dx * reach, ey = sy + dy * reach, ez = sz + dz * reach;
                boolean earthed = grounded && ey < s.groundY;
                if (earthed) {
                    // cut where it meets the ground
                    float k = (s.groundY + .02F - sy) / (ey - sy);
                    ex = sx + (ex - sx) * k; ey = s.groundY + .02F; ez = sz + (ez - sz) * k;
                }
                float w = .085F * fade;
                long jag = mix(s.seed, 820 + i, deal);
                jag = rods.bolt(sx, sy, sz, ex, ey, ez, w, CORE, .3F, .34F, jag, 2, MINT);
                jag = rods.bolt(sx, sy, sz, ex, ey, ez, w * .55F, MAGENTA, .38F, .34F, jag, 0, 0);
                if (earthed && t > .8F) rods.star(ex, ey + .02F, ez, .32F * fade + .08F, w * .7F, MAGENTA, CORE, jag, 5);
            }
        }
        // crackling about the spot after
        if (t >= 1.5F && t < s.burstTicks) {
            float fade = Mth.clamp((s.burstTicks - t) / 3, 0, 1);
            for (int i = 0; i < 2; i++) {
                long rng = mix(s.seed, 900 + i, (int) (t * 1.5F));
                float[] a = new float[3], b = new float[3];
                for (float[] p : new float[][]{a, b}) {
                    rng = next(rng); p[0] = unit(rng) * 1.3F;
                    rng = next(rng); p[1] = unit(rng) * .9F;
                    rng = next(rng); p[2] = unit(rng) * 1.3F;
                    if (grounded) p[1] = Math.max(p[1], s.groundY + .05F);
                }
                rng = rods.bolt(a[0], a[1], a[2], b[0], b[1], b[2], .045F * fade, CORE, .2F, .26F, rng, 1, MINT);
                rods.bolt(a[0], a[1], a[2], b[0], b[1], b[2], .025F * fade, MAGENTA, .26F, .26F, rng, 0, 0);
            }
        }
    }

    /**
     * A jagged ring of lightning about (cx, cy, cz) in the plane of the unit, perpendicular (a) and (b): bends at
     * {@code radius} give or take {@code rough} of it, a {@code gaps} share of its stretches left out, {@code tendrils}
     * leaping outward from it; magenta, with a white thread laid on its {@code toward} side.
     */
    private static long ring(GlowRods rods, float cx, float cy, float cz, float ax, float ay, float az, float bx, float by, float bz,
                             float[] toward, float radius, float width, float gaps, int tendrils, float rough, long rng) {
        int n = Math.max(12, Math.min(28, (int) (radius * 22)));
        float[] x = new float[n], y = new float[n], z = new float[n];
        rng = next(rng);
        float turn = unit(rng) * Mth.PI;
        for (int i = 0; i < n; i++) {
            rng = next(rng); float a = turn + Mth.TWO_PI * i / n + unit(rng) * .12F;
            rng = next(rng); float rr = radius * (1 + rough * unit(rng));
            float c = Mth.cos(a) * rr, s = Mth.sin(a) * rr;
            x[i] = cx + ax * c + bx * s; y[i] = cy + ay * c + by * s; z[i] = cz + az * c + bz * s;
        }
        float lift = width * .62F, lx = toward[0] * lift, ly = toward[1] * lift, lz = toward[2] * lift;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            rng = next(rng);
            if (unit(rng) * .5F + .5F < gaps) continue;
            rods.rod(x[i], y[i], z[i], x[j], y[j], z[j], width, MAGENTA);
            rods.rod(x[i] + lx, y[i] + ly, z[i] + lz, x[j] + lx, y[j] + ly, z[j] + lz, width * .38F, WHITE);
        }
        for (int k = 0; k < tendrils; k++) {
            rng = next(rng);
            int i = (int) ((rng >>> 33) % n);
            float ox = x[i] - cx, oy = y[i] - cy, oz = z[i] - cz, l = Mth.sqrt(ox * ox + oy * oy + oz * oz);
            if (l < 1.0E-4F) continue;
            ox /= l; oy /= l; oz /= l;
            rng = next(rng); float reach = radius * (.35F + .35F * (unit(rng) * .5F + .5F));
            rng = next(rng); float side = unit(rng) * reach * .3F;
            // aside, in the ring's plane: its normal (a x b) crossed with the way out
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float qx = ny * oz - nz * oy, qy = nz * ox - nx * oz, qz = nx * oy - ny * ox;
            float mx = x[i] + ox * reach * .5F + qx * side, my = y[i] + oy * reach * .5F + qy * side, mz = z[i] + oz * reach * .5F + qz * side;
            float ex = x[i] + ox * reach, ey = y[i] + oy * reach, ez = z[i] + oz * reach;
            rods.rod(x[i], y[i], z[i], mx, my, mz, width * .8F, MAGENTA);
            rods.rod(mx, my, mz, ex, ey, ez, width * .6F, MAGENTA);
            rods.rod(x[i] + lx, y[i] + ly, z[i] + lz, mx + lx, my + ly, mz + lz, width * .3F, WHITE);
        }
        return rng;
    }

    /** An arc crawling over the ball: a stretch of a great circle a little out from its surface, jagged, white-cored. */
    private static void crawl(GlowRods rods, float radius, float width, long rng) {
        rng = next(rng); float nx = unit(rng);
        rng = next(rng); float ny = unit(rng);
        rng = next(rng); float nz = unit(rng);
        float nl = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0E-3F) return;
        nx /= nl; ny /= nl; nz /= nl;
        float[] u = GlowRods.perpendicular(nx, ny, nz), v = GlowRods.cross(nx, ny, nz, u);
        rng = next(rng); float start = unit(rng) * Mth.PI;
        rng = next(rng); float sweep = 1.1F + .9F * (unit(rng) * .5F + .5F);
        int m = 7;
        float px = 0, py = 0, pz = 0, sx = 0, sy = 0, sz = 0;
        for (int i = 0; i <= m; i++) {
            float a = start + sweep * i / m, c = Mth.cos(a), s = Mth.sin(a);
            rng = next(rng); float rr = radius * (1.06F + .08F * unit(rng));
            rng = next(rng); float strand = radius * (1.06F + .1F * unit(rng));
            float x = (u[0] * c + v[0] * s), y = (u[1] * c + v[1] * s), z = (u[2] * c + v[2] * s);
            if (i > 0) {
                rods.rod(px, py, pz, x * rr, y * rr, z * rr, width, CORE);
                rods.rod(sx, sy, sz, x * strand, y * strand, z * strand, width * .6F, MAGENTA);
            }
            px = x * rr; py = y * rr; pz = z * rr;
            sx = x * strand; sy = y * strand; sz = z * strand;
        }
    }
}
