package com.digicube.entity;

import com.digicube.digimon.AttackMotion;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.FreezeMark;
import com.digicube.digimon.PounceAttacks;
import com.digicube.registry.DCDamageTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server: one pounce under way ({@link PounceAttacks}). The AI's dash is flown here, tick by tick: a crouch, then the
 * burst along its line, turning after its prey at the move's homing rate, stopping short of the prey's body and at
 * walls, its fall held in the air, its last speed handed back to the body as momentum. A rider's dash is flown by the
 * rider's client, which owns the ridden body; here its jaws are checked along the path the body really takes. The jaws
 * are the attack motion's muzzle segment ({@code horn_base} to {@code horn_tip}) turned by the dash's pitch and the
 * body's heading; the first body they meet in the contact window is bitten, once.
 */
final class PounceSession {
    private final DigimonEntity body;
    private final DigimonAttack attack;
    private final PounceAttacks.Spec spec;
    private final boolean rider, air;
    private LivingEntity prey;
    private Vec3 direction;
    private boolean connected, blocked;
    private Vec3 lastBase, lastTip;
    private LivingEntity bitten;

    /** {@code spec} is the form cast: the move itself, or its wing form on the wing (its own timing, motion and burst). */
    PounceSession(DigimonEntity body, PounceAttacks.Spec spec, LivingEntity prey, Vec3 direction, boolean rider, boolean air) {
        this.body = body;
        this.attack = spec.attack();
        this.spec = spec;
        this.prey = prey;
        this.direction = direction.normalize();
        this.rider = rider;
        this.air = air;
    }

    boolean air() { return air; }
    boolean connected() { return connected; }
    Vec3 direction() { return direction; }
    LivingEntity bitten() { return bitten; }

    /** The dash's pitch, degrees up. */
    float pitch() { return (float) Math.toDegrees(Math.asin(Mth.clamp(direction.y, -1, 1))); }

