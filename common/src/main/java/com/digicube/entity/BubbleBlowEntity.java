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
 * One damaging Bubble Blow volley, rendered as a stream of seven small bubbles.
 * Leads moving targets, steers gently, and sweeps its volume with block occlusion.
 * A hit deals damage once, without fire, then syncs a six-tick visual pop to clients.
 */
public final class BubbleBlowEntity extends ThrowableProjectile {

    /** Slow enough to see individual bubbles: 6.4 blocks per second. */
    public static final float SPEED = 0.32F;
    /** Maximum predicted target displacement, in blocks. */
    public static final double MAX_AIM_LEAD = 4.0;
    private static final int MAX_AGE_TICKS = 50;
    private static final int POP_TICKS = 6;
    private static final double MAX_TURN = Math.toRadians(4.0);
    private static final double HOMING_CONE_COS = Math.cos(Math.toRadians(70.0));
    private static final EntityDataAccessor<Boolean> DATA_POPPED =
            SynchedEntityData.defineId(BubbleBlowEntity.class, EntityDataSerializers.BOOLEAN);

    private float damage = 2.0F;
    private LivingEntity target;
    private int popTicks;

    /**
     * Creates an unlaunched volley for loading or spawning.
     * @param type registered bubble entity type
     * @param level owning level
     */
    public BubbleBlowEntity(EntityType<? extends BubbleBlowEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Creates a volley whose hitbox centre starts at Koromon's mouth.
     * @param level owning level
     * @param shooter attacking Digimon
     * @param mouth world-space centre of the rendered blowing mouth
     * @param damage total volley damage, independent of the number of rendered bubbles
     * @param target intended target, or null for straight flight
     */
    public BubbleBlowEntity(Level level, LivingEntity shooter, Vec3 mouth, float damage, LivingEntity target) {
        super(DCEntityTypes.BUBBLE_BLOW, mouth.x, mouth.y, mouth.z, level);
        setPos(mouth.x, mouth.y - getBbHeight() * 0.5, mouth.z);
        setOwner(shooter);
        this.damage = damage;
        this.target = target;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_POPPED, false);
    }

    /**
     * Reports whether flight and damage have ended.
     * @return whether the volley is showing its harmless pop animation
     */
    public boolean isPopped() {
        return this.entityData.get(DATA_POPPED);
    }

    /**
     * Supplies the client animation clock.
     * @return ticks elapsed since the pop became visible on this logical side
     */
    public int getPopTicks() {
        return popTicks;
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
        return !isPopped() && super.canHitEntity(entity)
                && (!(getOwner() instanceof DigimonEntity shooter) || !shooter.isAllyOf(entity));
    }

    @Override
    public void tick() {
        if (isPopped()) {
            setDeltaMovement(Vec3.ZERO);
            if (++popTicks >= POP_TICKS && !level().isClientSide()) {
                discard();
            }
            return;
        }
        if (level() instanceof ServerLevel serverLevel) {
            if (tickCount >= MAX_AGE_TICKS) {
                pop(serverLevel);
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

    /** Sweep the bubble volume, selecting the nearest entity before the first wall. */
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
        return isPopped() || isRemoved();
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (isPopped() || !(level() instanceof ServerLevel serverLevel)) return;
        LivingEntity shooter = getOwner() instanceof LivingEntity living ? living : null;
        if (hit.getEntity().hurtServer(serverLevel, damageSources().mobProjectile(this, shooter), damage)
                && shooter != null) {
            shooter.setLastHurtMob(hit.getEntity());
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        if (isPopped()) return;
        super.onHit(hit);
        if (level() instanceof ServerLevel serverLevel) pop(serverLevel);
    }

    private void pop(ServerLevel level) {
        this.entityData.set(DATA_POPPED, true);
        setDeltaMovement(Vec3.ZERO);
        popTicks = 0;
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.BUBBLE_POP,
                SoundSource.NEUTRAL, 0.65F, 1.15F);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat("Damage", damage);
        output.putBoolean("Popped", isPopped());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        damage = input.getFloatOr("Damage", damage);
        this.entityData.set(DATA_POPPED, input.getBooleanOr("Popped", false));
    }
}
