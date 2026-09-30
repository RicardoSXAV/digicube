package com.digicube.fabric.client.digivice;

import com.digicube.digimon.BreathAttacks;
import com.digicube.digimon.ConstrictionMotion;
import com.digicube.digimon.CrackMark;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.ExposedMark;
import com.digicube.digimon.FreezeMark;
import com.digicube.digimon.IceCombo;
import com.digicube.digimon.PounceAttacks;

import java.util.Locale;

/**
 * One run of a mark as its emblem plays it over a body, for the guide's stage: it builds, closes, drains and clears,
 * on the lengths the game uses, and then starts again. Each moment says how the emblem is drawn and what is going on.
 * No client types here, so the run tests headless.
 */
public final class MarkLife {
    public enum Draw {
        /** No emblem: the body carries no mark now. */
        NONE,
        /** The unlit emblem with its charge risen from the bottom. */
        BUILD,
        /** The spent emblem with what is left of the lit one as a pie, draining clockwise from the top. */
        TIMER,
        /** The whole emblem in white: something just happened to the mark. */
        FLASH,
        /** The whole lit emblem. */
        WHOLE,
        /** The whole lit emblem tinted red: the body was just hurt through the mark. */
        HURT,
        /** The small slashed emblem: the mark cannot build now. */
        RESIST
    }

    /**
     * @param amount  0..1: a build-up's charge, or what is left of a timer
     * @param caption the lang key, under {@code gui.digicube.marks.life.}, of what is going on
     * @param args    what the caption quotes
     */
    public record Frame(Draw draw, float amount, String caption, Object... args) {
        /** How full the bar under the emblem is. */
        public float bar() {
            return switch (draw) { case NONE -> 0; case WHOLE, HURT -> 1; default -> amount; };
        }
    }

    /** Ticks without a mark before a run, and after it. */
    static final int REST = 10, PAUSE = 12;
    /** A charge that arrives in steps stays on show this long before the next one. */
    private static final int STEP = 30;
    private static final Frame CLEAR = new Frame(Draw.NONE, 0, "none");

    private MarkLife() {}

    /** The moment {@code tick} ticks into the guide's stage; the run repeats. */
    public static Frame at(MarkGuide.Entry entry, int tick) {
        int t = Math.floorMod(tick, length(entry)) - REST;
        if (t < 0) return CLEAR;
        return switch (entry.mark()) {
            case FREEZE -> freeze(entry, t);
            case COLD -> t < IceCombo.COLD_CHARGE_TICKS
                    ? new Frame(Draw.BUILD, (t + 1F) / IceCombo.COLD_CHARGE_TICKS, "chill", percent((t + 1F) / IceCombo.COLD_CHARGE_TICKS))
                    : timer(t - IceCombo.COLD_CHARGE_TICKS, IceCombo.COLD_TICKS, "slowed");
            case HELD -> {
                int held = ConstrictionMotion.RELEASE_TICK - ConstrictionMotion.CAPTURE_TICK;
                if (t >= held) yield CLEAR;
                boolean squeezed = t >= ConstrictionMotion.INTERVAL && t % ConstrictionMotion.INTERVAL < 4;
                yield new Frame(squeezed ? Draw.HURT : Draw.WHOLE, 1, squeezed ? "squeezed" : "held");
            }
            case INKED -> timer(t, lasts(entry), "inked");
            case CRACK -> {
                int build = (CrackMark.CHARGES - 1) * STEP + 4;
                if (t >= build) yield timer(t - build, CrackMark.CRACKED_TICKS, "cracked");
                int charge = Math.min(CrackMark.CHARGES, t / STEP + 1);
                yield new Frame(Draw.BUILD, (float) charge / CrackMark.CHARGES, "hit", charge, CrackMark.CHARGES);
            }
            case EXPOSED -> {
                int lasts = lasts(entry);
                if (t >= lasts) yield CLEAR;
                for (int crit : new int[]{lasts * 3 / 10, lasts * 13 / 20}) {
                    if (t >= crit && t < crit + ExposedMark.FLASH_TICKS) yield new Frame(Draw.FLASH, 1 - (float) t / lasts, "crit");
                }
                yield timer(t, lasts, "exposed");
            }
            case BURN -> timer(t, lasts(entry), "burning");
        };
    }

