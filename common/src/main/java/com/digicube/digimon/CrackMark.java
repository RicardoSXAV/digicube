package com.digicube.digimon;

import com.digicube.entity.CombatMarkState;
import net.minecraft.world.entity.LivingEntity;

/**
 * Crack: the mark of stone fists. Heavy blows fill a gauge on the target; a full gauge breaks
 * its guard and it is Cracked for a few seconds, taking more damage from everyone. The striker
 * need not hit hard itself: the opening is for the whole party.
 */
public final class CrackMark {
    private CrackMark() {}

    /** Charges a full gauge holds; a punch pays one, a spike wave two. */
    public static final int CHARGES = 3;
    public static final int CRACKED_TICKS = 120;
    /** Damage a Cracked target takes, from any source. */
    public static final float DAMAGE_TAKEN = 1.25F;
    /** A gauge nobody adds to for this long empties, one charge at a time at this pace. */
    public static final int DECAY_DELAY_TICKS = 160;

    /** Charges one landed hit of this attack pays; zero for attacks that do not crack. */
    public static int charges(DigimonAttack attack) {
        return switch (attack.kind()) {
            case FIST -> 1;
            case GROUND_WAVE -> 2;
            default -> 0;
        };
    }

    /** Call after a hit landed. Fills the gauge and, when full, cracks the victim. */
    public static void strike(DigimonAttack attack, LivingEntity victim) {
        int charges = charges(attack);
        if (charges > 0 && victim.isAlive()) ((CombatMarkState) victim).digicube$crack(charges);
    }
}
