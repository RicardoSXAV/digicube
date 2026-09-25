package com.digicube.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a moving body will be, for a shot that flies straight. Server side, one per shooter.
 *
 * <p>The last second of the target's positions, sampled once a tick, is read as a body that keeps its pace and its
 * rate of turn: a straight run, a strafe along a wall and a circle around the shooter all continue as they were going.
 * A reversal (a juke) is not a turn and restarts the reading from the new heading. In the air the body falls with
 * vanilla's gravity and drag to the floor it left; a wall in the way stops the prediction where the body would stop.
 *
 * <p>How far ahead the reading can be trusted is measured, not assumed: the same model, run from where the target
 * was a few ticks ago, is checked against where it actually went ({@link #miss}). A steady mover scores near zero;
 * a body that keeps changing its mind scores its own pace.
 */
public final class TargetMotion {
    /** Ticks of history kept, and the stride of the pace and the heading readings. */
    private static final int HISTORY = 24, PACE_TICKS = 3, TURN_TICKS = 4;
    /** Below this pace (blocks a tick) a body stands; its heading means nothing. */
    private static final double STILL = .02;
    /** A heading change past this, between two readings, is a reversal rather than a curve. */
    private static final double MAX_TURN = Math.toRadians(20);
    /** The two backtests: a prediction this many ticks long, made that many ticks ago. */
    private static final int[] BACKTESTS = {5, 9};
    /** Vanilla's air drag on vertical speed. */
    private static final double DRAG = .98;

    private final double[] x = new double[HISTORY], y = new double[HISTORY], z = new double[HISTORY];
    private final boolean[] grounded = new boolean[HISTORY];
    private int count, head = -1, targetId = Integer.MIN_VALUE, lastTick;
    private double floorY, gravity = .08;

    /** Records the target's hitbox centre for this tick; a new target, or a gap, starts the history over. */
    public void sample(LivingEntity target, int tick) {
        if (target.getId() != targetId || tick - lastTick > 2 || tick < lastTick) { count = 0; head = -1; targetId = target.getId(); }
        if (count > 0 && tick == lastTick) return;
        lastTick = tick;
        Vec3 centre = target.getBoundingBox().getCenter();
        head = (head + 1) % HISTORY;
        x[head] = centre.x; y[head] = centre.y; z[head] = centre.z;
        grounded[head] = target.onGround() || target.isInWater();
        if (grounded[head]) floorY = centre.y;
        gravity = target.getGravity();
        count = Math.min(HISTORY, count + 1);
    }

    /** Whether this track follows the given target and has enough of it to read a pace. */
    public boolean follows(LivingEntity target) { return target.getId() == targetId && count > PACE_TICKS + TURN_TICKS; }

    private int at(int ago) { return Math.floorMod(head - ago, HISTORY); }
    private Vec3 position(int ago) { int i = at(ago); return new Vec3(x[i], y[i], z[i]); }

    /** The motion as read {@code ago} ticks back: position, horizontal pace, rate of turn, vertical speed and support. */
    private record State(Vec3 position, double vx, double vz, double turn, double vy, boolean grounded) {}

    private State state(int ago) {
        Vec3 now = position(ago), before = position(ago + PACE_TICKS);
        double vx = (now.x - before.x) / PACE_TICKS, vz = (now.z - before.z) / PACE_TICKS;
        double turn = 0;
        if (ago + PACE_TICKS + TURN_TICKS < count) {
            Vec3 earlier = position(ago + TURN_TICKS), earliest = position(ago + TURN_TICKS + PACE_TICKS);
            double px = (earlier.x - earliest.x) / PACE_TICKS, pz = (earlier.z - earliest.z) / PACE_TICKS;
            if (Math.hypot(vx, vz) > STILL && Math.hypot(px, pz) > STILL) {
                double change = Mth.wrapDegrees(Math.toDegrees(Math.atan2(vz, vx) - Math.atan2(pz, px)));
                // A body that swung its heading this far did not curve: it turned back, and its new heading is all there is.
                if (Math.abs(Math.toRadians(change)) <= MAX_TURN * TURN_TICKS) turn = Math.toRadians(change) / TURN_TICKS;
            }
        }
        boolean ground = grounded[at(ago)];
        double vy = ground ? 0 : now.y - position(ago + 1).y;
        return new State(now, vx, vz, turn, vy, ground);
    }

    /** The reading carried {@code ticks} ahead from a state; the floor stops a fall. */
    private Vec3 carry(State s, double ticks) {
        double px = s.position.x, py = s.position.y, pz = s.position.z, vx = s.vx, vz = s.vz, vy = s.vy;
        double cos = Math.cos(s.turn), sin = Math.sin(s.turn);
        int whole = (int) ticks;
        for (int i = 0; i <= whole; i++) {
            double share = i < whole ? 1 : ticks - whole;
            if (share <= 0) break;
            if (s.turn != 0) { double rx = vx * cos - vz * sin; vz = vx * sin + vz * cos; vx = rx; }
            px += vx * share; pz += vz * share;
            if (!s.grounded) {
                vy = (vy - gravity) * DRAG;
                py = Math.max(floorY, py + vy * share);
            }
        }
        return new Vec3(px, py, pz);
    }

    /** Where the target's hitbox centre will be {@code ticks} from now, stopped where a wall would stop it. */
    public Vec3 predict(LivingEntity target, double ticks) {
        Vec3 now = target.getBoundingBox().getCenter();
        if (!follows(target)) return now;
        Vec3 ahead = carry(state(0), ticks);
        var hit = target.level().clip(new ClipContext(now, ahead, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        if (hit.getType() == HitResult.Type.MISS) return ahead;
        // Short of the wall by half the body, where the body itself would come to rest.
        Vec3 path = ahead.subtract(now);
        double stop = Math.max(0, hit.getLocation().distanceTo(now) - target.getBbWidth() / 2);
        return path.lengthSqr() < 1e-8 ? now : now.add(path.normalize().scale(stop));
    }

    /** How far a prediction may be off, across the ground and in height, in blocks. */
    public record Miss(double across, double height) {}

    /**
     * How far off a prediction {@code ticks} ahead is likely to be: the model's worst recent error per tick of lead
     * (its predictions from where the target was a few ticks back, checked against where it is now), times the lead.
     * Zero for a body that has done what it was doing. In height it is never more than the body has actually moved up
     * and down lately: a hopper is always somewhere between its floor and the top of its hop.
     */
    public Miss miss(double ticks) {
        double across = 0, height = 0;
        for (int lead : BACKTESTS) {
            if (lead + PACE_TICKS + TURN_TICKS >= count) continue;
            Vec3 error = carry(state(lead), lead).subtract(position(0));
            across = Math.max(across, error.horizontalDistance() / lead);
            height = Math.max(height, Math.abs(error.y) / lead);
        }
        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (int ago = 0; ago < count; ago++) { low = Math.min(low, y[at(ago)]); high = Math.max(high, y[at(ago)]); }
        return new Miss(across * ticks, Math.min(height * ticks, Math.max(0, high - low)));
    }

    /** The target's current horizontal pace, blocks a tick. */
    public double pace() {
        if (count <= PACE_TICKS) return 0;
        State s = state(0);
        return Math.hypot(s.vx, s.vz);
    }

    /**
     * Where to send a straight shot of the given speed from {@code from} so it meets the target: the predicted
     * centre at the moment the shot arrives there, found by iterating on the flight time.
     * @param delay ticks before the shot leaves
     */
    public Vec3 intercept(LivingEntity target, Vec3 from, double speed, double delay) {
        Vec3 point = predict(target, delay);
        for (int i = 0; i < 6; i++) point = predict(target, delay + point.distanceTo(from) / speed);
        return point;
    }
}
