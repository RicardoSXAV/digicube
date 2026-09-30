package com.digicube.mixin;

import com.digicube.digimon.CrackMark;
import com.digicube.digimon.ExposedMark;
import com.digicube.digimon.FreezeMark;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No loader event exposes the vanilla immobility gate which also suspends mob AI. */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity implements CombatMarkState {
    @Unique
    private static final EntityDataAccessor<Integer> digicube$MARKS =
            SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);
    /** The second readout, begun when the first ran out of bits. */
    @Unique
    private static final EntityDataAccessor<Integer> digicube$MARKS2 =
            SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);
    /** Server-side only; not saved, a charge never outlives a few seconds. */
    @Unique
    private int digicube$coldCharge, digicube$coldTouchTick;
    /** The longest the current ink has been, so its emblem drains from full whatever attack applied it. */
    @Unique
    private int digicube$inkLength;
    /** Server-side only, like the Cold charge: stone blows counted toward Cracked, and when the last one landed. */
    @Unique
    private int digicube$crackCharges, digicube$crackTouchTick;
    /** Like the ink's: the longest the current Exposed has been, and until when its emblem blinks for a critical hit. */
    @Unique
    private int digicube$exposedLength, digicube$exposedFlashUntil;
    /** The longest the fire a Digimon lit on this body has been, in ticks; 0 while it is not Burned. */
    @Unique
    private int digicube$burnLength;
    /** Server-side only, like the Cold charge: frost paid into the Freeze gauge (0 to FreezeMark.FULL), and when last. */
    @Unique
    private float digicube$freezeGauge;
    @Unique
    private int digicube$freezeTouchTick;
    /** The longest the current ice has been, so its emblem drains from full; until when the emblem blinks. */
    @Unique
    private int digicube$frozenLength, digicube$freezeFlashUntil;

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void digicube$defineMarks(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(digicube$MARKS, 0);
        builder.define(digicube$MARKS2, 0);
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
        MobEffectInstance cracked = living.getEffect(DCEffects.CRACKED);
        if (cracked != null || !living.isAlive()) digicube$crackCharges = 0;
        else if (digicube$crackCharges > 0 && living.tickCount - digicube$crackTouchTick > CrackMark.DECAY_DELAY_TICKS) {
            digicube$crackCharges--;
            digicube$crackTouchTick = living.tickCount;
        }
        living.getEntityData().set(digicube$MARKS, !living.isAlive() ? 0 : CombatMarkState.pack(
                living.hasEffect(DCEffects.CONSTRICTED),
                digicube$coldCharge, cold == null ? 0 : cold.getDuration(),
                ink == null ? 0 : ink.isInfiniteDuration() ? 1 : ink.getDuration() / (float) Math.max(1, digicube$inkLength),
                digicube$crackCharges,
                cracked == null ? 0 : cracked.isInfiniteDuration() ? 1 : Math.min(1F, cracked.getDuration() / (float) CrackMark.CRACKED_TICKS)));
        MobEffectInstance exposed = living.getEffect(DCEffects.EXPOSED);
        digicube$exposedLength = exposed == null ? 0 : Math.max(digicube$exposedLength, exposed.getDuration());
        // Burned while the fire lasts: water, rain or the fire running out ends it, whatever lit it again since.
        if (!living.isOnFire()) digicube$burnLength = 0;
        // Frozen: the ice holds the body still (DCEffects.FROZEN) and vanilla's freezing shows on it; an unfed gauge
        // holds a while and then drains, and none builds while the body is Frozen or resisting after its ice.
        MobEffectInstance ice = living.getEffect(DCEffects.FROZEN);
        boolean resisting = living.hasEffect(DCEffects.FROST_RESISTANCE);
        if (ice != null || resisting || !living.isAlive()) digicube$freezeGauge = 0;
        else if (digicube$freezeGauge > 0 && living.tickCount - digicube$freezeTouchTick > FreezeMark.HOLD_TICKS)
            digicube$freezeGauge = Math.max(0, digicube$freezeGauge - FreezeMark.DRAIN);
        digicube$frozenLength = ice == null ? 0 : Math.max(digicube$frozenLength, ice.getDuration());
        if (ice != null) living.setTicksFrozen(Math.max(living.getTicksFrozen(), living.getTicksRequiredToFreeze()));
        living.getEntityData().set(digicube$MARKS2, !living.isAlive() ? 0 : CombatMarkState.pack2(
                exposed == null ? 0 : exposed.isInfiniteDuration() ? 1 : exposed.getDuration() / (float) Math.max(1, digicube$exposedLength),
                living.tickCount < digicube$exposedFlashUntil,
                digicube$burnLength == 0 ? 0 : Math.min(1F, living.getRemainingFireTicks() / (float) digicube$burnLength),
                digicube$freezeGauge / FreezeMark.FULL,
                ice == null ? 0 : ice.isInfiniteDuration() ? 1 : ice.getDuration() / (float) Math.max(1, digicube$frozenLength),
                living.tickCount < digicube$freezeFlashUntil, resisting));
    }

    @Override
    public int digicube$marks() {
        return ((LivingEntity) (Object) this).getEntityData().get(digicube$MARKS);
    }

    @Override
    public int digicube$marks2() {
        return ((LivingEntity) (Object) this).getEntityData().get(digicube$MARKS2);
    }

    @Override
    public void digicube$exposedCrit() {
        digicube$exposedFlashUntil = ((LivingEntity) (Object) this).tickCount + ExposedMark.FLASH_TICKS;
    }

    @Override
    public void digicube$burn(int ticks) {
        LivingEntity living = (LivingEntity) (Object) this;
        if (!living.isOnFire()) return; // fire-proof bodies are never Burned
        digicube$burnLength = Math.max(digicube$burnLength, Math.max(ticks, living.getRemainingFireTicks()));
    }

    @Override
    public boolean digicube$chill() {
        digicube$coldTouchTick = ((LivingEntity) (Object) this).tickCount;
        digicube$coldCharge = Math.min(IceCombo.COLD_CHARGE_TICKS, digicube$coldCharge + 1);
        return digicube$coldCharge >= IceCombo.COLD_CHARGE_TICKS;
    }

    @Override
    public boolean digicube$freezeGauge(float amount) {
        LivingEntity living = (LivingEntity) (Object) this;
        digicube$freezeTouchTick = living.tickCount;
        digicube$freezeGauge = Math.min(FreezeMark.FULL, digicube$freezeGauge + amount);
        if (digicube$freezeGauge < FreezeMark.FULL) return false;
        digicube$freezeGauge = 0;
        return true;
    }

    @Override
    public void digicube$freezeFlash() {
        digicube$freezeFlashUntil = ((LivingEntity) (Object) this).tickCount + FreezeMark.FLASH_TICKS;
    }

    @Override
    public void digicube$thaw() {
        LivingEntity living = (LivingEntity) (Object) this;
        digicube$coldCharge = 0;
        digicube$freezeGauge = 0;
        living.removeEffect(DCEffects.FROZEN);
        living.removeEffect(DCEffects.COLD);
    }

    @Override
    public void digicube$crack(int charges) {
        LivingEntity living = (LivingEntity) (Object) this;
        if (living.hasEffect(DCEffects.CRACKED)) return; // the opening is already there; it is not extended
        digicube$crackTouchTick = living.tickCount;
        digicube$crackCharges = Math.min(CrackMark.CHARGES, digicube$crackCharges + charges);
        if (digicube$crackCharges < CrackMark.CHARGES) return;
        digicube$crackCharges = 0;
        living.addEffect(new MobEffectInstance(DCEffects.CRACKED, CrackMark.CRACKED_TICKS, 0, false, true));
    }

    /** A Cracked body takes more from every source. */
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float digicube$crackedDamage(float amount) {
        return ((LivingEntity) (Object) this).hasEffect(DCEffects.CRACKED) ? amount * CrackMark.DAMAGE_TAKEN : amount;
    }

    @Inject(method = "isImmobile", at = @At("RETURN"), cancellable = true)
    private void digicube$frozenImmobility(CallbackInfoReturnable<Boolean> result) {
        LivingEntity living = (LivingEntity) (Object) this;
        if (living.hasEffect(DCEffects.FROZEN) || living.hasEffect(DCEffects.CONSTRICTED)) result.setReturnValue(true);
    }
}
