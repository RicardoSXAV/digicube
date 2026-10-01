package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.BreathAttacks;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.CombatMarkState;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Headless check of Meramon's two moves on its own AI: {@code DIGICUBE_SCENARIO=meramon_checks}. Fire Fist dashed from
 * a few blocks out (the dash carries it in and stops at its prey, the burning fist lands once and sets it alight: a Burn)
 * and thrown at prey right in front of it (the dash hardly travels); Heat Wave at prey out of the fist's reach (the
 * stream from between its palms reaches it, pulses its damage and keeps it alight), at prey off to its side (its body
 * comes round with the aim and the fire still lands), at prey strafing across its front or jumping about on the spot and
 * at a Golemon fighting back (the body turns smoothly through the stream, never twitching), and over a floor of snow and
 * ice (the snow melts and the ice turns to water, never right at its palms); and its body of fire, which stands in a
 * fire unhurt. The verdict line starts with {@code [meramon] RESULT}.
 */
public final class MeramonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    /** The arena: a stone floor at {@code FLOOR}, from x = -HALF to HALF and z = -HALF to HALF. */
    private static final int FLOOR = 300, HALF = 14;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static DigimonEntity hunter, prey;
    private static final List<Vec3> track = new ArrayList<>();
    private static final List<Float> yaws = new ArrayList<>();
    /** Per recorded tick: whether the hunter's Heat Wave was pouring. */
    private static final List<Boolean> pouring = new ArrayList<>();
    private static float dealt;
    private static int fistAt = -1, breathAt = -1, hits, burningTicks, snowBefore, iceBefore;
    private static float burnPeak;
    private static boolean done;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();
    /** DIGICUBE_MERAMON_TRACE=true: the hunter's state every tick. */
    private static final boolean TRACE = Boolean.parseBoolean(System.getenv("DIGICUBE_MERAMON_TRACE"));

    private MeramonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"meramon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                steps = plan(level);
                stepIndex = 0;
                begin(level);
                return;
            }
            record();
            Step step = steps.get(stepIndex);
            if (++stepTick >= step.ticks()) {
                String verdict = step.judge().get();
                total++;
                String line = step.name() + ": " + verdict.substring(verdict.indexOf(' ') + 1);
                if (verdict.startsWith("PASS")) passed++; else failures.add(line);
                Constants.LOG.info("[meramon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin(level);
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (TRACE && hunter != null) Constants.LOG.info("[meramon-trace] {} t{} at {} yaw {} attack {} tick {} prey {} fire {}", step.name(), stepTick,
                    hunter.position(), hunter.getYRot(), hunter.currentAttackAnimation(), hunter.currentAttackTick(),
                    prey == null ? null : prey.position(), prey == null ? 0 : prey.getRemainingFireTicks());
        } catch (RuntimeException e) {
            Constants.LOG.error("[meramon] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static void build(ServerLevel level) {
        floor(level, Blocks.STONE.defaultBlockState(), false);
    }

    /** The stone floor and the air over it; with {@code frozen}, a band of snow layers and ice blocks in the floor ahead. */
    private static void floor(ServerLevel level, net.minecraft.world.level.block.state.BlockState stone, boolean frozen) {
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++) {
            boolean band = frozen && Math.abs(x) <= 2 && z >= 0 && z <= 8;
            level.setBlock(new BlockPos(x, FLOOR - 1, z), band && (x + z) % 2 == 0 ? Blocks.ICE.defaultBlockState() : stone, 2);
            for (int y = FLOOR; y <= FLOOR + 8; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
            if (band && (x + z) % 2 != 0) level.setBlock(new BlockPos(x, FLOOR, z), Blocks.SNOW.defaultBlockState(), 2);
        }
    }

    private static int count(ServerLevel level, net.minecraft.world.level.block.Block block, int y) {
        int n = 0;
        for (int x = -2; x <= 2; x++) for (int z = 0; z <= 8; z++) if (level.getBlockState(new BlockPos(x, y, z)).is(block)) n++;
        return n;
    }

    private static List<Step> plan(ServerLevel level) {
        var plan = new ArrayList<Step>();
        // Prey a few blocks out, past a plain punch: the fist is lit, the dash carries it in and stops at the prey's body.
        plan.add(new Step("fist from afar", 60, () -> spawn(level, new Vec3(.5, FLOOR, -6), new Vec3(.5, FLOOR, -6 + 4.6)),
                t -> watch(),
                () -> {
                    double dash = fistAt < 0 ? 0 : track.get(Math.min(track.size() - 1, fistAt + 16)).subtract(track.get(fistAt)).horizontalDistance();
                    double gap = prey.position().subtract(hunter.position()).horizontalDistance() - (prey.getBbWidth() + hunter.getBbWidth()) / 2;
                    return verdict(fistAt >= 0 && hits == 1 && dealt > 0 && dash > 2.2 && gap > -.25 && burnPeak > 0 && burningTicks > 20,
                            "Fire Fist from tick %d, dashed %.2f blocks, stood %.2f off the prey's box, landed %d time(s) for %.1f, set it alight %d ticks (Burn %.2f)",
                            fistAt, dash, gap, hits, dealt, burningTicks, burnPeak);
                }));
        // Prey right in front: the fist still lands, and the dash hardly travels (it stops at the body). (Judged before
        // the fist's cooldown sends Heat Wave in at close range.)
        plan.add(new Step("fist up close", 44, () -> spawn(level, new Vec3(.5, FLOOR, -6), new Vec3(.5, FLOOR, -6 + 1.9)),
                t -> watch(),
                () -> {
                    double dash = fistAt < 0 ? 0 : track.get(Math.min(track.size() - 1, fistAt + 16)).subtract(track.get(fistAt)).horizontalDistance();
                    double gap = prey.position().subtract(hunter.position()).horizontalDistance() - (prey.getBbWidth() + hunter.getBbWidth()) / 2;
                    return verdict(fistAt >= 0 && hits == 1 && dealt > 0 && dash < 1.1 && gap > -.25 && burnPeak > 0,
                            "Fire Fist from tick %d, travelled %.2f blocks to stand %.2f off the prey's box, landed %d time(s) for %.1f, Burn %.2f",
                            fistAt, dash, gap, hits, dealt, burnPeak);
                }));
        // Prey beyond the fist's reach: Heat Wave pours from between the palms, pulses damage, keeps it alight.
        plan.add(new Step("heat wave", 110, () -> spawn(level, new Vec3(.5, FLOOR, -8), new Vec3(.5, FLOOR, -8 + 7.5)),
                t -> watch(),
                () -> verdict(breathAt >= 0 && hits >= 3 && dealt > 0 && burnPeak > 0 && burningTicks > 40,
                        "Heat Wave from tick %d, %d damage pulses for %.1f, alight %d ticks (Burn %.2f)", breathAt, hits, dealt, burningTicks, burnPeak)));
        // Prey off to its left: the body comes round with the aim and the fire lands.
        plan.add(new Step("heat wave aside", 110, () -> spawn(level, new Vec3(.5, FLOOR, -6), new Vec3(.5 + 6.5, FLOOR, -6 + 2.5)),
                t -> watch(),
                () -> {
                    float turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaws.getLast() - yaws.getFirst()));
                    return verdict(breathAt >= 0 && hits >= 2 && burnPeak > 0 && turned > 45,
                            "Heat Wave from tick %d, its body turned %.0f degrees, %d pulses, Burn %.2f", breathAt, turned, hits, burnPeak);
                }));
        // Prey strafing across its front (followed and led: the fire keeps landing), and prey jumping about on the spot
        // (its steps damped: the body's turn never jerks by more than a few degrees a tick).
        plan.add(new Step("heat wave strafing", 110, () -> spawn(level, new Vec3(.5, FLOOR, -8), new Vec3(.5, FLOOR, -8 + 6)),
                t -> { watch(); prey.setPos(.5 + 2.5 * Math.sin(t * 2 * Math.PI / 40), FLOOR, -2); },
                () -> steadyAim(3, 2, 6)));
        plan.add(new Step("heat wave shuffling", 110, () -> spawn(level, new Vec3(.5, FLOOR, -8), new Vec3(.5, FLOOR, -8 + 5)),
                t -> { watch(); if (t % 4 == 0) prey.setPos(.5 + SHUFFLE[(t / 4) % SHUFFLE.length], FLOOR, -3 + SHUFFLE[(t / 4 + 3) % SHUFFLE.length] * .6); },
                () -> steadyAim(9, 5, 5)));
        // A real brawler on its own AI closing in and fighting back: the body never twitches through a stream.
        plan.add(new Step("heat wave on a brawler", 400, () -> {
                    spawn(level, new Vec3(.5, FLOOR, -9), new Vec3(.5, FLOOR, 3));
                    prey.discard();
                    prey = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("golemon")), 20, new Vec3(.5, FLOOR, 3));
                    prey.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
                    prey.setHealth(prey.getMaxHealth());
                },
                t -> { watch(); if (t == SETTLE) prey.setTarget(hunter); hunter.setHealth(hunter.getMaxHealth()); },
                () -> steadyAim(2, 1.5F, 0)));
        // Over snow and ice: the fire melts the snow and turns the ice to water (mobGriefing).
        plan.add(new Step("heat wave melts", 110, () -> {
                    floor(level, Blocks.STONE.defaultBlockState(), true);
                    snowBefore = count(level, Blocks.SNOW, FLOOR);
                    iceBefore = count(level, Blocks.ICE, FLOOR - 1);
                    spawn(level, new Vec3(.5, FLOOR, -3), new Vec3(.5, FLOOR, -3 + 8));
                },
                t -> watch(),
                () -> {
                    int snow = count(level, Blocks.SNOW, FLOOR), ice = count(level, Blocks.ICE, FLOOR - 1), water = count(level, Blocks.WATER, FLOOR - 1);
                    floor(level, Blocks.STONE.defaultBlockState(), false);
                    return verdict(breathAt >= 0 && snow < snowBefore && ice < iceBefore && water > 0,
                            "Heat Wave from tick %d: snow %d -> %d, ice %d -> %d, %d blocks of water", breathAt, snowBefore, snow, iceBefore, ice, water);
                }));
        // A body of fire: standing in a fire, set alight, it neither burns nor is hurt.
        plan.add(new Step("fireproof", 50, () -> {
                    spawn(level, new Vec3(.5, FLOOR, -6), new Vec3(.5 - 6, FLOOR, -6));
                    prey.discard(); prey = null;
                    level.setBlock(new BlockPos(0, FLOOR, -6), Blocks.FIRE.defaultBlockState(), 3);
                    hunter.setRemainingFireTicks(100);
                },
                t -> {},
                () -> {
                    boolean hurt = hunter.getHealth() < hunter.getMaxHealth();
                    level.setBlock(new BlockPos(0, FLOOR, -6), Blocks.AIR.defaultBlockState(), 3);
                    return verdict(!hurt && !hunter.isOnFire() && hunter.fireImmune(), "stood in a fire 50 ticks: burning %s, hurt %s", hunter.isOnFire(), hurt);
                }));
        String only = System.getenv("DIGICUBE_MERAMON_ONLY");
        if (only != null && !only.isBlank()) plan.removeIf(step -> !step.name().startsWith(only));
        return plan;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    /** A brawler's footwork about its place (blocks): small steps this way and that, never a run. */
    private static final double[] SHUFFLE = {0, .25, -.1, .3, .05, -.25, .15, -.3, .2, -.05};

    /**
     * How the body came round while the stream poured (the AI turns its whole body with the aim): its turn a tick never
     * jerks by more than {@code maxJerk} degrees from the last tick's, and its way of turning flips (a reversal of more
     * than a degree a tick each way) at most {@code maxFlips} times in 40 ticks of stream; the fire lands at least
     * {@code minHits} times.
     */
    private static String steadyAim(float maxFlips, float maxJerk, int minHits) {
        if (breathAt < 0) return verdict(false, "no Heat Wave");
        float jerk = 0, last = Float.NaN, lastBig = 0;
        int flips = 0, ticks = 0;
        for (int i = 1; i < yaws.size(); i++) {
            if (!pouring.get(i) || !pouring.get(i - 1)) { last = Float.NaN; lastBig = 0; continue; }
            float rate = net.minecraft.util.Mth.wrapDegrees(yaws.get(i) - yaws.get(i - 1));
            if (!Float.isNaN(last)) jerk = Math.max(jerk, Math.abs(rate - last));
            if (Math.abs(rate) > 1) { if (lastBig != 0 && Math.signum(rate) != Math.signum(lastBig)) flips++; lastBig = rate; }
            last = rate; ticks++;
        }
        float perForty = flips * 40F / Math.max(1, ticks);
        return verdict(ticks >= 30 && hits >= minHits && perForty <= maxFlips && jerk <= maxJerk,
                "Heat Wave from tick %d over %d ticks: %d pulses, the body's turn flipped %d times (%.1f per 40 ticks), jerked at most %.1f degrees a tick",
                breathAt, ticks, hits, flips, perForty, jerk);
    }

    private static void begin(ServerLevel level) {
        stepTick = 0;
        track.clear(); yaws.clear(); pouring.clear();
        dealt = 0; hits = 0; burningTicks = 0; burnPeak = 0;
        fistAt = breathAt = -1;
        if (hunter != null) { hunter.discard(); hunter = null; }
        if (prey != null) { prey.discard(); prey = null; }
        steps.get(stepIndex).start().run();
        record();
    }

    /** Meramon facing +z at {@code at}, its prey (a still dummy that never dies) at {@code target}. */
    private static void spawn(ServerLevel level, Vec3 at, Vec3 target) {
        hunter = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("meramon")), 20, at);
        hunter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        hunter.setHealth(hunter.getMaxHealth());
        hunter.snapTo(at.x, at.y, at.z, 0, 0);
        hunter.yBodyRot = hunter.yHeadRot = 0;
        prey = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, target);
        prey.setNoAi(true);
        prey.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        prey.setHealth(prey.getMaxHealth());
    }

    /** The hunter settles a few ticks, then hunts; each tick: what it casts, and what reaches the prey. */
    private static void watch() {
        if (stepTick == SETTLE) hunter.setTarget(prey);
        var attack = hunter.getActiveAttack();
        if (attack != null) {
            if (fistAt < 0 && attack.id().getPath().equals("fire_fist")) fistAt = stepTick;
            if (breathAt < 0 && BreathAttacks.handles(attack)) breathAt = stepTick;
        }
        if (prey.isOnFire()) burningTicks++;
        burnPeak = Math.max(burnPeak, CombatMarkState.burnRemaining(((CombatMarkState) prey).digicube$marks2()));
    }
    private static final int SETTLE = 5;

    private static void record() {
        if (hunter == null) return;
        track.add(hunter.position());
        yaws.add(hunter.getYRot());
        var attack = hunter.getActiveAttack();
        pouring.add(attack != null && BreathAttacks.handles(attack) && hunter.currentAttackTick() > attack.motion().activeFrom() + 2
                && hunter.currentAttackTick() < attack.motion().activeUntil());
        if (prey != null) {
            float lost = prey.getMaxHealth() - prey.getHealth();
            // fire's own damage is the Burn's, not the move's: count the blows and pulses alone
            if (lost > 0 && prey.getLastDamageSource() != null && !prey.getLastDamageSource().is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) {
                dealt += lost; hits++;
            }
            prey.setHealth(prey.getMaxHealth());
        }
    }

    private static String verdict(boolean pass, String format, Object... args) {
        return (pass ? "PASS " : "FAIL ") + String.format(java.util.Locale.ROOT, format, args);
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[meramon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
