package com.digicube.fabric.client.model;

import com.digicube.digimon.ConstrictionCoil;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.util.Mth;

/**
 * A serpent's body wound round its prey ({@link ConstrictionCoil}): where each point of the chain lies on the coil, and how
 * far it has come there from where it lay on its trail. The head looms over the prey, looking down at it (beside a prey
 * too tall to loom over, facing it); the neck drops from under it to the top loop, which starts on the head's side of the
 * prey and winds away from it; the loops go round the prey's box, pressed against it, each a girth under the one before;
 * the tail leaves the bottom loop, sinks to the ground and curls away. On a squeeze the loops draw in and wind a little
 * tighter. {@link SerpentSpine} lays the links along the points it leaves.
 */
final class SerpentCoil {
    private SerpentCoil() {}

    /** Radians round the prey from the head's side that the top loop starts, on the way it winds. */
    private static final double ENTRY = Math.toRadians(15);
    /** Share of the straight way from the head to the top loop that each end of the neck's curve reaches along its line. */
    private static final double REACH = .45;
    /** Blocks the head's base stays over the top loop at least, and over the prey's top when it looms over it. */
    private static final double NECK_RISE = .8, CLEAR = .45;
    /** The highest the head's base rears over the coil, blocks over the clips' own height. */
    private static final double REAR_MORE = .3;
    /** Share of the prey's hugged radius out from its axis the head's base looms at; beside a tall prey, blocks off its side. */
    private static final double LOOM = .45, BESIDE = .75;
    /** Share of a loop's radius the loops draw in by at a squeeze's peak, and radians the bottom loop winds on round. */
    private static final double PRESS = .08, TWIST = .09;
    /** The tightest a loop goes round (blocks, to the body's middle): a link bends no further than a joint can. */
    private static final double TIGHTEST = .58;
    /** Blocks the tail runs on from the bottom loop before it lies on the ground, and radians a block it curls away. */
    private static final double TAIL_DROP = 1.8, TAIL_CURL = .45;
    /** Points sampled along the neck's curve to measure it, and blocks between the points the body's way is drawn at. */
    private static final int NECK_SAMPLES = 32;
    private static final double FINE = .1;
    /** Share of its way round a point has closed in on the prey when it is this far through its swing. */
    private static final double CLOSE_FIRST = 1.5;

