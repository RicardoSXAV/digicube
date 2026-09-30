package com.digicube.entity;

import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pepper Breath: a straight-flying fireball with no gravity and no drag.
 *
 * <p>Damage is decided by the Digimon that spat it (attack power times its attack
 * attribute) and carried here; the hit also sets the target on fire. Lives a few
 * seconds at most. Rendered client-side from Agumon's cubic flame effect (see the fabric
 * module); a hit stops the ball where it struck for the flare of its impact clip, with no
 * further collision, and only then removes it. The particles here are the few loose
 * sparks and smoke the model cannot carry.
 *
 * <p>Accuracy. The ball flies dead straight, so a target that changes its mind in time is
 * missed. Landing it on a moving body is the shooter's skill: it reads how the target moves
 * ({@link TargetMotion}), turns its body onto the meeting point during the wind-up and holds
 * fire while that reading cannot be trusted over the flight (see {@code DigimonEntity}). The
 * ball hits with its whole body rather than the thin ray vanilla sweeps (which has a margin
 * of 0 for the first two ticks and at most 0.3 blocks after).
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
    /** Extra reach around the ball's own box when sweeping for hits, blocks. */
    public static final double HIT_MARGIN = 0.2;
    private static final int MAX_AGE_TICKS = 60;
    private static final float BURN_SECONDS = 3.0F;
    /** How long a body the ball set alight burns, and is Burned. */
    public static final int BURN_TICKS = Math.round(BURN_SECONDS * 20);
    private static final String DAMAGE_TAG = "Damage";
    /** The rendered tail reaches about 0.7 blocks behind the hitbox centre. */
    private static final double TAIL_LENGTH = 0.7;
    /** Ticks the flare lasts after a hit: the length of the effect's {@code fireball_impact} clip. */
    public static final int IMPACT_TICKS = 7;
    /** Ticks since the ball struck something, or -1 while it flies. */
    private static final EntityDataAccessor<Integer> IMPACT = SynchedEntityData.defineId(PepperBreathEntity.class, EntityDataSerializers.INT);

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

    /** Attack visuals are never culled by hitbox size (vanilla hides a .1-block entity past 6 blocks); tracking range decides. */
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < com.digicube.registry.DCEntityTypes.ATTACK_RENDER_DISTANCE_SQR; }
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(IMPACT, -1);
    }

    /** True from the hit until the flare is over; the ball no longer moves or collides. */
    public boolean impacting() { return entityData.get(IMPACT) >= 0; }

    /** Ticks into the impact flare, interpolated for drawing. */
    public float impactTick(float partialTick) { return entityData.get(IMPACT) + partialTick; }

    /** A flare is only a picture: a world saved during one does not bring the ball back. */
    @Override
    public boolean shouldBeSaved() { return !impacting() && super.shouldBeSaved(); }

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
        if (impacting()) {
            setDeltaMovement(Vec3.ZERO);
            if (!level().isClientSide()) {
                int age = entityData.get(IMPACT) + 1;
                if (age >= IMPACT_TICKS) discard();
                else entityData.set(IMPACT, age);
            }
            return;
        }
        if (level() instanceof ServerLevel && sweepForHit()) {
            return;
        }
        super.tick();
        if (level().isClientSide()) {
            spawnTrail();
        } else if (tickCount > MAX_AGE_TICKS) {
            discard();
        }
    }

    /**
     * Hits whatever the ball's own body would pass through this tick. Vanilla's move-vector
     * ray (run afterwards by {@code super.tick()}) is left in place for blocks.
     *
     * @return true if the fireball hit something and is spent
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
        return impacting() || isRemoved();
    }

    /**
     * What the model's own sparks leave behind: one small flame peeling off the ball each
     * tick and a wisp of smoke behind the tail.
     */
    private void spawnTrail() {
        Vec3 centre = position().add(0.0, getBbHeight() * 0.5, 0.0);
        Vec3 velocity = getDeltaMovement();
        Vec3 back = velocity.lengthSqr() > 1.0E-6 ? velocity.normalize().scale(-1.0) : Vec3.ZERO;
        Vec3 offset = new Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5).scale(0.5);
        Vec3 drift = offset.scale(0.03).add(back.scale(0.04));
        level().addAlwaysVisibleParticle(ParticleTypes.SMALL_FLAME, centre.x + offset.x, centre.y + offset.y, centre.z + offset.z,
                drift.x, drift.y + 0.01, drift.z);
        if (tickCount % 3 == 0) {
            Vec3 end = centre.add(back.scale(TAIL_LENGTH));
            level().addAlwaysVisibleParticle(ParticleTypes.SMOKE,
                    end.x + (random.nextDouble() - 0.5) * 0.3, end.y + (random.nextDouble() - 0.5) * 0.3,
                    end.z + (random.nextDouble() - 0.5) * 0.3, 0.0, 0.03, 0.0);
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
            // A struck hit part sets its whole body alight; the fire it lights is a Burn in the fight's terms.
            Entity body = DigimonPart.livingOf(hitEntity) != null ? DigimonPart.livingOf(hitEntity) : hitEntity;
            body.igniteForSeconds(BURN_SECONDS);
            if (body instanceof CombatMarkState marked) marked.digicube$burn(BURN_TICKS);
            if (shooter != null) {
                shooter.setLastHurtMob(hitEntity);
            }
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        if (impacting()) return;
        super.onHit(hit);
        if (level() instanceof ServerLevel serverLevel) {
            Vec3 pos = position();
            double centreY = pos.y + getBbHeight() * 0.5;
            // The flare is the model's impact clip; these are the embers thrown out past it, then smoke.
            serverLevel.sendParticles(ParticleTypes.FLAME, true, true, pos.x, centreY, pos.z, 8, 0.25, 0.25, 0.25, 0.08);
            serverLevel.sendParticles(ParticleTypes.SMALL_FLAME, true, true, pos.x, centreY, pos.z, 6, 0.2, 0.2, 0.2, 0.12);
            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, pos.x, centreY, pos.z, 4, 0.3, 0.3, 0.3, 0.02);
            serverLevel.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 0.7F, 1.3F);
            serverLevel.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.NEUTRAL, 0.35F, 1.6F);
            // Stay where it struck for the flare, facing the way it flew; nothing moves or hits from here on.
            entityData.set(IMPACT, 0);
            setDeltaMovement(Vec3.ZERO);
            needsSync = true;
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
