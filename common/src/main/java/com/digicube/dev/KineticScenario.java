package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.*;
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

/** Dedicated-server fixtures for the solid-shot and escape/kick mechanics, opt-in through CombatScenario. */
final class KineticScenario {
    private record Case(String attack, int yaw, int elevation, String motion, boolean cow, String boundary) {
        String id() { return attack + "/" + (boundary.isEmpty() ? "aim/" + yaw + "/" + elevation + "/" + motion : "boundary/" + boundary) + "/" + (cow ? "cow" : "agumon"); }
    }
    private static final List<Case> CASES = cases();
    private static final Vec3 START = new Vec3(.5, 300, .5);
    private static final AABB ARENA = new AABB(-17, 294, -17, 18, 310, 18);
    private static int index = -1, ticks, hits, failed, passed;
    private static boolean initialized, done, started, kick;
    private static DigimonEntity caster;
    private static Mob target, owner;
    private static Vec3 initialTarget, velocity;
    private static float health;
    private static DigimonAttack attack;

    private KineticScenario() {}
    private static List<Case> cases() {
        var result = new ArrayList<Case>();
        for (String attack : new String[]{"hunting_cannon", "jet_dash"}) for (int yaw = 0; yaw < 360; yaw += 45)
            for (int elevation : new int[]{-1, 0, 1}) for (String motion : new String[]{"still", "cross_left", "cross_right", "approach", "retreat"})
                for (boolean cow : new boolean[]{false, true}) result.add(new Case(attack, yaw, elevation, motion, cow, ""));
        for (String attack : new String[]{"hunting_cannon", "jet_dash"})
            for (String boundary : new String[]{"ally", "target_lost", "interrupted_reset", "cover_during_windup", "world_border", "invulnerability_retry", "commit_turn", "cross_after_commit", "repeat_frames", "unreachable_path", "wide_body_endpoint", "empty_visual_corner"})
                result.add(new Case(attack, 0, 0, "still", false, boundary));
        for (String boundary : new String[]{"near_target", "far_target", "cover_during_flight", "muzzle_blocked", "tracking_limit", "flight_sweep", "cooldown_fallback"})
            result.add(new Case("hunting_cannon", 0, 0, "still", false, boundary));
        for (String boundary : new String[]{"connected_kick", "clear_escape", "miss_after_commit", "obstacle_after_commit", "ledge_refusal", "cooldown_fallback", "close_overlap", "asymmetric_contact", "limb_sweep", "outside_limb_reach"})
            result.add(new Case("jet_dash", 0, 0, "still", false, boundary));
        return List.copyOf(result);
    }

