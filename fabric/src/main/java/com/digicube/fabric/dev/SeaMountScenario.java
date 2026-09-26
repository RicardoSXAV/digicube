package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonBody;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.RiderAttack;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Headless check of riding in water: {@code DIGICUBE_SCENARIO=sea_mount_checks}. Every sea mount (a sheet with
 * {@code body.mount.water_turn_rate}) carries a fake rider through a deep pool with a quay, steered by keys and view as a
 * player's would be. The server moves it here ({@link DigimonEntity#driveScenarioRider}) through the same
 * getRiddenInput, tickRidden and getRiddenSpeed that a rider's client runs. It checks cruise and surge pace, a dive along
 * the view, the climb that holds at the surface, the rise and dive keys, a breach, hauling out onto a quay one block above
 * the water, the walk on land and back into the water, and then the rider's attacks afloat: a shot at prey on the pool
 * floor far below, a strike at depth. The verdict line starts with {@code [sea] RESULT}.
 */
public final class SeaMountScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    /** The pool: water from {@code FLOOR + 1} to the surface, a quay one block above the water from z = QUAY on. */
    private static final int FLOOR = 283, SURFACE = 300, QUAY = 10, HALF_X = 9, BACK = -40, FAR = 44, TOP = 312;
    /** Where the swimming checks set off: at the far end of the pool, so none of them reaches the quay. */
    private static final double START = BACK + 3;
    /** The attacks are tried on a lane of their own, clear of the one the swimming checks use. */
    private static final int LANE = 5;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<DigimonSpecies> species;
    private static int speciesIndex = -1, stepIndex, stepTick;
    private static List<Step> steps;
    private static DigimonEntity mount, dummy;
    private static ServerPlayer rider;
    private static final List<Vec3> track = new ArrayList<>();
    private static final List<Double> depth = new ArrayList<>();
    private static float healthBefore;
    private static boolean done, breached;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();
    /** DIGICUBE_SEA_TRACE=true: the mount's state every five ticks of every check. */
    private static final boolean TRACE = Boolean.parseBoolean(System.getenv("DIGICUBE_SEA_TRACE"));

    private SeaMountScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"sea_mount_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (species == null) {
                species = DigimonSpeciesRegistry.all().stream()
                        .filter(s -> s.body().mount().map(m -> m.waterTurnRate() > 0).orElse(false)).toList();
                for (int cx = -2; cx <= 1; cx++) for (int cz = -3; cz <= 2; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                nextSpecies(level);
                return;
            }
            if (mount == null) return;
            record(level);
            Step step = steps.get(stepIndex);
            if (TRACE && stepTick % 5 == 0) Constants.LOG.info("[sea-trace] {} {} t{} at {} moving {} wall {} fluid {} yaw {} pitch {}", mount.getSpeciesId().getPath(),
                    step.name(), stepTick, mount.position(), mount.getDeltaMovement(), mount.horizontalCollision,
                    String.format("%.2f", mount.getFluidHeight(FluidTags.WATER)), String.format("%.1f", mount.getYRot()), String.format("%.1f", mount.getXRot()));
            if (++stepTick >= step.ticks()) {
                String verdict = step.judge().get();
                total++;
                String line = mount.getSpeciesId().getPath() + " " + step.name() + ": " + verdict.substring(verdict.indexOf(' ') + 1);
                if (verdict.startsWith("PASS")) passed++; else failures.add(line);
                Constants.LOG.info("[sea] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (!verdict.startsWith("PASS")) Constants.LOG.info("[sea]   state: ticks {} in water {} fluid {} block {} controller {} removed {} at {}",
                        mount.tickCount, mount.isInWater(), String.format("%.2f", mount.getFluidHeight(FluidTags.WATER)),
                        level.getBlockState(mount.blockPosition()), mount.getControllingPassenger(), mount.isRemoved(), mount.position());
                if (++stepIndex >= steps.size()) { nextSpecies(level); return; }
                begin(level);
            }
            steps.get(stepIndex).input().accept(stepTick);
            mount.positionRider(rider);
        } catch (RuntimeException e) {
            Constants.LOG.error("[sea] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    // --- the pool ---------------------------------------------------------------------------------------------------

    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        var water = Blocks.WATER.defaultBlockState();
        for (int x = -HALF_X - 1; x <= HALF_X + 1; x++) for (int z = BACK - 1; z <= FAR + 1; z++) {
            boolean rim = Math.abs(x) > HALF_X || z < BACK || z > FAR;
            for (int y = FLOOR - 1; y <= TOP; y++) {
                var state = air;
                if (y == FLOOR - 1 || y == FLOOR) state = stone;
                else if (rim) state = y <= SURFACE + 6 ? stone : air;
                else if (z >= QUAY) state = y <= SURFACE ? stone : air;
                else if (y < SURFACE) state = water;
                level.setBlock(new BlockPos(x, y, z), state, 2);
            }
        }
    }

    // --- one species ------------------------------------------------------------------------------------------------

    private static void nextSpecies(ServerLevel level) {
        if (mount != null) mount.discard();
        if (dummy != null) { dummy.discard(); dummy = null; }
        if (++speciesIndex >= species.size()) { finish(level); return; }
        DigimonSpecies kind = species.get(speciesIndex);
        mount = DigimonEntity.spawnWild(level, kind, 20, new Vec3(.5, FLOOR + 6, -10));
        rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "SeaRider"));
        mount.seatScenarioRider(rider);
        seat(rider, mount);
        mount.driveScenarioRider(true, false);
        steps = plan(kind);
        stepIndex = 0;
        begin(level);
    }

    private static void begin(ServerLevel level) {
        stepTick = 0;
        track.clear();
        depth.clear();
        breached = false;
        purge(level);
        keys(0, 0, false, false, false, 0, 0);
        steps.get(stepIndex).start().run();
        mount.positionRider(rider);
    }

    /** Anything a night brought onto the quay leaves the pool (the wild spawner is stopped once, before the first mount). */
    private static void purge(ServerLevel level) {
        var area = new AABB(-HALF_X - 2, FLOOR - 2, BACK - 2, HALF_X + 2, TOP, FAR + 2);
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Mob.class), area,
                mob -> mob != mount && mob != dummy).forEach(net.minecraft.world.entity.Entity::discard);
    }

    private static void record(ServerLevel level) {
        track.add(mount.position());
        depth.add(mount.getFluidHeight(FluidTags.WATER));
    }

    private static List<Step> plan(DigimonSpecies kind) {
        DigimonBody.Mount m = kind.body().mount().orElseThrow();
        double swim = kind.locomotion().swimSpeed(), h = kind.body().dimensions().height();
        double afloat = SURFACE - .85 * h;
        List<Step> plan = new ArrayList<>();
        // A jet swimmer's pace is measured over whole pulses (36 ticks: two of a cruise's, three of a surge's) once under way.
        boolean jet = kind.locomotion().jet() != null;
        int span = jet ? 62 : 40;
        plan.add(new Step("cruise", span, () -> place(0, FLOOR + 5, START, 0), t -> keys(1, 0, false, false, false, 0, 0), () -> {
            double v = pace(25, span - 1), drift = Math.abs(track.get(span - 1).y - track.get(0).y);
            return verdict(Math.abs(v - swim) <= .15 * swim && drift < .6,
                    "%.3f blocks a tick (swim speed %.3f), %.2f blocks of drift up or down", v, swim, drift);
        }));
        plan.add(new Step("surge", span, () -> place(0, FLOOR + 5, START, 0), t -> keys(1, 0, false, true, false, 0, 0), () -> {
            double v = pace(25, span - 1), want = swim * m.waterSprint();
            return verdict(v >= .85 * want, "%.3f blocks a tick on the surge key (%.2f x the swim, %.3f)", v, m.waterSprint(), want);
        }));
        // A jet swimmer goes in pulses: fast after each squeeze, slow in the glide, pulse after pulse.
        if (jet) {
            int[] pulsesAt = new int[1];
            plan.add(new Step("jet pulses", 62, () -> { place(0, FLOOR + 5, START, 0); pulsesAt[0] = mount.jetPulses(); },
                    t -> keys(1, 0, false, false, false, 0, 0), () -> {
                double lo = 1e9, hi = 0;
                for (int i = 26; i < 61; i++) { double v = pace(i, i + 1); lo = Math.min(lo, v); hi = Math.max(hi, v); }
                int pulses = mount.jetPulses() - pulsesAt[0];
                return verdict(hi >= 1.6 * lo && pulses >= 3, "%d pulses in %d ticks, between %.3f and %.3f blocks a tick (%.1f x)",
                        pulses, 62, lo, hi, hi / Math.max(1.0E-6, lo));
            }));
        }
        plan.add(new Step("dive", 30, () -> place(0, SURFACE - h - 1.5, START, 0), t -> keys(1, 0, false, false, false, 45, 0), () -> {
            double down = (track.get(15).y - track.get(29).y) / 14, ahead = pace(15, 29), want = swim * Math.sin(Math.PI / 4);
            return verdict(down >= .6 * want && ahead >= .6 * want, "looking 45 degrees down it sinks %.3f and goes %.3f a tick (%.3f each along the view)", down, ahead, want);
        }));
        plan.add(new Step("surface", 110, () -> place(0, FLOOR + 2, START, 0), t -> keys(1, 0, false, false, false, -60, 0), () -> {
            double end = depth.get(depth.size() - 1) / h;
            double lo = 1e9, hi = -1e9;
            for (int i = depth.size() - 20; i < depth.size(); i++) { lo = Math.min(lo, track.get(i).y); hi = Math.max(hi, track.get(i).y); }
            boolean out = depth.stream().skip(40).anyMatch(d -> d <= 0);
            return verdict(end > .7 && end < .95 && hi - lo < .3 && !out,
                    "climbing at 60 degrees it stops at the surface with %.0f %% of its height in the water, %.2f blocks of bob%s", end * 100, hi - lo, out ? ", but left the water" : "");
        }));
        plan.add(new Step("rise key", 20, () -> place(0, FLOOR + 3, START, 0), t -> keys(0, 0, true, false, false, 0, 0), () -> {
            double up = track.get(19).y - track.get(0).y;
            return verdict(up >= 1, "rose %.2f blocks in a second on the jump key", up);
        }));
        plan.add(new Step("dive key", 20, () -> place(0, SURFACE - h - 3, START, 0), t -> keys(0, 0, false, false, true, 0, 0), () -> {
            double down = track.get(0).y - track.get(19).y;
            return verdict(down >= 1, "sank %.2f blocks in a second on the dive key", down);
        }));
        // Once its feet are out the keys go: it must fall back in, not surge on into the quay.
        plan.add(new Step("breach", 70, () -> place(0, SURFACE - h - 3, START, 0), t -> {
            if (mount.getY() > SURFACE + .05) breached = true;
            keys(breached ? 0 : 1, 0, false, !breached, false, -35, 0);
        }, () -> {
            double peak = track.stream().mapToDouble(Vec3::y).max().orElse(0);
            boolean back = depth.get(depth.size() - 1) > 0;
            return verdict(peak > SURFACE + .1 && back, "surging up at 35 degrees its feet leave the water by %.2f blocks%s",
                    peak - SURFACE, back ? " and it falls back in" : ", and it never came back down");
        }));
        plan.add(new Step("haul out", 90, () -> place(0, afloat, QUAY - kind.body().dimensions().width() * .5 - 1.2, 0),
                t -> keys(1, 0, false, false, false, 0, 0), () -> {
            Vec3 end = track.get(track.size() - 1);
            int onLand = -1;
            for (int i = 0; i < track.size(); i++) if (track.get(i).y >= SURFACE + 1 - .01 && depth.get(i) <= 0) { onLand = i; break; }
            return verdict(onLand >= 0 && end.z > QUAY && mount.onGround() && !mount.isInWater(),
                    "pushing into a quay one block above the water it %s (feet %.2f above the water at z %.1f)",
                    onLand >= 0 ? "is on it after " + onLand + " ticks" : "never got onto it", end.y - SURFACE, end.z);
        }));
        plan.add(new Step("walk", 60, () -> place(0, SURFACE + 1, QUAY + 4, 0), t -> keys(1, 0, false, false, false, 0, 0), () -> {
            double v = pace(40, 59);
            return verdict(v > .02 && mount.onGround(), "walks %.3f blocks a tick on land", v);
        }));
        if (m.sprint() > 1) plan.add(new Step("sprint", 80, () -> place(0, SURFACE + 1, QUAY + 4, 0), t -> keys(1, 0, false, true, false, 0, 0), () -> {
            double v = pace(60, 79);
            return verdict(v > .02, "runs %.3f blocks a tick on the sprint key", v);
        }));
        plan.add(new Step("into the water", 90, () -> place(0, SURFACE + 1, QUAY + 3, 180), t -> keys(1, 0, false, false, false, 20, 180), () -> {
            double end = depth.get(depth.size() - 1) / h;
            return verdict(mount.isInWater() && end > .35, "walked off the quay and swims, %.0f %% of its height in the water", end * 100);
        }));
        for (int slot = 0; slot < m.riderAttacks().size(); slot++) {
            RiderAttack spec = m.riderAttacks().get(slot);
            int s = slot;
            // Off the swimming lane: the prey stands on a pillar under water, a short shot's reach from the muzzle.
            if (spec.aim() == RiderAttack.Aim.SHOT) plan.add(new Step(spec.attack().getPath() + " at prey far below", 80,
                    () -> { place(LANE, afloat, -12, 0); pillar(-7, SURFACE - 9); prey(new Vec3(LANE + .5, SURFACE - 8, -6.5)); },
                    t -> { look(); if (t == 20 && !mount.startRiderAttack(rider, s)) Constants.LOG.info("[sea] the press was refused"); },
                    () -> hit("from the surface, %.0f degrees down")));
            if (spec.aim() == RiderAttack.Aim.SWEEP) plan.add(new Step(spec.attack().getPath() + " at depth", 60,
                    () -> { place(LANE, FLOOR + 1, -10, 0); prey(new Vec3(LANE + .5, FLOOR + 1, -10 + kind.body().dimensions().width() * .5 + 1.4)); },
                    t -> { look(); if (t == 10 && !mount.startRiderAttack(rider, s)) Constants.LOG.info("[sea] the press was refused"); },
                    () -> hit("on the pool floor, %.0f degrees down")));
            // A whip is held back, gathering momentum, and let go at the prey swimming below and ahead.
            if (spec.aim() == RiderAttack.Aim.WHIP) plan.add(new Step(spec.attack().getPath() + " whipped at depth", 70,
                    () -> { place(LANE, FLOOR + 3, -10, 0); prey(new Vec3(LANE + .5, FLOOR + 1, -10 + kind.body().dimensions().width() * .5 + 1.6)); },
                    t -> {
                        look();
                        if (t == 10 && !mount.startRiderAttack(rider, s)) Constants.LOG.info("[sea] the press was refused");
                        if (t == 24) mount.stopRiderAttack(rider);
                    },
                    () -> hit("wound up afloat and lashed down at the prey, %.0f degrees down")));
        }
        return plan;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static void place(double x, double y, double z, float yaw) {
        if (dummy != null) { dummy.discard(); dummy = null; }
        mount.snapTo(x + .5, y, z, yaw, 0);
        mount.setDeltaMovement(Vec3.ZERO);
        mount.yBodyRot = mount.yHeadRot = yaw;
        rider.setYRot(yaw);
        rider.setXRot(0);
    }

    private static void keys(float forward, float strafe, boolean jump, boolean sprint, boolean dive, float pitch, float yaw) {
        rider.zza = forward;
        rider.xxa = strafe;
        rider.setJumping(jump);
        rider.setSprinting(sprint);
        mount.driveScenarioRider(true, dive);
        rider.setXRot(pitch);
        rider.setYRot(yaw);
    }

    /** A stone column on the attack lane from the pool floor up to {@code top}. */
    private static void pillar(int z, int top) {
        for (int y = FLOOR + 1; y <= top; y++) mount.level().setBlock(new BlockPos(LANE, y, z), Blocks.STONE.defaultBlockState(), 2);
    }

    /** A dummy that takes hits and never dies, standing (or sunk) at {@code at}. */
    private static void prey(Vec3 at) {
        dummy = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, at);
        dummy.setNoAi(true);
        var health = dummy.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        healthBefore = dummy.getHealth();
    }

    /** The rider's crosshair on the dummy's chest. */
    private static void look() {
        if (dummy == null) return;
        Vec3 to = dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
        rider.zza = rider.xxa = 0;
    }

    private static String hit(String where) {
        float dealt = dummy == null ? 0 : healthBefore - dummy.getHealth();
        Vec3 to = dummy == null ? Vec3.ZERO : dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        double down = Math.toDegrees(Math.atan2(-to.y, to.horizontalDistance()));
        return verdict(dealt > 0, "%.1f damage to prey " + where, dealt, down);
    }

    /** Blocks a tick over the ground between two recorded ticks. */
    private static double pace(int from, int to) {
        Vec3 a = track.get(from), b = track.get(to);
        return Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / (to - from);
    }

    private static String verdict(boolean pass, String format, Object... args) {
        return (pass ? "PASS " : "FAIL ") + String.format(format, args);
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
        Constants.LOG.info("[sea] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
