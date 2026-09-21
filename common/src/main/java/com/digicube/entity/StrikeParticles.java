package com.digicube.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What an authored volume throws off besides its drawn effect: a trail while it travels, a burst where it lands.
 * A move opts in with {@code "particles"} in {@code authored_attacks.json}; the server sends everything, so every
 * player near the fight sees and hears the same blow.
 */
public enum StrikeParticles {
    NONE,
    /**
     * A small rock Digimon's fists and summoned stone: chips and dust in the air, the struck floor thrown up on
     * landing. The sounds are light and bright (a chirp, a bonk, crockery), not a monster's: a heavy stone style
     * for a big Digimon would be its own constant.
     */
    STONE;

    public static StrikeParticles byId(String id) {
        return id == null ? NONE : valueOf(id.toUpperCase(java.util.Locale.ROOT));
    }

    private static final BlockParticleOption CHIPS = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState());

    /**
     * The caster's voice as the move starts, in place of the growl every other authored attack opens with.
     * Returns false when this style has no voice of its own.
     */
    public boolean windUp(ServerLevel level, Vec3 at, boolean summoned) {
        if (this == NONE) return false;
        play(level, at, SoundEvents.ARMADILLO_AMBIENT, .9F, summoned ? 1.05F : 1.3F);
        if (summoned) play(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, .8F);
        return true;
    }

    /** The fist starts its strike. */
    public void swing(ServerLevel level, Vec3 at) {
        if (this == NONE) return;
        play(level, at, SoundEvents.PLAYER_ATTACK_SWEEP, .6F, 1.45F);
    }

    /** One tick of a volume on the move: a fist through its swing, a stone on its way down. */
    public void trail(ServerLevel level, Vec3 at, boolean summoned) {
        if (this == NONE) return;
        if (summoned) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, at.x, at.y, at.z, 2, .18, .18, .18, .01);
            level.sendParticles(ParticleTypes.SMALL_FLAME, true, true, at.x, at.y, at.z, 4, .2, .2, .2, .02);
            level.sendParticles(ParticleTypes.LAVA, true, true, at.x, at.y, at.z, 1, .1, .1, .1, 0);
            level.sendParticles(CHIPS, true, true, at.x, at.y, at.z, 3, .25, .25, .25, .05);
        } else {
            level.sendParticles(CHIPS, true, true, at.x, at.y, at.z, 2, .08, .08, .08, .02);
            level.sendParticles(ParticleTypes.CRIT, true, true, at.x, at.y, at.z, 1, .06, .06, .06, .05);
        }
    }

    /** The stone leaves the caster's hold. */
    public void release(ServerLevel level, Vec3 at) {
        if (this == NONE) return;
        play(level, at, SoundEvents.BREEZE_SHOOT, .8F, 1.1F);
        level.sendParticles(ParticleTypes.POOF, true, true, at.x, at.y, at.z, 10, .3, .3, .3, .06);
    }

    /** A fist lands on a victim. */
    public void contact(ServerLevel level, Vec3 at) {
        if (this == NONE) return;
        level.sendParticles(CHIPS, true, true, at.x, at.y, at.z, 18, .18, .18, .18, .12);
        level.sendParticles(ParticleTypes.CRIT, true, true, at.x, at.y, at.z, 10, .2, .2, .2, .25);
        level.sendParticles(ParticleTypes.POOF, true, true, at.x, at.y, at.z, 4, .12, .12, .12, .03);
        play(level, at, SoundEvents.STONE_HIT, 1.2F, 1.15F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_BASEDRUM, SoundSource.NEUTRAL, 1F, 1.2F);
    }

    /** A summoned strike meets the floor: the floor itself goes up, out to the reach of the burst. */
    public void landing(ServerLevel level, Vec3 at, double reach) {
        if (this == NONE) return;
        BlockState floor = level.getBlockState(BlockPos.containing(at.x, at.y - .2, at.z));
        if (floor.isAir() || !floor.getFluidState().isEmpty()) floor = Blocks.STONE.defaultBlockState();
        double spread = reach * .55;
        level.sendParticles(ParticleTypes.EXPLOSION, true, true, at.x, at.y + .4, at.z, 2, .3, .1, .3, 0);
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, floor), true, true, at.x, at.y + .05, at.z, 45, spread, .05, spread, .2);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, floor), true, true, at.x, at.y + .2, at.z, 60, spread, .15, spread, .35);
        level.sendParticles(CHIPS, true, true, at.x, at.y + .3, at.z, 30, spread * .6, .2, spread * .6, .3);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, at.x, at.y + .2, at.z, 8, spread, .1, spread, .015);
        level.sendParticles(ParticleTypes.POOF, true, true, at.x, at.y + .15, at.z, 24, spread, .05, spread, .12);
        level.sendParticles(ParticleTypes.LAVA, true, true, at.x, at.y + .2, at.z, 8, spread * .5, .1, spread * .5, 0);
        play(level, at, SoundEvents.MACE_SMASH_GROUND, 1.1F, 1.15F);
        play(level, at, SoundEvents.DECORATED_POT_SHATTER, 1.3F, .75F);
        play(level, at, SoundEvents.STONE_BREAK, 1.2F, .9F);
    }

    private static void play(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume, pitch);
    }
}