    /**
     * Winds the chain's points ({@code world}, x y z a point: the pose along its trail) onto the coil, each part of the body
     * by its own share ({@link ConstrictionCoil#onCoil}). Every stretch of the body swings round the prey's axis from where
     * it lay to its place on the coil, closing in as it goes, so the body is thrown round the prey front first and comes
     * off it the same way; the links are then laid along that way by their own lengths from the head (swung part way round
     * at a wider radius the way is longer than the body, and the tail simply does not reach its end).
     * @param arc    blocks along the chain from its first point, a point
     * @param radius the body's half-thickness at each point (blocks)
     * @param rest   blocks over the feet the clips hold the head's base
     */
    static void wind(double[] world, double[] arc, double[] radius, double rest, DigimonRenderState.Wrap wrap) {
        int n = arc.length;
        var shape = wrap.shape;
        double total = Math.max(1.0E-3, arc[n - 1]);
        if (ConstrictionCoil.onCoil(wrap.since, 0) <= 0 && ConstrictionCoil.onCoil(wrap.since, 1) <= 0) return;
        double cx = wrap.x, cy = wrap.y, cz = wrap.z, w = wrap.winding >= 0 ? 1 : -1;
        double press = ConstrictionCoil.squeeze(wrap.since);
        double girth = wrap.girth / 2, hug = shape.hug();
        // the head's base: over the prey on the side its feet stand, looking down at it; a prey taller than it rears is
        // faced from beside it instead
        double headAngle = Math.atan2(world[2] - cz, world[0] - cx);
        double highest = rest + REAR_MORE, over = shape.height() + CLEAR;
        boolean looms = over <= highest;
        double hy = cy + Math.max(shape.top() + NECK_RISE, looms ? over : Math.min(highest, shape.height() - CLEAR));
        double out = looms ? hug * LOOM : hug / ConstrictionCoil.HUG + BESIDE;
        double hx = cx + out * Math.cos(headAngle), hz = cz + out * Math.sin(headAngle);
        // the top loop's start and its way on: round the prey the way it winds, sinking as the loops go down
        double turn = 2 * Math.PI * shape.loops(), round = Math.max(TIGHTEST, hug + girth);
        double entry = headAngle + w * ENTRY, inner = round * (1 - PRESS * press);
        double ex = cx + inner * Math.cos(entry), ey = cy + shape.top(), ez = cz + inner * Math.sin(entry);
        double[] into = unit(-Math.sin(entry) * w, -(shape.top() - shape.bottom()) / (turn * round), Math.cos(entry) * w);
        // the neck: from the head's base along the line the clips give it, curving into the top loop
        double[] leave = unit(world[3] - world[0], world[4] - world[1], world[5] - world[2]);
        double reach = REACH * Math.sqrt(sq(ex - hx) + sq(ey - hy) + sq(ez - hz));
        double[] p1 = {hx + leave[0] * reach, hy + leave[1] * reach, hz + leave[2] * reach};
        double[] p2 = {ex - into[0] * reach, ey - into[1] * reach, ez - into[2] * reach};
        double[][] neck = new double[NECK_SAMPLES + 1][];
        double[] along = new double[NECK_SAMPLES + 1];
        for (int k = 0; k <= NECK_SAMPLES; k++) {
            double t = k / (double) NECK_SAMPLES, a = (1 - t) * (1 - t) * (1 - t), b = 3 * (1 - t) * (1 - t) * t, c = 3 * (1 - t) * t * t, d = t * t * t;
            neck[k] = new double[]{a * hx + b * p1[0] + c * p2[0] + d * ex, a * hy + b * p1[1] + c * p2[1] + d * ey, a * hz + b * p1[2] + c * p2[2] + d * ez};
            if (k > 0) along[k] = along[k - 1] + Math.sqrt(sq(neck[k][0] - neck[k - 1][0]) + sq(neck[k][1] - neck[k - 1][1]) + sq(neck[k][2] - neck[k - 1][2]));
        }
        var coil = new Coil(cx, cy, cz, w, shape, neck, along, turn, round, entry, inner, press, arc, radius);
        // the body's way this moment: every stretch swung round the prey from its trail toward the coil
        int m = (int) Math.ceil(total / FINE) + 1;
        double[] way = new double[3 * m];
        double[] onTrail = new double[3], onCoil = new double[4];
        for (int j = 0; j < m; j++) {
            double a = Math.min(total, j * FINE), s = ConstrictionCoil.onCoil(wrap.since, (float) (a / total));
            trailAt(world, arc, a, onTrail);
            if (j == 0) {
                // the head's base goes straight to where it looms
                way[0] = Mth.lerp(s, onTrail[0], hx); way[1] = Mth.lerp(s, onTrail[1], hy); way[2] = Mth.lerp(s, onTrail[2], hz);
                continue;
            }
            coil.at(a, headAngle, onCoil);
            double tx = onTrail[0] - cx, tz = onTrail[2] - cz, kx = onCoil[0] - cx, kz = onCoil[2] - cz;
            double from = headAngle + Mth.wrapDegrees(Math.toDegrees(Math.atan2(tz, tx) - headAngle)) * Mth.DEG_TO_RAD;
            double close = Mth.smoothstep(Math.min(1, s * CLOSE_FIRST));
            double rho = Mth.lerp(close, Math.sqrt(tx * tx + tz * tz), Math.sqrt(kx * kx + kz * kz));
            double angle = Mth.lerp(s, from, onCoil[3]);
            way[3 * j] = cx + rho * Math.cos(angle);
            way[3 * j + 1] = Mth.lerp(s, onTrail[1], onCoil[1]);
            way[3 * j + 2] = cz + rho * Math.sin(angle);
        }
        // the links along that way by their lengths, from the head
        world[0] = way[0]; world[1] = way[1]; world[2] = way[2];
        double gone = 0;
        int j = 0;
        for (int i = 1; i < n; i++) {
            double want = arc[i];
            while (j + 1 < m && gone + step(way, j) < want) {
                gone += step(way, j);
                j++;
            }
            if (j + 1 < m) {
                double f = Math.clamp((want - gone) / Math.max(1.0E-9, step(way, j)), 0, 1);
                for (int c = 0; c < 3; c++) world[3 * i + c] = way[3 * j + c] + (way[3 * j + 3 + c] - way[3 * j + c]) * f;
            } else {
                // the way came out shorter than the body: on along its last line
                int last = m - 1, before = Math.max(0, m - 2);
                double[] d = unit(way[3 * last] - way[3 * before], way[3 * last + 1] - way[3 * before + 1], way[3 * last + 2] - way[3 * before + 2]);
                for (int c = 0; c < 3; c++) world[3 * i + c] = way[3 * last + c] + d[c] * (want - gone);
            }
            // laid by length, a link may land where the way was drawn for a thinner stretch: its own underside stays on the
            // prey's floor, by as much as it has come onto the coil
            double s = ConstrictionCoil.onCoil(wrap.since, (float) (arc[i] / total));
            world[3 * i + 1] = Math.max(world[3 * i + 1], cy + radius[i] * s);
        }
    }

