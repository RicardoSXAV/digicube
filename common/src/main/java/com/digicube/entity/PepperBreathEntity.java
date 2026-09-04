package com.digicube.entity;

import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pepper Breath: a straight-flying fireball with no gravity and no drag.
 *
 * <p>Damage is decided by the Digimon that spat it (attack power times its attack
 * attribute) and carried here; the hit also sets the target on fire. Lives a few
 * seconds at most. Rendered client-side as flat pixel planes (see the fabric module).
 */
public final class PepperBreathEntity extends ThrowableProjectile {

    /** Blocks per tick. Fast enough to be hard to sidestep at close range, slow enough to see. */
    public static final float SPEED = 0.9F;
    private static final int MAX_AGE_TICKS = 60;
    private static final float BURN_SECONDS = 3.0F;
    private static final String DAMAGE_TAG = "Damage";

    private float damage = 6.0F;

    public PepperBreathEntity(EntityType<? extends PepperBreathEntity> type, Level level) {
        super(type, level);
    }

    /** Spawns at {@code from}, owned by {@code shooter}; call {@link #shoot} before adding it. */
    public PepperBreathEntity(Level level, LivingEntity shooter, Vec3 from, float damage) {
        super(DCEntityTypes.PEPPER_BREATH, from.x, from.y, from.z, level);
        setOwner(shooter);
        this.damage = damage;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // Nothing to sync: the client only needs position and rotation.
    }

    @Override
    protected double getDefaultGravity() {
        return 0.0;
    }

    @Override
    protected float getAirDrag() {
        return 1.0F;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            // Ember trail. The fireball itself is a model; particles sell the motion.
            Vec3 pos = position();
            level().addParticle(ParticleTypes.FLAME,
                    pos.x + (random.nextDouble() - 0.5) * 0.3, pos.y + 0.25 + (random.nextDouble() - 0.5) * 0.3,
                    pos.z + (random.nextDouble() - 0.5) * 0.3, 0.0, 0.0, 0.0);
            if (tickCount % 3 == 0) {
                level().addParticle(ParticleTypes.SMOKE, pos.x, pos.y + 0.25, pos.z, 0.0, 0.02, 0.0);
            }
        } else if (tickCount > MAX_AGE_TICKS) {
            discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity target = hit.getEntity();
        Entity owner = getOwner();
        LivingEntity shooter = owner instanceof LivingEntity living ? living : null;
        DamageSource source = damageSources().mobProjectile(this, shooter);
        if (target.hurtServer(serverLevel, source, damage)) {
            target.igniteForSeconds(BURN_SECONDS);
            if (shooter != null) {
                shooter.setLastHurtMob(target);
            }
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (level() instanceof ServerLevel serverLevel) {
            Vec3 pos = position();
            serverLevel.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 0.25, pos.z, 14, 0.2, 0.2, 0.2, 0.06);
            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.25, pos.z, 4, 0.15, 0.15, 0.15, 0.02);
            serverLevel.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 0.7F, 1.3F);
            discard();
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat(DAMAGE_TAG, damage);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        damage = input.getFloatOr(DAMAGE_TAG, damage);
    }
}
