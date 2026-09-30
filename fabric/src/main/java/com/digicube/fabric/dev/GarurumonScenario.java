package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.CombatMarkState;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.IceSlip;
import com.digicube.platform.Services;
import com.digicube.registry.DCEffects;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
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
 * Headless check of Garurumon, ridden and wild: {@code DIGICUBE_SCENARIO=garurumon_checks}. A fake rider drives the mount
 * as a player's client would ({@link DigimonEntity#driveScenarioRider}): its cruise and gallop, the gallop on ice (no
 * faster than on stone, gathered more slowly), a stop there that skids a few blocks on braced legs, a bend at the gallop
 * that the body runs through along its own length (its travel never more than a few degrees off its heading) and the
 * same bend on ice, a drift it gallops through along its heading, a turn on the spot no faster than its pivot
 * plants its paws (gathering into the turn and braking out of it), a standing leap and a leap at the gallop (which
 * must carry far and land unhurt), Freeze Fang dashed at a dummy ahead, at one off to the side (the dash follows the
 * crosshair, not the body) and from the top of a leap (diving onto the dummy), Howling Blaster steered across two
 * dummies, breathed from the top of a leap, held on one until its Freeze gauge fills, and the pounce that shatters it;
 * frost putting out fire and freezing water. Then Garurumon wild: its body coming round after its head no faster than its
 * pivot, and onto a path behind it (stepping round on the spot first, never walking sideways), at a walk and hurried;
 * its AI's pounce up close, a breath at prey off to its side (the body coming round steadily, the neck turning the rest
 * of the way), a breath from further out that freezes its prey and the pounce that shatters it, and a leap onto prey
 * standing on a ledge, pounced on from the air. The verdict line starts with {@code [garurumon] RESULT}.
 */
