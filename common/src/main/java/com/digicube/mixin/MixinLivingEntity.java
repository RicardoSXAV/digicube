package com.digicube.mixin;

import com.digicube.digimon.IceCombo;
import com.digicube.registry.DCEffects;
import com.digicube.entity.CombatMarkState;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No loader event exposes the vanilla immobility gate which also suspends mob AI. */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity implements CombatMarkState {
    @Unique
    private static final EntityDataAccessor<Integer> digicube$MARKS =
            SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);
    /** Server-side only; not saved, a charge never outlives a few seconds. */
    @Unique
    private int digicube$coldCharge, digicube$coldTouchTick;
    /** The longest the current ink has been, so its emblem drains from full whatever attack applied it. */
    @Unique
    private int digicube$inkLength;

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void digicube$defineMarks(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(digicube$MARKS, 0);
    }

    @Inject(method = "tickEffects", at = @At("TAIL"))
    private void digicube$syncMarks(CallbackInfo ci) {
        LivingEntity living = (LivingEntity) (Object) this;
        if (living.level().isClientSide()) return;
        if (living.isOnFire()) digicube$thaw();
        MobEffectInstance cold = living.getEffect(DCEffects.COLD);
        if (cold != null || !living.isAlive()) digicube$coldCharge = 0;
        else if (digicube$coldCharge > 0 && living.tickCount - digicube$coldTouchTick > IceCombo.COLD_DECAY_DELAY_TICKS) digicube$coldCharge--;
        MobEffectInstance ink = living.getEffect(DCEffects.INKED);
        digicube$inkLength = ink == null ? 0 : Math.max(digicube$inkLength, ink.getDuration());
        living.getEntityData().set(digicube$MARKS, !living.isAlive() ? 0 : CombatMarkState.pack(
                living.hasEffect(DCEffects.ICE_MARK), living.hasEffect(DCEffects.CONSTRICTED),
                digicube$coldCharge, cold == null ? 0 : cold.getDuration(),
                ink == null ? 0 : ink.isInfiniteDuration() ? 1 : ink.getDuration() / (float) Math.max(1, digicube$inkLength)));
    }

    @Override
    public int digicube$marks() {
        return ((LivingEntity) (Object) this).getEntityData().get(digicube$MARKS);
    }

    @Override
    public boolean digicube$chill() {
        digicube$coldTouchTick = ((LivingEntity) (Object) this).tickCount;
        digicube$coldCharge = Math.min(IceCombo.COLD_CHARGE_TICKS, digicube$coldCharge + 1);
        return digicube$coldCharge >= IceCombo.COLD_CHARGE_TICKS;
    }

    @Override
    public void digicube$thaw() {
        LivingEntity living = (LivingEntity) (Object) this;
        digicube$coldCharge = 0;
        living.removeEffect(DCEffects.ICE_MARK);
        living.removeEffect(DCEffects.COLD);
    }

    @Inject(method = "isImmobile", at = @At("RETURN"), cancellable = true)
    private void digicube$frozenImmobility(CallbackInfoReturnable<Boolean> result) {
        LivingEntity living = (LivingEntity) (Object) this;
        if (living.hasEffect(DCEffects.FROZEN) || living.hasEffect(DCEffects.CONSTRICTED)) result.setReturnValue(true);
    }
}
