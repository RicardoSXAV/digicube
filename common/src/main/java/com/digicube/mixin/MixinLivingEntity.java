package com.digicube.mixin;

import com.digicube.registry.DCEffects;
import com.digicube.entity.IceMarkState;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No loader event exposes the vanilla immobility gate which also suspends mob AI. */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity implements IceMarkState {
    @Unique
    private static final EntityDataAccessor<Boolean> digicube$ICE_MARK =
            SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void digicube$defineMark(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(digicube$ICE_MARK, false);
    }

    @Inject(method = "tickEffects", at = @At("TAIL"))
    private void digicube$syncMark(CallbackInfo ci) {
        LivingEntity living = (LivingEntity) (Object) this;
        if (!living.level().isClientSide()) {
            living.getEntityData().set(digicube$ICE_MARK, living.isAlive() && living.hasEffect(DCEffects.ICE_MARK));
        }
    }

    @Override
    public boolean digicube$hasIceMark() {
        return ((LivingEntity) (Object) this).getEntityData().get(digicube$ICE_MARK);
    }

    @Inject(method = "isImmobile", at = @At("RETURN"), cancellable = true)
    private void digicube$frozenImmobility(CallbackInfoReturnable<Boolean> result) {
        if (((LivingEntity) (Object) this).hasEffect(DCEffects.FROZEN)) result.setReturnValue(true);
    }
}
