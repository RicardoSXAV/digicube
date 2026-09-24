package com.digicube.digimon;

import com.digicube.registry.DCEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * Exposed: the hunter's mark. A shot that finds its target leaves it Exposed for a few seconds: every
 * Digimon hit against it is far likelier to be critical, and it cannot sidestep. Like Crack, the opening
 * is for the whole party, not only for the shooter.
 */
public final class ExposedMark {
    private ExposedMark() {}

    /** Added to a hit's critical chance against an Exposed target: the neutral 10 % becomes 40 %. */
    public static final float CRIT_BONUS = .30F;
    /** How long the emblem blinks after a critical hit lands on an Exposed target. */
    public static final int FLASH_TICKS = 4;

    public static boolean exposed(LivingEntity entity) {
        return entity != null && entity.hasEffect(DCEffects.EXPOSED);
    }

    /** A fresh shot restarts the timer; it never stacks. */
    public static void expose(LivingEntity victim, int ticks) {
        if (ticks > 0 && victim.isAlive()) victim.addEffect(new MobEffectInstance(DCEffects.EXPOSED, ticks, 0, false, true));
    }
}
