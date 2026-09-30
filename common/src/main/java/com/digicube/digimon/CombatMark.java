package com.digicube.digimon;

import com.digicube.entity.CombatMarkState;
import com.digicube.entity.MegaFlameEntity;
import com.digicube.entity.PepperBreathEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The combat marks as a list, in the order the Analyzer's guide shows them. Each mark's mechanics stay where they are
 * ({@link FreezeMark}, {@link IceCombo}, {@link CrackMark}, {@link ExposedMark}, the readouts of
 * {@link CombatMarkState}); this names them, tells which one a move leaves on what it hits ({@link #of}) and which
 * emblems a body shows ({@link #showing}). A new mark is a constant here as well as an emblem over the body.
 */
public enum CombatMark {
    FREEZE, COLD, HELD, INKED, CRACK, EXPOSED, BURN;

    /** The most emblems one body shows at once. */
    public static final int MAX_SHOWN = 3;

    /** Lower case, as saved and as the lang keys and emblem textures spell it. */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    /** The mark's name; {@code .effect}, {@code .guide} and {@code .ends} follow it for the Analyzer's guide. */
    public String translationKey() { return "mark.digicube." + id(); }

    public static Optional<CombatMark> byId(String id) {
        for (CombatMark mark : values()) if (mark.id().equals(id)) return Optional.of(mark);
        return Optional.empty();
    }

    /** One bit per mark, for a set that travels in an int. */
    public static int mask(Collection<CombatMark> marks) {
        int mask = 0;
        for (CombatMark mark : marks) mask |= 1 << mark.ordinal();
        return mask;
    }

    public static Set<CombatMark> unmask(int mask) {
        Set<CombatMark> marks = EnumSet.noneOf(CombatMark.class);
        for (CombatMark mark : values()) if ((mask & 1 << mark.ordinal()) != 0) marks.add(mark);
        return marks;
    }

    /**
     * The emblems a body with these two readouts shows, left to right: build-ups first, at most {@link #MAX_SHOWN}.
     * The same rule the client draws the row of emblems by.
     */
    public static List<CombatMark> showing(int marks, int marks2) {
        List<CombatMark> shown = new ArrayList<>();
        if (CombatMarkState.freezeGauge(marks2) > 0 || CombatMarkState.frozenRemaining(marks2) > 0
                || CombatMarkState.has(marks2, CombatMarkState.FREEZE_FLASH)) shown.add(FREEZE);
        if (CombatMarkState.coldRemainingTicks(marks) > 0 || CombatMarkState.coldCharge(marks) > 0) shown.add(COLD);
        if (CombatMarkState.crackedRemaining(marks) > 0 || CombatMarkState.crackCharge(marks) > 0) shown.add(CRACK);
        if (CombatMarkState.has(marks, CombatMarkState.HELD)) shown.add(HELD);
        if (CombatMarkState.has(marks, CombatMarkState.INKED)) shown.add(INKED);
        if (CombatMarkState.exposedRemaining(marks2) > 0) shown.add(EXPOSED);
        if (CombatMarkState.burnRemaining(marks2) > 0) shown.add(BURN);
        return shown.size() > MAX_SHOWN ? List.copyOf(shown.subList(0, MAX_SHOWN)) : shown;
    }

    /** The mark a landed hit of {@code attack} leaves, read from the same data and rules the hit itself uses; null for none. */
    public static CombatMark of(DigimonAttack attack) {
        if (attack == null) return null;
        switch (attack.kind()) {
            case POUNCE -> {
                PounceAttacks.Spec pounce = PounceAttacks.get(attack);
                return pounce != null && pounce.freeze() > 0 ? FREEZE : null;
            }
            case FROST_STREAM -> {
                // A breath of puffs builds Freeze; a stream charges Cold.
                BreathAttacks.Spec breath = BreathAttacks.get(attack);
                return breath == null ? COLD : breath.freeze() > 0 ? FREEZE : null;
            }
            case CONSTRICTION -> { return HELD; }
            case FIREBALL, FLAME_SHOT -> { return BURN; }
            default -> { }
        }
        KineticAttacks.Definition shot = KineticAttacks.get(attack);
        if (shot != null && shot.impairmentTicks() > 0) return INKED;
        if (shot != null && shot.exposeTicks() > 0) return EXPOSED;
        return CrackMark.charges(attack) > 0 ? CRACK : null;
    }

    /** How long the mark {@code attack} leaves lasts, in ticks; 0 for no mark, and for Held, which lasts as long as the hold. */
    public static int ticks(DigimonAttack attack) {
        CombatMark mark = of(attack);
        if (mark == null) return 0;
        return switch (mark) {
            case FREEZE -> FreezeMark.FROZEN_TICKS;
            case COLD -> IceCombo.COLD_TICKS;
            case CRACK -> CrackMark.CRACKED_TICKS;
            case HELD -> 0;
            case INKED -> KineticAttacks.get(attack).impairmentTicks();
            case EXPOSED -> KineticAttacks.get(attack).exposeTicks();
            case BURN -> attack.kind() == DigimonAttack.Kind.FLAME_SHOT ? MegaFlameEntity.BURN_TICKS : PepperBreathEntity.BURN_TICKS;
        };
    }
}
