package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.DigimonPart;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Headless checks of Seadramon: {@code DIGICUBE_SCENARIO=seadramon_checks}. Wild, in a deep pool: it swims about without
 * spinning on the spot, turning no faster than the circle its body draws at its pace; it swims round the corner of a
 * narrow channel with its whole body (its hit parts) inside the water; on land it slithers round to a spot behind it,
 * turning as slowly, its head never turned further off its body than its neck; it climbs a ledge four blocks high, over a
 * thin wall two blocks high, and out of the pool onto rock three blocks over the water. Ridden: swinging the view round, it carves the turn (gathering
 * into it, no tighter than its circle); on land it goes faster than a player walks and sprints faster still; looking back
 * standing, its head turns only as far as its neck, and going on it curls round; it climbs the ledge (head on, from a
 * standstill at its foot, and aslant), over the thin wall head on and aslant, and out of the pool, goes down into a pit
 * and up out of it, stops at a wall five blocks high and lowers itself down the ledge's face; out of a sea onto a beach behind a shelf,
 * a beach at the waterline and a terraced bank, head on and aslant, never stalled at the shore; it swims on while it breathes Ice Blast, its head
 * turned to prey off its line; breathed on the sea, the frost freezes floes on the surface and never where the body lies.
 * The verdict line starts with {@code [seadramon] RESULT}.
 */
