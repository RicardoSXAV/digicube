package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.PepperBreathEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Agumon's kit against bodies that move the way players and mobs do, with Agumon's own AI choosing when to act:
 * how many Pepper Breath balls land on each kind of movement and whether Agumon faces where it fires; how many
 * leaping claws land (from 1.2 to 3.6 blocks out, on small and big bodies), never before the landing and never at the
 * cost of a fall; and the Burn a ball leaves, which water puts out. The prey is driven along a scripted path (walking
 * and sprinting speeds of a player, jumps with vanilla gravity) and never fights back. Opt-in through
 * {@link CombatScenario} as {@code agumon_checks}.
 */
final class AgumonScenario {
    /**
     * @param minimum share of the balls that must land for the case to pass
     */
    private record Case(String move, String motion, String prey, double speed, int shots, double minimum) {
        String id() { return move + "/" + motion + "/" + prey; }
    }
    /** A player walks .216 blocks a tick and sprints .281. */
    private static final double WALK = .216, SPRINT = .281;
    private static final List<Case> CASES = List.of(
            new Case("pepper_breath", "still", "villager", 0, 8, .95),
            new Case("pepper_breath", "strafe", "villager", WALK, 14, .8),
            new Case("pepper_breath", "sprint", "villager", SPRINT, 14, .7),
            new Case("pepper_breath", "circle", "villager", WALK, 14, .7),
            new Case("pepper_breath", "radial", "villager", WALK, 12, .8),
            new Case("pepper_breath", "hop", "villager", WALK, 14, .6),
            new Case("pepper_breath", "juke", "villager", WALK, 20, .35),
            // Stands until it sees the inhale, then sprints aside for a second, as a player who knows the move does.
            new Case("pepper_breath", "dodge", "villager", SPRINT, 12, 0),
            new Case("pepper_breath", "strafe", "gabumon", WALK, 10, .8),
            // Leaps from every distance in reach, the claw landing on bodies from a player's to Golemon's.
            new Case("claw", "still", "villager", 0, 12, .9),
            new Case("claw", "strafe", "villager", WALK, 12, .7),
            new Case("claw", "radial", "villager", WALK, 12, .7),
            new Case("claw", "still", "gabumon", 0, 9, .9),
            new Case("claw", "still", "golemon", 0, 9, .9),
            // A ball's fire is a Burn for as long as it lasts; the second time, the prey stands in water.
            new Case("pepper_breath", "burn", "villager", 0, 1, 1),
            new Case("pepper_breath", "douse", "villager", 0, 1, 1));
    private static final Vec3 CASTER_START = new Vec3(.5, CombatScenario.FLOOR_Y, -9.5);
    private static final AABB ARENA = new AABB(-14, CombatScenario.FLOOR_Y - 6, -14, 15, CombatScenario.FLOOR_Y + 9, 15);
    private static final int SETTLE_TICKS = 20, REST_TICKS = 12, TIMEOUT_TICKS = 20 * 90;
    /** Vanilla's jump and fall for a living body: impulse, gravity and air drag per tick. */
    private static final double JUMP = .42, GRAVITY = .08, DRAG = .98;

    private static int index = -1, ticks, idleTicks, released, landed, passed, failed;
    /** Claw cases: casts, casts that drew blood, blows before the landing, ticks the caster lost health. */
    private static int casts, clawHits, earlyHits, casterHurt;
    private static boolean castHit, wasAttacking;
    /** Blocks between the caster's front and the prey's side when each claw case sets the caster down, in turn. */
    private static final double[] LEAP_FROM = {1.2, 2.5, 3.6};
    /** Burn cases: the tick the ball struck, the most Burn shown after it, and whether the mark went out with the fire. */
    private static int struckAt = -1;
    private static float burnSeen;
    private static boolean burnOutWithFire = true;
    private static boolean initialized, done;
    private static DigimonEntity caster;
    private static Mob prey;
    private static final Set<UUID> SEEN = new HashSet<>(), LANDED = new HashSet<>();
    private static final List<PepperBreathEntity> FLYING = new ArrayList<>();
    private static double facingWorst, facingSum;
    private static final List<String> SUMMARY = new ArrayList<>();
    /** The scripted path's state: where along it, which way, the vertical speed, and the next turn of a juke. */
    private static double along, direction, verticalSpeed;
    private static int nextTurn, groundedTicks;
    private static Vec3 place;
    private static RandomSource random;

    private AgumonScenario() {}

