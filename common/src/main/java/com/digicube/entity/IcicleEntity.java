package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.ThrownAttacks;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A charged throw in flight ({@link ThrownAttacks.Charged}): a spear of ice whose size, weight and bite follow the
 * charge it was thrown with. A snap throw is small, quick and nearly flat; a full charge is twice as long and thick,
 * slow and drops hard, so it has to be lobbed and led, and hits for several times as much. It strikes the first enemy
 * its whole body touches, shattering, or buries its point in the ground and stands there a moment before it breaks.
 */
public final class IcicleEntity extends Projectile {
    public enum Phase { FLYING, STUCK, SHATTERED }
    private static final EntityDataAccessor<String> ATTACK = SynchedEntityData.defineId(IcicleEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> CHARGE = SynchedEntityData.defineId(IcicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> PHASE = SynchedEntityData.defineId(IcicleEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<org.joml.Vector3fc> HEADING = SynchedEntityData.defineId(IcicleEntity.class, EntityDataSerializers.VECTOR3);
    private int age, stuck;
    /** Server: how much harder it left the hand than a braced throw (a leap, a run): it hits and shoves harder. */
    private float impulse = 1;

    public IcicleEntity(EntityType<? extends IcicleEntity> type, Level level) { super(type, level); }

    public IcicleEntity(ServerLevel level, DigimonEntity owner, ThrownAttacks.Charged spec, float charge, Vec3 origin, Vec3 velocity, float impulse) {
        this(DCEntityTypes.ICICLE, level);
        this.impulse = impulse;
        setOwner(owner);
        entityData.set(ATTACK, spec.attack().id().getPath());
        entityData.set(CHARGE, charge);
        setPos(origin);
        setDeltaMovement(velocity);
        heading(velocity);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ATTACK, "").define(CHARGE, 0F).define(PHASE, (byte) 0).define(HEADING, new org.joml.Vector3f(0, 0, 1));
    }

    public ThrownAttacks.Charged spec() {
        String name = entityData.get(ATTACK);
        for (var c : ThrownAttacks.charged()) if (c.attack().id().getPath().equals(name)) return c;
        return null;
    }
    public float charge() { return entityData.get(CHARGE); }
    public Phase phase() { return Phase.values()[Math.clamp(entityData.get(PHASE), 0, 2)]; }
    public Vec3 heading() { var h = entityData.get(HEADING); return new Vec3(h.x(), h.y(), h.z()); }
    private void heading(Vec3 v) { if (v.lengthSqr() > 1.0E-8) { v = v.normalize(); entityData.set(HEADING, new org.joml.Vector3f((float) v.x, (float) v.y, (float) v.z)); } }
    public int age() { return age; }

    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < DCEntityTypes.ATTACK_RENDER_DISTANCE_SQR; }
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }

    /**
     * The spear's body for a grip at {@code grip} heading along {@code direction}: long along it, square across, and
     * mostly ahead of the grip (the fist holds it a quarter of the way from the butt).
     */
    public static AttackBox body(ThrownAttacks.Charged spec, float charge, Vec3 grip, Vec3 direction) {
        double half = spec.mix(spec.halfLength(), charge), width = spec.mix(spec.halfWidth(), charge);
        double ahead = GRIP_TO_MIDDLE * spec.mix(spec.size(), charge) * spec.modelScale();
        return KineticGeometry.flightBox(new AttackBox(new Vec3(0, 0, ahead), new Vec3(width, 0, 0), new Vec3(0, width, 0), new Vec3(0, 0, half)), grip, direction);
    }
    /** Blocks from the grip to the middle of the spear at size 1 and model scale 1 (the mesh spans -13..33 px). */
    private static final double GRIP_TO_MIDDLE = 10 / 16.0;

