package com.digicube.fabric.client.digivice;

import com.digicube.digimon.CombatMark;
import com.digicube.digimon.CrackMark;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.ExposedMark;
import com.digicube.digimon.FreezeMark;
import com.digicube.digimon.IceCombo;
import com.digicube.registry.DCEffects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BinaryOperator;

/**
 * What the Analyzer's guide says about each combat mark, read from the game's own rules so that it cannot fall behind
 * them: which Digimon leave the mark and with which moves ({@link CombatMark#of}), how long it lasts, and the numbers
 * its sentence quotes. No client types here, so the guide tests headless.
 */
public final class MarkGuide {
    /** One Digimon that leaves the mark, with the moves that do it, in the order of its sheet. */
    public record Applier(DigimonSpecies species, List<DigimonAttack> attacks) {}

    /**
     * @param shortest how long the mark lasts after the move that leaves it shortest, in ticks; 0 while nothing says
     * @param longest  and after the one that leaves it longest
     */
    public record Entry(CombatMark mark, List<Applier> appliers, int shortest, int longest) {}

    private final Map<CombatMark, Entry> entries = new EnumMap<>(CombatMark.class);

    public MarkGuide(Collection<DigimonSpecies> species) {
        List<DigimonSpecies> sorted = new ArrayList<>(species);
        sorted.sort(Comparator.comparingInt((DigimonSpecies s) -> s.stage().ordinal()).thenComparing(s -> s.id().toString()));
        for (CombatMark mark : CombatMark.values()) {
            List<Applier> appliers = new ArrayList<>();
            int shortest = Integer.MAX_VALUE, longest = 0;
            for (DigimonSpecies s : sorted) {
                List<DigimonAttack> moves = s.attacks().stream().filter(attack -> CombatMark.of(attack) == mark).toList();
                if (moves.isEmpty()) continue;
                appliers.add(new Applier(s, moves));
                for (DigimonAttack move : moves) {
                    shortest = Math.min(shortest, CombatMark.ticks(move));
                    longest = Math.max(longest, CombatMark.ticks(move));
                }
            }
            entries.put(mark, new Entry(mark, List.copyOf(appliers), appliers.isEmpty() ? 0 : shortest, longest));
        }
    }

    /** Every mark, in the guide's order, whether or not a Digimon leaves it yet. */
    public List<Entry> entries() { return List.copyOf(entries.values()); }
    public Entry get(CombatMark mark) { return entries.get(mark); }

    /**
     * The numbers the mark's sentence ({@code mark.digicube.<id>.guide}) quotes, in its order.
     * @param span joins the shortest and the longest length when moves differ, e.g. "3 to 6"
     */
    public static Object[] numbers(Entry entry, BinaryOperator<String> span) {
        String lasts = entry.shortest() == entry.longest() ? seconds(entry.longest()) : span.apply(seconds(entry.shortest()), seconds(entry.longest()));
        return switch (entry.mark()) {
            case FREEZE -> new Object[]{seconds(FreezeMark.FROZEN_TICKS), seconds(FreezeMark.RESIST_TICKS)};
            case COLD -> new Object[]{percent(-IceCombo.COLD_SLOW), seconds(IceCombo.COLD_TICKS)};
            case HELD -> new Object[0];
            case INKED -> new Object[]{number(DCEffects.INK_SIGHT), lasts};
            case CRACK -> new Object[]{Integer.toString(CrackMark.CHARGES), percent(CrackMark.DAMAGE_TAKEN - 1), seconds(CrackMark.CRACKED_TICKS)};
            case EXPOSED -> new Object[]{lasts, percent(ExposedMark.CRIT_BONUS)};
            case BURN -> new Object[]{lasts};
        };
    }

    /** Ticks as seconds: "6", "2.5". */
    public static String seconds(int ticks) { return number(ticks / 20.0); }
    /** A share as a whole percentage: .25 is "25". */
    public static String percent(double share) { return Long.toString(Math.round(share * 100)); }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
