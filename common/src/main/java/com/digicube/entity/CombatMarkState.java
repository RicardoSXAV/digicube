package com.digicube.entity;

import com.digicube.digimon.IceCombo;

/**
 * Combat marks of any living entity: a tracked, packed readout for the client's emblems, and the
 * server-side Cold charge that frost contact pays into. Effect durations remain server-owned.
 */
public interface CombatMarkState {
    int ICE_MARK = 1, HELD = 2, INKED = 4;
    /** Remaining Cold travels in steps of this many ticks; the client counts the ticks in between. */
    int COLD_STEP_TICKS = 5;
    /** Remaining Inked travels as a fraction of the ink's full length, in this many units. */
    int INK_UNITS = 127;

    /**
     * Flags in the low byte, Cold charge (ticks) in the second, remaining Cold (steps) in the third,
     * remaining Inked (fraction, {@link #INK_UNITS}) in the seven bits above.
     */
    int digicube$marks();

    /** One tick of landed frost. True once the charge is complete; the caller then applies Cold. */
    boolean digicube$chill();

    /** A fire hit melts the ice mark, the Cold charge and Cold itself. */
    void digicube$thaw();

    static int pack(boolean iceMark, boolean held, int coldCharge, int coldRemainingTicks, float inkRemaining) {
        return (iceMark ? ICE_MARK : 0) | (held ? HELD : 0) | (inkRemaining > 0 ? INKED : 0)
                | Math.min(255, coldCharge) << 8
                | Math.min(255, (coldRemainingTicks + COLD_STEP_TICKS - 1) / COLD_STEP_TICKS) << 16
                | Math.min(INK_UNITS, Math.round(Math.max(0, inkRemaining) * INK_UNITS)) << 24;
    }

    static boolean has(int marks, int flag) { return (marks & flag) != 0; }

    /** 0..1 */
    static float coldCharge(int marks) { return Math.min(1F, (marks >> 8 & 255) / (float) IceCombo.COLD_CHARGE_TICKS); }

    static int coldRemainingTicks(int marks) { return (marks >> 16 & 255) * COLD_STEP_TICKS; }

    /** 0..1 of the ink's full length; the emblem's rim drains with it. */
    static float inkRemaining(int marks) { return (marks >> 24 & INK_UNITS) / (float) INK_UNITS; }
}
