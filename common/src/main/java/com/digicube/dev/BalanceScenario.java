package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Headless balance runs: many real fights between two wild Digimon, no healing, both at the same
 * level, and a tally of who wins and how long it takes.
 *
 * <p>{@code DIGICUBE_SCENARIO=balance:<a>_vs_<b>[@terrain]} starts the dedicated server through
 * {@link CombatScenario}: each round the arena is rebuilt, both are spawned eight blocks apart
 * six to ten blocks apart, roughly facing each other, given a settle, then set on each other.
 * Sides alternate every round and the spots vary, so no round repeats the last. A round ends when one dies, or is a draw at
 * {@link #ROUND_LIMIT_TICKS}. Fighters that both drop their target (ink does that) are set on each
 * other again after {@link #IDLE_TICKS}; the count of those nudges is reported.
 * {@code DIGICUBE_BALANCE_ROUNDS} (default 20) and {@code DIGICUBE_BALANCE_LEVEL} (default 20)
 * size the run; {@code DIGICUBE_NEUTRAL=true} drops the attribute triangle. The game sprints, so a round costs real seconds, not its game length. The
 * verdict line starts with {@code [balance] RESULT}.
 */
final class BalanceScenario {
    private static final int SETTLE_TICKS = 40, ROUND_LIMIT_TICKS = 20 * 90, IDLE_TICKS = 40, BETWEEN_ROUNDS_TICKS = 10;
    private static final int ROUNDS = Integer.parseInt(System.getenv().getOrDefault("DIGICUBE_BALANCE_ROUNDS", "20"));
    private static final int LEVEL = Integer.parseInt(System.getenv().getOrDefault("DIGICUBE_BALANCE_LEVEL", "20"));

    private record Round(String winner, int ticks, float winnerHealthLeft, int retargets, double[] damage) {}

    private static final class Side {
        final DigimonSpecies species;
        final TreeMap<String, Integer> casts = new TreeMap<>();
        DigimonEntity fighter;
        String lastMove;
        float lastHealth;
        double damageTaken;
        int wins, crits, dodges;

        Side(DigimonSpecies species) { this.species = species; }
        String name() { return species.id().getPath(); }
    }

    private static Side a, b;
    private static String terrain;
    private static boolean initialized, done;
    private static int round, roundStartTick, idleTicks, retargets, waitTicks;
    private static final List<Round> rounds = new ArrayList<>();

    private BalanceScenario() {}

    static void tick(ServerLevel level, String spec) {
        if (done) return;
        try {
            if (!initialized) start(level, spec);
            else if (a.fighter == null) { if (--waitTicks <= 0) begin(level); }
            else observe(level);
        } catch (RuntimeException e) {
            Constants.LOG.error("[balance] aborted", e);
            done = true;
            level.getServer().halt(false);
        }
    }

    private static void start(ServerLevel level, String spec) {
        initialized = true;
        String[] parts = spec.toLowerCase(Locale.ROOT).split("@", 2);
        String[] names = parts[0].split("_vs_", 2);
        terrain = parts.length > 1 ? parts[1] : "flat";
        if (names.length != 2) throw new IllegalArgumentException("bad balance scenario " + spec);
        if (Boolean.parseBoolean(System.getenv("DIGICUBE_NEUTRAL"))) for (String name : names) CombatScenario.neutralize(Constants.id(name));
        a = new Side(DigimonSpeciesRegistry.getOrThrow(Constants.id(names[0])));
        b = new Side(DigimonSpeciesRegistry.getOrThrow(Constants.id(names[1])));
        for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++) level.setChunkForced(cx, cz, true);
        level.getServer().tickRateManager().requestGameToSprint(ROUNDS * (ROUND_LIMIT_TICKS + SETTLE_TICKS + 100));
        Constants.LOG.info("[balance] {} vs {} on {} terrain, level {}, {} rounds", a.name(), b.name(), terrain, LEVEL, ROUNDS);
        begin(level);
    }

    private static void begin(ServerLevel level) {
        if (round == ROUNDS) { summarize(level); return; }
        int evicted = com.digicube.spawn.WildSpawner.clear(level) + CombatScenario.purge(level, null, null);
        CombatScenario.build(level, terrain);
        // Alternate sides, and vary the opening: a duel that always starts from the same two spots is
        // decided by whole hit counts, and its "win chance" would flip between 0 and 1 on a rounding.
        Side near = round % 2 == 0 ? a : b, far = near == a ? b : a;
        var random = level.getRandom();
        double y = terrain.equals("water") ? CombatScenario.FLOOR_Y - 3 : CombatScenario.FLOOR_Y;
        double distance = 6 + random.nextDouble() * 4, sway = random.nextDouble() * 6 - 3;
        near.fighter = DigimonEntity.spawnWild(level, near.species, LEVEL, new Vec3(.5 - sway / 2, y, .5 - distance / 2));
        far.fighter = DigimonEntity.spawnWild(level, far.species, LEVEL, new Vec3(.5 + sway / 2, terrain.equals("ledge") ? y + 1 : y, .5 + distance / 2));
        if (near.fighter == null || far.fighter == null) throw new IllegalStateException("could not spawn round " + round);
        near.fighter.setYRot(random.nextInt(61) - 30); far.fighter.setYRot(180 + random.nextInt(61) - 30);
        for (Side side : new Side[]{a, b}) {
            side.fighter.yBodyRot = side.fighter.yHeadRot = side.fighter.getYRot();
            side.fighter.setHealth(side.fighter.getMaxHealth());
            side.lastHealth = side.fighter.getHealth();
            side.lastMove = null;
            side.damageTaken = 0;
        }
        roundStartTick = level.getServer().getTickCount();
        idleTicks = retargets = 0;
        if (evicted > 0) Constants.LOG.info("[balance] evicted {} leftover mobs", evicted);
        Constants.LOG.info("[balance] round {} start: {} ({} hp, near) vs {} ({} hp, far), {} blocks apart",
                round + 1, near.name(), near.fighter.getMaxHealth(), far.name(), far.fighter.getMaxHealth(), String.format(Locale.ROOT, "%.1f", distance));
    }

    private static void observe(ServerLevel level) {
        int elapsed = level.getServer().getTickCount() - roundStartTick;
        if (elapsed < SETTLE_TICKS) {
            a.fighter.getNavigation().stop();
            b.fighter.getNavigation().stop();
            return;
        }
        if (elapsed == SETTLE_TICKS) engage();
        int fought = elapsed - SETTLE_TICKS;
        if (fought % 40 == 0) CombatScenario.purge(level, a.fighter, b.fighter);
        if (TRACE && fought % 5 == 0) Constants.LOG.info(String.format(Locale.ROOT, "[balance-trace] t=%d d=%.1f | %s | %s", fought,
                a.fighter.position().subtract(b.fighter.position()).horizontalDistance(), state(a), state(b)));
        for (Side side : new Side[]{a, b}) {
            float health = side.fighter.getHealth();
            if (health < side.lastHealth) side.damageTaken += side.lastHealth - health;
            side.lastHealth = health;
            String move = side.fighter.getActiveAttack() == null ? null : side.fighter.getActiveAttack().id().getPath();
            if (move != null && !move.equals(side.lastMove)) side.casts.merge(move, 1, Integer::sum);
            side.lastMove = move;
        }
        boolean aDown = !a.fighter.isAlive(), bDown = !b.fighter.isAlive();
        if (aDown || bDown || fought >= ROUND_LIMIT_TICKS) {
            Side winner = aDown == bDown ? null : aDown ? b : a;
            end(level, winner, fought);
            return;
        }
        // Ink drops targets on both sides; in the wild the two would drift apart. Count the nudges it takes.
        boolean idle = a.fighter.getTarget() == null && b.fighter.getTarget() == null
                && !a.fighter.isAttacking() && !b.fighter.isAttacking();
        idleTicks = idle ? idleTicks + 1 : 0;
        if (idleTicks >= IDLE_TICKS) { engage(); retargets++; idleTicks = 0; }
    }

    private static final boolean TRACE = Boolean.parseBoolean(System.getenv("DIGICUBE_BALANCE_TRACE"));

    private static String state(Side side) {
        var f = side.fighter;
        var effects = f.getActiveEffects().stream().map(e -> e.getEffect().value().getDescriptionId().replaceAll(".*\\.", "") + e.getDuration()).toList();
        return String.format(Locale.ROOT, "%s hp=%.0f %s%s tgt=%s eff=%s", side.name(), f.getHealth(),
                f.getActiveAttack() == null ? "-" : f.getActiveAttack().id().getPath() + "@" + f.currentAttackTick(),
                f.getNavigation().isDone() ? " still" : " moving", f.getTarget() == null ? "none" : "yes", effects);
    }

    private static void engage() {
        a.fighter.setTarget(b.fighter);
        b.fighter.setTarget(a.fighter);
    }

    private static void end(ServerLevel level, Side winner, int ticks) {
        float left = winner == null ? 0 : winner.fighter.getHealth() / winner.fighter.getMaxHealth();
        rounds.add(new Round(winner == null ? "draw" : winner.name(), ticks, left, retargets, new double[]{a.damageTaken, b.damageTaken}));
        if (winner != null) winner.wins++;
        Constants.LOG.info(String.format(Locale.ROOT, "[balance] round %d: %s in %.1f s%s, %s took %.1f, %s took %.1f, retargets=%d",
                round + 1, winner == null ? "draw" : winner.name() + " wins", ticks / 20.0,
                winner == null ? "" : String.format(Locale.ROOT, " with %.0f%% health left", left * 100),
                a.name(), a.damageTaken, b.name(), b.damageTaken, retargets));
        for (Side side : new Side[]{a, b}) { side.crits += side.fighter.criticalHits(); side.dodges += side.fighter.dodges(); side.fighter.discard(); side.fighter = null; }
        round++;
        waitTicks = BETWEEN_ROUNDS_TICKS;
    }

    private static void summarize(ServerLevel level) {
        done = true;
        int draws = rounds.size() - a.wins - b.wins;
        var decided = rounds.stream().filter(r -> !r.winner().equals("draw")).mapToInt(Round::ticks).sorted().toArray();
        double mean = decided.length == 0 ? 0 : java.util.Arrays.stream(decided).average().orElse(0) / 20;
        double median = decided.length == 0 ? 0 : decided[decided.length / 2] / 20.0;
        Constants.LOG.info(String.format(Locale.ROOT,
                "[balance] RESULT %s_vs_%s level %d: %d rounds, %s wins %d (%.0f%%), %s wins %d (%.0f%%), draws %d; "
                        + "duration mean %.1f s, median %.1f s, min %.1f s, max %.1f s; retargets %d",
                a.name(), b.name(), LEVEL, rounds.size(), a.name(), a.wins, 100.0 * a.wins / rounds.size(),
                b.name(), b.wins, 100.0 * b.wins / rounds.size(), draws, mean, median,
                decided.length == 0 ? 0 : decided[0] / 20.0, decided.length == 0 ? 0 : decided[decided.length - 1] / 20.0,
                rounds.stream().mapToInt(Round::retargets).sum()));
        for (Side side : new Side[]{a, b}) {
            double taken = rounds.stream().mapToDouble(r -> r.damage()[side == a ? 0 : 1]).average().orElse(0);
            double leftWhenWinning = rounds.stream().filter(r -> r.winner().equals(side.name())).mapToDouble(Round::winnerHealthLeft).average().orElse(0);
            Constants.LOG.info(String.format(Locale.ROOT, "[balance] %s: casts %s, %.1f crits and %.1f dodges per round, took %.1f damage per round, kept %.0f%% health when winning; tactics %s",
                    side.name(), side.casts, (double) side.crits / rounds.size(), (double) side.dodges / rounds.size(), taken, leftWhenWinning * 100, new TreeMap<>(side.species.tactics().describe())));
        }
        level.getServer().halt(false);
    }
}
