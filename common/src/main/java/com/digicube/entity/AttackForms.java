package com.digicube.entity;

import com.digicube.digimon.AuthoredAttacks;
import com.digicube.digimon.DigimonAttack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Which form of a move with forms ({@link AuthoredAttacks.Forms}) is cast: by the AI from where the target is and
 * what was cast last, by a rider from the movement key held.
 */
public final class AttackForms {
    /** A leap is not worth starting below this chance of landing on its target (an impaired target is always worth it). */
    static final double MIN_LEAP_CHANCE = .3;

    private AttackForms() {}

    /**
     * The form the AI casts against {@code target} from {@code feet}, or null when none can strike from there.
     * A combo continues with its next form while the last ended within {@link AuthoredAttacks#COMBO_TICKS}; a fresh one
     * opens with the lightest form that reaches (a form that does not travel before one that does). Distance forms
     * take the one worth most: its power times the chance it lands ({@link DigimonEntity#strikeChance}).
     */
    public static DigimonAttack choose(DigimonEntity mob, DigimonAttack move, LivingEntity target, Vec3 feet) {
        var forms = AuthoredAttacks.forms(move);
        if (forms == null) return mob.canStrikeFrom(move, target, feet) ? move : null;
        if (forms.choice() == AuthoredAttacks.FormChoice.REACH) {
            DigimonAttack best = null;
            double worth = 0, chance = 0;
            for (var form : forms.all()) {
                if (!mob.canStrikeFrom(form, target, feet)) continue;
                double c = mob.strikeChance(form, target), w = form.power() * c;
                if (w > worth) { best = form; worth = w; chance = c; }
            }
            return best != null && (chance >= MIN_LEAP_CHANCE || DigimonEntity.impaired(target)) ? best : null;
        }
        for (var form : order(mob, forms)) if (mob.canStrikeFrom(form, target, feet)) return form;
        return null;
    }

    /** Forms in the order a combo tries them. */
    private static List<DigimonAttack> order(DigimonEntity mob, AuthoredAttacks.Forms forms) {
        var all = forms.all();
        var order = new ArrayList<DigimonAttack>(all.size());
        int last = mob.lastForm() == null ? -1 : forms.index(mob.lastForm());
        if (last >= 0 && mob.tickCount - mob.lastFormEnd() <= AuthoredAttacks.COMBO_TICKS) {
            for (int k = 1; k <= all.size(); k++) order.add(all.get((last + k) % all.size()));
            return order;
        }
        for (var form : all) if (!AuthoredAttacks.get(form).travels()) order.add(form);
        for (var form : all) if (AuthoredAttacks.get(form).travels()) order.add(form);
        return order;
    }

    /**
     * The form a rider casts: the one whose {@code key} is the movement key held (a strafe key before forward), the
     * move itself without one.
     */
    public static DigimonAttack rider(DigimonAttack move, boolean forward, boolean left, boolean right) {
        var forms = AuthoredAttacks.forms(move);
        if (forms == null) return move;
        String key = right && !left ? "right" : left && !right ? "left" : forward ? "forward" : null;
        if (key != null) for (var form : forms.all()) if (key.equals(AuthoredAttacks.get(form).key())) return form;
        return move;
    }
}
