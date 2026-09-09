package com.digicube.entity;

import net.minecraft.world.entity.Entity;
import com.digicube.digimon.AttackMotion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.function.BiFunction;

/**
 * Shared render/damage geometry: a continuous mouth-attached jet clipped against solid terrain.
 * @param origin mouth in world space
 * @param end furthest unobstructed point of the jet
 * @param radius half-width at the mouth; entrainment widens the jet downstream
 */
public record FlameStream(Vec3 origin, Vec3 end, double radius) {
    /** @return visible length in blocks */
    public double length() { return origin.distanceTo(end); }

    /** @return broadphase bounds around the entire jet */
    public AABB bounds() { return new AABB(origin, end).inflate(radiusAt(length())); }

    /** @param distance distance from the mouth @return half-width of the turbulent plume */
    public double radiusAt(double distance) { return radius + Math.max(0, distance) * 0.07; }

    /**
     * A forceful jet slows as it entrains surrounding air. This is an art-directed flow
     * approximation in blocks/ticks, shared with the Blender flame transport curves.
     * @param age time since emission in ticks
     * @return distance travelled in blocks
     */
    public static double flowDistance(double age) {
        return 1.18 / 0.07 * (1 - Math.exp(-0.07 * Math.max(0, age)));
    }

    /** @param frame authored pose @param pitch extra downward aim @param yaw body yaw
     * @return unit direction in the same convention as the living model */
    public static Vec3 direction(AttackMotion.Frame frame, float pitch, float yaw) {
        return Vec3.directionFromRotation(frame.headPitch() + pitch * frame.aimWeight(), yaw);
    }

    /** Solve the rotating mouth offset as well as the sight line to the target.
     * @param frame authored pose @param feet entity position @param target target centre
     * @param yaw body yaw @param previousPitch starting guess
     * @return additional head pitch, constrained to the authored neck's useful range */
    public static float aimPitch(AttackMotion.Frame frame, Vec3 feet, Vec3 target, float yaw, float previousPitch) {
        if (frame.aimWeight() < .001F) return previousPitch;
        float previous = Mth.clamp(previousPitch, -55, 60);
        if (Math.abs(pitchError(frame, feet, target, yaw, previous)) < .001F) return previous;
        // Repeatedly aiming from the newly rotated mouth oscillates at close range
        // on a long snout. Bracket the angular error instead of chasing that offset.
        float low = -55, high = 60;
        for (int i = 0; i < 16; i++) {
            float pitch = (low + high) * .5F;
            if (pitchError(frame, feet, target, yaw, pitch) > 0) low = pitch;
            else high = pitch;
        }
        return (low + high) * .5F;
    }

    private static float pitchError(AttackMotion.Frame frame, Vec3 feet, Vec3 target, float yaw, float pitch) {
        Vec3 mouth = feet.add(frame.aimedMouth(pitch).yRot(-yaw * Mth.DEG_TO_RAD));
        Vec3 aim = target.subtract(mouth);
        double forward = aim.dot(Vec3.directionFromRotation(0, yaw));
        return (float) Math.toDegrees(Math.atan2(-aim.y, forward)) - frame.headPitch() - pitch * frame.aimWeight();
    }

    /** @param target victim bounds @return whether the jet's volume touches the victim */
    public boolean intersects(AABB target) {
        double length = length();
        if (length < 1.0E-5) return false;
        Vec3 direction = end.subtract(origin).scale(1 / length);
        for (double from = 0; from < length; from += 0.4) {
            double to = Math.min(length, from + 0.4);
            Vec3 start = origin.add(direction.scale(from));
            AABB padded = target.inflate(radiusAt(to));
            if (padded.contains(start) || padded.clip(start, origin.add(direction.scale(to))).isPresent()) return true;
        }
        return false;
    }

    /**
     * Clip the throat and the centre/edges of the jet, so its width cannot reach around a wall.
     * @param emitter entity whose world supplies collision shapes
     * @param head head pivot in world space
     * @param mouth mouth in world space
     * @param direction normalized firing direction
     * @param reach maximum visible length
     * @param radius half-width of the jet
     * @return terrain-clipped stream
     */
    public static FlameStream trace(Entity emitter, Vec3 head, Vec3 mouth, Vec3 direction,
                                    double reach, double radius) {
        return trace(head, mouth, direction, reach, radius, (from, to) -> {
            HitResult hit = clip(emitter, from, to);
            return hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
        });
    }

    /** Collision adapter also allows terrain regressions without launching Minecraft. */
    public static FlameStream trace(Vec3 head, Vec3 mouth, Vec3 direction, double reach, double radius,
                                    BiFunction<Vec3, Vec3, Vec3> clip) {
        if (clip.apply(head, mouth).distanceToSqr(mouth) > 1.0E-8) {
            return new FlameStream(mouth, mouth, radius);
        }
        Vec3 side = direction.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0E-8) side = new Vec3(1, 0, 0);
        side = side.normalize();
        Vec3 up = side.cross(direction).normalize();
        double length = reach;
        // The widening boundary is linear, so each edge needs only one world ray.
        // Include diagonals without multiplying collision work by the number of eddies.
        for (int edge = -1; edge < 8 && reach > 0; edge++) {
            double angle = edge * Math.PI / 4;
            Vec3 radial = edge < 0 ? Vec3.ZERO : side.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            Vec3 start = mouth.add(radial.scale(radius));
            Vec3 finish = mouth.add(direction.scale(reach)).add(radial.scale(radius + 0.07 * reach));
            Vec3 hit = clip.apply(start, finish);
            if (hit.distanceToSqr(finish) > 1.0E-8) {
                double fraction = start.distanceTo(hit) / start.distanceTo(finish);
                length = Math.min(length, Math.max(0, reach * fraction - 0.03));
            }
        }
        return new FlameStream(mouth, mouth.add(direction.scale(length)), radius);
    }

    private static HitResult clip(Entity emitter, Vec3 from, Vec3 to) {
        return emitter.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, emitter));
    }
}
