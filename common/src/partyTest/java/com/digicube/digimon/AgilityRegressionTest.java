package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.util.GsonHelper;

import java.util.Map;

/**
 * The agility data: a body's own leap, step and crouch with its roll ({@code body.leap}, {@code body.step_height},
 * {@code body.crouch}), the tactics that duck and leap clear ({@code duck_chance}, {@code leap_dodge}), a kinetic shot's
 * {@code falloff} and a blow's {@code launch}: their defaults, what they are worth, and the sheets they refuse.
 */
public final class AgilityRegressionTest {
    private AgilityRegressionTest() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void rejects(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static DigimonSpecies sheet(String body) {
        return sheet(body, "{}");
    }

    private static DigimonSpecies sheet(String body, String tactics) {
        String json = "{\"stage\":\"adult\",\"attribute\":\"vaccine\",\"base_health\":40,\"base_attack\":14,\"base_defence\":10,"
                + "\"base_speed\":0.22,\"attacks\":[],\"evolutions\":[],\"body\":" + body + ",\"tactics\":" + tactics + "}";
        return BundledSpeciesLoader.parse(Constants.id("agility_fixture"), GsonHelper.parse(json), Map.of());
    }

    private static final String STANDING = "\"model_scale\":0.32,\"width\":1.2,\"height\":2.75,\"eye_height\":2.45";

    public static void run() {
        // A body with none of it: vanilla's step, no leap, no crouch.
        var plain = sheet("{" + STANDING + "}").body();
        check(plain.stepHeight() == 0 && plain.leap() == null && plain.crouch() == null, "a sheet without agility keeps vanilla's step and has no leap or crouch");

        // Every key given.
        var full = sheet("{" + STANDING + ",\"step_height\":1.0,\"leap\":{\"jump\":0.62,\"carry\":0.95},"
                + "\"crouch\":{\"height\":1.95,\"eye_height\":1.65,\"pace\":0.5,\"roll\":{\"ticks\":15,\"height\":1.3,\"eye_height\":1.0,"
                + "\"low_from\":2,\"low_until\":11,\"push\":0.06,\"keep\":0.96,\"from\":0.18,\"speed\":0.44}}}").body();
        var roll = full.crouch().roll();
        check(full.stepHeight() == 1.0F && full.leap().jump() == .62F && full.leap().carry() == .95F, "body.leap and body.step_height as written");
        check(full.crouch().height() == 1.95F && full.crouch().eyeHeight() == 1.65F && full.crouch().pace() == .5F, "body.crouch as written");
        check(roll.ticks() == 15 && roll.height() == 1.3F && roll.eyeHeight() == 1.0F && roll.lowFrom() == 2 && roll.lowUntil() == 11
                && roll.push() == .06F && roll.keep() == .96F && roll.from() == .18F && roll.speed() == .44F, "body.crouch.roll as written");
        check(!roll.tucked(1) && roll.tucked(2) && roll.tucked(10) && !roll.tucked(11), "the roll tucks from low_from up to, not including, low_until");

        // Defaults: the crouched eye lowered with the box, a roll of 16 ticks tucked over its middle, its run kept.
        var defaults = sheet("{" + STANDING + ",\"leap\":{\"jump\":0.5},\"crouch\":{\"height\":1.5,\"roll\":{\"height\":1.2}}}").body();
        var defaultRoll = defaults.crouch().roll();
        check(defaults.leap().carry() == 0 && Math.abs(defaults.crouch().eyeHeight() - 2.45F * 1.5F / 2.75F) < 1.0E-5 && defaults.crouch().pace() == .45F,
                "a leap without carry leaves the air to vanilla; the crouched eye drops with the box; the crouched walk is .45 of the pace");
        check(defaultRoll.ticks() == 16 && defaultRoll.lowFrom() == 3 && defaultRoll.lowUntil() == 12 && defaultRoll.push() == .05F
                && defaultRoll.keep() == .97F && defaultRoll.from() == 0 && defaultRoll.speed() == 0 && Math.abs(defaultRoll.eyeHeight() - defaults.crouch().eyeHeight() * 1.2F / 1.5F) < 1.0E-5,
                "a roll's defaults: 16 ticks, tucked from 3 to 12, a .05 push, .97 kept a tick, the gait's run_from");

        // What a sheet may not say.
        rejects(() -> sheet("{" + STANDING + ",\"crouch\":{\"height\":2.75}}"), "a crouch no lower than the standing body");
        rejects(() -> sheet("{" + STANDING + ",\"crouch\":{\"height\":1.9,\"eye_height\":2.0}}"), "a crouched eye above the crouched box");
        rejects(() -> sheet("{" + STANDING + ",\"crouch\":{\"height\":1.9,\"roll\":{\"height\":2.0}}}"), "a roll that tucks higher than the crouch");
        rejects(() -> sheet("{" + STANDING + ",\"crouch\":{\"height\":1.9,\"roll\":{\"height\":1.2,\"ticks\":10,\"low_from\":4,\"low_until\":12}}}"),
                "a tucked window past the roll's end");
        rejects(() -> sheet("{" + STANDING + ",\"leap\":{\"jump\":0}}"), "a leap with no jump");
        rejects(() -> sheet("{" + STANDING + ",\"leap\":{\"jump\":0.6,\"carry\":1}}"), "a leap that keeps all its run forever");
        rejects(() -> sheet("{" + STANDING + ",\"step_height\":-1}"), "a negative step");

        // Tactics: the duck's chance and the leap clear of a ground wave.
        var tactics = sheet("{" + STANDING + "}", "{\"duck_chance\":0.6,\"leap_dodge\":true}").tactics();
        check(tactics.duckChance() == .6F && tactics.leapDodge(), "duck_chance and leap_dodge as written");
        check(DigimonTactics.DEFAULT.duckChance() == 0 && !DigimonTactics.DEFAULT.leapDodge(), "no species ducks or leaps clear unless its sheet says so");
        rejects(() -> sheet("{" + STANDING + "}", "{\"duck_chance\":1.5}"), "a duck chance past 1");
        // ... the dodge roll and the fighter's footwork in its band.
        var fighter = sheet("{" + STANDING + "}", "{\"roll_dodge\":0.35,\"footwork\":true,\"stalk\":0.45,\"spacing\":0.6}").tactics();
        check(fighter.rollDodge() == .35F && fighter.footwork() && fighter.stalk() == .45F && fighter.spacing() == .6F,
                "roll_dodge, footwork, stalk and spacing as written");
        check(DigimonTactics.DEFAULT.rollDodge() == 0 && !DigimonTactics.DEFAULT.footwork() && DigimonTactics.DEFAULT.stalk() == 0
                && DigimonTactics.DEFAULT.spacing() == 0, "no species rolls away, keeps footwork, stalks or spaces its combos unless its sheet says so");
        rejects(() -> sheet("{" + STANDING + "}", "{\"roll_dodge\":1.2}"), "a roll dodge chance past 1");
        rejects(() -> sheet("{" + STANDING + "}", "{\"stalk\":-0.1}"), "a negative stalk");

        // A shot's falloff: whole to near, falling in a straight line to its far shares, held past far.
        var falloff = KineticAttacks.falloff(GsonHelper.parse("{\"falloff\":{\"near\":4,\"far\":18,\"power\":0.45,\"knockback\":0.3}}"));
        check(falloff.power(0) == 1 && falloff.power(4) == 1 && Math.abs(falloff.power(11) - (1 - .55F * .5F)) < 1.0E-6
                && Math.abs(falloff.power(18) - .45F) < 1.0E-6 && Math.abs(falloff.power(40) - .45F) < 1.0E-6, "falloff's damage share near, half way, far and past it");
        check(Math.abs(falloff.knockback(11) - (1 - .7F * .5F)) < 1.0E-6 && Math.abs(falloff.knockback(18) - .3F) < 1.0E-6, "falloff's push share");
        var unpushed = KineticAttacks.falloff(GsonHelper.parse("{\"falloff\":{\"near\":2,\"far\":10,\"power\":0.5}}"));
        check(unpushed.knockback(10) == unpushed.power(10), "a falloff without knockback falls its push with its damage");
        check(KineticAttacks.falloff(GsonHelper.parse("{}")) == null, "a shot without falloff strikes whole at any range");
        rejects(() -> KineticAttacks.falloff(GsonHelper.parse("{\"falloff\":{\"near\":8,\"far\":8,\"power\":0.5}}")), "a falloff ending where it starts");
        rejects(() -> KineticAttacks.falloff(GsonHelper.parse("{\"falloff\":{\"near\":2,\"far\":8,\"power\":1.5}}")), "a falloff that grows");

        // A launch: along the blow and up, in place of vanilla's push.
        var launch = Launch.parse(GsonHelper.parse("{\"launch\":{\"speed\":1.2,\"lift\":0.5}}"));
        check(launch.speed() == 1.2F && launch.lift() == .5F && Launch.parse(GsonHelper.parse("{\"launch\":{\"speed\":0.8}}")).lift() == 0,
                "launch as written, no lift by default");
        check(Launch.parse(GsonHelper.parse("{}")) == null, "a blow without launch pushes as ever");
        rejects(() -> Launch.parse(GsonHelper.parse("{\"launch\":{\"speed\":0}}")), "a launch that throws nothing");

        // The catalogs: the existing moves keep their plain push and whole shots.
        for (var definition : KineticAttacks.all()) if (definition.attack().id().getPath().matches("hunting_cannon|mega_flame|mega_blaster|water_shot"))
            check(definition.falloff() == null && definition.launch() == null, definition.attack().id() + " keeps its whole shot and plain push");
        var fang = PounceAttacks.get(PounceAttacks.attacks().stream().filter(a -> a.id().getPath().equals("freeze_fang")).findFirst().orElseThrow());
        check(fang.launch() == null && (fang.air() == null || fang.air().launch() == null), "Freeze Fang keeps its plain push");
        Constants.LOG.info("Agility data checks passed.");
    }
}