    /** The facing of the dash, degrees (Minecraft yaw). */
    float yaw() { return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z)); }

    /**
     * One tick at clip tick {@code tick}.
     * @return false when the pounce is spent early (stopped by a wall before it met anything)
     */
    boolean tick(ServerLevel level, int tick) {
        int burstTick = tick - spec.gather();
        boolean bursting = burstTick >= 0 && burstTick < spec.burst();
        if (!rider) fly(level, tick, burstTick, bursting);
        if (!connected && tick >= attack.hitTick() && tick <= spec.contactUntil()) bite(level, tick);
        else if (connected || tick > spec.contactUntil()) { lastBase = lastTip = null; }
        return !(blocked && !connected && burstTick >= spec.burst());
    }

    private void fly(ServerLevel level, int tick, int burstTick, boolean bursting) {
        if (burstTick < 0) {
            // The crouch: gathered on the spot, turned onto the line.
            body.setDeltaMovement(0, Math.min(0, body.getDeltaMovement().y), 0);
            face();
            return;
        }
        if (!bursting || connected || blocked) {
            if (burstTick == spec.burst() && !connected && !blocked) {
                // The burst is spent: what is left of its speed carries on as ordinary momentum.
                Vec3 exit = direction.scale(spec.exit());
                body.setDeltaMovement(exit.x, Math.min(exit.y, .15), exit.z);
                body.needsSync = true;
            }
            return;
        }
        home(burstTick);
        double speed = spec.speedAt(burstTick);
        Vec3 step = direction.scale(speed);
        // Never through its prey: the burst stops where the bodies meet (the jaws, reaching ahead, still bite).
        if (prey != null && prey.isAlive()) {
            double room = gap(body.getBoundingBox(), prey.getBoundingBox(), step);
            if (room < speed) step = direction.scale(Math.max(0, room));
        }
        Vec3 before = body.position();
        body.move(MoverType.SELF, step);
        if (body.horizontalCollision && body.position().subtract(before).horizontalDistance() < step.horizontalDistance() * .4) blocked = true;
        // Gravity is held while it drives: travel() takes one tick of it off next, so leave exactly that.
        body.setDeltaMovement(0, body.getGravity(), 0);
        body.resetFallDistance();
        body.needsSync = true;
        face();
    }

    /** Turns the line after the prey, at most the move's homing rate a tick, keeping its pitch within bounds. */
    private void home(int burstTick) {
        if (prey == null || !prey.isAlive() || spec.home() <= 0) return;
        Vec3 aim = prey.getBoundingBox().getCenter().subtract(jawReach()).subtract(body.position());
        if (aim.lengthSqr() < 1.0E-4) return;
        Vec3 want = aim.normalize();
        double angle = Math.acos(Mth.clamp(direction.dot(want), -1, 1));
        double max = Math.toRadians(spec.home());
        Vec3 turned = angle <= max ? want : slerp(direction, want, max / angle);
        float pitch = spec.pitch((float) Math.toDegrees(Math.asin(Mth.clamp(turned.y, -1, 1))), air || !body.onGround());
        double flat = Math.cos(Math.toRadians(pitch));
        Vec3 horizontal = new Vec3(turned.x, 0, turned.z);
        if (horizontal.lengthSqr() < 1.0E-8) return;
        horizontal = horizontal.normalize().scale(flat);
        direction = new Vec3(horizontal.x, Math.sin(Math.toRadians(pitch)), horizontal.z).normalize();
    }

    /**
     * The jaws' reach ahead of the feet at the burst's middle, so the homing brings the mouth, not the feet, to the prey;
     * a wing form drives tipped along its line, its horn's tip where that tipping puts it.
     */
    private Vec3 jawReach() {
        AttackMotion.Frame frame = attack.motion().sample(spec.gather() + spec.burst() * .5);
        return spec.airborne() ? PounceLines.tipped(body, spec, frame.hornTip(), yaw(), pitch()) : new Vec3(0, frame.mouth().y, 0);
    }

    private void face() {
        float yaw = yaw();
        body.setYRot(yaw);
        body.yHeadRot = body.yBodyRot = yaw;
        body.syncAttackYaw(yaw);
    }

    /**
     * The jaws' muzzle segment at clip tick {@code tick}, from where the body stands, posed by the dash's pitch as the
     * body is drawn ({@link PounceLines#posed}: tipped about the middle of the body, a wing form about the seat, the neck
     * taking a form's share that the body does not).
     */
    private Vec3[] jaws(double tick) {
        AttackMotion.Frame frame = attack.motion().sample(tick);
        float yaw = body.getYRot(), pitch = pitch();
        return new Vec3[]{body.position().add(PounceLines.posed(body, spec, frame, frame.hornBase(), yaw, pitch)),
                body.position().add(PounceLines.posed(body, spec, frame, frame.hornTip(), yaw, pitch))};
    }

    private void bite(ServerLevel level, int tick) {
        Vec3[] now = jaws(tick);
        Vec3 fromBase = lastBase == null ? now[0] : lastBase, fromTip = lastTip == null ? now[1] : lastTip;
        lastBase = now[0]; lastTip = now[1];
        double radius = attack.motion().contactRadius();
        for (int i = 0; i <= 4 && !connected; i++) {
            double u = i / 4.0;
            Vec3 base = fromBase.lerp(now[0], u), tip = fromTip.lerp(now[1], u);
            AABB region = new AABB(base, tip).inflate(radius);
            for (Entity entity : level.getEntities(body, region,
                    e -> DigimonPart.livingOf(e) instanceof LivingEntity living && living != body && living.isAlive() && body.canAttack(living) && !body.isAllyOf(living))) {
                AABB box = entity.getBoundingBox().inflate(radius);
                var contact = box.clip(base, tip);
                if (!box.contains(base) && !box.contains(tip) && contact.isEmpty()) continue;
                Vec3 at = contact.orElse(tip);
                if (level.clip(new ClipContext(body.getEyePosition(), at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() != HitResult.Type.MISS) continue;
                strike(level, DigimonPart.livingOf(entity), at);
                break;
            }
        }
    }

    private void strike(ServerLevel level, LivingEntity victim, Vec3 at) {
        connected = true;
        bitten = victim;
        boolean shatter = FreezeMark.frozen(victim);
        float damage = body.pounceDamage(attack, victim) * (shatter ? spec.shatter() : 1);
        var source = DCDamageTypes.partnerAttack(body);
        Vec3 before = victim.getDeltaMovement();
        boolean hurt = victim.hurtServer(level, source, damage);
        if (hurt) {
            body.setLastHurtMob(victim);
            if (shatter) FreezeMark.shatter(level, victim, at);
            else FreezeMark.freeze(level, victim, spec.freeze(), body);
            // A blow that throws its victim sends it on along the dash in place of the push.
            if (spec.launch() != null) spec.launch().apply(victim, direction, 1, before);
            else if (spec.knockback() > 0) victim.knockback(spec.knockback(), -direction.x, -direction.z, source, damage);
            body.attackLanded(attack, victim);
        }
        // Everyone sees the bite: the jaws shut on it, and a frost bite's ice bursts where they met (the client draws it at
        // its own jaws); a bite without frost (a horn, a blade, a fist) strikes clean.
        level.broadcastEntityEvent(body, DigimonAnimationEvents.IMPACT);
        if (spec.freeze() > 0 || shatter) {
            level.sendParticles(ParticleTypes.ITEM_SNOWBALL, true, true, at.x, at.y, at.z, 14, .25, .25, .25, .18);
            level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, at.x, at.y, at.z, 18, .3, .3, .3, .06);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_HIT, SoundSource.NEUTRAL, 1F, 1.3F);
        } else level.sendParticles(ParticleTypes.CRIT, true, true, at.x, at.y, at.z, 12, .2, .2, .2, .08);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 1F, .7F);
        if (!rider) {
            // The dash ends against its prey with a little of its weight still on it.
            Vec3 settle = direction.scale(.08);
            body.setDeltaMovement(settle.x, Math.min(body.getDeltaMovement().y, 0), settle.z);
            body.needsSync = true;
        }
    }

    /** How far {@code a} may move along {@code step} before it touches {@code b} (blocks along the step's direction). */
    private static double gap(AABB a, AABB b, Vec3 step) {
        double length = step.length();
        if (length < 1.0E-6) return 0;
        // Sample the swept box; exact enough at a quarter of the step.
        for (int i = 1; i <= 8; i++) {
            double u = i / 8.0;
            if (a.move(step.scale(u)).intersects(b)) return length * (i - 1) / 8.0;
        }
        return Double.MAX_VALUE;
    }

    private static Vec3 slerp(Vec3 a, Vec3 b, double t) {
        double dot = Mth.clamp(a.dot(b), -1, 1), angle = Math.acos(dot);
        if (angle < 1.0E-6) return b;
        double s = Math.sin(angle);
        return a.scale(Math.sin((1 - t) * angle) / s).add(b.scale(Math.sin(t * angle) / s)).normalize();
    }
}
