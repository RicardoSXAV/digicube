package com.digicube.entity;

import com.digicube.digimon.CompoundAttacks;
import com.digicube.digimon.DigimonAttack;

/**
 * A weapon drawn for a while ({@link CompoundAttacks.Stance}; Leomon's Lion Sword): the server's clock of one stance, and
 * the code every client reads it from. The draw plays, the hold lasts its ticks (held open while one of the move's
 * strikes plays out), the sheathe plays, and the move's cooldown starts. The stance is no attack under way: the legs
 * stay the body's own, and the draw, the hold and the sheathe are drawn over the gait.
 *
 * <p>The code ({@code DigimonEntity}'s synced stance) packs the phase, the move's slot on the sheet and the ticks into
 * the phase; 0 is no stance.
 */
public final class AttackStance {
    public enum Phase { DRAW, HOLD, SHEATHE }

    private static final int TICKS = (1 << 13) - 1, PRESENT = 1 << 19;

    private final DigimonAttack move;
    private final CompoundAttacks.Stance spec;
    private final int slot;
    private Phase phase = Phase.DRAW;
    private int ticks;

    AttackStance(DigimonAttack move, CompoundAttacks.Stance spec, int slot) {
        this.move = move;
        this.spec = spec;
        this.slot = slot;
    }

    public DigimonAttack move() { return move; }
    public CompoundAttacks.Stance spec() { return spec; }
    public Phase phase() { return phase; }
    public int ticks() { return ticks; }
    /** The forms strike only now. */
    public boolean holds() { return phase == Phase.HOLD; }

    /**
     * One tick. A strike of the move under way ({@code striking}) keeps the hold open past its end, so a strike begun
     * before the end plays out and the sheathe follows it.
     * @return the swap this tick passed (the weapon came out or went back): 1 out, -1 back, 0 none; or
     *         {@link #ENDED} when the sheathe has ended
     */
    int tick(boolean striking) {
        ticks++;
        int swap = 0;
        switch (phase) {
            case DRAW -> {
                if (ticks == spec.drawSwap()) swap = 1;
                if (ticks >= spec.draw()) { phase = Phase.HOLD; ticks = 0; }
            }
            case HOLD -> {
                if (ticks >= spec.hold() && !striking) { phase = Phase.SHEATHE; ticks = 0; }
            }
            case SHEATHE -> {
                if (ticks == spec.sheatheSwap()) swap = -1;
                if (ticks >= spec.sheathe()) return ENDED;
            }
        }
        return swap;
    }
    /** What {@link #tick} answers once the sheathe is over. */
    static final int ENDED = 2;

    /** The synced code of this stance now. */
    int code() { return code(phase, slot, ticks); }

    /** The code of a stance {@code ticks} into {@code phase}, its move in sheet slot {@code slot} (0 to 15). */
    public static int code(Phase phase, int slot, int ticks) {
        return PRESENT | (slot & 15) << 15 | phase.ordinal() << 13 | Math.clamp(ticks, 0, TICKS);
    }

    /** The phase a code carries, or null for none. */
    public static Phase phase(int code) { return code == 0 ? null : Phase.values()[(code >> 13) & 3]; }
    /** The sheet slot of the move a code carries. */
    public static int slot(int code) { return (code >> 15) & 15; }
    /** The ticks into its phase a code carries. */
    public static int ticks(int code) { return code & TICKS; }

    /**
     * Whether the weapon is out {@code ticks} into {@code phase}: from the draw's swap until the sheathe's (the hand
     * prop shown and the stowed one hidden then, the other way round otherwise).
     */
    public static boolean drawn(CompoundAttacks.Stance spec, Phase phase, float ticks) {
        if (phase == null) return false;
        return switch (phase) {
            case DRAW -> ticks >= spec.drawSwap();
            case HOLD -> true;
            case SHEATHE -> ticks < spec.sheatheSwap();
        };
    }
}
