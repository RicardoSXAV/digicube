package com.digicube.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The path a serpent's head took (a body with {@code body.serpent} on its sheet): the places its feet went through, the
 * newest first, sampled by distance behind the head ({@link #sample}) or laid out as a fine level line ({@link #line})
 * whose points keep their places on the ground as the head goes on. Its body lies along it, so it goes where its head
 * went and never through the ground or a wall beside the way: the server lays its hit parts on it, a client its drawn
 * body. Backing up, the head takes back the trail it comes over; a teleport, or a first sight of the body, lays it out
 * afresh behind the head, bending away from blocks.
 */
public final class SerpentTrail {
    /** Blocks between kept points, and the jump in a tick past which the head was moved, not walked. */
    public static final double SPACING = .1, TELEPORT = 4;
    /** Times its level length the trail is kept for at most, counted whole (up and down faces too). */
    private static final double KEEP_WHOLE = 4;
    /** Blocks between the points of a fresh body laid out behind its head, and the turns tried to go round a block. */
    private static final double LAY_STEP = .25;
    private static final int[] LAY_TURNS = {0, 15, -15, 30, -30, 45, -45, 60, -60, 90, -90, 120, -120};
    /** Blocks around a sampled point its tangent is read over: steadier than the one segment it lies on. */
    private static final double TANGENT_SPAN = .35;
    /**
     * Blocks of the line's plan smoothed together (a bell's spread), narrowing to nothing at the head: a corner the head
     * turned on the spot, or a small loop it walked, is rounded off to a bend the body can take.
     */
    private static final double SMOOTH = .4;

    private final double length;
    private final ArrayDeque<Vec3> points = new ArrayDeque<>();
    /** Each point's place along the way the head went, level, counted from where the trail was last laid. */
    private final ArrayDeque<Double> marks = new ArrayDeque<>();
    private Vec3 head;
    private double clock;
    private int lays;

    /** @param length blocks of trail kept behind the head: the body's length and a margin */
    public SerpentTrail(double length) {
        this.length = length;
    }

    /** Where the head is (its feet), or null before the trail is laid. */
    public Vec3 head() { return head; }

    /**
     * Blocks the head has gone along its own heading since the trail began, times {@code rate} (backing up counts down):
     * a clock a lateral wave can run on, so the wave stays where it is on the ground as the body slides through it.
     */
    public double clock() { return clock; }

    /** Runs the clock on by time alone: a swimmer's body waves at rest too. */
    public void tick(double blocks) { clock += blocks; }

    /** How many times the trail was laid afresh (a teleport, a first sight): the line's steps count from there. */
    public int lays() { return lays; }

    /** The head's place along the way it went, level, counted from where the trail was last laid (the line's steps). */
    public double mark() {
        if (points.isEmpty()) return 0;
        Vec3 first = points.peekFirst();
        return marks.peekFirst() + Math.hypot(head.x - first.x, head.z - first.z);
    }

    /**
     * The way the body runs up to the head (degrees, as an entity's yaw): level, from the trail {@code back} blocks behind
     * the head to the head. NaN before the trail is laid, or where it runs up or down there more than along (a climb, a
     * dive), when it has no heading to speak of.
     */
    public float heading(double back) {
        if (head == null) return Float.NaN;
        double[] at = new double[3], tangent = new double[3];
        sample(new double[]{back}, at, tangent);
        double dx = head.x - at[0], dz = head.z - at[2];
        if (dx * dx + dz * dz < back * back / 4) return Float.NaN;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Lays the body out straight behind a head facing {@code yaw}, bending round any block in the way. */
    public void lay(Vec3 feet, float yaw, BlockGetter level, double lift) {
        lays++;
        points.clear();
        marks.clear();
        double angle = yaw * Mth.DEG_TO_RAD, dx = Math.sin(angle), dz = -Math.cos(angle);
        Vec3 at = feet;
        double mark = 0;
        for (int i = 0; i < (int) Math.ceil((length + 2) / LAY_STEP); i++) {
            Vec3 next = null;
            for (int turn : LAY_TURNS) {
                double c = Math.cos(turn * Mth.DEG_TO_RAD), s = Math.sin(turn * Mth.DEG_TO_RAD);
                double tx = dx * c - dz * s, tz = dx * s + dz * c;
                Vec3 tried = at.add(tx * LAY_STEP, 0, tz * LAY_STEP);
                if (level == null || !solid(level, tried.x, tried.y + lift, tried.z)) { next = tried; dx = tx; dz = tz; break; }
            }
            Vec3 was = at;
            at = next != null ? next : at.add(dx * LAY_STEP, 0, dz * LAY_STEP);
            mark -= Math.hypot(at.x - was.x, at.z - was.z);
            points.addLast(at);
            marks.addLast(mark);
        }
        head = feet;
    }

    /**
     * The head is now at {@code feet}, facing {@code yaw}: the trail follows it (laid afresh after a teleport or before
     * it was ever laid) and the clock runs on by its travel along its heading, times {@code rate}.
     */
    public void follow(Vec3 feet, float yaw, double rate, BlockGetter level, double lift) {
        if (head == null || feet.distanceToSqr(head) > TELEPORT * TELEPORT) { lay(feet, yaw, level, lift); return; }
        double angle = yaw * Mth.DEG_TO_RAD;
        clock += ((feet.x - head.x) * -Math.sin(angle) + (feet.z - head.z) * Math.cos(angle)) * rate;
        head = feet;
        // Backing up over its own trail, the head takes back the points it has come over: any it is level with or behind
        // (coming back down a face it went up, aslant, it was level with each in turn, and left a fold on the trail).
        while (points.size() >= 2) {
            Iterator<Vec3> it = points.iterator();
            Vec3 first = it.next(), second = it.next();
            if (feet.subtract(first).dot(first.subtract(second)) > 1.0E-9) break;
            points.pollFirst();
            marks.pollFirst();
        }
        if (points.isEmpty() || feet.distanceToSqr(points.peekFirst()) >= SPACING * SPACING) {
            double mark = points.isEmpty() ? 0 : marks.peekFirst() + Math.hypot(feet.x - points.peekFirst().x, feet.z - points.peekFirst().z);
            points.addFirst(feet);
            marks.addFirst(mark);
        }
        // Keep what the body lies on: its length of the way the head went over the ground (up or down a face counts
        // nothing, as the level line reads it; counted whole, a climb used it up and the body's end ran on off the trail,
        // swinging as the trail's end changed), within a bound on the whole.
        double flat = 0, whole = 0;
        Vec3 previous = feet;
        int kept = 0;
        for (Vec3 p : points) {
            flat += Math.hypot(p.x - previous.x, p.z - previous.z);
            whole += p.distanceTo(previous);
            previous = p;
            kept++;
            if (flat > length + 2 || whole > KEEP_WHOLE * (length + 2)) break;
        }
        while (points.size() > kept) {
            points.pollLast();
            marks.pollLast();
        }
    }

    /**
     * The trail's points and unit tangents (toward the head) at each of {@code distances} behind the head (ascending or
     * not), into {@code at} and {@code tangent} as x, y, z triples. Past the end it runs on straight.
     */
    public void sample(double[] distances, double[] at, double[] tangent) {
        int n = points.size() + 1;
        double[] px = new double[n], py = new double[n], pz = new double[n], cumulative = new double[n];
        px[0] = head.x; py[0] = head.y; pz[0] = head.z;
        int m = 1;
        for (Vec3 p : points) {
            double step = Math.sqrt(Mth.square(p.x - px[m - 1]) + Mth.square(p.y - py[m - 1]) + Mth.square(p.z - pz[m - 1]));
            if (step < 1.0E-6) continue;
            px[m] = p.x; py[m] = p.y; pz[m] = p.z;
            cumulative[m] = cumulative[m - 1] + step;
            m++;
        }
        double[] a = new double[3], b = new double[3];
        for (int i = 0; i < distances.length; i++) {
            double d = distances[i];
            point(px, py, pz, cumulative, m, d, a);
            at[3 * i] = a[0]; at[3 * i + 1] = a[1]; at[3 * i + 2] = a[2];
            point(px, py, pz, cumulative, m, Math.max(0, d - TANGENT_SPAN), a);
            point(px, py, pz, cumulative, m, d + TANGENT_SPAN, b);
            double tx = a[0] - b[0], ty = a[1] - b[1], tz = a[2] - b[2], norm = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (norm < 1.0E-6) { tx = 0; ty = 0; tz = 1; norm = 1; }
            tangent[3 * i] = tx / norm; tangent[3 * i + 1] = ty / norm; tangent[3 * i + 2] = tz / norm;
        }
    }

    /** The point {@code d} blocks along the polyline, running on straight past its end. */
    private static void point(double[] x, double[] y, double[] z, double[] cumulative, int n, double d, double[] out) {
        if (n == 1 || d <= 0) { out[0] = x[0]; out[1] = y[0]; out[2] = z[0] - (n == 1 ? d : 0); return; }
        if (d >= cumulative[n - 1]) {
            double dx = x[n - 1] - x[n - 2], dy = y[n - 1] - y[n - 2], dz = z[n - 1] - z[n - 2];
            double norm = Math.max(1.0E-6, Math.sqrt(dx * dx + dy * dy + dz * dz)), over = d - cumulative[n - 1];
            out[0] = x[n - 1] + dx / norm * over; out[1] = y[n - 1] + dy / norm * over; out[2] = z[n - 1] + dz / norm * over;
            return;
        }
        int low = 0, high = n - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (cumulative[middle] <= d) low = middle; else high = middle;
        }
        double f = (d - cumulative[low]) / Math.max(1.0E-9, cumulative[high] - cumulative[low]);
        out[0] = x[low] + (x[high] - x[low]) * f; out[1] = y[low] + (y[high] - y[low]) * f; out[2] = z[low] + (z[high] - z[low]) * f;
    }

    /**
     * The trail behind the head as a fine level polyline ({@link #line}): each point's place, the trail's own height there
     * (the feet's), the level way toward the head, its level distance behind the head and which step of the trail's way it
     * is (the head's own none): a step keeps its place on the ground as the head goes on.
     */
    public static final class Line {
        public final double[] x, y, z, tx, tz, t;
        public final long[] k;

        Line(int count) {
            x = new double[count]; y = new double[count]; z = new double[count];
            tx = new double[count]; tz = new double[count]; t = new double[count]; k = new long[count];
        }

        public int count() { return x.length; }
    }

    /**
     * The trail behind the head as a polyline of {@code count} points, measured level: only the way the head went over the
     * ground counts (wherever it went straight up or down, a step, a face it climbed or lowered itself down, a fall, a
     * dive, is no distance at all). The first point is the head; the others lie at every {@code step} of the way the head
     * went counted from where the trail was laid ({@link #mark}), the nearest at least half a step behind the head: they
     * keep their places on the ground as the head goes on (laid a step from the head, they slid over every edge and the
     * body shivered). A body laid along the line takes its heights from the ground under it there, so a climb the head
     * gave up, a fall it came back from, or a jump, stands nothing up off the ground. Each point keeps the trail's height
     * there (the feet's; where the head went straight up or down, the height it went on from, away from the head). The
     * line's plan is smoothed over SMOOTH, less near the head. Past the trail's end the line runs on straight.
     */
    public Line line(double step, int count) {
        int n = points.size() + 1;
        double[] px = new double[n], py = new double[n], pz = new double[n];
        px[0] = head.x; py[0] = head.y; pz[0] = head.z;
        int m = 1;
        for (Vec3 p : points) {
            if (Mth.square(p.x - px[m - 1]) + Mth.square(p.y - py[m - 1]) + Mth.square(p.z - pz[m - 1]) < 1.0E-12) continue;
            px[m] = p.x; py[m] = p.y; pz[m] = p.z;
            m++;
        }
        int segments = m - 1;
        double[] along = new double[segments + 1];
        for (int k = 0; k < segments; k++) along[k + 1] = along[k] + Math.hypot(px[k + 1] - px[k], pz[k + 1] - pz[k]);
        // the way the trail runs on past its end: its last level stretch
        double ex = 0, ez = -1;
        for (int k = segments - 1; k >= 0; k--) {
            double dx = px[k + 1] - px[k], dz = pz[k + 1] - pz[k], d = Math.hypot(dx, dz);
            if (d > 1.0E-6) { ex = dx / d; ez = dz / d; break; }
        }
        var line = new Line(count);
        int seg = 0;
        double mark = mark(), phase = mark - step * Math.floor(mark / step), first = phase < step / 2 ? phase + step : phase;
        long firstStep = Math.round((mark - first) / step);
        for (int j = 0; j < count; j++) {
            double t = j == 0 ? 0 : first + (j - 1) * step;
            line.t[j] = t;
            line.k[j] = j == 0 ? Long.MIN_VALUE : firstStep - (j - 1);
            if (segments == 0 || t >= along[segments]) {
                double over = segments == 0 ? t : t - along[segments];
                line.x[j] = px[m - 1] + ex * over; line.y[j] = py[m - 1]; line.z[j] = pz[m - 1] + ez * over;
                continue;
            }
            while (seg + 1 < segments && along[seg + 1] <= t) seg++;
            double span = along[seg + 1] - along[seg], f = span > 1.0E-9 ? (t - along[seg]) / span : 0;
            line.x[j] = px[seg] + (px[seg + 1] - px[seg]) * f;
            line.y[j] = py[seg] + (py[seg + 1] - py[seg]) * f;
            line.z[j] = pz[seg] + (pz[seg + 1] - pz[seg]) * f;
        }
        smooth(line, step);
        // the level way toward the head at each point, read over a span round it (wider where the head stood still)
        int reach = Math.max(1, (int) Math.round(TANGENT_SPAN / step));
        int lastKnown = -1;
        for (int j = 0; j < count; j++) {
            double dx = 0, dz = 0, d = 0;
            for (int r = reach; r <= 4 * reach && d < 1.0E-4; r += reach) {
                int a = Math.max(0, j - r), b = Math.min(count - 1, j + r);
                dx = line.x[a] - line.x[b]; dz = line.z[a] - line.z[b];
                d = Math.hypot(dx, dz);
            }
            if (d < 1.0E-4) { line.tx[j] = Double.NaN; continue; }
            line.tx[j] = dx / d; line.tz[j] = dz / d;
            if (lastKnown < j - 1) for (int i = Math.max(0, lastKnown + 1); i < j; i++) { line.tx[i] = line.tx[j]; line.tz[i] = line.tz[j]; }
            lastKnown = j;
        }
        for (int j = lastKnown + 1; j < count; j++) {
            if (lastKnown < 0) { line.tx[j] = 0; line.tz[j] = 1; }
            else { line.tx[j] = line.tx[lastKnown]; line.tz[j] = line.tz[lastKnown]; }
        }
        return line;
    }

    /** The line's plan smoothed by a bell SMOOTH blocks wide, narrowing to nothing at the head (which holds). */
    private static void smooth(Line line, double step) {
        int n = line.x.length;
        double[] sx = new double[n], sz = new double[n];
        sx[0] = line.x[0];
        sz[0] = line.z[0];
        for (int j = 1; j < n; j++) {
            double spread = Math.min(SMOOTH, line.t[j] / 3);
            if (spread < step / 4) { sx[j] = line.x[j]; sz[j] = line.z[j]; continue; }
            int reach = (int) Math.ceil(3 * spread / step);
            double sum = 0, ax = 0, az = 0;
            for (int i = Math.max(0, j - reach); i <= Math.min(n - 1, j + reach); i++) {
                double d = line.t[i] - line.t[j], weight = Math.exp(-d * d / (2 * spread * spread));
                sum += weight;
                ax += weight * line.x[i];
                az += weight * line.z[i];
            }
            sx[j] = ax / sum;
            sz[j] = az / sum;
        }
        System.arraycopy(sx, 0, line.x, 0, n);
        System.arraycopy(sz, 0, line.z, 0, n);
    }

    /** Whether a point is inside a block's collision box. */
    public static boolean solid(BlockGetter level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.bounds().move(pos).contains(x, y, z);
    }
}
