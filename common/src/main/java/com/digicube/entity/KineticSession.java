package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.KineticAttacks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.UUID;

/** A committed, collision-checked retreat or a hand-aimed projectile timeline. */
public final class KineticSession {
    private final DigimonEntity owner;
    private final LivingEntity target;
    private final KineticAttacks.Definition definition;
    private final Vec3 start;
    private final float startYaw;
    /** A rider's crosshair point, for a shot cast without a target; null for the AI. */
    private final java.util.function.Supplier<Vec3> viewPoint;
    private float aimYaw;
    private final HashSet<UUID> hit = new HashSet<>();
    private boolean kick;
    private float pitch;

    public KineticSession(DigimonEntity owner, LivingEntity target, DigimonAttack attack) { this(owner, target, attack, null); }

    /** {@code target} may be null for a rider, whose shot then follows {@code viewPoint}. */
    public KineticSession(DigimonEntity owner, LivingEntity target, DigimonAttack attack, java.util.function.Supplier<Vec3> viewPoint) {
        this.owner = owner;
        this.target = target;
        this.viewPoint = viewPoint;
        definition = KineticAttacks.get(attack);
        start = owner.position();
        startYaw = aimYaw = target != null ? AttackGeometry.yaw(start, target.position()) : owner.getYRot();
    }

    /** The yaw the shot is aimed along; a rider's client turns the mount to it. */
    public float aimYaw() { return aimYaw; }

    public boolean kick() { return kick; }
    public Vec3 start() { return start; }
    public float startYaw() { return startYaw; }
    public int duration() { return definition.duration(kick); }
    public float pitch() { return pitch; }
    public String animation() { return definition.animation(kick); }

    private static boolean safe(DigimonEntity owner, Vec3 feet) {
        var box = owner.getBoundingBox().move(feet.subtract(owner.position())).deflate(.001);
        return owner.level().getWorldBorder().isWithinBounds(box)
                && !owner.level().getBlockCollisions(owner, box).iterator().hasNext()
                && !KineticGeometry.clear(owner.level(), owner, feet.add(0, .1, 0), feet.add(0, -.35, 0));
    }

    public static boolean canStart(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet) {
        var d = KineticAttacks.get(attack);
        if (attack.kind() == DigimonAttack.Kind.KINETIC_SHOT) {
            var aim = KineticGeometry.aim(d, feet, targetPoint(d,target));
            var pivot = clearanceOrigin(d,feet,aim);
            return target.getBoundingBox().getCenter().subtract(aim.muzzle()).dot(aim.direction()) > .1
                    && targetPoint(d,target).subtract(aim.muzzle()).normalize().dot(aim.direction()) > .9995
                    && KineticGeometry.clear(owner.level(), owner, pivot, aim.muzzle())
                    && KineticGeometry.clear(owner.level(), owner, aim.muzzle(), targetPoint(d,target))
                    && d.projectileBoxes().stream().noneMatch(b -> KineticGeometry.blocked(owner.level(), owner,
                            KineticGeometry.flightBox(b, aim.muzzle(), aim.direction())));
        }
        if (!owner.onGround() || owner.isInWater() || owner.isInLava()) return false;
        float yaw = AttackGeometry.yaw(feet, target.position());
        for (int i = 0; i <= d.motion().frames().size() - 1; i++) {
            Vec3 destination = AttackGeometry.world(feet, d.motion().frames().get(i).offset(), yaw);
            if (!safe(owner, destination)) return false;
        }
        return true;
    }

