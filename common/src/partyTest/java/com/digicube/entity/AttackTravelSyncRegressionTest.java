package com.digicube.entity;

import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpeciesBootstrap;

import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;

/**
 * The lunge smoothing against vanilla's, on the real travel curves and the real interpolation arithmetic:
 * a remote body must sit closer to the server without moving more roughly, on a clean link and a bad one.
 */
public final class AttackTravelSyncRegressionTest {
    private static final int TICKS = 48, TRIALS = 2000;

    private AttackTravelSyncRegressionTest() {}

    public static void run() {
        double worstLag = 0, worstJudder = 0;
        check(AttackTravelSync.steps(null, 5) == AttackTravelSync.VANILLA_STEPS, "No attack keeps vanilla smoothing");
        check(AttackTravelSync.steps(DigimonSpeciesBootstrap.MEGA_FLAME, 16) == AttackTravelSync.VANILLA_STEPS,
                "An attack that stands still keeps vanilla smoothing");
        for (DigimonAttack attack : List.of(DigimonSpeciesBootstrap.GREAT_ANTLER, DigimonSpeciesBootstrap.HORN_ATTACK,
                DigimonSpeciesBootstrap.FREEZE_FANG)) {
            var window = AttackTravelSync.window(attack.motion());
            String name = attack.id().getPath();
            check(window.from() > 0 && window.until() < attack.durationTicks(), name + " lunges inside its clip");
            check(AttackTravelSync.steps(attack, window.from() - 1) == AttackTravelSync.VANILLA_STEPS
                    && AttackTravelSync.steps(attack, window.from()) == AttackTravelSync.LUNGE_STEPS
                    && AttackTravelSync.steps(attack, window.until()) == AttackTravelSync.LUNGE_STEPS
                    && AttackTravelSync.steps(attack, window.until() + 1) == AttackTravelSync.VANILLA_STEPS,
                    name + " chases harder exactly while it travels");
            double full = attack.motion().sample(attack.durationTicks()).travel();
            // A thrust without knockback stops at its victim; the body must never be drawn past where the server stopped.
            for (double stop : new double[]{full, full * .6}) {
                for (double miss : new double[]{0, .05, .2, .4}) {
                    Result vanilla = measure(attack, stop, miss, tick -> AttackTravelSync.VANILLA_STEPS);
                    Result lunge = measure(attack, stop, miss, tick -> AttackTravelSync.steps(attack, tick));
                    String label = name + (stop < full ? " stopped early" : "") + " with " + Math.round(miss * 100) + "% late packets";
                    worstLag = Math.max(worstLag, lunge.lag / vanilla.lag);
                    worstJudder = Math.max(worstJudder, lunge.judder / vanilla.judder);
                    check(lunge.lag < vanilla.lag * .7, label + ": lag " + lunge.lag + " vs vanilla " + vanilla.lag);
                    check(lunge.judder <= vanilla.judder * 1.1, label + ": judder " + lunge.judder + " vs vanilla " + vanilla.judder);
                    check(lunge.overshoot <= 1.0E-9, label + ": drawn " + lunge.overshoot + " past the server");
                }
            }
        }
        System.out.printf("PASS: lunge smoothing keeps at most %.0f%% of the vanilla lag and %.0f%% of its judder%n", worstLag * 100, worstJudder * 100);
    }

    /**
     * @param lag mean distance behind the newest position a perfect link would have delivered
     * @param judder root mean square of the tick-to-tick speed change the authored curve does not have
     * @param overshoot furthest the body was drawn beyond the server's final position
     */
    private record Result(double lag, double judder, double overshoot) {}

    private static Result measure(DigimonAttack attack, double stop, double miss, IntUnaryOperator steps) {
        var motion = attack.motion();
        var window = AttackTravelSync.window(motion);
        var random = new Random(26);
        double lag = 0, judder = 0, overshoot = 0;
        int samples = 0;
        for (int trial = 0; trial < (miss == 0 ? 1 : TRIALS); trial++) {
            // InterpolationHandler: a packet restarts the countdown, each tick closes 1/steps of the gap.
            double[] drawn = new double[TICKS], ideal = new double[TICKS];
            double position = 0, target = 0;
            int left = 0, newest = -1;
            for (int tick = 0; tick < TICKS; tick++) {
                // Attack tick t moves to travel(t + 1) and reaches animation tick t + 1, or one later when it misses the client tick.
                int arrived = random.nextDouble() < miss ? tick - 2 : tick - 1;
                ideal[tick] = Math.min(stop, motion.sample(tick).travel());
                if (arrived > newest) {
                    newest = arrived;
                    double reported = Math.min(stop, motion.sample(arrived + 1).travel());
                    if (reported != target) { target = reported; left = steps.applyAsInt(tick); }
                }
                if (left > 0) position += (target - position) / left--;
                drawn[tick] = position;
            }
            for (int tick = Math.max(2, window.from()); tick <= window.until() + 3; tick++) {
                lag += Math.abs(drawn[tick] - ideal[tick]);
                double change = (drawn[tick] - 2 * drawn[tick - 1] + drawn[tick - 2]) - (ideal[tick] - 2 * ideal[tick - 1] + ideal[tick - 2]);
                judder += change * change;
                overshoot = Math.max(overshoot, drawn[tick] - Math.min(stop, motion.sample(TICKS).travel()));
                samples++;
            }
        }
        return new Result(lag / samples, Math.sqrt(judder / samples), overshoot);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
