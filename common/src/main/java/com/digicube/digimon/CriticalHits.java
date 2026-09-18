package com.digicube.digimon;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Critical hits, and the attribute triangle as a chance edge rather than a damage multiplier.
 *
 * <p>Every Digimon hit rolls once. Against a Digimon the triangle moves the chance: the
 * favoured side crits more often, the countered side less (Ricardo, 2026-09-18: "a small
 * advantage, in percentage", so a same-level fight against a counter stays winnable). In
 * expectation the favoured side deals about 10 % more than the countered one; the rest is
 * luck, which is what keeps two equal fighters from always ending the same way.
 */
public final class CriticalHits {
    public static final float MULTIPLIER = 1.5F;
    public static final float BASE_CHANCE = .10F;
    /** Overridable per process for balance sweeps. */
    public static final float ADVANTAGE_CHANCE = Float.parseFloat(System.getenv().getOrDefault("DIGICUBE_TRIANGLE_UP", ".25")),
            DISADVANTAGE_CHANCE = Float.parseFloat(System.getenv().getOrDefault("DIGICUBE_TRIANGLE_DOWN", ".05"));

    private CriticalHits() {}

    public static float chance(DigimonAttribute attacker, DigimonAttribute defender) {
        if (attacker.strongAgainst() == defender) return ADVANTAGE_CHANCE;
        if (defender.strongAgainst() == attacker) return DISADVANTAGE_CHANCE;
        return BASE_CHANCE;
    }

    /** The damage this hit deals after its critical roll; a crit sparks and rings at the victim. */
    public static float roll(ServerLevel level, DigimonEntity attacker, LivingEntity victim, float damage) {
        float chance = BASE_CHANCE;
        if (victim instanceof DigimonEntity other && attacker.getSpecies().isPresent() && other.getSpecies().isPresent()) {
            chance = chance(attacker.getSpecies().get().attribute(), other.getSpecies().get().attribute());
        }
        if (attacker.getRandom().nextFloat() >= chance) return damage;
        attacker.countCriticalHit();
        level.sendParticles(ParticleTypes.CRIT, true, true, victim.getX(), victim.getY(.6), victim.getZ(), 12,
                victim.getBbWidth() * .4, victim.getBbHeight() * .3, victim.getBbWidth() * .4, .15);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.NEUTRAL, 1F, 1F);
        Constants.LOG.debug("[crit] {} on {} for {}", attacker.getType().toShortString(), victim.getType().toShortString(), damage * MULTIPLIER);
        return damage * MULTIPLIER;
    }
}
