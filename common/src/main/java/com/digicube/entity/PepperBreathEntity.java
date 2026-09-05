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
 * seconds at most. Rendered client-side as flat pixel planes (see the fabric module);
 * the particles here are the loose sparks and smoke the model cannot carry.
 */
public final class PepperBreathEntity extends ThrowableProjectile {

    /**
     * Blocks per tick: 10 blocks/s, about a third of a ghast fireball at full speed. Slow
     * enough to watch the ball roll and flicker; the shooter leads moving targets to
     * compensate (see {@code DigimonEntity#predictImpactPoint}).
     */
    public static final float SPEED = 0.5F;
    private static final int MAX_AGE_TICKS = 60;
    private static final float BURN_SECONDS = 3.0F;
    private static final String DAMAGE_TAG = "Damage";
    /** The rendered tail is about 1.5 blocks long behind the hitbox centre. */
    private static final double TAIL_LENGTH = 1.5;

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
            spawnTrail();
        } else if (tickCount > MAX_AGE_TICKS) {
            discard();
        }
    }

    /**
     * Sparks and smoke shed along the flight: small flames peeling off the ball, embers
     * drifting back along the tail, a wisp of smoke behind the tip of it.
     */
    private void spawnTrail() {
        Vec3 centre = position().add(0.0, getBbHeight() * 0.5, 0.0);
        Vec3 velocity = getDeltaMovement();
        Vec3 back = velocity.lengthSqr() > 1.0E-6 ? velocity.normalize().scale(-1.0) : Vec3.ZERO;
        // Sparks leaving the ball, thrown slightly outward and backward.
        for (int i = 0; i < 2; i++) {
            Vec3 offset = new Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5).scale(0.8);
            Vec3 drift = offset.scale(0.03).add(back.scale(0.04));
            level().addParticle(ParticleTypes.SMALL_FLAME, centre.x + offset.x, centre.y + offset.y, centre.z + offset.z,
                    drift.x, drift.y + 0.01, drift.z);
        }
        // Embers along the tail, falling away as they cool.
        Vec3 tail = centre.add(back.scale(random.nextDouble() * TAIL_LENGTH));
        level().addParticle(ParticleTypes.FLAME,
                tail.x + (random.nextDouble() - 0.5) * 0.4, tail.y + (random.nextDouble() - 0.5) * 0.4,
                tail.z + (random.nextDouble() - 0.5) * 0.4, back.x * 0.02, -0.01, back.z * 0.02);
        if (tickCount % 3 == 0) {
            Vec3 end = centre.add(back.scale(TAIL_LENGTH));
            level().addParticle(ParticleTypes.SMOKE,
                    end.x + (random.nextDouble() - 0.5) * 0.3, end.y + (random.nextDouble() - 0.5) * 0.3,
                    end.z + (random.nextDouble() - 0.5) * 0.3, 0.0, 0.03, 0.0);
        }
        if (tickCount % 5 == 0) {
            level().addParticle(ParticleTypes.LAVA, centre.x, centre.y, centre.z, 0.0, 0.0, 0.0);
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
            double centreY = pos.y + getBbHeight() * 0.5;
            // Burst: a flash of flame, embers thrown out, then smoke.
            serverLevel.sendParticles(ParticleTypes.FLAME, pos.x, centreY, pos.z, 32, 0.3, 0.3, 0.3, 0.1);
            serverLevel.sendParticles(ParticleTypes.SMALL_FLAME, pos.x, centreY, pos.z, 24, 0.2, 0.2, 0.2, 0.15);
            serverLevel.sendParticles(ParticleTypes.LAVA, pos.x, centreY, pos.z, 6, 0.2, 0.2, 0.2, 0.0);
            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, centreY, pos.z, 8, 0.3, 0.3, 0.3, 0.02);
            serverLevel.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 0.7F, 1.3F);
            serverLevel.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.NEUTRAL, 0.35F, 1.6F);
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