public final class GarurumonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    /** The arena: a stone floor to stand on at {@code FLOOR}, from x = -HALF to HALF and z = BACK to FAR. */
    private static final int FLOOR = 300, HALF = 22, BACK = -40, FAR = 60;
    /** Where the wild hunter's lane is, clear of the rider's, and where the ledge on it starts. */
    private static final int WILD = 12, LEDGE = -7;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static DigimonEntity mount, hunter;
    private static final List<DigimonEntity> dummies = new ArrayList<>();
    private static ServerPlayer rider;
    private static final List<Vec3> track = new ArrayList<>();
    private static final List<Boolean> grounded = new ArrayList<>();
    private static final List<Float> yaws = new ArrayList<>();
    /** The wild hunter's place and body facing, each tick of a check that has one. */
    private static final List<Vec3> hunterTrack = new ArrayList<>();
    private static final List<Float> hunterYaws = new ArrayList<>();
    /** Ticks a gallop check stands before it sets off. */
    private static final int REST = 15;
    /** The gallop's pace on stone, for the one on ice, and the ticks it takes to gather nine tenths of it. */
    private static double stoneGallop;
    private static int stoneGather;
    private static final List<Float> dealt = new ArrayList<>();
    private static float mountHealth, biteDamage;
    private static int castTick = -1, frozenAt = -1, shatterAt = -1, airStart = -1, pathDoneAt = -1;
    private static float shatterDamage, gaugeAfterBite, gaugePeak;
    /** The wild breath's widest aim off the body and its fastest body turn while it breathed. */
    private static float breathTwist, breathTurn, breathLimit;
    private static boolean done, castInAir;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();
    /** DIGICUBE_GARURUMON_TRACE=true: the wild hunter's state every tick of the wild checks. */
    private static final boolean TRACE = Boolean.parseBoolean(System.getenv("DIGICUBE_GARURUMON_TRACE"));

    private GarurumonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"garurumon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                for (int cx = -2; cx <= 1; cx++) for (int cz = -3; cz <= 3; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                mount = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("garurumon")), 20, new Vec3(.5, FLOOR, -20));
                mount.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
                mount.setHealth(mount.getMaxHealth());
                rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "WolfRider"));
                mount.seatScenarioRider(rider);
                seat(rider, mount);
                mount.driveScenarioRider(true, false);
                steps = plan();
                stepIndex = 0;
                begin(level);
                return;
            }
            record();
            Step step = steps.get(stepIndex);
            if (++stepTick >= step.ticks()) {
                String verdict = step.judge().get();
                total++;
                String line = step.name() + ": " + verdict.substring(verdict.indexOf(' ') + 1);
                if (verdict.startsWith("PASS")) passed++; else failures.add(line);
                Constants.LOG.info("[garurumon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin(level);
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (rider.getVehicle() == mount) mount.positionRider(rider);
            if (TRACE && hunter != null) Constants.LOG.info("[garurumon-trace] {} t{} at {} moving {} ground {} wall {} attack {} tick {}", step.name(), stepTick,
                    hunter.position(), hunter.getDeltaMovement(), hunter.onGround(), hunter.horizontalCollision, hunter.currentAttackAnimation(), hunter.currentAttackTick());
        } catch (RuntimeException e) {
            Constants.LOG.error("[garurumon] aborted", e);
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
            for (int y = FLOOR; y <= FLOOR + 12; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
        }
    }

    // --- the checks -------------------------------------------------------------------------------------------------

    private static List<Step> plan() {
        var plan = new ArrayList<Step>();
        plan.add(new Step("cruise", 80, () -> place(0, -30, 0),
                t -> keys(1, false, false, 0, 0),
                () -> {
                    double pace = pace(40, 79);
                    return verdict(pace > .2 && pace < .5, "held W, %.3f blocks a tick", pace);
                }));
        // Both gallops start from a standstill (the rider's momentum from the check before settles first).
        plan.add(new Step("gallop", REST + 80, () -> place(0, -30, 0),
                t -> keys(t < REST ? 0 : 1, t >= REST, false, 0, 0),
                () -> {
                    double pace = pace(REST + 60, REST + 79);
                    stoneGallop = pace;
                    stoneGather = gather(pace);
                    return verdict(pace > .6, "sprinting, %.3f blocks a tick after the build-up", pace);
                }));
        // Sure-footed on ice (ice_grip): its paws keep part of their grip, so the gallop gathers more slowly and runs no
        // faster than on stone.
        plan.add(new Step("gallop on ice", REST + 80, () -> { ice(true); place(0, -38, 0); },
                t -> keys(t < REST ? 0 : 1, t >= REST, false, 0, 0),
                () -> {
                    double pace = pace(REST + 60, REST + 79), stone = stoneGallop > 0 ? stoneGallop : .82;
                    int gathered = gather(pace);
                    ice(false);
                    return verdict(pace > .6 && pace < stone * 1.06 && gathered > stoneGather,
                            "sprinting on ice, %.3f blocks a tick (%.3f on stone), nine tenths of it in %d ticks (%d on stone)", pace, stone, gathered, stoneGather);
                }));
        // Let go on ice, it skids a few blocks: its legs (read from its moves as the client reads them, IceSlip) stop
        // driving at once, and the body slides on them, braced.
        plan.add(new Step("stop on ice", 90, () -> { ice(true); place(0, -38, 0); },
                t -> keys(t < 50 ? 1 : 0, t < 50, false, 0, 0),
                () -> {
                    int still = -1;
                    for (int i = 51; i < track.size(); i++) if (moved(i).horizontalDistance() < .02) { still = i - 50; break; }
                    double slid = flat(50, still > 0 ? still + 50 : track.size() - 1), grip = mount.getLocomotion().iceGrip(), legs = 0;
                    float skid = 1;
                    for (int i = 53; i <= 58; i++) {
                        double[] drive = IceSlip.legs(moved(i).x, moved(i).z, moved(i - 1).x, moved(i - 1).z, grip, grip);
                        legs = Math.max(legs, Math.hypot(drive[0], drive[1]));
                        skid = Math.min(skid, IceSlip.skid(moved(i).x, moved(i).z, drive[0], drive[1]));
                    }
                    ice(false);
                    return verdict(still >= 12 && still <= 40 && slid > 2.5 && slid < 6.5 && legs < .05 && skid > .9,
                            "let go at a gallop on ice, it skidded %.1f blocks and stood still %d ticks later, its legs driving at most %.3f blocks a tick and braced %.2f",
                            slid, still, legs, skid);
                }));
        // The view swings 60 degrees at a gallop: the body turns after it and its run goes round with it, never sideways.
        plan.add(new Step("gallop bend", 75, () -> place(0, -38, 0),
                t -> keys(1, true, false, 0, t < 45 ? 0 : Math.min(60, (t - 45) * 6)),
                () -> {
                    double drift = 0, speed = 0;
                    for (int i = 45; i < 74; i++) {
                        Vec3 moved = track.get(i + 1).subtract(track.get(i));
                        if (moved.horizontalDistance() < .2) continue;
                        speed = Math.max(speed, moved.horizontalDistance());
                        double yaw = Math.toRadians(yaws.get(i + 1));
                        double along = (-Math.sin(yaw) * moved.x + Math.cos(yaw) * moved.z) / moved.horizontalDistance();
                        drift = Math.max(drift, Math.toDegrees(Math.acos(Math.clamp(along, -1, 1))));
                    }
                    return verdict(speed > .6 && drift < 12, "turned 60 degrees at %.2f blocks a tick, its travel at most %.1f degrees off its heading",
                            speed, drift);
                }));
        // The same bend on ice: friction alone turns its run, so it drifts through the bend and comes round to its heading,
        // its legs (IceSlip) galloping along the body all the while rather than stepping aside.
        plan.add(new Step("drift on ice", 85, () -> { iceField(true); place(0, -38, 0); },
                t -> keys(1, true, false, 0, t < 45 ? 0 : Math.min(60, (t - 45) * 6)),
                () -> {
                    double drift = 0, end = 0, legsOff = 0, grip = mount.getLocomotion().iceGrip();
                    for (int i = 46; i < 85; i++) {
                        Vec3 moved = moved(i), before = moved(i - 1);
                        if (moved.horizontalDistance() < .2) continue;
                        double yaw = Math.toRadians(yaws.get(i));
                        double off = offHeading(moved.x, moved.z, yaw);
                        drift = Math.max(drift, off);
                        if (i >= 80) end = Math.max(end, off);
                        double[] legs = IceSlip.legs(moved.x, moved.z, before.x, before.z, grip, grip);
                        if (off > 10) legsOff = Math.max(legsOff, offHeading(legs[0], legs[1], yaw));
                    }
                    iceField(false);
                    return verdict(drift > 8 && drift < 50 && end < 10 && legsOff < 15,
                            "turned 60 degrees at a gallop on ice, its travel drifting up to %.1f degrees off its heading (%.1f at the end), its legs at most %.1f off it",
                            drift, end, legsOff);
                }));
        // Standing, the view swings 150 degrees: the body steps round after it no faster than its pivot plants (about 5
        // degrees a tick), gathering into the turn and braking out of it, and gets there.
        plan.add(new Step("turn on the spot", 50, () -> place(0, -30, 0),
                t -> keys(0, false, false, 0, 150),
                () -> {
                    float rate = mount.getLocomotion().groundGait().pivotTurnRate(mount.getBody().modelScale());
                    float[] turn = turning(yaws, 0);
                    float left = Math.abs(net.minecraft.util.Mth.wrapDegrees(150 - yaws.get(yaws.size() - 1)));
                    return verdict(turn[0] > rate * .9F && turn[0] < rate + .01F && turn[1] < rate * .5F && turn[2] < rate * .5F && left < 1,
                            "turned at most %.2f degrees a tick (its pivot's %.2f), %.2f on its first tick and %.2f on its last, %.1f degrees short of the view at the end",
                            turn[0], rate, turn[1], turn[2], left);
                }));
        plan.add(new Step("standing leap", 40, () -> place(0, -30, 0),
                t -> keys(0, false, t == 5, 0, 0),
                () -> {
                    double top = peak(5, 39) - FLOOR, across = flat(5, 39);
                    return verdict(top > 1.5 && top < 3.5 && across < 2 && mount.onGround(),
                            "%.2f blocks up, %.2f across, landed %s", top, across, mount.onGround());
                }));
        plan.add(new Step("gallop leap", 100, () -> { place(0, -38, 0); mountHealth = mount.getHealth(); },
                t -> keys(1, true, t == 55, 0, 0),
                () -> {
                    int off = firstAir(55), on = off < 0 ? -1 : firstGround(off + 1);
                    if (off < 0 || on < 0) return verdict(false, "no leap (left the ground at %d, landed at %d)", off, on);
                    double length = track.get(on).subtract(track.get(off)).horizontalDistance(), top = peak(off, on) - FLOOR;
                    boolean hurt = mount.getHealth() < mountHealth - 1e-3;
                    return verdict(length > 9 && top > 2.2 && !hurt, "%.1f blocks long, %.2f up, %d ticks in the air, hurt %s",
                            length, top, on - off, hurt);
                }));
        plan.add(new Step("pounce ahead", 40, () -> { place(0, -10, 0); dummy(new Vec3(.5, FLOOR, -10 + 5.5)); },
                t -> { look(dummies.get(0)); if (t == 5) cast(0); },
                () -> {
                    float damage = bite(0);
                    biteDamage = damage;
                    gaugeAfterBite = gauge(dummies.get(0));
                    double dash = track.get(castTick + 8).subtract(track.get(castTick)).horizontalDistance();
                    return verdict(damage > 0 && dash > 2.5 && gaugeAfterBite > .35F && gaugeAfterBite < .6F,
                            "bit for %.1f, dashed %.1f blocks, Freeze gauge %.2f", damage, dash, gaugeAfterBite);
                }));
        plan.add(new Step("pounce steered", 40, () -> { place(0, -10, 0); dummy(new Vec3(.5 - 3.9, FLOOR, -10 + 3.9)); },
                t -> { look(dummies.get(0)); if (t == 1) cast(0); },
                () -> {
                    Vec3 moved = track.get(castTick + 6).subtract(track.get(castTick));
                    Vec3 want = dummies.get(0).position().subtract(track.get(castTick));
                    double off = Math.toDegrees(Math.acos(Math.clamp(moved.multiply(1, 0, 1).normalize().dot(want.multiply(1, 0, 1).normalize()), -1, 1)));
                    return verdict(bite(0) > 0 && off < 20, "the body faced ahead, the dash went %.0f degrees off the crosshair's dummy, bit %s",
                            off, bite(0) > 0);
                }));
        plan.add(new Step("air pounce", 50, () -> { place(0, -10, 0); dummy(new Vec3(.5, FLOOR, -10 + 6.5)); },
                t -> {
                    keys(0, false, t == 5, 0, 0);
                    look(dummies.get(0));
                    if (t == 11) { castInAir = !mount.onGround(); cast(0); }
                },
                () -> {
                    double drop = castTick < 0 ? 0 : track.get(castTick).y - track.get(Math.min(track.size() - 1, castTick + 6)).y;
                    return verdict(castInAir && bite(0) > 0 && drop > .3, "cast in the air %s, dived %.2f blocks onto the dummy, bit %s",
                            castInAir, drop, bite(0) > 0);
                }));
        plan.add(new Step("breath steered", 100, () -> {
                    place(0, -10, 0);
                    for (double side : new double[]{25, -25}) {
                        double r = Math.toRadians(side);
                        dummy(new Vec3(.5 - Math.sin(r) * 6.5, FLOOR, -10 + Math.cos(r) * 6.5));
                    }
                },
                t -> {
                    if (t < 30) look(dummies.get(0));
                    else sweepTo(dummies.get(1), 3);
                    if (t == 5) mount.startRiderAttack(rider, 1);
                    if (t == 80) mount.stopRiderAttack(rider);
                },
                () -> verdict(bite(0) > 0 && bite(1) > 0, "the left dummy took %.1f, the right one %.1f as the view swept across", bite(0), bite(1))));
        // Pressed at the top of a standing leap, the breath starts in the air and pours down onto the dummy ahead.
        plan.add(new Step("breath from a leap", 60, () -> { place(0, -10, 0); dummy(new Vec3(.5, FLOOR, -10 + 7)); },
                t -> {
                    keys(0, false, t == 5, 0, 0);
                    look(dummies.get(0));
                    if (t == 10) { castInAir = !mount.onGround(); if (mount.startRiderAttack(rider, 1)) castTick = t; }
                    if (t == 45) mount.stopRiderAttack(rider);
                },
                () -> verdict(castInAir && castTick > 0 && bite(0) > 0, "pressed in the air %s, the breath started %s, the dummy took %.1f",
                        castInAir, castTick > 0, bite(0))));
        plan.add(new Step("breath freezes, pounce shatters", 130, () -> { place(0, -10, 0); dummy(new Vec3(.5, FLOOR, -10 + 6)); },
                t -> {
                    var prey = dummies.get(0);
                    look(prey);
                    if (t == 5) mount.startRiderAttack(rider, 1);
                    if (frozenAt < 0 && prey.hasEffect(DCEffects.FROZEN)) { frozenAt = t; mount.stopRiderAttack(rider); }
                    if (frozenAt >= 0 && castTick < 0 && t > frozenAt && !mount.isAttacking()) cast(0);
                    if (frozenAt >= 0 && shatterAt < 0 && !prey.hasEffect(DCEffects.FROZEN) && prey.hasEffect(DCEffects.FROST_RESISTANCE)) {
                        shatterAt = t;
                        shatterDamage = dealt.get(dealt.size() - 1);
                    }
                },
                // Double a plain bite, or 2/1.5 of one that was a critical hit.
                () -> verdict(frozenAt > 0 && frozenAt < 70 && shatterAt > 0 && shatterDamage > biteDamage * 1.3F,
                        "Frozen after %d ticks of breath, shattered %d ticks later for %.1f (a plain bite %.1f)",
                        frozenAt - 5, shatterAt - frozenAt, shatterDamage, biteDamage)));
        plan.add(new Step("frost douses fire and freezes water", 90, () -> {
                    place(0, -10, 0);
                    var level = mount.level();
                    for (int x = -1; x <= 1; x++) level.setBlock(new BlockPos(x, FLOOR, -7), Blocks.FIRE.defaultBlockState(), 3);
                    for (int x = -2; x <= 2; x++) for (int z = -5; z <= 0; z++) level.setBlock(new BlockPos(x, FLOOR - 1, z), Blocks.WATER.defaultBlockState(), 3);
                },
                t -> {
                    // swept first across the fires three blocks ahead, then across the water beyond them
                    if (t < 40) lookAt(new Vec3(-.8 + Math.max(0, t - 5) * 2.6 / 30, FLOOR, -6.5));
                    else lookAt(new Vec3(-1.5 + (t - 40) * 4.0 / 35, FLOOR - .1, -2.5));
                    if (t == 5) mount.startRiderAttack(rider, 1);
                    if (t == 75) mount.stopRiderAttack(rider);
                },
                () -> {
                    var level = mount.level();
                    int fires = 0, ice = 0;
                    for (int x = -1; x <= 1; x++) if (level.getBlockState(new BlockPos(x, FLOOR, -7)).is(Blocks.FIRE)) fires++;
                    for (int x = -2; x <= 2; x++) for (int z = -5; z <= 0; z++) if (level.getBlockState(new BlockPos(x, FLOOR - 1, z)).is(Blocks.FROSTED_ICE)) ice++;
                    for (int x = -2; x <= 2; x++) for (int z = -7; z <= 0; z++) {
                        level.setBlock(new BlockPos(x, FLOOR - 1, z), Blocks.STONE.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, FLOOR, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                    return verdict(fires == 0 && ice >= 3, "%d of 3 fires left burning, %d water blocks frozen", fires, ice);
                }));
        // Wild, its head looks round first and its body follows no faster than its pivot plants its paws, gathering into the
        // turn and braking out of it; its facing is that body (what every client is sent). The head is turned here as its
        // look control turns it (10 degrees a tick), the AI's goals held off.
        plan.add(new Step("wild looks round", 90, () -> {
                    place(0, FAR - 4, 180);
                    hunter(new Vec3(WILD + .5, FLOOR, -10));
                    hunter.setNoAi(true);
                },
                t -> hunter.yHeadRot = net.minecraft.util.Mth.approachDegrees(hunter.yHeadRot, 150, 10),
                () -> {
                    float rate = hunter.steadyTurnRate();
                    float[] turn = turning(hunterYaws, 0);
                    float left = Math.abs(net.minecraft.util.Mth.wrapDegrees(150 - hunter.yBodyRot));
                    return verdict(turn[0] > rate * .9F && turn[0] < rate + .01F && turn[1] < rate * .5F && turn[2] < rate * .5F && left < 1
                                    && hunter.getYRot() == hunter.yBodyRot,
                            "its body followed its head at most %.2f degrees a tick (its pivot's %.2f), %.2f on its first tick and %.2f on its last, %.1f degrees short of it at the end, facing its body %s",
                            turn[0], rate, turn[1], turn[2], left, hunter.getYRot() == hunter.yBodyRot);
                }));
        // Sent to a spot eight blocks behind it, it steps round on the spot first (vanilla turned it half round in two ticks)
        // and sets off as it comes round, never walking sideways; hurried (a chase's pace) it comes round more briskly.
        for (double pace : new double[]{1, 1.6}) {
            plan.add(new Step("wild turns onto a path" + (pace > 1 ? ", hurried" : ""), 120, () -> {
                        place(0, FAR - 4, 180);
                        hunter(new Vec3(WILD + .5, FLOOR, -10));
                    },
                    t -> {
                        if (t < SETTLE || pathDoneAt >= 0) return;
                        // A stroll of its own would take the path over (vanilla starts one whatever it is doing): the spot is
                        // sent again. There it stands, no stroll afterwards.
                        var nav = hunter.getNavigation();
                        if (t == SETTLE || !BlockPos.containing(WILD + .5, FLOOR, -18).equals(nav.getTargetPos())) nav.moveTo(WILD + .5, FLOOR, -18, pace);
                        else if (nav.isDone()) { pathDoneAt = t; hunter.setNoAi(true); }
                    },
                    () -> {
                        float rate = hunter.steadyTurnRate() * (float) Math.min(pace, 1.5);
                        float[] turn = turning(hunterYaws, SETTLE);
                        Vec3 goal = new Vec3(WILD + .5, FLOOR, -18);
                        double roundFirst = 0, aside = 0;
                        for (int i = SETTLE + 1; i < hunterTrack.size() && (pathDoneAt < 0 || i <= pathDoneAt); i++) {
                            Vec3 moved = hunterTrack.get(i).subtract(hunterTrack.get(i - 1)).multiply(1, 0, 1);
                            double yaw = Math.toRadians(hunterYaws.get(i));
                            Vec3 to = goal.subtract(hunterTrack.get(i - 1)).multiply(1, 0, 1);
                            // far off its way (over 100 degrees), it hardly moves; on its way, it moves along its body
                            if (to.horizontalDistance() > 1 && offHeading(to.x, to.z, yaw) > 100) roundFirst = Math.max(roundFirst, moved.horizontalDistance());
                            if (moved.horizontalDistance() > .05) aside = Math.max(aside, offHeading(moved.x, moved.z, yaw));
                        }
                        // vanilla's path for a body this wide ends a block or two short of the spot
                        double left = hunter.position().subtract(goal).horizontalDistance();
                        boolean arrived = pathDoneAt > 0 && !hunter.getNavigation().isStuck() && left < 3;
                        return verdict(turn[0] < rate + .01F && turn[1] < rate * .5F && roundFirst < .05 && aside < 20 && arrived,
                                "turned at most %.2f degrees a tick (%.2f allowed), %.2f on its first, moved at most %.3f blocks a tick while far off its way, walked at most %.1f degrees off its body, its path done after %d ticks %.1f blocks from the spot",
                                turn[0], rate, turn[1], roundFirst, aside, pathDoneAt - SETTLE, left);
                    }));
        }
        // The wild hunter: the mount and its rider wait at the far end.
        plan.add(new Step("wild pounce", 60, () -> {
                    place(0, FAR - 4, 180);
                    hunter(new Vec3(WILD + .5, FLOOR, -10));
                    dummy(new Vec3(WILD + .5, FLOOR, -10 + 3));
                },
                t -> { keep(); gaugePeak = Math.max(gaugePeak, gauge(dummies.get(0))); },
                () -> verdict(bite(0) > 0 && gaugePeak > .35F, "bit for %.1f, Freeze gauge %.2f after the bite", bite(0), gaugePeak)));
        // Prey off to its right: the breath brings the body round steadily after the aim (hurried, at most its pivot's rate
        // and a half), the neck turning the rest of the way, the aim never past the breath's twist; the frost still lands.
        plan.add(new Step("wild breath aside", 100, () -> {
                    place(0, FAR - 4, 180);
                    hunter(new Vec3(WILD + .5, FLOOR, -14));
                    dummy(new Vec3(WILD + .5 - 7, FLOOR, -14));
                },
                t -> {
                    keep();
                    if (castTick < 0 && hunter.isAttacking()) castTick = t;
                    if (hunter.isAttacking() && com.digicube.digimon.BreathAttacks.handles(hunter.getActiveAttack())) {
                        breathLimit = com.digicube.digimon.BreathAttacks.get(hunter.getActiveAttack()).twist();
                        breathTwist = Math.max(breathTwist, Math.abs(net.minecraft.util.Mth.wrapDegrees(hunter.getAttackYaw(1) - hunter.yBodyRot)));
                        int n = hunterYaws.size();
                        if (n > 1) breathTurn = Math.max(breathTurn, Math.abs(net.minecraft.util.Mth.wrapDegrees(hunterYaws.get(n - 1) - hunterYaws.get(n - 2))));
                    }
                },
                () -> {
                    float rate = hunter.steadyTurnRate() * com.digicube.entity.ai.SteadyBodyControl.BRISK;
                    float twist = breathLimit;
                    return verdict(castTick > 0 && breathTurn > 0 && breathTurn < rate + .01F && breathTwist < twist + .01F && bite(0) > 0,
                            "breathed from tick %d, its body turning at most %.2f degrees a tick (%.2f allowed), its aim at most %.1f degrees off its body (twist %.0f), the prey took %.1f",
                            castTick, breathTurn, rate, breathTwist, twist, bite(0));
                }));
        plan.add(new Step("wild freeze and shatter", 240, () -> {
                    hunter(new Vec3(WILD + .5, FLOOR, -14));
                    dummy(new Vec3(WILD + .5, FLOOR, -14 + 8));
                },
                t -> {
                    keep();
                    var prey = dummies.get(0);
                    if (frozenAt < 0 && prey.hasEffect(DCEffects.FROZEN)) frozenAt = t;
                    if (frozenAt >= 0 && shatterAt < 0 && !prey.hasEffect(DCEffects.FROZEN) && prey.hasEffect(DCEffects.FROST_RESISTANCE)) shatterAt = t;
                },
                () -> verdict(frozenAt > 0 && shatterAt > 0 && shatterAt - frozenAt <= 50,
                        "breathed on until Frozen at %d, shattered %d ticks later", frozenAt, shatterAt < 0 ? -1 : shatterAt - frozenAt)));
        // A ledge five blocks up, too steep for a pounce from the ground (over 45 degrees), within one from a leap.
        plan.add(new Step("wild leap onto a ledge", 80, () -> {
                    var level = mount.level();
                    for (int x = WILD - 3; x <= WILD + 3; x++) for (int z = LEDGE; z <= LEDGE + 5; z++)
                        for (int y = FLOOR; y <= FLOOR + 4; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
                    hunter(new Vec3(WILD + .5, FLOOR, LEDGE - 2.4));
                    dummy(new Vec3(WILD + .5, FLOOR + 5, LEDGE + 1.5));
                    dummies.get(0).addEffect(new MobEffectInstance(DCEffects.FROST_RESISTANCE, 100_000));
                },
                t -> {
                    keep();
                    if (airStart < 0 && hunter.isAttacking()) { airStart = t; castInAir = !hunter.onGround(); }
                },
                () -> {
                    int leaps = hunter.skillUses().getOrDefault("leap_pounce", 0);
                    var level = mount.level();
                    for (int x = WILD - 3; x <= WILD + 3; x++) for (int z = LEDGE; z <= LEDGE + 5; z++)
                        for (int y = FLOOR; y <= FLOOR + 4; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    return verdict(leaps > 0 && castInAir && bite(0) > 0, "leapt %d times, the pounce left the ground %s, bit %s",
                            leaps, castInAir, bite(0) > 0);
                }));
        // DIGICUBE_GARURUMON_ONLY=<start of a check's name>: that check alone.
        String only = System.getenv("DIGICUBE_GARURUMON_ONLY");
        if (only != null && !only.isBlank()) plan.removeIf(step -> !step.name().startsWith(only));
        return plan;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static void begin(ServerLevel level) {
        stepTick = 0;
        track.clear(); grounded.clear(); yaws.clear(); dealt.clear(); hunterTrack.clear(); hunterYaws.clear();
        castTick = frozenAt = shatterAt = airStart = pathDoneAt = -1;
        castInAir = false;
        shatterDamage = gaugePeak = breathTwist = breathTurn = breathLimit = 0;
        for (var dummy : dummies) dummy.discard();
        dummies.clear();
        if (hunter != null) { hunter.discard(); hunter = null; }
        mount.readyAttacks();
        steps.get(stepIndex).start().run();
        record();
    }

    /** One tick of what the checks read: where the mount is, whether it stands, the damage the dummies took this tick. */
    private static void record() {
        track.add(mount.position());
        grounded.add(mount.onGround());
        yaws.add(mount.getYRot());
        if (hunter != null) { hunterTrack.add(hunter.position()); hunterYaws.add(hunter.yBodyRot); }
        float total = 0;
        for (var dummy : dummies) {
            float lost = dummy.getMaxHealth() - dummy.getHealth();
            total += lost;
            dummy.setHealth(dummy.getMaxHealth());
            DUMMY_DAMAGE.merge(dummy.getId(), lost, Float::sum);
        }
        dealt.add(total);
    }
    private static final java.util.Map<Integer, Float> DUMMY_DAMAGE = new java.util.HashMap<>();

    /** Damage dummy {@code i} has taken in this check. */
    private static float bite(int i) { return i < dummies.size() ? DUMMY_DAMAGE.getOrDefault(dummies.get(i).getId(), 0F) : 0; }

    private static float gauge(DigimonEntity dummy) {
        return CombatMarkState.freezeGauge(((CombatMarkState) dummy).digicube$marks2());
    }

    /** The move the mount made over tick {@code i} of the check (from where it stood before it). */
    private static Vec3 moved(int i) { return track.get(i).subtract(track.get(i - 1)).multiply(1, 0, 1); }

    /**
     * A turn read from a facing each tick from {@code from} on: the most it turned in a tick, how far it turned on the
     * first tick it turned at all, and on the last.
     */
    private static float[] turning(List<Float> facing, int from) {
        float fastest = 0, first = -1, last = 0;
        for (int i = from + 1; i < facing.size(); i++) {
            float turn = Math.abs(net.minecraft.util.Mth.wrapDegrees(facing.get(i) - facing.get(i - 1)));
            fastest = Math.max(fastest, turn);
            if (turn > 1.0E-3F) { if (first < 0) first = turn; last = turn; }
        }
        return new float[]{fastest, Math.max(0, first), last};
    }

    /** Degrees a move {@code x, z} runs off a heading of {@code yaw} radians. */
    private static double offHeading(double x, double z, double yaw) {
        double length = Math.hypot(x, z);
        return length < 1.0E-6 ? 0 : Math.toDegrees(Math.acos(Math.clamp((-Math.sin(yaw) * x + Math.cos(yaw) * z) / length, -1, 1)));
    }

    /** Ticks after it sets off ({@link #REST}) that its move first reaches nine tenths of {@code pace}. */
    private static int gather(double pace) {
        for (int i = REST + 1; i < track.size(); i++) if (moved(i).horizontalDistance() >= pace * .9) return i - REST;
        return track.size();
    }

    /** Ice under the whole arena, or the stone back. */
    private static void iceField(boolean on) {
        var block = (on ? Blocks.ICE : Blocks.STONE).defaultBlockState();
        for (int x = -HALF; x <= HALF; x++) for (int z = BACK; z <= FAR; z++) mount.level().setBlock(new BlockPos(x, FLOOR - 1, z), block, 2);
    }

    /** An ice lane under the rider's checks (x -3 to 3, z BACK to FAR), or the stone back. */
    private static void ice(boolean on) {
        var block = (on ? Blocks.ICE : Blocks.STONE).defaultBlockState();
        for (int x = -3; x <= 3; x++) for (int z = BACK; z <= FAR; z++) mount.level().setBlock(new BlockPos(x, FLOOR - 1, z), block, 2);
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
        if (hunter != null && stepTick == SETTLE && !dummies.isEmpty()) hunter.setTarget(dummies.get(0));
    }
    private static final int SETTLE = 5;

    private static void cast(int slot) {
        if (castTick >= 0) return;
        if (!mount.scenarioRiderCast(rider, slot)) Constants.LOG.info("[garurumon] the press was refused at {}", stepTick);
        else castTick = stepTick;
    }

    /** The rider's crosshair on a dummy's chest. */
    private static void look(DigimonEntity dummy) {
        lookAt(dummy.getBoundingBox().getCenter().add(0, dummy.getBbHeight() * .2, 0));
    }

    private static void lookAt(Vec3 point) {
        Vec3 to = point.subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
        rider.zza = rider.xxa = 0;
    }

    /** The view turns toward a dummy by at most {@code rate} degrees a tick. */
    private static void sweepTo(DigimonEntity dummy, float rate) {
        Vec3 to = dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        rider.setYRot(net.minecraft.util.Mth.approachDegrees(rider.getYRot(), yaw, rate));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
    }

    /** A dummy that takes hits and never dies, standing still at {@code at}. */
    private static void dummy(Vec3 at) {
        var dummy = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, at);
        dummy.setNoAi(true);
        dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        dummies.add(dummy);
        DUMMY_DAMAGE.put(dummy.getId(), 0F);
    }

    private static void hunter(Vec3 at) {
        hunter = DigimonEntity.spawnWild((ServerLevel) mount.level(), DigimonSpeciesRegistry.getOrThrow(Constants.id("garurumon")), 20, at);
        hunter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        hunter.setHealth(hunter.getMaxHealth());
        hunter.snapTo(at.x, at.y, at.z, 0, 0);
        hunter.yBodyRot = hunter.yHeadRot = 0;
    }

    /** Blocks a tick over the ground between two recorded ticks. */
    private static double pace(int from, int to) { return flat(from, to) / (to - from); }

    private static double flat(int from, int to) { return track.get(to).subtract(track.get(from)).horizontalDistance(); }

    private static double peak(int from, int to) {
        double top = -1e9;
        for (int i = from; i <= to && i < track.size(); i++) top = Math.max(top, track.get(i).y);
        return top;
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
        Constants.LOG.info("[garurumon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
