package com.digicube.digimon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every balance number of the level and XP system, in one place.
 *
 * <p>Nothing else in the code base holds a progression constant, so tuning is a diff of
 * this file and the headless {@code :common:progressionTest} suite reproduces the tables
 * in {@code ../design/wild-spawns-and-progression.md}, kept beside the repository. All
 * arithmetic is integer or a
 * single correctly rounded division, so the same inputs give the same XP on every
 * machine and no floating-point drift can turn an exact share into one less.
 *
 * <p>Levels run from {@link #MIN_LEVEL} to {@link #LEVEL_CAP}. XP is stored as progress
 * within the current level. The only XP source is the defeat of a wild Digimon; its yield
 * is split among the partners that damaged it, in proportion to the damage they dealt.
 */
public final class Progression {

    public static final int MIN_LEVEL = 1;
    public static final int LEVEL_CAP = 50;

    /** A contributor that has not hit the wild Digimon for this long is forgotten. */
    public static final int CONTRIBUTION_MEMORY_TICKS = 1200;
    /** A contributor farther than this from the defeated Digimon receives nothing. */
    public static final double CONTRIBUTION_RANGE = 64.0;

    /** Max health grows by this percentage of the base value per level above 1. */
    private static final int HEALTH_GROWTH_PERCENT = 4;
    /** Attack grows by this percentage of the base value per level above 1. */
    private static final int ATTACK_GROWTH_PERCENT = 3;
    /** The level-gap multiplier moves by this percentage per level of difference. */
    private static final int GAP_STEP_PERCENT = 10;
    private static final int GAP_MIN_PERCENT = 25;
    private static final int GAP_MAX_PERCENT = 150;

    private Progression() {}

    public static int clampLevel(int level) {
        return Math.clamp(level, MIN_LEVEL, LEVEL_CAP);
    }

    public static boolean isMaxLevel(int level) {
        return level >= LEVEL_CAP;
    }

    /**
     * XP needed to advance from {@code level} to the next one.
     * @return the requirement, or 0 at the cap where there is no next level
     */
    public static int xpToNext(int level) {
        if (level >= LEVEL_CAP) return 0;
        return 40 + 12 * level + 2 * level * level;
    }

    /** Total XP a Digimon has gathered when it reaches {@code level} from level 1. */
    public static int totalXpToReach(int level) {
        int total = 0;
        for (int current = MIN_LEVEL; current < level; current++) total += xpToNext(current);
        return total;
    }

    /** Base yield of a wild Digimon by stage, before its level is applied. */
    public static int stageYield(DigimonStage stage) {
        return switch (stage) {
            case BABY_I -> 4;
            case BABY_II -> 6;
            case CHILD -> 10;
            case ADULT, ARMOR, HYBRID -> 18;
            case PERFECT -> 28;
            case ULTIMATE -> 40;
            case SUPER_ULTIMATE -> 52;
        };
    }

    /** Total XP a wild Digimon of this stage and level is worth, before any split. */
    public static int xpYield(DigimonStage stage, int wildLevel) {
        return stageYield(stage) * (wildLevel + 4) / 2;
    }

    /**
     * Multiplier a partner of {@code partnerLevel} gets against a wild Digimon of
     * {@code wildLevel}: farming trivial wilds decays, punching up is rewarded, and both
     * edges are clamped so there is no exploit corner.
     */
    public static double gapMultiplier(int wildLevel, int partnerLevel) {
        return gapPercent(wildLevel, partnerLevel) / 100.0;
    }

    private static int gapPercent(int wildLevel, int partnerLevel) {
        return Math.clamp(100 + GAP_STEP_PERCENT * (wildLevel - partnerLevel), GAP_MIN_PERCENT, GAP_MAX_PERCENT);
    }

    /** Max health of a species with {@code baseHealth} at {@code level}. */
    public static int maxHealth(int baseHealth, int level) {
        return (int) Math.round(baseHealth * (100 + HEALTH_GROWTH_PERCENT * (clampLevel(level) - 1)) / 100.0);
    }

    /** Attack of a species with {@code baseAttack} at {@code level}. */
    public static double attack(int baseAttack, int level) {
        return baseAttack * (100 + ATTACK_GROWTH_PERCENT * (clampLevel(level) - 1)) / 100.0;
    }

    /**
     * One partner's part in a wild Digimon's defeat.
     * @param id     the partner entity
     * @param level  the partner's level at the moment of death
     * @param damage health the wild Digimon lost to this partner
     */
    public record Contributor(UUID id, int level, float damage) {}

    /**
     * Damage-proportional XP shares for a defeated wild Digimon.
     *
     * <p>With {@code D} the damage of every listed contributor, a partner that dealt
     * {@code d} receives {@code max(1, floor(yield × gap × d / D))}. The pie is fixed by
     * the wild Digimon; contributions only slice it, and a tap earns the floor of 1.
     *
     * @return share per contributor id, in the order given; empty when nobody dealt damage
     */
    public static Map<UUID, Integer> split(DigimonStage stage, int wildLevel, List<Contributor> contributors) {
        double total = 0;
        for (Contributor contributor : contributors) {
            if (contributor.damage() > 0) total += contributor.damage();
        }
        Map<UUID, Integer> shares = new LinkedHashMap<>();
        if (!(total > 0)) return shares;
        int yield = xpYield(stage, wildLevel);
        for (Contributor contributor : contributors) {
            if (!(contributor.damage() > 0)) continue;
            double share = (double) yield * gapPercent(wildLevel, contributor.level()) * contributor.damage()
                    / (100.0 * total);
            shares.put(contributor.id(), Math.max(1, (int) Math.floor(share)));
        }
        return shares;
    }

    /**
     * The state of a Digimon after XP was added.
     * @param level        level after any level-ups
     * @param xp           progress within that level; 0 at the cap
     * @param levelsGained how many levels this gain crossed
     */
    public record Gain(int level, int xp, int levelsGained) {}

    /**
     * Add {@code amount} XP to a Digimon at {@code level} with {@code xp} progress.
     * Overflow carries over, several levels can be crossed at once and surplus at the
     * cap is discarded.
     */
    public static Gain gain(int level, int xp, int amount) {
        level = clampLevel(level);
        xp = Math.max(0, xp) + Math.max(0, amount);
        int gained = 0;
        while (!isMaxLevel(level) && xp >= xpToNext(level)) {
            xp -= xpToNext(level);
            level++;
            gained++;
        }
        if (isMaxLevel(level)) xp = 0;
        return new Gain(level, xp, gained);
    }
}