    /** Ticks of one run, the rests around it included. */
    public static int length(MarkGuide.Entry entry) {
        int run = switch (entry.mark()) {
            case FREEZE -> STEP + breathTicks(entry) + 2 * FreezeMark.FLASH_TICKS + FreezeMark.FROZEN_TICKS + FreezeMark.RESIST_TICKS;
            case COLD -> IceCombo.COLD_CHARGE_TICKS + IceCombo.COLD_TICKS;
            case HELD -> ConstrictionMotion.RELEASE_TICK - ConstrictionMotion.CAPTURE_TICK;
            case CRACK -> (CrackMark.CHARGES - 1) * STEP + 4 + CrackMark.CRACKED_TICKS;
            case INKED, EXPOSED, BURN -> lasts(entry);
        };
        return REST + run + PAUSE;
    }

    /** A bite pays its share at once; the breath then fills the rest, tick by tick. */
    private static Frame freeze(MarkGuide.Entry entry, int t) {
        float bite = bite(entry), breath = breath(entry);
        if (t < STEP) return new Frame(Draw.BUILD, bite, "bite", percent(bite));
        t -= STEP;
        int filling = breathTicks(entry);
        if (t < filling) {
            float gauge = Math.min(1, bite + breath * (t + 1));
            return new Frame(Draw.BUILD, gauge, "breath", percent(gauge));
        }
        t -= filling;
        if (t < FreezeMark.FLASH_TICKS) return new Frame(Draw.FLASH, 1, "frozen_now");
        t -= FreezeMark.FLASH_TICKS;
        if (t < FreezeMark.FROZEN_TICKS) return timer(t, FreezeMark.FROZEN_TICKS, "frozen");
        t -= FreezeMark.FROZEN_TICKS;
        if (t < FreezeMark.FLASH_TICKS) return new Frame(Draw.FLASH, 0, "ice_breaks");
        t -= FreezeMark.FLASH_TICKS;
        if (t < FreezeMark.RESIST_TICKS) return new Frame(Draw.RESIST, 1 - (float) t / FreezeMark.RESIST_TICKS, "immune", seconds(FreezeMark.RESIST_TICKS - t));
        return CLEAR;
    }

    private static Frame timer(int t, int lasts, String caption) {
        return t >= lasts ? CLEAR : new Frame(Draw.TIMER, 1 - (float) t / lasts, caption, seconds(lasts - t));
    }

    /** The share of a full gauge one bite pays: the first pounce that freezes, or a little under half. */
    private static float bite(MarkGuide.Entry entry) {
        for (MarkGuide.Applier applier : entry.appliers()) for (DigimonAttack attack : applier.attacks()) {
            PounceAttacks.Spec pounce = PounceAttacks.get(attack);
            if (pounce != null && pounce.freeze() > 0) return Math.min(1, pounce.freeze() / FreezeMark.FULL);
        }
        return .45F;
    }

    /** The share a tick of breath pays: the first breath that freezes, or a gauge in a second and a half. */
    private static float breath(MarkGuide.Entry entry) {
        for (MarkGuide.Applier applier : entry.appliers()) for (DigimonAttack attack : applier.attacks()) {
            BreathAttacks.Spec breath = BreathAttacks.get(attack);
            if (breath != null && breath.freeze() > 0) return Math.min(1, breath.freeze() / FreezeMark.FULL);
        }
        return 1 / 30F;
    }

    private static int breathTicks(MarkGuide.Entry entry) {
        return Math.max(1, (int) Math.ceil((1 - bite(entry)) / breath(entry)));
    }

    /** The shortest the mark lasts: the run the tamer will see most; two seconds while no move leaves it. */
    private static int lasts(MarkGuide.Entry entry) { return entry.shortest() > 0 ? entry.shortest() : 40; }

    private static String seconds(int ticks) { return String.format(Locale.ROOT, "%.1f", ticks / 20F); }
    private static String percent(float share) { return Integer.toString(Math.round(share * 100)); }
}
