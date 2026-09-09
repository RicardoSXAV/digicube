package com.digicube.entity.ai;

import com.digicube.digimon.AerialMount;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Deterministic, tick-based velocity response shared by local riding and server checks. */
public final class AerialHandling {
    private AerialHandling() {}
    /**
     * Convert normal movement and look direction into desired aerial motion.
     * @param p species handling
     * @param forward vanilla forward axis
     * @param strafe vanilla strafe axis
     * @param yaw smoothed mount yaw in degrees
     * @param pitch rider look pitch in degrees
     * @param input jump/menu state
     * @return bounded desired velocity; looking without moving preserves hover
     */
    public static Vec3 target(AerialMount p, float forward, float strafe, float yaw, float pitch, AerialInput input) {
        if (input.brake()) return Vec3.ZERO;
        double f = Mth.clamp(forward, -1, 1);
        double s = Mth.clamp(strafe, -1, 1);
        double angle = Mth.clamp(pitch, -80, 80) * Mth.DEG_TO_RAD;
        Vec3 direction = new Vec3(s*.7, f>0 ? -Math.sin(angle)*f : 0, Math.cos(angle)*f);
        if (direction.lengthSqr()>1) direction=direction.normalize();
        double speed = p.cruiseSpeed();
        if (f<0) direction=direction.multiply(1,1,.45);
        Vec3 target=direction.scale(speed).yRot(-yaw*Mth.DEG_TO_RAD);
        double y = input.ascend() ? p.climbSpeed() : target.y;
        double descent = p.dive() != null && f > 0 && !input.ascend() ? speed : p.descendSpeed();
        Vec3 result = new Vec3(target.x, Math.clamp(y, -descent, p.climbSpeed()), target.z);
        return result.lengthSqr() > speed * speed ? result.normalize().scale(speed) : result;
    }

    /** Steer earned dive momentum without resetting it to cruise on a pull-up.
     * @param p species handling
     * @param velocity current physical motion, after the previous collision
     * @param target powered motion from normal controls
     * @param diving forward descent requested without the climb key
     * @return bounded next motion; neutral controls still brake into hover */
    public static Vec3 flightStep(AerialMount p, Vec3 velocity, Vec3 target, boolean diving) {
        var dive = p.dive();
        if (dive == null || target.lengthSqr() < .0001) return step(p, velocity, target, false);
        double speed = velocity.length();
        double poweredSpeed = target.length();
        Vec3 desired = target.normalize();
        Vec3 heading = speed < .0001 ? desired : velocity.scale(1 / speed);
        double angle = Math.acos(Mth.clamp(heading.dot(desired), -1, 1));
        // Momentum widens the turning arc instead of snapping a fast dive into a new direction.
        double turn = p.turnDegrees() * Mth.DEG_TO_RAD * p.cruiseSpeed() / Math.max(p.cruiseSpeed(), speed);
        Vec3 direction = turnTowards(heading, desired, angle, turn);
        double loss = dive.drag() + dive.climbDrag() * Math.max(0, direction.y)
                + dive.turnDrag() * angle / Math.PI;
        double nextSpeed = speed <= poweredSpeed
                ? Math.min(poweredSpeed, speed + p.acceleration()) : Math.max(poweredSpeed, speed - loss);
        // Camera pitch alone earns nothing: the creature must actually be descending.
        double intent = diving ? Mth.clamp((-desired.y - .17) / .65, 0, 1) : 0;
        nextSpeed += dive.acceleration() * intent * Math.max(0, -direction.y);
        return direction.scale(Math.min(dive.maxSpeed(), nextSpeed));
    }

    private static Vec3 turnTowards(Vec3 from, Vec3 to, double angle, double limit) {
        if (angle <= limit) return to;
        Vec3 tangent = to.subtract(from.scale(from.dot(to)));
        if (tangent.lengthSqr() < .000001) {
            Vec3 axis = Math.abs(from.y) < .9 ? Vec3.Y_AXIS : Vec3.X_AXIS;
            tangent = axis.subtract(from.scale(from.dot(axis)));
        }
        return from.scale(Math.cos(limit)).add(tangent.normalize().scale(Math.sin(limit))).normalize();
    }

    /** Allow three stable approach ticks plus enough distance to brake a fast descent.
     * @param p species handling
     * @param velocity current physical motion
     * @return automatic landing probe distance in blocks */
    public static double landingDistance(AerialMount p, Vec3 velocity) {
        double down = Math.max(0, -velocity.y);
        return 1.25 + 3 * down + down * down / (2 * p.braking());
    }

    /** Orient the complete flying silhouette along its trajectory, keeping hover steady.
     * @param velocity actual movement, not camera look
     * @return additional model pitch in degrees; positive angles point the nose down */
    public static float flightPitch(Vec3 velocity) {
        double weight = Math.min(1, velocity.length() / .2);
        double pathPitch = Math.toDegrees(Math.atan2(-velocity.y, velocity.horizontalDistance()));
        return (float) (Mth.clamp(pathPitch * .75 + Math.min(8, velocity.horizontalDistance() * 12), -25, 60) * weight);
    }
    /** Approach a requested velocity without abrupt starts or stops.
     * @param p species handling
     * @param velocity current motion
     * @param target desired motion
     * @param brake menu input explicitly requests a stop
     * @return acceleration-limited motion for the next tick */
    public static Vec3 step(AerialMount p, Vec3 velocity, Vec3 target, boolean brake) {
        double rate=brake || target.lengthSqr()<.0001 ? p.braking() : p.acceleration();
        Vec3 delta=target.subtract(velocity);
        Vec3 result=delta.length()<=rate ? target : velocity.add(delta.normalize().scale(rate));
        return result.lengthSqr()<.000001 ? Vec3.ZERO : result;
    }
}
