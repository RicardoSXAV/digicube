package com.digicube.fabric.client.party;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.Progression;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.party.CommandWheelReadout.Module;
import com.digicube.fabric.client.party.CommandWheelReadout.Order;
import com.digicube.fabric.client.party.CommandWheelReadout.Reason;
import com.digicube.fabric.client.party.CommandWheelReadout.Refusal;
import com.digicube.fabric.client.party.CommandWheelReadout.Target;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;

import java.util.UUID;

/**
 * Pins the command wheel's rules: which order each key offers, when it can be given and why not, what the cursor
 * points at, where the wheel sits beside the party strip, and when an attack order is sent.
 * No game, no window, no test framework.
 */
public final class CommandWheelRegressionTest {
    private CommandWheelRegressionTest() {}

    private static int failures;

    public static void main(String[] args) {
        Module[] idle = CommandWheelReadout.modules(member(20, 24, true, 0, 3600, "RESTING", 0, false, false), 0, true);
        check(idle[0].order() == Order.STAND_STILL && idle[0].enabled(), "a following partner is offered Stand still");
        check(idle[1].order() == Order.CANCEL_TARGET && !idle[1].enabled() && idle[1].reason() == Reason.NOT_ATTACKING, "Cancel target is off without a target");
        check(idle[2].order() == Order.RECALL && idle[2].enabled(), "a deployed partner is offered Recall");
        check(idle[3].order() == Order.DIGIVOLVE && idle[3].enabled() && idle[3].reason() == Reason.NONE, "full DigiSoul at level 24 with a route: Digivolve");

        Module[] busy = CommandWheelReadout.modules(member(20, 24, true, 0, 3600, "RESTING", 0, true, true), 0, true);
        check(busy[0].order() == Order.FOLLOW && busy[0].enabled(), "a holding partner is offered Follow");
        check(busy[1].enabled() && busy[1].reason() == Reason.NONE, "Cancel target lights while attacking");

        Module[] aimedAt = CommandWheelReadout.modules(member(20, 24, true, 0, 3600, "RESTING", 0, false, false), 0, true, true);
        check(aimedAt[1].order() == Order.RIDE && aimedAt[1].enabled() && aimedAt[0].order() == Order.STAND_STILL, "a partner aimed at that can carry its owner is offered Ride where Cancel target had nothing to cancel");
        Module[] aimedFighting = CommandWheelReadout.modules(member(20, 24, true, 0, 3600, "RESTING", 0, true, true), 0, true, true);
        check(aimedFighting[1].order() == Order.CANCEL_TARGET && aimedFighting[1].enabled(), "a fighting partner keeps Cancel target; it is called off before it is ridden");
        check(CommandWheelReadout.modules(member(20, 24, false, 0, 3600, "RESTING", 0, false, false), 0, true, true)[1].order() == Order.CANCEL_TARGET, "no Ride from the Digivice");

        Module[] stowed = CommandWheelReadout.modules(member(20, 24, false, 0, 3600, "RESTING", 0, false, false), 0, true);
        check(stowed[2].order() == Order.RECALL && !stowed[2].enabled() && stowed[2].reason() == Reason.NO_SPACE, "there is no Send out: a partner with no room to come out just says so");
        check(!stowed[0].enabled() && stowed[0].reason() == Reason.NO_SPACE, "no behaviour orders before it is out");
        check(!stowed[1].enabled() && stowed[1].reason() == Reason.NO_SPACE, "no target to cancel before it is out");
        check(!stowed[3].enabled() && stowed[3].reason() == Reason.NO_SPACE, "no Digivolution before it is out");

        Module[] resting = CommandWheelReadout.modules(member(0, 24, false, 5440, 0, "RESTING", 0, false, false), 0, true);
        check(resting[2].order() == Order.RECALL && !resting[2].enabled() && resting[2].reason() == Reason.REST, "a defeated partner shows its rest");
        Module[] defeated = CommandWheelReadout.modules(member(0, 24, false, 0, 0, "RESTING", 0, false, false), 0, true);
        check(defeated[3].reason() == Reason.DEFEATED && defeated[0].reason() == Reason.DEFEATED, "defeated without rest owed says DEFEATED");

        Module[] evolved = CommandWheelReadout.modules(member(40, 24, true, 0, 1680, "EVOLVED", 0, false, false), 0, true);
        check(evolved[3].order() == Order.REVERT && evolved[3].enabled(), "an evolved partner is offered Revert");
        Module[] rootless = CommandWheelReadout.modules(new PartyMemberView(UUID.randomUUID(), Constants.id("greymon"), "", 40, 44, 24, 0, 0, true, 0,
                1680, "EVOLVED", 0, false, "", false, 0, 0, false, false), 0, true);
        check(rootless[3].order() == Order.REVERT && !rootless[3].enabled() && rootless[3].reason() == Reason.NEEDS_ORIGIN,
                "a Champion brought out in creative with no Rookie behind it has no Revert");
        check(evolved[2].order() == Order.RECALL && evolved[2].enabled(), "an evolved partner can be recalled");
        Module[] evolving = CommandWheelReadout.modules(member(20, 24, true, 0, 3600, "EVOLVING", 0, false, false), 0, true);
        check(!evolving[3].enabled() && evolving[3].reason() == Reason.BUSY, "no evolution order mid-transformation");
        check(!evolving[2].enabled() && evolving[2].reason() == Reason.BUSY, "no recall mid-transformation");

        check(evolutionReason(member(20, 19, true, 0, 3600, "RESTING", 0, false, false), 0, true) == Reason.NEEDS_LEVEL, "below level 20: NEEDS_LEVEL");
        check(evolutionReason(member(20, 24, true, 0, 3600, "RESTING", 0, false, false), 0, false) == Reason.NO_ROUTE, "no supported route: NO_ROUTE");
        check(evolutionReason(member(20, 24, true, 0, 3600, "RESTING", 200, false, false), 0, true) == Reason.COOLDOWN, "cooldown blocks Digivolve");
        check(evolutionReason(member(20, 24, true, 0, 3600, "RESTING", 200, false, false), 400, true) == Reason.COOLDOWN, "cooldown holds until the server clears it");
        check(evolutionReason(member(20, 24, true, 0, Progression.DIGISOUL_MINIMUM - 1, "RESTING", 0, false, false), 0, true) == Reason.SOUL, "below the DigiSoul minimum: SOUL");
        check(evolutionReason(member(20, 24, true, 0, Progression.DIGISOUL_MINIMUM, "RESTING", 0, false, false), 0, true) == Reason.NONE, "at the DigiSoul minimum: available");
        check(evolutionReason(new PartyMemberView(UUID.randomUUID(), Constants.id("greymon"), "", 40, 44, 24, 0, 0, true, 0,
                3600, "RESTING", 0, true, "", false, 0, 0, false, false), 0, true) == Reason.NEEDS_ORIGIN, "origin required: NEEDS_ORIGIN");

        check(Order.STAND_STILL.action() == PartyActionPayload.HOLD && Order.FOLLOW.action() == PartyActionPayload.FOLLOW
                && Order.CANCEL_TARGET.action() == PartyActionPayload.CANCEL_TARGET && Order.RECALL.action() == PartyActionPayload.RECALL
                && Order.DIGIVOLVE.action() == PartyActionPayload.EVOLVE
                && Order.REVERT.action() == PartyActionPayload.REVERT, "every order maps to its payload action");
        check(Order.DIGIVOLVE.evolution() && Order.REVERT.evolution() && !Order.RECALL.evolution(), "only evolution orders carry an intent");

        // Pointing, from the wheel's centre: the panel is the dead zone, its tiles and switches the targets in it.
        check(CommandWheelReadout.pick(0, 0, 2, true).target() == Target.NOTHING, "the centre of the panel points at nothing");
        check(CommandWheelReadout.pick(-50, -50, 2, true).target() == Target.NOTHING, "nor does the rest of the panel");
        check(CommandWheelReadout.pick(-27, 10, 2, true).is(Target.TILE, 0) && CommandWheelReadout.pick(26, 33, 2, true).is(Target.TILE, 1),
                "the two tiles sit side by side under the name");
        check(CommandWheelReadout.pick(-15, 40, 2, true).is(Target.SWITCH, 0) && CommandWheelReadout.pick(10, 46, 2, true).is(Target.SWITCH, 1),
                "each AUTO switch sits under its tile");
        check(CommandWheelReadout.pick(-15, 40, 2, false).target() == Target.NOTHING, "a ridden partner's tiles have no switches");
        check(CommandWheelReadout.pick(-12, 20, 0, true).target() == Target.NOTHING, "no attacks, no tiles");
        check(CommandWheelReadout.pick(-59, -1, 2, true).is(Target.KEY, CommandWheelReadout.TOP_LEFT), "just past the panel's left edge, up: the top left key");
        check(CommandWheelReadout.pick(200, -3, 2, true).is(Target.KEY, CommandWheelReadout.TOP_RIGHT), "far right, slightly up: the whole quarter counts");
        check(CommandWheelReadout.pick(-1, 61, 2, true).is(Target.KEY, CommandWheelReadout.BOTTOM_LEFT), "below the panel and barely left");
        check(CommandWheelReadout.pick(30, 120, 2, true).is(Target.KEY, CommandWheelReadout.BOTTOM_RIGHT), "down and right, past the Digivice key");
        check(CommandWheelReadout.pick(0, 80, 2, true).target() == Target.DIGIVICE && CommandWheelReadout.pick(-50, 66, 2, true).target() == Target.DIGIVICE,
                "the Digivice key under the panel wins over its quarters, with a little reach");
        check(!CommandWheelReadout.overDigivice(0, 60) && !CommandWheelReadout.overDigivice(60, 80), "but not beyond it");

        check(CommandWheelReadout.tileX(0, 2) == -27 && CommandWheelReadout.tileX(1, 2) == 3 && CommandWheelReadout.tileX(0, 1) == -12, "tiles are centred in the panel, 6 apart");
        check(CommandWheelReadout.keyX(CommandWheelReadout.TOP_LEFT) == -134 && CommandWheelReadout.keyX(CommandWheelReadout.BOTTOM_RIGHT) == 66
                && CommandWheelReadout.keyY(CommandWheelReadout.TOP_RIGHT) == -54 && CommandWheelReadout.keyY(CommandWheelReadout.BOTTOM_LEFT) == 6,
                "the keys stand 8 off the panel's sides, in two rows");
        int reach = CommandWheelReadout.HALF_WIDTH + CommandWheelReadout.STEP_OUT;
        check(CommandWheelReadout.centerX(480, 101) == 240, "at 480 units wide (1080p) the wheel stays centred");
        int shifted = CommandWheelReadout.centerX(426, 108);
        check(shifted - reach >= 108 + CommandWheelReadout.STRIP_CLEAR && shifted + reach <= 426 - CommandWheelReadout.EDGE_CLEAR,
                "at 426 units wide (4K, 1440p, 720p) it moves right, clear of the party strip and inside the screen: " + shifted);
        check(CommandWheelReadout.centerX(320, 96) + reach <= 320 - CommandWheelReadout.EDGE_CLEAR, "on the narrowest screen it never leaves the right edge");

        // Attack orders: a target (its own, or an enemy on the crosshair) and a move within two seconds of ready.
        PartyMemberView calm = member(20, 24, true, 0, 3600, "RESTING", 0, false, false);
        PartyMemberView fighting = member(20, 24, true, 0, 3600, "RESTING", 0, false, true);
        check(CommandWheelReadout.attackRefusal(calm, false, 0) == Refusal.NO_TARGET, "no target and nothing on the crosshair: NO TARGET, nothing sent");
        check(CommandWheelReadout.attackRefusal(calm, true, 0) == Refusal.NONE, "an enemy on the crosshair is enough");
        check(CommandWheelReadout.attackRefusal(fighting, false, DigimonEntity.ORDER_GRACE_TICKS) == Refusal.NONE, "an order within two seconds of ready goes and waits");
        check(CommandWheelReadout.attackRefusal(fighting, false, DigimonEntity.ORDER_GRACE_TICKS + 1) == Refusal.COOLING, "further out it is refused");
        check(CommandWheelReadout.attackRefusal(fighting, false, -1) == Refusal.NONE, "a partner out of sight is left to the server");
        check(CommandWheelReadout.attackRefusal(member(20, 24, false, 0, 3600, "RESTING", 0, false, false), true, 0) == Refusal.AWAY,
                "nothing is ordered to a partner that is not out");
        // A move behind a gauge (Beast King Fist): refused while it charges, whatever its clock says; full, it goes.
        check(CommandWheelReadout.attackRefusal(fighting, false, 0, .6F) == Refusal.CHARGING
                && CommandWheelReadout.attackRefusal(fighting, false, 0, 1) == Refusal.NONE
                && CommandWheelReadout.attackRefusal(fighting, false, 0, -1) == Refusal.NONE
                && CommandWheelReadout.attackRefusal(calm, false, 0, .6F) == Refusal.NO_TARGET, "a gauge still charging refuses the order");
        check(CommandWheelReadout.percent(.6F) == 60 && CommandWheelReadout.percent(.999F) == 99 && CommandWheelReadout.percent(1) == 100
                && CommandWheelReadout.percent(0) == 0, "the charging readout's percentage, never 100 before full");
        check(CommandWheelReadout.ATTACK_KEYS.length == 2 && CommandWheelReadout.ATTACK_KEYS[0].equals("Q") && CommandWheelReadout.ATTACK_KEYS[1].equals("E"),
                "Q casts the first attack and E the second, on foot as in the saddle");
        check(CommandWheelReadout.soulPercent(650) == 18 && CommandWheelReadout.soulPercent(3600) == 100 && CommandWheelReadout.soulPercent(0) == 0, "DigiSoul percentage");

        for (Order order : Order.values()) {
            String[] rows = CommandIcons.rows(order);
            boolean square = rows.length == CommandIcons.SIZE;
            for (String row : rows) square &= row.length() == CommandIcons.SIZE;
            check(square, order + " icon is 20 x 20");
        }
        for (DigimonAttack.Kind kind : DigimonAttack.Kind.values()) {
            String[] rows = CommandIcons.glyph(kind);
            boolean square = rows.length == CommandIcons.GLYPH;
            for (String row : rows) square &= row.length() == CommandIcons.GLYPH;
            check(square, kind + " placeholder glyph is 16 x 16");
        }

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " command wheel check(s) failed");
            System.exit(1);
        }
        System.out.println("PASS: command wheel rules hold");
    }

    private static Reason evolutionReason(PartyMemberView member, int age, boolean route) {
        return CommandWheelReadout.modules(member, age, route)[CommandWheelReadout.BOTTOM_RIGHT].reason();
    }

    private static PartyMemberView member(float health, int level, boolean deployed, int restTicks, int soul, String phase, int cooldown,
                                          boolean holding, boolean attacking) {
        return new PartyMemberView(UUID.randomUUID(), Constants.id("agumon"), "", health, 44, level, 0, 0, deployed, restTicks,
                soul, phase, cooldown, false, "EVOLVED".equals(phase) ? "digicube:agumon" : "", true, 0, 0, holding, attacking);
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            System.out.println("ok: " + message);
        } else {
            failures++;
            System.err.println("FAIL: " + message);
        }
    }
}
