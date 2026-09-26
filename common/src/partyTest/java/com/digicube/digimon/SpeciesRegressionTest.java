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
            net.minecraft.server.Bootstrap.bootStrap();
            DigimonSpeciesBootstrap.registerBuiltIn();
            com.digicube.entity.ConstrictionRegressionTest.run();
            check(DigimonSpeciesRegistry.size() == 24, "all bundled species loaded");
            var ganimon = DigimonSpeciesRegistry.getOrThrow(Constants.id("ganimon"));
            var ganimonGait = ganimon.locomotion().groundGait();
            // The wave gait: 12 ticks, 52 px forward and side-on, 44 px back;
            // the body faces its enemy close by and turns side-on when it hurries (travel_facing).
            check(ganimon.stage() == DigimonStage.CHILD && ganimon.attribute() == DigimonAttribute.DATA
                            && ganimon.attacks().stream().map(a -> a.id().getPath()).toList().equals(List.of("scissors_execution", "water_shot"))
                            && ganimonGait != null && ganimonGait.cycleTicks() == 12 && ganimonGait.directional()
                            && Math.abs(ganimonGait.fullSpeed(ganimon.body().modelScale()) - 3.25 * .2 / 12) < 1.0E-6
                            && ganimon.locomotion().travelFacing() != null && ganimon.locomotion().travelFacing().sideOnFrom() > ganimon.locomotion().walkSpeed()
                            && AuthoredAttacks.get(ganimon.attacks().getFirst()).leap() != null,
                    "Crabmon is a data rookie with a leaping Scissors Execution and Water Shot, on the authored wave gait");
            var meramon = DigimonSpeciesRegistry.getOrThrow(Constants.id("meramon"));
            var meramonGait = meramon.locomotion().groundGait();
            // Planted walk and run: one 18-tick phase, the run played in 13.
            check(meramon.stage() == DigimonStage.ADULT && meramon.attribute() == DigimonAttribute.DATA && meramon.attacks().isEmpty()
                            && meramonGait != null && meramonGait.cycleTicks() == 18 && meramonGait.directional()
                            && Math.abs(meramonGait.fullSpeed(meramon.body().modelScale()) - 5.6 * .26 / 18) < 1.0E-6
                            && Math.abs(meramonGait.runSpeed(meramon.body().modelScale()) - 10.4 * .26 / 13) < 1.0E-6,
                    "Meramon is a data champion with no attacks yet, on the authored planted stride");
            var mojyamon = DigimonSpeciesRegistry.getOrThrow(Constants.id("mojyamon"));
            check(mojyamon.attacks().stream().map(a -> a.id().getPath()).toList().equals(List.of("boomerang_bone", "icicle_rod"))
                            && ThrownAttacks.returning(mojyamon.attacks().get(0)) != null && ThrownAttacks.charged(mojyamon.attacks().get(1)) != null,
                    "Mojyamon throws a returning bone and a charged icicle");
            var mojyamonGait = mojyamon.locomotion().groundGait();
            // Planted walk and run: one 14-tick phase, the run played in 10, at the Golemon-sized scale .45.
            check(mojyamonGait != null && mojyamonGait.cycleTicks() == 14 && mojyamonGait.directional()
                            && Math.abs(mojyamonGait.fullSpeed(mojyamon.body().modelScale()) - 3.2 * .45 / 14) < 1.0E-6
                            && Math.abs(mojyamonGait.runSpeed(mojyamon.body().modelScale()) - 4.6 * .45 / 10) < 1.0E-6,
                    "Mojyamon's gait is the authored planted stride");
            var betamon = DigimonSpeciesRegistry.getOrThrow(Constants.id("betamon"));
            check(betamon.stage() == DigimonStage.CHILD && betamon.attribute() == DigimonAttribute.VIRUS
                    && betamon.locomotion().canSwim(), "Betamon is an amphibious virus rookie");
            check(betamon.attacks().stream().map(a -> a.id().getPath()).toList().equals(List.of("electric_shock", "headbutt")),
                    "Betamon signature burst priority and contact fallback");
            for (var move : betamon.attacks()) {
                var authored = AuthoredAttacks.get(move);
                check(authored.maxHits() == 1 && authored.hasWaterVariant(), "one hit per cast and matched water volumes");
                check(authored.samplesPerTick() == 8 && authored.motion(true).activeUntil() == move.motion().activeUntil(),
                        "preserve native sub-tick sampling and water release clock");
            }
            var digmon = DigimonSpeciesRegistry.getOrThrow(Constants.id("digmon"));
            check(digmon.locomotion().canFly() && !digmon.locomotion().canRun(),
                    "Digmon uses flight instead of running");
            check(digmon.attacks().stream().map(a -> a.id().getPath()).toList().equals(List.of("gold_rush", "big_crack")),
                    "Digmon has both authored signature attacks");
            var digmonGait = digmon.locomotion().groundGait();
            // The planted walk: .125 blocks a tick at full amplitude,
            // his walking, ridden and fighting pace.
            check(digmonGait != null && digmonGait.cycleTicks() == 16 && digmonGait.stride() == 4
                            && Math.abs(digmonGait.fullSpeed(digmon.body().modelScale()) - .125) < 1.0E-6,
                    "Digmon ground animation uses the planted 0.8-second, four-unit stride");
            com.digicube.entity.AuthoredAttackRegressionTest.run();
            com.digicube.entity.GolemonRegressionTest.run();
            com.digicube.entity.KineticRegressionTest.run();
            var centalmon = DigimonSpeciesRegistry.getOrThrow(Constants.id("centalmon"));
            check(DigimonSpeciesRegistry.resolve("centarumon").orElseThrow() == centalmon
                            && DigimonSpeciesRegistry.resolve("digicube:centarumon").orElseThrow() == centalmon
                            && DigimonSpeciesRegistry.resolve("Centarumon").orElseThrow() == centalmon,
                    "Centarumon command spelling resolves to the existing species");
            check(DigimonSpeciesRegistry.commandId(centalmon.id()).equals(Constants.id("centarumon"))
                            && DigimonSpeciesRegistry.resolve("centalmon").orElseThrow() == centalmon
                            && DigimonSpeciesRegistry.get(centalmon.id()).orElseThrow() == centalmon
                            && DigimonSpeciesRegistry.resolve("other:centarumon").isEmpty(),
                    "suggest Centarumon while preserving saved ids, old commands and namespaces");
            rejects(() -> DigimonSpeciesRegistry.setCommandNames(Map.of(centalmon.id(), Constants.id("agumon"))),
                    "command aliases cannot hide another species");
            check(centalmon.stage() == DigimonStage.ADULT && centalmon.attribute() == DigimonAttribute.DATA,
                    "Centarumon is a data champion registered as centalmon");
            check(centalmon.attacks().stream().map(a -> a.id().getPath()).toList().equals(List.of("hunting_cannon", "jet_dash"))
                            && centalmon.locomotion().canRun() && !centalmon.locomotion().canFly()
                            && !centalmon.locomotion().canSwim(),
                    "Centarumon uses cannon, retreat kick and approved ground gaits without basic melee");
            var steed = centalmon.body().mount().orElseThrow();
            check(steed.ownPace() && steed.sprint() > 1 && steed.turnRate() > 0 && steed.jump() > 0,
                    "Centarumon is ridden at its own pace, gallops on the sprint key, turns with weight and leaps on the jump key");
            check(steed.turnToTravel() && steed.cameraDistance() > 0 && centalmon.locomotion().groundGait().footfalls(),
                    "Centarumon walks along its own length under a rider, keeps the camera close and sounds its own hooves");
            check(steed.riderAttacks().size() == 2 && steed.riderAttacks().get(0).attack().getPath().equals("jet_dash")
                            && steed.riderAttacks().get(0).aim() == RiderAttack.Aim.CHARGE && steed.riderAttacks().get(0).input() == RiderAttack.Input.TAP
                            && steed.riderAttacks().get(0).cone() > 0 && steed.riderAttacks().get(0).reach() > 0,
                    "the quick button is the jet charge, homing on the enemy in its cone");
            check(steed.riderAttacks().get(1).attack().getPath().equals("hunting_cannon") && steed.riderAttacks().get(1).aim() == RiderAttack.Aim.SHOT
                            && steed.riderAttacks().get(1).input() == RiderAttack.Input.HOLD && steed.riderAttacks().get(1).move(),
                    "the special button draws the cannon like a bow and looses it on the run");
            var cannon = KineticAttacks.get(Constants.id("hunting_cannon"));
            check(cannon.riderDrawTick() > 0 && cannon.riderDrawTick() < cannon.attack().hitTick(), "the drawn cannon holds raised before it fires");
            var centalmonGait = centalmon.locomotion().groundGait();
            check(centalmonGait.cycleTicks() == 25 && centalmonGait.stride() == 2.5 && centalmonGait.runStride() == 12,
                    "Centarumon cadence uses the full cycle distance, not the stance sweep");
            for (float amount : new float[] {.005F, .025F, .075F, .125F, .25F, .5F, 1F}) {
                double travel = centalmonGait.fullSpeed(centalmon.body().modelScale()) * amount;
                check(Math.abs(centalmonGait.advance(travel, amount, centalmon.body().modelScale()) - 1) < 1e-6,
                        "Centarumon ground clock matches scaled partial stride");
            }
            check(centalmonGait.advance(0, 0, centalmon.body().modelScale()) == 0,
                    "Centarumon gait clock stops at zero travel");
            IkkakumonRegressionTest.run();
            var tentomon = DigimonSpeciesRegistry.getOrThrow(Constants.id("tentomon"));
            check(tentomon.locomotion().canFly() && !tentomon.locomotion().canSwim()
                    && tentomon.stage() == DigimonStage.CHILD && tentomon.attribute() == DigimonAttribute.VACCINE,
                    "Tentomon is a bipedal vaccine rookie with opt-in flight");
            check(tentomon.attacks().isEmpty() && tentomon.body().mount().isEmpty(),
                    "Tentomon only exposes approved locomotion, without unauthored attacks or a rider");
            check(!DigimonLocomotion.DEFAULT.canFly(), "flight is not implicitly granted to other rookies");
            var gomamon = DigimonSpeciesRegistry.getOrThrow(Constants.id("gomamon"));
            check(gomamon.stage() == DigimonStage.CHILD && gomamon.attribute() == DigimonAttribute.VACCINE,
                    "Gomamon is a vaccine rookie");
            check(gomamon.locomotion().canSwim() && gomamon.locomotion().swimSpeed() == .46
                            && !gomamon.locomotion().canRun() && gomamon.baseSpeed() == .08F && gomamon.locomotion().followSpeed(false) == 1.3,
                    "Gomamon walks on land and has independently configured fast swimming");
            check(gomamon.attacks().equals(List.of(DigimonSpeciesBootstrap.MARCHING_FISHES, DigimonSpeciesBootstrap.CLAW_ATTACK))
                            && gomamon.body().mount().isEmpty(),
                    "Gomamon prioritizes the fish wave, then alternating claws, and is not rideable");
            com.digicube.entity.MarchingFishesRegressionTest.run();
            var koromon = DigimonSpeciesRegistry.getOrThrow(Constants.id("koromon"));
            var tsunomon = DigimonSpeciesRegistry.getOrThrow(Constants.id("tsunomon"));
            var gabumon = DigimonSpeciesRegistry.getOrThrow(Constants.id("gabumon"));
            var garurumon = DigimonSpeciesRegistry.getOrThrow(Constants.id("garurumon"));
            var garurumonMount = garurumon.body().mount().orElseThrow();
            check(garurumon.stage() == DigimonStage.ADULT && garurumon.attribute() == DigimonAttribute.VACCINE
                            && garurumon.baseSpeed() > gabumon.baseSpeed(), "Garurumon is a fast vaccine champion");
            check(garurumon.body().modelScale() == 1 && garurumonMount.seat().y == 2.1875
                            && garurumonMount.seat().z == -.375 && garurumonMount.speed() == .5F
                            && garurumonMount.stepHeight() == 1, "Garurumon has the measured back seat and fast ridden pace");
            check(garurumon.locomotion().followSpeed(false) == garurumon.locomotion().followSpeed(true)
                            && garurumon.locomotion().followSpeed(false) * garurumon.baseSpeed() > .5,
                    "Garurumon keeps its fast pace whether or not its owner sprints");
            check(garurumon.attacks().equals(List.of(DigimonSpeciesBootstrap.FREEZE_FANG, DigimonSpeciesBootstrap.HOWLING_BLASTER)),
                    "Garurumon uses the authored frost combo");
            IceComboRegressionTest.run();
            check(gabumon.stage() == DigimonStage.CHILD && gabumon.attribute() == DigimonAttribute.DATA,
                    "Gabumon is a data rookie");
            check(gabumon.attacks().equals(List.of(DigimonSpeciesBootstrap.BLUE_BLASTER, DigimonSpeciesBootstrap.HORN_ATTACK))
                            && gabumon.evolutions().equals(List.of(Evolution.atLevel(Constants.id("garurumon"),20))), "Gabumon prioritizes fueled breath, then horn contact, with its level-20 route");
            check(DigimonSpeciesBootstrap.BLUE_BLASTER.cooldownTicks() == 0
                    && DigimonSpeciesBootstrap.BLUE_BLASTER.fuel().capacityTicks() == 80
                    && DigimonSpeciesBootstrap.HORN_ATTACK.knockback() == 0,
                    "Blue Blaster uses fuel and Horn Attack has no impulse");
            FuelRegressionTest.run();
            com.digicube.entity.FlameStreamRegressionTest.run();
            com.digicube.entity.AttackGeometryRegressionTest.run();
            com.digicube.entity.AttackTravelSyncRegressionTest.run();
            check(gabumon.body().modelScale() == .6F && gabumon.body().dimensions().width() == .95F
                    && gabumon.body().dimensions().height() == 1.45F && gabumon.body().mount().isEmpty(),
                    "Gabumon uses its own non-rideable dimensions");
            var follow = gabumon.locomotion();
            check(follow.canRun() && follow.followSpeed(true) == 1.65 && follow.followSpeed(false) == 1.15,
                    "Gabumon accelerates for sprint-following and returns to walking speed");
            check(!follow.canSwim() && !DigimonLocomotion.DEFAULT.canSwim(),
                    "existing land species do not gain aquatic movement");
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
            var mochimon = DigimonSpeciesRegistry.getOrThrow(Constants.id("mochimon"));
            check(mochimon.baseSpeed() == koromon.baseSpeed() && mochimon.baseSpeed() == tsunomon.baseSpeed()
                    && mochimon.locomotion().walkSpeed() == koromon.locomotion().walkSpeed()
                    && mochimon.locomotion().runSpeed() == tsunomon.locomotion().runSpeed(),
                    "Motimon travels at the same pace as Koromon and Tsunomon");
            var punch = mochimon.attacks().getFirst();
            var inflatedBubbles = mochimon.attacks().get(1);
            check(punch.alternateSides() && punch.kind() == DigimonAttack.Kind.BOX_SWEEP
                    && AuthoredAttacks.get(punch).maxHits() == 1 && CrackMark.charges(punch) == 0,
                    "Mochi Punch alternates physical contact without Crack or repeated damage");
            check(inflatedBubbles.id().equals(bubble.id()) && inflatedBubbles.kind() == bubble.kind()
                    && inflatedBubbles.power() == bubble.power() && inflatedBubbles.cooldownTicks() == bubble.cooldownTicks()
                    && inflatedBubbles.hitTick() == bubble.hitTick() && inflatedBubbles.motion() != null && bubble.motion() == null,
                    "Motimon changes the shared volley muzzle without changing the other babies");
            var pukamon = DigimonSpeciesRegistry.getOrThrow(Constants.id("pukamon"));
            var slap = pukamon.attacks().getFirst();
            var blownBubbles = pukamon.attacks().get(1);
            check(pukamon.stage() == DigimonStage.BABY_II && pukamon.locomotion().hovers() && !pukamon.locomotion().canFly()
                    && !mochimon.locomotion().hovers() && pukamon.baseSpeed() == mochimon.baseSpeed(),
                    "Bukamon hovers on its fins at Motimon's pace without burst flight");
            check(slap.alternateSides() && slap.kind() == DigimonAttack.Kind.BOX_SWEEP && AuthoredAttacks.get(slap).maxHits() == 1
                    && CrackMark.charges(slap) == 0 && blownBubbles.id().equals(bubble.id()) && blownBubbles.motion() != null
                    && blownBubbles.motion() != inflatedBubbles.motion(),
                    "Flipper Slap alternates flippers once per cast; Bubbles leaves Bukamon's own mouth");
            check(pukamon.evolutions().equals(List.of(Evolution.atLevel(Constants.id("gomamon"), 5))),
                    "Bukamon digivolves to Gomamon");
            check(tsunomon.body().equals(koromon.body()) && tsunomon.baseSpeed() == koromon.baseSpeed(),
                    "shared model scale and follow speed");
            check(koromon.evolutions().equals(List.of(Evolution.atLevel(Constants.id("agumon"), 5))),
                    "Koromon evolution preserved");
            var agumon = DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon"));
            for (String other : List.of("greymon", "koromon", "tsunomon")) {
                var unchanged = DigimonSpeciesRegistry.getOrThrow(Constants.id(other)).locomotion();
                check(unchanged.equals(DigimonLocomotion.DEFAULT) && !unchanged.canRun()
                                && unchanged.followSpeed(true) == unchanged.followSpeed(false),
                        other + " retains its original follow distances and speed even when the owner sprints");
            }
            // The native model brought a planted directional gait and its own scale, never a new pace or size.
            var paced = agumon.locomotion(); var defaults = DigimonLocomotion.DEFAULT;
            check(paced.followStartDistance() == defaults.followStartDistance() && paced.followStopDistance() == defaults.followStopDistance()
                            && paced.walkSpeed() == defaults.walkSpeed() && paced.runSpeed() == defaults.runSpeed()
                            && paced.swimSpeed() == defaults.swimSpeed() && paced.flight() == null && !paced.canRun()
                            && paced.groundGait() != null && paced.groundGait().directional(),
                    "agumon retains its original follow distances and speed on its planted gait");
            check(agumon.baseHealth() == 20 && agumon.baseAttack() == 6 && agumon.baseDefence() == 4
                    && agumon.baseSpeed() == .30F && agumon.body().dimensions().width() == DigimonBody.DEFAULT.dimensions().width()
                    && agumon.body().dimensions().height() == DigimonBody.DEFAULT.dimensions().height()
                    && agumon.body().dimensions().eyeHeight() == DigimonBody.DEFAULT.dimensions().eyeHeight()
                    && agumon.body().mount().isEmpty() && Math.abs(agumon.body().modelScale() - 1.3F / 7) < 1e-5,
                    "Agumon stats and dimensions preserved at the native model's scale");
            var leapingClaw = AuthoredAttacks.get(Constants.id("claw"));
            check(agumon.attacks().equals(List.of(DigimonSpeciesBootstrap.PEPPER_BREATH, leapingClaw.attack())),
                    "Agumon attack priority preserved");
            check(leapingClaw.leap() != null && leapingClaw.leap().edge() && leapingClaw.attack().alternateSides()
                    && leapingClaw.hitWindows().getFirst()[0] >= leapingClaw.leap().land() - 1
                    && leapingClaw.particles() == com.digicube.entity.StrikeParticles.CLAW,
                    "Agumon's claw is a leap that strikes as it lands, with either hand");
            check(agumon.evolutions().equals(List.of(Evolution.atLevel(Constants.id("greymon"), 20))), "Champion prototype has one level-20 route");
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
            data.add("attack_motion", GsonHelper.parse("{\"bubble_blow\":\"mochimon_bubbles\"}"));
            data.getAsJsonArray("attacks").add("bubble_blow");
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "duplicate overridden moves rejected");
            data.getAsJsonArray("attacks").remove(1);
            data.add("attack_motion", GsonHelper.parse("{\"bubble_blow\":\"mochi_punch\"}"));
            rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "mismatched muzzle clock rejected");
            data.remove("attack_motion");
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
            locomotion.addProperty("walk_speed", 1.15);
            locomotion.addProperty("swim_speed", .46);
            check(BundledSpeciesLoader.parse(Constants.id("test"), data, moves).locomotion().canSwim(),
                    "another species can opt into swimming through data alone");
            for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, 1.01}) {
                locomotion.addProperty("swim_speed", invalid);
                rejects(() -> BundledSpeciesLoader.parse(Constants.id("test"), data, moves), "invalid swimming speed rejected");
            }
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
