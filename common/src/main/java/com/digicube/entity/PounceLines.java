package com.digicube.entity;

import com.digicube.digimon.AttackMotion;
import com.digicube.digimon.PounceAttacks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a pounce goes ({@link PounceAttacks}): the line its burst takes, from the body at the height of its jaws. The
 * same on the rider's client, which flies a rider's pounce from the press, and on the server, which checks its bite.
 */
public final class PounceLines {
    private PounceLines() {}

    /** Blocks the crosshair is followed out to, for the point a rider's pounce is aimed at. */
    private static final double SIGHT = 32;

    /**
     * A rider's pounce: from the jaws to the point under the crosshair (the first block or body on the ray from the
     * rider's eye, which the third-person camera shares), or to the soft target's chest when one is outlined; its pitch
     * bounded for the ground or the air.
     */
    public static Vec3 rider(DigimonEntity body, PounceAttacks.Spec spec, Vec3 eye, Vec3 look, LivingEntity soft, boolean air) {
        Vec3 jaws = origin(body, spec);
        Vec3 point;
        if (soft != null) point = AttackGeometry.chest(soft.getBoundingBox());
        else {
            Vec3 end = eye.add(look.scale(SIGHT));
            var hit = body.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
            point = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
            // Aimed at the ground close by, a pounce still goes on along it rather than into it.
            if (!air && point.y < jaws.y && point.distanceTo(eye) < 8) point = new Vec3(point.x, jaws.y, point.z);
        }
        Vec3 to = point.subtract(jaws);
        if (to.lengthSqr() < 1.0E-4) to = look;
        return bound(to, spec, air);
    }

    /** The AI's pounce: at where its prey will be when the jaws get there (TargetMotion), aimed at its chest. */
    public static Vec3 ai(DigimonEntity body, PounceAttacks.Spec spec, LivingEntity target, TargetMotion motion) {
        if (target == null) return Vec3.directionFromRotation(0, body.getYRot());
        Vec3 chest = AttackGeometry.chest(target.getBoundingBox());
        Vec3 jaws = origin(body, spec);
        double flight = spec.gather() + chest.distanceTo(jaws) / Math.max(.2, (spec.speed()[0] + spec.speed()[1]) * .5);
        if (motion != null && motion.follows(target)) {
            Vec3 ahead = motion.predict(target, flight);
            chest = new Vec3(ahead.x, chest.y + (ahead.y - target.getBoundingBox().getCenter().y), ahead.z);
        }
        boolean air = !body.onGround() && !body.isInWater();
        Vec3 line = bound(chest.subtract(jaws), spec, air);
        // From a leap the jaws are far ahead of the body: a dive straight at prey on a ledge would drive the body into
        // the ledge's edge, so the line is raised until the body clears it, and the burst's homing takes it down after.
        for (float lift = 10; air && lift <= 60 && blocked(body, line, spec.distance()); lift += 10) {
            Vec3 raised = raise(line, lift, spec);
            if (!blocked(body, raised, spec.distance())) return raised;
        }
        return line;
    }

    /** The line with its pitch raised by {@code degrees}, within what a pounce from the air may take. */
    private static Vec3 raise(Vec3 line, float degrees, PounceAttacks.Spec spec) {
        double flat = Math.sqrt(line.x * line.x + line.z * line.z);
        float pitch = (float) Math.toDegrees(Math.atan2(line.y, Math.max(1.0E-4, flat)));
        return bound(new Vec3(line.x, Math.tan(Math.toRadians(Math.min(pitch + degrees, 89))) * Math.max(1.0E-4, flat), line.z), spec, true);
    }

    /**
     * Where a pounce's line is drawn from: the middle of the body at the height of the jaws at the burst's start. From
     * the jaws themselves (far ahead of a long body) prey closer than them would be behind the line's start, and the
     * dash would go backwards.
     */
    static Vec3 origin(DigimonEntity body, PounceAttacks.Spec spec) {
        AttackMotion.Frame frame = spec.attack().motion().sample(spec.gather());
        return body.position().add(0, frame.mouth().y, 0);
    }

    /** The line's pitch held within what a pounce from the ground or the air may take. */
    static Vec3 bound(Vec3 to, PounceAttacks.Spec spec, boolean air) {
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        float pitch = (float) Math.toDegrees(Math.atan2(to.y, Math.max(1.0E-4, flat)));
        float held = spec.pitch(pitch, air);
        Vec3 horizontal = flat < 1.0E-4 ? new Vec3(0, 0, 1) : new Vec3(to.x / flat, 0, to.z / flat);
        double r = Math.toRadians(held);
        return new Vec3(horizontal.x * Math.cos(r), Math.sin(r), horizontal.z * Math.cos(r)).normalize();
    }

    /** Whether the body driven along {@code line} meets a block within its burst's first three blocks (half-block steps). */
    static boolean blocked(DigimonEntity body, Vec3 line, double distance) {
        AABB box = body.getBoundingBox().deflate(.05);
        for (double d = .5; d <= Math.min(distance, 3) + 1.0E-6; d += .5)
            if (!body.level().noCollision(body, box.move(line.scale(d)))) return true;
        return false;
    }
}
