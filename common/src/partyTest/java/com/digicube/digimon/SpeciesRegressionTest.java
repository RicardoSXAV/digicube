package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.SharedConstants;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Map;

/** Checks catalog migration, shared bubble behavior and malformed content without a game. */
public final class SpeciesRegressionTest {
    private SpeciesRegressionTest() {}

    /** @param args unused */
    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            DigimonSpeciesBootstrap.registerBuiltIn();
            check(DigimonSpeciesRegistry.size() == 5, "all bundled species loaded");
            var koromon = DigimonSpeciesRegistry.getOrThrow(Constants.id("koromon"));
            var tsunomon = DigimonSpeciesRegistry.getOrThrow(Constants.id("tsunomon"));
            var gabumon = DigimonSpeciesRegistry.getOrThrow(Constants.id("gabumon"));
            check(gabumon.stage() == DigimonStage.CHILD && gabumon.attribute() == DigimonAttribute.DATA,
                    "Gabumon is a data rookie");
            check(gabumon.attacks().isEmpty() && gabumon.evolutions().isEmpty(),
                    "Gabumon model release has no unauthored attacks or evolutions");
            check(gabumon.body().modelScale() == .6F && gabumon.body().dimensions().width() == .95F
                    && gabumon.body().dimensions().height() == 1.45F && gabumon.body().mount().isEmpty(),
                    "Gabumon uses its own non-rideable dimensions");
            var follow = gabumon.locomotion();
            check(follow.canRun() && follow.followSpeed(true) == 1.65 && follow.followSpeed(false) == 1.15,
                    "Gabumon accelerates for sprint-following and returns to walking speed");
            check(follow.followStartDistance() == 4 && follow.followStopDistance() == 2,
                    "Gabumon follows before its owner gets far away, with a stop/start gap");
            check(tsunomon.stage() == DigimonStage.BABY_II && tsunomon.attribute() == DigimonAttribute.FREE,
                    "Tsunomon is an in-training species");
            check(tsunomon.attacks().size() == 1 && tsunomon.attacks().getFirst() == koromon.attacks().getFirst(),
                    "Tsunomon reuses Koromon's exact shared attack");
            var bubble = tsunomon.attacks().getFirst();
            check(bubble.kind() == DigimonAttack.Kind.BUBBLES && bubble.cooldownTicks() == 40
                    && bubble.durationTicks() == 24 && bubble.hitTick() == 10 && bubble.range() == 8,
                    "bubble delivery and timing retained");
            check(tsunomon.body().equals(koromon.body()) && tsunomon.baseSpeed() == koromon.baseSpeed(),
                    "shared model scale and follow speed");
            check(koromon.evolutions().equals(List.of(Evolution.atLevel(Constants.id("agumon"), 5))),
                    "Koromon evolution preserved");
            var agumon = DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon"));
            for (String other : List.of("agumon", "greymon", "koromon", "tsunomon")) {
                var unchanged = DigimonSpeciesRegistry.getOrThrow(Constants.id(other)).locomotion();
                check(unchanged.equals(DigimonLocomotion.DEFAULT) && !unchanged.canRun()
                                && unchanged.followSpeed(true) == unchanged.followSpeed(false),
                        other + " retains its original follow distances and speed even when the owner sprints");
            }
            check(agumon.baseHealth() == 20 && agumon.baseAttack() == 6 && agumon.baseDefence() == 4
                    && agumon.baseSpeed() == .30F && agumon.body().equals(DigimonBody.DEFAULT),
                    "Agumon stats and dimensions preserved");
            check(agumon.attacks().equals(List.of(DigimonSpeciesBootstrap.PEPPER_BREATH, DigimonSpeciesBootstrap.CLAW)),
                    "Agumon attack priority preserved");
            check(agumon.evolutions().equals(List.of(new Evolution(Constants.id("greymon"), 16, 40, -1, 20, null),
                    Evolution.atLevel(Constants.id("greymon"), 20))), "evolution conditions and priority preserved");
            var greymon = DigimonSpeciesRegistry.getOrThrow(Constants.id("greymon"));
            check(greymon.baseHealth() == 40 && greymon.baseAttack() == 14 && greymon.baseDefence() == 10
                    && greymon.baseSpeed() == .32F, "Greymon stats preserved");
            check(greymon.attacks().equals(List.of(DigimonSpeciesBootstrap.MEGA_FLAME, DigimonSpeciesBootstrap.GREAT_ANTLER)),
                    "Greymon attack priority and motion profiles preserved");
            var body = greymon.body();
            var mount = body.mount().orElseThrow();
            check(body.modelScale() == 1.5F && body.dimensions().width() == 2.5F && body.dimensions().height() == 4.6F
                    && body.dimensions().eyeHeight() == 4.1F && mount.seat().y == 4.540426
                    && mount.seat().z == .507345 && mount.speed() == .32F && mount.stepHeight() == 1,
                    "approved Greymon dimensions and rider seat preserved");
            var data = GsonHelper.parse("""
                    {"stage":"baby_ii","attribute":"free","base_health":12,"base_attack":2,
                     "base_defence":2,"base_speed":0.25,"attacks":["bubble_blow"],"evolutions":[]}
                    """);
            var moves = Map.of(bubble.id(), bubble);
            data.addProperty("stage", "typo");
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "unknown stages rejected");
            data.addProperty("stage", "baby_ii");
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, Map.of()), "unknown moves rejected");
            data.addProperty("base_speed", Float.NaN);
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "nonfinite stats rejected");
            data.addProperty("base_speed", .25F);
            data.addProperty("base_health", 0);
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "invalid health rejected");
            data.addProperty("base_health", 12);
            var locomotion = GsonHelper.parse("""
                    {"follow_start_distance":4,"follow_stop_distance":2,"walk_speed":1.15,"run_speed":1.65}
                    """);
            data.add("locomotion", locomotion);
            check(BundledSpeciesLoader.parse(Constants.id("test"), data, moves).locomotion().equals(follow),
                    "running is available to another species through data alone");
            locomotion.addProperty("follow_stop_distance", 4);
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "empty follow hysteresis rejected");
            locomotion.addProperty("follow_stop_distance", 0);
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "zero stopping distance rejected");
            locomotion.addProperty("follow_stop_distance", 2);
            locomotion.addProperty("run_speed", Double.POSITIVE_INFINITY);
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "nonfinite run speed rejected");
            locomotion.addProperty("run_speed", 1.0);
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "running slower than walking rejected");
            locomotion.addProperty("run_speed", 1.65);
            locomotion.addProperty("walk_speed", -1);
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "negative walk speed rejected");
            Constants.LOG.info("Species regression checks passed.");
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
