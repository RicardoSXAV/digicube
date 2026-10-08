package com.digicube.digimon;

import com.digicube.Constants;
import com.digicube.entity.AttackStance;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.List;

/**
 * Compound moves as data, without a game: the catalog's forms across families, a weapon's stance and a gauge, the list of
 * attacks a body plays (castables), the stance's synced code, and the malformed entries the catalog refuses. Leomon's two
 * moves are the bundled examples. Run from {@link SpeciesRegressionTest} after the species load.
 */
public final class CompoundAttacksRegressionTest {
    private CompoundAttacksRegressionTest() {}

    public static void run() {
        var leomon = DigimonSpeciesRegistry.getOrThrow(Constants.id("leomon"));
        check(leomon.stage() == DigimonStage.ADULT && leomon.attribute() == DigimonAttribute.VACCINE
                && leomon.attacks().stream().map(a -> a.id().getPath()).toList().equals(List.of("lion_sword", "beast_king_fist"))
                && leomon.attacks().stream().allMatch(a -> a.kind() == DigimonAttack.Kind.COMPOUND), "Leomon's two moves are compounds");
        var sword = leomon.attacks().get(0);
        var fist = leomon.attacks().get(1);

        // Lion Sword: a stance of 15 + 100 + 15 ticks (its clips'), swaps at 4 and 10, 80 ticks of cooldown after it; its forms the stab
        // in the air, the slash, the stab further out; strikes chain in their last 4 ticks.
        var lion = CompoundAttacks.get(sword);
        var stance = lion.stance();
        check(stance.draw() == 15 && stance.drawSwap() == 4 && stance.hold() == 100 && stance.sheathe() == 15 && stance.sheatheSwap() == 10
                && stance.cooldown() == 80 && stance.length() == 130 && stance.drawSound() != null && stance.sheatheSound() != null
                && lion.gauge() == null && lion.chain() == 4, "Lion Sword's stance and chain");
        check(lion.forms().size() == 3 && lion.forms().get(0).attack().id().getPath().equals("lion_sword_stab") && Boolean.TRUE.equals(lion.forms().get(0).air())
                && lion.forms().get(1).attack().id().getPath().equals("lion_sword_slash") && Boolean.FALSE.equals(lion.forms().get(1).air())
                && lion.forms().get(2).attack().id().getPath().equals("lion_sword_stab") && lion.forms().get(2).minReach() == 1.5,
                "Lion Sword's forms: the stab in the air, the slash, the stab further out");
        check(lion.forms().get(0).suits(true, 3) && !lion.forms().get(0).suits(false, 3) && !lion.forms().get(2).suits(false, 1)
                && lion.forms().get(2).suits(false, 6) && lion.forms().get(1).suits(false, 0), "a form's conditions: the air and the reach");
        check(PounceAttacks.get(lion.forms().get(0).attack()).air() != null && PounceAttacks.get(lion.forms().get(0).attack()).runStart() > 0
                && AuthoredAttacks.forms(lion.forms().get(1).attack()) != null && AuthoredAttacks.forms(lion.forms().get(1).attack()).all().size() == 3,
                "the stab has its air and run starts, the slash its three combo forms");
        check(!sword.isRanged() && fist.isRanged() && sword.range() == 10, "the sword is close work drawn from 10 blocks, the fist reaches far");

        // Beast King Fist: a gauge of 100 that the sword's hits fill (a slash form pays as its move), no stance, no cooldown.
        var king = CompoundAttacks.get(fist);
        var gauge = king.gauge();
        var slash = lion.forms().get(1).attack();
        var rising = AuthoredAttacks.forms(slash).all().get(1);
        check(gauge != null && gauge.capacity() == 100 && king.stance() == null && gauge.fill(slash) == 25 && gauge.fill(rising) == 25
                && gauge.fill(lion.forms().get(0).attack()) == 35 && gauge.fill(king.forms().get(0).attack()) == 0, "the gauge and what fills it");
        check(king.forms().get(0).attack().id().getPath().equals("beast_king_fist_punch") && king.forms().get(0).maxReach() == 4
                && PounceAttacks.get(king.forms().get(0).attack()).launch() != null
                && king.forms().get(1).attack().id().getPath().equals("beast_king_fist_shot") && king.forms().get(1).move()
                && CompoundAttacks.castsOnTheMove(king.forms().get(1).attack()) && !CompoundAttacks.castsOnTheMove(king.forms().get(0).attack()),
                "the punch up close throws its victim, the shot goes on the move");

        // What a body plays: the sheet's moves, then the forms, each once, inside the start events' indices.
        var castables = CompoundAttacks.castables(leomon.attacks());
        check(castables.stream().map(a -> a.id().getPath()).toList().equals(List.of("lion_sword", "beast_king_fist", "lion_sword_stab",
                "lion_sword_slash", "beast_king_fist_punch", "beast_king_fist_shot")) && castables.size() <= com.digicube.entity.DigimonAnimationEvents.MAX_ATTACKS
                && CompoundAttacks.castables(leomon.attacks()) == castables && CompoundAttacks.castableCount(leomon.attacks()) == 6,
                "Leomon's castables: its two moves, then their four forms");
        var greymon = DigimonSpeciesRegistry.getOrThrow(Constants.id("greymon"));
        check(CompoundAttacks.castables(greymon.attacks()) == greymon.attacks(), "a sheet without compounds plays its moves alone");
        check(CompoundAttacks.owner(leomon.attacks(), rising) == lion && CompoundAttacks.owner(leomon.attacks(), king.forms().get(1).attack()) == king
                && CompoundAttacks.owner(leomon.attacks(), greymon.attacks().getFirst()) == null && lion.owns(rising) && !lion.owns(king.forms().get(0).attack()),
                "a form's owner: an authored combo form belongs to its move's compound");
        check(CombatMark.of(sword) == null && CombatMark.of(fist) == null, "neither move leaves a mark");

        // The stance's synced code and when the weapon is out.
        int code = AttackStance.code(AttackStance.Phase.HOLD, 1, 37);
        check(code != 0 && AttackStance.code(AttackStance.Phase.DRAW, 0, 0) != 0 && AttackStance.phase(code) == AttackStance.Phase.HOLD
                && AttackStance.slot(code) == 1 && AttackStance.ticks(code) == 37 && AttackStance.phase(0) == null
                && AttackStance.ticks(AttackStance.code(AttackStance.Phase.SHEATHE, 15, 9000)) == 8191, "the stance code carries phase, slot and ticks");
        check(!AttackStance.drawn(stance, AttackStance.Phase.DRAW, stance.drawSwap() - .1F) && AttackStance.drawn(stance, AttackStance.Phase.DRAW, stance.drawSwap())
                && AttackStance.drawn(stance, AttackStance.Phase.HOLD, 0) && AttackStance.drawn(stance, AttackStance.Phase.SHEATHE, stance.sheatheSwap() - .1F)
                && !AttackStance.drawn(stance, AttackStance.Phase.SHEATHE, stance.sheatheSwap()) && !AttackStance.drawn(stance, null, 0), "the weapon is out between the swaps");

        // A catalog entry of its own: the move reports its forms' power and range, a tick of clock and no cooldown.
        var own = CompoundAttacks.parse(json("{\"own\": {\"forms\": [{\"attack\": \"lion_sword_stab\"}], \"gauge\": {\"capacity\": 50, \"fill\": {\"freeze_fang\": 10}}}}"));
        var ownMove = own.get(Constants.id("own")).attack();
        check(ownMove.kind() == DigimonAttack.Kind.COMPOUND && ownMove.durationTicks() == 1 && ownMove.cooldownTicks() == 1
                && ownMove.range() == lion.forms().get(0).attack().range() && ownMove.power() == lion.forms().get(0).attack().power(),
                "a compound's own record");
        // What the catalog refuses.
        refused("{\"x\": {\"forms\": []}}", "no forms");
        refused("{\"x\": {\"forms\": [{\"attack\": \"lion_sword_slash_rising\"}]}}", "a later combo form named as a form");
        refused("{\"x\": {\"forms\": [{\"attack\": \"howling_blaster\"}]}}", "a form of a family that cannot be cast as one");
        refused("{\"x\": {\"forms\": [{\"attack\": \"no_such_attack\"}]}}", "an unknown form");
        refused("{\"x\": {\"forms\": [{\"attack\": \"lion_sword_stab\", \"min_reach\": 5, \"max_reach\": 2}]}}", "a reach that runs backwards");
        refused("{\"x\": {\"forms\": [{\"attack\": \"lion_sword_stab\"}], \"stance\": {\"draw\": 14, \"draw_swap\": 15, \"hold\": 100, "
                + "\"sheathe\": 14, \"sheathe_swap\": 10, \"cooldown\": 80}}}", "a swap past its clip");
        refused("{\"x\": {\"forms\": [{\"attack\": \"lion_sword_stab\"}], \"gauge\": {\"capacity\": 100, \"fill\": {\"no_such_attack\": 5}}}}",
                "a gauge filled by an unknown attack");
        refused("{\"x\": {\"forms\": [{\"attack\": \"lion_sword_stab\"}], \"gauge\": {\"capacity\": 0, \"fill\": {\"lion_sword_stab\": 5}}}}",
                "a gauge of no capacity");
        refused("{\"x\": {\"forms\": [{\"attack\": \"lion_sword_stab\"}], \"chain\": 11}}", "a chain longer than 10 ticks");
        Constants.LOG.info("Compound attack checks passed: Leomon's stance and gauge, forms, castables, the stance code and refused entries.");
    }

    private static JsonObject json(String text) { return GsonHelper.parse(text); }

    private static void refused(String text, String what) {
        try {
            CompoundAttacks.parse(json(text));
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("the compound catalog took " + what);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
