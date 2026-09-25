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
    private Vec3 start;
    private float startYaw;
    /** A rider's buck: the kick on the faster rider clock, sliding and turning onto its prey until the hooves swing. */
    private boolean buck;
    private double standoff;
    /** Blocks a tick a buck's start may slide toward its prey, and degrees a tick it may turn after it. */
    private static final double BUCK_SLIDE = .6;
    private static final float BUCK_TURN = 25;
    /** A rider's crosshair point, for a shot cast without a target; null for the AI. */
    private final java.util.function.Supplier<Vec3> viewPoint;
    private float aimYaw;
    private final HashSet<UUID> hit = new HashSet<>();
    private boolean kick;
    private float pitch;
    /** Where the shot was last aimed (the led target, or the rider's crosshair point). */
    private Vec3 aimedAt;
    /** Under a rider: a shot aimed by the view, or a buck committed at once. */
    private final boolean ridden;
    /** A rider's shot loosed on the run: the rider's client moves and turns the mount, the upper body turns to the aim. */
    private boolean twists;
    /** A rider's drawn shot: {@code charge} runs from 0 (a snap shot) to 1 (held to the full); the bolt hits harder and flies faster. */
    private boolean drawn;
    private float charge = 1;
    /** Degrees the upper body may turn from the horse body's heading before the shot and the pose part ways. */
    public static final float MAX_TWIST = 110;

    public KineticSession(DigimonEntity owner, LivingEntity target, DigimonAttack attack) { this(owner, target, attack, (java.util.function.Supplier<Vec3>) null); }

    /** {@code target} may be null for a rider, whose shot then follows {@code viewPoint}. */
    public KineticSession(DigimonEntity owner, LivingEntity target, DigimonAttack attack, java.util.function.Supplier<Vec3> viewPoint) {
        this.owner = owner;
        this.target = target;
        this.viewPoint = viewPoint;
        this.ridden = viewPoint != null;
        definition = KineticAttacks.get(attack);
        start = owner.position();
        startYaw = aimYaw = target != null ? AttackGeometry.yaw(start, target.position()) : owner.getYRot();
    }

    /**
     * A rider's buck: the retreat kick committed at once (no decision tick), from {@code start} and turned from
     * {@code target}, the prey a jet charge has run down. The server moves the body along it, as it does unridden.
     */
    public KineticSession(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 start) {
        this.owner = owner;
        this.target = target;
        this.viewPoint = null;
        this.ridden = true;
        definition = KineticAttacks.get(attack);
        this.start = start;
        startYaw = aimYaw = AttackGeometry.yaw(start, target.position());
        kick = true;
        buck = definition.riderKick() != null;
        // Where the kick was authored to start: the bodies half a block into each other, as the AI's retreat kick finds them.
        standoff = owner.getBbWidth() * .5 + target.getBbWidth() * .5 - .45;
    }

    public void charge(float charge) { this.charge = Math.clamp(charge, 0, 1); }
    public float charge() { return charge; }
    /** The shot is aimed by a rider's view. */
    public boolean riderShot() { return viewPoint != null; }
    /** Loosed on the run: the legs are someone else's (the rider's client, or the AI's combat goal), the upper body aims. */
    public boolean twists() { return twists; }
    /** How a shot is cast: drawn (held, it charges) and on the run (the upper body twists to the aim). The AI's is never drawn. */
    public void riderStyle(boolean drawn, boolean twists) {
        this.drawn = drawn;
        this.twists = twists;
        if (drawn) charge = 0;
    }

    /** The yaw the shot is aimed along; a rider's client turns the mount to it. */
    public float aimYaw() { return aimYaw; }

    public boolean kick() { return kick; }
    public Vec3 start() { return start; }
    public float startYaw() { return startYaw; }
    public int duration() { return buck ? Math.round(definition.riderKick().length()) : definition.duration(kick); }
    public float pitch() { return pitch; }
    public String animation() { return buck ? definition.riderKick().animation() : definition.animation(kick); }
    /** A buck still sliding onto its prey: its start moves, so the clients need it again. */
    public boolean homing(int tick) { return buck && tick <= homeUntil(); }
    /** The buck tick the hooves start to swing: the kick's hit window, on the buck's clock. */
    private double homeUntil() { return buckTickOf(definition.attack().motion().activeFrom()); }
    private double buckTickOf(double kickTime) {
        for (double t = 0; t <= definition.riderKick().length(); t += .25) if (definition.riderKick().kickTime(t) >= kickTime) return t;
        return definition.riderKick().length();
    }

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
        if (!twists) owner.getNavigation().stop();
        // A rider's shot is loosed on the run: the rider's client moves and turns the mount, only the arm aims.
        if (!twists) owner.setDeltaMovement(0, owner.isInWater()?0:owner.getDeltaMovement().y, 0);
        if (definition.attack().kind() == DigimonAttack.Kind.KINETIC_SHOT) {
            if (tick <= definition.attack().hitTick() && (target != null && target.isAlive() || viewPoint != null)) {
                Vec3 point;
                if (target != null && target.isAlive()) {
                    point = PepperBreathEntity.predictImpactPoint(target, owner.position(), definition.projectileSpeed(), definition.maxLead());
                    if(definition.projectileMotion()!=null) point=point.add(targetPoint(definition,target).subtract(AttackGeometry.chest(target.getBoundingBox())));
                } else point = viewPoint.get();
                aimedAt = point;
                var aim = KineticGeometry.aim(definition, owner.position(), point);
                aimYaw = aim.yaw();
                if (!twists) face(aimYaw);
                pitch = aim.pitch();
            }
            return true;
        }
        if (tick == definition.decisionTick() && !ridden) kick = opportunity();
        var motion = definition.motion(kick);
        if (homing(tick) && target != null && target.isAlive()) {
            // The buck skids on into its prey while it wheels, and turns after it: a charge that arrives short or a
            // prey that sidesteps still meets the hooves.
            Vec3 to = target.position().subtract(start).multiply(1, 0, 1);
            if (to.lengthSqr() > 1.0E-6) {
                startYaw = net.minecraft.util.Mth.approachDegrees(startYaw, AttackGeometry.yaw(start, target.position()), BUCK_TURN);
                start = start.add(to.normalize().scale(Math.clamp(to.length() - standoff, -BUCK_SLIDE, BUCK_SLIDE)));
            }
        }
        int samples = buck ? motion.samplesPerTick() * 3 : motion.samplesPerTick();
        for (int sub = 0; sub <= samples; sub++) {
            double tau = Math.max(0, tick - 1 + (double) sub / samples);
            double time = buck ? definition.riderKick().kickTime(tau) : tau;
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
                            if (ridden) level.broadcastEntityEvent(owner, DigimonAnimationEvents.SLAM);
                            if (buck) owner.countSkill("buck_landed");
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

    /**
     * Under water a rider's shot is not held to the body's reach up and down ({@code max_pitch}, a spit from a standing
     * body): it leaves the muzzle along the line to where it was aimed, up or down, as a swimmer turns to spit.
     */
    private Vec3 waterLine(Vec3 muzzle, Vec3 aimed) {
        if (!ridden || aimedAt == null || !owner.isInWater()) return aimed;
        Vec3 line = aimedAt.subtract(muzzle);
        return line.lengthSqr() > 1 && line.normalize().dot(aimed) > .3 ? line.normalize() : aimed;
    }

    public void fire(ServerLevel level) {
        var frame = definition.motion().sample(definition.attack().hitTick());
        // Under a rider the facing arrives from the client a moment late; the shot leaves along the solved aim.
        var aim = KineticGeometry.pose(frame, owner.position(), viewPoint != null || twists ? aimYaw : owner.getYRot(), pitch);
        var pivot = clearanceOrigin(definition,owner.position(),aim);
        if (twists) {
            // Only the upper body turned to the aim, about its own base: the muzzle is where that turn puts it.
            Vec3 shift = twistShift(frame, owner.getYRot(), aimYaw);
            aim = new KineticGeometry.Aim(aim.yaw(), aim.pitch(), aim.muzzle().add(shift), aim.direction());
            pivot = pivot.add(shift);
        }
        Vec3 muzzle = aim.muzzle(), direction = waterLine(muzzle, aim.direction());
        if (!KineticGeometry.clear(level, owner, pivot, muzzle)) return;
        if (definition.projectileBoxes().stream().anyMatch(b -> KineticGeometry.blocked(level, owner,
                KineticGeometry.flightBox(b, muzzle, direction)))) return;
        float power = drawn ? .8F + .5F * charge : 1, speed = drawn ? 1 + .4F * charge : 1;
        level.addFreshEntity(new KineticProjectileEntity(level, owner, definition, muzzle, direction, power, speed));
        definition.shotStyle().fire(level, muzzle, direction, drawn ? charge : 1);
        Constants.LOG.info("[kinetic] {} released tick={} charge={}", definition.attack().id(), definition.attack().hitTick(),
                drawn ? String.format("%.2f", charge) : "-");
    }

    /**
     * How far the muzzle moves when only the upper body turns to {@code aimYaw} while the horse body keeps
     * {@code bodyYaw}: that turn is about the upper body's base (the shoulder pivot's depth on the body's axis),
     * not about the feet. Clamped to {@link #MAX_TWIST}, as the pose is.
     */
    public static Vec3 twistShift(KineticAttacks.Frame frame, float bodyYaw, float aimYaw) {
        float twist = Math.clamp(net.minecraft.util.Mth.wrapDegrees(aimYaw - bodyYaw), -MAX_TWIST, MAX_TWIST);
        Vec3 base = new Vec3(0, 0, frame.pivot().z);
        return AttackGeometry.world(Vec3.ZERO, base, aimYaw - twist).subtract(AttackGeometry.world(Vec3.ZERO, base, aimYaw));
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
