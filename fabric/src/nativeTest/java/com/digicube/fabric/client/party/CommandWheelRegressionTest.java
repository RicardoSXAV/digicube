package com.digicube.fabric.client.party;

import com.digicube.Constants;
import com.digicube.digimon.Progression;
import com.digicube.fabric.client.party.CommandWheelReadout.Module;
import com.digicube.fabric.client.party.CommandWheelReadout.Order;
import com.digicube.fabric.client.party.CommandWheelReadout.Reason;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;

import java.util.UUID;

/**
 * Pins the command wheel's rules from {@code design/command-wheel.md}: which order each
 * sector offers, when it can be given and why not, and how the cursor picks a sector.
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

        Module[] stowed = CommandWheelReadout.modules(member(20, 24, false, 0, 3600, "RESTING", 0, false, false), 0, true);
        check(stowed[2].order() == Order.SEND_OUT && stowed[2].enabled(), "a partner in the Digivice is offered Send out");
        check(!stowed[0].enabled() && stowed[0].reason() == Reason.IN_DIGIVICE, "no behaviour orders in the Digivice");
        check(!stowed[1].enabled() && stowed[1].reason() == Reason.IN_DIGIVICE, "no target to cancel in the Digivice");
        check(!stowed[3].enabled() && stowed[3].reason() == Reason.IN_DIGIVICE, "no Digivolution in the Digivice");

        Module[] resting = CommandWheelReadout.modules(member(0, 24, false, 5440, 0, "RESTING", 0, false, false), 0, true);
        check(resting[2].order() == Order.SEND_OUT && !resting[2].enabled() && resting[2].reason() == Reason.REST, "a defeated partner cannot be sent out while it rests");
        Module[] defeated = CommandWheelReadout.modules(member(0, 24, false, 0, 0, "RESTING", 0, false, false), 0, true);
        check(defeated[3].reason() == Reason.DEFEATED && defeated[0].reason() == Reason.DEFEATED, "defeated without rest owed says DEFEATED");

        Module[] evolved = CommandWheelReadout.modules(member(40, 24, true, 0, 1680, "EVOLVED", 0, false, false), 0, true);
        check(evolved[3].order() == Order.REVERT && evolved[3].enabled(), "an evolved partner is offered Revert");
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
                && Order.CANCEL_TARGET.action() == PartyActionPayload.CANCEL_TARGET && Order.RECALL.action() == PartyActionPayload.STOW
                && Order.SEND_OUT.action() == PartyActionPayload.SEND_OUT && Order.DIGIVOLVE.action() == PartyActionPayload.EVOLVE
                && Order.REVERT.action() == PartyActionPayload.REVERT, "every order maps to its payload action");
        check(Order.DIGIVOLVE.evolution() && Order.REVERT.evolution() && !Order.RECALL.evolution(), "only evolution orders carry an intent");

        check(CommandWheelReadout.sector(0, 0) == CommandWheelReadout.NONE, "the centre selects nothing");
        check(CommandWheelReadout.sector(9, -9) == CommandWheelReadout.NONE, "inside the dead zone selects nothing");
        check(CommandWheelReadout.sector(-10, -10) == CommandWheelReadout.TOP_LEFT, "just outside the dead zone, up and left");
        check(CommandWheelReadout.sector(200, -3) == CommandWheelReadout.TOP_RIGHT, "far right, slightly up: the whole quarter counts");
        check(CommandWheelReadout.sector(-1, 120) == CommandWheelReadout.BOTTOM_LEFT, "down and barely left");
        check(CommandWheelReadout.sector(30, 30) == CommandWheelReadout.BOTTOM_RIGHT, "down and right");

        check(CommandWheelReadout.moduleX(CommandWheelReadout.TOP_LEFT, 240) == 114 && CommandWheelReadout.moduleX(CommandWheelReadout.BOTTOM_RIGHT, 240) == 248, "columns sit 16 units apart around the centre");
        check(CommandWheelReadout.moduleY(CommandWheelReadout.TOP_RIGHT, 135) == 71 && CommandWheelReadout.moduleY(CommandWheelReadout.BOTTOM_LEFT, 135) == 167, "rows leave a 64-unit band for the hub");
        check(CommandWheelReadout.soulPercent(650) == 18 && CommandWheelReadout.soulPercent(3600) == 100 && CommandWheelReadout.soulPercent(0) == 0, "DigiSoul percentage");

        for (Order order : Order.values()) {
            String[] rows = CommandIcons.rows(order);
            boolean square = rows.length == CommandIcons.SIZE;
            for (String row : rows) square &= row.length() == CommandIcons.SIZE;
            check(square, order + " icon is 20 x 20");
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
                soul, phase, cooldown, false, "", true, 0, 0, holding, attacking);
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