    public boolean tick(ServerLevel level, int tick) {
        owner.getNavigation().stop();
        owner.setDeltaMovement(0, owner.isInWater()?0:owner.getDeltaMovement().y, 0);
        if (definition.attack().kind() == DigimonAttack.Kind.KINETIC_SHOT) {
            if (tick <= definition.attack().hitTick() && (target != null && target.isAlive() || viewPoint != null)) {
                Vec3 point;
                if (target != null && target.isAlive()) {
                    point = PepperBreathEntity.predictImpactPoint(target, owner.position(), definition.projectileSpeed(), definition.maxLead());
                    if(definition.projectileMotion()!=null) point=point.add(targetPoint(definition,target).subtract(AttackGeometry.chest(target.getBoundingBox())));
                } else point = viewPoint.get();
                var aim = KineticGeometry.aim(definition, owner.position(), point);
                aimYaw = aim.yaw();
                face(aimYaw);
                pitch = aim.pitch();
            }
            return true;
        }
        if (tick == definition.decisionTick()) kick = opportunity();
        var motion = definition.motion(kick);
        for (int sub = 0; sub <= motion.samplesPerTick(); sub++) {
            double time = Math.max(0, tick - 1 + (double) sub / motion.samplesPerTick());
            var frame = motion.sample(time);
            Vec3 feet = AttackGeometry.world(start, frame.offset(), startYaw);
            if (!safe(owner, feet)) return false;
            owner.setPos(feet);
            face(startYaw + frame.yaw());
            if (kick && time >= definition.attack().motion().activeFrom() && time <= definition.attack().motion().activeUntil()) {
                for (var local : frame.hooves()) {
                    var box = local.world(feet, owner.getYRot(), 0);
                    if (KineticGeometry.blocked(level, owner, box)) continue;
                    for (var entity : level.getEntities(owner, box.bounds())) {
                        LivingEntity victim = DigimonPart.livingOf(entity);
                        if (victim == null || victim == owner || hit.contains(victim.getUUID())
                                || HitParts.of(victim).stream().noneMatch(box::intersects)) continue;
                        if (owner.hitWithAttack(level, definition.attack(), victim)) {
                            hit.add(victim.getUUID());
                            Constants.LOG.info("[kinetic] {} hoof hit {} tick={}", definition.attack().id(), victim.getType().toShortString(), time);
                        }
                    }
                }
            }
        }
        return true;
    }

    private boolean opportunity() {
        if (target == null || !target.isAlive() || !owner.canAttack(target) || owner.isAllyOf(target)) return false;
        for (double time = definition.attack().motion().activeFrom(); time <= definition.attack().motion().activeUntil(); time += .25) {
            var frame = definition.kickMotion().sample(time);
            Vec3 feet = AttackGeometry.world(start, frame.offset(), startYaw);
            Vec3 lead = target.getDeltaMovement().multiply(1, 0, 1).scale(time - definition.decisionTick());
            if (lead.length() > definition.maxLead()) lead = lead.normalize().scale(definition.maxLead());
            for (var box : frame.hooves()) {
                var world = box.world(feet, startYaw + frame.yaw(), 0);
                for (var part : HitParts.of(target)) if (world.intersects(part.move(lead))) return true;
            }
        }
        return false;
    }

    public void fire(ServerLevel level) {
        var frame = definition.motion().sample(definition.attack().hitTick());
        // Under a rider the facing arrives from the client a moment late; the shot leaves along the solved aim.
        var aim = KineticGeometry.pose(frame, owner.position(), viewPoint != null ? aimYaw : owner.getYRot(), pitch);
        var pivot = clearanceOrigin(definition,owner.position(),aim);
        if (!KineticGeometry.clear(level, owner, pivot, aim.muzzle())) return;
        if (definition.projectileBoxes().stream().anyMatch(b -> KineticGeometry.blocked(level, owner,
                KineticGeometry.flightBox(b, aim.muzzle(), aim.direction())))) return;
        level.addFreshEntity(new KineticProjectileEntity(level, owner, definition, aim.muzzle(), aim.direction()));
        Constants.LOG.info("[kinetic] {} released tick={}", definition.attack().id(), definition.attack().hitTick());
    }

    private void face(float yaw) { owner.setYRot(yaw); owner.yBodyRot = yaw; owner.yHeadRot = yaw; }
    private static Vec3 targetPoint(KineticAttacks.Definition d,LivingEntity target) {
        var box=target.getBoundingBox();
        return !d.aimAtTop()?AttackGeometry.chest(box):new Vec3(box.getCenter().x,box.maxY-.1,box.getCenter().z);
    }
    private static Vec3 clearanceOrigin(KineticAttacks.Definition d,Vec3 feet,KineticGeometry.Aim aim) {
        var frame=d.motion().sample(d.attack().hitTick());
        var anchor=d.attack().motion().sample(d.attack().hitTick()).head();
        return AttackGeometry.world(feet,KineticGeometry.aimedPoint(frame,anchor,aim.pitch()),aim.yaw());
    }
}
