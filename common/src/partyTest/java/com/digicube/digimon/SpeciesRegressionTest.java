package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.SharedConstants;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Map;

/** Checks catalog migration and malformed content without a game. */
public final class SpeciesRegressionTest {
    private SpeciesRegressionTest() {}

    /** @param args unused */
    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            DigimonSpeciesBootstrap.registerBuiltIn();
            check(DigimonSpeciesRegistry.size() == 3, "all bundled species loaded");
            var koromon = DigimonSpeciesRegistry.getOrThrow(Constants.id("koromon"));
            var bubble = koromon.attacks().getFirst();
            check(bubble.kind() == DigimonAttack.Kind.BUBBLES && bubble.cooldownTicks() == 40
                    && bubble.durationTicks() == 24 && bubble.hitTick() == 10 && bubble.range() == 8,
                    "bubble delivery and timing retained");
            check(koromon.evolutions().equals(List.of(Evolution.atLevel(Constants.id("agumon"), 5))),
                    "Koromon evolution preserved");
            var agumon = DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon"));
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
