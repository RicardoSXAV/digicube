package com.digicube.digimon;

import java.util.UUID;

/** Combat decisions and contact accounting at the freeze threshold. */
final class IceComboRegressionTest {
    private IceComboRegressionTest() {}

    static void run() {
        var breath = DigimonSpeciesBootstrap.HOWLING_BLASTER;
        int required = IceCombo.requiredContactTicks(breath.fuel());
        check(required == 20 && IceCombo.requiredContactTicks(new AttackFuel(81, 160, 10)) == 20,
                "one second of contact is independent of tank size");
        check(IceCombo.comboFuelTicks(breath.fuel()) == 28, "reserve includes eight frames of travel/miss allowance");
        check(IceCombo.choose(true, true, true, true, false, false, false, true, true) == IceCombo.Choice.BITE,
                "set up a mark even when the big move is ready");
        check(IceCombo.choose(true, true, true, true, true, false, false, true, true) == IceCombo.Choice.BREATH,
                "cash in an existing mark instead of repeating the bite");
        check(IceCombo.choose(true, false, true, true, false, false, false, true, true) == IceCombo.Choice.APPROACH,
                "approach a nearby target for the combo");
        check(IceCombo.choose(true, false, true, true, false, false, false, true, false) == IceCombo.Choice.BREATH,
                "use range against distant or vertically inaccessible enemies");
        check(IceCombo.choose(true, true, true, true, false, false, false, true, false) == IceCombo.Choice.BITE,
                "a target already in fang reach needs no new navigation path");
        check(IceCombo.choose(true, true, true, true, true, false, false, false, true) == IceCombo.Choice.BITE,
                "keep a partial tank refilling while using the basic attack");
        check(IceCombo.choose(true, true, true, true, false, false, true, true, true) == IceCombo.Choice.BITE,
                "bite during resistance while saving fuel for the next combo");
        check(IceCombo.choose(true, true, false, true, true, false, false, true, true) == IceCombo.Choice.BITE,
                "exhaustion lock still permits the basic attack");
        check(IceCombo.choose(true, true, true, false, true, false, false, true, true) == IceCombo.Choice.BITE,
                "bite back when a marked enemy blocks the flame instead of stalling");
        check(IceCombo.choose(false, true, true, false, true, false, false, true, true) == IceCombo.Choice.APPROACH,
                "defensive bites still obey cooldown");
        check(IceCombo.choose(true, false, true, false, true, false, false, true, true) == IceCombo.Choice.APPROACH,
                "reposition when neither real attack can hit");
        check(IceCombo.choose(true, true, true, true, false, true, true, true, true) == IceCombo.Choice.BITE,
                "frozen prey takes priority over both resistance and available breath");
        check(IceCombo.choose(true, false, true, true, false, true, true, true, true) == IceCombo.Choice.APPROACH,
                "close for the shatter instead of starting another breath at frozen prey");
        check(IceCombo.choose(false, true, true, true, false, true, true, true, true) == IceCombo.Choice.APPROACH,
                "wait for a ready bite rather than waste the frozen opening on breath");
        check(IceCombo.choose(false, true, true, true, false, false, false, true, true) == IceCombo.Choice.APPROACH,
                "a short bite cooldown does not trigger a full unmarked breath");
        check(IceCombo.choose(true, false, true, true, false, false, false, false, false) == IceCombo.Choice.APPROACH,
                "do not repeatedly start tiny distant bursts with insufficient fuel");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var exposure = new IceExposure();
        for (int i = 0; i < required - 1; i++) {
            check(!exposure.touch(first, i, true, false, required), "less than one second does not freeze");
            check(!exposure.touch(first, i, true, false, required), "duplicate samples in a tick do not count");
            check(!exposure.touch(second, i, false, false, required), "unmarked targets never build exposure");
        }
        check(!new IceExposure().touch(first, 39, true, false, required), "another caster has its own ledger");
        check(exposure.touch(first, 55, true, false, required), "misses spend fuel but do not add contact ticks");
        check(!exposure.touch(first, 56, false, true, required), "consumed mark and resistance cannot refreeze");
        exposure.clear();
        check(!exposure.touch(first, 0, true, false, required), "new casts and interrupted casts start empty");
        for (int i = 1; i < 40; i++) check(!exposure.touch(first, i, true, true, required), "resistance rejects marks");
        check(!exposure.touch(first, 41, true, false, required), "resistance does not bank contact for later");
        var tank = new FuelReserve(breath.fuel());
        tank.begin();
        for (int i = 0; i < 41; i++) tank.consume();
        tank.end();
        check(tank.availableTicks() == 39, "partial tank reports exact emission ticks");
        tank.tickRecharge(); tank.tickRecharge();
        check(tank.availableTicks() == 40, "two resting ticks restore one emission tick");
        check(IceCombo.FREEZE_TICKS == 60 && IceCombo.RESISTANCE_TICKS == 140,
                "short freeze is followed by a longer recovery window");
        var bite = DigimonSpeciesBootstrap.FREEZE_FANG;
        check(bite.cooldownTicks() == bite.durationTicks() && bite.cooldownTicks() == 28,
                "the bite is ready as soon as its existing performance finishes");
        int recovery = breath.durationTicks() - breath.motion().activeUntil() - 1;
        check(recovery + bite.hitTick() + 30 < IceCombo.FREEZE_TICKS,
                "exhale plus thirty ticks of approach plus fang contact fit inside the frozen opening");
        check(IceCombo.biteMultiplier(true) == 1.5F && IceCombo.biteMultiplier(false) == 1,
                "only an actually frozen victim pays the shatter bonus");
        var comboTank = new FuelReserve(breath.fuel());
        comboTank.begin();
        var comboExposure = new IceExposure();
        int spent = 0;
        while (comboTank.consume()) {
            spent++;
            if (comboExposure.touch(first, spent, true, false, required)) {
                comboTank.end();
                break;
            }
        }
        check(spent == 20 && comboTank.availableTicks() == 60,
                "a clean freeze uses a quarter tank, leaving fuel for another target");
        for (int tick = 0; tick < recovery + bite.durationTicks(); tick++) comboTank.tickRecharge();
        check(comboTank.availableTicks() == 80 && comboTank.isReady(),
                "exhale and follow-up bite naturally refill a clean combo's fuel");
        check(bite.motion().sample(28).travel() > .9 && bite.knockback() == 0,
                "bite has authored forward travel without pushing its marked target away");
        var target = new net.minecraft.world.phys.AABB(-.3, 0, bite.range()-.3, .3, 1.8, bite.range()+.3);
        boolean reaches = false;
        for (double tick = bite.motion().activeFrom(); tick <= bite.motion().activeUntil(); tick += .25) {
            var frame = bite.motion().sample(tick);
            var start = frame.hornBase().add(0, 0, frame.travel());
            var end = frame.hornTip().add(0, 0, frame.travel());
            reaches |= target.inflate(bite.motion().contactRadius()).clip(start, end).isPresent();
        }
        check(reaches, "the advertised bite range must be reachable by its actual fang trajectory");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
