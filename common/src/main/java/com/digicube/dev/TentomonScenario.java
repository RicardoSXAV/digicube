package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.ai.FlightPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
 * Tentomon's two moves and his fighting on the wing, on the real server ({@code DIGICUBE_SCENARIO=tentomon_checks}).
 *
 * <p>Twice Arm on the ground: both claws cut a foe standing in reach, at every heading (each claw strikes once), nothing
 * out of reach, never an ally. Petit Thunder is refused on the ground (it is cast only on the wing). On the wing Twice
 * Arm cuts a foe standing level with the hovering body, and a foe on the ground from a body hovering at the sortie's
 * strike height. A fight on the wing: wild, with prey six blocks off, Tentomon takes off, casts Petit Thunder only once
 * airborne, swoops in low for Twice Arm, and round again (two of each), strikes the prey with both, never comes down
 * while it has prey, and lands once the prey is gone.
 * Every case logs its own line; the run ends with {@code [scenario] PASS|FAIL tentomon_checks}.
 */
final class TentomonScenario {
    private record Case(String name, String kind, int yaw, double ahead, double rise, String role, int want) {}

    /** The sortie's strike height (locomotion.flight.sortie.strike): the hover a swoop cuts a foe on the ground from. */
    private static final double STRIKE = .35;
    private static final List<Case> CASES = new ArrayList<>();
    static {
        for (int yaw = 0; yaw < 360; yaw += 45) CASES.add(new Case("claws at agumon", "ground", yaw, 1.45, 0, "target", 2));
        CASES.add(new Case("claws out of reach", "ground", 0, 3.4, 0, "target", 0));
        CASES.add(new Case("claws beside an ally", "ground", 90, 1.45, 0, "ally", 0));
        CASES.add(new Case("thunder refused on the ground", "refused", 0, 6, 0, "target", 0));
        for (int yaw = 0; yaw < 360; yaw += 90) CASES.add(new Case("claws on the wing", "wing", yaw, 1.45, 2.5, "target", 2));
        for (int yaw = 45; yaw < 360; yaw += 90) CASES.add(new Case("claws swooping low", "low", yaw, 1.45, STRIKE, "target", 2));
        for (int yaw = 0; yaw < 360; yaw += 120) CASES.add(new Case("fight on the wing", "sortie", yaw, 6, 0, "target", 6));
    }

