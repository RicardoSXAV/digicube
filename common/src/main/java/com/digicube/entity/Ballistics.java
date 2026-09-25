package com.digicube.entity;

import net.minecraft.world.phys.Vec3;

/**
 * Aiming a thrown body that falls: the flat solution of a projectile launched at {@code speed} blocks a tick under
 * {@code gravity} blocks a tick squared, corrected against the game's own per-tick flight (move, then fall, then a
 * little drag) so a heavy lob lands where it was meant to.
 */
public final class Ballistics {
    public static final double DRAG = .995;
    private Ballistics() {}

    /** The flat-arc velocity from {@code from} that passes through {@code to}, or null when it is out of reach. */
    public static Vec3 launch(Vec3 from, Vec3 to, double speed, double gravity) {
        Vec3 aim = to;
        Vec3 velocity = null;
        for (int pass = 0; pass < 4; pass++) {
            velocity = continuous(from, aim, speed, gravity);
            if (velocity == null) return null;
            // Fly it tick by tick to the target's distance and lift the aim by the miss.
            Vec3 flat = to.subtract(from).multiply(1, 0, 1);
            double distance = flat.length();
            Vec3 p = from, v = velocity;
            double travelled = 0, lastY = p.y;
            for (int tick = 0; tick < 200 && travelled < distance; tick++) {
                lastY = p.y;
                Vec3 next = p.add(v);
                double step = next.subtract(p).horizontalDistance();
                if (travelled + step >= distance) {
                    double f = step < 1.0E-9 ? 1 : (distance - travelled) / step;
                    lastY = p.y + (next.y - p.y) * f;
                    travelled = distance;
                    break;
                }
                travelled += step; p = next; v = v.add(0, -gravity, 0).scale(DRAG);
            }
            double miss = to.y - lastY;
            if (Math.abs(miss) < .03) break;
            aim = aim.add(0, miss, 0);
        }
        return velocity;
    }

    /** Ticks a launch at {@code velocity} takes to cover the horizontal distance to {@code to}. */
    public static double flightTicks(Vec3 from, Vec3 to, Vec3 velocity) {
        double distance = to.subtract(from).horizontalDistance(), h = velocity.horizontalDistance();
        if (h < 1.0E-6) return Double.POSITIVE_INFINITY;
        double travelled = 0; int tick = 0; double speed = h;
        while (travelled < distance && tick < 400) { travelled += speed; speed *= DRAG; tick++; }
        return tick - (travelled - distance) / Math.max(1.0E-6, speed / DRAG);
    }

    /** The flatter of the two classic solutions (no drag). */
    private static Vec3 continuous(Vec3 from, Vec3 to, double speed, double gravity) {
        Vec3 d = to.subtract(from);
        double x = Math.sqrt(d.x * d.x + d.z * d.z), y = d.y, v2 = speed * speed;
        if (x < 1.0E-6) return new Vec3(0, Math.signum(y) * speed, 0);
        double disc = v2 * v2 - gravity * (gravity * x * x + 2 * y * v2);
        if (disc < 0) return null;
        double angle = Math.atan2(v2 - Math.sqrt(disc), gravity * x);
        double h = Math.cos(angle) * speed;
        return new Vec3(d.x / x * h, Math.sin(angle) * speed, d.z / x * h);
    }

    /** Out of reach: as far as it goes, 40 degrees up toward the point. */
    public static Vec3 farthest(Vec3 from, Vec3 to, double speed) {
        Vec3 flat = to.subtract(from).multiply(1, 0, 1);
        if (flat.lengthSqr() < 1.0E-8) flat = new Vec3(0, 0, 1);
        flat = flat.normalize();
        double a = Math.toRadians(40);
        return new Vec3(flat.x * Math.cos(a) * speed, Math.sin(a) * speed, flat.z * Math.cos(a) * speed);
    }
}
