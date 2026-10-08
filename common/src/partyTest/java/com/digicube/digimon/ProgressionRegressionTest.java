package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Pins the balance tables of {@code Progression} (levels, XP and rest)
 * without a game, so a tuning change shows up here before it shows up in play.
 */
public final class ProgressionRegressionTest {
    private ProgressionRegressionTest() {}

    /** @param args unused */
    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            checkCurve();
            checkYield();
            checkGap();
            checkSplit();
            checkGain();
            checkStats();
            checkReserveRegen();
            checkFeeding();
            checkDrops();
            checkLedger();
            Constants.LOG.info("Progression regression checks passed: curve, yield, gap, damage split, level-ups, stats, reserve regeneration, feeding, drops and ledger.");
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static void checkCurve() {
        Map<Integer, Integer> expected = Map.of(1, 54, 2, 72, 3, 94, 4, 120, 5, 150, 10, 360, 15, 670, 20, 1080, 30, 2200, 40, 3720);
        expected.forEach((level, xp) -> check(Progression.xpToNext(level) == xp, "xpToNext(" + level + ") is " + xp));
        check(Progression.xpToNext(49) == 5430, "xpToNext(49) is 5430");
        for (int level = 1; level < 49; level++) {
            check(Progression.xpToNext(level + 1) > Progression.xpToNext(level), "curve strictly increasing at " + level);
        }
        check(Progression.xpToNext(50) == 0 && Progression.xpToNext(99) == 0, "no requirement at or past the cap");
        check(Progression.totalXpToReach(1) == 0 && Progression.totalXpToReach(5) == 340 && Progression.totalXpToReach(20) == 7980,
                "cumulative XP matches the table");
        check(Progression.totalXpToReach(50) == 97510, "cumulative XP to the cap is 97 510");
        check(Progression.isMaxLevel(50) && !Progression.isMaxLevel(49), "cap is level 50");
        check(Progression.clampLevel(0) == 1 && Progression.clampLevel(99) == 50 && Progression.clampLevel(7) == 7, "levels clamp to 1..50");
    }

    private static void checkYield() {
        check(Progression.stageYield(DigimonStage.BABY_I) == 4 && Progression.stageYield(DigimonStage.BABY_II) == 6
                && Progression.stageYield(DigimonStage.CHILD) == 10 && Progression.stageYield(DigimonStage.ADULT) == 18
                && Progression.stageYield(DigimonStage.PERFECT) == 28 && Progression.stageYield(DigimonStage.ULTIMATE) == 40
                && Progression.stageYield(DigimonStage.SUPER_ULTIMATE) == 52 && Progression.stageYield(DigimonStage.ARMOR) == 18
                && Progression.stageYield(DigimonStage.HYBRID) == 18, "stage yields");
        int[][] babyII = {{1, 22}, {5, 40}, {10, 63}, {20, 108}, {30, 153}, {40, 198}, {50, 243}};
        for (int[] row : babyII) check(Progression.xpYield(DigimonStage.BABY_II, row[0]) == row[1], "baby_ii yield at " + row[0]);
        int[][] child = {{1, 37}, {5, 67}, {10, 105}, {20, 180}, {30, 255}, {40, 330}, {50, 405}};
        for (int[] row : child) check(Progression.xpYield(DigimonStage.CHILD, row[0]) == row[1], "child yield at " + row[0]);
        int[][] adult = {{1, 67}, {5, 121}, {10, 189}, {20, 324}, {50, 729}};
        for (int[] row : adult) check(Progression.xpYield(DigimonStage.ADULT, row[0]) == row[1], "adult yield at " + row[0]);
        int[][] perfect = {{1, 105}, {5, 189}, {20, 504}, {50, 1134}};
        for (int[] row : perfect) check(Progression.xpYield(DigimonStage.PERFECT, row[0]) == row[1], "perfect yield at " + row[0]);
    }

    private static void checkGap() {
        check(Progression.gapMultiplier(10, 10) == 1.0, "same level is neutral");
        check(Progression.gapMultiplier(13, 10) == 1.3 && Progression.gapMultiplier(15, 10) == 1.5, "punching up is rewarded");
        check(Progression.gapMultiplier(17, 10) == 1.5 && Progression.gapMultiplier(50, 1) == 1.5, "reward clamps at 150 %");
        check(Progression.gapMultiplier(7, 10) == 0.7 && Progression.gapMultiplier(6, 10) == 0.6
                && Progression.gapMultiplier(5, 10) == 0.5, "farming down decays");
        check(Progression.gapMultiplier(3, 10) == 0.5 && Progression.gapMultiplier(0, 10) == 0.5
                && Progression.gapMultiplier(1, 50) == 0.5, "decay clamps at 50 %");
    }

