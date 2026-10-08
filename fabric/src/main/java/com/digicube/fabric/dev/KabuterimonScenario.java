package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.ai.AerialInput;
import com.digicube.entity.ai.FlightPhase;
import com.digicube.platform.Services;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Headless check of Kabuterimon's agile flight and his moves on the wing: {@code DIGICUBE_SCENARIO=kabuterimon_checks}.
 * A fake rider flies him as a player's client would ({@link DigimonEntity#driveScenarioRider}, the aerial input fed as
 * the client sends it): the takeoff, cruise, the boost, a dive and the speed it carries on level, a dive at the ground
 * levelling into a skim, the hover, a barrel roll's throw aside, the slide, landing on the dive key; Beet Horn's gore on
 * the ground at a dummy a Rookie's height and its ram from the wing at one on a pillar, Mega Blaster passing a dummy that
 * sidesteps it (a shock) and striking one that stands (the whole shot), its two stacked uses, what the casts cost the
 * flight reserve and the slow refill in a fight. The verdict line starts with
 * {@code [kabuterimon] RESULT}.
 */
public final class KabuterimonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int FLOOR = 300, HALF = 40, BACK = -60, FAR = 140, SKY = 60;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static DigimonEntity mount, dummy;
    private static ServerPlayer rider;
    private static final List<Vec3> track = new ArrayList<>();
    private static final List<Double> fuel = new ArrayList<>();
    private static final List<BlockPos> pillars = new ArrayList<>();
    private static boolean done;
    private static int passed, total, rollsBefore, casts;
    private static float healthBefore, nearMiss, directHit;
    private static boolean sidestepped;
    private static double lowest;
    private static final List<String> failures = new ArrayList<>();

    private KabuterimonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"kabuterimon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -3; cx <= 2; cx++) for (int cz = -4; cz <= 9; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                mount = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("kabuterimon")), 20, new Vec3(.5, FLOOR, -20));
                mount.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
                mount.setHealth(mount.getMaxHealth());
                rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "BeetleRider"));
                mount.seatScenarioRider(rider);
                seat(rider, mount);
                mount.driveScenarioRider(true, false);
                steps = plan(level);
                stepIndex = 0;
                begin();
                return;
            }
            record();
            Step step = steps.get(stepIndex);
            if (++stepTick >= step.ticks()) {
                String verdict = step.judge().get();
                total++;
                String line = step.name() + ": " + verdict.substring(verdict.indexOf(' ') + 1);
                if (verdict.startsWith("PASS")) passed++; else failures.add(line);
                Constants.LOG.info("[kabuterimon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin();
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (rider.getVehicle() == mount) mount.positionRider(rider);
        } catch (RuntimeException e) {
            Constants.LOG.error("[kabuterimon] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -HALF; x <= HALF; x++) for (int z = BACK; z <= FAR; z++) {
            level.setBlock(new BlockPos(x, FLOOR - 1, z), stone, 2);
            for (int y = FLOOR; y <= FLOOR + SKY; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
        }
    }

    private static List<Step> plan(ServerLevel level) {
        var plan = new ArrayList<Step>();
        var flight = mount.aerialMount();
        var agility = flight.agility();
        var costs = mount.flightReserve().costs();
        int capacity = mount.getLocomotion().flight().capacityTicks();
        plan.add(new Step("takeoff", 40, () -> place(0, FLOOR, -20, 0, false), t -> keys(0, 0, t < 3, false, false, 0, 0), () -> {
            double rise = track.getLast().y - FLOOR;
            double spent = fuel.getFirst() - fuel.get(4);
            return verdict(mount.getFlightPhase() == FlightPhase.FLYING && rise > 2 && spent >= costs.takeoff() - 1,
                    "%s, %.2f blocks up, the takeoff took %.0f of the reserve", mount.getFlightPhase(), rise, spent);
        }));
        plan.add(new Step("cruise", 70, () -> place(0, FLOOR + 20, -40, 0, true), t -> keys(1, 0, false, false, false, 0, 0), () -> {
            double pace = pace(50, 69), drift = Math.abs(track.getLast().y - track.get(50).y);
            return verdict(Math.abs(pace - flight.cruiseSpeed()) < .03 && drift < .5,
                    "%.3f blocks a tick (cruise %.2f), %.2f blocks up or down", pace, flight.cruiseSpeed(), drift);
        }));
        plan.add(new Step("boost", 90, () -> place(0, FLOOR + 20, -40, 0, true), t -> keys(1, 0, false, true, false, 0, 0), () -> {
            double pace = pace(70, 89), rate = (fuel.get(70) - fuel.get(89)) / 19;
            return verdict(Math.abs(pace - flight.cruiseSpeed() * agility.boost()) < .06 && Math.abs(rate - costs.boost()) < .3,
                    "%.3f blocks a tick (%.2f boosted), the reserve %.2f a tick (the boost's %.1f)", pace, flight.cruiseSpeed() * agility.boost(), rate, costs.boost());
        }));
        plan.add(new Step("dive and carry", 60, () -> place(0, FLOOR + SKY - 4, -50, 0, true), t -> keys(1, 0, false, false, false, 0, t < 30 ? 70 : 0), () -> {
            double dive = track.get(29).distanceTo(track.get(28)), carried = pace(45, 59);
            return verdict(dive > 1.25 && carried > .85 && lowest > 1,
                    "%.2f blocks a tick at the bottom of the dive, %.2f carried on level 15 ticks later, never under %.1f blocks", dive, carried, lowest);
        }));
        plan.add(new Step("dive at the ground", 50, () -> place(0, FLOOR + 14, -50, 0, true), t -> keys(1, 0, false, false, false, 0, 60), () -> {
            double end = track.getLast().distanceTo(track.get(track.size() - 2));
            return verdict(lowest > .5 && end > .6 && mount.getFlightPhase() == FlightPhase.FLYING,
                    "never lower than %.2f blocks over the ground, skimming on at %.2f blocks a tick, %s", lowest, end, mount.getFlightPhase());
        }));
        plan.add(new Step("hover", 45, () -> place(0, FLOOR + 20, -40, 0, true), t -> keys(t < 20 ? 1 : 0, 0, false, false, false, 0, 0), () -> {
            double end = track.getLast().distanceTo(track.get(track.size() - 2));
            return verdict(end < .02, "%.3f blocks a tick 25 ticks after letting go", end);
        }));
        plan.add(new Step("barrel roll", 40, () -> { place(0, FLOOR + 20, -40, 0, true); rollsBefore = mount.rolls(); },
                t -> keys(1, 1, t == 10 || t == 12, false, false, 0, 0), () -> {
            double aside = track.get(30).x - track.get(10).x, spent = fuel.get(10) - fuel.get(14);
            return verdict(mount.rolls() == rollsBefore + 1 && mount.lastRollSide() > 0 && aside > 2.5 && spent >= costs.roll() - 2,
                    "%d roll(s) to the %s, thrown %.2f blocks aside over 20 ticks, %.0f of the reserve", mount.rolls() - rollsBefore,
                    mount.lastRollSide() > 0 ? "left" : "right", aside, spent);
        }));
        plan.add(new Step("slide", 40, () -> place(0, FLOOR + 20, -40, 0, true), t -> keys(0, 1, false, false, false, 0, 0), () -> {
            double aside = (track.get(39).x - track.get(25).x) / 14;
            return verdict(Math.abs(aside - flight.cruiseSpeed() * agility.strafe()) < .05, "%.3f blocks a tick to the left (%.2f)", aside, flight.cruiseSpeed() * agility.strafe());
        }));
        plan.add(new Step("landing on the dive key", 70, () -> place(0, FLOOR + 6, -40, 0, true), t -> keys(0, 0, false, false, true, 0, 0), () ->
                verdict(mount.getFlightPhase() == FlightPhase.GROUNDED || mount.getFlightPhase() == FlightPhase.LANDING,
                        "%s after 70 ticks on the dive key, %.2f blocks over the ground", mount.getFlightPhase(), track.getLast().y - FLOOR)));
        // Beet Horn on the ground at a small dummy (a Rookie's height) ahead: the gore's low sweep reaches it.
        plan.add(new Step("Beet Horn on the ground", 30, () -> {
            place(0, FLOOR, -30, 0, false);
            dummyAt(level, new Vec3(.5, FLOOR, -30 + 3.6), false);
        }, t -> { aim(dummy.getBoundingBox().getCenter()); if (t == 2) mount.scenarioRiderCast(rider, 0); feed(0, 0, false, false, false); }, () -> {
            float dealt = healthBefore - dummy.getHealth();
            return verdict(dealt > 0 && mount.getFlightPhase() == FlightPhase.GROUNDED, "the gore dealt %.1f to a dummy %.1f blocks tall, %s",
                    dealt, dummy.getBbHeight(), mount.getFlightPhase());
        }));
        // Beet Horn from the wing: a dummy on a pillar ahead at the mount's height, the crosshair on it, a ram.
        plan.add(new Step("Beet Horn on the wing", 30, () -> {
            place(0, FLOOR + 10, -30, 0, true);
            dummyAt(level, new Vec3(.5, FLOOR + 10, -30 + 9), true);
        }, t -> { aim(dummy.getBoundingBox().getCenter()); if (t == 2) casts += mount.scenarioRiderCast(rider, 0) ? 1 : 0; feed(0, 0, false, false, false); }, () -> {
            float dealt = healthBefore - dummy.getHealth();
            double spent = fuel.get(1) - fuel.get(4);
            return verdict(dealt > 0 && spent >= costs.attack() * capacity - 2,
                    "the ram dealt %.1f, the cast took %.0f of the reserve (%.0f a cast)", dealt, spent, costs.attack() * capacity);
        }));
        // Mega Blaster from a hover, aimed at a dummy: one that sidesteps the ball as it closes in (1.4 blocks clear of it,
        // once the ball is 4.5 blocks off) is shocked as it passes; one that stands is struck by the whole shot.
        plan.add(new Step("Mega Blaster passes close", 60, () -> {
            place(0, FLOOR + 5, -30, 0, true);
            dummyAt(level, new Vec3(.5, FLOOR, -30 + 12), false);
            sidestepped = false;
        }, t -> {
            if (!sidestepped) aim(dummy.getBoundingBox().getCenter());
            if (t == 2) mount.scenarioRiderCast(rider, 1);
            if (!sidestepped && !level.getEntitiesOfClass(com.digicube.entity.KineticProjectileEntity.class, dummy.getBoundingBox().inflate(4.5),
                    shot -> shot.getOwner() == mount).isEmpty()) {
                dummy.setPos(dummy.getX() + dummy.getBbWidth() + 1.4, dummy.getY(), dummy.getZ());
                sidestepped = true;
            }
            feed(0, 0, false, false, false);
        }, () -> {
            nearMiss = healthBefore - dummy.getHealth();
            return verdict(nearMiss > 0, "the shock as it passed the sidestepping dummy dealt %.1f", nearMiss);
        }));
        plan.add(new Step("Mega Blaster strikes", 60, () -> {
            place(0, FLOOR + 5, -30, 0, true);
            dummyAt(level, new Vec3(.5, FLOOR, -30 + 12), false);
        }, t -> { aim(dummy.getBoundingBox().getCenter()); if (t == 2) mount.scenarioRiderCast(rider, 1); feed(0, 0, false, false, false); }, () -> {
            directHit = healthBefore - dummy.getHealth();
            return verdict(directHit > nearMiss * 1.2, "the whole shot dealt %.1f (a shock in passing %.1f)", directHit, nearMiss);
        }));
        plan.add(new Step("two shots stacked", 60, () -> { place(0, FLOOR + 5, -30, 0, true); mount.readyAttacks(); casts = 0; }, t -> {
            aim(new Vec3(.5, FLOOR + 3, 0));
            if (t == 1 || t == 30) casts += mount.scenarioRiderCast(rider, 1) ? 1 : 0;
            feed(0, 0, false, false, false);
        }, () -> verdict(casts == 2 && mount.readyUses(mount.riderAttacks().get(1)) == 0,
                "%d of 2 casts left, %d use(s) ready after", casts, mount.readyUses(mount.riderAttacks().get(1)))));
        plan.add(new Step("three casts drain the reserve", 100, () -> { place(0, FLOOR + 20, -30, 0, true); mount.readyAttacks(); casts = 0; }, t -> {
            aim(new Vec3(.5, FLOOR, 0));
            if (t == 1 || t == 33 || t == 66) { mount.readyAttacks(); casts += mount.scenarioRiderCast(rider, 1) ? 1 : 0; }
            feed(0, 0, false, false, false);
        }, () -> verdict(casts == 3 && mount.flightReserve().mustLand() && mount.flightRecharge() < .5,
                "%d casts left %.0f%% of the reserve (must land: %s), refilling at %.2f of its rate in the fight", casts,
                mount.flightReserve().fraction() * 100, mount.flightReserve().mustLand(), mount.flightRecharge())));
        return plan;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static void begin() {
        stepTick = 0;
        track.clear(); fuel.clear();
        lowest = Double.MAX_VALUE;
        mount.readyAttacks();
        mount.flightReserve().restore(mount.getLocomotion().flight().capacityTicks(), 0);
        steps.get(stepIndex).start().run();
        record();
    }

    private static void record() {
        track.add(mount.position());
        fuel.add(mount.flightReserve().charge());
        lowest = Math.min(lowest, mount.getY() - FLOOR);
    }

    private static double pace(int from, int to) {
        int last = Math.min(to, track.size() - 1);
        return track.get(last).subtract(track.get(from)).length() / (last - from);
    }

    private static void place(double x, double y, double z, float yaw, boolean flying) {
        mount.snapTo(x + .5, y, z, yaw, 0);
        mount.setDeltaMovement(Vec3.ZERO);
        mount.yBodyRot = mount.yHeadRot = yaw;
        mount.setFlightPhase(flying ? FlightPhase.FLYING : FlightPhase.GROUNDED);
        mount.setNoGravity(flying);
        if (dummy != null) { dummy.discard(); dummy = null; }
        // a pillar left from an earlier step would stand in the next one's line of fire
        for (BlockPos block : pillars) mount.level().setBlock(block, Blocks.AIR.defaultBlockState(), 2);
        pillars.clear();
        keys(0, 0, false, false, false, yaw, 0);
    }

    private static void dummyAt(ServerLevel level, Vec3 at, boolean pillar) {
        if (pillar) for (int y = FLOOR; y < (int) at.y; y++) {
            BlockPos block = BlockPos.containing(at.x, y, at.z);
            level.setBlock(block, Blocks.STONE.defaultBlockState(), 2);
            pillars.add(block);
        }
        dummy = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, at);
        dummy.setNoAi(true);
        dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        healthBefore = dummy.getHealth();
    }

    /** The rider's crosshair on a point (the view from the eye). */
    private static void aim(Vec3 point) {
        Vec3 to = point.subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
    }

    private static void keys(float forward, float strafe, boolean jump, boolean sprint, boolean dive, float yaw, float pitch) {
        rider.setYRot(yaw);
        rider.setXRot(pitch);
        feed(forward, strafe, jump, sprint, dive);
    }

    /** The keys as the rider's client sends them: vanilla's axes and jump, the aerial input's bits. */
    private static void feed(float forward, float strafe, boolean jump, boolean sprint, boolean dive) {
        rider.zza = forward;
        rider.xxa = strafe;
        rider.setJumping(jump);
        rider.setSprinting(sprint);
        mount.aerialRiding().accept(new AerialInput(jump, false, sprint, dive));
        mount.driveScenarioRider(true, dive);
    }

    private static String verdict(boolean pass, String format, Object... args) {
        return (pass ? "PASS " : "FAIL ") + String.format(java.util.Locale.ROOT, format, args);
    }

    /** Fabric's fake player refuses every ride, so the two ends of the vanilla link are set directly (development only). */
    private static void seat(ServerPlayer player, DigimonEntity vehicle) {
        try {
            var seat = net.minecraft.world.entity.Entity.class.getDeclaredField("vehicle");
            var riders = net.minecraft.world.entity.Entity.class.getDeclaredField("passengers");
            seat.setAccessible(true); riders.setAccessible(true);
            seat.set(player, vehicle);
            riders.set(vehicle, com.google.common.collect.ImmutableList.of(player));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot seat the fake rider", e);
        }
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[kabuterimon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