    /** Blocks from point {@code j} of the way to the next. */
    private static double step(double[] way, int j) {
        return Math.sqrt(sq(way[3 * j + 3] - way[3 * j]) + sq(way[3 * j + 4] - way[3 * j + 1]) + sq(way[3 * j + 5] - way[3 * j + 2]));
    }

    /** A point of the chain's trail pose {@code a} blocks along it from the head (straight between its points). */
    private static void trailAt(double[] world, double[] arc, double a, double[] out) {
        int n = arc.length, i = 1;
        while (i < n - 1 && arc[i] < a) i++;
        double f = Math.clamp((a - arc[i - 1]) / Math.max(1.0E-9, arc[i] - arc[i - 1]), 0, 1);
        for (int c = 0; c < 3; c++) out[c] = world[3 * (i - 1) + c] + (world[3 * i + c] - world[3 * (i - 1) + c]) * f;
    }

    /** The coil round the prey as one way from the head: the neck's curve, the loops, then the tail. */
    private record Coil(double cx, double cy, double cz, double w, ConstrictionCoil.Shape shape, double[][] neck, double[] along,
                        double turn, double round, double entry, double inner, double press, double[] arc, double[] radius) {
        /**
         * The body's middle {@code a} blocks from the head on the coil ({@code out} x, y, z) and its angle round the prey
         * ({@code out[3]}), unwound from the head's so that it goes on growing round the loops.
         */
        void at(double a, double headAngle, double[] out) {
            double neckLength = along[NECK_SAMPLES], loopLength = turn * round;
            if (a <= neckLength) {
                int k = 1;
                while (k < NECK_SAMPLES && along[k] < a) k++;
                double u = (a - along[k - 1]) / Math.max(1.0E-9, along[k] - along[k - 1]);
                out[0] = Mth.lerp(u, neck[k - 1][0], neck[k][0]); out[1] = Mth.lerp(u, neck[k - 1][1], neck[k][1]); out[2] = Mth.lerp(u, neck[k - 1][2], neck[k][2]);
                out[3] = headAngle + Mth.wrapDegrees(Math.toDegrees(Math.atan2(out[2] - cz, out[0] - cx) - headAngle)) * Mth.DEG_TO_RAD;
                return;
            }
            double endAngle = entry + w * (turn + TWIST * press);
            if (a <= neckLength + loopLength) {
                double f = (a - neckLength) / loopLength;
                double r = Math.max(TIGHTEST, shape.hug() + radiusAt(a)) * (1 - PRESS * press);
                double angle = entry + w * (turn + TWIST * press) * f;
                // down the loops, the thickest links' undersides never under the floor
                out[0] = cx + r * Math.cos(angle); out[2] = cz + r * Math.sin(angle);
                out[1] = cy + Math.max(radiusAt(a), shape.top() + (shape.bottom() - shape.top()) * f);
                out[3] = angle;
                return;
            }
            // the tail: on from the bottom loop along its way round, curling off the coil and sinking to the ground
            double d = a - neckLength - loopLength, heading = endAngle + w * Math.PI / 2;
            double x = cx + inner * Math.cos(endAngle), z = cz + inner * Math.sin(endAngle);
            for (double gone = 0; gone < d; gone += FINE) {
                double step = Math.min(FINE, d - gone);
                x += Math.cos(heading) * step; z += Math.sin(heading) * step;
                heading -= w * TAIL_CURL * step;
            }
            double r = radiusAt(a);
            out[0] = x; out[2] = z;
            out[1] = cy + Math.max(r, shape.bottom() - (shape.bottom() - r) * Mth.smoothstep(Math.clamp(d / TAIL_DROP, 0, 1)));
            out[3] = endAngle + Mth.wrapDegrees(Math.toDegrees(Math.atan2(z - cz, x - cx) - endAngle)) * Mth.DEG_TO_RAD;
        }

        /** The body's half-thickness {@code a} blocks from the head. */
        private double radiusAt(double a) {
            int n = arc.length, i = 1;
            while (i < n - 1 && arc[i] < a) i++;
            double f = Math.clamp((a - arc[i - 1]) / Math.max(1.0E-9, arc[i] - arc[i - 1]), 0, 1);
            return radius[i - 1] + (radius[i] - radius[i - 1]) * f;
        }
    }

    private static double sq(double v) { return v * v; }

    private static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return l < 1.0E-9 ? new double[]{0, -1, 0} : new double[]{x / l, y / l, z / l};
    }
}
