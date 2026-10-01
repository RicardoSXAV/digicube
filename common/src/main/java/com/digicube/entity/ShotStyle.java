package com.digicube.entity;

import com.digicube.registry.DCParticles;
import com.digicube.registry.DCSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a kinetic shot sounds and looks like besides its model: the report as it leaves the muzzle, the trail it
 * strews in flight and the burst where it strikes. A move opts in with {@code "shot_style"} in
 * {@code kinetic_attacks.json}. The report and the burst are sent by the server, so everyone near the fight sees and
 * hears them; the trail is strewn by each client from the bolt it draws, on the stretch it flew that tick.
 */
public enum ShotStyle {
    NONE,
    /**
     * Centarumon's Hunting Cannon: a heavy report with a flash at the muzzle, yellow sparks behind the bolt, and a
     * burst of flat yellow pixel planes with a white-hot flash at its heart where it strikes. A full draw rings lower.
     */
    CANNON,
    /**
     * Crabmon's Water Shot: a wet spit and a spray of droplets at the mouth, a slug that sheds drops and specks as it
     * flies, and a splash of spray where it bursts. Water, so it also puts out a burning victim ({@link #douses}).
     */
    WATER,
    /**
     * Monochromon's Volcano Strike: a roaring cough of fire and smoke at the jaws, a ball of magma that drips lava and
     * trails flame, smoke and ash, and an eruption of fire, lava and smoke where it bursts.
     */
    MAGMA;

    public static ShotStyle byId(String id) {
        return id == null ? NONE : valueOf(id.toUpperCase(java.util.Locale.ROOT));
    }

    /** Sparks a bolt strews per block of flight. */
    private static final double SPARKS_PER_BLOCK = 3.5;

