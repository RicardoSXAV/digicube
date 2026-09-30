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
 * Betamon's two moves cast on the real server ({@code DIGICUBE_SCENARIO=betamon_checks}), one case at a time.
 *
 * <p>Headbutt, a dash: the body is carried toward a target standing 1.8 to 4.2 blocks away (every heading, a small
 * body and a big one, still or stepping aside), strikes it once, stops at its body instead of running through it and
 * knocks it back; a wall in the way stops the dash with no hit; it dashes through water; an ally is never struck.
 *
 * <p>Electric Shock, a discharge: the bolt strikes a target 2.5 to 7.5 blocks away once, at every heading; a wall
 * between stops it; it jumps on along a line of foes, each struck once for less; standing in water it runs through the
 * water to foes off to the sides; a foe pressed against the body is struck by the burst; a passive animal and an ally
 * standing by are never struck. Every case logs its own line; the run ends with {@code [scenario] PASS|FAIL
 * betamon_checks}.
 */
final class BetamonScenario {
    /** A body in a case: its species (a Digimon id path, or "cow"), where it stands (blocks ahead and to the left of the caster, and up), its role. */
    private record Body(String species, double ahead, double left, String role) {}
    /** A case: the move, the heading the caster faces, the bodies, the terrain, and what must be struck (role -> hits). */
    private record Case(String name, String move, int yaw, List<Body> bodies, String terrain, Map<String, Integer> expect,
                        double sideways) {}

    private static final List<Case> CASES = new ArrayList<>();
    static {
        for (int yaw = 0; yaw < 360; yaw += 45) {
            for (String target : new String[]{"agumon", "golemon"})
                for (double d : new double[]{1.8, 3.0, 4.2}) {
                    CASES.add(new Case("dash " + target + " " + d, "headbutt", yaw, List.of(new Body(target, d, 0, "target")), "flat", Map.of("target", 1), 0));
                }
            CASES.add(new Case("dash at a target stepping aside", "headbutt", yaw, List.of(new Body("agumon", 3.2, 0, "target")), "flat", Map.of("target", 1), .06));
            for (double d : new double[]{2.5, 5.0, 7.5})
                CASES.add(new Case("bolt " + d, "electric_shock", yaw, List.of(new Body(yaw % 90 == 0 ? "golemon" : "agumon", d, 0, "target")), "flat", Map.of("target", 1), 0));
        }
        CASES.add(new Case("dash into a wall", "headbutt", 0, List.of(new Body("agumon", 3.6, 0, "target")), "wall", Map.of("target", 0), 0));
        CASES.add(new Case("dash through water", "headbutt", 90, List.of(new Body("agumon", 3.0, 0, "target")), "water", Map.of("target", 1), 0));
        CASES.add(new Case("dash at an ally", "headbutt", 0, List.of(new Body("agumon", 3.0, 0, "ally")), "flat", Map.of("ally", 0), 0));
        CASES.add(new Case("bolt behind a wall", "electric_shock", 0, List.of(new Body("agumon", 5.0, 0, "target")), "wall", Map.of("target", 0), 0));
        CASES.add(new Case("bolt jumps along a line", "electric_shock", 45, List.of(new Body("agumon", 4.0, 0, "target"),
                new Body("agumon", 7.5, .5, "foe1"), new Body("agumon", 11.0, 0, "foe2")), "flat", Map.of("target", 1, "foe1", 1, "foe2", 1), 0));
        CASES.add(new Case("bolt through water", "electric_shock", 180, List.of(new Body("agumon", 4.0, 0, "target"),
                new Body("agumon", -2.5, 4.5, "foe1"), new Body("agumon", -1.0, -5.0, "foe2")), "water", Map.of("target", 1, "foe1", 1, "foe2", 1), 0));
        CASES.add(new Case("burst from the body", "electric_shock", 0, List.of(new Body("agumon", 4.5, 0, "target"),
                new Body("agumon", -1.4, 0, "foe1")), "flat", Map.of("target", 1, "foe1", 1), 0));
        CASES.add(new Case("bystanders are spared", "electric_shock", 270, List.of(new Body("agumon", 4.0, 0, "target"),
                new Body("cow", 5.5, 1.2, "cow"), new Body("agumon", 6.5, -1.0, "ally")), "flat", Map.of("target", 1, "cow", 0, "ally", 0), 0));
    }

