package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.DigimonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elecmon's two moves cast on the real server ({@code DIGICUBE_SCENARIO=elecmon_checks}), one case at a time.
 *
 * <p>Sparkling Thunder, a discharge from the ball over its fan: the bolt strikes a target 2.5 to 7.5 blocks ahead once,
 * at every heading; a wall between stops it; it jumps on along a line of foes, each struck once; a foe pressed against
 * the body is struck by the burst; a passive animal and an ally standing by are never struck.
 *
 * <p>Nine Tails, a whirl: the disc of tails strikes a foe standing close on any side (ahead, beside, behind; a small body
 * and a big one) once, both of two foes on either side, nothing out of its reach, never an ally.
 * Every case logs its own line; the run ends with {@code [scenario] PASS|FAIL elecmon_checks}.
 */
final class ElecmonScenario {
    /** A body in a case: its species (a Digimon id path, or "cow"), where it stands (blocks ahead and to the left of the caster), its role. */
    private record Body(String species, double ahead, double left, String role) {}
    /** A case: the move, the heading the caster faces, the bodies, the terrain, and what must be struck (role -> hits). */
    private record Case(String name, String move, int yaw, List<Body> bodies, String terrain, Map<String, Integer> expect) {}

    private static final List<Case> CASES = new ArrayList<>();
    static {
        for (int yaw = 0; yaw < 360; yaw += 45) {
            for (double d : new double[]{2.5, 5.0, 7.5})
                CASES.add(new Case("bolt " + d, "sparkling_thunder", yaw, List.of(new Body(yaw % 90 == 0 ? "golemon" : "agumon", d, 0, "target")), "flat", Map.of("target", 1)));
            // the whirl reaches round him: a foe ahead, beside and behind
            for (int around = 0; around < 360; around += 90) {
                double a = Math.toRadians(around);
                CASES.add(new Case("whirl agumon at " + around, "nine_tails", yaw,
                        List.of(new Body("agumon", 1.15 * Math.cos(a), 1.15 * Math.sin(a), "target")), "flat", Map.of("target", 1)));
            }
            // a big body close by (its box held clear of his: farther on a diagonal, where the boxes meet corner to corner)
            CASES.add(new Case("whirl golemon", "nine_tails", yaw, List.of(new Body("golemon", yaw % 90 == 0 ? 1.8 : 2.2, 0, "target")), "flat", Map.of("target", 1)));
        }
        CASES.add(new Case("whirl out of reach", "nine_tails", 0, List.of(new Body("agumon", 2.2, 0, "target")), "flat", Map.of("target", 0)));
        CASES.add(new Case("whirl at foes on both sides", "nine_tails", 90, List.of(new Body("agumon", 0, 1.1, "target"),
                new Body("agumon", 0, -1.1, "foe1")), "flat", Map.of("target", 1, "foe1", 1)));
        CASES.add(new Case("whirl beside an ally", "nine_tails", 0, List.of(new Body("agumon", 1.1, 0, "ally")), "flat", Map.of("ally", 0)));
        CASES.add(new Case("bolt behind a wall", "sparkling_thunder", 0, List.of(new Body("agumon", 5.0, 0, "target")), "wall", Map.of("target", 0)));
        CASES.add(new Case("bolt jumps along a line", "sparkling_thunder", 45, List.of(new Body("agumon", 4.0, 0, "target"),
                new Body("agumon", 7.5, .5, "foe1"), new Body("agumon", 11.0, 0, "foe2")), "flat", Map.of("target", 1, "foe1", 1, "foe2", 1)));
        CASES.add(new Case("burst from the body", "sparkling_thunder", 0, List.of(new Body("agumon", 4.5, 0, "target"),
                new Body("agumon", -1.3, 0, "foe1")), "flat", Map.of("target", 1, "foe1", 1)));
        CASES.add(new Case("bystanders are spared", "sparkling_thunder", 270, List.of(new Body("agumon", 4.0, 0, "target"),
                new Body("cow", 5.5, 1.2, "cow"), new Body("agumon", 6.5, -1.0, "ally")), "flat", Map.of("target", 1, "cow", 0, "ally", 0)));
    }

    private static final Vec3 ORIGIN = new Vec3(.5, 300, .5);
    private static final AABB ARENA = new AABB(-24, 294, -24, 25, 313, 25);
    private static int index = -1, ticks, passed, failed;
    private static boolean initialized, done;
    private static DigimonEntity caster;
    private static DigimonAttack attack;
    private static final Map<String, LivingEntity> bodies = new LinkedHashMap<>();
    private static final Map<String, Integer> hits = new LinkedHashMap<>();
    private static final Map<String, Float> health = new LinkedHashMap<>();
    private static boolean refused;
    private static double moved;

