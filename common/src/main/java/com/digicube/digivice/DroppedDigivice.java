package com.digicube.digivice;

import com.digicube.registry.DCEntityTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** A persistent, non-ItemEntity device. Hoppers, allays, merging and vanilla item despawn cannot consume it. */
public final class DroppedDigivice extends Entity {
    private static final EntityDataAccessor<ItemStack> STACK = SynchedEntityData.defineId(DroppedDigivice.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Float> PITCH = SynchedEntityData.defineId(DroppedDigivice.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Long> BEACON_AT = SynchedEntityData.defineId(DroppedDigivice.class, EntityDataSerializers.LONG);
    public static final int BEACON_DELAY = 8, BEACON_FADE = 8;
    private final InterpolationHandler interpolation = new InterpolationHandler(this, 3);
    private int pickupDelay = 30;
    private float oldPitch = 15, visualPitch = 15;
    public DroppedDigivice(EntityType<? extends DroppedDigivice> type, Level level) { super(type, level); }
    public DroppedDigivice(Level level, ItemStack stack, Vec3 position, Vec3 velocity) {
        this(DCEntityTypes.DROPPED_DIGIVICE, level);
        entityData.set(STACK, stack.copyWithCount(1));
        setPos(position); setDeltaMovement(velocity);
        setYRot((float) Math.toDegrees(Math.atan2(velocity.x, velocity.z)));
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) { b.define(STACK, ItemStack.EMPTY); b.define(PITCH, 15F); b.define(BEACON_AT, -1L); }
    public ItemStack stack() { return entityData.get(STACK); }
    public long beaconAt() { return entityData.get(BEACON_AT); }
    public static float beaconStrength(long start, double time) {
        if (start < 0) return 0;
        float t = (float) Math.clamp((time - start) / BEACON_FADE, 0, 1);
        return t * t * (3 - 2 * t);
    }
    public float pitch(float partial) { return oldPitch + (visualPitch - oldPitch) * partial; }
    @Override public InterpolationHandler getInterpolation() { return interpolation; }
    @Override public boolean fireImmune() { return true; }
    @Override public boolean displayFireAnimation() { return false; }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
    @Override public boolean isAttackable() { return false; }
    @Override public void kill(ServerLevel level) {} // ordinary /kill and environmental removal must not lose the device
    @Override public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) { return true; }
    @Override public boolean canUsePortal(boolean passenger) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 160 * 160; }
    @Override public void onInsideBubbleColumn(boolean down) {} // solid device sinks; bubbles never eject it
    @Override public void onAboveBubbleColumn(boolean down, net.minecraft.core.BlockPos pos) {}
    @Override protected void onBelowWorld() { hoverAtFloor(); }
    private void hoverAtFloor() {
        setPos(getX(), level().getMinY() + 1, getZ());
        setDeltaMovement(Vec3.ZERO);
    }
    @Override public void tick() {
        oldPitch = visualPitch;
        super.tick();
        if (level().isClientSide()) {
            interpolation.interpolate();
            visualPitch = entityData.get(PITCH);
            if (tickCount <= 1) oldPitch = visualPitch;
            return;
        }
        if (stack().isEmpty()) { discard(); return; }
        // Revoked devices in unloaded chunks must never reappear after an operator replacement.
        var owner = Digivices.owner(stack());
        if (owner != null) {
            var current = DigiviceSavedData.get(((ServerLevel) level()).getServer()).device(owner);
            if (current == null || !current.token().equals(Digivices.token(stack()))) { discard(); return; }
        }
        if (pickupDelay > 0) pickupDelay--;
        boolean liquid = isInWater() || isInLava();
        Vec3 velocity = getDeltaMovement().add(0, liquid ? -.018 : -.04, 0);
        move(MoverType.SELF, velocity);
        applyEffectsFromBlocks();
        if (getY() < level().getMinY() + 1) hoverAtFloor();
        else setDeltaMovement(velocity.multiply(onGround() ? .55 : liquid ? .8 : .98, liquid ? .8 : .98, onGround() ? .55 : liquid ? .8 : .98));
        if (onGround()) setDeltaMovement(getDeltaMovement().multiply(1, 0, 1));
        float target = onGround() || getY() <= level().getMinY() + 1 ? -90 : -65;
        float pitch = entityData.get(PITCH);
        entityData.set(PITCH, Math.abs(target - pitch) < .05F ? target : pitch + (target - pitch) * (onGround() ? .32F : .075F));
        visualPitch = entityData.get(PITCH);
        boolean settled = target == -90 && Math.abs(visualPitch + 90) < .25F && getDeltaMovement().lengthSqr() < .0001;
        if (!settled) entityData.set(BEACON_AT, -1L);
        else if (beaconAt() < 0) entityData.set(BEACON_AT, level().getGameTime() + BEACON_DELAY);
        clearFire();
        Digivices.remember(this, (ServerLevel) level());
        for (Player player : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(.45, .25, .45))) playerTouch(player);
    }
    @Override public void playerTouch(Player player) {
        if (pickupDelay == 0 && player instanceof ServerPlayer serverPlayer && Digivices.pickup(this, serverPlayer)) {
            level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.ITEM_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS, .2F, 1.1F);
            discard();
        }
    }
    @Override protected void addAdditionalSaveData(ValueOutput out) {
        out.store("device", ItemStack.CODEC, stack()); out.putInt("pickup_delay", pickupDelay); out.putFloat("pitch", entityData.get(PITCH));
        out.putLong("beacon_at", beaconAt());
    }
    @Override protected void readAdditionalSaveData(ValueInput in) {
        entityData.set(STACK, in.read("device", ItemStack.CODEC).orElse(ItemStack.EMPTY));
        pickupDelay = in.getIntOr("pickup_delay", 0); oldPitch = in.getFloatOr("pitch", -90);
        visualPitch = oldPitch; entityData.set(PITCH, oldPitch);
        entityData.set(BEACON_AT, in.getLongOr("beacon_at", -1L));
    }
}
