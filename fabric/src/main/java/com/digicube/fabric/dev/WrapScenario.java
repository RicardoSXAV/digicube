package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digimon.ConstrictionCoil;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import com.digicube.registry.DCEffects;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
 * Headless checks of a serpent's wrap (Seadramon's Constriction, {@link ConstrictionCoil}):
 * {@code DIGICUBE_SCENARIO=wrap_checks}. Wild, on a stone floor, it goes after prey of every size its body goes round
 * (a chicken, a rabbit hopping off, a cow, a zombie, a spider) and takes each: the prey is held, squeezed four times and let
 * go; a Golemon, too big to go round, is never wrapped (it gets Ice Blast); a cow with its back to a wall is drawn out
 * into the open and wrapped there; a squid is wrapped in the water. Ridden, one press takes a rabbit six blocks off (the
 * tile lit and the prey outlined first); with nothing to take, or only a Golemon, a press casts nothing. The verdict line
 * starts with {@code [wrap] RESULT}.
 */
public final class WrapScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    /** DIGICUBE_WRAP_ONLY=<words>: only the checks whose name holds them. */
    private static final String ONLY = System.getenv("DIGICUBE_WRAP_ONLY");
    /** The floor's top, the arena's half width, and the pool (from POOL_X0, POOL_Z0 on, POOL deep). */
    private static final int FLOOR = 300, HALF = 34, POOL_X0 = 12, POOL_X1 = 30, POOL_Z0 = -30, POOL_Z1 = -12, POOL = 5;
    /** A wall three blocks high along x at WALL_Z, from x WALL_X0 to WALL_X1. */
    private static final int WALL_Z = 20, WALL_X0 = -20, WALL_X1 = -8;

    private record Step(String name, int ticks, Runnable start, IntConsumer input, Supplier<String> judge) {}

    private static List<Step> steps;
    private static int stepIndex, stepTick;
    private static ServerLevel world;
    private static DigimonEntity body;
    private static LivingEntity prey;
    private static ServerPlayer rider;
    private static boolean done, held, lit;
    private static int passed, total, captureAt, releaseAt, squeezes, heldTicks, iceTicks;
    private static float lastHealth;
    private static Vec3 preyFrom, preyHeld;
    private static final List<String> failures = new ArrayList<>();

    private WrapScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"wrap_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (steps == null) {
                world = level;
                for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) level.setChunkForced(cx, cz, true);
                com.digicube.spawn.WildSpawner.clear(level);
                build(level);
                rider = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "WrapRider"));
                steps = plan();
                if (ONLY != null) steps.removeIf(step -> !step.name().contains(ONLY));
                stepIndex = 0;
                begin();
                return;
            }
            watch();
            Step step = steps.get(stepIndex);
            if (++stepTick >= step.ticks()) {
                String verdict = step.judge().get();
                total++;
                String line = step.name() + ": " + verdict.substring(verdict.indexOf(' ') + 1);
                if (verdict.startsWith("PASS")) passed++; else failures.add(line);
                Constants.LOG.info("[wrap] {} {}", verdict.startsWith("PASS") ? "PASS" : "FAIL", line);
                if (++stepIndex >= steps.size()) { finish(); return; }
                begin();
            }
            steps.get(stepIndex).input().accept(stepTick);
            if (body != null && rider.getVehicle() == body) body.positionRider(rider);
        } catch (RuntimeException e) {
            Constants.LOG.error("[wrap] aborted", e);
            failures.add("aborted: " + e);
            finish();
        }
    }

    // --- the arena ----------------------------------------------------------------------------------------------------

    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        var water = Blocks.WATER.defaultBlockState();
        for (int x = -HALF - 1; x <= HALF + 1; x++) for (int z = -HALF - 1; z <= HALF + 1; z++) {
            boolean rim = Math.abs(x) > HALF || Math.abs(z) > HALF;
            boolean pool = x >= POOL_X0 && x <= POOL_X1 && z >= POOL_Z0 && z <= POOL_Z1;
            boolean wall = z == WALL_Z && x >= WALL_X0 && x <= WALL_X1;
            for (int y = FLOOR - POOL - 2; y <= FLOOR + 8; y++) {
                var state = air;
                if (y < FLOOR - POOL) state = stone;
                else if (y < FLOOR) state = pool ? water : stone;
                else if (rim && y <= FLOOR + 3 || wall && y < FLOOR + 3) state = stone;
                level.setBlock(new BlockPos(x, y, z), state, 2);
            }
        }
    }

    // --- the checks ---------------------------------------------------------------------------------------------------

    private static List<Step> plan() {
        var plan = new ArrayList<Step>();
        // Wild, it takes prey of every size its body goes round, wherever it runs or hops to.
        for (var kind : List.<Object[]>of(new Object[]{"a chicken", EntityTypes.CHICKEN}, new Object[]{"a hopping rabbit", EntityTypes.RABBIT},
                new Object[]{"a cow", EntityTypes.COW}, new Object[]{"a zombie", EntityTypes.ZOMBIE}, new Object[]{"a spider", EntityTypes.SPIDER})) {
            String what = (String) kind[0];
            EntityType<?> type = (EntityType<?>) kind[1];
            plan.add(new Step("wild wraps " + what, 700, () -> { wild(new Vec3(-10.5, FLOOR, -10.5), 0); prey = mob(type, new Vec3(-10.5, FLOOR, -3.5), true); aim(); },
                    t -> {}, WrapScenario::heldAndLetGo));
        }
        // A Golemon is too big for its body to go round: it is never wrapped, and Ice Blast plays on it instead.
        plan.add(new Step("wild leaves a Golemon to Ice Blast", 400, () -> {
            wild(new Vec3(10.5, FLOOR, 10.5), 180);
            var golemon = DigimonEntity.spawnWild(world, DigimonSpeciesRegistry.getOrThrow(Constants.id("golemon")), 20, new Vec3(10.5, FLOOR, 3.5));
            golemon.setNoAi(true);
            toughen(golemon);
            prey = golemon;
            aim();
        }, t -> {}, () -> verdict(captureAt < 0 && iceTicks > 20, "never held (first hold at tick %d), Ice Blast playing on it for %d ticks", captureAt, iceTicks)));
        // A cow with its back to a wall is drawn out into the open, and the loops go round it clear of the wall.
        plan.add(new Step("wild draws a cow off a wall", 700, () -> {
            wild(new Vec3(-14.5, FLOOR, 12.5), 0);
            prey = mob(EntityTypes.COW, new Vec3(-14.5, FLOOR, WALL_Z - .46), false);
            aim();
        }, t -> {}, () -> {
            String base = heldAndLetGo();
            if (!base.startsWith("PASS") || preyHeld == null) return base;
            double drawn = WALL_Z - .46 - preyHeld.z;
            return verdict(drawn > .1 && drawn <= ConstrictionCoil.DRAW_OUT + .05, "%s; drawn %.2f blocks out from the wall", base.substring(5), drawn);
        }));
        // In the water it takes a swimmer (one that keeps still, so the check is the wrap and not the chase).
        plan.add(new Step("wild wraps a squid in the water", 500, () -> {
            wild(new Vec3(POOL_X0 + 8.5, FLOOR - 3, POOL_Z0 + 3.5), 0);
            prey = mob(EntityTypes.SQUID, new Vec3(POOL_X0 + 8.5, FLOOR - 3, POOL_Z0 + 9.5), false);
            aim();
        }, t -> {}, WrapScenario::heldAndLetGo));
        // Ridden: one press takes a rabbit six blocks off, the tile lit and the rabbit outlined before it.
        plan.add(new Step("ridden takes a rabbit six blocks off", 120, () -> {
            ridden(new Vec3(4.5, FLOOR, -26.5), 0);
            prey = mob(EntityTypes.RABBIT, new Vec3(4.5, FLOOR, -20.5), false);
        }, t -> {
            look();
            if (t == 6) lit = body.grabPrey() == prey;
            if (t == 8) body.startRiderAttack(rider, slot());
        }, () -> {
            String base = heldAndLetGo();
            return !base.startsWith("PASS") ? base : verdict(lit && captureAt - 8 <= ConstrictionCoil.STRIKE_TICKS,
                    "%s; outlined before the press: %s, taken %d ticks after it", base.substring(5), lit, captureAt - 8);
        }));
        // With nothing to take, or only prey too big to go round, a press casts nothing and costs nothing.
        plan.add(new Step("ridden press with no prey", 40, () -> { ridden(new Vec3(-4.5, FLOOR, -26.5), 180); prey = null; },
                t -> { if (t == 8) lit = body.startRiderAttack(rider, slot()); }, () ->
                verdict(!lit && !body.isAttacking() && body.isAttackReady(wrapMove()), "the press started nothing: %s, still ready: %s", !lit, body.isAttackReady(wrapMove()))));
        plan.add(new Step("ridden press at a Golemon", 40, () -> {
            ridden(new Vec3(-4.5, FLOOR, 26.5), 180);
            var golemon = DigimonEntity.spawnWild(world, DigimonSpeciesRegistry.getOrThrow(Constants.id("golemon")), 20, new Vec3(-4.5, FLOOR, 21.5));
            golemon.setNoAi(true);
            prey = golemon;
        }, t -> {
            look();
            if (t == 8) lit = body.startRiderAttack(rider, slot()) || body.grabPrey() != null;
        }, () -> verdict(!lit && captureAt < 0, "not outlined and nothing cast: %s", !lit)));
        return plan;
    }

    /** The verdict of a hold: the prey taken, squeezed four times while held, and let go. */
    private static String heldAndLetGo() {
        boolean ok = captureAt >= 0 && releaseAt > captureAt && squeezes >= ConstrictionCoil.SQUEEZES;
        return verdict(ok, "taken at tick %d, held %d ticks, squeezed %d times, let go at tick %d", captureAt, heldTicks, squeezes, releaseAt);
    }

    // --- staging ------------------------------------------------------------------------------------------------------

    private static void begin() {
        stepTick = 0;
        captureAt = releaseAt = -1;
        squeezes = heldTicks = iceTicks = 0;
        held = lit = false;
        preyFrom = preyHeld = null;
        if (body != null) body.discard();
        if (prey != null) prey.discard();
        body = null;
        prey = null;
        for (var e : world.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(-HALF, FLOOR - 10, -HALF, HALF, FLOOR + 10, HALF),
                e -> !(e instanceof ServerPlayer))) e.discard();
        steps.get(stepIndex).start().run();
        if (prey != null) { lastHealth = prey.getHealth(); preyFrom = prey.position(); }
    }

    private static void wild(Vec3 at, float yaw) {
        body = DigimonEntity.spawnWild(world, DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon")), 20, at);
        toughen(body);
        body.snapTo(at.x, at.y, at.z, yaw, 0);
        body.yBodyRot = body.yHeadRot = yaw;
    }

    private static void ridden(Vec3 at, float yaw) {
        wild(at, yaw);
        body.seatScenarioRider(rider);
        try {
            var seat = net.minecraft.world.entity.Entity.class.getDeclaredField("vehicle");
            var riders = net.minecraft.world.entity.Entity.class.getDeclaredField("passengers");
            seat.setAccessible(true); riders.setAccessible(true);
            seat.set(rider, body);
            riders.set(body, com.google.common.collect.ImmutableList.of(rider));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot seat the fake rider", e);
        }
        body.driveScenarioRider(true, false);
        rider.setYRot(yaw);
        rider.setXRot(0);
    }

    /** A vanilla mob with health to spare (the frost and the squeezes would kill a chicken at once). */
    private static LivingEntity mob(EntityType<?> type, Vec3 at, boolean free) {
        var entity = type.create(world, EntitySpawnReason.COMMAND);
        if (!(entity instanceof Mob mob)) throw new IllegalStateException("no mob of " + type);
        mob.setPos(at.x, at.y, at.z);
        mob.setPersistenceRequired();
        mob.setNoAi(!free);
        world.addFreshEntity(mob);
        toughen(mob);
        return mob;
    }

    private static void toughen(LivingEntity entity) {
        entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        entity.setHealth(entity.getMaxHealth());
    }

    private static void aim() { body.setTarget(prey); }

    /** The rider looks at the prey's middle. */
    private static void look() {
        if (prey == null) return;
        Vec3 to = prey.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
    }

    private static DigimonAttack wrapMove() { return com.digicube.digimon.DigimonSpeciesBootstrap.CONSTRICTION; }

    private static int slot() { return body.riderAttacks().indexOf(wrapMove()); }

    /** Every tick: when the prey is taken and let go, its squeezes, where it is held, and how long Ice Blast plays. */
    private static void watch() {
        if (body == null) return;
        if ("ice_blast".equals(body.currentAttackAnimation())) iceTicks++;
        if (prey == null || !prey.isAlive()) return;
        boolean now = prey.hasEffect(DCEffects.CONSTRICTED);
        if (now && !held && captureAt < 0) captureAt = stepTick;
        if (!now && held && releaseAt < 0) releaseAt = stepTick;
        if (now) {
            heldTicks++;
            if (prey.getHealth() < lastHealth - .01F) squeezes++;
            if (captureAt >= 0 && stepTick - captureAt == 10) preyHeld = prey.position();
        }
        held = now;
        lastHealth = prey.getHealth();
        if (prey.getHealth() < 100) toughen(prey);
    }

    private static String verdict(boolean pass, String format, Object... args) {
        return (pass ? "PASS " : "FAIL ") + String.format(format, args);
    }

    private static void finish() {
        done = true;
        if (body != null) body.discard();
        if (prey != null) prey.discard();
        Constants.LOG.info("[wrap] RESULT {} of {} checks passed{}", passed, total, failures.isEmpty() ? "" : ": " + String.join("; ", failures));
        world.getServer().halt(false);
    }
}
