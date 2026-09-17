package com.digicube.fabric.client.party;

import com.digicube.Constants;
import com.digicube.digimon.Progression;
import com.digicube.fabric.client.party.PartyHudReadout.Status;
import com.digicube.party.PartyMemberView;

import java.util.UUID;

/**
 * Pins the party strip's readout rules from {@code design/party-hud-strip.md}: the status
 * priority, the local countdowns, the DigiSoul cell and the stack layout. No game, no
 * window, no test framework.
 */
public final class PartyHudRegressionTest {
    private PartyHudRegressionTest() {}

    private static int failures;

    public static void main(String[] args) {
        PartyMemberView healthy = member(17, 20, 12, true, 0, 0, "RESTING", 0);
        check(PartyHudReadout.status(healthy, 0, false) == Status.STAGE, "healthy deployed partner shows its stage");
        check(PartyHudReadout.soulLocked(healthy), "DigiSoul locked below the champion level");
        check(PartyHudReadout.status(member(0, 20, 12, true, 5440, 0, "RESTING", 0), 0, false) == Status.REST, "defeated with rest owed shows REST");
        check(PartyHudReadout.status(member(0, 20, 12, true, 0, 0, "RESTING", 0), 0, false) == Status.DEFEATED, "defeated without rest shows DEFEATED");
        check(PartyHudReadout.status(member(20, 20, 24, false, 0, 3600, "EVOLVED", 0), 0, true) == Status.SOUL, "evolved beats waiting");
        check(PartyHudReadout.status(member(20, 20, 24, true, 0, 0, "EVOLVING", 0), 0, true) == Status.EVOLVING, "evolving code");
        check(PartyHudReadout.status(member(20, 20, 24, true, 0, 0, "REVERTING", 0), 0, true) == Status.REVERTING, "reverting code");
        check(PartyHudReadout.status(member(20, 20, 9, false, 0, 0, "RESTING", 0), 0, false) == Status.WAITING, "not deployed shows WAITING");
        check(PartyHudReadout.status(member(20, 20, 24, true, 0, 3600, "RESTING", 200), 0, true) == Status.COOLDOWN, "cooldown beats ready");
        check(PartyHudReadout.status(member(20, 20, 24, true, 0, 3600, "RESTING", 200), 400, true) == Status.COOLDOWN, "cooldown holds at one tick until the server clears it");

        PartyMemberView ready = member(20, 20, 24, true, 0, 3600, "RESTING", 0);
        check(PartyHudReadout.status(ready, 0, true) == Status.READY, "full soul, deployed, resting, route: READY");
        check(PartyHudReadout.status(ready, 0, false) == Status.STAGE, "no route: never READY");
        check(PartyHudReadout.status(member(20, 20, 24, true, 0, Progression.DIGISOUL_MINIMUM - 1, "RESTING", 0), 0, true) == Status.STAGE, "below the minimum: not ready");
        check(PartyHudReadout.status(member(20, 20, 24, true, 0, Progression.DIGISOUL_MINIMUM, "RESTING", 0), 0, true) == Status.READY, "at the minimum: ready");
        check(PartyHudReadout.status(member(20, 20, 19, true, 0, 3600, "RESTING", 0), 0, true) == Status.STAGE, "below level 20: not ready");
        check(PartyHudReadout.status(new PartyMemberView(UUID.randomUUID(), Constants.id("greymon"), "", 40, 44, 24, 0, 0, true, 0,
                3600, "RESTING", 0, true, "", false, 0, 0), 0, true) == Status.STAGE, "origin required: not ready");

        PartyMemberView evolved = member(40, 44, 24, true, 0, 1680, "EVOLVED", 0);
        check(PartyHudReadout.soul(evolved, 0) == 1680 && PartyHudReadout.soul(evolved, 100) == 1580, "evolved soul drains one per tick locally");
        check(PartyHudReadout.soul(evolved, 5000) == 0, "drained soul clamps at zero");
        check(PartyHudReadout.soul(ready, 100) == 3600, "resting soul does not drain with age");
        PartyMemberView resting = member(0, 24, 11, true, 5440, 0, "RESTING", 0);
        check(PartyHudReadout.restTicks(resting, 40) == 5400, "rest counts down locally");
        check(PartyHudReadout.restTicks(resting, 6000) == PartyHudReadout.HOLD_TICKS, "rest holds at one second, never zero");
        check(PartyHudReadout.restTicks(healthy, 40) == 0, "no rest owed reads zero");

        check(PartyHudReadout.healthFraction(healthy) == 0.85F, "health fraction");
        check(PartyHudReadout.healthFraction(member(5, 0, 1, true, 0, 0, "RESTING", 0)) == 0, "zero max health is safe");
        check(PartyHudReadout.xpFraction(member(20, 20, Progression.LEVEL_CAP, true, 0, 0, "RESTING", 0)) == 1.0F, "xp rail full at the cap");
        PartyMemberView half = new PartyMemberView(UUID.randomUUID(), Constants.id("agumon"), "", 20, 20, 10, Progression.xpToNext(10) / 2, 0, true, 0);
        check(PartyHudReadout.xpFraction(half) == 0.5F, "xp rail at half");

        check(PartyHudReadout.soulSegments(Progression.DIGISOUL_CAPACITY) == 8.0F, "full soul lights eight segments");
        check(PartyHudReadout.soulSegments(Progression.DIGISOUL_MINIMUM) == 2.0F, "the activation minimum is exactly two segments");
        check(PartyHudReadout.minimumSegments() == 2, "the divider sits above segment two");
        check(PartyHudReadout.soulSegments(650) > 1 && PartyHudReadout.soulSegments(650) < 2, "18 percent lights one segment and part of the next");

        check(PartyHudReadout.stackHeight(3, 0, true) == 146, "three partners with the header: 146 units");
        check(PartyHudReadout.stackHeight(1, 2, true) == 90, "one partner and two stubs: 90 units");
        check(PartyHudReadout.stackHeight(3, 0, false) == 134, "without the header: 134 units");
        check(PartyHudReadout.stackHeight(0, 0, true) == PartyHudReadout.HEADER_HEIGHT, "empty stack is just the header");

        check(PartyHudReadout.stripScale(3) == 2.5F / 3 && PartyHudReadout.stripScale(4) == 0.875F, "strip is half a pixel per unit smaller from GUI scale 3 up");
        check(PartyHudReadout.stripScale(2) == 1.0F && PartyHudReadout.stripScale(1) == 1.0F, "strip keeps full size at small GUI scales");

        boolean[] full = {true, true, true};
        boolean[] gap = {true, false, true};
        boolean[] one = {false, true, false};
        boolean[] none = {false, false, false};
        check(PartyHudReadout.nextSelection(full, 0, 1) == 1 && PartyHudReadout.nextSelection(full, 1, 1) == 2, "down moves to the next slot");
        check(PartyHudReadout.nextSelection(full, 2, 1) == 0, "down wraps from the last slot to the first");
        check(PartyHudReadout.nextSelection(full, 0, -1) == 2, "up wraps from the first slot to the last");
        check(PartyHudReadout.nextSelection(gap, 0, 1) == 2 && PartyHudReadout.nextSelection(gap, 2, -1) == 0, "empty slots are skipped both ways");
        check(PartyHudReadout.nextSelection(one, 1, 1) == 1 && PartyHudReadout.nextSelection(one, 1, -1) == 1, "a single partner stays selected");
        check(PartyHudReadout.nextSelection(none, 0, 1) == 0, "an empty party leaves the selection alone");
        check(PartyHudReadout.normalizeSelection(gap, 1) == 2, "a selection on a slot that emptied moves to the next filled one");
        check(PartyHudReadout.normalizeSelection(gap, 2) == 2, "a selection on a filled slot stays");
        check(PartyHudReadout.normalizeSelection(one, 2) == 1, "normalizing wraps to the only partner");
        check(PartyHudReadout.normalizeSelection(none, 1) == 1, "normalizing an empty party changes nothing");

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " party HUD check(s) failed");
            System.exit(1);
        }
        System.out.println("PASS: party HUD readout rules hold");
    }

    private static PartyMemberView member(float health, float maxHealth, int level, boolean deployed, int restTicks, int soul, String phase, int cooldown) {
        return new PartyMemberView(UUID.randomUUID(), Constants.id("agumon"), "", health, maxHealth, level, 0, 0, deployed, restTicks,
                soul, phase, cooldown, false, "", true, 0, 0);
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
