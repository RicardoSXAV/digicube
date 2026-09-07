package com.digicube.entity;

import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A homing fish school inside a full-width water crest, followed by an inert splash. */
public final class MarchingFishesEntity extends ThrowableProjectile {
    public static final float SPEED = 0.34F;
    private static final int MAX_AGE = 40;
    private static final int SPLASH_TICKS = 12;
    // Covers even a corner contact of the 2.2 x 1.25 swept volume.
    private static final double IMPACT_RADIUS = 1.7;
    private static final EntityDataAccessor<Boolean> DATA_SPLASH =
            SynchedEntityData.defineId(MarchingFishesEntity.class, EntityDataSerializers.BOOLEAN);

    private LivingEntity target;
    private float damage = 2.75F;
    private double knockback = 1.35;
    private int splashTicks;
    private int flightTicks;

    public MarchingFishesEntity(EntityType<? extends MarchingFishesEntity> type, Level level) {
        super(type, level);
    }

    public MarchingFishesEntity(Level level, LivingEntity shooter, Vec3 origin, float damage,
                               double knockback, LivingEntity target) {
        super(DCEntityTypes.MARCHING_FISHES, origin.x, origin.y, origin.z, level);
        setPos(origin.x, origin.y - getBbHeight() * 0.5, origin.z);
        setOwner(shooter);
        this.damage = damage;
        this.knockback = knockback;
        this.target = target;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SPLASH, false);
    }

    public boolean isSplash() { return this.entityData.get(DATA_SPLASH); }
    public int getSplashTicks() { return splashTicks; }

    @Override
    protected double getDefaultGravity() { return 0.0; }

    @Override
    protected float getAirDrag() { return 1.0F; }

    /** Keep the bottom of the wave above a grounded target's feet, including small mobs. */
    public static Vec3 aimPoint(LivingEntity target, Vec3 origin) {
        Vec3 predicted = PepperBreathEntity.predictImpactPoint(target, origin, SPEED, 2.5);
        return new Vec3(predicted.x, Math.max(target.getY() + 0.69, predicted.y), predicted.z);
    }

    private boolean opponent(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive() && entity != getOwner()
                && (!(getOwner() instanceof DigimonEntity shooter)
                || !shooter.isAllyOf(entity) && shooter.canAttack(living));
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return !isSplash() && super.canHitEntity(entity) && opponent(entity);
    }

    @Override
    public void tick() {
        if (isSplash()) {
            setDeltaMovement(Vec3.ZERO);
            if (++splashTicks >= SPLASH_TICKS && !level().isClientSide()) discard();
            return;
        }
        if (level() instanceof ServerLevel server) {
            if (++flightTicks >= MAX_AGE || !(getOwner() instanceof LivingEntity owner) || !owner.isAlive()) {
                splash(server, false);
                return;
            }
            Vec3 center = getBoundingBox().getCenter();
            if (target != null && opponent(target)) {
                setDeltaMovement(MarchingFishesFlight.steer(getDeltaMovement(), aimPoint(target, center).subtract(center)));
            }
            if (sweep(server)) return;
            if (flightTicks % 3 == 0) {
                server.sendParticles(ParticleTypes.SPLASH, getX(), getY() + 0.4, getZ(),
                        4, 0.7, 0.2, 0.4, 0.035);
            }
        }
        // Vanilla applies water drag before moving. Restore momentum after each step so
        // an underwater school keeps travelling instead of exponentially slowing to a stop.
        Vec3 velocity = getDeltaMovement();
        super.tick();
        if (!isSplash()) setDeltaMovement(velocity);
    }

    /** Sweep against actual block shapes too: the sides of the crest cannot pass through walls. */
    private boolean sweep(ServerLevel level) {
        Vec3 from = getBoundingBox().getCenter(), to = from.add(getDeltaMovement());
        AABB search = getBoundingBox().expandTowards(getDeltaMovement());
        Vec3 closest = null;
        double nearest = Double.POSITIVE_INFINITY;
        boolean entityHit = false;
        for (var shape : level.getBlockCollisions(this, search)) for (AABB box : shape.toAabbs()) {
            Vec3 hit = MarchingFishesFlight.contact(from, to, box, getBbWidth() * 0.5, getBbHeight() * 0.5);
            if (hit != null && from.distanceToSqr(hit) < nearest) {
                closest = hit;
                nearest = from.distanceToSqr(hit);
            }
        }
        for (Entity candidate : level.getEntities(this, search, this::canHitEntity)) {
            Vec3 hit = MarchingFishesFlight.contact(from, to, candidate.getBoundingBox(),
                    getBbWidth() * 0.5, getBbHeight() * 0.5);
            if (hit == null || from.distanceToSqr(hit) >= nearest || !visible(from, candidate)) continue;
            closest = hit;
            nearest = from.distanceToSqr(hit);
            entityHit = true;
        }
        if (closest == null) return false;
        Vec3 center = closest.subtract(getDeltaMovement().normalize().scale(0.01));
        setPos(center.x, center.y - getBbHeight() * 0.5, center.z);
        splash(level, entityHit);
        return true;
    }

    private boolean visible(Vec3 origin, Entity target) {
        return level().clip(new ClipContext(origin, target.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (level() instanceof ServerLevel server) splash(server, hit.getType() == HitResult.Type.ENTITY);
    }

    /** Once-only damage to visible opponents; terrain and expiry only produce the visual splash. */
    public void splash(ServerLevel level, boolean damageOpponents) {
        if (isSplash()) return;
        Vec3 center = getBoundingBox().getCenter();
        Vec3 forward = getDeltaMovement().multiply(1, 0, 1).normalize();
        if (damageOpponents && getOwner() instanceof LivingEntity shooter) {
            for (Entity entity : level.getEntities(this, new AABB(center, center).inflate(IMPACT_RADIUS), this::opponent)) {
                LivingEntity victim = (LivingEntity) entity;
                AABB b = victim.getBoundingBox();
                Vec3 near = new Vec3(Mth.clamp(center.x, b.minX, b.maxX), Mth.clamp(center.y, b.minY, b.maxY),
                        Mth.clamp(center.z, b.minZ, b.maxZ));
                if (center.distanceToSqr(near) > IMPACT_RADIUS * IMPACT_RADIUS || !visible(center, victim)) continue;
                float power = damage;
                if (shooter instanceof DigimonEntity digimon && victim instanceof DigimonEntity other
                        && digimon.getSpecies().isPresent() && other.getSpecies().isPresent()) {
                    power *= digimon.getSpecies().get().attribute().damageMultiplierAgainst(other.getSpecies().get().attribute());
                }
                var source = damageSources().mobProjectile(this, shooter);
                if (victim.hurtServer(level, source, power)) {
                    Vec3 push = forward.lengthSqr() > 1.0E-6 ? forward : victim.position().subtract(shooter.position()).normalize();
                    victim.knockback(knockback, -push.x, -push.z, source, power);
                    shooter.setLastHurtMob(victim);
                }
            }
        }
        this.entityData.set(DATA_SPLASH, true);
        splashTicks = 0;
        setDeltaMovement(Vec3.ZERO);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 1.0F, 1.2F);
        level.sendParticles(ParticleTypes.SPLASH, center.x, center.y, center.z, 28, 0.9, 0.45, 0.7, 0.12);
        level.sendParticles(ParticleTypes.BUBBLE_POP, center.x, center.y, center.z, 12, 0.7, 0.4, 0.7, 0.04);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat("Damage", damage);
        output.putDouble("Knockback", knockback);
        output.putInt("FlightTicks", flightTicks);
        output.putInt("SplashTicks", splashTicks);
        output.putBoolean("Splash", isSplash());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        damage = input.getFloatOr("Damage", damage);
        knockback = input.getDoubleOr("Knockback", knockback);
        flightTicks = input.getIntOr("FlightTicks", MAX_AGE);
        splashTicks = input.getIntOr("SplashTicks", 0);
        this.entityData.set(DATA_SPLASH, input.getBooleanOr("Splash", false));
    }
}
