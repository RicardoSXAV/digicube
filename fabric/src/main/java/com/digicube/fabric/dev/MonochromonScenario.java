package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.RushAttacks;
import com.digicube.entity.BullRush;
import com.digicube.entity.DigimonEntity;
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
 * Headless check of Monochromon, ridden and wild: {@code DIGICUBE_SCENARIO=monochromon_checks}. A fake rider drives the
 * mount as a player's client would ({@link DigimonEntity#driveScenarioRider}): its cruise (an amble) and its gallop;
 * Guardy Tusk held along the view (a standing brace that stops the body, a rush that gathers to its pace, the blow on
 * release carrying it on, the reins back after it), held into a dummy ahead (the rush strikes it by itself: hurt, thrown
 * back and tossed up), and let go in the brace (a standing blow); Volcano Strike at a dummy (struck, burning). Then
 * Monochromon wild: a rush at prey from afar that strikes it, and the ball at prey on a pillar it cannot rush. The
 * verdict line starts with {@code [monochromon] RESULT}.
 */
public final class MonochromonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int FLOOR = 300, HALF = 20, BACK = -40, FAR = 60, WILD = 12;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static DigimonEntity mount, hunter;
    private static final List<DigimonEntity> dummies = new ArrayList<>();
    private static ServerPlayer rider;
    private static final List<Vec3> track = new ArrayList<>();
    private static final List<Integer> rushCodes = new ArrayList<>();
    private static final java.util.Map<Integer, Float> DAMAGE = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Double> THROWN = new java.util.HashMap<>(), TOSSED = new java.util.HashMap<>();
    private static int castTick = -1, releaseTick = -1, strikeTick = -1;
    private static boolean burned, done;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();

    private MonochromonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"monochromon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -2; cx <= 1; cx++) for (int cz = -3; cz <= 3; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                mount = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("monochromon")), 20, new Vec3(.5, FLOOR, -20));
                mount.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
                mount.setHealth(mount.getMaxHealth());
                rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "HornRider"));
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
                Constants.LOG.info("[monochromon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin();
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (rider.getVehicle() == mount) mount.positionRider(rider);
        } catch (RuntimeException e) {
            Constants.LOG.error("[monochromon] aborted", e);
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
        var spec = RushAttacks.get(mount.rushMove());
        plan.add(new Step("cruise", 80, () -> place(0, -30, 0), t -> keys(1, false, 0),
                () -> {
                    double pace = pace(40, 80);
                    return verdict(pace > .17 && pace < .3, "%.3f blocks a tick (an amble)", pace);
                }));
        plan.add(new Step("gallop", 110, () -> place(0, -30, 0), t -> keys(1, true, 0),
                () -> {
                    double pace = pace(70, 110);
                    return verdict(pace > .3 && pace < .5, "%.3f blocks a tick", pace);
                }));
        // Held along the view: the standing brace stops it, the rush gathers to its pace, the blow on release carries it on.
        plan.add(new Step("rush along the view", 110, () -> place(0, -32, 0),
                t -> {
                    keys(0, false, 0);
                    if (t == 3) cast(1);
                    if (t == 70) { mount.stopRiderAttack(rider); releaseTick = t; }
                },
                () -> {
                    int build = spec.build();
                    double braced = castTick < 0 ? 9 : pace(castTick + 2, castTick + build - 2);
                    double top = 0;
                    for (int i = castTick + build + spec.ramp() + 2; i < releaseTick; i++) top = Math.max(top, pace(i - 1, i));
                    double carried = releaseTick < 0 ? 0 : track.get(Math.min(track.size() - 1, releaseTick + 24)).subtract(track.get(releaseTick)).horizontalDistance();
                    boolean back = rushCodes.getLast() == 0;
                    return verdict(castTick >= 0 && braced < .02 && top > spec.pace() * .9 && carried > 1.5 && back,
                            "braced at %.3f blocks a tick, rushed at %.3f, the blow carried %.2f blocks, reins back %s", braced, top, carried, back);
                }));
        // Held into a dummy: the rush strikes it by itself, before any release.
        plan.add(new Step("rush into a dummy", 100, () -> { place(0, -34, 0); dummy(new Vec3(.5, FLOOR, -14)); },
                t -> {
                    keys(0, false, 0);
                    if (t == 3) cast(1);
                    if (strikeTick < 0 && BullRush.blow(mount.rushCode())) strikeTick = t;
                },
                () -> verdict(strikeTick > 0 && damage(0) > 0 && thrown(0) > .8 && tossed(0) > .2,
                        "struck by itself at %d, %.1f damage, thrown %.2f and tossed %.2f blocks a tick", strikeTick, damage(0), thrown(0), tossed(0))));
        // Let go in the brace: a standing blow.
        plan.add(new Step("released in the brace", 50, () -> place(0, -32, 0),
                t -> {
                    keys(0, false, 0);
                    if (t == 3) cast(1);
                    if (t == 9) { mount.stopRiderAttack(rider); releaseTick = t; }
                    if (strikeTick < 0 && BullRush.blow(mount.rushCode())) strikeTick = t;
                },
                () -> {
                    double carried = flat(9, 40);
                    return verdict(strikeTick > 0 && strikeTick <= 11 && carried > 1.2, "struck at %d, the lunge carried %.2f blocks", strikeTick, carried);
                }));
        plan.add(new Step("volcano strike", 60, () -> { place(0, -30, 0); dummy(new Vec3(.5, FLOOR, -21)); },
                t -> {
                    look(dummies.getFirst());
                    if (t == 3) cast(0);
                    if (dummies.getFirst().isOnFire()) burned = true;
                },
                () -> verdict(damage(0) > 0 && burned, "%.1f damage, alight %s", damage(0), burned)));
        plan.add(new Step("wild rush", 140, () -> { hunter(new Vec3(WILD + .5, FLOOR, -30)); dummy(new Vec3(WILD + .5, FLOOR, -18)); },
                t -> { if (t == 5) hunter.setTarget(dummies.getFirst()); },
                () -> {
                    int rushes = hunter.skillUses().getOrDefault("rush", 0);
                    return verdict(rushes > 0 && damage(0) > 0, "rushed %d times, %.1f damage", rushes, damage(0));
                }));
        plan.add(new Step("wild ball at a pillar", 140, () -> {
                    var level = mount.level();
                    for (int y = FLOOR; y < FLOOR + 3; y++) level.setBlock(new BlockPos(WILD, y, -20), Blocks.STONE.defaultBlockState(), 3);
                    hunter(new Vec3(WILD + .5, FLOOR, -30));
                    dummy(new Vec3(WILD + .5, FLOOR + 3, -19.5));
                },
                t -> {
                    if (t == 5) hunter.setTarget(dummies.getFirst());
                    if (dummies.getFirst().isOnFire()) burned = true;
                },
                () -> {
                    var level = mount.level();
                    for (int y = FLOOR; y < FLOOR + 3; y++) level.setBlock(new BlockPos(WILD, y, -20), Blocks.AIR.defaultBlockState(), 3);
                    return verdict(damage(0) > 0 && burned, "%.1f damage, alight %s", damage(0), burned);
                }));
        String only = System.getenv("DIGICUBE_MONOCHROMON_ONLY");
        if (only != null && !only.isBlank()) plan.removeIf(step -> !step.name().startsWith(only));
        return plan;
    }

    private static void begin() {
        stepTick = 0;
        track.clear(); rushCodes.clear();
        castTick = releaseTick = strikeTick = -1;
        burned = false;
        for (var dummy : dummies) dummy.discard();
        dummies.clear();
        if (hunter != null) { hunter.discard(); hunter = null; }
        mount.readyAttacks();
        steps.get(stepIndex).start().run();
        record();
    }

    private static void record() {
        track.add(mount.position());
        rushCodes.add(mount.rushCode());
        for (var dummy : dummies) {
            float lost = dummy.getMaxHealth() - dummy.getHealth();
            dummy.setHealth(dummy.getMaxHealth());
            DAMAGE.merge(dummy.getId(), lost, Float::sum);
            THROWN.merge(dummy.getId(), dummy.getDeltaMovement().horizontalDistance(), Math::max);
            TOSSED.merge(dummy.getId(), dummy.getDeltaMovement().y, Math::max);
        }
    }

    private static float damage(int i) { return i < dummies.size() ? DAMAGE.getOrDefault(dummies.get(i).getId(), 0F) : 0; }
    private static double thrown(int i) { return i < dummies.size() ? THROWN.getOrDefault(dummies.get(i).getId(), 0.0) : 0; }
    private static double tossed(int i) { return i < dummies.size() ? TOSSED.getOrDefault(dummies.get(i).getId(), 0.0) : 0; }

    private static double pace(int from, int to) {
        from = Math.clamp(from, 0, track.size() - 1); to = Math.clamp(to, 0, track.size() - 1);
        return to <= from ? 0 : flat(from, to) / (to - from);
    }
    private static double flat(int from, int to) {
        from = Math.clamp(from, 0, track.size() - 1); to = Math.clamp(to, 0, track.size() - 1);
        return track.get(to).subtract(track.get(from)).horizontalDistance();
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

    private static void cast(int slot) {
        if (castTick >= 0) return;
        if (!mount.scenarioRiderCast(rider, slot)) Constants.LOG.info("[monochromon] the press was refused at {}", stepTick);
        else castTick = stepTick;
    }

    private static void look(DigimonEntity dummy) {
        Vec3 to = dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
        rider.zza = rider.xxa = 0;
        mount.driveScenarioRider(true, false);
    }

    /** A dummy that takes hits and never dies, standing at {@code at}. */
    private static void dummy(Vec3 at) {
        var dummy = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, at);
        dummy.setNoAi(true);
        dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        dummies.add(dummy);
        DAMAGE.put(dummy.getId(), 0F);
        THROWN.put(dummy.getId(), 0.0);
        TOSSED.put(dummy.getId(), 0.0);
    }

    private static void hunter(Vec3 at) {
        hunter = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id("monochromon")), 20, at);
        hunter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        hunter.setHealth(hunter.getMaxHealth());
        hunter.snapTo(at.x, at.y, at.z, 0, 0);
        hunter.yBodyRot = hunter.yHeadRot = 0;
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
        Constants.LOG.info("[monochromon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
