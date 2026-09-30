package com.digicube.digimon;

import com.digicube.entity.CombatMarkState;
import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEffects;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Freeze mark: a gauge on any living body that frost pays into (a Freeze Fang bite, a tick of Howling Blaster's
 * contact). Unfed it holds for {@link #HOLD_TICKS}, then drains. Full, the body is Frozen for {@link #FROZEN_TICKS}: it
 * cannot move, jump or act (vanilla's freezing shake and frost show it), and a pounce on it shatters the ice for more
 * damage. After the ice the body resists frost for {@link #RESIST_TICKS}: the gauge cannot fill, so a freeze never
 * chains into another. Fire melts the gauge and the ice. The gauge and its readout live in {@code MixinLivingEntity}
 * ({@link CombatMarkState}); the emblem is a round medallion with a snowflake that fills from the bottom.
 */
public final class FreezeMark {
    private FreezeMark() {}

    /** A full gauge. */
    public static final float FULL = 100;
    /** An unfed gauge holds this long, then drains {@link #DRAIN} a tick. */
    public static final int HOLD_TICKS = 40;
    public static final float DRAIN = 2.5F;
    /** How long a full gauge freezes its body. */
    public static final int FROZEN_TICKS = 50;
    /** How long after the ice the gauge cannot fill again. */
    public static final int RESIST_TICKS = 80;
    /** Ticks the emblem blinks white as the ice closes, or breaks. */
    public static final int FLASH_TICKS = 6;

    public static boolean frozen(LivingEntity body) { return body.hasEffect(DCEffects.FROZEN); }

    /** Frost cannot build on this body now: already Frozen, resisting after the ice, or immune to it. */
    public static boolean resists(LivingEntity body) {
        return body.hasEffect(DCEffects.FROZEN) || body.hasEffect(DCEffects.FROST_RESISTANCE)
                || !body.canBeAffected(new MobEffectInstance(DCEffects.FROZEN, FROZEN_TICKS));
    }

    /**
     * Server: pays {@code amount} (0 to {@link #FULL}) into {@code victim}'s gauge; a gauge that fills freezes it.
     * @return whether the body froze now
     */
    public static boolean freeze(ServerLevel level, LivingEntity victim, float amount, Entity source) {
        if (!victim.isAlive() || amount <= 0 || resists(victim)) return false;
        if (!((CombatMarkState) victim).digicube$freezeGauge(amount)) return false;
        if (!victim.addEffect(new MobEffectInstance(DCEffects.FROZEN, FROZEN_TICKS, 0, false, true), source)) return false;
        victim.addEffect(new MobEffectInstance(DCEffects.FROST_RESISTANCE, FROZEN_TICKS + RESIST_TICKS, 0, false, false, true), source);
        if (victim instanceof DigimonEntity digimon) digimon.interruptAttack();
        ((CombatMarkState) victim).digicube$freezeFlash();
        level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, victim.getX(), victim.getY(.5), victim.getZ(),
                40, victim.getBbWidth() * .55, victim.getBbHeight() * .5, victim.getBbWidth() * .55, .05);
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, true, true, victim.getX(), victim.getY(.5), victim.getZ(),
                16, victim.getBbWidth() * .45, victim.getBbHeight() * .4, victim.getBbWidth() * .45, .12);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.PLAYER_HURT_FREEZE, SoundSource.NEUTRAL, 1.1F, .7F);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.GLASS_PLACE, SoundSource.NEUTRAL, .9F, .55F);
        return true;
    }

    /** Server: a pounce broke a Frozen body out of its ice (the resistance after it stands). */
    public static void shatter(ServerLevel level, LivingEntity victim, Vec3 at) {
        victim.removeEffect(DCEffects.FROZEN);
        ((CombatMarkState) victim).digicube$freezeFlash();
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, true, true, at.x, at.y, at.z, 30, .45, .45, .45, .25);
        level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, at.x, at.y, at.z, 30, .5, .5, .5, .09);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 1.2F, .75F);
    }
}
