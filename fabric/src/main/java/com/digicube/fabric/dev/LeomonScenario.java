package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.CompoundAttacks;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.PounceAttacks;
import com.digicube.entity.AttackStance;
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
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Headless check of Leomon's two compound moves on its own AI and under orders: {@code DIGICUBE_SCENARIO=leomon_checks}.
 * Lion Sword's stance (the draw, the hold and the sheathe on their clock, the weapon out between the swaps, the cooldown
 * after the sheathe); its strikes only while the weapon holds, the slash combo up close (its forms in order, chained, each
 * landed hit filling the gauge by its amount), the stab from a run (no gather, its pace kept, filling more) and from the
 * air. Beast King Fist refused while its gauge charges and taken once full; the punch up close (the gauge spent, the
 * victim launched) and the shot at range. An order on Lion Sword draws and then strikes the ordered target. The verdict
 * line starts with {@code [leomon] RESULT}; {@code DIGICUBE_LEOMON_ONLY=<prefix>} runs the checks named so.
 */
public final class LeomonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO"), ONLY = System.getenv("DIGICUBE_LEOMON_ONLY");
    private static final int FLOOR = 300, HALF = 16, BACK = -32, FAR = 32;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}
    /** One tick of the hunter: its stance, the attack under way and its tick, its gauge, where it and its prey are. */
    private record Frame(AttackStance.Phase phase, int stanceTicks, String attack, int attackTick, float gauge, Vec3 at, Vec3 prey, boolean ground) {}
    /** A cast seen starting: its frame, which attack, the form it started with, the stance's phase then, the pace into it, its first tick. */
    private record Cast(int tick, String attack, int form, AttackStance.Phase phase, double pace, int firstTick) {}
    /** A rise of the gauge: its frame, by how much, and the attack under way as it rose. */
    private record Fill(int tick, float amount, String attack) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static DigimonEntity hunter, prey;
    private static final List<Frame> frames = new ArrayList<>();
    private static final List<Cast> casts = new ArrayList<>();
    private static final List<Fill> fills = new ArrayList<>();
    private static float damage;
    private static DigimonAttack lastAttack;
    private static int lastAttackTick;
    /** Step notes: answers to presses and orders, and the frames things happened at. */
    private static boolean answer, second, third;
    private static int markTick = -1, endTick = -1;
    private static boolean done;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();

    private LeomonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"leomon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -2; cx <= 1; cx++) for (int cz = -3; cz <= 2; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                steps = plan().stream().filter(s -> ONLY == null || ONLY.isBlank() || s.name().startsWith(ONLY)).toList();
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
                if (verdict.startsWith("PASS")) passed++; else { failures.add(line); trace(step.name()); }
                Constants.LOG.info("[leomon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin(level);
            }
            steps.get(stepIndex).input().accept(stepTick);
        } catch (RuntimeException e) {
            Constants.LOG.error("[leomon] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    // --- the arena and its bodies -----------------------------------------------------------------------------------

    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -HALF; x <= HALF; x++) for (int z = BACK; z <= FAR; z++) {
            level.setBlock(new BlockPos(x, FLOOR - 1, z), stone, 2);
            for (int y = FLOOR; y <= FLOOR + 16; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
        }
    }

    private static DigimonAttack move(String path) {
        return hunter.speciesAttacks().stream().filter(a -> a.id().getPath().equals(path)).findFirst().orElseThrow();
    }

    private static CompoundAttacks.Stance stance() { return CompoundAttacks.get(Constants.id("lion_sword")).stance(); }

    /** The stab's pounce, as Lion Sword's forms name it. */
    private static PounceAttacks.Spec stab() {
        return CompoundAttacks.get(Constants.id("lion_sword")).forms().stream().map(f -> PounceAttacks.get(f.attack()))
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    }

    /** A fresh wild Leomon at {@code z} facing +z, its gauge empty and its weapon away; {@code ai} false holds its AI still. */
    private static void hunter(ServerLevel level, double z, boolean ai) {
        if (hunter != null) hunter.discard();
        hunter = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("leomon")), 20, new Vec3(.5, FLOOR, z));
        hunter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        hunter.setHealth(hunter.getMaxHealth());
        hunter.snapTo(.5, FLOOR, z, 0, 0);
        hunter.yBodyRot = hunter.yHeadRot = 0;
        hunter.setNoAi(!ai);
        lastAttack = null;
    }

    /** A still body that takes hits and never dies, at {@code z}. */
    private static void prey(ServerLevel level, double z) { prey(level, z, false); }

    /**
     * As {@link #prey(ServerLevel, double)}; {@code moves}: its AI runs (so a blow can throw it: a body without AI never
     * moves) but its legs never walk.
     */
    private static void prey(ServerLevel level, double z, boolean moves) { prey(level, z, moves, "agumon"); }

    /** As {@link #prey(ServerLevel, double, boolean)}, of the species {@code species} (an Agumon by default: short prey). */
    private static void prey(ServerLevel level, double z, boolean moves, String species) {
        if (prey != null) prey.discard();
        prey = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id(species)), 20, new Vec3(.5, FLOOR, z));
        prey.setNoAi(!moves);
        if (moves) prey.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        prey.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        prey.setHealth(prey.getMaxHealth());
        prey.snapTo(.5, FLOOR, z, 180, 0);
    }

    /** From {@link #SETTLE} ticks into a step (the bodies on the floor), the hunter is set on its prey. */
    private static void hunt(int t) {
        if (t >= SETTLE && hunter.getTarget() != prey) hunter.setTarget(prey);
    }
    private static final int SETTLE = 5;

    // --- the checks -------------------------------------------------------------------------------------------------

    private static List<Step> plan() {
        var plan = new ArrayList<Step>();
        var spec = stance();
        // The stance on its own clock: the draw, the hold, the sheathe, the weapon out from the draw's swap to the sheathe's,
        // and the move's cooldown from the sheathe's end (refused before it is over, taken after).
        plan.add(new Step("stance clock", spec.length() + spec.cooldown() + 80,
                () -> { hunter(level(), -10, false); prey(level(), 20); },
                t -> {
                    // The stance and its cooldown run on the body's own clock, which starts late while its chunk comes to
                    // ticking: the draw waits for the body's first ticks, and the cooldown is read on its clock.
                    if (!answer && hunter.tickCount >= 2) answer = hunter.drawStance(move("lion_sword"));
                    if (endTick < 0 && answer && hunter.stanceCode() == 0) { endTick = t; markTick = hunter.tickCount; }
                    int since = endTick > 0 ? hunter.tickCount - markTick : Integer.MIN_VALUE;
                    if (since == spec.cooldown() - 20 && !second) second = !hunter.drawStance(move("lion_sword"));
                    if (since == spec.cooldown() + 2 && !third) third = hunter.drawStance(move("lion_sword"));
                },
                () -> {
                    int draw = length(AttackStance.Phase.DRAW), hold = length(AttackStance.Phase.HOLD), sheathe = length(AttackStance.Phase.SHEATHE);
                    int[] out = drawn();
                    boolean clock = draw == spec.draw() && hold == spec.hold() && sheathe == spec.sheathe();
                    return verdict(answer && clock && out[0] == spec.drawSwap() && out[1] == spec.draw() + spec.hold() + spec.sheatheSwap() && second && third,
                            "drawn %s; draw %d, hold %d, sheathe %d ticks (want %d, %d, %d); weapon out from tick %d to %d of the stance (want %d to %d); "
                                    + "refused %s before the cooldown is over, drawn again %s after it",
                            answer, draw, hold, sheathe, spec.draw(), spec.hold(), spec.sheathe(), out[0], out[1], spec.drawSwap(),
                            spec.draw() + spec.hold() + spec.sheatheSwap(), second, third);
                }));
        // Up close on its own AI: the draw first, then the slash combo (forms in order, chained), every strike while the weapon
        // holds, and each landed slash filling the gauge by 25: against short prey (an Agumon, the cuts aimed down at it) and
        // tall prey (a Garurumon, the cuts level).
        plan.add(new Step("slash combo", 170,
                () -> { hunter(level(), -10, true); prey(level(), -6.8); },
                t -> hunt(t),
                LeomonScenario::comboVerdict));
        plan.add(new Step("slash combo, tall prey", 170,
                () -> { hunter(level(), -10, true); prey(level(), -6.2, false, "garurumon"); },
                t -> hunt(t),
                LeomonScenario::comboVerdict));
        // From a run: the weapon drawn on the way in, the stab cast at its pace (no gather: the run start) from four to eight
        // blocks out, its hit filling the gauge by 35.
        plan.add(new Step("stab from a run", 140,
                () -> { hunter(level(), -24, true); prey(level(), -11); },
                t -> hunt(t),
                () -> {
                    Cast stab = casts.stream().filter(c -> c.attack().equals("lion_sword_stab")).findFirst().orElse(null);
                    double gap = stab == null ? -1 : apart(stab.tick());
                    List<Fill> stabFills = fills.stream().filter(f -> f.attack().equals("lion_sword_stab")).toList();
                    boolean amount = !stabFills.isEmpty() && Math.abs(stabFills.getFirst().amount() - 35) < .01F;
                    return verdict(stab != null && stab.form() == PounceAttacks.Spec.RUNNING && stab.firstTick() == stab().gather() + 1
                                    && stab.pace() >= stab().runStart() && gap >= 4 && gap <= 9 && amount && stab.phase() == AttackStance.Phase.HOLD,
                            "stab %s from %.1f blocks (centre to centre) at %.3f blocks a tick, form %s, its first tick %s (from its gather %d, no crouch); gauge rose %s",
                            stab != null, gap, stab == null ? 0 : stab.pace(), stab == null ? "-" : stab.form(), stab == null ? "-" : stab.firstTick(),
                            stab().gather(), amounts(stabFills));
                }));
        // From the air: the weapon out, the body thrown up beside its prey: the stab's air form goes from the leap.
        plan.add(new Step("stab from the air", 80,
                () -> { hunter(level(), -10, false); prey(level(), -6.2); answer = hunter.drawStance(move("lion_sword")); },
                t -> {
                    if (t == spec.draw() + 1) { hunter.setNoAi(false); hunt(t); hunter.setDeltaMovement(0, .7, 0); hunter.needsSync = true; markTick = t; }
                },
                () -> {
                    Cast stab = casts.stream().filter(c -> c.attack().equals("lion_sword_stab")).findFirst().orElse(null);
                    boolean air = stab != null && !frames.get(Math.clamp(stab.tick() - 1, 0, frames.size() - 1)).ground();
                    return verdict(answer && stab != null && stab.form() == PounceAttacks.Spec.AIR && air && damage > 0,
                            "drawn %s; stab %s, form %s, cast %s; damage %.1f", answer, stab != null, stab == null ? "-" : stab.form(),
                            air ? "in the air" : "on the ground", damage);
                }));
        // Prey on a ledge above, out of every strike's reach: the weapon out, the body leaps at it and stabs from the top.
        plan.add(new Step("leap at a ledge", 70,
                () -> {
                    hunter(level(), -10, false);
                    for (int y = 0; y < 3; y++) level().setBlock(new BlockPos(0, FLOOR + y, -5), Blocks.STONE.defaultBlockState(), 3);
                    prey(level(), -4.5);
                    prey.snapTo(.5, FLOOR + 3, -4.5, 180, 0);
                    answer = hunter.drawStance(move("lion_sword"));
                },
                t -> { if (t == spec.draw() + 1) { hunter.setNoAi(false); hunt(t); } },
                () -> {
                    for (int y = 0; y < 3; y++) level().setBlock(new BlockPos(0, FLOOR + y, -5), Blocks.AIR.defaultBlockState(), 3);
                    Cast stab = casts.stream().filter(c -> c.attack().equals("lion_sword_stab")).findFirst().orElse(null);
                    int leaps = hunter.skillUses().getOrDefault("leap_pounce", 0);
                    boolean air = stab != null && !frames.get(Math.clamp(stab.tick() - 1, 0, frames.size() - 1)).ground();
                    return verdict(answer && leaps >= 1 && stab != null && stab.form() == PounceAttacks.Spec.AIR && air && damage > 0,
                            "drawn %s; %d leaps; stab %s, form %s, %s; damage %.1f", answer, leaps, stab != null, stab == null ? "-" : stab.form(),
                            air ? "in the air" : "on the ground", damage);
                }));
        // Beast King Fist while its gauge charges: an order is refused (it never gets near ready) and nothing casts; full, it is taken.
        plan.add(new Step("fist refused until full", 6,
                () -> { hunter(level(), -10, false); prey(level(), -7); },
                t -> {
                    if (t != 2) return;
                    var fist = move("beast_king_fist");
                    hunter.setGauge(fist, 60);
                    answer = !hunter.orderAttack(fist, prey) && hunter.readyIn(fist) > DigimonEntity.ORDER_GRACE_TICKS && !hunter.castCompound(fist, prey)
                            && !hunter.gaugeFull(fist) && Math.abs(hunter.gaugeShare(fist) - .6F) < 1.0E-4F;
                    hunter.setGauge(fist, 100);
                    second = hunter.orderAttack(fist, prey);
                },
                () -> verdict(answer && second, "at 60 of 100: refused %s; full: ordered %s", answer, second)));
        // The gauge keeps what its hits filled through a save, and the loaded body shows it.
        plan.add(new Step("gauge saved", 4,
                () -> { hunter(level(), -10, false); prey(level(), -7); },
                t -> { if (t == 2) hunter.setGauge(move("beast_king_fist"), 70); },
                () -> {
                    try (var problems = new net.minecraft.util.ProblemReporter.ScopedCollector(Constants.LOG)) {
                        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(problems, hunter.registryAccess());
                        hunter.saveWithoutId(output);
                        DigimonEntity copy = com.digicube.registry.DCEntityTypes.DIGIMON.create(hunter.level(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
                        copy.load(net.minecraft.world.level.storage.TagValueInput.create(problems, hunter.registryAccess(), output.buildResult()));
                        float kept = copy.gauge(move("beast_king_fist")), shown = copy.shownGauge();
                        copy.discard();
                        return verdict(Math.abs(kept - 70) < 1.0E-3F && Math.abs(shown - .7F) < 1.0E-3F, "loaded copy holds %.1f of 100, shows %.2f", kept, shown);
                    }
                }));
        // Full, up close: the punch, the gauge spent at the cast, the victim thrown far and up.
        plan.add(new Step("punch launches", 70,
                () -> { hunter(level(), -10, true); prey(level(), -6.5, true); hunter.setGauge(move("beast_king_fist"), 100); },
                t -> hunt(t),
                () -> {
                    Cast punch = casts.stream().filter(c -> c.attack().equals("beast_king_fist_punch")).findFirst().orElse(null);
                    float after = punch == null ? -1 : frames.get(Math.min(punch.tick(), frames.size() - 1)).gauge();
                    double thrown = 0, rise = 0;
                    if (punch != null) {
                        Vec3 from = frames.get(Math.min(punch.tick(), frames.size() - 1)).prey();
                        for (int i = punch.tick(); i < frames.size(); i++) {
                            thrown = Math.max(thrown, frames.get(i).prey().subtract(from).horizontalDistance());
                            rise = Math.max(rise, frames.get(i).prey().y - from.y);
                        }
                    }
                    return verdict(punch != null && after == 0 && thrown >= 2.5 && rise >= .3 && damage > 0,
                            "punch %s, gauge %.0f after the cast; the victim thrown %.2f blocks and %.2f up; damage %.1f", punch != null, after, thrown, rise, damage);
                }));
        // Full, at range: the shot, which flies and strikes.
        plan.add(new Step("shot at range", 90,
                () -> { hunter(level(), -16, true); prey(level(), -2); hunter.setGauge(move("beast_king_fist"), 100); },
                t -> hunt(t),
                () -> {
                    Cast shot = casts.stream().filter(c -> c.attack().equals("beast_king_fist_shot")).findFirst().orElse(null);
                    double gap = shot == null ? -1 : apart(shot.tick());
                    float left = shot == null ? -1 : frames.get(Math.min(shot.tick(), frames.size() - 1)).gauge();
                    return verdict(shot != null && gap >= 8 && damage > 0 && left == 0,
                            "shot %s from %.1f blocks; damage %.1f; gauge %.0f after the cast", shot != null, gap, damage, left);
                }));
        // An order on Lion Sword with every move on manual: nothing before it; then the draw, and a strike at the ordered target.
        plan.add(new Step("order draws then strikes", 110,
                () -> {
                    hunter(level(), -10, true); prey(level(), -7);
                    for (var attack : hunter.speciesAttacks()) hunter.setManual(attack, true);
                },
                t -> {
                    hunt(t);
                    if (t == 20) { answer = hunter.orderAttack(move("lion_sword"), prey); markTick = t; }
                },
                () -> {
                    int drawAt = firstPhase(AttackStance.Phase.DRAW);
                    Cast strike = casts.stream().filter(c -> c.attack().startsWith("lion_sword")).findFirst().orElse(null);
                    boolean before = casts.stream().anyMatch(c -> c.tick() <= markTick) || drawAt >= 0 && drawAt <= markTick;
                    return verdict(answer && !before && drawAt > markTick && drawAt <= markTick + 3 && strike != null && strike.tick() >= drawAt + spec.draw() - 1
                                    && !hunter.hasStandingOrder() && damage > 0,
                            "ordered %s at %d; drawn at %d; first strike %s at %s; anything before the order %s; order still standing %s; damage %.1f",
                            answer, markTick, drawAt, strike == null ? "-" : strike.attack(), strike == null ? "-" : strike.tick(), before,
                            hunter.hasStandingOrder(), damage);
                }));
        return plan;
    }

    /** The slash combo's verdict: drawn first, every strike while it holds, the forms in order and chained, +25 a hit. */
    private static String comboVerdict() {
        var spec = stance();
        List<Cast> sword = casts.stream().filter(c -> c.attack().startsWith("lion_sword")).toList();
        List<Cast> slashes = sword.stream().filter(c -> c.attack().startsWith("lion_sword_slash")).toList();
        boolean held = sword.stream().allMatch(c -> c.phase() == AttackStance.Phase.HOLD);
        int drawAt = firstPhase(AttackStance.Phase.DRAW);
        // the combo opens with its first form and goes on to the next; the finisher comes when it reaches
        boolean order = slashes.size() >= 3 && slashes.get(0).attack().equals("lion_sword_slash")
                && slashes.get(1).attack().equals("lion_sword_slash_rising")
                && slashes.stream().anyMatch(c -> c.attack().equals("lion_sword_slash_cleave"));
        List<Fill> slashFills = fills.stream().filter(f -> f.attack().startsWith("lion_sword_slash")).toList();
        // each landed slash pays 25, or what is left to fill the gauge (a stab between the combos may have paid 35)
        float capacity = CompoundAttacks.get(move("beast_king_fist")).gauge().capacity();
        boolean amounts = !slashFills.isEmpty() && slashFills.stream().allMatch(f -> Math.abs(f.amount() - 25) < .01F
                || f.amount() < 25 && Math.abs(frames.get(f.tick()).gauge() - capacity) < .01F);
        int chained = hunter.skillUses().getOrDefault("strike_chained", 0);
        return verdict(drawAt >= 0 && !sword.isEmpty() && sword.getFirst().tick() >= drawAt + spec.draw() - 1 && held && order
                        && slashFills.size() >= 2 && amounts && chained >= 1 && damage > 0,
                "drawn at %d, first strike at %s, every strike while it holds %s; slashes %s; gauge rises %s; %d chained; damage %.1f",
                drawAt, sword.isEmpty() ? "-" : sword.getFirst().tick(), held, names(slashes), amounts(slashFills), chained, damage);
    }

    // --- the record -------------------------------------------------------------------------------------------------

    private static ServerLevel level() { return (ServerLevel) hunter.level(); }

    private static void begin(ServerLevel level) {
        stepTick = 0;
        frames.clear(); casts.clear(); fills.clear();
        damage = 0;
        answer = second = third = false;
        markTick = endTick = -1;
        if (hunter == null) hunter(level, -10, false);
        steps.get(stepIndex).start().run();
        hunter.readyAttacks();
        record();
    }

    /** One frame (its index is the step's tick): the hunter's state, the casts it started, the gauge's rises, the prey's damage. */
    private static void record() {
        int at = frames.size(), code = hunter.stanceCode();
        float gauge = hunter.gauge(move("beast_king_fist"));
        DigimonAttack now = hunter.isAttacking() ? hunter.getActiveAttack() : null;
        int tick = hunter.currentAttackTick();
        if (now != null && (now != lastAttack || tick < lastAttackTick)) {
            // the pace into the cast: the ground covered over the tick before it
            double pace = frames.size() < 2 ? 0 : frames.getLast().at().subtract(frames.get(frames.size() - 2).at()).horizontalDistance();
            casts.add(new Cast(at, now.id().getPath(), hunter.lastCastForm(), AttackStance.phase(code), pace, tick));
            Constants.LOG.info("[leomon] {}: {} (form {}) at frame {}, attack tick {}, stance {}", steps.get(stepIndex).name(), now.id().getPath(),
                    hunter.lastCastForm(), at, tick, AttackStance.phase(code));
        }
        if (!frames.isEmpty() && gauge > frames.getLast().gauge() + 1.0E-3F) {
            String by = now != null ? now.id().getPath() : lastAttack != null ? lastAttack.id().getPath() : "-";
            fills.add(new Fill(at, gauge - frames.getLast().gauge(), by));
        }
        lastAttack = now;
        lastAttackTick = tick;
        frames.add(new Frame(AttackStance.phase(code), AttackStance.ticks(code), now == null ? "" : now.id().getPath(), tick, gauge,
                hunter.position(), prey == null ? Vec3.ZERO : prey.position(), hunter.onGround()));
        if (prey != null) {
            float lost = prey.getMaxHealth() - prey.getHealth();
            prey.setHealth(prey.getMaxHealth());
            damage += lost;
        }
    }

    /** A failed step's track, every third frame: the stance, the attack, the gap and the hunter's pace. */
    private static void trace(String step) {
        var line = new StringBuilder();
        for (int i = 0; i < frames.size(); i += 3) {
            var f = frames.get(i);
            double pace = i == 0 ? 0 : f.at().subtract(frames.get(i - 1).at()).horizontalDistance();
            line.append(String.format(Locale.ROOT, " %d:%s%s/%s g%.1f v%.2f%s", i, f.phase() == null ? "-" : f.phase().name().charAt(0), f.stanceTicks(),
                    f.attack().isEmpty() ? "" : f.attack().replace("lion_sword_", "").replace("beast_king_fist_", "fist_") + "@" + f.attackTick(),
                    apart(i), pace, f.ground() ? "" : "^"));
        }
        Constants.LOG.info("[leomon] track {}:{}", step, line);
    }

    /** Ticks a phase lasted: its last tick recorded, plus one. */
    private static int length(AttackStance.Phase phase) {
        int most = -1;
        for (var f : frames) if (f.phase() == phase) most = Math.max(most, f.stanceTicks());
        return most + 1;
    }

    private static int firstPhase(AttackStance.Phase phase) {
        for (int i = 0; i < frames.size(); i++) if (frames.get(i).phase() == phase) return i;
        return -1;
    }

    /** The stance's own time (ticks from the draw) at which the weapon came out, and at which it was back. */
    private static int[] drawn() {
        var spec = stance();
        int out = -1, back = -1;
        for (var f : frames) {
            if (f.phase() == null) continue;
            int time = switch (f.phase()) { case DRAW -> f.stanceTicks(); case HOLD -> spec.draw() + f.stanceTicks(); default -> spec.draw() + spec.hold() + f.stanceTicks(); };
            boolean shown = AttackStance.drawn(spec, f.phase(), f.stanceTicks());
            if (shown && out < 0) out = time;
            if (!shown && out >= 0 && back < 0) back = time;
        }
        return new int[]{out, back};
    }

    /** Blocks between the hunter and its prey (centre to centre, level) at a frame. */
    private static double apart(int frame) {
        var f = frames.get(Math.clamp(frame, 0, frames.size() - 1));
        return f.prey().subtract(f.at()).horizontalDistance();
    }

    private static String names(List<Cast> casts) {
        return casts.stream().map(c -> c.attack().replace("lion_sword_", "") + "@" + c.tick()).toList().toString();
    }

    private static String amounts(List<Fill> fills) {
        return fills.stream().map(f -> String.format(Locale.ROOT, "+%.0f", f.amount())).toList().toString();
    }

    private static String verdict(boolean pass, String format, Object... args) {
        return (pass ? "PASS " : "FAIL ") + String.format(Locale.ROOT, format, args);
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[leomon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
