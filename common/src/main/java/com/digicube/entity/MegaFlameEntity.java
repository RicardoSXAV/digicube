package com.digicube.entity;

import com.digicube.registry.DCEntityTypes;
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
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A large flame shot with a Blender-authored flight and ten-tick impact burst.
 * Its full volume collides, it leads targets, and each impact deals damage once.
 * Fire affects combatants; the shot never places fire blocks or destroys terrain.
 */
public final class MegaFlameEntity extends ThrowableProjectile {

    /** Visible flight at eleven blocks per second. */
    public static final float SPEED = 0.55F;
    /** Maximum predicted target displacement, in blocks. */
    public static final double MAX_AIM_LEAD = 4.0;
    private static final int MAX_AGE_TICKS = 45;
    private static final int BURST_TICKS = 10;
    private static final double BLAST_RADIUS = 1.8;
    private static final double MAX_TURN = Math.toRadians(2.5);
    private static final double HOMING_CONE_COS = Math.cos(Math.toRadians(70.0));
    private static final EntityDataAccessor<Boolean> DATA_BURST =
            SynchedEntityData.defineId(MegaFlameEntity.class, EntityDataSerializers.BOOLEAN);

    private float damage = 2.0F;
    private LivingEntity target;
    private int burstTicks;

    /**
     * Creates an unlaunched flame for loading or spawning.
     * @param type registered flame entity type
     * @param level owning level
     */
    public MegaFlameEntity(EntityType<? extends MegaFlameEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Creates a flame whose hitbox centre starts at the authored mouth marker.
     * @param level owning level
     * @param shooter attacking Digimon
     * @param mouth world-space centre of the rendered mouth
     * @param damage base direct-hit damage before attribute matchups
     * @param target intended target, or null for straight flight
     */
    public MegaFlameEntity(Level level, LivingEntity shooter, Vec3 mouth, float damage, LivingEntity target) {
        super(DCEntityTypes.MEGA_FLAME, mouth.x, mouth.y, mouth.z, level);
        setPos(mouth.x, mouth.y - getBbHeight() * 0.5, mouth.z);
        setOwner(shooter);
        this.damage = damage;
        this.target = target;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_BURST, false);
    }

    /**
     * Reports whether flight and damage have ended.
     * @return whether flight has ended and the impact animation is visible
     */
    public boolean isBurst() {
        return this.entityData.get(DATA_BURST);
    }

