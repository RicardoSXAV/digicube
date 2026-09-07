package com.digicube.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Geometry shared by the wave's authoritative collision and its headless regression checks. */
public final class MarchingFishesFlight {
    private static final double TURN_RADIANS = Math.toRadians(5.0);
    private static final double CONE_COS = Math.cos(Math.toRadians(110.0));

    private MarchingFishesFlight() {}

    /** Bend at most five degrees per tick; a passed target cannot pull the wave into an orbit. */
    public static Vec3 steer(Vec3 velocity, Vec3 displacement) {
        double speed = velocity.length();
        if (speed < 1.0E-6 || displacement.lengthSqr() < 1.0E-8) return velocity;
        Vec3 heading = velocity.scale(1.0 / speed), desired = displacement.normalize();
        double cosine = Mth.clamp(heading.dot(desired), -1.0, 1.0);
        if (cosine < CONE_COS) return velocity;
        double angle = Math.acos(cosine);
        if (angle < 1.0E-5) return velocity;
        double turn = Math.min(angle, TURN_RADIANS);
        Vec3 tangent = desired.subtract(heading.scale(cosine)).normalize();
        return heading.scale(Math.cos(turn)).add(tangent.scale(Math.sin(turn))).scale(speed);
    }

    /** Earliest contact of the moving wave centre against a box expanded by its half extents. */
    public static Vec3 contact(Vec3 from, Vec3 to, AABB obstacle, double halfWidth, double halfHeight) {
        AABB expanded = obstacle.inflate(halfWidth, halfHeight, halfWidth);
        return expanded.contains(from) ? from : expanded.clip(from, to).orElse(null);
    }
}