public final class SeadramonScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    /** The pool: water from {@code FLOOR + 1} up to the surface, walled round; its far end is rock with a channel through it. */
    private static final int FLOOR = 284, SURFACE = 300, HALF_X = 14, BACK = -40, ROCK = 11, FAR = 40, TOP = 312;
    /** The channel: along z at x -1..1 from the pool to CORNER, then along x at z CORNER-2..CORNER to x 13. */
    private static final int CORNER = 31, CHANNEL_END = 13;
    /** The land: a stone floor at the surface's height east of the pool. */
    private static final int LAND_X0 = 18, LAND_X1 = 44;
    /**
     * On the land from z LEDGE_Z on: a ledge LEDGE blocks high up to x LEDGE_X1, and past a gap from x WALL_X0 a wall WALL
     * blocks high (more than half its body: Seadramon climbs 4.6). A thin wall THIN high, a block thick, at z THIN_Z from x
     * LAND_X0 + 1 to THIN_X1.
     */
    private static final int LEDGE_Z = 12, LEDGE = 4, LEDGE_X1 = 30, WALL_X0 = 33, WALL = 5, THIN_Z = -14, THIN = 2, THIN_X1 = 26;
    /** A pit a block deep on the land's right, a step down into and a step up out of. */
    private static final int PIT_X0 = 30, PIT_X1 = 36, PIT_Z0 = -36, PIT_Z1 = -33;
    /**
     * A patch of sand on the land (x SAND_X0..LAND_X1, z SAND_Z0..SAND_Z1) with a wall of logs LOGS high and a block
     * thick standing on it at x LOG_X, z LOG_Z0..LOG_Z1: the free-standing wall a player builds on a beach.
     */
    private static final int SAND_X0 = 32, SAND_Z0 = -9, SAND_Z1 = 7, LOG_X = 36, LOG_Z0 = -3, LOG_Z1 = 3, LOGS = 3;
    /**
     * West of the pool, a basin of sea (its floor at BASIN_FLOOR, x BASIN_X0..BANK_X1, z BASIN_Z0 on) whose north end is
     * a shore of three kinds, a lane each, the shore starting at z SHORE_Z: a beach behind a shelf a block under the
     * surface (from x BASIN_X0, the shelf SHELF blocks deep before the beach), a beach at the waterline (from x BEACH_X0)
     * and a bank a block over the water terraced on up (from x BANK_X0): the coasts a world's sea meets.
     */
    private static final int BASIN_X0 = -31, BEACH_X0 = -26, BANK_X0 = -21, BANK_X1 = -16, BASIN_Z0 = -25, BASIN_Z1 = 18, SHORE_Z = 6,
            SHELF = 3, BASIN_FLOOR = SURFACE - 7;

    /**
     * One check. A wild body sent somewhere is sent again when its path ends short of the spot, every half second, as
     * the AI's own goals send it (a path found only part of the way ends there).
     */
    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static ServerLevel world;
    private static DigimonEntity body, dummy;
    private static ServerPlayer rider;
    private static final List<Vec3> track = new ArrayList<>();
    private static final List<Float> yaws = new ArrayList<>();
    private static final List<Double> speeds = new ArrayList<>();
    /**
     * The deepest any hit part's core went into a block this check, how far a standing body moved, the widest the head
     * turned off the body breathing, and the furthest the body faced off the way its trail runs up to its head.
     */
    private static double sunk, standing;
    private static float twist, dummyHealth, neck;
    private static boolean done;
    private static int passed, total;
    private static final List<String> failures = new ArrayList<>();
    /** DIGICUBE_SEADRAMON_ONLY: only the checks whose names contain it. */
    private static final String ONLY = System.getenv("DIGICUBE_SEADRAMON_ONLY");
    /** DIGICUBE_SEADRAMON_TRACE=true: the body's state every five ticks. */
    private static final boolean TRACE = Boolean.parseBoolean(System.getenv("DIGICUBE_SEADRAMON_TRACE"));

    private SeadramonScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"seadramon_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                world = level;
                for (int cx = -2; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "SeaRider"));
                steps = plan();
                if (ONLY != null && !ONLY.isBlank()) steps = steps.stream().filter(step -> step.name().contains(ONLY)).toList();
                stepIndex = 0;
                begin(level);
                return;
            }
            record();
            Step step = steps.get(stepIndex);
            if (TRACE && body != null && stepTick % 5 == 0) {
                var nav = body.getNavigation();
                Constants.LOG.info("[seadramon-trace] {} t{} at {} moving {} yaw {} pitch {} water {} nav {} target {}", step.name(), stepTick,
                        fmt(body.position()), fmt(body.getDeltaMovement()), String.format("%.1f", body.getYRot()), String.format("%.1f", body.getXRot()),
                        body.isInWater(), nav.isDone() ? "done" : "going", nav.getTargetPos());
            }
            if (++stepTick >= step.ticks()) {
                String verdict = step.judge().get();
                total++;
                String line = step.name() + ": " + verdict.substring(verdict.indexOf(' ') + 1);
                if (verdict.startsWith("PASS")) passed++; else failures.add(line);
                Constants.LOG.info("[seadramon] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(level); return; }
                begin(level);
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (body != null && rider.getVehicle() == body) body.positionRider(rider);
        } catch (RuntimeException e) {
            Constants.LOG.error("[seadramon] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static String fmt(Vec3 v) { return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z); }

    // --- the pool, the channel and the land -------------------------------------------------------------------------

    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        var water = Blocks.WATER.defaultBlockState();
        for (int x = -HALF_X - 1; x <= HALF_X + 1; x++) for (int z = BACK - 1; z <= FAR + 1; z++) {
            boolean rim = Math.abs(x) > HALF_X || z < BACK || z > FAR;
            boolean channel = z >= ROCK && (Math.abs(x) <= 1 && z <= CORNER || z >= CORNER - 2 && z <= CORNER && x >= -1 && x <= CHANNEL_END);
            for (int y = FLOOR - 1; y <= TOP; y++) {
                var state = air;
                if (y == FLOOR - 1 || y == FLOOR) state = stone;
                else if (rim || z >= ROCK && !channel) state = y <= SURFACE + 2 ? stone : air;
                else if (y < SURFACE) state = water;
                level.setBlock(new BlockPos(x, y, z), state, 2);
            }
        }
        for (int x = LAND_X0; x <= LAND_X1; x++) for (int z = BACK; z <= FAR; z++) {
            boolean pit = x >= PIT_X0 && x <= PIT_X1 && z >= PIT_Z0 && z <= PIT_Z1;
            level.setBlock(new BlockPos(x, SURFACE - 2, z), stone, 2);
            level.setBlock(new BlockPos(x, SURFACE - 1, z), pit ? air : stone, 2);
            int high = z == THIN_Z && x > LAND_X0 && x <= THIN_X1 ? THIN : z < LEDGE_Z ? 0 : x <= LEDGE_X1 ? LEDGE : x >= WALL_X0 ? WALL : 0;
            for (int y = SURFACE; y <= TOP; y++) level.setBlock(new BlockPos(x, y, z), y < SURFACE + high ? stone : air, 2);
            if (x >= SAND_X0 && z >= SAND_Z0 && z <= SAND_Z1) {
                level.setBlock(new BlockPos(x, SURFACE - 1, z), Blocks.SAND.defaultBlockState(), 2);
                if (x == LOG_X && z >= LOG_Z0 && z <= LOG_Z1)
                    for (int y = SURFACE; y < SURFACE + LOGS; y++) level.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState(), 2);
            }
        }
        var sand = Blocks.SAND.defaultBlockState();
        var dirt = Blocks.DIRT.defaultBlockState();
        var grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = BASIN_X0 - 1; x <= BANK_X1; x++) for (int z = BASIN_Z0 - 1; z <= BASIN_Z1; z++) {
            boolean rim = x < BASIN_X0 || z < BASIN_Z0;
            // the top block of the column's ground (the floor under open water), and whether it is a beach's sand
            int ground;
            boolean beach;
            if (rim) { ground = SURFACE + 1; beach = false; }
            else if (x < BEACH_X0) {
                ground = z < SHORE_Z - SHELF ? BASIN_FLOOR : z < SHORE_Z ? SURFACE - 2 : z < SHORE_Z + 3 ? SURFACE - 1 : SURFACE;
                beach = z < SHORE_Z + 3;
            } else if (x < BANK_X0) {
                ground = z < SHORE_Z ? BASIN_FLOOR : z < SHORE_Z + 3 ? SURFACE - 1 : SURFACE;
                beach = z < SHORE_Z + 3;
            } else {
                ground = z < SHORE_Z ? BASIN_FLOOR : z < SHORE_Z + 2 ? SURFACE : z < SHORE_Z + 4 ? SURFACE + 1 : SURFACE + 2;
                beach = false;
            }
            for (int y = BASIN_FLOOR; y <= TOP; y++) {
                var state = air;
                if (rim) state = y <= ground ? stone : air;
                else if (y == BASIN_FLOOR) state = stone;
                else if (y <= ground) state = beach ? sand : y == ground ? grass : dirt;
                else if (y < SURFACE) state = water;
                level.setBlock(new BlockPos(x, y, z), state, 2);
            }
        }
    }

    // --- the checks -------------------------------------------------------------------------------------------------

    private static List<Step> plan() {
        var plan = new ArrayList<Step>();
        var serpent = DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon")).body().serpent();
        var mount = DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon")).body().mount().orElseThrow();
        // Left to itself in the water it swims about: it may circle a spot, but never spins round and round on it, and it
        // turns no faster than the circle its body draws at its pace.
        plan.add(new Step("wild swims", 600, () -> wild(new Vec3(.5, SURFACE - 6, -15), 0), t -> {}, () -> {
            float spin = spin(200, 5), loop = spin(100, 1000), over = overTurn(serpent, true);
            double travelled = 0;
            for (int i = 1; i < track.size(); i++) travelled += track.get(i).distanceTo(track.get(i - 1));
            // (a loop and a quarter in five seconds is a U-turn onto a spot behind, and another; a spin goes on)
            return verdict(spin < 450 && loop < 450 && travelled > 20 && over < .5F,
                    "turned at most %.0f degrees one way in any ten seconds spent within 5 blocks of one spot and %.0f in any five seconds, travelled %.1f blocks in 30 s, "
                            + "its turn at most %.2f degrees a tick past its circle", spin, loop, travelled, over);
        }));
        // Round the corner of a channel three blocks wide its whole body follows its head: no hit part cuts through the rock.
        plan.add(new Step("wild round a corner", 220, () -> wild(new Vec3(.5, FLOOR + 5, ROCK + 1), 0), t -> {
            if (t < 5) return;
            var nav = body.getNavigation();
            BlockPos goal = BlockPos.containing(CHANNEL_END - 1.5, FLOOR + 5, CORNER - 1 + .5);
            if (!goal.equals(nav.getTargetPos()) || nav.isDone() && t % 10 == 0) nav.moveTo(goal.getX() + .5, goal.getY(), goal.getZ() + .5, 1);
            sunk = Math.max(sunk, sunk());
        }, () -> {
            boolean round = body.getX() > 6;
            return verdict(round && sunk < .2, "swam round the corner to x %.1f, its hit parts at most %.2f blocks into the rock", body.getX(), sunk);
        }));
        // On land it slithers round to a spot behind it, no faster than its circle there.
        plan.add(new Step("wild on land", 300, () -> wild(new Vec3(LAND_X0 + 12.5, SURFACE, -20), 0), t -> {
            if (t < 5) return;
            var nav = body.getNavigation();
            BlockPos goal = BlockPos.containing(LAND_X0 + 9.5, SURFACE, -27.5);
            if (!goal.equals(nav.getTargetPos()) || nav.isDone() && t % 10 == 0) nav.moveTo(goal.getX() + .5, goal.getY(), goal.getZ() + .5, 1);
            neck = Math.max(neck, neck());
        }, () -> {
            float over = overTurn(serpent, false);
            double left = body.position().distanceTo(new Vec3(LAND_X0 + 10, SURFACE, -27));
            return verdict(over < .5F && left < 4 && neck <= serpent.neckTurn() + 1,
                    "came round to a spot behind it, %.1f blocks off it at the end, its turn at most %.2f degrees a tick past its circle, its head at most %.0f degrees off its body",
                    left, over, neck);
        }));
        // Ridden on land it goes faster than a player walks, and sprinting well past a running player.
        plan.add(new Step("ridden on land", 110, () -> ridden(new Vec3(LAND_X1 - 5.5, SURFACE, BACK + 2.5), 0),
                t -> keys(1, 0, false, t >= 50, 0, 0), () -> {
            double walk = level(track.get(45), track.get(35)) / 10, sprint = level(track.get(105), track.get(95)) / 10;
            return verdict(walk > .25 && sprint > .4, "went %.2f blocks a tick walking and %.2f sprinting", walk, sprint);
        }));
        // Standing, the rider looks back: its head turns only as far as its neck lets it. Going on, it curls round to the view.
        plan.add(new Step("ridden looks back on land", 150, () -> ridden(new Vec3(LAND_X0 + 5.5, SURFACE, -4.5), 0), t -> {
            keys(t < 40 ? 0 : 1, 0, false, false, 0, 180);
            neck = Math.max(neck, neck());
            if (t == 39) standing = level(track.get(0), body.position());
        }, () -> {
            float end = Mth.wrapDegrees(yaws.get(yaws.size() - 1) - 180);
            return verdict(standing < .3 && neck <= serpent.neckTurn() + 1 && Math.abs(end) < 12,
                    "standing it moved %.2f blocks, its head at most %.0f degrees off its body; going on it came round to %.0f degrees off the view",
                    standing, neck, end);
        }));
        // A ledge four blocks high: it climbs it, its body up the face after its head, none of its hit parts in the rock.
        plan.add(new Step("wild climbs a ledge", 200, () -> wild(new Vec3(LAND_X0 + 6.5, SURFACE, LEDGE_Z - 6.5), 0), t -> {
            if (t < 5) return;
            var nav = body.getNavigation();
            BlockPos goal = new BlockPos(LAND_X0 + 6, SURFACE + LEDGE, LEDGE_Z + 5);
            if (!goal.equals(nav.getTargetPos()) || nav.isDone() && t % 10 == 0) nav.moveTo(goal.getX() + .5, goal.getY(), goal.getZ() + .5, 1);
            sunk = Math.max(sunk, sunk());
        }, () -> verdict(body.getY() > SURFACE + LEDGE - .01 && body.getZ() > LEDGE_Z + 1 && sunk < .2,
                "climbed to %.1f blocks up, %.1f blocks onto the ledge, its hit parts at most %.2f blocks into the rock",
                body.getY() - SURFACE, body.getZ() - LEDGE_Z, sunk)));
        plan.add(new Step("ridden climbs a ledge", 90, () -> ridden(new Vec3(LAND_X0 + 8.5, SURFACE, LEDGE_Z - 5.5), 0),
                t -> { keys(1, 0, false, false, 0, 0); sunk = Math.max(sunk, sunk()); },
                () -> verdict(body.getY() > SURFACE + LEDGE - .01 && body.getZ() > LEDGE_Z + 1 && sunk < .2,
                        "climbed to %.1f blocks up, %.1f blocks onto the ledge, its hit parts at most %.2f blocks into the rock",
                        body.getY() - SURFACE, body.getZ() - LEDGE_Z, sunk)));
        // Standing at the foot of the ledge, its head half a block from the face, the push takes it up; and aslant, 30
        // degrees off the face's normal, it climbs as it slides along the face (a flatter angle slid it past the ledge's end).
        plan.add(new Step("ridden climbs from a standstill", 90, () -> ridden(new Vec3(LAND_X0 + 3.5, SURFACE, LEDGE_Z - .95), 0),
                t -> keys(1, 0, false, false, 0, 0), () -> verdict(body.getY() > SURFACE + LEDGE - .01 && body.getZ() > LEDGE_Z + 1,
                        "climbed to %.1f blocks up, %.1f blocks onto the ledge", body.getY() - SURFACE, body.getZ() - LEDGE_Z)));
        plan.add(new Step("ridden climbs aslant", 60, () -> ridden(new Vec3(LAND_X0 + 1.5, SURFACE, LEDGE_Z - 3.5), -30),
                t -> keys(1, 0, false, false, 0, -30), () -> verdict(body.getY() > SURFACE + LEDGE - .01 && body.getZ() > LEDGE_Z + 1,
                        "climbed to %.1f blocks up, %.1f blocks onto the ledge", body.getY() - SURFACE, body.getZ() - LEDGE_Z)));
        // A wall of logs three blocks high and a block thick on sand: it goes up it and over it however it comes to it: head
        // on, from a standstill with its head at the wall, turning to it from alongside, at a gallop, and its rider looking
        // down at it.
        logWall(plan, "ridden climbs a log wall", LOG_X + 6.5, 90, 90, false, 0);
        logWall(plan, "ridden climbs a log wall from a standstill", LOG_X + 1.5, 90, 90, false, 0);
        logWall(plan, "ridden climbs a log wall turning to it", LOG_X + 4.5, 0, 90, false, 0);
        logWall(plan, "ridden climbs a log wall at a gallop", LOG_X + 7.5, 90, 90, true, 0);
        logWall(plan, "ridden climbs a log wall looking down", LOG_X + 5.5, 90, 90, false, 50);
        // Looking round to the side as it climbs (and back) turns it off the face no way: it goes on up and over.
        plan.add(new Step("ridden climbs a log wall looking round", 90, () -> ridden(new Vec3(LOG_X + 3.5, SURFACE, .5), 90),
                t -> { keys(body.getX() < LOG_X - 2.5 ? 0 : 1, 0, false, false, 0, t >= 8 && t < 20 ? 160 : 90); sunk = Math.max(sunk, sunk()); },
                SeadramonScenario::overTheLogs));
        // Let go half way up, it lowers itself back down the face to its foot, no faster than it climbs, unhurt.
        plan.add(new Step("ridden lets go of a log wall half way up", 60, () -> ridden(new Vec3(LOG_X + 1.5, SURFACE, .5), 90),
                t -> keys(t < 9 ? 1 : 0, 0, false, false, 0, 90), () -> {
            double highest = track.stream().mapToDouble(Vec3::y).max().orElse(SURFACE) - SURFACE, fastest = 0;
            for (int i = 1; i < track.size(); i++) fastest = Math.max(fastest, track.get(i - 1).y - track.get(i).y);
            float hurt = body.getMaxHealth() - body.getHealth();
            return verdict(highest > 1 && highest < LOGS && body.getY() < SURFACE + .01 && body.getX() > LOG_X + 1 && fastest < DigimonEntity.CLIMB_PACE + .02
                            && hurt == 0, "went up %.1f blocks, came back down to %.2f at most %.2f blocks a tick, %.1f blocks from the wall, hurt %.1f",
                    highest, body.getY() - SURFACE, fastest, body.getX() - LOG_X - 1, hurt);
        }));
        // Pushed along the wall nearly alongside it (its nose 80 degrees off square to it), it slides on and does not climb.
        plan.add(new Step("ridden along a log wall", 40, () -> ridden(new Vec3(LOG_X + 1.47, SURFACE, LOG_Z0 + .5), 10),
                t -> keys(1, 0, false, false, 0, 10), () -> {
            double highest = track.stream().mapToDouble(Vec3::y).max().orElse(SURFACE) - SURFACE;
            return verdict(highest < .1 && body.getZ() > LOG_Z0 + 3, "went %.1f blocks along it, %.2f blocks up at most", body.getZ() - LOG_Z0 - .5, highest);
        }));
        // A pit a block deep: ridden through it, it steps down into it and up out of it as any body does, without a climb.
        plan.add(new Step("ridden through a pit", 70, () -> ridden(new Vec3(PIT_X0 + 3.5, SURFACE, PIT_Z0 - 3.5), 0),
                t -> keys(1, 0, false, false, 0, 0), () -> {
            double highest = track.stream().mapToDouble(Vec3::y).max().orElse(SURFACE) - SURFACE;
            float hurt = body.getMaxHealth() - body.getHealth();
            return verdict(body.getZ() > PIT_Z1 + 2.5 && body.getY() > SURFACE - .01 && highest < .5 && hurt == 0,
                    "went through it to %.1f blocks past it, back on the ground, never more than %.2f blocks over it, hurt %.1f",
                    body.getZ() - PIT_Z1 - 1, highest, hurt);
        }));
        // A wall five blocks high is more than it climbs: pressed into it, it stays at its foot and does not cling to it.
        plan.add(new Step("ridden at a high wall", 70, () -> ridden(new Vec3(WALL_X0 + 5.5, SURFACE, LEDGE_Z - 4.5), 0),
                t -> keys(1, 0, false, false, 0, 0), () -> {
            double highest = track.stream().mapToDouble(Vec3::y).max().orElse(SURFACE) - SURFACE;
            return verdict(highest < 1.1 && body.getZ() > LEDGE_Z - 1.5, "stayed at the wall's foot, %.2f blocks up at most", highest);
        }));
        // A thin wall two blocks high: it goes up and over it, ridden head on and aslant, and wild.
        plan.add(new Step("ridden over a thin wall", 80, () -> ridden(new Vec3(LAND_X0 + 4.5, SURFACE, THIN_Z - 5.5), 0),
                t -> keys(1, 0, false, false, 0, 0), () -> verdict(body.getZ() > THIN_Z + 2 && body.getY() < SURFACE + .5,
                        "went over it to %.1f blocks past it", body.getZ() - THIN_Z)));
        plan.add(new Step("ridden over a thin wall aslant", 90, () -> ridden(new Vec3(LAND_X0 + 2.5, SURFACE, THIN_Z - 4.5), -35),
                t -> keys(1, 0, false, false, 0, -35), () -> verdict(body.getZ() > THIN_Z + 2 && body.getY() < SURFACE + .5,
                        "went over it to %.1f blocks past it", body.getZ() - THIN_Z)));
        plan.add(new Step("wild over a thin wall", 200, () -> wild(new Vec3(LAND_X0 + 5.5, SURFACE, THIN_Z - 6.5), 0), t -> {
            if (t < 5) return;
            var nav = body.getNavigation();
            BlockPos goal = new BlockPos(LAND_X0 + 5, SURFACE, THIN_Z + 6);
            if (!goal.equals(nav.getTargetPos()) || nav.isDone() && t % 10 == 0) nav.moveTo(goal.getX() + .5, goal.getY(), goal.getZ() + .5, 1);
        }, () -> verdict(body.getZ() > THIN_Z + 2 && body.getY() < SURFACE + .5, "went over it to %.1f blocks past it", body.getZ() - THIN_Z)));
        // The pool's rock stands three blocks over the water: it climbs out onto it, ridden and wild.
        plan.add(new Step("ridden climbs out of the water", 90, () -> ridden(new Vec3(-7.5, SURFACE - mount.sea().floatLine() * body().dimensions().height(), ROCK - 5.5), 0),
                t -> keys(1, 0, false, false, 0, 0), () -> verdict(body.getY() > SURFACE + 3 - .01 && body.getZ() > ROCK + .5,
                        "came out onto the rock %.1f blocks over the water, %.1f blocks in from its edge", body.getY() - SURFACE, body.getZ() - ROCK)));
        plan.add(new Step("wild climbs out of the water", 240, () -> wild(new Vec3(-9.5, SURFACE - 2, ROCK - 4.5), 0), t -> {
            if (t < 5) return;
            var nav = body.getNavigation();
            BlockPos goal = new BlockPos(-10, SURFACE + 3, ROCK + 5);
            if (!goal.equals(nav.getTargetPos()) || nav.isDone() && t % 10 == 0) nav.moveTo(goal.getX() + .5, goal.getY(), goal.getZ() + .5, 1);
        }, () -> verdict(body.getY() > SURFACE + 3 - .01 && body.getZ() > ROCK + .5,
                "came out onto the rock %.1f blocks over the water, %.1f blocks in from its edge", body.getY() - SURFACE, body.getZ() - ROCK)));
        // Swimming along under the rock, it still turns to face it and climbs out.
        plan.add(new Step("wild climbs out along the rock", 240, () -> wild(new Vec3(-4.5, SURFACE - 2, ROCK - 1.5), 90), t -> {
            if (t < 5) return;
            var nav = body.getNavigation();
            BlockPos goal = new BlockPos(-8, SURFACE + 3, ROCK + 4);
            if (!goal.equals(nav.getTargetPos()) || nav.isDone() && t % 10 == 0) nav.moveTo(goal.getX() + .5, goal.getY(), goal.getZ() + .5, 1);
        }, () -> verdict(body.getY() > SURFACE + 3 - .01 && body.getZ() > ROCK + .5,
                "came out onto the rock %.1f blocks over the water, %.1f blocks in from its edge", body.getY() - SURFACE, body.getZ() - ROCK)));
        // Out of the sea onto each of its shores, head on and aslant: over a shelf whose lip lies a fifth of a block over
        // the feet of a body afloat (it walled the body in), onto a beach at the waterline, up a terraced bank. Pushing
        // all the way, it never stalls at the shore.
        shore(plan, "ridden climbs out over a shelf", BASIN_X0 + 2.5, 5.5, 0);
        shore(plan, "ridden climbs out over a shelf aslant", BASIN_X0 + .5, 3.5, -35);
        shore(plan, "ridden climbs out onto a beach", BEACH_X0 + 2.5, 5.5, 0);
        shore(plan, "ridden climbs out onto a beach aslant", BEACH_X0 + .5, 2.5, -40);
        shore(plan, "ridden climbs out up a bank", BANK_X0 + 2.5, 5.5, 0);
        shore(plan, "ridden climbs out up a bank aslant", BANK_X1 - .5, 2.5, 40);
        // Off the ledge it lowers itself down the face, no faster than it climbs, and takes no fall.
        plan.add(new Step("ridden lowers itself", 60, () -> ridden(new Vec3(LAND_X0 + 8.5, SURFACE + LEDGE, LEDGE_Z + 3.5), 180),
                t -> keys(1, 0, false, false, 0, 180), () -> {
            double fastest = 0, landed = Double.NaN;
            for (int i = 1; i < track.size(); i++) {
                fastest = Math.max(fastest, track.get(i - 1).y - track.get(i).y);
                if (Double.isNaN(landed) && track.get(i).y < SURFACE + .05) landed = track.get(i).z;
            }
            float hurt = body.getMaxHealth() - body.getHealth();
            // it comes down the face: its feet land near it (sailing out at its pace it landed 4 out)
            double out = LEDGE_Z - landed;
            return verdict(body.getY() < SURFACE + .01 && fastest < DigimonEntity.CLIMB_PACE + .02 && hurt == 0 && out < 1.2,
                    "came down to %.1f blocks up, at most %.2f blocks a tick, %.1f blocks out from the face, hurt %.1f",
                    body.getY() - SURFACE, fastest, out, hurt);
        }));
        // Ridden, the view swings a quarter round: it carves the turn, gathering into it, no tighter than its circle.
        plan.add(new Step("ridden carve", 70, () -> ridden(new Vec3(.5, FLOOR + 6, -32), 0), t -> keys(1, 0, false, false, 0, t < 20 ? 0 : -90), () -> {
            float fastest = 0, first = -1, over = 0;
            // the view swings at the end of tick 20's input: the record of tick 20 holds its first turn
            for (int i = 20; i < yaws.size(); i++) {
                float turned = Math.abs(Mth.wrapDegrees(yaws.get(i) - yaws.get(i - 1)));
                if (first < 0 && turned > .01F) first = turned;
                fastest = Math.max(fastest, turned);
                over = Math.max(over, turned - Math.min(mount.waterTurnRate(), serpent.turnRate(speeds.get(i - 1), true)));
            }
            float end = Mth.wrapDegrees(yaws.get(yaws.size() - 1));
            float gather = mount.waterTurnRate() / com.digicube.entity.ai.SteadyBodyControl.EASE_TICKS;
            return verdict(Math.abs(end + 90) < 6 && over < .3F && first <= gather * 1.6F,
                    "came round to %.0f degrees, at most %.2f a tick (%.2f past its circle), %.2f on its first tick", end, fastest, Math.max(0, over), first);
        }));
        // It swims on while it breathes Ice Blast, its head turned to prey off its line: the rider keeps the crosshair on the
        // prey and steers past it (W and D swim 45 degrees right of the view).
        plan.add(new Step("ice blast swimming", 70, () -> {
            ridden(new Vec3(.5, FLOOR + 6, -36), 0);
            dummy(new Vec3(-9.5, FLOOR + 6, -14));
        }, t -> {
            keepDummy();
            Vec3 to = dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
            keys(1, -1, false, false, (float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())), (float) Math.toDegrees(Math.atan2(-to.x, to.z)));
            if (t == 10 && !body.startRiderAttack(rider, 0)) Constants.LOG.info("[seadramon] the breath was refused");
            if (t == 50) body.stopRiderAttack(rider);
            if (body.isAttacking()) twist = Math.max(twist, Math.abs(Mth.wrapDegrees(body.getAttackYaw(1) - body.getYRot())));
            if (TRACE) Constants.LOG.info("[seadramon-trace] breath t{} speed {} attacking {} yaw {} aim {} keys {} {} controller {} fluid {}", t,
                    String.format("%.3f", body.getDeltaMovement().length()), body.isAttacking(), String.format("%.1f", body.getYRot()),
                    String.format("%.1f", body.getAttackYaw(1)), rider.zza, rider.xxa, body.getControllingPassenger() == rider,
                    String.format("%.2f", body.getFluidHeight(net.minecraft.tags.FluidTags.WATER)));
        }, () -> {
            double swum = track.get(50).distanceTo(track.get(10));
            float dealt = dummyHealth - dummy.getHealth();
            return verdict(swum > 6 && dealt > 0 && twist > 12, "swam %.1f blocks while it breathed, its head up to %.0f degrees off its body, the prey took %.1f",
                    swum, twist, dealt);
        }));
        // Breathed at the sea ahead, the frost freezes a floe on the surface where the stream plays, never where the body lies.
        plan.add(new Step("frost on the sea", 50, () -> {
            thaw();
            ridden(new Vec3(.5, SURFACE - mount.sea().floatLine() * body().dimensions().height(), -30), 0);
        }, t -> {
            // the crosshair on the water some eight blocks ahead
            Vec3 spot = body.position().add(0, 0, 8).with(net.minecraft.core.Direction.Axis.Y, SURFACE);
            Vec3 to = spot.subtract(rider.getEyePosition());
            keys(0, 0, false, false, (float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())), 0);
            if (t == 5 && !body.startRiderAttack(rider, 0)) Constants.LOG.info("[seadramon] the breath was refused");
            if (t == 45) body.stopRiderAttack(rider);
        }, () -> {
            int floes = 0;
            boolean under = false;
            for (BlockPos pos : BlockPos.betweenClosed(-HALF_X, SURFACE - 3, BACK, HALF_X, SURFACE, ROCK - 1)) {
                if (!world.getBlockState(pos).is(Blocks.FROSTED_ICE)) continue;
                floes++;
                var block = new AABB(pos);
                if (body.getBoundingBox().intersects(block)) under = true;
                for (DigimonPart part : body.parts()) if (part.getBoundingBox().intersects(block)) under = true;
            }
            thaw();
            return verdict(floes >= 3 && !under, "froze %d blocks of the surface%s", floes, under ? ", one where its body lies" : ", none where its body lies");
        }));
        return plan;
    }

    private static com.digicube.digimon.DigimonBody body() { return DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon")).body(); }

    /**
     * The most a body turned (summed, one way) within any {@code window} ticks over which it stayed within
     * {@code radius} blocks of the window's middle: a spin round one spot, where a swim round a pool turns as far but goes.
     */
    private static float spin(int window, double radius) {
        float worst = 0;
        for (int from = 0; from + window < yaws.size(); from += 5) {
            Vec3 middle = Vec3.ZERO;
            for (int i = from; i <= from + window; i++) middle = middle.add(track.get(i));
            middle = middle.scale(1.0 / (window + 1));
            boolean near = true;
            float turned = 0;
            for (int i = from + 1; i <= from + window && near; i++) {
                turned += Mth.wrapDegrees(yaws.get(i) - yaws.get(i - 1));
                near = track.get(i).distanceTo(middle) <= radius;
            }
            if (near) worst = Math.max(worst, Math.abs(turned));
        }
        return worst;
    }

    /** How far past the circle its body draws at its pace (DigimonBody.Serpent.turnRate) it ever turned in a tick, degrees. */
    private static float overTurn(com.digicube.digimon.DigimonBody.Serpent serpent, boolean swimming) {
        float over = 0;
        for (int i = 1; i < yaws.size(); i++) {
            float turned = Math.abs(Mth.wrapDegrees(yaws.get(i) - yaws.get(i - 1)));
            over = Math.max(over, turned - serpent.turnRate(speeds.get(i - 1), swimming) * 1.05F);
        }
        return over;
    }

    /** Degrees the body faces off the way its trail runs up to its head (the server's trail, over its neck). */
    private static float neck() {
        float heading = body.serpentTrail() == null ? Float.NaN : body.serpentTrail().heading(1.5);
        return Float.isNaN(heading) ? 0 : Math.abs(Mth.wrapDegrees(body.getYRot() - heading));
    }

    /** Level blocks between two places. */
    private static double level(Vec3 a, Vec3 b) { return Math.hypot(a.x - b.x, a.z - b.z); }

    /** How deep the core of any hit part is in a block (its box shrunk by a fifth of a block tried at depths). */
    private static double sunk() {
        double worst = 0;
        for (DigimonPart part : body.parts()) {
            AABB box = part.getBoundingBox();
            for (double depth = .05; depth <= .45; depth += .05) {
                AABB core = box.deflate(depth);
                if (core.getXsize() <= 0 || core.getZsize() <= 0 || core.getYsize() <= 0) break;
                boolean hits = BlockPos.betweenClosedStream(core).anyMatch(p -> {
                    var shape = world.getBlockState(p).getCollisionShape(world, p);
                    return !shape.isEmpty() && shape.bounds().move(p).intersects(core);
                });
                if (!hits) break;
                worst = Math.max(worst, depth);
            }
        }
        return worst;
    }

    // --- staging ----------------------------------------------------------------------------------------------------

    private static Vec3 pendingAt;
    private static float pendingYaw;
    private static boolean pendingRidden;

    private static void wild(Vec3 at, float yaw) { pendingAt = at; pendingYaw = yaw; pendingRidden = false; }

    private static void ridden(Vec3 at, float yaw) { pendingAt = at; pendingYaw = yaw; pendingRidden = true; }

    /**
     * Ridden from {@code x} on the sand east of the log wall, the body facing {@code bodyYaw} and the rider's view
     * {@code viewYaw} (and {@code pitch} down), pushing forward (sprinting too with {@code gallop}): it goes up the wall
     * and over it, coming down on its far side unhurt.
     */
    private static void logWall(List<Step> plan, String name, double x, float bodyYaw, float viewYaw, boolean gallop, float pitch) {
        plan.add(new Step(name, 100, () -> ridden(new Vec3(x, SURFACE, .5), bodyYaw),
                t -> { keys(body.getX() < LOG_X - 2.5 ? 0 : 1, 0, false, gallop, pitch, viewYaw); sunk = Math.max(sunk, sunk()); },
                SeadramonScenario::overTheLogs));
    }

    /**
     * Ridden afloat at its float line from {@code x}, {@code out} blocks out from the shore, heading {@code yaw} and the
     * rider looking there a little down, pushing forward until it is up: it comes out onto the shore's first level a
     * block over the water (the beach's grass, the bank's first terrace), unhurt, and never stands still pushing for more
     * than half a second on the way (the most ticks it moved less than .02 blocks).
     */
    private static void shore(List<Step> plan, String name, double x, double out, float yaw) {
        int top = SURFACE + 1;
        plan.add(new Step(name, 90, () -> ridden(new Vec3(x, SURFACE - mount().sea().floatLine() * body().dimensions().height(), SHORE_Z - out), yaw),
                t -> keys(body.getY() > top - .01 && body.onGround() ? 0 : 1, 0, false, false, 10, yaw), () -> {
            int stalled = 0, run = 0, reached = -1;
            for (int i = 1; i < track.size() && reached < 0; i++) {
                run = track.get(i).distanceTo(track.get(i - 1)) < .02 ? run + 1 : 0;
                stalled = Math.max(stalled, run);
                if (track.get(i).y > top - .01) reached = i;
            }
            float hurt = body.getMaxHealth() - body.getHealth();
            return verdict(reached >= 0 && stalled <= 10 && hurt == 0,
                    "%s, %.1f blocks in from the shore at x %.1f, stood still pushing %d ticks at most, hurt %.1f",
                    reached < 0 ? String.format("got no further than %.1f blocks over the water", body.getY() - SURFACE) : "up out of the water after " + reached + " ticks",
                    body.getZ() - SHORE_Z, body.getX(), stalled, hurt);
        }));
    }

    private static com.digicube.digimon.DigimonBody.Mount mount() { return body().mount().orElseThrow(); }

    /** Up the log wall and over it: down on the sand past it, unhurt, no hit part in the logs. */
    private static String overTheLogs() {
        double highest = track.stream().mapToDouble(Vec3::y).max().orElse(SURFACE) - SURFACE;
        float hurt = body.getMaxHealth() - body.getHealth();
        return verdict(highest > LOGS - .01 && body.getX() < LOG_X - 1 && body.getY() < SURFACE + .5 && hurt == 0 && sunk < .2,
                "went up %.1f blocks and over it to %.1f blocks past it, back on the sand, hurt %.1f, its hit parts at most %.2f blocks into the logs",
                highest, LOG_X - body.getX(), hurt, sunk);
    }

    private static void dummy(Vec3 at) {
        dummy = DigimonEntity.spawnWild(world, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, at);
        dummy.setNoAi(true);
        dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        dummyHealth = dummy.getHealth();
        dummySpot = at;
    }

    private static Vec3 dummySpot;

    /** The prey holds its place in the water (it would sink, or drift off). */
    private static void keepDummy() {
        dummy.setPos(dummySpot);
        dummy.setDeltaMovement(Vec3.ZERO);
    }

    private static void thaw() {
        for (BlockPos pos : BlockPos.betweenClosed(-HALF_X, SURFACE - 3, BACK, HALF_X, SURFACE, ROCK - 1))
            if (world.getBlockState(pos).is(Blocks.FROSTED_ICE)) world.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
    }

    private static void begin(ServerLevel level) {
        stepTick = 0;
        track.clear();
        yaws.clear();
        speeds.clear();
        sunk = 0;
        standing = 0;
        twist = 0;
        neck = 0;
        if (body != null) body.discard();
        if (dummy != null) { dummy.discard(); dummy = null; }
        body = null;
        steps.get(stepIndex).start().run();
        if (pendingAt != null) {
            body = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon")), 20, pendingAt);
            body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
            body.setHealth(body.getMaxHealth());
            body.snapTo(pendingAt.x, pendingAt.y, pendingAt.z, pendingYaw, 0);
            body.yBodyRot = body.yHeadRot = pendingYaw;
            if (pendingRidden) {
                body.seatScenarioRider(rider);
                seat(rider, body);
                body.driveScenarioRider(true, false);
                keys(0, 0, false, false, 0, pendingYaw);
            }
            pendingAt = null;
        }
    }

    private static void keys(float forward, float strafe, boolean jump, boolean sprint, float pitch, float yaw) {
        rider.zza = forward;
        rider.xxa = strafe;
        rider.setJumping(jump);
        rider.setSprinting(sprint);
        rider.setXRot(pitch);
        rider.setYRot(yaw);
    }

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

    private static void record() {
        if (body == null) return;
        track.add(body.position());
        yaws.add(body.getYRot());
        speeds.add(body.getDeltaMovement().length());
    }

    private static String verdict(boolean pass, String format, Object... args) {
        return (pass ? "PASS " : "FAIL ") + String.format(format, args);
    }

    private static void finish(ServerLevel level) {
        done = true;
        if (body != null) body.discard();
        if (dummy != null) dummy.discard();
        Constants.LOG.info("[seadramon] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : ": " + String.join("; ", failures));
        level.getServer().halt(false);
    }
}
