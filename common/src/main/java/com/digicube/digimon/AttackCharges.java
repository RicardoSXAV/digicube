package com.digicube.digimon;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Stacked uses of an attack ({@code charges} in {@code authored_attacks.json}, {@code pounce_attacks.json} or
 * {@code kinetic_attacks.json}; Gold Rush holds three, Freeze Fang and Mega Blaster two). Each use starts
 * its own refill of the attack's cooldown, so a full stack can be fired back to back, a cast at a time. The body's
 * ordinary cooldown clock stays the one gate everything reads: while a stack is left it says "ready now", once none is
 * it says when the soonest comes back.
 */
public final class AttackCharges {
    private AttackCharges() {}

    /** How many uses {@code attack} stacks; 1 for an ordinary cooldown. */
    public static int of(DigimonAttack attack) {
        if (attack == null) return 1;
        var authored = AuthoredAttacks.get(attack);
        if (authored != null) return authored.charges();
        var pounce = PounceAttacks.get(attack);
        if (pounce != null) return pounce.charges();
        var shot = KineticAttacks.get(attack);
        return shot == null ? 1 : shot.charges();
    }

    /**
     * Spends a use of {@code attack} at tick {@code now}: its refill clock joins {@code refills}.
     * @return the tick the attack may start again (now while a stack is left)
     */
    public static int spend(Map<Identifier, List<Integer>> refills, DigimonAttack attack, int now) {
        int charges = of(attack);
        if (charges <= 1) return now + attack.cooldownTicks();
        List<Integer> clocks = refills.computeIfAbsent(attack.id(), id -> new ArrayList<>());
        clocks.removeIf(until -> until <= now);
        clocks.add(now + attack.cooldownTicks());
        return clocks.size() < charges ? now : Collections.min(clocks);
    }

    /** Uses of {@code attack} ready at tick {@code now}. */
    public static int ready(Map<Identifier, List<Integer>> refills, DigimonAttack attack, int now) {
        List<Integer> clocks = refills.get(attack.id());
        int spent = 0;
        if (clocks != null) for (int until : clocks) if (until > now) spent++;
        return Math.max(0, of(attack) - spent);
    }

    /** The tick the soonest spent use of {@code attack} comes back, or {@code now} when none is out. */
    public static int nextRefill(Map<Identifier, List<Integer>> refills, DigimonAttack attack, int now) {
        List<Integer> clocks = refills.get(attack.id());
        int next = Integer.MAX_VALUE;
        if (clocks != null) for (int until : clocks) if (until > now) next = Math.min(next, until);
        return next == Integer.MAX_VALUE ? now : next;
    }
}
