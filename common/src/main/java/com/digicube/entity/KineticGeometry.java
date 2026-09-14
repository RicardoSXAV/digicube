package com.digicube.entity;

import com.digicube.digimon.KineticAttacks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared aiming and oriented solid geometry in feet-relative block coordinates. */
public final class KineticGeometry {
    private KineticGeometry() {}
    public record Aim(float yaw, float pitch, Vec3 muzzle, Vec3 direction) {}

    public static Vec3 rotate(Vec3 point, Vec3 axis, double radians) {
        double c = Math.cos(radians), s = Math.sin(radians);
        return point.scale(c).add(axis.cross(point).scale(s)).add(axis.scale(axis.dot(point) * (1 - c)));
    }

    public static Vec3 right(KineticAttacks.Frame frame) {
        return new Vec3(frame.direction().z, 0, -frame.direction().x).normalize();
    }

    public static Vec3 aimedPoint(KineticAttacks.Frame frame, Vec3 point, float pitch) {
        return frame.pivot().add(rotate(point.subtract(frame.pivot()), right(frame), Math.toRadians(pitch)));
    }

    public static Aim pose(KineticAttacks.Frame frame, Vec3 feet, float yaw, float pitch) {
        return new Aim(yaw, pitch, AttackGeometry.world(feet, aimedPoint(frame, frame.muzzle(), pitch), yaw),
                rotate(frame.direction(), right(frame), Math.toRadians(pitch)).yRot((float) -Math.toRadians(yaw)));
    }

    /** Iteration accounts for the off-centre hand moving when the shoulder pitches. */
    public static Aim aim(KineticAttacks.Definition definition, Vec3 feet, Vec3 target) {
        var frame = definition.motion().sample(definition.attack().hitTick());
        float yaw = AttackGeometry.yaw(feet, target), pitch = 0;
        for (int i = 0; i < 96; i++) {
            Aim current = pose(frame, feet, yaw, pitch);
            Vec3 desired = target.subtract(current.muzzle());
            float yawError = net.minecraft.util.Mth.wrapDegrees(AttackGeometry.yaw(Vec3.ZERO, desired)
                    - AttackGeometry.yaw(Vec3.ZERO, current.direction()));
            float pitchError = (float) Math.toDegrees(Math.atan2(current.direction().y, current.direction().horizontalDistance())
                    - Math.atan2(desired.y, desired.horizontalDistance()));
            if (Math.abs(yawError) + Math.abs(pitchError) < .001) break;
            // The muzzle moves with pitch. Damping avoids overshooting around nearby targets.
            yaw += yawError * .5F;
            pitch += pitchError * .5F;
            pitch = Math.clamp(pitch, -definition.maxPitch(), definition.maxPitch());
        }
        return pose(frame, feet, yaw, pitch);
    }

    public static boolean clear(Level level, Entity owner, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner))
                .getType() == HitResult.Type.MISS;
    }

    public static AttackBox flightBox(AttackBox box, Vec3 origin, Vec3 direction) {
        Vec3 forward = direction.normalize();
        Vec3 right = new Vec3(forward.z, 0, -forward.x).normalize();
        if (right.lengthSqr() < .01) right = new Vec3(1, 0, 0);
        Vec3 up = forward.cross(right);
        return new AttackBox(basis(box.center(), right, up, forward).add(origin),
                basis(box.x(), right, up, forward), basis(box.y(), right, up, forward), basis(box.z(), right, up, forward));
    }

    private static Vec3 basis(Vec3 v, Vec3 right, Vec3 up, Vec3 forward) {
        return right.scale(v.x).add(up.scale(v.y)).add(forward.scale(v.z));
    }

    public static boolean blocked(Level level, Entity owner, AttackBox box) {
        if (!level.getWorldBorder().isWithinBounds(box.bounds())) return true;
        for (var shape : level.getBlockCollisions(owner, box.bounds())) {
            for (var block : shape.toAabbs()) if (box.intersects(block)) return true;
        }
        return false;
    }
}
