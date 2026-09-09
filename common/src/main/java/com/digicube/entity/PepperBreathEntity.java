package com.digicube.entity;

import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
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
 *
 * <p>Accuracy. A slow, big projectile needs three things to land on a moving mob, and
 * vanilla gives none of them: the shooter leads the target ({@link #predictImpactPoint}),
 * the ball hits with its whole body rather than the thin ray vanilla sweeps (which has a
 * margin of 0 for the first two ticks and at most 0.3 blocks after), and it bends gently
 * toward its target in flight so a mob that turns mid-flight is still met. The bend is
 * rate-limited and only works while the target stays ahead, so a sharp dodge still wins.
 */
public final class PepperBreathEntity extends ThrowableProjectile {

    /**
     * Blocks per tick: 10 blocks/s, about a third of a ghast fireball at full speed. Slow
     * enough to watch the ball roll and flicker; the shooter leads moving targets to
     * compensate.
     */
    public static final float SPEED = 0.5F;
    /** Blocks. Longer leads assume the target keeps its heading longer than mobs usually do. */
    public static final double MAX_AIM_LEAD = 6.0;
    /** Degrees per tick the ball may bend toward its target: 80 degrees over a second of flight. */
    private static final float MAX_TURN_DEGREES = 4.0F;
    /** Homing gives up once the target is more than this far off the nose (no boomerangs). */
    private static final double HOMING_CONE_COS = Math.cos(Math.toRadians(70.0));
    /** Extra reach around the ball's own box when sweeping for hits, blocks. */
    private static final double HIT_MARGIN = 0.2;
    private static final int MAX_AGE_TICKS = 60;
    private static final float BURN_SECONDS = 3.0F;
    private static final String DAMAGE_TAG = "Damage";
    /** The rendered tail is about 1.5 blocks long behind the hitbox centre. */
    private static final double TAIL_LENGTH = 1.5;

    private float damage = 6.0F;
    /** What the shooter was aiming at; server-side only and not saved (a reloaded fireball just flies straight). */
    private LivingEntity target;

    public PepperBreathEntity(EntityType<? extends PepperBreathEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Spawns at {@code from}, owned by {@code shooter} and homing on {@code target} (may be
     * null); call {@link #shoot} before adding it.
     */
    public PepperBreathEntity(Level level, LivingEntity shooter, Vec3 from, float damage, LivingEntity target) {
        super(DCEntityTypes.PEPPER_BREATH, from.x, from.y, from.z, level);
        setOwner(shooter);
        this.damage = damage;
        this.target = target;
    }

    /**
     * Where to aim a projectile of the given speed so it meets a moving target: the centre
     * of the target's hitbox, led by its current horizontal velocity for the flight time
     * (refined because the lead changes the distance). The lead is capped so a mob that
     * turns around does not get a fireball thrown at empty ground.
     */
    public static Vec3 predictImpactPoint(LivingEntity target, Vec3 from, double blocksPerTick, double maxLead) {
        Vec3 centre = AttackGeometry.chest(target.getBoundingBox());
        Vec3 velocity = target.position().subtract(target.oldPosition());
        velocity = new Vec3(velocity.x, 0.0, velocity.z);
        if (velocity.lengthSqr() < 1.0E-4) {
            return centre;
        }
        double flightTicks = centre.subtract(from).length() / blocksPerTick;
        for (int refine = 0; refine < 2; refine++) {
            Vec3 predicted = centre.add(velocity.scale(flightTicks));
            flightTicks = predicted.subtract(from).length() / blocksPerTick;
        }
        Vec3 lead = velocity.scale(flightTicks);
        if (lead.length() > maxLead) {
            lead = lead.normalize().scale(maxLead);
        }
        return centre.add(lead);
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

    /** Never burn the tamer or stable-mates standing in the way. */
    @Override
    protected boolean canHitEntity(Entity entity) {
        if (!super.canHitEntity(entity)) {
            return false;
        }
        return !(getOwner() instanceof DigimonEntity shooter) || !shooter.isAllyOf(entity);
    }

    @Override
    public void tick() {
        if (level() instanceof ServerLevel) {
            steerTowardsTarget();
            if (sweepForHit()) {
                return;
            }
        }
        super.tick();
        if (level().isClientSide()) {
            spawnTrail();
        } else if (tickCount > MAX_AGE_TICKS) {
            discard();
        }
    }

    /** Bends the flight path toward the predicted meeting point, a few degrees per tick at most. */
    private void steerTowardsTarget() {
        if (target == null || !target.isAlive()) {
            return;
        }
        Vec3 velocity = getDeltaMovement();
        double speed = velocity.length();
        if (speed < 1.0E-4) {
            return;
        }
        Vec3 centre = getBoundingBox().getCenter();
        Vec3 desired = predictImpactPoint(target, centre, speed, MAX_AIM_LEAD).subtract(centre);
        if (desired.lengthSqr() < 1.0E-4) {
            return;
        }
        desired = desired.normalize();
        Vec3 current = velocity.scale(1.0 / speed);
        double cos = current.dot(desired);
        if (cos < HOMING_CONE_COS) {
            return;
        }
        double angle = Math.acos(Mth.clamp(cos, -1.0, 1.0));
        if (angle < 1.0E-3) {
            return;
        }
        // Blend toward the wanted heading by the fraction that turns at most MAX_TURN_DEGREES.
        double blend = Math.min(1.0, Math.toRadians(MAX_TURN_DEGREES) / angle);
        Vec3 heading = current.scale(1.0 - blend).add(desired.scale(blend)).normalize();
        setDeltaMovement(heading.scale(speed));
    }

    /**
     * Hits whatever the ball's own body would pass through this tick. Vanilla's move-vector
     * ray (run afterwards by {@code super.tick()}) is left in place for blocks.
     *
     * @return true if the fireball hit something and is gone
     */
    private boolean sweepForHit() {
        AABB swept = getBoundingBox().expandTowards(getDeltaMovement()).inflate(HIT_MARGIN);
        Vec3 centre = getBoundingBox().getCenter();
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity candidate : level().getEntities(this, swept, this::canHitEntity)) {
            double distance = candidate.getBoundingBox().distanceToSqr(centre);
            if (distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        if (closest == null) {
            return false;
        }
        hitTargetOrDeflectSelf(new EntityHitResult(closest, centre));
        return isRemoved();
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
        Entity hitEntity = hit.getEntity();
        Entity owner = getOwner();
        LivingEntity shooter = owner instanceof LivingEntity living ? living : null;
        DamageSource source = damageSources().mobProjectile(this, shooter);
        if (hitEntity.hurtServer(serverLevel, source, damage)) {
            hitEntity.igniteForSeconds(BURN_SECONDS);
            if (shooter != null) {
                shooter.setLastHurtMob(hitEntity);
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