    private static void checkSplit() {
        UUID agumon = UUID.randomUUID();
        UUID gabumon = UUID.randomUUID();
        UUID koromon = UUID.randomUUID();
        Map<UUID, Integer> shares = Progression.split(DigimonStage.CHILD, 14, List.of(
                new Progression.Contributor(agumon, 12, 70), new Progression.Contributor(gabumon, 10, 30)));
        check(shares.get(agumon) == 113 && shares.get(gabumon) == 56, "70/30 split against a level-14 wild Agumon gives 113 and 56");
        check(List.copyOf(shares.keySet()).equals(List.of(agumon, gabumon)), "shares keep contributor order");

        shares = Progression.split(DigimonStage.BABY_II, 6, List.of(
                new Progression.Contributor(agumon, 8, 90), new Progression.Contributor(gabumon, 8, 30)));
        check(shares.get(agumon) == 27 && shares.get(gabumon) == 9, "two players at 90 and 30 damage get 27 and 9");

        shares = Progression.split(DigimonStage.BABY_II, 6, List.of(
                new Progression.Contributor(agumon, 8, 90), new Progression.Contributor(gabumon, 8, 30),
                new Progression.Contributor(koromon, 8, 2)));
        check(shares.get(koromon) == 1, "a 2-of-122 tap floors to 1 XP");
        check(shares.get(agumon) == 26 && shares.get(gabumon) == 8, "the tap only nibbles at the real contributors");

        shares = Progression.split(DigimonStage.BABY_II, 3, List.of(new Progression.Contributor(agumon, 5, 21)));
        check(shares.get(agumon) == 24, "level-5 Agumon alone against a level-3 Koromon earns 24");
        shares = Progression.split(DigimonStage.BABY_II, 8, List.of(new Progression.Contributor(agumon, 30, 100)));
        check(shares.get(agumon) == 27, "level-30 Greymon farming a level-8 Koromon earns 27, the floor's half");
        shares = Progression.split(DigimonStage.CHILD, 14, List.of(new Progression.Contributor(agumon, 12, 37.5F)));
        check(shares.get(agumon) == 162, "a lone contributor takes the whole pie whatever its damage");

        check(Progression.split(DigimonStage.CHILD, 10, List.of()).isEmpty(), "no contributors, no XP");
        check(Progression.split(DigimonStage.CHILD, 10, List.of(new Progression.Contributor(agumon, 10, 0))).isEmpty(),
                "zero damage is not a contribution");
        shares = Progression.split(DigimonStage.CHILD, 10, List.of(
                new Progression.Contributor(agumon, 10, -5), new Progression.Contributor(gabumon, 10, 10)));
        check(!shares.containsKey(agumon) && shares.get(gabumon) == 105, "negative damage is ignored and does not dilute others");

        Random random = new Random(7);
        for (int round = 0; round < 2000; round++) {
            int wildLevel = 1 + random.nextInt(50);
            int count = 1 + random.nextInt(6);
            java.util.ArrayList<Progression.Contributor> contributors = new java.util.ArrayList<>();
            for (int index = 0; index < count; index++) {
                contributors.add(new Progression.Contributor(UUID.randomUUID(), 1 + random.nextInt(50), random.nextFloat() * 100));
            }
            int yield = Progression.xpYield(DigimonStage.CHILD, wildLevel);
            int total = Progression.split(DigimonStage.CHILD, wildLevel, contributors).values().stream().mapToInt(Integer::intValue).sum();
            check(total <= yield * 1.5 + count, "the pie is fixed: total never exceeds yield x 1.5 plus the floors");
        }
    }

