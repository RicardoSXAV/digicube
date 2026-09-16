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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Real-server single-cast checks complement autonomous armed-opponent duels. */
final class BetamonScenario {
    private record Fixture(String move, int yaw, String target, double sideways, boolean water, String boundary) {}
    private static final List<Fixture> CASES = new ArrayList<>();
    static {
        for (boolean water : new boolean[]{false, true})
            for (String move : new String[]{"headbutt", "electric_shock"}) {
                for (int yaw = 0; yaw < 360; yaw += 45)
                    for (String target : new String[]{"agumon", "golemon"})
                        for (double speed : new double[]{0, -.015, .015})
                            CASES.add(new Fixture(move, yaw, target, speed, water, ""));
                for (String boundary : new String[]{"ally", "invulnerable", "retry", "interrupt", "cover", "lost", "range"})
                    CASES.add(new Fixture(move, 0, "agumon", 0, water, boundary));
            }
    }
    private static final Vec3 ORIGIN = new Vec3(.5, 300, .5);
    private static final AABB ARENA = new AABB(-20, 294, -20, 21, 313, 21);
    private static int index = -1, ticks, hits, passed, failed;
    private static boolean initialized, done, waterArena;
    private static DigimonEntity caster, target;
    private static DigimonAttack attack;
    private static Vec3 start;
    private static float health;

    static void tick(ServerLevel level) {
        if (done) return;
        try {
            if (!initialized) {
                initialized = true;
                for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) level.setChunkForced(x, z, true);
                level.getServer().tickRateManager().requestGameToSprint(30000);
                build(level, false);
                next(level);
            } else observe(level);
        } catch (RuntimeException | AssertionError e) {
            failed++;
            Constants.LOG.error("[betamon-case] aborted {}", index, e);
            finish(level);
        }
    }

    private static void build(ServerLevel level, boolean water) {
        waterArena = water;
        for (int x = -18; x <= 18; x++) for (int z = -18; z <= 18; z++) for (int y = 298; y <= 310; y++) {
            boolean rim = Math.abs(x) == 18 || Math.abs(z) == 18;
            level.setBlock(new BlockPos(x, y, z), (y < 300 || rim ? Blocks.STONE
                    : water && y <= 304 ? Blocks.WATER : Blocks.AIR).defaultBlockState(), 3);
        }
    }

    private static void next(ServerLevel level) {
        level.getEntities((Entity) null, ARENA).forEach(Entity::discard);
        if (++index == CASES.size()) { finish(level); return; }
        var c = CASES.get(index);
        if (waterArena != c.water) build(level, c.water);
        for (int x = -5; x <= 5; x++) for (int y = 300; y <= 308; y++)
            level.setBlock(new BlockPos(x, y, 1), (c.water && y <= 304 ? Blocks.WATER : Blocks.AIR).defaultBlockState(), 3);
        ticks = hits = 0;
        caster = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("betamon")), 20, ORIGIN);
        target = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id(c.target)), 20, ORIGIN.add(0, 0, 2));
        target.setNoAi(true);
        for (Mob mob : new Mob[]{caster, target}) {
            mob.setNoGravity(true);
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
            mob.setHealth(mob.getMaxHealth());
        }
        attack = caster.getSpecies().orElseThrow().attacks().stream().filter(a -> a.id().getPath().equals(c.move)).findFirst().orElseThrow();
        caster.setYRot(c.yaw);
        caster.yBodyRot = caster.yHeadRot = c.yaw;
        health = target.getHealth();
        start = ORIGIN.add(0, 0, 2);
    }

    private static void observe(ServerLevel level) {
        var c = CASES.get(index);
        ticks++;
        if (target.getHealth() < health - .01 && target.getLastDamageSource() != null
                && target.getLastDamageSource().getEntity() == caster) hits++;
        health = target.getHealth();
        caster.setTarget(null);
        caster.setLastHurtByMob(null);
        caster.getNavigation().stop();
        caster.setPos(ORIGIN);
        caster.setOnGround(true);
        caster.setDeltaMovement(Vec3.ZERO);
        // Fluid state has now been updated by real entity ticks before selecting a reachable position.
        if (ticks == 8) {
            start = null;
            for (double distance = 2.4; distance >= .6; distance -= .05) {
                var candidate = AttackGeometry.world(ORIGIN, new Vec3(0, 0, distance), c.yaw);
                target.setPos(candidate);
                if (caster.canAttackFrom(attack, target, ORIGIN)) { start = candidate; break; }
            }
            if (start == null) throw new AssertionError("No reachable fixture " + c);
            // Keep moving fixtures inside the reachable envelope instead of starting at its edge.
            if (c.sideways != 0) start = ORIGIN.add(start.subtract(ORIGIN).scale(.85));
            if (c.boundary.equals("range")) start = ORIGIN.add(0, 0, 12);
            if (c.boundary.equals("ally")) {
                var owner = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
                owner.setPos(14, 300, 14); owner.setNoAi(true); level.addFreshEntity(owner);
                caster.setOwner(owner); target.setOwner(owner);
            }
            if (c.boundary.equals("invulnerable")) target.setInvulnerable(true);
        }
        var velocity = new Vec3(c.sideways, 0, 0).yRot((float) -Math.toRadians(c.yaw));
        if (!target.isRemoved()) {
            target.setPos(start.add(velocity.scale(Math.max(0, ticks - 10))));
            target.setDeltaMovement(velocity);
        }
        if (ticks == 10) {
            caster.startAttack(attack, target);
            if (c.boundary.isEmpty() && !caster.isAttacking()) throw new AssertionError("Refused " + c);
        }
        if (ticks == 12) {
            if (c.boundary.equals("interrupt")) {
                caster.interruptAttack();
                if (caster.isAttackReady(attack)) throw new AssertionError("Interrupted cast lost cooldown");
            }
            if (c.boundary.equals("lost")) target.discard();
            if (c.boundary.equals("cover")) for (int x = -5; x <= 5; x++) for (int y = 300; y <= 308; y++)
                level.setBlock(new BlockPos(x, y, 1), Blocks.STONE.defaultBlockState(), 3);
        }
        // Reject the first active frame, after commitment, without cancelling windup.
        if (ticks == 10 + attack.hitTick() && c.boundary.equals("retry")) target.setInvulnerable(true);
        if (ticks == 11 + attack.hitTick() && c.boundary.equals("retry")) target.setInvulnerable(false);
        if (ticks >= 100) {
            boolean pass = (c.boundary.isEmpty() || c.boundary.equals("retry") ? hits == 1 : hits == 0)
                    && !caster.isAttacking() && caster.isAttackReady(attack);
            if (pass) passed++; else failed++;
            Constants.LOG.info("[betamon-case] {} {} hits={}", pass ? "PASS" : "FAIL", c, hits);
            next(level);
        }
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[scenario] {} betamon_checks passed={} failed={} total={}",
                failed == 0 && passed == CASES.size() ? "PASS" : "FAIL", passed, failed, CASES.size());
        level.getServer().halt(false);
    }
}
