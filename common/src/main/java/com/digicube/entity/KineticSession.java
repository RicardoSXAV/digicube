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
    private final HashSet<UUID> hit = new HashSet<>();
    private boolean kick;
    private float pitch;

    public KineticSession(DigimonEntity owner, LivingEntity target, DigimonAttack attack) {
        this.owner = owner;
        this.target = target;
        definition = KineticAttacks.get(attack);
        start = owner.position();
        startYaw = AttackGeometry.yaw(start, target.position());
    }

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
            var aim = KineticGeometry.aim(d, feet, AttackGeometry.chest(target.getBoundingBox()));
            var pivot = AttackGeometry.world(feet, d.motion().sample(attack.hitTick()).pivot(), aim.yaw());
            return target.getBoundingBox().getCenter().subtract(aim.muzzle()).dot(aim.direction()) > .1
                    && AttackGeometry.chest(target.getBoundingBox()).subtract(aim.muzzle()).normalize().dot(aim.direction()) > .9995
                    && KineticGeometry.clear(owner.level(), owner, pivot, aim.muzzle())
                    && KineticGeometry.clear(owner.level(), owner, aim.muzzle(), AttackGeometry.chest(target.getBoundingBox()))
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
        owner.setDeltaMovement(0, owner.getDeltaMovement().y, 0);
        if (definition.attack().kind() == DigimonAttack.Kind.KINETIC_SHOT) {
            if (tick <= definition.attack().hitTick() && target.isAlive()) {
                Vec3 point = PepperBreathEntity.predictImpactPoint(target, owner.position(), definition.projectileSpeed(), definition.maxLead());
                var aim = KineticGeometry.aim(definition, owner.position(), point);
                face(aim.yaw());
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
        if (!target.isAlive() || !owner.canAttack(target) || owner.isAllyOf(target)) return false;
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
        var aim = KineticGeometry.pose(frame, owner.position(), owner.getYRot(), pitch);
        var pivot = AttackGeometry.world(owner.position(), frame.pivot(), owner.getYRot());
        if (!KineticGeometry.clear(level, owner, pivot, aim.muzzle())) return;
        if (definition.projectileBoxes().stream().anyMatch(b -> KineticGeometry.blocked(level, owner,
                KineticGeometry.flightBox(b, aim.muzzle(), aim.direction())))) return;
        level.addFreshEntity(new KineticProjectileEntity(level, owner, definition, aim.muzzle(), aim.direction()));
        Constants.LOG.info("[kinetic] {} released tick={}", definition.attack().id(), definition.attack().hitTick());
    }

    private void face(float yaw) { owner.setYRot(yaw); owner.yBodyRot = yaw; owner.yHeadRot = yaw; }
}