    static void tick(ServerLevel level) {
        if (done) return;
        try {
            if (!initialized) {
                initialized = true;
                for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) level.setChunkForced(x, z, true);
                level.getServer().tickRateManager().requestGameToSprint(30000);
                next(level);
            } else observe(level);
        } catch (RuntimeException | AssertionError e) {
            failed++;
            Constants.LOG.error("[elecmon-case] aborted {}", index, e);
            finish(level);
        }
    }

    private static void build(ServerLevel level) {
        for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++) for (int y = 298; y <= 310; y++) {
            boolean rim = Math.abs(x) == 22 || Math.abs(z) == 22;
            level.setBlock(new BlockPos(x, y, z), (y < 300 || rim ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
        }
    }

    private static Vec3 at(Case c, double ahead, double left) {
        return AttackGeometry.world(ORIGIN, new Vec3(left, 0, ahead), c.yaw);
    }

    private static void next(ServerLevel level) {
        level.getEntities((Entity) null, ARENA).forEach(Entity::discard);
        if (++index == CASES.size()) { finish(level); return; }
        var c = CASES.get(index);
        build(level);
        if (c.terrain.equals("wall")) {
            // a wall two blocks thick across the way, half-way to the target, three blocks high
            var target = c.bodies.getFirst();
            for (double s = -3; s <= 3; s += .5) for (double t = -.5; t <= .5; t += .5) {
                Vec3 p = at(c, target.ahead * .5 + t, s);
                for (int y = 300; y <= 303; y++) level.setBlock(BlockPos.containing(p.x, y, p.z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        ticks = 0;
        bodies.clear(); hits.clear(); health.clear(); refused = false; moved = 0;
        caster = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("elecmon")), 20, ORIGIN);
        prepare(caster);
        caster.setYRot(c.yaw); caster.yBodyRot = caster.yHeadRot = c.yaw;
        for (var b : c.bodies) {
            Vec3 p = at(c, b.ahead, b.left);
            LivingEntity e;
            if (b.species.equals("cow")) {
                var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
                cow.setPos(p); level.addFreshEntity(cow); cow.setNoAi(true); e = cow;
            } else {
                var d = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id(b.species)), 20, p);
                d.setNoAi(true); prepare(d); e = d;
            }
            // sides as Battle Testing stages them: the caster and its ally on one, everything it fights on the other
            if (b.role.equals("ally")) { caster.joinBattleSide(1); ((DigimonEntity) e).joinBattleSide(1); }
            else if (e instanceof DigimonEntity d && !b.role.equals("cow")) d.joinBattleSide(2);
            // the foes beyond the target are fighting the caster: the bolt may jump to them
            if (b.role.startsWith("foe") && e instanceof Mob mob) mob.setTarget(caster);
            e.setYRot(c.yaw + 180);
            bodies.put(b.role, e); hits.put(b.role, 0); health.put(b.role, e.getHealth());
        }
        attack = caster.getSpecies().orElseThrow().attacks().stream().filter(a -> a.id().getPath().equals(c.move)).findFirst().orElseThrow();
    }

    private static void prepare(Mob mob) {
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
        mob.setHealth(mob.getMaxHealth());
        mob.setPersistenceRequired();
    }

    private static void observe(ServerLevel level) {
        var c = CASES.get(index);
        ticks++;
        for (var entry : bodies.entrySet()) {
            var e = entry.getValue();
            if (e.getHealth() < health.get(entry.getKey()) - .01F) hits.merge(entry.getKey(), 1, Integer::sum);
            health.put(entry.getKey(), e.getHealth());
        }
        caster.setTarget(null);
        caster.setLastHurtByMob(null);
        caster.getNavigation().stop();
        LivingEntity target = bodies.getOrDefault("target", bodies.getOrDefault("ally", null));
        if (ticks < 10) {
            // settle: the caster stands where the case put it (its own physics lands it), facing the heading
            caster.setDeltaMovement(caster.getDeltaMovement().multiply(0, 1, 0));
            caster.setYRot(c.yaw); caster.yBodyRot = caster.yHeadRot = c.yaw;
            for (var entry : bodies.entrySet()) entry.getValue().setDeltaMovement(entry.getValue().getDeltaMovement().multiply(0, 1, 0));
        }
        if (ticks == 10) {
            caster.readyAttacks();
            caster.startAttack(attack, target);
            refused = !caster.isAttacking();
        }
        // the whirl turns on the spot: the body must stay where it stood
        if (ticks > 10) moved = Math.max(moved, caster.position().multiply(1, 0, 1).distanceTo(ORIGIN.multiply(1, 0, 1)));
        if (ticks >= 10 + attack.durationTicks() + 12) verdict(level, c);
    }

    private static void verdict(ServerLevel level, Case c) {
        List<String> wrong = new ArrayList<>();
        if (refused && c.expect.values().stream().anyMatch(v -> v > 0)) wrong.add("the move was refused from where it stood");
        for (var e : c.expect.entrySet()) if (!hits.getOrDefault(e.getKey(), 0).equals(e.getValue()))
            wrong.add(e.getKey() + " struck " + hits.getOrDefault(e.getKey(), 0) + " times, wanted " + e.getValue());
        String detail;
        if (c.move.equals("nine_tails")) {
            if (moved > .35) wrong.add(String.format("the whirl moved the body %.2f blocks", moved));
            detail = String.format(" moved=%.2f", moved);
        } else detail = " discharge=" + caster.dischargeText();
        boolean pass = wrong.isEmpty() && !caster.isAttacking();
        if (pass) passed++; else failed++;
        Constants.LOG.info("[elecmon-case] {} {} yaw={} hits={}{}{}", pass ? "PASS" : "FAIL", c.name, c.yaw, hits, detail,
                wrong.isEmpty() ? "" : " " + String.join("; ", wrong));
        next(level);
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[scenario] {} elecmon_checks passed={} failed={} total={}",
                failed == 0 && passed == CASES.size() ? "PASS" : "FAIL", passed, failed, CASES.size());
        level.getServer().halt(false);
    }
}
