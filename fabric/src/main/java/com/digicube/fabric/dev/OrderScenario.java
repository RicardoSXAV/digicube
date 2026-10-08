package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.dev.CombatScenario;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.ManualAttacks;
import com.digicube.entity.DigimonEntity;
import com.digicube.party.AttackOrders;
import com.digicube.platform.Services;
import com.digicube.registry.DCEntityTypes;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Headless check of universal control, the AI side of the command wheel's AUTO switches and attack orders:
 * {@code DIGICUBE_SCENARIO=order_checks}. A wild hunter fights a still dummy that never dies on the flat arena. A move on
 * manual is never picked (Agumon claws but never breathes; Garurumon with its pounce on manual only breathes and never
 * leaps; Mojyamon with both throws on manual throws nothing until the icicle is ordered); with every move on manual
 * the hunter follows its target and waits; an order casts its move, walks in for it from across the arena, lapses
 * after {@link DigimonEntity#ORDER_TICKS}, is refused on a move further than {@link DigimonEntity#ORDER_GRACE_TICKS}
 * from ready and kept nearer; the enemy an order goes at without a target is the one on the tamer's crosshair; and
 * the settings survive being saved. The verdict line starts with {@code [order-checks] RESULT}.
 */
public final class OrderScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int FLOOR = CombatScenario.FLOOR_Y;
    /** Ticks a hunter is left to settle before it is set on its prey, and when an order is given in the steps that give one. */
    private static final int SETTLE = 5, ORDER_AT = 20;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static DigimonEntity hunter, prey;
    private static ServerPlayer tamer;
    /** Casts seen this step, by move path, and the tick each move was first seen under way. */
    private static final Map<String, Integer> casts = new HashMap<>(), firstCast = new HashMap<>();
    private static String lastAttack = "";
    private static boolean leapt, threw, ordered, refusedCooling, reordered;
    private static int reorderedAt = -1, readyAtRefusal;
    private static String sightNote = "";
    private static boolean done;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();

    private OrderScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"order_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                CombatScenario.build(level, "flat");
                steps = plan(level);
                stepIndex = 0;
                begin(level);
                return;
            }
            watch();
            Step step = steps.get(stepIndex);
            if (++stepTick >= step.ticks()) {
                String verdict = step.judge().get();
                total++;
                String line = step.name() + ": " + verdict.substring(verdict.indexOf(' ') + 1);
                if (verdict.startsWith("PASS")) passed++; else failures.add(line);
                Constants.LOG.info("[order-checks] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin(level);
            }
            steps.get(stepIndex).input().accept(stepTick);
        } catch (RuntimeException e) {
            Constants.LOG.error("[order-checks] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static List<Step> plan(ServerLevel level) {
        List<Step> plan = new ArrayList<>();
        plan.add(new Step("a manual move is never picked", 200,
                () -> { spawn(level, "agumon", 7); manual("pepper_breath"); },
                t -> hunt(t),
                () -> verdict(count("pepper_breath") == 0 && count("claw") >= 1,
                        "Pepper Breath on manual cast %d times, Claw on AUTO %d times", count("pepper_breath"), count("claw"))));
        plan.add(new Step("every move on manual: it follows and waits", 160,
                () -> { spawn(level, "agumon", 12); manual("pepper_breath"); manual("claw"); },
                t -> hunt(t),
                () -> verdict(casts.isEmpty() && gap() <= 5.5, "casts %s, %.1f blocks between the bodies at the end", casts, gap())));
        plan.add(new Step("an order casts its move", 100,
                () -> { spawn(level, "agumon", 6); manual("pepper_breath"); manual("claw"); },
                t -> { hunt(t); if (t == ORDER_AT) ordered = hunter.orderAttack(attack("pepper_breath"), prey); },
                () -> verdict(ordered && first("pepper_breath") >= ORDER_AT && count("claw") == 0 && !hunter.hasStandingOrder(),
                        "order taken %s, Pepper Breath at tick %d, Claw %d times, order still standing %s",
                        ordered, first("pepper_breath"), count("claw"), hunter.hasStandingOrder())));
        plan.add(new Step("an order walks in for its move", 150,
                () -> { spawn(level, "agumon", 19); manual("pepper_breath"); manual("claw"); },
                t -> { hunt(t); if (t == ORDER_AT) ordered = hunter.orderAttack(attack("claw"), prey); },
                () -> verdict(ordered && first("claw") >= ORDER_AT && first("claw") - ORDER_AT <= DigimonEntity.ORDER_TICKS && count("pepper_breath") == 0,
                        "from 19 blocks: order taken %s, Claw %d ticks after it, Pepper Breath %d times",
                        ordered, first("claw") - ORDER_AT, count("pepper_breath"))));
        plan.add(new Step("an order lapses", ORDER_AT + DigimonEntity.ORDER_TICKS + 10,
                () -> { spawn(level, "agumon", 6); manual("pepper_breath"); manual("claw"); },
                t -> {
                    hunt(t);
                    if (t == ORDER_AT) { ordered = hunter.orderAttack(attack("pepper_breath"), prey); hunter.setNoAi(true); }
                },
                () -> verdict(ordered && !hunter.hasStandingOrder() && casts.isEmpty(),
                        "order taken %s, still standing after %d ticks %s, casts %s", ordered, DigimonEntity.ORDER_TICKS + 10, hunter.hasStandingOrder(), casts)));
        plan.add(new Step("an order on a move cooling down", 200,
                () -> { spawn(level, "agumon", 6); manual("pepper_breath"); manual("claw"); },
                t -> {
                    hunt(t);
                    DigimonAttack pepper = attack("pepper_breath");
                    if (t == ORDER_AT) ordered = hunter.orderAttack(pepper, prey);
                    // Just after the first breath: refused. Once the move is within the grace of ready: kept.
                    if (first("pepper_breath") >= 0 && t == first("pepper_breath") + 2) {
                        readyAtRefusal = hunter.readyIn(pepper);
                        refusedCooling = !hunter.orderAttack(pepper, prey);
                    }
                    if (refusedCooling && !reordered && hunter.readyIn(pepper) <= DigimonEntity.ORDER_GRACE_TICKS && hunter.readyIn(pepper) > 0) {
                        reordered = hunter.orderAttack(pepper, prey);
                        reorderedAt = t;
                    }
                },
                () -> verdict(ordered && refusedCooling && readyAtRefusal > DigimonEntity.ORDER_GRACE_TICKS && reordered && count("pepper_breath") == 2,
                        "refused %s at %d ticks from ready, kept %s at tick %d, Pepper Breath cast %d times",
                        refusedCooling, readyAtRefusal, reordered, reorderedAt, count("pepper_breath"))));
        plan.add(new Step("Garurumon with its pounce on manual only breathes", 220,
                () -> { spawn(level, "garurumon", 9); manual("freeze_fang"); },
                t -> hunt(t),
                () -> verdict(count("freeze_fang") == 0 && !leapt && count("howling_blaster") >= 1,
                        "Freeze Fang %d times, leapt %s, Howling Blaster %d times", count("freeze_fang"), leapt, count("howling_blaster"))));
        plan.add(new Step("Mojyamon with both throws on manual throws nothing until ordered", 220,
                () -> { spawn(level, "mojyamon", 9); manual("boomerang_bone"); manual("icicle_rod"); },
                t -> {
                    hunt(t);
                    if (t == 120) { ordered = hunter.orderAttack(attack("icicle_rod"), prey); }
                    if (t < 120 && hunter.thrower().busy()) threw = true;
                },
                () -> verdict(!threw && ordered && first("icicle_rod") >= 120,
                        "busy before the order %s, order taken %s, icicle under way at tick %d", threw, ordered, first("icicle_rod"))));
        plan.add(new Step("the enemy on the crosshair", 2,
                () -> sightCheck(level),
                t -> {},
                () -> verdict(sightNote.isEmpty(), sightNote.isEmpty() ? "picked the dummy ahead, through its own partner, and nothing looking away or behind a wall" : sightNote)));
        plan.add(new Step("AUTO settings survive being saved", 2,
                () -> { spawn(level, "agumon", 6); manual("pepper_breath"); },
                t -> {},
                OrderScenario::savedCheck));
        return plan;
    }

    // --- staging -----------------------------------------------------------------------------------------------------

    private static void begin(ServerLevel level) {
        stepTick = 0;
        casts.clear(); firstCast.clear(); lastAttack = "";
        leapt = threw = ordered = refusedCooling = reordered = false;
        reorderedAt = -1; readyAtRefusal = 0; sightNote = "";
        if (hunter != null) { hunter.discard(); hunter = null; }
        if (prey != null) { prey.discard(); prey = null; }
        CombatScenario.purge(level, null, null);
        steps.get(stepIndex).start().run();
    }

    /** A wild {@code species} hunter facing +z, and {@code distance} blocks ahead a still Agumon that never dies. */
    private static void spawn(ServerLevel level, String species, double distance) {
        Vec3 at = new Vec3(.5, FLOOR, -distance / 2 - 1);
        hunter = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id(species)), 20, at);
        hunter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        hunter.setHealth(hunter.getMaxHealth());
        hunter.snapTo(at.x, at.y, at.z, 0, 0);
        hunter.yBodyRot = hunter.yHeadRot = 0;
        prey = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, at.add(0, 0, distance));
        prey.setNoAi(true);
        prey.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        prey.setHealth(prey.getMaxHealth());
    }

    private static DigimonAttack attack(String path) {
        for (DigimonAttack attack : hunter.speciesAttacks()) if (attack.id().getPath().equals(path)) return attack;
        throw new IllegalStateException(hunter.getSpeciesId() + " has no " + path);
    }

    private static void manual(String path) {
        hunter.setManual(attack(path), true);
    }

    /** The hunter settles, then is set on its prey; the prey's wounds are healed each tick. */
    private static void hunt(int t) {
        if (t == SETTLE) hunter.setTarget(prey);
        prey.setHealth(prey.getMaxHealth());
        hunter.setHealth(hunter.getMaxHealth());
    }

    /** Each tick: the move under way (a change is a cast) and whether a pouncer left the ground to pounce. */
    private static void watch() {
        if (hunter == null) return;
        DigimonAttack attack = hunter.getActiveAttack();
        String now = attack == null ? "" : com.digicube.digimon.AuthoredAttacks.move(attack).id().getPath();
        if (!now.isEmpty() && !now.equals(lastAttack)) {
            casts.merge(now, 1, Integer::sum);
            firstCast.putIfAbsent(now, stepTick);
        }
        lastAttack = now;
        if (hunter.leapingToPounce()) leapt = true;
    }

    private static int count(String path) { return casts.getOrDefault(path, 0); }
    private static int first(String path) { return firstCast.getOrDefault(path, -1); }

    private static double gap() {
        return prey.position().subtract(hunter.position()).horizontalDistance() - (prey.getBbWidth() + hunter.getBbWidth()) / 2;
    }

    /**
     * A tamer at the south wall looking north at the dummy, its partner standing in the line between them: the dummy is
     * picked through the partner; turned away, nothing is; with a wall in the way, nothing is.
     */
    private static void sightCheck(ServerLevel level) {
        Vec3 at = new Vec3(.5, FLOOR, -12.5);
        hunter = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, at.add(0, 0, 10));
        hunter.setNoAi(true);
        prey = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("gabumon")), 20, at.add(0, 0, 14));
        prey.setNoAi(true);
        tamer = FakePlayer.get(level);
        tamer.snapTo(at.x, at.y, at.z, 0, 0);
        // Looking down a little, at the dummy's middle: the ray passes through the partner standing in the way.
        Vec3 look = prey.getBoundingBox().getCenter().subtract(tamer.getEyePosition());
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, look.horizontalDistance()));
        tamer.snapTo(at.x, at.y, at.z, 0, pitch);
        tamer.setYHeadRot(0);
        List<String> wrong = new ArrayList<>();
        var ahead = AttackOrders.sighted(tamer, hunter);
        if (ahead != prey) wrong.add("looking at the dummy picked " + ahead);
        if (hunter.getBoundingBox().clip(tamer.getEyePosition(), tamer.getEyePosition().add(tamer.getViewVector(1).scale(14))).isEmpty())
            wrong.add("the partner is not in the line");
        tamer.snapTo(at.x, at.y, at.z, 90, pitch);
        tamer.setYHeadRot(90);
        if (AttackOrders.sighted(tamer, hunter) != null) wrong.add("looking away picked something");
        tamer.snapTo(at.x, at.y, at.z, 0, pitch);
        tamer.setYHeadRot(0);
        BlockPos wall = BlockPos.containing(at.add(0, 1, 9));
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 2; dy++) level.setBlock(wall.offset(dx, dy - 1, 0), Blocks.STONE.defaultBlockState(), 3);
        if (AttackOrders.sighted(tamer, hunter) != null) wrong.add("a wall in the way did not hide the dummy");
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 2; dy++) level.setBlock(wall.offset(dx, dy - 1, 0), Blocks.AIR.defaultBlockState(), 3);
        sightNote = String.join("; ", wrong);
    }

    /** The hunter saved and loaded into a fresh body: Pepper Breath still on manual, Claw still on AUTO. */
    private static String savedCheck() {
        try (var problems = new ProblemReporter.ScopedCollector(Constants.LOG)) {
            TagValueOutput output = TagValueOutput.createWithContext(problems, hunter.registryAccess());
            hunter.saveWithoutId(output);
            var tag = output.buildResult();
            DigimonEntity copy = DCEntityTypes.DIGIMON.create(hunter.level(), EntitySpawnReason.LOAD);
            copy.load(TagValueInput.create(problems, hunter.registryAccess(), tag));
            boolean kept = copy.isManual(attack("pepper_breath")) && !copy.isManual(attack("claw")) && copy.manualMask() == 0b01
                    && ManualAttacks.read(tag).size() == 1;
            hunter.setManual(attack("pepper_breath"), false);
            TagValueOutput cleared = TagValueOutput.createWithContext(problems, hunter.registryAccess());
            hunter.saveWithoutId(cleared);
            boolean clean = !cleared.buildResult().contains(ManualAttacks.TAG);
            copy.discard();
            return verdict(kept && clean, "loaded copy keeps Pepper Breath on manual %s (mask %d), all AUTO saves no list %s", kept, copy.manualMask(), clean);
        }
    }

    private static String verdict(boolean pass, String format, Object... args) {
        return (pass ? "PASS " : "FAIL ") + String.format(java.util.Locale.ROOT, format, args);
    }

    private static void finish(ServerLevel level) {
        done = true;
        if (hunter != null) hunter.discard();
        if (prey != null) prey.discard();
        Constants.LOG.info("[order-checks] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