    /** The shot leaves the muzzle; {@code charge} is a rider's draw (1 for an unridden shot). */
    public void fire(ServerLevel level, Vec3 muzzle, Vec3 direction, float charge) {
        if (this == MAGMA) {
            level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 1.2F, .55F);
            level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1F, .7F);
            Vec3 ahead = muzzle.add(direction.scale(.3));
            level.sendParticles(ParticleTypes.FLAME, true, true, ahead.x, ahead.y, ahead.z, 16, .18, .14, .18, .06);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, muzzle.x, muzzle.y, muzzle.z, 6, .15, .1, .15, .02);
            level.sendParticles(ParticleTypes.LAVA, true, true, ahead.x, ahead.y, ahead.z, 4, .12, .08, .12, 0);
            return;
        }
        if (this == WATER) {
            level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.PUFFER_FISH_BLOW_OUT, SoundSource.NEUTRAL, 1.1F, 1.25F);
            level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.SQUID_SQUIRT, SoundSource.NEUTRAL, .8F, 1.5F);
            Vec3 ahead = muzzle.add(direction.scale(.25));
            level.sendParticles(ParticleTypes.SPLASH, true, true, ahead.x, ahead.y, ahead.z, 14, .12, .08, .12, .05);
            level.sendParticles(ParticleTypes.FALLING_WATER, true, true, muzzle.x, muzzle.y, muzzle.z, 5, .1, .03, .1, 0);
            return;
        }
        if (this != CANNON) return;
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, DCSounds.HUNTING_CANNON_FIRE, SoundSource.NEUTRAL, 1.6F, 1.12F - .16F * charge);
        flash(level, muzzle, .9F + .5F * charge);
        Vec3 ahead = muzzle.add(direction.scale(.3));
        level.sendParticles(DCParticles.CANNON_SPARK, true, true, ahead.x, ahead.y, ahead.z, 8, .08, .08, .08, 0);
    }

    /** Client, every tick of flight: sparks along the stretch from {@code from} to {@code to}. */
    public void trail(Level level, Vec3 from, Vec3 to, RandomSource random) {
        if (this == MAGMA) {
            // Flame licks off the ball and hangs where it passed; smoke and ash drift up behind; lava drips off it.
            Vec3 step = to.subtract(from);
            int puffs = Math.max(1, (int) Math.round(step.length() * 3));
            for (int i = 0; i < puffs; i++) {
                Vec3 at = from.add(step.scale((i + random.nextDouble()) / puffs));
                level.addParticle(ParticleTypes.FLAME, at.x + random.nextGaussian() * .12, at.y + random.nextGaussian() * .12,
                        at.z + random.nextGaussian() * .12, step.x * .05, step.y * .05 + .01, step.z * .05);
                if (random.nextInt(2) == 0) level.addParticle(ParticleTypes.SMOKE, at.x, at.y + .1, at.z, 0, .03, 0);
                if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.ASH, at.x, at.y, at.z, 0, 0, 0);
            }
            if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.DRIPPING_LAVA, from.x, from.y - .2, from.z, 0, 0, 0);
            if (random.nextInt(4) == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, from.x, from.y, from.z, 0, .04, 0);
            return;
        }
        if (this == WATER) {
            // Drops fall off the slug and fine spray hangs where it passed.
            Vec3 step = to.subtract(from);
            int drops = Math.max(1, (int) Math.round(step.length() * 2.5));
            for (int i = 0; i < drops; i++) {
                Vec3 at = from.add(step.scale((i + random.nextDouble()) / drops));
                level.addParticle(random.nextInt(3) == 0 ? ParticleTypes.FALLING_WATER : ParticleTypes.SPLASH, at.x, at.y, at.z,
                        random.nextGaussian() * .03, .04 + random.nextDouble() * .05, random.nextGaussian() * .03);
                if (random.nextInt(2) == 0) level.addParticle(ParticleTypes.DOLPHIN, at.x, at.y, at.z, 0, 0, 0);
            }
            return;
        }
        if (this != CANNON) return;
        Vec3 step = to.subtract(from);
        int sparks = Math.max(1, (int) Math.round(step.length() * SPARKS_PER_BLOCK));
        for (int i = 0; i < sparks; i++) {
            Vec3 at = from.add(step.scale((i + random.nextDouble()) / sparks));
            // They keep a little of the bolt's way, then drift and fall off it.
            level.addParticle(DCParticles.CANNON_SPARK, true, true, at.x, at.y, at.z,
                    step.x * .04 + random.nextGaussian() * .02, step.y * .04 + random.nextGaussian() * .02, step.z * .04 + random.nextGaussian() * .02);
        }
    }

    /** The bolt strikes a block or a body at {@code at}. */
    public void impact(ServerLevel level, Vec3 at) {
        if (this == MAGMA) {
            RandomSource random = level.getRandom();
            level.playSound(null, at.x, at.y, at.z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.NEUTRAL, 1.1F, .75F + random.nextFloat() * .1F);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.LAVA_POP, SoundSource.NEUTRAL, 1.4F, .6F);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.BASALT_BREAK, SoundSource.NEUTRAL, 1F, .6F);
            level.sendParticles(ParticleTypes.FLAME, true, true, at.x, at.y, at.z, 34, .35, .3, .35, .12);
            level.sendParticles(ParticleTypes.LAVA, true, true, at.x, at.y, at.z, 14, .3, .25, .3, 0);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, at.x, at.y + .2, at.z, 10, .35, .3, .35, .03);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, at.x, at.y + .2, at.z, 4, .3, .2, .3, .01);
            level.sendParticles(ParticleTypes.ASH, true, true, at.x, at.y, at.z, 30, .6, .5, .6, 0);
            return;
        }
        if (this == WATER) {
            RandomSource random = level.getRandom();
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 1F, 1.1F + random.nextFloat() * .15F);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_SPLASH, SoundSource.NEUTRAL, .7F, 1.4F);
            level.sendParticles(ParticleTypes.SPLASH, true, true, at.x, at.y, at.z, 40, .3, .25, .3, .15);
            level.sendParticles(ParticleTypes.FALLING_WATER, true, true, at.x, at.y, at.z, 12, .3, .2, .3, 0);
            level.sendParticles(ParticleTypes.CLOUD, true, true, at.x, at.y, at.z, 3, .15, .1, .15, .02);
            return;
        }
        if (this != CANNON) return;
        RandomSource random = level.getRandom();
        level.playSound(null, at.x, at.y, at.z, DCSounds.HUNTING_CANNON_IMPACT, SoundSource.NEUTRAL, 2F, .92F + random.nextFloat() * .16F);
        flash(level, at, 2.4F);
        flash(level, at, 1.8F);
        flash(level, at, 1.4F);
        level.sendParticles(DCParticles.CANNON_SHARD, true, true, at.x, at.y, at.z, 42, .12, .12, .12, 0);
        level.sendParticles(DCParticles.CANNON_SPARK, true, true, at.x, at.y, at.z, 16, .25, .25, .25, 0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, at.x, at.y, at.z, 4, .25, .25, .25, .02);
    }

    /** A bolt that flew its full range without striking anything fizzles out where it is. */
    public void fizzle(ServerLevel level, Vec3 at) {
        if (this == MAGMA) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, at.x, at.y, at.z, 8, .2, .2, .2, .02);
            level.sendParticles(ParticleTypes.LAVA, true, true, at.x, at.y, at.z, 3, .15, .1, .15, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, .6F, .7F);
            return;
        }
        if (this == WATER) {
            level.sendParticles(ParticleTypes.SPLASH, true, true, at.x, at.y, at.z, 16, .2, .15, .2, .08);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, .4F, 1.5F);
            return;
        }
        if (this != CANNON) return;
        level.sendParticles(DCParticles.CANNON_SPARK, true, true, at.x, at.y, at.z, 10, .15, .15, .15, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, .35F, 1.8F);
    }

    /** A shot of water puts out what it hits. */
    public boolean douses() { return this == WATER; }

    /** One flash plane of the given size in blocks: sent with no count, so the size rides as the velocity's x. */
    private static void flash(ServerLevel level, Vec3 at, float size) {
        level.sendParticles(DCParticles.CANNON_FLASH, true, true, at.x, at.y, at.z, 0, size, 0, 0, 1);
    }
}
