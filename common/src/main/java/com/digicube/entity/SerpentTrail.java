package com.digicube.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The path a serpent's head took (a body with {@code body.serpent} on its sheet): the places its feet went through, the
 * newest first, sampled by distance behind the head. Its body lies along it, so it goes where its head went and never
 * through the ground or a wall beside the way: the server lays its hit parts on it, a client its drawn body. Backing up,
 * the head takes back the trail it comes over; a teleport, or a first sight of the body, lays it out afresh behind the
 * head, bending away from blocks.
 */
public final class SerpentTrail {
    /** Blocks between kept points, and the jump in a tick past which the head was moved, not walked. */
    public static final double SPACING = .1, TELEPORT = 4;
    /** Blocks between the points of a fresh body laid out behind its head, and the turns tried to go round a block. */
    private static final double LAY_STEP = .25;
    private static final int[] LAY_TURNS = {0, 15, -15, 30, -30, 45, -45, 60, -60, 90, -90, 120, -120};
    /** Blocks around a sampled point its tangent is read over: steadier than the one segment it lies on. */
    private static final double TANGENT_SPAN = .35;

    private final double length;
    private final ArrayDeque<Vec3> points = new ArrayDeque<>();
    private Vec3 head;
    private double clock;

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
        points.clear();
        double angle = yaw * Mth.DEG_TO_RAD, dx = Math.sin(angle), dz = -Math.cos(angle);
        Vec3 at = feet;
        for (int i = 0; i < (int) Math.ceil((length + 2) / LAY_STEP); i++) {
            Vec3 next = null;
            for (int turn : LAY_TURNS) {
                double c = Math.cos(turn * Mth.DEG_TO_RAD), s = Math.sin(turn * Mth.DEG_TO_RAD);
                double tx = dx * c - dz * s, tz = dx * s + dz * c;
                Vec3 tried = at.add(tx * LAY_STEP, 0, tz * LAY_STEP);
                if (level == null || !solid(level, tried.x, tried.y + lift, tried.z)) { next = tried; dx = tx; dz = tz; break; }
            }
            at = next != null ? next : at.add(dx * LAY_STEP, 0, dz * LAY_STEP);
            points.addLast(at);
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
        // Backing up over its own trail, the head takes back the points it has come over.
        while (points.size() >= 2) {
            Iterator<Vec3> it = points.iterator();
            Vec3 first = it.next(), second = it.next();
            if (feet.subtract(first).dot(first.subtract(second)) >= 0) break;
            points.pollFirst();
        }
        if (points.isEmpty() || feet.distanceToSqr(points.peekFirst()) >= SPACING * SPACING) points.addFirst(feet);
        // Keep what the body lies on.
        double total = 0;
        Vec3 previous = feet;
        int kept = 0;
        for (Vec3 p : points) {
            total += p.distanceTo(previous);
            previous = p;
            kept++;
            if (total > length + 2) break;
        }
        while (points.size() > kept) points.pollLast();
    }

    /**
     * The trail's points and unit tangents (toward the head) at each of {@code distances} behind the head (ascending or
     * not), into {@code at} and {@code tangent} as x, y, z triples. Past the end it runs on straight.
     */
    public void sample(double[] distances, double[] at, double[] tangent) {
        sample(distances, at, tangent, 0);
    }

    /**
     * {@link #sample(double[], double[], double[])}, with the first {@code level} blocks behind the head measured level,
     * leaving out what the trail climbs or drops there (a face the head went up or down is no distance at all), and every
     * distance past them measured along the trail on from where they end. A neck reared over the ground behind its head
     * lies on the ground that far back whatever face the head is on.
     */
    public void sample(double[] distances, double[] at, double[] tangent, double level) {
        int n = points.size() + 1;
        double[] px = new double[n], py = new double[n], pz = new double[n], cumulative = new double[n], flat = new double[n];
        px[0] = head.x; py[0] = head.y; pz[0] = head.z;
        int m = 1;
        for (Vec3 p : points) {
            double across = Mth.square(p.x - px[m - 1]) + Mth.square(p.z - pz[m - 1]);
            double step = Math.sqrt(across + Mth.square(p.y - py[m - 1]));
            if (step < 1.0E-6) continue;
            px[m] = p.x; py[m] = p.y; pz[m] = p.z;
            cumulative[m] = cumulative[m - 1] + step;
            flat[m] = flat[m - 1] + Math.sqrt(across);
            m++;
        }
        // where along the trail the level part ends
        double shift = level <= 0 ? 0 : along(flat, cumulative, m, level) - level;
        double[] a = new double[3], b = new double[3];
        for (int i = 0; i < distances.length; i++) {
            double d = distances[i];
            point(px, py, pz, cumulative, m, along(flat, cumulative, m, d, level, shift), a);
            at[3 * i] = a[0]; at[3 * i + 1] = a[1]; at[3 * i + 2] = a[2];
            point(px, py, pz, cumulative, m, along(flat, cumulative, m, Math.max(0, d - TANGENT_SPAN), level, shift), a);
            point(px, py, pz, cumulative, m, along(flat, cumulative, m, d + TANGENT_SPAN, level, shift), b);
            double tx = a[0] - b[0], ty = a[1] - b[1], tz = a[2] - b[2], norm = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (norm < 1.0E-6) { tx = 0; ty = 0; tz = 1; norm = 1; }
            tangent[3 * i] = tx / norm; tangent[3 * i + 1] = ty / norm; tangent[3 * i + 2] = tz / norm;
        }
    }

    /** Blocks along the trail at {@code d} behind the head: level within {@code level}, along the trail past it. */
    private static double along(double[] flat, double[] cumulative, int n, double d, double level, double shift) {
        return d <= level ? along(flat, cumulative, n, d) : d + shift;
    }

    /** Blocks along the trail where it has gone {@code d} level (the first place it has; on straight past its end). */
    private static double along(double[] flat, double[] cumulative, int n, double d) {
        if (n == 1 || d <= 0) return Math.max(0, d);
        if (d >= flat[n - 1]) return cumulative[n - 1] + (d - flat[n - 1]);
        int low = 0, high = n - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (flat[middle] < d) low = middle; else high = middle;
        }
        double f = (d - flat[low]) / Math.max(1.0E-9, flat[high] - flat[low]);
        return cumulative[low] + (cumulative[high] - cumulative[low]) * f;
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

    /** Whether a point is inside a block's collision box. */
    public static boolean solid(BlockGetter level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.bounds().move(pos).contains(x, y, z);
    }

    /**
     * How far up a point must go for a ball of {@code radius} round it to rest on the ground under it, at most
     * {@code reach}: 0 when it is clear of the ground, or the ground there is higher than it could climb.
     */
    public static double lift(BlockGetter level, double x, double y, double z, double radius, double reach) {
        double bottom = y - radius, top = bottom;
        BlockPos pos = BlockPos.containing(x, bottom, z);
        // the top of the solid column the ball's bottom is sunk in
        for (int up = 0; up <= Math.ceil(reach); up++) {
            BlockPos at = pos.above(up);
            var shape = level.getBlockState(at).getCollisionShape(level, at);
            if (shape.isEmpty()) break;
            double t = at.getY() + shape.max(Direction.Axis.Y);
            if (t <= top) break;
            top = t;
        }
        double need = top - bottom;
        return need > reach ? 0 : need;
    }
}