    private static void checkGain() {
        Progression.Gain gain = Progression.gain(1, 0, 53);
        check(gain.level() == 1 && gain.xp() == 53 && gain.levelsGained() == 0, "one short of level 2");
        gain = Progression.gain(1, 0, 54);
        check(gain.level() == 2 && gain.xp() == 0 && gain.levelsGained() == 1, "exactly level 2");
        gain = Progression.gain(1, 0, 54 + 72 + 10);
        check(gain.level() == 3 && gain.xp() == 10 && gain.levelsGained() == 2, "overflow carries across two levels");
        gain = Progression.gain(49, 5429, 1);
        check(gain.level() == 50 && gain.xp() == 0 && gain.levelsGained() == 1, "reaching the cap");
        gain = Progression.gain(50, 0, 1000);
        check(gain.level() == 50 && gain.xp() == 0 && gain.levelsGained() == 0, "surplus at the cap is discarded");
        gain = Progression.gain(1, 0, 97510);
        check(gain.level() == 50 && gain.xp() == 0 && gain.levelsGained() == 49, "the whole curve in one gain");
        gain = Progression.gain(1, 0, 97509);
        check(gain.level() == 49 && gain.xp() == 5429, "one XP short of the cap");
        gain = Progression.gain(3, 20, 0);
        check(gain.level() == 3 && gain.xp() == 20 && gain.levelsGained() == 0, "nothing gained, nothing changed");
        gain = Progression.gain(99, -5, -5);
        check(gain.level() == 50 && gain.xp() == 0, "garbage in is clamped");
    }

    private static void checkStats() {
        int[][] koromon = {{1, 12}, {5, 14}, {10, 16}, {20, 21}, {30, 26}, {40, 31}, {50, 36}};
        for (int[] row : koromon) check(Progression.maxHealth(12, row[0]) == row[1], "Koromon health at " + row[0]);
        int[][] agumon = {{1, 20}, {5, 23}, {10, 27}, {20, 35}, {30, 43}, {40, 51}, {50, 59}};
        for (int[] row : agumon) check(Progression.maxHealth(20, row[0]) == row[1], "Agumon health at " + row[0]);
        int[][] greymon = {{1, 40}, {5, 46}, {10, 54}, {20, 70}, {30, 86}, {40, 102}, {50, 118}};
        for (int[] row : greymon) check(Progression.maxHealth(40, row[0]) == row[1], "Greymon health at " + row[0]);
        check(near(Progression.attack(2, 1), 2.0) && near(Progression.attack(2, 20), 3.14) && near(Progression.attack(2, 50), 4.94),
                "Koromon attack");
        check(near(Progression.attack(6, 5), 6.72) && near(Progression.attack(6, 20), 9.42) && near(Progression.attack(6, 50), 14.82),
                "Agumon attack");
        check(near(Progression.attack(14, 10), 17.78) && near(Progression.attack(14, 50), 34.58), "Greymon attack");
        check(Progression.maxHealth(12, 50) < Progression.maxHealth(40, 10), "a capped Koromon never matches a young Greymon");
        check(Progression.maxHealth(20, 0) == 20 && Progression.maxHealth(20, 60) == 59, "stat scaling clamps the level");
    }

    private static void checkLedger() {
        DamageLedger ledger = new DamageLedger();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        check(ledger.isEmpty() && ledger.recent(0, 1200).isEmpty(), "fresh ledger is empty");
        ledger.record(first, 5, 100);
        ledger.record(first, 7, 800);
        ledger.record(second, 3, 799);
        ledger.record(second, 0, 2000);
        ledger.record(second, -4, 2000);
        ledger.record(second, Float.NaN, 2000);
        List<DamageLedger.Entry> all = ledger.entries();
        check(all.size() == 2 && all.getFirst().attacker().equals(first) && all.getFirst().damage() == 12
                && all.getFirst().lastHitTick() == 800, "hits accumulate and the last hit tick moves forward");
        check(all.get(1).damage() == 3 && all.get(1).lastHitTick() == 799, "zero, negative and NaN damage leave no trace");
        List<DamageLedger.Entry> recent = ledger.recent(2000, 1200);
        check(recent.size() == 1 && recent.getFirst().attacker().equals(first), "a hit exactly at the window edge counts, one tick older does not");
        check(ledger.recent(800, 1200).size() == 2, "both recent when the window covers them");
        check(ledger.damageBy(first) == 12 && ledger.damageBy(UUID.randomUUID()) == 0, "damage reads per attacker");
        ledger.forget(first);
        check(ledger.damageBy(first) == 0 && ledger.entries().size() == 1 && ledger.damageBy(second) == 3, "a fallen partner's total is forgotten, alone");
        ledger.clear();
        check(ledger.isEmpty(), "clear empties the ledger");
    }

