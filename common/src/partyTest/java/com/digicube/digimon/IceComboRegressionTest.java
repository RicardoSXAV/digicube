package com.digicube.digimon;

import com.digicube.Constants;
import com.digicube.entity.CombatMarkState;

/** Cold and Freeze: the chilling charge, the mark readouts, and Garurumon's pounce and breath as data. */
final class IceComboRegressionTest {
    private IceComboRegressionTest() {}

    static void run() {
        var ice = BreathAttacks.attacks().stream().filter(a -> a.id().getPath().equals("ice_blast")).findFirst().orElseThrow();
        check(IceCombo.COLD_CHARGE_TICKS == 20 && IceCombo.chillFuelTicks(ice.fuel()) == 28
                        && ice.fuel().capacityTicks() - IceCombo.chillFuelTicks(ice.fuel()) >= ice.fuel().damageIntervalTicks(),
                "a Cold charge costs a second of contact and one tank pays for it with a damage pulse to spare");
        int wrapExhale = ice.durationTicks() - ice.motion().activeUntil() - 1;
        check(wrapExhale + 8 + ConstrictionCoil.STRIKE_TICKS < IceCombo.COLD_TICKS,
                "exhale, alignment and the wrap's strike all fit inside one Cold");
        check(IceCombo.COLD_SLOW > -1 && IceCombo.COLD_SLOW < 0, "Cold slows and never stops");
        check(IceCombo.COLD_DECAY_DELAY_TICKS >= 2 * ice.fuel().damageIntervalTicks(), "a brief miss does not drain the charge");
        var tank = new FuelReserve(ice.fuel());
        tank.begin();
        for (int i = 0; i < 21; i++) tank.consume();
        tank.end();
        check(tank.availableTicks() == ice.fuel().capacityTicks() - 21, "partial tank reports exact emission ticks");
        // a tick of emission refills in rechargeTicks / capacityTicks resting ticks (an emptied tank in rechargeTicks)
        int rest = (int) Math.ceil(ice.fuel().rechargeTicks() / (double) ice.fuel().capacityTicks());
        for (int i = 0; i < rest; i++) tank.tickRecharge();
        check(tank.availableTicks() == ice.fuel().capacityTicks() - 20, "resting ticks restore emission ticks at the tank's refill rate");
        check(ice.fuel().capacityTicks() == 80 && ice.fuel().rechargeTicks() == 120, "Ice Blast breathes four seconds on a tank and refills in six");
        var empty = new FuelReserve(ice.fuel());
        empty.begin();
        while (empty.consume()) {}
        empty.end();
        check(empty.isRecharging() && empty.fill() < .01F, "an emptied tank is locked until it refills");
        for (int i = 0; i < ice.fuel().rechargeTicks() / 2; i++) empty.tickRecharge();
        check(empty.isRecharging() && Math.abs(empty.fill() - .5F) < .01F, "half way through its refill it shows half full, still locked");
        for (int i = 0; i < ice.fuel().rechargeTicks() / 2; i++) empty.tickRecharge();
        check(!empty.isRecharging() && empty.isReady() && empty.fill() == 1, "refilled, it fires again");

        int marks = CombatMarkState.pack(false, 10, 118, .5F, 2, .5F);
        check(!CombatMarkState.has(marks, CombatMarkState.HELD) && CombatMarkState.has(marks, CombatMarkState.INKED)
                        && CombatMarkState.coldCharge(marks) == .5F
                        && CombatMarkState.coldRemainingTicks(marks) == 120
                        && Math.abs(CombatMarkState.inkRemaining(marks) - .5F) < .01F
                        && Math.abs(CombatMarkState.crackCharge(marks) - 2F / CrackMark.CHARGES) < .001F
                        && Math.abs(CombatMarkState.crackedRemaining(marks) - .5F) < .01F
                        && marks > 0,
                "the tracked readout carries flags, half a charge, remaining Cold rounded up to its step, half an ink, two Crack charges and half a Cracked");
        check(CrackMark.charges(DigimonSpeciesBootstrap.ROCK_PUNCH) == 1 && CrackMark.charges(DigimonSpeciesBootstrap.TECTONIC_FIST) == 2
                        && CrackMark.charges(ice) == 0 && CrackMark.CHARGES == 3 && CrackMark.DAMAGE_TAKEN > 1,
                "stone blows fill the Crack gauge (punch one, spikes two of three), other attacks do not");
        check(!CombatMarkState.has(CombatMarkState.pack(false, 0, 0, 0, 0, 0), CombatMarkState.INKED), "no ink, no Inked flag");
        int readout2 = CombatMarkState.pack2(.5F, true, 0);
        check(Math.abs(CombatMarkState.exposedRemaining(readout2) - .5F) < .01F
                        && CombatMarkState.has(readout2, CombatMarkState.EXPOSED_FLASH)
                        && CombatMarkState.exposedRemaining(CombatMarkState.pack2(.001F, false, 0)) > 0
                        && CombatMarkState.pack2(0, true, 0) == 0,
                "the second readout carries half an Exposed and its crit blink; its last tick still shows; no Exposed, no blink");
        int burning = CombatMarkState.pack2(0, false, .25F);
        check(Math.abs(CombatMarkState.burnRemaining(burning) - .25F) < .01F
                        && CombatMarkState.exposedRemaining(burning) == 0
                        && CombatMarkState.burnRemaining(CombatMarkState.pack2(1, true, .001F)) > 0
                        && CombatMarkState.exposedRemaining(CombatMarkState.pack2(1, true, 1)) == 1
                        && CombatMarkState.burnRemaining(CombatMarkState.pack2(1, true, 0)) == 0,
                "Burn rides beside Exposed in the second readout without touching it; its last tick still shows");
        check(ExposedMark.CRIT_BONUS == .30F && CriticalHits.BASE_CHANCE + ExposedMark.CRIT_BONUS < 1
                        && CriticalHits.ADVANTAGE_CHANCE + ExposedMark.CRIT_BONUS < 1,
                "Exposed turns a neutral 10 % crit chance into 40 %, and no roll becomes certain");

        int filling = CombatMarkState.pack2(0, false, 0, .5F, 0, false, false);
        check(Math.abs(CombatMarkState.freezeGauge(filling) - .5F) < .01F && CombatMarkState.frozenRemaining(filling) == 0
                        && !CombatMarkState.has(filling, CombatMarkState.FREEZE_FLASH) && !CombatMarkState.has(filling, CombatMarkState.FROST_RESIST)
                        && CombatMarkState.freezeGauge(CombatMarkState.pack2(0, false, 0, .001F, 0, false, false)) > 0,
                "the Freeze gauge rides in the second readout; its first touch already shows");
        int frozen = CombatMarkState.pack2(0, false, 0, 0, .4F, true, true);
        check(Math.abs(CombatMarkState.frozenRemaining(frozen) - .4F) < .01F && CombatMarkState.has(frozen, CombatMarkState.FREEZE_FLASH)
                        && !CombatMarkState.has(frozen, CombatMarkState.FROST_RESIST)
                        && CombatMarkState.has(CombatMarkState.pack2(0, false, 0, 0, 0, false, true), CombatMarkState.FROST_RESIST),
                "the ice and its blink show while Frozen; the resistance after it shows only once the ice is gone");
        int everything = CombatMarkState.pack2(1, true, 1, 1, 1, true, false);
        check(CombatMarkState.exposedRemaining(everything) == 1 && CombatMarkState.burnRemaining(everything) == 1
                        && CombatMarkState.freezeGauge(everything) == 1 && CombatMarkState.frozenRemaining(everything) == 1
                        && CombatMarkState.has(everything, CombatMarkState.EXPOSED_FLASH) && CombatMarkState.has(everything, CombatMarkState.FREEZE_FLASH)
                        && everything > 0,
                "Exposed, Burn and Freeze share the second readout without touching each other, bit 31 left free");

        var fang = PounceAttacks.get(DigimonSpeciesBootstrap.attacks().get(Constants.id("freeze_fang")));
        var blaster = BreathAttacks.get(DigimonSpeciesBootstrap.attacks().get(Constants.id("howling_blaster")));
        check(fang != null && blaster != null && fang.attack().kind() == DigimonAttack.Kind.POUNCE
                        && blaster.attack().kind() == DigimonAttack.Kind.FROST_STREAM && blaster.attack().fuel() != null,
                "Freeze Fang is a pounce and Howling Blaster a fueled breath of puffs");
        var bite = fang.attack();
        check(fang.charges() >= 2 && bite.cooldownTicks() >= bite.durationTicks(), "Freeze Fang stacks uses, each back after its cooldown");
        check(fang.startTick(false) == 0 && fang.startTick(true) == fang.gather() && fang.gather() + fang.burst() <= bite.durationTicks()
                        && bite.hitTick() <= fang.snap() && fang.snap() <= fang.contactUntil() && fang.contactUntil() < bite.durationTicks(),
                "a pounce in the air skips the gather, and the jaws shut inside the window they can strike in");
        check(bite.motion().activeFrom() <= bite.hitTick() && bite.motion().activeUntil() >= fang.snap(),
                "the clip's jaws are open over the whole strike window");
        boolean easing = Math.abs(fang.speedAt(0) - fang.speed()[0]) < 1e-6 && Math.abs(fang.speedAt(fang.burst() - 1) - fang.speed()[1]) < 1e-6;
        for (int i = 1; i < fang.burst(); i++) easing &= fang.speedAt(i) <= fang.speedAt(i - 1);
        check(easing, "the burst leaves at its opening speed and eases to its closing one");
        double reach = fang.distance() + bite.motion().sample(fang.snap()).hornTip().z;
        check(reach >= bite.range(), "the AI pounces only from where the dash and the jaws reach: " + reach + " of " + bite.range());
        check(fang.airPitch()[0] < fang.groundPitch()[0] && fang.airPitch()[1] > fang.groundPitch()[1]
                        && fang.pitch(-80, true) == fang.airPitch()[0] && fang.pitch(-80, false) == fang.groundPitch()[0],
                "from a leap the pounce dives steeper and rises higher than from the ground");
        check(fang.freeze() > 0 && fang.freeze() < FreezeMark.FULL && fang.shatter() >= 2,
                "one bite fills part of the Freeze gauge, and a bite on Frozen prey shatters it for at least double");
        check(fang.gather() + fang.burst() + 2 < FreezeMark.FROZEN_TICKS, "a pounce started on Frozen prey lands before the ice lets go");
        check(FreezeMark.RESIST_TICKS > FreezeMark.FROZEN_TICKS && FreezeMark.DRAIN > 0 && FreezeMark.HOLD_TICKS > 0,
                "the resistance after the ice outlasts it, and an untouched gauge drains after a moment");
        var breath = blaster.attack();
        check(breath.fuel().damageIntervalTicks() >= 10 && blaster.reach() >= breath.range() * .8,
                "the breath pulses its damage and its puffs fly most of its range");
        int toFreeze = (int) Math.ceil(FreezeMark.FULL / blaster.freeze());
        check(toFreeze <= breath.fuel().capacityTicks() && toFreeze < breath.motion().activeUntil() - breath.motion().activeFrom(),
                "one tank and one breath can fill a Freeze gauge");
        check((int) Math.ceil((FreezeMark.FULL - fang.freeze()) / blaster.freeze()) < toFreeze,
                "a bite first shortens the breath a freeze needs");
        check(blaster.twist() > 0 && blaster.twist() <= 110 && blaster.turn() > 0 && blaster.pitchTurn() > 0,
                "the neck turns the breath toward the aim, within its twist");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
