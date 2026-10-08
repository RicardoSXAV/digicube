package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.SpinAttacks;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.ShellSpin;
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
 * Headless check of Shellmon, ridden and wild: {@code DIGICUBE_SCENARIO=shellmon_checks}. A fake rider drives the mount
 * as a player's client would ({@link DigimonEntity#driveScenarioRider}): its crawl and its two-handed heave, a turn on the
 * spot after the view (no faster than its hands step round), and its swim across a pool. Hydro Pressure held on a dummy
 * ahead: the jet strikes it, puts out the fire on it, shoves it back at once and drives it on, along the ground and only
 * so far; and on a Golemon pressed up against its face, which goes back, not up. Drill Shell: a
 * tap (it sets off weakly as soon as it is in its shell, and the reins are back once it is out) and a full spin-up into
 * a dummy (struck, thrown); a full spin follows a swung view far less than a weak one (harder to control fast); a spin
 * into a wall comes back off it. Then Shellmon wild at a dummy: it spins at it and plays its jet on it. Each step waits
 * for the last move to end. The verdict line starts with {@code [shellmon] RESULT}; {@code DIGICUBE_SHELLMON_ONLY=<prefix>}
 * runs the steps named so.
 */
public final class ShellmonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int FLOOR = 300, HALF = 24, BACK = -50, FAR = 60, WILD = 14, POOL_X = -18, POOL_Z = 20;
    /** Ticks after it sets off over which a spin's answer to a swung view is judged. */
    private static final int STEER_WINDOW = 16;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick, settleTicks;
    private static boolean settling;
    private static DigimonEntity mount, hunter;
    private static final List<DigimonEntity> dummies = new ArrayList<>();
    private static ServerPlayer rider;
    private static final List<Vec3> track = new ArrayList<>();
    private static final List<Float> yaws = new ArrayList<>();
    private static final List<Integer> spins = new ArrayList<>();
    private static final List<Vec3> dummyTrack = new ArrayList<>();
    /** The first dummy's highest point above where it stood, through a step. */
    private static double dummyRise;
    private static final Map<Integer, Float> DAMAGE = new HashMap<>();
    private static final Map<Integer, Double> THROWN = new HashMap<>();
    private static int castTick, launchTick, endTick;
    private static float launchSpeed, steerWeak = Float.NaN;
    private static boolean doused, hunterJet, hunterSpin, done;
    /** A wild jet's ticks with its water out, and those with it on the dummy; the pillar a dummy stands on. */
    private static int jetTicks, jetOn;
    private static final List<BlockPos> pillar = new ArrayList<>();
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();

    private ShellmonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"shellmon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -3; cx <= 2; cx++) for (int cz = -4; cz <= 4; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                mount = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("shellmon")), 20, new Vec3(.5, FLOOR, -30));
                mount.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
                mount.setHealth(mount.getMaxHealth());
                rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "ShellRider"));
                mount.seatScenarioRider(rider);
                seat(rider, mount);
                mount.driveScenarioRider(true, false);
                steps = plan();
                stepIndex = 0;
                begin();
                return;
            }
            if (settling) {
                // the last move plays out (the body back out of its shell, the jet's head come up) before the next step
                keys(0, 0, false, mount.getYRot());
                if (rider.getVehicle() == mount) mount.positionRider(rider);
                if (++settleTicks > 300 || settleTicks > 4 && !mount.isAttacking() && mount.spinCode() == 0) {
                    settling = false;
                    begin();
                }
                return;
            }
            clearMonsters(level);
            record();
            Step step = steps.get(stepIndex);
            if (++stepTick >= step.ticks()) {
                String verdict = step.judge().get();
                total++;
                String line = step.name() + ": " + verdict.substring(verdict.indexOf(' ') + 1);
                if (verdict.startsWith("PASS")) passed++; else failures.add(line);
                Constants.LOG.info("[shellmon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                settling = true;
                settleTicks = 0;
                return;
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (rider.getVehicle() == mount) mount.positionRider(rider);
        } catch (RuntimeException e) {
            Constants.LOG.error("[shellmon] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    /** Night monsters that wander onto the arena would be struck and steered round: they are sent away each tick. */
    private static void clearMonsters(ServerLevel level) {
        var arena = new net.minecraft.world.phys.AABB(-HALF - 21, FLOOR - 8, BACK - 1, HALF + 1, FLOOR + 14, FAR + 1);
        for (var monster : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, arena))
            monster.discard();
    }

    /** A stone floor, a wall across the far lane at z = 6 (x 8..16) and a pool off to one side. */
    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        var water = Blocks.WATER.defaultBlockState();
        for (int x = -HALF - 20; x <= HALF; x++) for (int z = BACK; z <= FAR; z++) {
            level.setBlock(new BlockPos(x, FLOOR - 1, z), stone, 2);
            for (int y = FLOOR; y <= FLOOR + 12; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
        }
        for (int x = 8; x <= 16; x++) for (int y = FLOOR; y < FLOOR + 4; y++) level.setBlock(new BlockPos(x, y, 6), stone, 2);
        // the pool: 25 wide, 39 long, 5 deep, its water up to the arena's floor
        for (int x = POOL_X - 26; x <= POOL_X; x++) for (int z = POOL_Z - 40; z <= POOL_Z; z++) {
            boolean rim = x == POOL_X - 26 || x == POOL_X || z == POOL_Z - 40 || z == POOL_Z;
            for (int y = FLOOR - 6; y < FLOOR; y++) level.setBlock(new BlockPos(x, y, z), rim || y == FLOOR - 6 ? stone : water, 2);
        }
    }

    private static List<Step> plan() {
        var plan = new ArrayList<Step>();
        var spec = SpinAttacks.get(mount.spinMove());
        int full = spec.withdraw() + spec.charge() + 8;
        plan.add(new Step("crawl", 90, () -> place(0, -40, 0), t -> keys(1, 0, false, 0),
                () -> {
                    double pace = pace(50, 90);
                    return verdict(pace > .13 && pace < .19, "%.3f blocks a tick (the hands drag it on)", pace);
                }));
        plan.add(new Step("heave", 110, () -> place(0, -40, 0), t -> keys(1, 0, true, 0),
                () -> {
                    double pace = pace(70, 110);
                    return verdict(pace > .26 && pace < .38, "%.3f blocks a tick (both hands heaving)", pace);
                }));
        // Standing, the view swung a third of the way round: the body steps round after it on its hands, on the spot.
        plan.add(new Step("turn on the spot", 80, () -> place(0, -40, 0), t -> keys(0, 0, false, 120),
                () -> {
                    float peak = 0;
                    for (int i = 1; i < yaws.size(); i++) peak = Math.max(peak, Math.abs(Mth.wrapDegrees(yaws.get(i) - yaws.get(i - 1))));
                    float limit = mount.getLocomotion().groundGait().pivotTurnRate(mount.getBody().modelScale());
                    float left = Math.abs(Mth.wrapDegrees(120 - yaws.getLast()));
                    double drift = flat(0, track.size() - 1);
                    return verdict(peak > 1.5 && peak <= limit * 1.05 && left < 5 && drift < 1,
                            "%.2f degrees a tick at most (its hands step round at %.2f), %.0f degrees short of the view, drifted %.2f blocks",
                            peak, limit, left, drift);
                }));
        plan.add(new Step("swim", 100, () -> place(POOL_X - 13, POOL_Z - 38, 0), t -> keys(1, 0, false, 0),
                () -> {
                    double pace = pace(50, 100);
                    boolean wet = mount.isInWater();
                    return verdict(wet && pace > .2 && pace < .45, "%.3f blocks a tick swimming (in the water %s)", pace, wet);
                }));
        // The jet held on a dummy ahead, alight: struck, put out, shoved back at once and driven on, along the ground and
        // only so far (the water's force falls off away from the mouth).
        plan.add(new Step("hydro pressure", 70, () -> { place(0, -40, 0); dummy(new Vec3(.5, FLOOR, -32)).setRemainingFireTicks(400); },
                t -> {
                    look(dummies.getFirst());
                    if (t == 3) cast(0);
                    if (!dummies.getFirst().isOnFire()) doused = true;
                    if (t == 66) mount.stopRiderAttack(rider);
                },
                () -> {
                    double shove = dummyMoved(4), second = dummyPace(16, 24);
                    double moved = dummies.getFirst().position().subtract(.5, FLOOR, -32).horizontalDistance();
                    return verdict(damage(0) > 0 && doused && shove > .5 && second > .05 && moved > 3 && moved < 11 && dummyRise < .3,
                            "%.1f damage, put out %s, shoved %.2f blocks in its first 4 ticks, still going %.3f blocks a tick a second in, "
                                    + "pushed %.1f blocks in all, rose %.2f", damage(0), doused, shove, second, moved, dummyRise);
                }));
        // A Golemon pressed up against its face (taller than the jet's mouth, so the water strikes it rising): back it goes,
        // not up, and not across the map.
        plan.add(new Step("hydro pressure on a golemon up close", 70, () -> { place(0, -40, 0); dummy(new Vec3(.5, FLOOR, -34.6), "golemon"); },
                t -> {
                    look(dummies.getFirst());
                    if (t == 3) cast(0);
                    if (t == 66) mount.stopRiderAttack(rider);
                },
                () -> {
                    double moved = dummies.getFirst().position().subtract(.5, FLOOR, -34.6).horizontalDistance();
                    double back = dummies.getFirst().getZ() + 34.6;
                    return verdict(damage(0) > 0 && back > 1.5 && moved < 8 && dummyRise < .25,
                            "%.1f damage, pushed back %.1f blocks (%.1f in all), rose %.2f", damage(0), back, moved, dummyRise);
                }));
        // A tap: it withdraws, sets off weakly as soon as it is in, comes back out, and walks on under the reins.
        plan.add(new Step("drill shell tap", 190, () -> place(0, -40, 0),
                t -> {
                    keys(endTick >= 0 ? 1 : 0, 0, false, 0);
                    if (t == 3) cast(1);
                    if (t == 6) mount.stopRiderAttack(rider);
                },
                () -> {
                    double carried = launchTick < 0 ? 0 : flat(launchTick, endTick < 0 ? track.size() - 1 : endTick);
                    double after = endTick < 0 ? 0 : flat(endTick + 10, Math.min(track.size() - 1, endTick + 30));
                    return verdict(launchTick - castTick >= spec.withdraw() && launchSpeed < spec.speed()[0] + .1 && carried > 2 && after > 1,
                            "in its shell %d ticks, set off at %.2f, spun %.1f blocks, out at %d, then walked %.1f blocks",
                            launchTick - castTick, launchSpeed, carried, endTick, after);
                }));
        // A full spin-up into a dummy ahead: it strikes it hard and throws it.
        plan.add(new Step("drill shell full", 180, () -> { place(0, -42, 0); dummy(new Vec3(.5, FLOOR, -26)); },
                t -> {
                    keys(0, 0, false, 0);
                    if (t == 3) cast(1);
                    if (t == full) mount.stopRiderAttack(rider);
                },
                () -> {
                    double moved = dummies.getFirst().position().subtract(.5, FLOOR, -26).horizontalDistance();
                    return verdict(launchSpeed > spec.top() * .9 && damage(0) > 0 && thrown(0) > .6 && moved > 2 && endTick > 0,
                            "set off at %.2f, %.1f damage, thrown at %.2f blocks a tick and %.1f blocks away, out at %d",
                            launchSpeed, damage(0), thrown(0), moved, endTick);
                }));
        // Steering: the view swung 90 degrees right after it sets off; a weak spin follows it far sooner than a full one.
        plan.add(new Step("drill shell steers weak", 150, () -> place(-10, -40, 0), t -> steered(t, 6),
                () -> { steerWeak = steer(); return verdict(steerWeak > 45, "its travel turned %.0f degrees in %d ticks", steerWeak, STEER_WINDOW); }));
        plan.add(new Step("drill shell steers full", 150, () -> place(-10, -40, 0), t -> steered(t, full),
                () -> {
                    float strong = steer();
                    return verdict(strong < steerWeak - 20 && strong > 3, "a full spin's travel turned %.0f degrees in %d ticks, a weak one's %.0f",
                            strong, STEER_WINDOW, steerWeak);
                }));
        // Into a wall ahead: it comes back off it.
        plan.add(new Step("drill shell off a wall", 160, () -> place(12, -10, 0),
                t -> {
                    keys(0, 0, false, 0);
                    if (t == 3) cast(1);
                    if (t == spec.withdraw() + 20) mount.stopRiderAttack(rider);
                },
                () -> {
                    // its first run at the wall: up to where it turned back (once off the wall it may skid round its end)
                    double nearest = Double.MAX_VALUE, back = 0;
                    int at = -1;
                    for (int i = 0; i < track.size(); i++) {
                        if (6 - track.get(i).z < nearest) { nearest = 6 - track.get(i).z; at = i; }
                        else if (at >= 0 && track.get(at).z - track.get(i).z > .5) break;
                    }
                    if (at >= 0) for (int i = at; i < Math.min(track.size(), at + 15); i++) back = Math.max(back, track.get(at).z - track.get(i).z);
                    return verdict(nearest < 2.2 && back > .8, "reached %.2f blocks from the wall, came back %.2f blocks off it", nearest, back);
                }));
        // The AI's jet on a dummy that a push moves: how much of the jet its water spends on the dummy, from up close, at
        // range on the ground, and up on a pillar (the jet falls on its way: it must be aimed up by the drop).
        plan.add(jetAim(5, 0));
        plan.add(jetAim(10, 0));
        plan.add(jetAim(14, 0));
        plan.add(jetAim(10, 3));
        plan.add(new Step("wild", 400, () -> { hunter(new Vec3(WILD + .5, FLOOR, -30)); dummy(new Vec3(WILD + .5, FLOOR, -21)); },
                t -> {
                    if (t == 5) hunter.setTarget(dummies.getFirst());
                    if (hunter.spinCode() != 0) hunterSpin = true;
                    if (hunter.currentAttackAnimation().equals("hydro_pressure")) hunterJet = true;
                },
                () -> verdict(hunterSpin && hunterJet && damage(0) > 0, "spun %s, jet %s, %.1f damage", hunterSpin, hunterJet, damage(0))));
        String only = System.getenv("DIGICUBE_SHELLMON_ONLY");
        if (only != null && !only.isBlank()) plan.removeIf(step -> !step.name().startsWith(only));
        return plan;
    }

    /**
     * Shellmon wild, Drill Shell on manual so only its jet is left to it, at a dummy {@code out} blocks ahead and
     * {@code up} blocks up a pillar: its water must be on the dummy through most of the jet.
     */
    private static Step jetAim(double out, int up) {
        String name = String.format(java.util.Locale.ROOT, "wild jet aim from %.0f blocks%s", out, up > 0 ? ", " + up + " up" : "");
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("shellmon"));
        var jet = species.attacks().stream().filter(a -> a.id().getPath().equals("hydro_pressure")).findFirst().orElseThrow();
        var drill = species.attacks().stream().filter(a -> a.id().getPath().equals("drill_shell")).findFirst().orElseThrow();
        return new Step(name, 220, () -> {
            hunter(new Vec3(WILD + .5, FLOOR, -30));
            hunter.setManual(drill, true);
            int z = (int) Math.round(-30 + out);
            for (int y = 0; y < up; y++) {
                var block = new BlockPos(WILD, FLOOR + y, z);
                mount.level().setBlock(block, Blocks.STONE.defaultBlockState(), 2);
                pillar.add(block);
            }
            dummy(new Vec3(WILD + .5, FLOOR + up, z + .5));
        }, t -> {
            if (t == 2) hunter.setTarget(dummies.getFirst());
            int at = hunter.currentAttackTick();
            if (hunter.currentAttackAnimation().equals("hydro_pressure") && at >= jet.motion().activeFrom() + 3 && at <= jet.motion().activeUntil()) {
                jetTicks++;
                var breath = hunter.serverBreath();
                if (breath != null && breath.touches(dummies.getFirst().getBoundingBox())) jetOn++;
            }
        }, () -> {
            double share = jetTicks == 0 ? 0 : jetOn / (double) jetTicks;
            return verdict(jetTicks >= 30 && share > .7, "its water on the dummy %.0f%% of %d jet ticks, %.1f damage", share * 100, jetTicks, damage(0));
        });
    }

    /** A spin let go at {@code release}, the view then swung 90 degrees to the right over 10 ticks as it sets off. */
    private static void steered(int t, int release) {
        float yaw = launchTick < 0 ? 0 : Math.min(90, (track.size() - 1 - launchTick) * 9F);
        keys(0, 0, false, yaw);
        if (t == 3) cast(1);
        if (t == release) mount.stopRiderAttack(rider);
    }

    /** How far the spin's travel turned over the first {@link #STEER_WINDOW} ticks after it set off (degrees). */
    private static float steer() {
        if (launchTick < 0 || track.size() < launchTick + STEER_WINDOW + 3) return 0;
        Vec3 a = track.get(launchTick + 2).subtract(track.get(launchTick)),
                b = track.get(launchTick + STEER_WINDOW + 2).subtract(track.get(launchTick + STEER_WINDOW));
        if (a.horizontalDistanceSqr() < 1.0E-6 || b.horizontalDistanceSqr() < 1.0E-6) return 0;
        float ya = (float) Math.toDegrees(Math.atan2(-a.x, a.z)), yb = (float) Math.toDegrees(Math.atan2(-b.x, b.z));
        return Math.abs(Mth.wrapDegrees(yb - ya));
    }

    private static void begin() {
        stepTick = 0;
        track.clear(); yaws.clear(); spins.clear(); dummyTrack.clear();
        castTick = launchTick = endTick = -1;
        launchSpeed = 0;
        dummyRise = 0;
        doused = hunterJet = hunterSpin = false;
        jetTicks = jetOn = 0;
        for (var block : pillar) mount.level().setBlock(block, Blocks.AIR.defaultBlockState(), 2);
        pillar.clear();
        for (var dummy : dummies) dummy.discard();
        dummies.clear();
        if (hunter != null) { hunter.discard(); hunter = null; }
        mount.readyAttacks();
        steps.get(stepIndex).start().run();
        record();
    }

    /** One sample a tick (index 0 as the step starts): the mount, its spin, the dummies' hits. */
    private static void record() {
        track.add(mount.position());
        yaws.add(mount.getYRot());
        int code = mount.spinCode();
        int before = spins.isEmpty() ? 0 : spins.getLast();
        spins.add(code);
        if (code != 0 && ShellSpin.phase(code) == ShellSpin.Phase.SPIN && (before == 0 || ShellSpin.phase(before) != ShellSpin.Phase.SPIN)) {
            launchTick = track.size() - 1;
            launchSpeed = mount.spinSpeed();
        }
        if (code == 0 && before != 0 && endTick < 0) endTick = track.size() - 1;
        for (var dummy : dummies) {
            float lost = dummy.getMaxHealth() - dummy.getHealth();
            dummy.setHealth(dummy.getMaxHealth());
            DAMAGE.merge(dummy.getId(), lost, Float::sum);
            THROWN.merge(dummy.getId(), dummy.getDeltaMovement().horizontalDistance(), Math::max);
        }
        if (!dummies.isEmpty()) {
            dummyTrack.add(dummies.getFirst().position());
            dummyRise = Math.max(dummyRise, dummies.getFirst().getY() - dummyTrack.getFirst().y);
        }
    }

    private static float damage(int i) { return i < dummies.size() ? DAMAGE.getOrDefault(dummies.get(i).getId(), 0F) : 0; }
    private static double thrown(int i) { return i < dummies.size() ? THROWN.getOrDefault(dummies.get(i).getId(), 0.0) : 0; }

    /** How far the first dummy went (blocks, level) in its first {@code ticks} ticks after something first moved it. */
    private static double dummyMoved(int ticks) {
        int first = -1;
        for (int i = 1; i < dummyTrack.size() && first < 0; i++) if (dummyTrack.get(i).subtract(dummyTrack.getFirst()).horizontalDistanceSqr() > 1.0E-4) first = i;
        if (first < 0) return 0;
        return dummyTrack.get(Math.min(dummyTrack.size() - 1, first - 1 + ticks)).subtract(dummyTrack.getFirst()).horizontalDistance();
    }

    /** The first dummy's pace (blocks a tick) between two ticks counted from when the jet first moved it. */
    private static double dummyPace(int from, int to) {
        int first = -1;
        for (int i = 1; i < dummyTrack.size() && first < 0; i++) if (dummyTrack.get(i).subtract(dummyTrack.getFirst()).horizontalDistanceSqr() > 1.0E-4) first = i;
        if (first < 0 || dummyTrack.size() <= first + to) return 0;
        return dummyTrack.get(first + to).subtract(dummyTrack.get(first + from)).horizontalDistance() / (to - from);
    }

    private static double pace(int from, int to) {
        from = Math.clamp(from, 0, track.size() - 1); to = Math.clamp(to, 0, track.size() - 1);
        return to <= from ? 0 : flat(from, to) / (to - from);
    }
    private static double flat(int from, int to) {
        from = Math.clamp(from, 0, track.size() - 1); to = Math.clamp(to, 0, track.size() - 1);
        return track.get(to).subtract(track.get(from)).horizontalDistance();
    }

    private static void place(double x, double z, float yaw) {
        double y = x <= POOL_X && x >= POOL_X - 26 && z <= POOL_Z && z >= POOL_Z - 40 ? FLOOR - 3 : FLOOR;
        mount.snapTo(x + .5, y, z, yaw, 0);
        mount.setDeltaMovement(Vec3.ZERO);
        mount.yBodyRot = mount.yHeadRot = yaw;
        keys(0, 0, false, yaw);
    }

    private static void keys(float forward, float left, boolean sprint, float yaw) {
        rider.zza = forward;
        rider.xxa = left;
        rider.setJumping(false);
        rider.setSprinting(sprint);
        rider.setXRot(0);
        rider.setYRot(yaw);
        mount.driveScenarioRider(true, false);
    }

    private static void cast(int slot) {
        if (castTick >= 0) return;
        if (!mount.scenarioRiderCast(rider, slot)) Constants.LOG.info("[shellmon] the press was refused at {}", stepTick);
        else castTick = track.size() - 1;
    }

    private static void look(DigimonEntity dummy) {
        Vec3 to = dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
        rider.zza = rider.xxa = 0;
        mount.driveScenarioRider(true, false);
    }

    /**
     * A dummy that takes hits and never dies, standing at {@code at}: its AI runs (so a push or a throw moves it, as it
     * would a fighter) but it has no goals, so it stands where it is put.
     */
    private static DigimonEntity dummy(Vec3 at) { return dummy(at, "agumon"); }

    private static DigimonEntity dummy(Vec3 at, String species) {
        var dummy = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id(species)), 20, at);
        dummy.removeAllGoals(goal -> true);
        dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        dummy.snapTo(at.x, at.y, at.z, 180, 0);
        dummies.add(dummy);
        DAMAGE.put(dummy.getId(), 0F);
        THROWN.put(dummy.getId(), 0.0);
        return dummy;
    }

    private static void hunter(Vec3 at) {
        hunter = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id("shellmon")), 20, at);
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
        Constants.LOG.info("[shellmon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
