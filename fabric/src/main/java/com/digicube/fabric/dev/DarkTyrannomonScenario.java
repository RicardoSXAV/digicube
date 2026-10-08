package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
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
 * Headless check of DarkTyrannomon ridden: {@code DIGICUBE_SCENARIO=darktyrannomon_checks}. A fake rider drives the
 * mount as a player's client would ({@link DigimonEntity#driveScenarioRider}). Standing, the view swings round: the body
 * steps round after it no faster than its pivot plants its feet, gathering into the turn and braking out of it, and gets
 * there. Walking, the view swings round: the body comes round faster than it does standing (its full turn rate back as it
 * walks off) and no faster than the sheet's rate. Then, standing again, a swing the other way. The verdict line starts
 * with {@code [darktyrannomon] RESULT}.
 */
public final class DarkTyrannomonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int FLOOR = 300, HALF = 20, BACK = -40, FAR = 60;
    /** Ticks the mount stands after it is placed before the first check swings the view (its first tick after a placing turns it). */
    private static final int SETTLE = 10;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static DigimonEntity mount;
    private static ServerPlayer rider;
    private static final List<Float> yaws = new ArrayList<>();
    private static final List<Vec3> track = new ArrayList<>();
    private static boolean done;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();

    private DarkTyrannomonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"darktyrannomon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -2; cx <= 1; cx++) for (int cz = -3; cz <= 3; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                mount = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("darktyrannomon")), 20, new Vec3(.5, FLOOR, -20));
                mount.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
                mount.setHealth(mount.getMaxHealth());
                rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "TyrantRider"));
                mount.seatScenarioRider(rider);
                seat(rider, mount);
                mount.driveScenarioRider(true, false);
                steps = plan();
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
                Constants.LOG.info("[darktyrannomon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin();
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (rider.getVehicle() == mount) mount.positionRider(rider);
        } catch (RuntimeException e) {
            Constants.LOG.error("[darktyrannomon] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -HALF; x <= HALF; x++) for (int z = BACK; z <= FAR; z++) {
            level.setBlock(new BlockPos(x, FLOOR - 1, z), stone, 2);
            for (int y = FLOOR; y <= FLOOR + 12; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
        }
    }

    private static List<Step> plan() {
        var plan = new ArrayList<Step>();
        float rate = mount.getLocomotion().groundGait().pivotTurnRate(mount.getBody().modelScale());
        float full = mount.getBody().mount().orElseThrow().turnRate();
        // Standing (settled for SETTLE ticks first), the view swings 150 degrees: the body steps round after it no faster
        // than its pivot plants its feet (about 5.6 degrees a tick), gathering into the turn and braking out of it, and gets there.
        plan.add(new Step("turn on the spot", SETTLE + 60, () -> place(0, -30, 0), t -> keys(0, false, t < SETTLE ? 0 : 150), () -> {
            float[] turn = turning(yaws, SETTLE);
            float left = Math.abs(Mth.wrapDegrees(150 - yaws.get(yaws.size() - 1)));
            return verdict(turn[0] > rate * .9F && turn[0] < rate + .01F && turn[1] < rate * .5F && turn[2] < rate * .5F && left < 1,
                    "turned at most %.2f degrees a tick (its pivot's %.2f), %.2f on its first tick and %.2f on its last, %.1f degrees short of the view at the end",
                    turn[0], rate, turn[1], turn[2], left);
        }));
        // Walking off with the view 90 degrees round, the body gets its full turn back: it comes round faster than it does
        // standing and no faster than the sheet's rate, and walks on along the new heading.
        plan.add(new Step("turn at a walk", 70, () -> place(0, -30, 0), t -> keys(1, false, 90), () -> {
            float[] turn = turning(yaws, 0);
            float left = Math.abs(Mth.wrapDegrees(90 - yaws.get(yaws.size() - 1)));
            double pace = pace(50, 69);
            return verdict(turn[0] > rate + 1 && turn[0] < full + .01F && left < 1 && pace > .15,
                    "turned at most %.2f degrees a tick (standing %.2f, the sheet's %.2f), %.1f degrees short of the view at the end, walking on at %.3f blocks a tick",
                    turn[0], rate, full, left, pace);
        }));
        // And the other way round, standing: the left pivot keeps the same pace as the right.
        plan.add(new Step("turn on the spot the other way", 60, () -> place(0, -30, 0), t -> keys(0, false, -150), () -> {
            float[] turn = turning(yaws, 0);
            float left = Math.abs(Mth.wrapDegrees(-150 - yaws.get(yaws.size() - 1)));
            return verdict(turn[0] > rate * .9F && turn[0] < rate + .01F && turn[1] < rate * .5F && turn[2] < rate * .5F && left < 1,
                    "turned at most %.2f degrees a tick (its pivot's %.2f), %.2f on its first tick and %.2f on its last, %.1f degrees short of the view at the end",
                    turn[0], rate, turn[1], turn[2], left);
        }));
        return plan;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static void begin() {
        stepTick = 0;
        yaws.clear(); track.clear();
        mount.readyAttacks();
        steps.get(stepIndex).start().run();
        record();
    }

    private static void record() {
        yaws.add(mount.getYRot());
        track.add(mount.position());
    }

    /** Blocks a tick the mount covered on average over ticks {@code from} to {@code to} of the check. */
    private static double pace(int from, int to) {
        return track.get(Math.min(to, track.size() - 1)).subtract(track.get(from)).horizontalDistance() / (Math.min(to, track.size() - 1) - from);
    }

    /**
     * A turn read from a facing each tick from {@code from} on: the most it turned in a tick, how far it turned on the
     * first tick it turned at all, and on the last.
     */
    private static float[] turning(List<Float> facing, int from) {
        float fastest = 0, first = -1, last = 0;
        for (int i = from + 1; i < facing.size(); i++) {
            float turn = Math.abs(Mth.wrapDegrees(facing.get(i) - facing.get(i - 1)));
            fastest = Math.max(fastest, turn);
            if (turn > 1.0E-3F) { if (first < 0) first = turn; last = turn; }
        }
        return new float[]{fastest, Math.max(0, first), last};
    }

    private static void place(double x, double z, float yaw) {
        mount.snapTo(x + .5, FLOOR, z, yaw, 0);
        mount.setDeltaMovement(Vec3.ZERO);
        mount.yBodyRot = mount.yHeadRot = yaw;
        rider.setYRot(yaw);
        rider.setXRot(0);
        keys(0, false, yaw);
    }

    private static void keys(float forward, boolean sprint, float yaw) {
        rider.zza = forward;
        rider.xxa = 0;
        rider.setJumping(false);
        rider.setSprinting(sprint);
        rider.setXRot(0);
        rider.setYRot(yaw);
        mount.driveScenarioRider(true, false);
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
        Constants.LOG.info("[darktyrannomon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
