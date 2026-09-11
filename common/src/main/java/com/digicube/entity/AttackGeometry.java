package com.digicube.entity;

import com.digicube.digimon.DigimonAttack;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiPredicate;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Plans from the same contact markers used for damage, without extending an attack's hitbox. */
public final class AttackGeometry {
    private AttackGeometry() {}

    public static float yaw(Vec3 feet, Vec3 target) {
        Vec3 direction = target.subtract(feet);
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    public static Vec3 world(Vec3 feet, Vec3 local, float yaw) {
        return feet.add(local.yRot(-yaw * Mth.DEG_TO_RAD));
    }

    public static float contactYaw(DigimonAttack attack, Vec3 feet, Vec3 target) {
        float result=yaw(feet,target);
        if(attack.kind()==DigimonAttack.Kind.FIST) {
            var f=attack.motion().sample(attack.hitTick());var p=f.hornBase().lerp(f.hornTip(),.5);
            result-=(float)Math.toDegrees(Math.atan2(-p.x,p.z));
        }
        return result;
    }

    /** Exact, stationary-victim rehearsal of a horn/bite, including its collision-limited root travel. */
    public static boolean canContact(DigimonAttack attack, Vec3 feet, double width, double height,
                                     AABB target, BiPredicate<Vec3, Vec3> visible,
                                     Predicate<AABB> free, Predicate<Vec3> supported) {
        // Reject impossible heights/ranges using arithmetic before asking the world for collision shapes.
        return rehearseContact(attack, feet, width, height, target, (a, b) -> true, box -> true, p -> true)
                && rehearseContact(attack, feet, width, height, target, visible, free, supported);
    }

    private static boolean rehearseContact(DigimonAttack attack, Vec3 feet, double width, double height,
                                           AABB target, BiPredicate<Vec3, Vec3> visible,
                                           Predicate<AABB> free, Predicate<Vec3> supported) {
        var motion = attack.motion();
        float yaw = contactYaw(attack, feet, target.getCenter());
        Vec3 forward = Vec3.directionFromRotation(0, yaw);
        Vec3 at = feet;
        AABB padded = target.inflate(motion.contactRadius());
        for (int tick = 0; tick <= motion.activeUntil(); tick++) {
            Vec3 before = at;
            double travel = motion.sample(tick + 1).travel() - motion.sample(tick).travel();
            if (attack.knockback() == 0) {
                travel = Math.min(travel, thrustClearance(at, target, width));
            }
            if (travel > 0) {
                Vec3 step = forward.scale(travel);
                AABB body = new AABB(at.x - width / 2, at.y + .02, at.z - width / 2,
                        at.x + width / 2, at.y + height, at.z + width / 2);
                if (!free.test(body.expandTowards(step).deflate(.01)) || !supported.test(at.add(step))) return false;
                at = at.add(step);
            }
            if (tick < motion.activeFrom()) continue;
            for (int i = 0; i <= 4; i++) {
                double partial = i / 4.0;
                var frame = motion.sample(tick + partial);
                Vec3 root = before.lerp(at, partial);
                Vec3 base = world(root, frame.hornBase(), yaw), tip = world(root, frame.hornTip(), yaw);
                Vec3 contact = padded.contains(base) ? base : padded.clip(base, tip).orElse(null);
                if (contact != null && visible.test(world(root, frame.head(), yaw), contact)) return true;
            }
        }
        return false;
    }

    /** Keep no-knockback lunges outside ordinary body-pushing distance. */
    public static double thrustClearance(Vec3 feet, AABB target, double width) {
        double targetWidth = Math.max(target.getXsize(), target.getZsize());
        return Math.max(0, target.getCenter().subtract(feet).horizontalDistance() - (width + targetWidth) * .5 - .08);
    }

    /** An upper-chest point gives downward shots room above the ground while staying inside the victim. */
    public static Vec3 chest(AABB target) {
        return target.getCenter().add(0, target.getYsize() * .2, 0);
    }

    /** A clear point inside the victim plus the neck pitch which actually reaches it. */
    public record StreamAim(Vec3 target, float pitch) {}

    public static StreamAim streamAim(DigimonAttack attack, double tick, Vec3 feet, AABB target, float yaw,
                                      BiFunction<Vec3, Vec3, Vec3> clip) {
        var frame = attack.motion().sample(tick);
        for (double height : new double[]{.7, .85, .5}) {
            Vec3 point = target.getCenter().add(0, target.getYsize() * (height - .5), 0);
            float pitch = FlameStream.aimPitch(frame, feet, point, yaw, 0);
            Vec3 head = world(feet, frame.head(), yaw), mouth = world(feet, frame.aimedMouth(pitch), yaw);
            Vec3 direction = FlameStream.direction(frame, pitch, yaw);
            // An oversized volume is never permission to fire backwards through a close target.
            if (point.subtract(mouth).dot(direction) <= .05) continue;
            var stream = FlameStream.trace(head, mouth, direction, attack.range(), attack.motion().contactRadius(), clip);
            if (stream.intersects(target) && clip.apply(mouth, point).distanceToSqr(point) < 1.0E-8) {
                return new StreamAim(point, pitch);
            }
        }
        return null;
    }
}