    /**
     * Supplies the client animation clock.
     * @return ticks elapsed since the burst became visible on this logical side
     */
    public int getBurstTicks() {
        return burstTicks;
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
    protected boolean canHitEntity(Entity entity) {
        return !isBurst() && super.canHitEntity(entity)
                && (!(getOwner() instanceof DigimonEntity shooter) || !shooter.isAllyOf(entity));
    }

    @Override
    public void tick() {
        if (isBurst()) {
            setDeltaMovement(Vec3.ZERO);
            if (++burstTicks >= BURST_TICKS && !level().isClientSide()) {
                discard();
            }
            return;
        }
        if (level() instanceof ServerLevel serverLevel) {
            if (tickCount >= MAX_AGE_TICKS) {
                discard();
                return;
            }
            steerTowardsTarget();
            if (sweepForHit()) {
                return;
            }
        }
        super.tick();
    }

    private void steerTowardsTarget() {
        if (target == null || !target.isAlive()) return;
        Vec3 velocity = getDeltaMovement();
        double speed = velocity.length();
        if (speed < 1.0E-4) return;
        Vec3 centre = getBoundingBox().getCenter();
        Vec3 desired = PepperBreathEntity.predictImpactPoint(target, centre, speed, MAX_AIM_LEAD)
                .subtract(centre).normalize();
        Vec3 heading = velocity.scale(1.0 / speed);
        double cos = heading.dot(desired);
        if (cos < HOMING_CONE_COS) return;
        double angle = Math.acos(Mth.clamp(cos, -1.0, 1.0));
        if (angle < 1.0E-3) return;
        double blend = Math.min(1.0, MAX_TURN / angle);
        setDeltaMovement(heading.scale(1.0 - blend).add(desired.scale(blend)).normalize().scale(speed));
    }

    /** Sweep the flame volume, selecting the nearest entity before the first wall. */
    private boolean sweepForHit() {
        Vec3 from = getBoundingBox().getCenter();
        Vec3 to = from.add(getDeltaMovement());
        HitResult block = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        HitResult closest = block;
        double nearest = from.distanceToSqr(end);
        AABB search = getBoundingBox().expandTowards(getDeltaMovement());
        for (Entity candidate : level().getEntities(this, search, this::canHitEntity)) {
            AABB bounds = candidate.getBoundingBox();
            AABB expanded = bounds.inflate(getBbWidth() * 0.5, getBbHeight() * 0.5, getBbWidth() * 0.5);
            Vec3 contact = expanded.contains(from) ? from : expanded.clip(from, end).orElse(null);
            if (contact == null || from.distanceToSqr(contact) > nearest) continue;
            // Expansion must not make a target on the other side of a wall hittable.
            if (level().clip(new ClipContext(from, bounds.getCenter(), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) continue;
            closest = new EntityHitResult(candidate, contact);
            nearest = from.distanceToSqr(contact);
        }
        if (closest.getType() == HitResult.Type.MISS) return false;
        Vec3 contact = closest.getLocation();
        setPos(contact.x, contact.y - getBbHeight() * 0.5, contact.z);
        hitTargetOrDeflectSelf(closest);
        return isBurst() || isRemoved();
    }

    @Override
    protected void onHit(HitResult hit) {
        if (isBurst()) return;
        super.onHit(hit);
        if (level() instanceof ServerLevel serverLevel) burst(serverLevel);
    }

    /**
     * End flight, damage visible nearby opponents once, and sync the authored burst.
     * @param level authoritative level where the hit occurred
     */
    public void burst(ServerLevel level) {
        if (isBurst()) return;
        Vec3 velocity = getDeltaMovement();
        Vec3 center = getBoundingBox().getCenter().subtract(velocity.normalize().scale(0.04));
        LivingEntity shooter = getOwner() instanceof LivingEntity living ? living : null;
        for (Entity entity : level.getEntities(this, new AABB(center, center).inflate(BLAST_RADIUS),
                e -> e instanceof LivingEntity living && living.isAlive() && e != shooter
                        && (!(shooter instanceof DigimonEntity digimon) || !digimon.isAllyOf(e)))) {
            AABB bounds = entity.getBoundingBox();
            Vec3 near = new Vec3(Mth.clamp(center.x, bounds.minX, bounds.maxX),
                    Mth.clamp(center.y, bounds.minY, bounds.maxY), Mth.clamp(center.z, bounds.minZ, bounds.maxZ));
            double distance = center.distanceTo(near);
            if (distance > BLAST_RADIUS || level.clip(new ClipContext(center, bounds.getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) continue;
            float power = damage * (float) (1.0 - 0.45 * distance / BLAST_RADIUS);
            if (shooter instanceof DigimonEntity digimon && entity instanceof DigimonEntity victim
                    && digimon.getSpecies().isPresent() && victim.getSpecies().isPresent()) {
                power *= digimon.getSpecies().get().attribute().damageMultiplierAgainst(victim.getSpecies().get().attribute());
            }
            if (entity.hurtServer(level, damageSources().mobProjectile(this, shooter), power)) {
                entity.setRemainingFireTicks(Math.max(entity.getRemainingFireTicks(), 120));
                if (shooter != null) shooter.setLastHurtMob(entity);
            }
        }
        this.entityData.set(DATA_BURST, true);
        setDeltaMovement(Vec3.ZERO);
        burstTicks = 0;
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.DRAGON_FIREBALL_EXPLODE,
                SoundSource.NEUTRAL, 1.1F, 0.75F);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat("Damage", damage);
        output.putBoolean("Burst", isBurst());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        damage = input.getFloatOr("Damage", damage);
        this.entityData.set(DATA_BURST, input.getBooleanOr("Burst", false));
    }
}