    static void tick(ServerLevel level) {
        if (done) return;
        try {
            if (!initialized) {
                initialized = true;
                for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) level.setChunkForced(x, z, true);
                CombatScenario.build(level, "flat");
                level.getServer().tickRateManager().requestGameToSprint(1_000_000);
                next(level);
            } else observe(level);
        } catch (RuntimeException | AssertionError e) {
            Constants.LOG.error("[agumon-case] aborted {}", index, e);
            failed++;
            finish(level);
        }
    }

    private static DigimonSpecies agumonWith(String move) {
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon"));
        var bundled = com.digicube.digimon.DigimonSpeciesBootstrap.bundled(species.id());
        var moves = bundled.attacks().stream().filter(a -> a.id().getPath().equals(move)).toList();
        if (moves.isEmpty()) throw new AssertionError("Agumon has no " + move);
        var only = new DigimonSpecies(bundled.id(), bundled.stage(), bundled.attribute(), bundled.baseHealth(), bundled.baseAttack(),
                bundled.baseDefence(), bundled.baseSpeed(), bundled.evolutions(), moves, bundled.body(), bundled.locomotion(), bundled.tactics());
        DigimonSpeciesRegistry.replace(only);
        return only;
    }

    private static void next(ServerLevel level) {
        if (caster != null) caster.discard();
        if (prey != null) prey.discard();
        level.getEntities((Entity) null, ARENA).forEach(Entity::discard);
        if (++index == CASES.size()) { finish(level); return; }
        var c = CASES.get(index);
        ticks = idleTicks = released = landed = 0;
        casts = clawHits = earlyHits = casterHurt = 0; castHit = wasAttacking = false;
        struckAt = -1; burnSeen = 0; burnOutWithFire = true;
        facingWorst = facingSum = 0;
        SEEN.clear(); LANDED.clear(); FLYING.clear();
        random = RandomSource.create(index * 7919L + 17);
        along = 0; direction = 1; verticalSpeed = 0; groundedTicks = 0; nextTurn = c.motion().equals("dodge") ? 0 : 10 + random.nextInt(25);
        caster = DigimonEntity.spawnWild(level, agumonWith(c.move()), 20, CASTER_START);
        prey = c.prey().equals("villager") ? EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND)
                : DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id(c.prey())), 20, Vec3.ZERO);
        if (caster == null || prey == null) throw new AssertionError("fixture spawn");
        place = start(c);
        prey.setPos(place);
        if (c.prey().equals("villager")) level.addFreshEntity(prey);
        prey.setNoAi(true); prey.setNoGravity(true);
        caster.setYRot(0); caster.yBodyRot = caster.yHeadRot = 0;
        for (LivingEntity mob : new LivingEntity[]{caster, prey}) {
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
            mob.setHealth(mob.getMaxHealth());
        }
    }

    private static Vec3 start(Case c) {
        double y = CombatScenario.FLOOR_Y;
        return switch (c.motion()) {
            case "circle" -> new Vec3(.5 + 7, y, .5);
            case "radial" -> new Vec3(.5, y, -1);
            default -> new Vec3(.5, y, 5.5);
        };
    }

    /** One tick along the case's path: the prey's next position. */
    private static Vec3 drive(Case c) {
        double y = place.y, floor = CombatScenario.FLOOR_Y, step = c.speed();
        if (c.motion().equals("hop") && y <= floor && ++groundedTicks >= 4) { verticalSpeed = JUMP; groundedTicks = 0; }
        if (verticalSpeed != 0 || y > floor) {
            y += verticalSpeed; verticalSpeed = (verticalSpeed - GRAVITY) * DRAG;
            if (y <= floor) { y = floor; verticalSpeed = 0; }
        }
        return switch (c.motion()) {
            case "still" -> place;
            case "dodge" -> {
                // Reacts four ticks into the wind-up, alternating sides, and keeps going for twenty ticks.
                if (caster.isAttacking() && caster.currentAttackTick() == 4 && nextTurn <= ticks) { direction = along > 0 ? -1 : 1; nextTurn = ticks + 20; }
                if (ticks < nextTurn) along = Mth.clamp(along + direction * step, -10, 10);
                yield new Vec3(.5 + along, y, 5.5);
            }
            case "circle" -> {
                along += step / 7;
                yield new Vec3(.5 + 7 * Math.cos(along), y, .5 + 7 * Math.sin(along));
            }
            case "radial" -> {
                along += direction * step;
                if (along > 12 || along < 0) { direction = -direction; along = Mth.clamp(along, 0, 12); }
                yield new Vec3(.5, y, -1 + along);
            }
            default -> {
                // Strafe, sprint, hop and juke run along x between the arena's sides; a juke also turns at random.
                if (c.motion().equals("juke") && ticks >= nextTurn) { direction = -direction; nextTurn = ticks + 10 + random.nextInt(25); }
                along += direction * step;
                if (Math.abs(along) > 10) { direction = -Math.signum(along); along = Mth.clamp(along, -10, 10); }
                yield new Vec3(.5 + along, y, 5.5);
            }
        };
    }

    private static void observe(ServerLevel level) {
        var c = CASES.get(index);
        ticks++;
        if (caster.isRemoved() || prey.isRemoved()) throw new AssertionError("fighter removed in " + c.id());
        // Read this tick's damage before healing it away.
        boolean preyHurt = prey.getHealth() < prey.getMaxHealth() - 1e-3, hurtCaster = caster.getHealth() < caster.getMaxHealth() - 1e-3;
        for (LivingEntity mob : new LivingEntity[]{caster, prey}) mob.setHealth(mob.getMaxHealth());
        if (c.move().equals("claw")) { observeClaw(level, c, preyHurt, hurtCaster); return; }
        if (c.motion().equals("burn") || c.motion().equals("douse")) { observeBurn(level, c); return; }
        prey.clearFire();
        if (ticks > SETTLE_TICKS) {
            place = drive(c);
            prey.setPos(place);
            prey.setOnGround(place.y <= CombatScenario.FLOOR_Y);
            prey.setDeltaMovement(Vec3.ZERO);
            if (caster.getTarget() != prey) caster.setTarget(prey);
        } else {
            caster.getNavigation().stop();
        }
        // Many casts in one run: the cooldown is cleared a short rest after each one ends.
        if (caster.isAttacking()) idleTicks = 0;
        else if (++idleTicks >= REST_TICKS && released < c.shots()) caster.readyAttacks();
        for (var ball : level.getEntitiesOfClass(PepperBreathEntity.class, ARENA, b -> b.getOwner() == caster)) {
            if (SEEN.add(ball.getUUID())) {
                released++;
                FLYING.add(ball);
                Vec3 v = ball.getDeltaMovement();
                float flight = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
                double error = Math.abs(Mth.wrapDegrees(flight - caster.yBodyRot));
                facingWorst = Math.max(facingWorst, error); facingSum += error;
            }
            if (ball.impacting() && ball.getBoundingBox().inflate(1).intersects(prey.getBoundingBox()) && LANDED.add(ball.getUUID())) landed++;
        }
        FLYING.removeIf(ball -> ball.isRemoved() || ball.impacting());
        boolean over = released >= c.shots() && FLYING.isEmpty() && !caster.isAttacking();
        if (!over && ticks < TIMEOUT_TICKS) return;
        double share = released == 0 ? 0 : landed / (double) released;
        boolean pass = released >= c.shots() && share >= c.minimum();
        if (pass) passed++; else failed++;
        String line = String.format(Locale.ROOT, "%s %s landed %d/%d (%.0f%%, needs %.0f%%) facing worst %.1f mean %.1f deg in %d ticks",
                pass ? "PASS" : "FAIL", c.id(), landed, released, share * 100, c.minimum() * 100, facingWorst,
                released == 0 ? 0 : facingSum / released, ticks);
        SUMMARY.add(line);
        Constants.LOG.info("[agumon-case] {}", line);
        next(level);
    }

    /**
     * The leaping claw: every cast from 1.2, 2.5 or 3.6 blocks out (the caster is set down there, facing the prey, a rest
     * after each cast), counted as a hit when the prey loses health during it; a blow before the landing tick, or any
     * health the caster itself loses (a fall), fails the case.
     */
    private static void observeClaw(ServerLevel level, Case c, boolean preyHurt, boolean hurtCaster) {
        var claw = com.digicube.digimon.AuthoredAttacks.get(Constants.id("claw"));
        if (ticks > SETTLE_TICKS) {
            place = drive(c);
            prey.setPos(place); prey.setOnGround(true); prey.setDeltaMovement(Vec3.ZERO);
            if (caster.getTarget() != prey) caster.setTarget(prey);
        } else caster.getNavigation().stop();
        if (hurtCaster) casterHurt++;
        boolean attacking = caster.isAttacking();
        if (attacking && !wasAttacking) { casts++; castHit = false; }
        if (preyHurt && attacking) {
            // The timeline has already counted this tick on: a blow struck at attack tick t reads t + 1 here.
            if (caster.currentAttackTick() - 1 < claw.leap().land() - 1) earlyHits++;
            if (!castHit) { castHit = true; clawHits++; }
        }
        wasAttacking = attacking;
        if (attacking) idleTicks = 0;
        else if (++idleTicks == REST_TICKS && casts < c.shots()) {
            // Set the caster down at the next distance, facing the prey, and let its own AI take it from there.
            double distance = LEAP_FROM[casts % LEAP_FROM.length] + prey.getBbWidth() / 2 + caster.getBbWidth() / 2;
            double angle = random.nextDouble() * Math.PI - Math.PI / 2;
            Vec3 from = new Vec3(place.x + Math.sin(angle) * distance, CombatScenario.FLOOR_Y, place.z - Math.cos(angle) * distance);
            caster.getNavigation().stop(); caster.setDeltaMovement(Vec3.ZERO);
            caster.snapTo(from.x, from.y, from.z, (float) Math.toDegrees(Math.atan2(-(place.x - from.x), place.z - from.z)), 0);
            caster.yBodyRot = caster.yHeadRot = caster.getYRot();
            caster.readyAttacks();
        }
        boolean over = casts >= c.shots() && !attacking && idleTicks >= REST_TICKS;
        if (!over && ticks < TIMEOUT_TICKS) return;
        double share = casts == 0 ? 0 : clawHits / (double) casts;
        boolean pass = casts >= c.shots() && share >= c.minimum() && earlyHits == 0 && casterHurt == 0;
        report(pass, String.format(Locale.ROOT, "%s %s landed %d/%d leaps (%.0f%%, needs %.0f%%), %d blows before landing, caster hurt %d ticks, in %d ticks",
                pass ? "PASS" : "FAIL", c.id(), clawHits, casts, share * 100, c.minimum() * 100, earlyHits, casterHurt, ticks));
        next(level);
    }

    /** A ball's Burn: the emblem's readout shows while the prey burns and goes out with the fire, or at once in water. */
    private static void observeBurn(ServerLevel level, Case c) {
        if (ticks > SETTLE_TICKS && struckAt < 0 && caster.getTarget() != prey) caster.setTarget(prey);
        prey.setPos(place);
        if (caster.isAttacking()) idleTicks = 0; else if (++idleTicks >= REST_TICKS && struckAt < 0) caster.readyAttacks();
        float burn = com.digicube.entity.CombatMarkState.burnRemaining(((com.digicube.entity.CombatMarkState) prey).digicube$marks2());
        if (struckAt < 0 && prey.isOnFire()) {
            struckAt = ticks; burnSeen = burn;
            if (c.motion().equals("douse")) level.setBlock(net.minecraft.core.BlockPos.containing(place), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
        }
        if (struckAt >= 0) {
            // The caster stands down, so the fire is the one ball's alone.
            caster.setTarget(null); caster.getNavigation().stop();
            if (ticks > struckAt + 1 && !prey.isOnFire() && burn > 0) burnOutWithFire = false;
            burnSeen = Math.max(burnSeen, burn);
        }
        int wait = c.motion().equals("douse") ? 10 : 90;
        boolean over = struckAt >= 0 && ticks > struckAt + wait;
        if (!over && ticks < TIMEOUT_TICKS) return;
        boolean out = !prey.isOnFire() && burn == 0;
        boolean pass = struckAt >= 0 && burnSeen > .8F && burnOutWithFire && out;
        report(pass, String.format(Locale.ROOT, "%s %s struck at %d, Burn %.2f of its length, out with the fire %s, gone after %d ticks %s",
                pass ? "PASS" : "FAIL", c.id(), struckAt, burnSeen, burnOutWithFire, wait, out));
        if (c.motion().equals("douse")) level.setBlock(net.minecraft.core.BlockPos.containing(place), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        next(level);
    }

    private static void report(boolean pass, String line) {
        if (pass) passed++; else failed++;
        SUMMARY.add(line);
        Constants.LOG.info("[agumon-case] {}", line);
    }

    private static void finish(ServerLevel level) {
        done = true;
        for (String line : SUMMARY) Constants.LOG.info("[agumon-checks] {}", line);
        Constants.LOG.info("[agumon-checks] RESULT {} passed={} failed={}", failed == 0 ? "PASS" : "FAIL", passed, failed);
        level.getServer().halt(false);
    }
}