    private static void checkReserveRegen() {
        check(Progression.RESERVE_REGEN_INTERVAL_TICKS == 100 && Progression.FULL_HEAL_TICKS == 2400,
                "a pulse every five seconds, full in two minutes");
        check(nearFloat(Progression.reserveHealth(7.5F, 20), 7.5F + 20.0F / 24), "Agumon regains a twenty-fourth of its health per pulse");
        check(Progression.reserveHealth(19.9F, 20) == 20 && Progression.reserveHealth(20, 20) == 20, "regeneration clamps at full");
        check(nearFloat(Progression.reserveHealth(0, 20), 20.0F / 24) && Progression.reserveHealth(-1, 20) == -1,
                "a rested partner heals from zero; negative health is left alone");
        check(Progression.DEFEAT_REST_TICKS == 600 && Progression.REVIVE_HEALTH == 1, "a defeat costs thirty seconds of rest, then one point of health");
        check(nearFloat(Progression.feedHeal(20, 5), 5) && nearFloat(Progression.feedHeal(20, 8), 8) && nearFloat(Progression.feedHeal(118, 5), 29.5F),
                "food heals 5 % of the maximum per point of nutrition: bread a quarter");
        check(nearFloat(Progression.feedHeal(20, 0), 1), "even food with no nutrition is worth one point's share");
        float health = 0.5F;
        for (int i = 0; i < 24; i++) health = Progression.reserveHealth(health, 20);
        check(health == 20, "twenty-four pulses fill any partner");
        check(Progression.FIELD_REGEN_INTERVAL_TICKS == 20 && Progression.FIELD_REGEN_DELAY_TICKS == 100 && nearFloat(Progression.fieldHeal(20), 20.0F / 120),
                "out in the world a calm partner mends a hundred-and-twentieth a second, five seconds after its fight");
        check(nearFloat(Progression.fieldHeal(20) * (Progression.FULL_HEAL_TICKS / Progression.FIELD_REGEN_INTERVAL_TICKS), 20),
                "two minutes of calm fill it, as the Digivice does");
        check(nearFloat(Progression.reserveHealth(50, 118), 50 + 118.0F / 24), "regeneration scales with max health");
    }

    private static void checkFeeding() {
        check(Progression.FEED_INTERVAL_TICKS == 32 && Progression.FEED_FIGHT_INTERVAL_TICKS == 300,
                "a calm partner bites every 1.6 seconds, one in a fight every fifteen");
        check(Progression.feedWait(0, 0) == 32 && Progression.feedWait(31, 0) == 1 && Progression.feedWait(32, 0) == 0,
                "calm: the next bite 32 ticks after the last");
        check(Progression.feedWait(0, -1) == 300 && Progression.feedWait(299, -1) == 1 && Progression.feedWait(300, -1) == 0,
                "fighting: the next bite 300 ticks after the last");
        check(Progression.feedWait(100, 0) == 0 && Progression.feedWait(100, -1) == 200,
                "the same gap since the last bite is enough when calm, not in a fight");
        check(Progression.feedWait(0, 100) == 100 && Progression.feedWait(10, 10) == 22 && Progression.feedWait(250, 100) == 50,
                "winding down from a fight: calm, then the calm spacing, unless the fight's spacing ends first");
        check(Progression.DIGIMEAT_NUTRITION == 6 && nearFloat(Progression.DIGIMEAT_SATURATION_MODIFIER, 0.6F),
                "Digimeat eats like cooked chicken: 6 hunger, 0.6 saturation modifier");
        check(nearFloat(Progression.feedHeal(20, Progression.DIGIMEAT_NUTRITION), 6) && nearFloat(Progression.feedHeal(118, Progression.DIGIMEAT_NUTRITION), 35.4F),
                "a Digimeat gives a partner 30 % of its maximum");
    }

    private static void checkDrops() {
        check(DigimonDrops.stageTable(DigimonStage.CHILD).identifier().equals(Constants.id("entities/digimon/child")),
                "a Rookie drops from digicube:entities/digimon/child");
        check(DigimonDrops.speciesTable(Constants.id("agumon")).identifier().equals(Constants.id("entities/digimon/species/agumon")),
                "a species' own table sits under entities/digimon/species/");
        for (DigimonStage stage : DigimonStage.values()) {
            String path = "/data/digicube/loot_table/entities/digimon/" + stage.getId() + ".json";
            check(ProgressionRegressionTest.class.getResource(path) != null, "every stage has a drop table: " + path);
        }
    }

    /** Health is a float on the entity, so regeneration is compared at float precision. */
    private static boolean nearFloat(float actual, float expected) {
        return Math.abs(actual - expected) < 1.0E-4F;
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < 1.0E-9;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
