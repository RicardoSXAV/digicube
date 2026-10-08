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

    /** Share of cruise the back key flies backward at, and of the turn rate the path turns at against the sheet's yaw rate. */
    private static final double BACK = .3, TURN_SCALE = 1.6;
    /** Speed a hard turn costs: a share of the speed per radian turned. */
    private static final double TURN_LOSS = .06;
    /** Blocks over the ground a dive levels out into a skim, and the share of the height left a tick it may still drop. */
    public static final double SKIM = 1.1, SKIM_DROP = .3;
    /** Blocks a tick over the ground above which a skim holds its height instead of settling to land. */
    public static final double SKIM_PACE = .3;

    /**
     * Agile flight ({@link AerialMount.Agility}), one tick: the wings push where the rider looks while forward is held
     * (back a little on the back key, aside on the strafe keys, up and down on the jump and dive keys); the sprint key
     * beats them on full ({@code boost}). Speed is energy: going down a dive gathers it ({@code gravity} a tick straight
     * down), going up spends it, and whatever is over the wings' own speed bleeds off at {@code drag} a tick, more through
     * hard turns. The path turns toward the push no faster than the sheet's turn rate allows at that speed, so a fast
     * body carves a wide arc. With nothing held the body brakes to a hover.
     * @param velocity current motion
     * @param forward vanilla forward axis, strafe its strafe axis (+ left)
     * @param yaw the rider's view yaw, pitch the view pitch (+ down), degrees
     * @return the next motion, before the ground is taken into account ({@link #skim})
     */
    public static Vec3 agile(AerialMount p, Vec3 velocity, float forward, float strafe, float yaw, float pitch,
                             boolean ascend, boolean descend, boolean boost) {
        var a = p.agility();
        double cruise = p.cruiseSpeed();
        double r = Math.toRadians(Mth.clamp(pitch, -85, 85)), y = Math.toRadians(yaw);
        Vec3 look = new Vec3(-Math.sin(y) * Math.cos(r), -Math.sin(r), Math.cos(y) * Math.cos(r));
        Vec3 flat = new Vec3(-Math.sin(y), 0, Math.cos(y)), left = new Vec3(Math.cos(y), 0, Math.sin(y));
        double f = Mth.clamp(forward, -1, 1), s = Mth.clamp(strafe, -1, 1);
        double powered = cruise * (boost && f > 0 ? a.boost() : 1);
        Vec3 wish = f > 0 ? look.scale(f * powered) : f < 0 ? flat.scale(f * cruise * BACK) : Vec3.ZERO;
        wish = wish.add(left.scale(s * a.strafe() * cruise));
        if (ascend) wish = wish.add(0, p.climbSpeed(), 0);
        if (descend) wish = wish.add(0, -p.descendSpeed(), 0);
        double wishSpeed = wish.length(), speed = velocity.length();
        if (wishSpeed < 1.0E-4) {
            // Nothing held: a flare to a hover, the faster the harder.
            double brake = Math.max(p.braking(), speed * .1);
            return speed <= brake ? Vec3.ZERO : velocity.scale((speed - brake) / speed);
        }
        Vec3 wishDir = wish.scale(1 / wishSpeed);
        Vec3 dir = speed < 1.0E-4 ? wishDir : velocity.scale(1 / speed);
        double share = Mth.clamp(cruise / Math.max(cruise, speed), a.fastTurn(), 1);
        double limit = Math.toRadians(p.turnDegrees() * TURN_SCALE * share);
        double angle = Math.acos(Mth.clamp(dir.dot(wishDir), -1, 1));
        Vec3 path = turnTowards(dir, wishDir, angle, limit);
        double turned = Math.min(angle, limit);
        // Height and speed trade along the path; the wings drive toward their own speed, and the rest bleeds off.
        double next = speed + a.gravity() * -path.y;
        if (next < wishSpeed) next = Math.min(wishSpeed, next + p.acceleration());
        else next -= (next - wishSpeed) * a.drag();
        next = Math.max(0, next - turned * speed * TURN_LOSS);
        return path.scale(Math.min(a.maxSpeed(), next));
    }

    /**
     * Over the ground a dive levels out instead of striking it: the drop a tick is held to a share of the height left
     * above {@link #SKIM}, and the speed that takes off the descent goes on along the ground, so a dive becomes a skim at
     * the speed it had. Faster than {@link #SKIM_PACE} over the ground it holds the skim's height; slower, it settles
     * on down to land. Only the descent is touched.
     * @param height blocks from the feet down to the ground (or the probe's limit)
     */
    public static Vec3 skim(Vec3 velocity, double height) {
        if (velocity.y >= 0) return velocity;
        double speed = velocity.length(), flat = velocity.horizontalDistance();
        double floor = -Math.max(flat > SKIM_PACE ? 0 : .04, (height - SKIM) * SKIM_DROP);
        if (velocity.y >= floor) return velocity;
        double along = Math.sqrt(Math.max(0, speed * speed - floor * floor));
        if (flat < 1.0E-4) return new Vec3(0, floor, 0);
        return new Vec3(velocity.x / flat * along, floor, velocity.z / flat * along);
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
