package com.digicube.entity;

import com.digicube.digimon.ThrownAttacks;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The flight of a returning throw, the same on server and client: out from the hand along a curve that turns at the
 * range, then back along a second curve that swings wider and ends at a fixed catch point beside the thrower's start.
 * Two cubic Bezier legs, joined with a common tangent at the far turn, walked by arc length at a pace that slows into
 * the turn and picks up on the way back. In height it dips from the hand to the spec's cruise height over the floor the
 * thrower stood on (a throw from a leap comes down to it), climbs or sinks toward the aim by its far turn, and comes
 * home at the catching fist's height. Everything is fixed at the release: moving afterwards changes where the
 * thrower is, never where the weapon goes.
 *
 * @param start where it leaves the hand (world)
 * @param yaw the throw's heading (Minecraft yaw, degrees)
 * @param range how far out the turn is, in blocks
 * @param side +1 curves to the thrower's left, -1 to its right; the catch point lies on that side
 * @param floor the height of the floor under the thrower when it let go
 * @param pace how much faster than the spec's own speeds it flies (a charged throw, a leap, a run)
 * @param lift blocks the far turn sits above the cruise height
 */
public record BoomerangPath(Vec3 start, float yaw, double range, int side, ThrownAttacks.Returning spec, double floor, double pace,
                            double lift, Table table) {
    private static final int SAMPLES = 64;

    /** Arc-length tables of the two legs and the tick clock of the whole flight. */
    public record Table(Vec3[] points, double[] length, double outbound, double total, double[] clock) {}

    /** A tap thrown standing, straight out at the cruise height. */
    public static BoomerangPath of(Vec3 start, float yaw, double range, int side, ThrownAttacks.Returning spec) {
        return of(start, yaw, range, side, spec, start.y - spec.releasePoint().y, 1, 0);
    }

    public static BoomerangPath of(Vec3 start, float yaw, double range, int side, ThrownAttacks.Returning spec, double floor, double pace, double lift) {
        range = Math.clamp(range, spec.minRange(), spec.farRange() * MAX_IMPULSE);
        pace = Math.clamp(pace, .5, MAX_IMPULSE * 2);
        lift = Math.clamp(lift, spec.lift()[0], spec.lift()[1]);
        return new BoomerangPath(start, yaw, range, side, spec, floor, pace, lift, table(start, yaw, range, side, spec, floor, pace, lift));
    }
    /** Most a throw's reach can be stretched by the body's impulse (a leap at a run). */
    private static final double MAX_IMPULSE = 1.8;

    public Vec3 forward() { return forward(yaw); }
    public Vec3 lateral() { return lateral(yaw, side); }
    private static Vec3 forward(float yaw) { return Vec3.directionFromRotation(0, yaw); }
    /** The throw's side: the thrower's left for +1. */
    private static Vec3 lateral(float yaw, int side) {
        double r = Math.toRadians(yaw); return new Vec3(Math.cos(r), 0, Math.sin(r)).scale(side);
    }

    private static double smooth(double u) { u = Math.clamp(u, 0, 1); return u * u * (3 - 2 * u); }

    private static Vec3 cubic(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double u) {
        double v = 1 - u;
        return a.scale(v * v * v).add(b.scale(3 * v * v * u)).add(c.scale(3 * v * u * u)).add(d.scale(u * u * u));
    }

    private static Table table(Vec3 s, float yaw, double range, int side, ThrownAttacks.Returning spec, double floor, double speed, double lift) {
        Vec3 f = forward(yaw), l = lateral(yaw, side);
        double c = spec.catchOffset(range), w = spec.bulge();
        Vec3 apex = s.add(f.scale(range + .25)).add(l.scale(.45 * c));
        Vec3 catchPoint = s.add(l.scale(c));
        Vec3[] out = {s, s.add(f.scale(.62 * range)).add(l.scale(-.12 * c)), apex.add(l.scale(-.42 * c)), apex};
        Vec3[] back = {apex, apex.add(l.scale(.55 * c + w)), catchPoint.add(f.scale(.66 * range)).add(l.scale(w)), catchPoint};
        var points = new Vec3[2 * SAMPLES + 1];
        var length = new double[points.length];
        // Heights: thrown overhand, it leaves the fist high and dips to its cruise height over the first stretch out,
        // rises (or sinks) toward the aim by the turn and flies the turn there, and in the second half home settles to
        // where the catching fist will be.
        double cruise = floor + spec.cruiseHeight(), home = floor + spec.catchPoint().y;
        for (int i = 0; i <= SAMPLES; i++) {
            Vec3 p = cubic(out[0], out[1], out[2], out[3], (double) i / SAMPLES);
            double u = (double) i / SAMPLES;
            points[i] = new Vec3(p.x, Mth.lerp(smooth(i / (.45 * SAMPLES)), s.y, cruise) + lift * smooth(u / .85), p.z);
        }
        for (int i = 1; i <= SAMPLES; i++) {
            Vec3 p = cubic(back[0], back[1], back[2], back[3], (double) i / SAMPLES);
            double v = i / (double) SAMPLES;
            points[SAMPLES + i] = new Vec3(p.x, Mth.lerp(smooth((v - .4) / .6), cruise, home) + lift * (1 - smooth(v / .75)), p.z);
        }
        for (int i = 1; i < points.length; i++) length[i] = length[i - 1] + points[i].distanceTo(points[i - 1]);
        double outbound = length[SAMPLES], total = length[points.length - 1];
        // The tick clock: arc length reached at each whole tick.
        var clock = new java.util.ArrayList<Double>();
        double at = 0;
        clock.add(0.0);
        while (at < total && clock.size() < 400) { at = Math.min(total, at + speed * pace(at, outbound, total, spec)); clock.add(at); }
        double[] ticks = new double[clock.size()];
        for (int i = 0; i < ticks.length; i++) ticks[i] = clock.get(i);
        return new Table(points, length, outbound, total, ticks);
    }

    /** Blocks a tick at arc length {@code s}: quick from the hand, slowing into the far turn, quick again coming home. */
    public static double pace(double s, double outbound, double total, ThrownAttacks.Returning spec) {
        double[] v = spec.speed();
        if (s < outbound) {
            double u = s / outbound;
            return Mth.lerp(u * u, v[0], v[1]);
        }
        double u = Math.clamp((s - outbound) / Math.max(.01, .35 * (total - outbound)), 0, 1);
        return Mth.lerp(u * (2 - u), v[1], v[2]);
    }

    public double total() { return table.total(); }
    public double outbound() { return table.outbound(); }
    /** Whole ticks the flight lasts, release to the catch point. */
    public int ticks() { return table.clock().length - 1; }
    public Vec3 catchPoint() { return table.points()[table.points().length - 1]; }
    public Vec3 apex() { return table.points()[SAMPLES]; }

    /** Arc length reached {@code tick} ticks after the release (fractional ticks interpolate). */
    public double lengthAt(double tick) {
        double[] clock = table.clock();
        if (tick <= 0) return 0;
        int i = (int) tick;
        if (i >= clock.length - 1) return clock[clock.length - 1];
        return Mth.lerp(tick - i, clock[i], clock[i + 1]);
    }

    /** The point at arc length {@code s}. */
    public Vec3 at(double s) {
        double[] length = table.length();
        if (s <= 0) return start;
        if (s >= length[length.length - 1]) return catchPoint();
        int lo = 0, hi = length.length - 1;
        while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (length[mid] <= s) lo = mid; else hi = mid; }
        double t = (s - length[lo]) / Math.max(1.0E-9, length[hi] - length[lo]);
        return table.points()[lo].lerp(table.points()[hi], t);
    }

    /** Direction of travel at arc length {@code s}. */
    public Vec3 tangent(double s) {
        Vec3 a = at(Math.max(0, s - .15)), b = at(Math.min(total(), s + .15));
        Vec3 d = b.subtract(a);
        return d.lengthSqr() < 1.0E-9 ? forward() : d.normalize();
    }

    /** Where it is {@code tick} ticks after the release. */
    public Vec3 atTick(double tick) { return at(lengthAt(tick)); }

    /** On the way back at arc length {@code s}. */
    public boolean returning(double s) { return s > outbound(); }

    /** Within the stretch of the return where the thrower may take it out of the air. */
    public boolean catchable(double s) { return s >= total() - spec.catchWindow() * (total() - outbound()); }

    /** The first tick at which {@link #catchable} holds. */
    public int catchableFrom() {
        double[] clock = table.clock();
        for (int i = 0; i < clock.length; i++) if (catchable(clock[i])) return i;
        return clock.length - 1;
    }
}