    @Override public void tick() {
        super.tick();
        age++;
        var spec = spec();
        if (spec == null) { if (!level().isClientSide()) discard(); return; }
        if (level().isClientSide()) {
            if (phase() == Phase.FLYING) {
                setPos(position().add(getDeltaMovement()));
                setDeltaMovement(getDeltaMovement().add(0, -spec.mix(spec.gravity(), charge()), 0));
                if (age % 2 == 0) level().addParticle(ParticleTypes.SNOWFLAKE, getX(), getY(), getZ(), 0, 0, 0);
            }
            return;
        }
        var level = (ServerLevel) level();
        if (phase() == Phase.SHATTERED) { if (++stuck > 3) discard(); return; }
        if (phase() == Phase.STUCK) {
            if (++stuck >= Math.max(1, Math.round(spec.embedTicks() * charge()))) shatter(level, position(), false);
            return;
        }
        if (!(getOwner() instanceof DigimonEntity owner) || !owner.isAlive() || age > 160 || getY() < level.getMinY() - 8) { discard(); return; }
        float charge = charge();
        Vec3 from = position(), velocity = getDeltaMovement();
        int steps = Math.max(1, (int) Math.ceil(velocity.length() / .25));
        for (int i = 1; i <= steps; i++) {
            Vec3 at = from.add(velocity.scale((double) i / steps));
            var box = body(spec, charge, at, velocity);
            // The point meets the ground first: the spear stands in it (a light one breaks at once).
            if (KineticGeometry.blocked(level, this, box)) {
                setPos(from.add(velocity.scale((i - .6) / steps)));
                heading(velocity);
                if (charge < .3F) { shatter(level, position(), false); return; }
                entityData.set(PHASE, (byte) Phase.STUCK.ordinal());
                setDeltaMovement(Vec3.ZERO);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.GLASS_PLACE, SoundSource.NEUTRAL, 1.2F, .6F);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()), getX(), getY(), getZ(),
                        8 + (int) (charge * 16), .3, .15, .3, .1);
                Constants.LOG.info("[thrown] {} icicle stuck in the ground charge={} age={}", owner.getSpeciesId().getPath(), String.format("%.2f", charge), age);
                return;
            }
            for (var entity : level.getEntities(this, box.bounds())) {
                LivingEntity victim = DigimonPart.livingOf(entity);
                if (victim == null || victim == owner || !owner.canAttack(victim) || owner.isAllyOf(victim)
                        || HitParts.of(victim).stream().noneMatch(box::intersects)) continue;
                float scale = spec.mix(spec.power(), charge) / spec.attack().power() * ThrownAttacks.impulsePower(impulse);
                if (owner.hitWithAttack(level, spec.attack(), victim, at.subtract(velocity.normalize().scale(2)), scale)) {
                    double push = spec.mix(spec.knockback(), charge) * impulse;
                    if (push > 0) victim.knockback(push, -velocity.x, -velocity.z, owner.damageSources().mobAttack(owner), 0);
                    owner.countSkill(charge >= .75F ? "icicle_hit_heavy" : charge >= .3F ? "icicle_hit_mid" : "icicle_hit_light");
                    Constants.LOG.info("[thrown] {} icicle hit {} charge={} age={}", owner.getSpeciesId().getPath(),
                            victim.getType().toShortString(), String.format("%.2f", charge), age);
                    setPos(at);
                    shatter(level, at, true);
                    return;
                }
            }
        }
        setPos(from.add(velocity));
        heading(velocity);
        setDeltaMovement(velocity.add(0, -spec.mix(spec.gravity(), charge), 0).scale(.995));
    }

    private void shatter(ServerLevel level, Vec3 at, boolean struck) {
        float charge = charge();
        entityData.set(PHASE, (byte) Phase.SHATTERED.ordinal());
        stuck = 0;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()), at.x, at.y, at.z,
                10 + (int) (charge * 30), .25 + charge * .4, .25 + charge * .3, .25 + charge * .4, .15);
        level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 6 + (int) (charge * 14), .3, .3, .3, .08);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, .8F + charge * .6F, struck ? 1.1F - charge * .45F : 1.4F);
    }

    @Override protected void addAdditionalSaveData(ValueOutput output) { super.addAdditionalSaveData(output); }
    @Override protected void readAdditionalSaveData(ValueInput input) { super.readAdditionalSaveData(input); }
}