    private static final Vec3 ORIGIN = new Vec3(.5, 300, .5);
    private static final AABB ARENA = new AABB(-24, 294, -24, 25, 320, 25);
    private static int index = -1, ticks, passed, failed;
    private static boolean initialized, done;
    private static DigimonEntity caster;
    private static LivingEntity body;
    private static float health;
    private static int hits, maxTicks;
    /**
     * A fight on the wing: whether it left the ground, cast Petit Thunder on the ground or on the wing, swooped in low for
     * Twice Arm, came down while it still had prey, and landed again once the prey was gone (the tick it was let go).
     */
    private static boolean tookOff, castOnGround, castOnWing, swooped, cameDown, landed;
    /** Petit Thunders cast on the wing and swoops for Twice Arm, the tick the prey was let go, and the lowest and highest the body flew. */
    private static int thunders, swoops, released;
    private static double lowest, highest;
    private static boolean refused;
    /** The moves a sortie case cast, each with the flight phase it began in. */
    private static final List<String> casts = new ArrayList<>();
    private static DigimonAttack lastCast;

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
            Constants.LOG.error("[tentomon-case] aborted {}", index, e);
            finish(level);
        }
    }

    private static void build(ServerLevel level, Case c) {
        for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++) for (int y = 298; y <= 318; y++) {
            boolean rim = Math.abs(x) == 22 || Math.abs(z) == 22;
            level.setBlock(new BlockPos(x, y, z), (y < 300 || rim && y < 303 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
        }
        if (c.kind.equals("wing")) {
            // the foe stands on a pillar as high as the body hovers
            Vec3 p = at(c, c.ahead);
            for (int y = 300; y < 300 + (int) c.rise; y++) level.setBlock(BlockPos.containing(p.x, y, p.z), Blocks.STONE.defaultBlockState(), 3);
        }
    }

    private static Vec3 at(Case c, double ahead) {
        return AttackGeometry.world(ORIGIN, new Vec3(0, 0, ahead), c.yaw);
    }

    private static void next(ServerLevel level) {
        level.getEntities((Entity) null, ARENA).forEach(Entity::discard);
        if (++index == CASES.size()) { finish(level); return; }
        var c = CASES.get(index);
        build(level, c);
        ticks = 0; hits = 0; released = thunders = swoops = 0; lowest = Double.MAX_VALUE; highest = -Double.MAX_VALUE; refused = tookOff = castOnGround = castOnWing = swooped = cameDown = landed = false;
        casts.clear(); lastCast = null;
        caster = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("tentomon")), 20,
                ORIGIN.add(0, c.kind.equals("wing") ? (int) c.rise : c.kind.equals("low") ? c.rise : 0, 0));
        prepare(caster);
        caster.setYRot(c.yaw); caster.yBodyRot = caster.yHeadRot = c.yaw;
        Vec3 p = at(c, c.ahead).add(0, c.kind.equals("wing") ? (int) c.rise : 0, 0);
        var foe = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, p);
        foe.setNoAi(true); prepare(foe);
        if (c.role.equals("ally")) { caster.joinBattleSide(1); foe.joinBattleSide(1); } else foe.joinBattleSide(2);
        foe.setYRot(c.yaw + 180);
        body = foe; health = foe.getHealth();
        maxTicks = c.kind.equals("sortie") ? 700 : 60;
    }

    private static void prepare(Mob mob) {
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
        mob.setHealth(mob.getMaxHealth());
        mob.setPersistenceRequired();
    }

    private static DigimonAttack move(String name) {
        return caster.getSpecies().orElseThrow().attacks().stream().filter(a -> a.id().getPath().equals(name)).findFirst().orElseThrow();
    }

    private static void observe(ServerLevel level) {
        var c = CASES.get(index);
        ticks++;
        if (body.getHealth() < health - .01F) hits++;
        health = body.getHealth();
        body.setDeltaMovement(body.getDeltaMovement().multiply(0, 1, 0));
        switch (c.kind) {
            case "ground", "refused" -> {
                // the caster keeps its AI (its attack timeline runs on it) but goes nowhere and fights nothing of its own
                caster.setTarget(null); caster.setLastHurtByMob(null);
                caster.getNavigation().stop();
                if (ticks < 10) { caster.setDeltaMovement(caster.getDeltaMovement().multiply(0, 1, 0)); caster.setYRot(c.yaw); caster.yBodyRot = caster.yHeadRot = c.yaw; }
                if (ticks == 10) {
                    caster.readyAttacks();
                    caster.startAttack(move(c.kind.equals("refused") ? "petit_thunder" : "twice_arm"), body);
                    refused = !caster.isAttacking();
                }
                if (ticks >= 10 + 22 + 10) verdict(level, c);
            }
            case "wing", "low" -> {
                // held aloft as a flight holds it: flying, no gravity, still
                caster.setTarget(null); caster.setLastHurtByMob(null);
                caster.getNavigation().stop();
                caster.setNoGravity(true);
                caster.setFlightPhase(FlightPhase.FLYING);
                caster.setDeltaMovement(Vec3.ZERO);
                if (ticks < 10) { caster.setYRot(c.yaw); caster.yBodyRot = caster.yHeadRot = c.yaw; }
                if (ticks == 10) {
                    caster.readyAttacks();
                    caster.startAttack(move("twice_arm"), body);
                    refused = !caster.isAttacking();
                }
                if (ticks >= 10 + 22 + 10) verdict(level, c);
            }
            default -> {
                // the AI on its own: prey it fights, everything ready
                if (ticks == 2) { caster.readyAttacks(); caster.setTarget(body); }
                if (ticks > 2 && (caster.getTarget() == null || !caster.getTarget().isAlive())) caster.setTarget(body);
                var phase = caster.getFlightPhase();
                if (phase != FlightPhase.GROUNDED) tookOff = true;
                var active = caster.isAttacking() ? caster.getActiveAttack() : null;
                if (active != null && active != lastCast) casts.add(active.id().getPath() + "@" + phase + "#" + ticks);
                boolean began = active != null && active != lastCast;
                if (began && active.id().getPath().equals("petit_thunder")) {
                    if (phase == FlightPhase.GROUNDED) castOnGround = true; else { castOnWing = true; thunders++; }
                }
                // Twice Arm cut from a hover low over the ground: a swoop
                if (began && active.id().getPath().equals("twice_arm") && phase == FlightPhase.FLYING
                        && caster.getY() - ORIGIN.y < 1) { swooped = true; swoops++; }
                if (phase == FlightPhase.FLYING && released == 0) {
                    lowest = Math.min(lowest, caster.getY() - ORIGIN.y);
                    highest = Math.max(highest, caster.getY() - ORIGIN.y);
                }
                if (tookOff && released == 0 && phase != FlightPhase.TAKEOFF && phase != FlightPhase.FLYING) cameDown = true;
                lastCast = active;
                // twice round both moves, the fight is over: the prey stands down and the body comes back to the ground
                if (released == 0 && thunders >= 2 && swoops >= 2 && hits >= c.want && !caster.isAttacking()) released = ticks;
                if (released > 0) { caster.setTarget(null); caster.setLastHurtByMob(null); }
                if (released > 0 && phase == FlightPhase.GROUNDED && caster.onGround()) landed = true;
                if (landed || ticks >= maxTicks) verdict(level, c);
            }
        }
    }

    private static void verdict(ServerLevel level, Case c) {
        List<String> wrong = new ArrayList<>();
        switch (c.kind) {
            case "refused" -> { if (!refused) wrong.add("Petit Thunder started on the ground"); }
            case "sortie" -> {
                if (!tookOff) wrong.add("never took off");
                if (castOnGround) wrong.add("cast Petit Thunder on the ground");
                if (!castOnWing) wrong.add("never cast Petit Thunder on the wing");
                if (!swooped) wrong.add("never swooped in low for Twice Arm");
                if (thunders < 2 || swoops < 2) wrong.add("went round " + thunders + " Petit Thunders and " + swoops + " swoops, wanted two of each");
                if (hits < c.want) wrong.add("the prey was struck " + hits + " times, wanted " + c.want);
                if (cameDown) wrong.add("came down while it still had prey");
                if (!landed) wrong.add("never came back down once the prey was gone");
            }
            default -> {
                if (c.want > 0 && refused) wrong.add("the move was refused from where it stood");
                if (hits != c.want) wrong.add("struck " + hits + " times, wanted " + c.want);
            }
        }
        boolean pass = wrong.isEmpty();
        if (!pass && (c.kind.equals("ground") || c.kind.equals("wing") || c.kind.equals("low"))) explain(level, c);
        if (pass) passed++; else failed++;
        Constants.LOG.info("[tentomon-case] {} {} yaw={} hits={} ticks={}{}{}{}", pass ? "PASS" : "FAIL", c.name, c.yaw, hits, ticks,
                released > 0 ? String.format(" released=%d flew %.2f..%.2f", released, lowest, highest) : "",
                casts.isEmpty() ? "" : " casts=" + casts, wrong.isEmpty() ? "" : " " + String.join("; ", wrong));
        next(level);
    }

    /** A failed cut: where the claws' volumes were against the foe's box through the cut, and whether the reach rehearsal saw it. */
    private static void explain(ServerLevel level, Case c) {
        var attack = move("twice_arm");
        var d = com.digicube.digimon.AuthoredAttacks.get(attack);
        Constants.LOG.info("[tentomon-case]   foe box {} caster at {} yaw {} canReach {}", body.getBoundingBox(), caster.position(), caster.getYRot(),
                com.digicube.entity.AuthoredVolumeAttack.canReach(caster, attack, caster.position(), body));
        for (double t = 5.5; t <= 13; t += 1.5) {
            var boxes = d.sample(t);
            for (int i = 0; i < boxes.length; i++) if (boxes[i] != null) {
                var w = boxes[i].world(caster.position(), caster.getYRot(), 0);
                Constants.LOG.info("[tentomon-case]   t={} claw {} bounds {} hits {} visible {}", t, i, w.bounds(), w.intersects(body.getBoundingBox()),
                        com.digicube.entity.AuthoredVolumeAttack.visible(level, caster, attack, t, caster.position(), caster.getYRot(), w));
            }
        }
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[scenario] {} tentomon_checks passed={} failed={} total={}",
                failed == 0 && passed == CASES.size() ? "PASS" : "FAIL", passed, failed, CASES.size());
        level.getServer().halt(false);
    }
}