    private static final Vec3 ORIGIN = new Vec3(.5, 300, .5);
    private static final AABB ARENA = new AABB(-24, 294, -24, 25, 313, 25);
    private static int index = -1, ticks, passed, failed;
    private static boolean initialized, done;
    private static String terrain = "";
    private static DigimonEntity caster;
    private static DigimonAttack attack;
    private static final Map<String, LivingEntity> bodies = new LinkedHashMap<>();
    private static final Map<String, Integer> hits = new LinkedHashMap<>();
    private static final Map<String, Float> health = new LinkedHashMap<>();
    private static final Map<String, Vec3> startAt = new LinkedHashMap<>();
    private static double nearest, pushed, room, farthest;
    private static boolean refused;

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
            Constants.LOG.error("[betamon-case] aborted {}", index, e);
            finish(level);
        }
    }

    private static void build(ServerLevel level, String kind) {
        terrain = kind;
        for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++) for (int y = 298; y <= 310; y++) {
            boolean rim = Math.abs(x) == 22 || Math.abs(z) == 22;
            level.setBlock(new BlockPos(x, y, z), (y < 300 || rim ? Blocks.STONE
                    : kind.equals("water") && y <= 302 ? Blocks.WATER : Blocks.AIR).defaultBlockState(), 3);
        }
    }

    private static Vec3 at(Case c, double ahead, double left) {
        return AttackGeometry.world(ORIGIN, new Vec3(left, 0, ahead), c.yaw);
    }

    private static void next(ServerLevel level) {
        level.getEntities((Entity) null, ARENA).forEach(Entity::discard);
        if (++index == CASES.size()) { finish(level); return; }
        var c = CASES.get(index);
        build(level, c.terrain.equals("water") ? "water" : "flat");
        if (c.terrain.equals("wall")) {
            // a wall two blocks thick across the way, half-way to the target, three blocks high
            var target = c.bodies.getFirst();
            for (double s = -3; s <= 3; s += .5) for (double t = -.5; t <= .5; t += .5) {
                Vec3 p = at(c, target.ahead * .5 + t, s);
                for (int y = 300; y <= 302; y++) level.setBlock(BlockPos.containing(p.x, y, p.z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        ticks = 0;
        bodies.clear(); hits.clear(); health.clear(); startAt.clear();
        nearest = Double.MAX_VALUE; pushed = 0; farthest = 0; refused = false;
        caster = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("betamon")), 20, ORIGIN);
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
            bodies.put(b.role, e); hits.put(b.role, 0); health.put(b.role, e.getHealth()); startAt.put(b.role, p);
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
            // how far the dash may carry the body before it meets the target's box (a wide body met on its corner stops it sooner)
            if (target != null) room = Math.min(3.4, AttackGeometry.boxClearance(caster.getBoundingBox(), target.getBoundingBox(),
                    target.position().subtract(caster.position()).multiply(1, 0, 1).normalize()));
        }
        if (ticks > 10 && c.sideways != 0 && target != null && target.isAlive()) {
            Vec3 aside = new Vec3(c.sideways, 0, 0).yRot((float) -Math.toRadians(c.yaw));
            target.setPos(target.position().add(aside));
        }
        // the knock: a still body (no AI of its own) keeps the push it was given as its motion
        if (ticks > 10 && target != null) pushed = Math.max(pushed, target.getDeltaMovement().horizontalDistance());
        if (ticks > 10) farthest = Math.max(farthest, caster.position().multiply(1, 0, 1).distanceTo(ORIGIN.multiply(1, 0, 1)));
        if (ticks > 10 && target != null && c.move.equals("headbutt"))
            nearest = Math.min(nearest, target.getBoundingBox().getCenter().multiply(1, 0, 1).distanceTo(caster.position().multiply(1, 0, 1))
                    - (target.getBbWidth() + caster.getBbWidth()) * .5);
        if (ticks >= 10 + attack.durationTicks() + 12) verdict(level, c, target);
    }

    private static void verdict(ServerLevel level, Case c, LivingEntity target) {
        List<String> wrong = new ArrayList<>();
        if (refused && c.expect.values().stream().anyMatch(v -> v > 0)) wrong.add("the move was refused from where it stood");
        for (var e : c.expect.entrySet()) if (!hits.getOrDefault(e.getKey(), 0).equals(e.getValue()))
            wrong.add(e.getKey() + " struck " + hits.getOrDefault(e.getKey(), 0) + " times, wanted " + e.getValue());
        double travelled = farthest;
        String detail = "";
        if (c.move.equals("headbutt") && c.expect.getOrDefault("target", 0) == 1) {
            double knocked = pushed;
            // it runs up to the body and no further (a bodies' overlap of up to a quarter block is the push that separates them)
            if (nearest < -.25) wrong.add("ran into the target's body by " + String.format("%.2f", -nearest));
            if (knocked < .2) wrong.add("the target was not knocked back (" + String.format("%.2f", knocked) + ")");
            if (travelled < Math.min(room, 3.0) - .35) wrong.add("the dash stopped short (" + String.format("%.2f of %.2f", travelled, room) + ")");
            detail = String.format(" travelled=%.2f room=%.2f knocked=%.2f gap=%.2f", travelled, room, knocked, nearest);
        }
        if (c.terrain.equals("wall") && c.move.equals("headbutt")) detail = String.format(" travelled=%.2f", travelled);
        if (c.move.equals("electric_shock")) detail = " discharge=" + caster.dischargeText();
        boolean pass = wrong.isEmpty() && !caster.isAttacking();
        if (pass) passed++; else failed++;
        Constants.LOG.info("[betamon-case] {} {} yaw={} hits={}{}{}", pass ? "PASS" : "FAIL", c.name, c.yaw, hits, detail,
                wrong.isEmpty() ? "" : " " + String.join("; ", wrong));
        next(level);
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[scenario] {} betamon_checks passed={} failed={} total={}",
                failed == 0 && passed == CASES.size() ? "PASS" : "FAIL", passed, failed, CASES.size());
        level.getServer().halt(false);
    }
}
