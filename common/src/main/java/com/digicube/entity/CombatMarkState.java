package com.digicube.entity;

import com.digicube.digimon.CrackMark;
import com.digicube.digimon.FreezeMark;
import com.digicube.digimon.IceCombo;

/**
 * Combat marks of any living entity: a tracked, packed readout for the client's emblems, and the
 * server-side gauges that hits pay into (Cold from frost contact, Crack from stone blows, Freeze from
 * Garurumon's frost). Effect durations remain server-owned.
 */
public interface CombatMarkState {
    int HELD = 2, INKED = 4;
    /** Remaining Cold travels in steps of this many ticks; the client counts the ticks in between. */
    int COLD_STEP_TICKS = 5;
    /** Remaining Inked and Cracked travel as a fraction of their full length, in this many units. */
    int INK_UNITS = 127, CRACK_UNITS = 127;

    /** Remaining Exposed travels as a fraction of its full length, in this many units. */
    int EXPOSED_UNITS = 127;
    /** In the second readout: a critical hit just landed on this Exposed body, and its emblem blinks. */
    int EXPOSED_FLASH = 1 << 7;
    /** Remaining Burn travels as a fraction of its full length, in this many units (second readout, bits 8-14). */
    int BURN_UNITS = 127;
    /** The Freeze gauge (bits 15-21) and the remaining ice (bits 22-28) travel as fractions, in this many units. */
    int FREEZE_UNITS = 127;
    /** In the second readout: the ice just closed or broke, and the Freeze emblem blinks. */
    int FREEZE_FLASH = 1 << 29;
    /** In the second readout: the body resists frost after its ice (a small slashed emblem), not Frozen now. */
    int FROST_RESIST = 1 << 30;

    /**
     * Bits 0-3 flags (bit 0 free), 4-8 Cold charge (ticks), 9-13 remaining Cold (steps), 14-20 remaining Inked
     * (fraction, {@link #INK_UNITS}), 21-23 Crack charges, 24-30 remaining Cracked (fraction,
     * {@link #CRACK_UNITS}). Full: new marks go in {@link #digicube$marks2()}.
     */
    int digicube$marks();

    /**
     * The second readout: bits 0-6 remaining Exposed (fraction, {@link #EXPOSED_UNITS}), 7 {@link #EXPOSED_FLASH},
     * 8-14 remaining Burn (fraction, {@link #BURN_UNITS}), 15-21 the Freeze gauge and 22-28 the remaining ice (fractions,
     * {@link #FREEZE_UNITS}), 29 {@link #FREEZE_FLASH}, 30 {@link #FROST_RESIST}. Bit 31 free.
     */
    int digicube$marks2();

    /** A critical hit landed on this Exposed body. */
    void digicube$exposedCrit();

    /**
     * A Digimon's fire attack set this body alight for this many ticks: it is Burned for as long as it keeps burning.
     * Vanilla's fire does the damage and water puts it out; the mark is how the fight reads it.
     */
    void digicube$burn(int ticks);

    /** One tick of landed frost. True once the charge is complete; the caller then applies Cold. */
    boolean digicube$chill();

    /**
     * Frost pays {@code amount} ({@link FreezeMark#FULL} is a whole gauge) into the Freeze gauge. True when the gauge
     * is full now: it empties, and the caller freezes the body ({@link FreezeMark#freeze}).
     */
    boolean digicube$freezeGauge(float amount);

    /** The ice closed on this body or broke: its emblem blinks. */
    void digicube$freezeFlash();

    /** A fire hit melts the Freeze gauge and its ice, the Cold charge and Cold itself. */
    void digicube$thaw();

    /** Stone blows pay charges into the Crack gauge; a full gauge empties into Cracked. */
    void digicube$crack(int charges);

    static int pack(boolean held, int coldCharge, int coldRemainingTicks, float inkRemaining,
                    int crackCharges, float crackedRemaining) {
        return (held ? HELD : 0) | (inkRemaining > 0 ? INKED : 0)
                | Math.min(31, coldCharge) << 4
                | Math.min(31, (coldRemainingTicks + COLD_STEP_TICKS - 1) / COLD_STEP_TICKS) << 9
                | Math.min(INK_UNITS, Math.round(Math.max(0, inkRemaining) * INK_UNITS)) << 14
                | Math.min(7, Math.max(0, crackCharges)) << 21
                | Math.min(CRACK_UNITS, (int) Math.ceil(Math.max(0, crackedRemaining) * CRACK_UNITS)) << 24;
    }

    static int pack2(float exposedRemaining, boolean exposedFlash, float burnRemaining) {
        return pack2(exposedRemaining, exposedFlash, burnRemaining, 0, 0, false, false);
    }

    static int pack2(float exposedRemaining, boolean exposedFlash, float burnRemaining, float freezeGauge, float frozenRemaining,
                     boolean freezeFlash, boolean frostResist) {
        return Math.min(EXPOSED_UNITS, (int) Math.ceil(Math.max(0, exposedRemaining) * EXPOSED_UNITS))
                | (exposedFlash && exposedRemaining > 0 ? EXPOSED_FLASH : 0)
                | Math.min(BURN_UNITS, (int) Math.ceil(Math.max(0, burnRemaining) * BURN_UNITS)) << 8
                | Math.min(FREEZE_UNITS, (int) Math.ceil(Math.clamp(freezeGauge, 0, 1) * FREEZE_UNITS)) << 15
                | Math.min(FREEZE_UNITS, (int) Math.ceil(Math.clamp(frozenRemaining, 0, 1) * FREEZE_UNITS)) << 22
                | (freezeFlash ? FREEZE_FLASH : 0) | (frostResist && frozenRemaining <= 0 ? FROST_RESIST : 0);
    }

    static boolean has(int marks, int flag) { return (marks & flag) != 0; }

    /** 0..1 of Exposed's length, from the second readout; above zero the target is Exposed. */
    static float exposedRemaining(int marks2) { return (marks2 & EXPOSED_UNITS) / (float) EXPOSED_UNITS; }

    /** 0..1 of the Burn's length, from the second readout; above zero the target is Burned. */
    static float burnRemaining(int marks2) { return (marks2 >> 8 & BURN_UNITS) / (float) BURN_UNITS; }

    /** 0..1 of a full Freeze gauge, from the second readout. */
    static float freezeGauge(int marks2) { return (marks2 >> 15 & FREEZE_UNITS) / (float) FREEZE_UNITS; }

    /** 0..1 of the ice's length, from the second readout; above zero the target is Frozen. */
    static float frozenRemaining(int marks2) { return (marks2 >> 22 & FREEZE_UNITS) / (float) FREEZE_UNITS; }

    /** 0..1 */
    static float coldCharge(int marks) { return Math.min(1F, (marks >> 4 & 31) / (float) IceCombo.COLD_CHARGE_TICKS); }

    static int coldRemainingTicks(int marks) { return (marks >> 9 & 31) * COLD_STEP_TICKS; }

    /** 0..1 of the ink's full length; the emblem's rim drains with it. */
    static float inkRemaining(int marks) { return (marks >> 14 & INK_UNITS) / (float) INK_UNITS; }

    /** 0..1 of a full Crack gauge. */
    static float crackCharge(int marks) { return Math.min(1F, (marks >> 21 & 7) / (float) CrackMark.CHARGES); }

    /** 0..1 of Cracked's length; above zero the target is Cracked. */
    static float crackedRemaining(int marks) { return (marks >> 24 & CRACK_UNITS) / (float) CRACK_UNITS; }
}