    static void tick(ServerLevel level) {
        if (done) return;
        try {
            if (!initialized) {
                initialized = true;
                for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) level.setChunkForced(x, z, true);
                level.getServer().tickRateManager().requestGameToSprint(100_000);
                next(level);
            } else observe(level);
        } catch (RuntimeException | AssertionError e) {
            Constants.LOG.error("[kinetic-case] aborted fixture {}", index, e);
            finish(level, false);
        }
    }

    private static void build(ServerLevel level, int elevation) {
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) {
            int floor = x * x + z * z > 16 ? 300 + elevation : 300;
            for (int y = 298; y <= 305; y++) level.setBlock(new BlockPos(x, y, z),
                    (y < floor ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
        }
    }

    private static void next(ServerLevel level) {
        level.getWorldBorder().setSize(59_999_968);
        if (caster != null) caster.discard();
        if (target != null) target.discard();
        if (owner != null) owner.discard();
        level.getEntities((Entity) null, ARENA).forEach(Entity::discard);
        if (++index == CASES.size()) { finish(level, failed == 0); return; }
        Case c = CASES.get(index); build(level, c.attack.equals("hunting_cannon") ? c.elevation : 0);
        caster = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("centalmon")), 20, START);
        double distance = c.attack.equals("jet_dash") ? .75 : c.boundary.equals("near_target") ? 3 : c.boundary.equals("far_target") ? 13 : 8;
        if (c.boundary.equals("clear_escape") || c.boundary.equals("cooldown_fallback")) distance = 2.2;
        if (c.boundary.equals("cooldown_fallback") && c.attack.equals("hunting_cannon")) distance = 8;
        if (c.boundary.equals("unreachable_path")) distance = 24;
        if (c.boundary.equals("outside_limb_reach")) distance = 2.5;
        if (c.boundary.equals("close_overlap")) distance = .45;
        initialTarget = AttackGeometry.world(START, new Vec3(0, c.elevation, distance), c.yaw);
        target = c.cow ? EntityTypes.COW.create(level, EntitySpawnReason.COMMAND)
                : DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id(c.boundary.equals("wide_body_endpoint") ? "golemon" : "agumon")), 20, initialTarget);
        if (target == null || caster == null) throw new AssertionError("fixture spawn");
        if (c.cow) { target.setPos(initialTarget); level.addFreshEntity(target); }
        target.setNoAi(true); target.setNoGravity(true);
        if (c.boundary.equals("flight_sweep") || c.boundary.equals("empty_visual_corner")) {
            target.getAttribute(Attributes.SCALE).setBaseValue(.0625);
            target.refreshDimensions();
            initialTarget = c.attack.equals("hunting_cannon")
                    ? START.add(c.boundary.equals("flight_sweep") ? .18 : .21, 2 + (c.boundary.equals("flight_sweep") ? 0 : .21) - target.getBbHeight() / 2, 4)
                    : START.add(0, 1.22 - target.getBbHeight() / 2, .5);
        }
        if (c.boundary.equals("asymmetric_contact")) initialTarget = START.add(.28, 0, .7);
        caster.setYRot(c.yaw + (c.boundary.equals("commit_turn") ? 180 : 0)); caster.yBodyRot = caster.yHeadRot = caster.getYRot();
        for (var mob : new Mob[]{caster, target}) { mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024); mob.setHealth(mob.getMaxHealth()); }
        health = target.getHealth(); ticks = hits = 0; started = kick = false;
        attack = KineticAttacks.get(Constants.id(c.attack)).attack();
        Vec3 localVelocity = switch (c.motion) {
            case "cross_left" -> new Vec3(.035, 0, 0);
            case "cross_right" -> new Vec3(-.035, 0, 0);
            case "approach" -> new Vec3(0, 0, -.025);
            case "retreat" -> new Vec3(0, 0, .025);
            default -> Vec3.ZERO;
        };
        velocity = localVelocity.yRot((float) -Math.toRadians(c.yaw));
        if (c.boundary.equals("ally")) {
            owner = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND); owner.setPos(12, 300, 12); owner.setNoAi(true); level.addFreshEntity(owner);
            caster.setOwner(owner); ((DigimonEntity) target).setOwner(owner);
        }
        if (c.boundary.equals("world_border")) { level.getWorldBorder().setCenter(.5, .5); level.getWorldBorder().setSize(3); }
        if (c.boundary.equals("ledge_refusal")) for (int z = -3; z <= -1; z++) for (int x = -2; x <= 2; x++)
            level.setBlock(new BlockPos(x, 299, z), Blocks.AIR.defaultBlockState(), 3);
    }

    private static void wall(ServerLevel level, int z) {
        for (int x = -3; x <= 3; x++) for (int y = 300; y <= 304; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
    }

    private static void observe(ServerLevel level) {
        Case c = CASES.get(index); ticks++;
        if (target.getHealth() < health - .01) {
            hits++;
            if (c.attack.equals("jet_dash") && (!kick || caster.currentAttackTick() < 14 || caster.currentAttackTick() > 17))
                throw new AssertionError("Damage outside visible kick window " + c.id() + " tick=" + caster.currentAttackTick());
        }
        health = target.getHealth();
        caster.setTarget(null); caster.setLastHurtByMob(null); caster.getNavigation().stop();
        if (!started) {
            caster.setPos(START); caster.setDeltaMovement(Vec3.ZERO); caster.setOnGround(true);
            target.setPos(initialTarget); target.setDeltaMovement(Vec3.ZERO);
        }
        if (ticks == 10) {
            if (c.boundary.equals("muzzle_blocked")) wall(level, 2);
            if (c.attack.equals("hunting_cannon") && (c.boundary.equals("flight_sweep") || c.boundary.equals("empty_visual_corner"))) {
                level.addFreshEntity(new KineticProjectileEntity(level, caster, KineticAttacks.get(attack), START.add(0, 2, 0), new Vec3(0, 0, 1)));
                started = true;
            } else { caster.startAttack(attack, target); started = caster.isAttacking(); }
            if (c.boundary.equals("unreachable_path") && (started || com.digicube.entity.ai.DigimonCombatPosition.find(caster, target) != null)) throw new AssertionError("Unreachable endpoint accepted");
            if (c.boundary.equals("cooldown_fallback")) {
                if (!started) throw new AssertionError("fallback dash refused");
                caster.interruptAttack();
                if (caster.isAttackReady(attack)) throw new AssertionError("interruption erased cooldown");
                if (c.attack.equals("hunting_cannon")) {
                    initialTarget = START.add(0, 0, .75); target.setPos(initialTarget);
                    var fallback = caster.chooseAttack(target);
                    if (fallback == null || fallback.kind() != DigimonAttack.Kind.RETREAT_KICK) throw new AssertionError("Cannon cooldown did not expose retreat fallback");
                    caster.startAttack(fallback, target);
                } else if (caster.chooseAttack(target) != null) throw new AssertionError("cooling retreat bypassed its cooldown or minimum shot distance");
            }
        }
        int elapsed = ticks - 10;
        if (started && elapsed >= 0) {
            if (!target.isRemoved()) {
                Vec3 destination = initialTarget.add(velocity.scale(elapsed));
                if (c.boundary.equals("miss_after_commit") && elapsed >= 13) destination = destination.add(4, 0, 0);
                if (c.boundary.equals("cross_after_commit") && elapsed >= (c.attack.equals("jet_dash") ? 13 : 20)) destination = destination.add(6, 0, 0);
                if (c.boundary.equals("tracking_limit") && elapsed >= 20) destination = destination.add(6, 0, 0);
                target.setPos(destination); target.setDeltaMovement(velocity);
            }
            kick |= caster.currentAttackAnimation().equals("jet_dash_kick");
            if (elapsed == 8) {
                if (c.boundary.equals("target_lost")) target.discard();
                if (c.boundary.equals("interrupted_reset")) caster.interruptAttack();
                if (c.boundary.equals("cover_during_windup")) wall(level, c.attack.equals("jet_dash") ? -1 : 4);
                if (c.boundary.equals("invulnerability_retry")) {
                    target.hurtServer(level, caster.damageSources().mobAttack(caster), 100);
                    target.setHealth(health);
                    target.invulnerableTime = 100;
                }
            }
            if (elapsed == 4 && c.boundary.equals("obstacle_after_commit")) wall(level, -1);
            if (elapsed == 22 && c.boundary.equals("cover_during_flight")) wall(level, 5);
            if (elapsed == 40 && c.boundary.equals("invulnerability_retry")) {
                if (hits != 0) throw new AssertionError("rejected hurt counted as a successful hit");
                target.invulnerableTime = 0;
            }
            if (elapsed == 75 && c.boundary.equals("invulnerability_retry")) {
                caster.interruptAttack(); caster.setPos(START); caster.setOnGround(true); caster.startAttack(attack, target);
            }
        }
        if (ticks < (c.boundary.equals("invulnerability_retry") ? 150 : 80)) return;
        boolean expectHit = c.attack.equals("hunting_cannon") && (c.boundary.isEmpty() || List.of("near_target", "far_target", "invulnerability_retry", "flight_sweep", "cooldown_fallback").contains(c.boundary))
                || c.boundary.equals("connected_kick") || c.attack.equals("jet_dash") && c.elevation == 0 && c.motion.equals("still") && c.boundary.isEmpty();
        expectHit |= List.of("commit_turn", "repeat_frames", "wide_body_endpoint", "close_overlap", "asymmetric_contact", "limb_sweep").contains(c.boundary);
        boolean expectNoHit = List.of("ally", "target_lost", "interrupted_reset", "cover_during_windup", "cover_during_flight", "world_border", "muzzle_blocked", "clear_escape", "miss_after_commit", "obstacle_after_commit", "ledge_refusal", "cooldown_fallback", "tracking_limit").contains(c.boundary);
        if (c.boundary.equals("invulnerability_retry")) expectHit = true;
        expectNoHit |= List.of("cross_after_commit", "unreachable_path", "empty_visual_corner", "outside_limb_reach").contains(c.boundary);
        if (c.boundary.equals("cooldown_fallback") && c.attack.equals("hunting_cannon")) expectNoHit = false;
        boolean pass = (!expectHit || hits >= 1) && (!expectNoHit || hits == 0) && hits <= 1;
        if (c.attack.equals("jet_dash") && started && c.boundary.isEmpty()) pass &= caster.position().distanceTo(START) > 1.4;
        if (pass) passed++; else failed++;
        Constants.LOG.info("[kinetic-case] {} {} started={} hits={} kick={} end={} target={}", pass ? "PASS" : "FAIL", c.id(), started, hits, kick, caster.position(), target.position());
        next(level);
    }

    private static void finish(ServerLevel level, boolean pass) {
        done = true; level.getWorldBorder().setSize(59_999_968);
        Constants.LOG.info("[scenario] {} centalmon_checks cases={} passed={} failed={}", pass ? "PASS" : "FAIL", index, passed, failed);
        level.getServer().halt(false);
    }
}
