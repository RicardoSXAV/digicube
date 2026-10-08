package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Headless check of Greymon, ridden and wild: {@code DIGICUBE_SCENARIO=greymon_checks}. A fake rider drives the mount as a
 * player's client would ({@link DigimonEntity#driveScenarioRider}): its walk and its run; turning on the spot no faster
 * than its pivot, and turning as it walks (its travel along its heading all the way round); a leap at the run that keeps
 * the run's pace (the air keeping {@code leap_carry} of it a tick). Great Antler: a charge at a small foe ahead, one
 * aimed up at a foe on a pillar, one from the run (no crouch, its pace kept into the charge) and one from a leap. Mega Flame: standing (struck, alight), on the run (the legs
 * keep running), from the top of a leap, and its burst catching a second foe beside the one it strikes. Then Greymon wild:
 * a charge at prey close by, and the fireball at prey further out. The verdict line starts with {@code [greymon] RESULT};
 * {@code DIGICUBE_GREYMON_ONLY=<prefix>} runs the checks named so.
 */
public final class GreymonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int FLOOR = 300, HALF = 22, BACK = -48, FAR = 64, WILD = 14, REST = 20;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static DigimonEntity mount, hunter;
    private static final List<DigimonEntity> dummies = new ArrayList<>();
    private static ServerPlayer rider;
    private static final List<Vec3> track = new ArrayList<>();
    private static final List<Boolean> grounded = new ArrayList<>();
    private static final List<Float> yaws = new ArrayList<>();
    private static final Map<Integer, Float> DAMAGE = new HashMap<>();
    private static final Map<String, Integer> CASTS = new HashMap<>();
    private static int castTick = -1;
    private static boolean castInAir, burned, done;
    private static float mountHealth;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();

    private GreymonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"greymon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -2; cx <= 1; cx++) for (int cz = -4; cz <= 4; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                mount = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("greymon")), 20, new Vec3(.5, FLOOR, -20));
                mount.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
                mount.setHealth(mount.getMaxHealth());
                rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "FlameRider"));
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
                Constants.LOG.info("[greymon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin();
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (rider.getVehicle() == mount) mount.positionRider(rider);
        } catch (RuntimeException e) {
            Constants.LOG.error("[greymon] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    // --- the arena --------------------------------------------------------------------------------------------------

    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -HALF; x <= HALF; x++) for (int z = BACK; z <= FAR; z++) {
            level.setBlock(new BlockPos(x, FLOOR - 1, z), stone, 2);
            for (int y = FLOOR; y <= FLOOR + 14; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
        }
    }

    private static void pillar(int x, int z, int height, boolean on) {
        var block = (on ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        for (int y = FLOOR; y < FLOOR + height; y++) mount.level().setBlock(new BlockPos(x, y, z), block, 3);
    }

    // --- the checks -------------------------------------------------------------------------------------------------

    private static List<Step> plan() {
        var plan = new ArrayList<Step>();
        plan.add(new Step("walk", 80, () -> place(0, -40, 0), t -> keys(1, false, false, 0, 0),
                () -> {
                    double pace = pace(40, 79);
                    return verdict(pace > .14 && pace < .32, "held W, %.3f blocks a tick", pace);
                }));
        plan.add(new Step("run", REST + 90, () -> place(0, -44, 0), t -> keys(t < REST ? 0 : 1, t >= REST, false, 0, 0),
                () -> {
                    double pace = pace(REST + 70, REST + 89);
                    return verdict(pace > .38 && pace < .7, "sprinting, %.3f blocks a tick after the build-up", pace);
                }));
        // Standing, the view swings 150 degrees: the body steps round after it no faster than its pivot plants its feet,
        // gathering into the turn and braking out of it, and gets there.
        plan.add(new Step("turn on the spot", 70, () -> place(0, -30, 0), t -> keys(0, false, false, 0, 150),
                () -> {
                    float rate = mount.getLocomotion().groundGait().pivotTurnRate(mount.getBody().modelScale());
                    float[] turn = turning(yaws, 0);
                    float left = Math.abs(Mth.wrapDegrees(150 - yaws.getLast()));
                    return verdict(turn[0] > rate * .8F && turn[0] < rate + .01F && turn[1] < rate * .6F && left < 2,
                            "turned at most %.2f degrees a tick (its pivot's %.2f), %.2f on its first tick, %.1f degrees short of the view at the end",
                            turn[0], rate, turn[1], left);
                }));
        // Walking, the view swings 90 degrees: the body comes round as it walks, its travel along its heading all the way.
        plan.add(new Step("walking turn", 100, () -> place(0, -40, 0),
                t -> keys(1, false, false, 0, t < 40 ? 0 : Math.min(90, (t - 40) * 3)),
                () -> {
                    double drift = 0;
                    for (int i = 41; i < track.size(); i++) {
                        Vec3 moved = moved(i);
                        if (moved.horizontalDistance() < .08) continue;
                        drift = Math.max(drift, offHeading(moved.x, moved.z, Math.toRadians(yaws.get(i))));
                    }
                    float left = Math.abs(Mth.wrapDegrees(90 - yaws.getLast()));
                    return verdict(drift < 15 && left < 5 && pace(80, 99) > .12,
                            "came round to within %.1f degrees of the view, its travel at most %.1f degrees off its heading, still walking %.3f",
                            left, drift, pace(80, 99));
                }));
        plan.add(new Step("running leap", REST + 110, () -> { place(0, -46, 0); mountHealth = mount.getHealth(); },
                t -> keys(t < REST ? 0 : 1, t >= REST, t == REST + 70, 0, 0),
                () -> {
                    int off = firstAir(REST + 70), on = off < 0 ? -1 : firstGround(off + 1);
                    if (off < 0 || on < 0) return verdict(false, "no leap (left the ground at %d, landed at %d)", off, on);
                    double length = flat(off, on), top = peak(off, on) - FLOOR, before = pace(off - 6, off - 1), after = pace(on, Math.min(track.size() - 1, on + 5));
                    boolean hurt = mount.getHealth() < mountHealth - 1e-3;
                    return verdict(length > 4 && top > 1 && after > before * .8 && !hurt,
                            "%.1f blocks long, %.2f up, %d ticks in the air, kept %.3f a tick, running %.3f before and %.3f after, hurt %s",
                            length, top, on - off, kept(off, on), before, after, hurt);
                }));
        // The air keeps leap_carry of a ridden leap's run a tick (vanilla's 0.91 without one), the keys let go once it is up.
        plan.add(new Step("leap carry", REST + 110, () -> place(0, -46, 0),
                t -> keys(t < REST || t > REST + 70 ? 0 : 1, t >= REST && t <= REST + 70, t == REST + 70, 0, 0),
                () -> {
                    int off = firstAir(REST + 70), on = off < 0 ? -1 : firstGround(off + 1);
                    if (off < 0 || on < 0) return verdict(false, "no leap (left the ground at %d, landed at %d)", off, on);
                    float carry = mount.getBody().mount().orElseThrow().leapCarry();
                    double kept = kept(off, on);
                    return verdict(Math.abs(kept - carry) < .01, "kept %.3f of its ground speed a tick in the air (leap_carry %.2f), %.1f blocks long in %d ticks",
                            kept, carry, flat(off, on), on - off);
                }));
        plan.add(new Step("charge ahead", 50, () -> { place(0, -10, 0); dummy(new Vec3(.5, FLOOR, -10 + 5.5)); },
                t -> { look(dummies.getFirst()); if (t == 5) cast(0); },
                () -> {
                    double dash = castTick < 0 ? 0 : flat(castTick, Math.min(track.size() - 1, castTick + 10));
                    return verdict(damage(0) > 0 && dash > 2, "struck the small foe for %.1f, the charge carried %.1f blocks", damage(0), dash);
                }));
        plan.add(new Step("charge aimed up", 60, () -> {
                    place(0, -10, 0);
                    pillar(0, -3, 4, true);
                    dummy(new Vec3(.5, FLOOR + 4, -2.5));
                },
                t -> { look(dummies.getFirst()); if (t == 5) cast(0); },
                () -> {
                    float up = rider.getXRot();
                    pillar(0, -3, 4, false);
                    return verdict(damage(0) > 0, "struck the foe on the pillar for %.1f, the view %.0f degrees up", damage(0), -up);
                }));
        // From the run the charge skips its crouch and keeps the run's pace into its burst.
        plan.add(new Step("running charge", REST + 90, () -> { place(0, -46, 0); dummy(new Vec3(.5, FLOOR, -46 + 30)); },
                t -> {
                    keys(t < REST ? 0 : 1, t >= REST, false, 0, 0);
                    // pressed with the foe six blocks ahead, within the charge's reach
                    if (t >= REST + 20) look(dummies.getFirst());
                    if (t >= REST + 20 && mount.position().distanceTo(dummies.getFirst().position()) < 6.5) cast(0);
                },
                () -> {
                    double slowest = 9;
                    for (int i = castTick + 1; castTick > 0 && i <= castTick + 4; i++) slowest = Math.min(slowest, pace(i - 1, i));
                    return verdict(castTick > 0 && slowest > .3 && damage(0) > 0,
                            "charged from the run, never under %.3f blocks a tick into the charge, struck for %.1f", slowest, damage(0));
                }));
        plan.add(new Step("charge from a leap", 60, () -> { place(0, -10, 0); dummy(new Vec3(.5, FLOOR, -10 + 7)); },
                t -> {
                    keys(0, false, t == 5, 0, 0);
                    look(dummies.getFirst());
                    if (t == 11) { castInAir = !mount.onGround(); cast(0); }
                },
                () -> verdict(castInAir && damage(0) > 0, "cast in the air %s, struck for %.1f", castInAir, damage(0))));
        plan.add(new Step("mega flame", 70, () -> { place(0, -20, 0); dummy(new Vec3(.5, FLOOR, -20 + 11)); },
                t -> {
                    look(dummies.getFirst());
                    if (t == 5) cast(1);
                    if (dummies.getFirst().isOnFire()) burned = true;
                },
                () -> verdict(damage(0) > 0 && burned, "%.1f damage, alight %s", damage(0), burned)));
        // On the run the legs keep running while the upper body looses the ball.
        plan.add(new Step("mega flame on the run", REST + 110, () -> { place(0, -46, 0); dummy(new Vec3(.5, FLOOR, -46 + 36)); },
                t -> {
                    keys(t < REST ? 0 : 1, t >= REST, false, 0, 0);
                    // pressed with the foe fourteen blocks ahead, within the ball's range as the body runs on
                    if (t >= REST + 20) look(dummies.getFirst());
                    rider.zza = t < REST ? 0 : 1;
                    if (t >= REST + 30 && mount.position().distanceTo(dummies.getFirst().position()) < 14) cast(1);
                    if (dummies.getFirst().isOnFire()) burned = true;
                },
                () -> {
                    double slowest = 9;
                    // through the cast up to the ball's release
                    for (int i = castTick + 2; castTick > 0 && i <= castTick + 14; i++) slowest = Math.min(slowest, pace(i - 1, i));
                    return verdict(castTick > 0 && slowest > .3 && damage(0) > 0 && burned,
                            "loosed on the run, never under %.3f blocks a tick through the cast, %.1f damage, alight %s", slowest, damage(0), burned);
                }));
        plan.add(new Step("mega flame from a leap", 70, () -> { place(0, -20, 0); dummy(new Vec3(.5, FLOOR, -20 + 10)); },
                t -> {
                    keys(0, false, t == 5, 0, 0);
                    look(dummies.getFirst());
                    if (t == 9) { castInAir = !mount.onGround(); cast(1); }
                },
                () -> verdict(castInAir && castTick > 0 && damage(0) > 0, "pressed in the air %s, cast %s, %.1f damage", castInAir, castTick > 0, damage(0))));
        // The burst catches a second foe beside the one the ball strikes.
        plan.add(new Step("mega flame's burst", 70, () -> {
                    place(0, -20, 0);
                    dummy(new Vec3(.5, FLOOR, -20 + 11));
                    dummy(new Vec3(1.7, FLOOR, -20 + 11.4));
                },
                t -> { look(dummies.getFirst()); if (t == 5) cast(1); },
                () -> verdict(damage(0) > 0 && damage(1) > 0, "the struck foe took %.1f, the one beside it %.1f", damage(0), damage(1))));
        // Wild: the mount and its rider wait at the far end.
        plan.add(new Step("wild charge", 120, () -> {
                    place(0, FAR - 4, 180);
                    hunter(new Vec3(WILD + .5, FLOOR, -20));
                    dummy(new Vec3(WILD + .5, FLOOR, -20 + 5));
                },
                t -> { keep(); watch(); },
                () -> verdict(CASTS.getOrDefault("great_antler", 0) > 0 && damage(0) > 0,
                        "charged %d times, flamed %d times, %.1f damage", CASTS.getOrDefault("great_antler", 0), CASTS.getOrDefault("mega_flame", 0), damage(0))));
        plan.add(new Step("wild flame", 140, () -> {
                    place(0, FAR - 4, 180);
                    hunter(new Vec3(WILD + .5, FLOOR, -24));
                    dummy(new Vec3(WILD + .5, FLOOR, -24 + 12));
                },
                t -> { keep(); watch(); if (dummies.getFirst().isOnFire()) burned = true; },
                () -> verdict(CASTS.getOrDefault("mega_flame", 0) > 0 && damage(0) > 0 && burned,
                        "flamed %d times, charged %d times, %.1f damage, alight %s", CASTS.getOrDefault("mega_flame", 0),
                        CASTS.getOrDefault("great_antler", 0), damage(0), burned)));
        String only = System.getenv("DIGICUBE_GREYMON_ONLY");
        if (only != null && !only.isBlank()) plan.removeIf(step -> !step.name().startsWith(only));
        return plan;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static void begin() {
        stepTick = 0;
        track.clear(); grounded.clear(); yaws.clear(); CASTS.clear();
        castTick = -1;
        castInAir = burned = false;
        for (var dummy : dummies) dummy.discard();
        dummies.clear();
        if (hunter != null) { hunter.discard(); hunter = null; }
        mount.interruptAttack();
        for (var ball : mount.level().getEntitiesOfClass(com.digicube.entity.KineticProjectileEntity.class, mount.getBoundingBox().inflate(80)))
            ball.discard();
        mount.readyAttacks();
        steps.get(stepIndex).start().run();
        record();
    }

    private static void record() {
        track.add(mount.position());
        grounded.add(mount.onGround());
        yaws.add(mount.getYRot());
        for (var dummy : dummies) {
            float lost = dummy.getMaxHealth() - dummy.getHealth();
            dummy.setHealth(dummy.getMaxHealth());
            DAMAGE.merge(dummy.getId(), lost, Float::sum);
        }
    }

    /** Counts each move the wild hunter starts. */
    private static DigimonAttack last;
    private static void watch() {
        DigimonAttack now = hunter == null || !hunter.isAttacking() ? null : hunter.getActiveAttack();
        if (now != null && now != last) CASTS.merge(now.id().getPath(), 1, Integer::sum);
        last = now;
    }

    private static float damage(int i) { return i < dummies.size() ? DAMAGE.getOrDefault(dummies.get(i).getId(), 0F) : 0; }

    private static Vec3 moved(int i) { return track.get(i).subtract(track.get(i - 1)).multiply(1, 0, 1); }

    /** The most a facing turned in a tick from {@code from} on, and how far it turned on the first tick it turned at all. */
    private static float[] turning(List<Float> facing, int from) {
        float fastest = 0, first = -1;
        for (int i = from + 1; i < facing.size(); i++) {
            float turn = Math.abs(Mth.wrapDegrees(facing.get(i) - facing.get(i - 1)));
            fastest = Math.max(fastest, turn);
            if (turn > 1.0E-3F && first < 0) first = turn;
        }
        return new float[]{fastest, Math.max(0, first)};
    }

    /** Degrees a move {@code x, z} runs off a heading of {@code yaw} radians. */
    private static double offHeading(double x, double z, double yaw) {
        double length = Math.hypot(x, z);
        return length < 1.0E-6 ? 0 : Math.toDegrees(Math.acos(Math.clamp((-Math.sin(yaw) * x + Math.cos(yaw) * z) / length, -1, 1)));
    }

    private static void place(double x, double z, float yaw) {
        mount.snapTo(x + .5, FLOOR, z, yaw, 0);
        mount.setDeltaMovement(Vec3.ZERO);
        mount.yBodyRot = mount.yHeadRot = yaw;
        rider.setYRot(yaw);
        rider.setXRot(0);
        keys(0, false, false, 0, yaw);
    }

    private static void keys(float forward, boolean sprint, boolean jump, float pitch, float yaw) {
        rider.zza = forward;
        rider.xxa = 0;
        rider.setJumping(jump);
        rider.setSprinting(sprint);
        rider.setXRot(pitch);
        rider.setYRot(yaw);
        mount.driveScenarioRider(true, false);
    }

    /** The wild hunter settles on the floor for a few ticks, then its AI is given the dummy to hunt. */
    private static void keep() {
        if (hunter != null && stepTick == SETTLE && !dummies.isEmpty()) hunter.setTarget(dummies.getFirst());
    }
    private static final int SETTLE = 5;

    private static void cast(int slot) {
        if (castTick >= 0) return;
        if (!mount.scenarioRiderCast(rider, slot)) Constants.LOG.info("[greymon] the press was refused at {}", stepTick);
        else castTick = stepTick;
    }

    /** The rider's crosshair on a dummy's chest. */
    private static void look(DigimonEntity dummy) {
        Vec3 to = dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
        mount.driveScenarioRider(true, false);
    }

    /** A dummy that takes hits and never dies, standing still at {@code at}. */
    private static void dummy(Vec3 at) {
        var dummy = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, at);
        dummy.setNoAi(true);
        dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        dummies.add(dummy);
        DAMAGE.put(dummy.getId(), 0F);
    }

    private static void hunter(Vec3 at) {
        hunter = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id("greymon")), 20, at);
        hunter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        hunter.setHealth(hunter.getMaxHealth());
        hunter.snapTo(at.x, at.y, at.z, 0, 0);
        hunter.yBodyRot = hunter.yHeadRot = 0;
        last = null;
    }

    /** Blocks a tick over the ground between two recorded ticks. */
    private static double pace(int from, int to) {
        from = Math.clamp(from, 0, track.size() - 1); to = Math.clamp(to, 0, track.size() - 1);
        return to <= from ? 0 : flat(from, to) / (to - from);
    }

    private static double flat(int from, int to) {
        from = Math.clamp(from, 0, track.size() - 1); to = Math.clamp(to, 0, track.size() - 1);
        return track.get(to).subtract(track.get(from)).horizontalDistance();
    }

    private static double peak(int from, int to) {
        double top = -1e9;
        for (int i = from; i <= to && i < track.size(); i++) top = Math.max(top, track.get(i).y);
        return top;
    }

    /**
     * The share of its ground speed the body kept each tick of a flight from {@code off} (its first tick off the ground) to
     * {@code on} (back on it): the median of each move against the one before, the takeoff and the landing left out.
     */
    private static double kept(int off, int on) {
        var ratios = new ArrayList<Double>();
        for (int i = off + 2; i < on; i++) {
            double before = track.get(i - 1).subtract(track.get(i - 2)).horizontalDistance(), now = track.get(i).subtract(track.get(i - 1)).horizontalDistance();
            if (before > 1.0E-3) ratios.add(now / before);
        }
        if (ratios.isEmpty()) return 0;
        java.util.Collections.sort(ratios);
        return ratios.get(ratios.size() / 2);
    }

    private static int firstAir(int from) {
        for (int i = from; i < grounded.size(); i++) if (!grounded.get(i)) return i;
        return -1;
    }

    private static int firstGround(int from) {
        for (int i = from; i < grounded.size(); i++) if (grounded.get(i)) return i;
        return -1;
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
        Constants.LOG.info("[greymon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
