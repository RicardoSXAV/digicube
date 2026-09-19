package com.digicube.entity;

import com.digicube.digimon.CrackMark;
import com.digicube.digimon.IceCombo;

/**
 * Combat marks of any living entity: a tracked, packed readout for the client's emblems, and the
 * server-side gauges that hits pay into (Cold from frost contact, Crack from stone blows).
 * Effect durations remain server-owned.
 */
public interface CombatMarkState {
    int ICE_MARK = 1, HELD = 2, INKED = 4;
    /** Remaining Cold travels in steps of this many ticks; the client counts the ticks in between. */
    int COLD_STEP_TICKS = 5;
    /** Remaining Inked and Cracked travel as a fraction of their full length, in this many units. */
    int INK_UNITS = 127, CRACK_UNITS = 127;

    /**
     * Bits 0-3 flags, 4-8 Cold charge (ticks), 9-13 remaining Cold (steps), 14-20 remaining Inked
     * (fraction, {@link #INK_UNITS}), 21-23 Crack charges, 24-30 remaining Cracked (fraction,
     * {@link #CRACK_UNITS}).
     */
    int digicube$marks();

    /** One tick of landed frost. True once the charge is complete; the caller then applies Cold. */
    boolean digicube$chill();

    /** A fire hit melts the ice mark, the Cold charge and Cold itself. */
    void digicube$thaw();

    /** Stone blows pay charges into the Crack gauge; a full gauge empties into Cracked. */
    void digicube$crack(int charges);

    static int pack(boolean iceMark, boolean held, int coldCharge, int coldRemainingTicks, float inkRemaining,
                    int crackCharges, float crackedRemaining) {
        return (iceMark ? ICE_MARK : 0) | (held ? HELD : 0) | (inkRemaining > 0 ? INKED : 0)
                | Math.min(31, coldCharge) << 4
                | Math.min(31, (coldRemainingTicks + COLD_STEP_TICKS - 1) / COLD_STEP_TICKS) << 9
                | Math.min(INK_UNITS, Math.round(Math.max(0, inkRemaining) * INK_UNITS)) << 14
                | Math.min(7, Math.max(0, crackCharges)) << 21
                | Math.min(CRACK_UNITS, (int) Math.ceil(Math.max(0, crackedRemaining) * CRACK_UNITS)) << 24;
    }

    static boolean has(int marks, int flag) { return (marks & flag) != 0; }

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
