package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import com.digicube.registry.DCEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Headless combat rehearsal on a development server, for iterating on combat AI without a player.
 *
 * <p>Set {@code DIGICUBE_SCENARIO=<caster>_vs_<prey>[@flat|@steps|@ledge|@down|@wall|@water]} (for example
 * {@code seadramon_vs_golemon@steps}) and start the dedicated server. A platform is built high
 * above the world, both Digimon are spawned facing each other eight blocks apart, healed every
 * tick so the fight never ends early, and the log records each phase of the caster's freeze-and-
 * wrap loop. The server halts with a {@code [scenario] PASS} or {@code FAIL} verdict.
 * {@code ledge} raises the prey's half one block; {@code down} raises the caster's half.
 * {@code wall} requires zero damage through solid cover.
 *
 * <p>{@code DIGICUBE_SCENARIO_ATTACK=<attack>} isolates one caster move.
 * {@code DIGICUBE_BENCHMARK=true} measures 600 combat ticks instead of stopping after three hits;
 * {@code DIGICUBE_NEUTRAL=true} removes attribute advantage in that test process only.
 */
public final class CombatScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int FLOOR_Y = 300, HALF = 14, TIMEOUT_TICKS = 20 * 90, SETTLE_TICKS = 40, REQUIRED_HITS = 3;
    private static final boolean BENCHMARK=Boolean.parseBoolean(System.getenv("DIGICUBE_BENCHMARK"));
    private static final String MOVE=System.getenv("DIGICUBE_SCENARIO_ATTACK");
    private static double damage;
    private static final java.util.Map<String,Integer> moves=new java.util.TreeMap<>();
    private static String lastMove;
    private static boolean blockedScenario;
    private static boolean started, done;
    private static int startTick, freezeTick = -1, captureTick = -1, releaseTick = -1, firstHitTick = -1, casts, hits;
    private static boolean casterWasAttacking, wrapCaster, oversizedWrapPrey, duel, preyFacesAway;
    private static DigimonEntity caster, prey;

    private CombatScenario() {}

    /** Per-dimension server tick hook; inert unless the environment variable names a scenario. */
    public static void tick(ServerLevel level) {
        if (NAME == null || done || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        if (NAME.equals("centalmon_checks")) { KineticScenario.tick(level); return; }
        try {
            if (!started) start(level);
            else observe(level);
        } catch (RuntimeException e) {
            Constants.LOG.error("[scenario] aborted", e);
            finish(level, "FAIL " + e);
        }
    }

    private static void start(ServerLevel level) {
        started = true;
        String spec = NAME.toLowerCase(Locale.ROOT);
        boolean behind = false;
        for (boolean again = true; again; ) {
            again = false;
            if (spec.endsWith("+duel")) { duel = true; spec = spec.substring(0, spec.length() - 5); again = true; }
            if (spec.endsWith("+behind")) { behind = true; spec = spec.substring(0, spec.length() - 7); again = true; }
        }
        preyFacesAway = behind;
        String[] parts = spec.split("@", 2);
        String[] names = parts[0].split("_vs_", 2);
        String terrain = parts.length > 1 ? parts[1] : "flat";
        blockedScenario=terrain.equals("wall");
        if (names.length != 2) { finish(level, "FAIL bad scenario name " + NAME); return; }
        for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++) level.setChunkForced(cx, cz, true);
        // The dev world persists between runs: evict every earlier fighter and anything else that wandered in.
        int evicted = com.digicube.spawn.WildSpawner.clear(level) + purge(level);
        if (evicted > 0) Constants.LOG.info("[scenario] evicted {} leftover mobs from the arena", evicted);
        build(level, terrain);
        DigimonSpecies casterSpecies = DigimonSpeciesRegistry.getOrThrow(Constants.id(names[0]));
        DigimonSpecies preySpecies = DigimonSpeciesRegistry.getOrThrow(Constants.id(names[1]));
        if(MOVE!=null && !MOVE.isBlank()) {
            var selected=casterSpecies.attacks().stream().filter(a->a.id().getPath().equals(MOVE)).toList();
            if(selected.isEmpty())throw new IllegalArgumentException("Unknown scenario attack "+MOVE);
            casterSpecies=new DigimonSpecies(casterSpecies.id(),casterSpecies.stage(),casterSpecies.attribute(),casterSpecies.baseHealth(),
                    casterSpecies.baseAttack(),casterSpecies.baseDefence(),casterSpecies.baseSpeed(),casterSpecies.evolutions(),selected,
                    casterSpecies.body(),casterSpecies.locomotion());
            DigimonSpeciesRegistry.replace(casterSpecies);
        }
        if(Boolean.parseBoolean(System.getenv("DIGICUBE_NEUTRAL"))) {
            for(var species:new DigimonSpecies[]{casterSpecies,preySpecies}) {
                DigimonSpeciesRegistry.replace(new DigimonSpecies(species.id(),species.stage(),com.digicube.digimon.DigimonAttribute.FREE,
                        species.baseHealth(),species.baseAttack(),species.baseDefence(),species.baseSpeed(),species.evolutions(),species.attacks(),species.body(),species.locomotion()));
            }
            casterSpecies=DigimonSpeciesRegistry.getOrThrow(casterSpecies.id());preySpecies=DigimonSpeciesRegistry.getOrThrow(preySpecies.id());
        }
        // A wrap caster is judged on freeze, capture and release; anyone else on landing hits.
        wrapCaster = casterSpecies.attacks().stream().anyMatch(a -> a.kind() == com.digicube.digimon.DigimonAttack.Kind.CONSTRICTION);
        double y = terrain.equals("water") ? FLOOR_Y - 3 : FLOOR_Y;
        caster = DigimonEntity.spawnWild(level, casterSpecies, 20, new Vec3(.5, terrain.equals("down") ? y+1:y, -3.5));
        prey = DigimonEntity.spawnWild(level, preySpecies, 20, new Vec3(.5, terrain.equals("ledge") ? y + 1 : y, 4.5));
        if (caster == null || prey == null) { finish(level, "FAIL could not spawn"); return; }
        if (wrapCaster && caster.constrictionMotion().fit(prey.getBoundingBox(), caster.getBody().modelScale()) == null) {
            oversizedWrapPrey = true;
            wrapCaster = false;
            Constants.LOG.info("[scenario] prey exceeds authored wrap fit; require landed fallback attacks and no capture");
        }
        // Facing each other by default; +behind turns the prey's back (and any long body) toward the caster.
        caster.setYRot(0); prey.setYRot(preyFacesAway ? 0 : 180); prey.yBodyRot = prey.yHeadRot = prey.getYRot();
        // Both stay valid, damageable targets that no single blow can kill. Never mark either invulnerable:
        // an invulnerable mob is not a legal target, and its opponent wanders off instead of fighting.
        for (DigimonEntity fighter : new DigimonEntity[]{caster, prey}) {
            var health = fighter.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (health != null) health.setBaseValue(10_000);
            fighter.setHealth(fighter.getMaxHealth());
        }
        startTick = level.getServer().getTickCount();
        if ("true".equals(System.getenv("DIGICUBE_SCENARIO_SPRINT"))) level.getServer().tickRateManager().requestGameToSprint(TIMEOUT_TICKS + 100);
        Constants.LOG.info("[scenario] {} vs {} on {} terrain: start at tick {}", names[0], names[1], terrain, startTick);
    }

    /** A stone platform far above the world, with optional one-block steps or a pool. */
    private static void build(ServerLevel level, String terrain) {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        boolean pool = terrain.equals("water");
        for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++) {
            for (int dy = -6; dy <= 8; dy++) level.setBlock(new BlockPos(x, FLOOR_Y + dy, z), air, 3);
            level.setBlock(new BlockPos(x, FLOOR_Y - (pool ? 6 : 1), z), stone, 3);
            // A rim keeps a strolling or knocked-back fighter on the platform.
            if (Math.abs(x) == HALF || Math.abs(z) == HALF) for (int dy = 0; dy <= 2; dy++) level.setBlock(new BlockPos(x, FLOOR_Y + dy, z), stone, 3);
            if (pool) {
                boolean rim = Math.abs(x) == HALF || Math.abs(z) == HALF;
                for (int dy = -5; dy <= -1; dy++) level.setBlock(new BlockPos(x, FLOOR_Y + dy, z), rim ? stone : water, 3);
            } else if(terrain.equals("wall")) {
                if(z==0)for(int dy=0;dy<=8;dy++)level.setBlock(new BlockPos(x,FLOOR_Y+dy,z),stone,3);
            } else if(terrain.equals("down")) {
                if(z<0)level.setBlock(new BlockPos(x,FLOOR_Y,z),stone,3);
            } else if (terrain.equals("ledge")) {
                // The prey's whole half stands one block higher: the caster must climb before anything else.
                if (z >= 0) level.setBlock(new BlockPos(x, FLOOR_Y, z), stone, 3);
            } else if (terrain.equals("steps")) {
                // A ledge one block up beyond the prey, a step down behind the caster, and scattered single blocks.
                if (z >= 8) level.setBlock(new BlockPos(x, FLOOR_Y, z), stone, 3);
                if (z <= -8) level.setBlock(new BlockPos(x, FLOOR_Y - 1, z), air, 3);
                if (z <= -8) level.setBlock(new BlockPos(x, FLOOR_Y - 2, z), stone, 3);
                if ((x == 3 || x == -4) && (z == 1 || z == 6)) level.setBlock(new BlockPos(x, FLOOR_Y, z), stone, 3);
            }
        }
    }

    private static void observe(ServerLevel level) {
        int elapsed = level.getServer().getTickCount() - startTick;
        if (caster.isRemoved() || prey.isRemoved()) {
            DigimonEntity gone = caster.isRemoved() ? caster : prey;
            finish(level, String.format("FAIL %s vanished at t=%d: reason=%s alive=%s health=%.1f pos=%s",
                    caster.isRemoved() ? "caster" : "prey", elapsed, gone.getRemovalReason(), gone.isAlive(), gone.getHealth(), gone.position()));
            return;
        }
        // Read the damage of this tick before healing it away; the species may reset its own max health,
        // so the boost is reasserted every tick rather than trusted from spawn.
        boolean preyHurt = prey.getHealth() < prey.getMaxHealth();
        if(elapsed>=SETTLE_TICKS)damage+=prey.getMaxHealth()-prey.getHealth();
        if (oversizedWrapPrey && prey.hasEffect(DCEffects.CONSTRICTED)) { finish(level, "FAIL oversized prey was captured"); return; }
        for (DigimonEntity fighter : new DigimonEntity[]{caster, prey}) {
            var health = fighter.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (health != null && health.getBaseValue() < 1024) health.setBaseValue(1024);
            fighter.setHealth(fighter.getMaxHealth());
        }
        if (elapsed == SETTLE_TICKS) {
            caster.setTarget(prey);
            if (duel) prey.setTarget(caster);
            Constants.LOG.info("[scenario] targets set ({}), caster at {} prey at {}", duel ? "duel" : "passive prey", caster.position(), prey.position());
        }
        if (elapsed < SETTLE_TICKS) {
            // Nobody strolls off during the settle; the fight starts from the authored positions.
            caster.getNavigation().stop();
            prey.getNavigation().stop();
            return;
        }
        if (elapsed % 40 == 0) purge(level);
        if (!duel) {
            // A passive prey measures the caster alone; being hit would otherwise make it retaliate.
            prey.setLastHurtByMob(null);
            prey.setTarget(null);
            prey.interruptAttack();
            prey.getNavigation().stop();
            if (preyFacesAway) {
                // Hold the pose so the caster keeps meeting the back of the prey, not its face.
                prey.setYRot(0); prey.yBodyRot = prey.yHeadRot = 0;
            }
        }
        boolean attacking = caster.isAttacking();
        String current=caster.getActiveAttack()==null?null:caster.getActiveAttack().id().getPath();
        if (attacking && (!casterWasAttacking || !java.util.Objects.equals(current,lastMove))) {
            casts++;moves.merge(current,1,Integer::sum);Constants.LOG.info("[scenario] t={} caster starts {}",elapsed,current);
        }
        lastMove=current;
        casterWasAttacking = attacking;
        // Both are healed at the end of every tick, so any missing health is a hit landed this tick.
        if (preyHurt) {
            if (hits++ == 0) { firstHitTick = elapsed; Constants.LOG.info("[scenario] t={} first hit on the prey", elapsed); }
            if (!blockedScenario && !BENCHMARK && !wrapCaster && hits >= REQUIRED_HITS) {
                finish(level, String.format("PASS firstHit=%d hits=%d by t=%d casts=%d", firstHitTick, hits, elapsed, casts));
                return;
            }
        }
        if(blockedScenario && elapsed>=240) {
            finish(level,(hits==0?"PASS":"FAIL")+" solid cover hits="+hits+" casts="+casts);return;
        }
        if(BENCHMARK && elapsed>=640) {
            float triangle=caster.getSpecies().orElseThrow().attribute().damageMultiplierAgainst(prey.getSpecies().orElseThrow().attribute());
            finish(level,String.format(java.util.Locale.ROOT,"%s benchmark firstHit=%d hits=%d damage=%.2f neutralEquivalent=%.2f duration=600 moves=%s",
                    hits>=REQUIRED_HITS?"PASS":"FAIL",firstHitTick,hits,damage,damage/triangle,moves));return;
        }
        if (!wrapCaster) {
            if (elapsed >= TIMEOUT_TICKS) finish(level, String.format("FAIL only %d hits within %d ticks (casts=%d caster=%s prey=%s)",
                    hits, TIMEOUT_TICKS, casts, caster.position(), prey.position()));
            return;
        }
        if (freezeTick < 0 && prey.hasEffect(DCEffects.FROZEN)) {
            freezeTick = elapsed;
            Constants.LOG.info("[scenario] t={} prey frozen", elapsed);
        }
        if (captureTick < 0 && prey.hasEffect(DCEffects.CONSTRICTED)) {
            captureTick = elapsed;
            Constants.LOG.info("[scenario] t={} prey captured ({} ticks after the freeze)", elapsed, freezeTick < 0 ? -1 : elapsed - freezeTick);
        }
        if (captureTick >= 0 && releaseTick < 0 && !prey.hasEffect(DCEffects.CONSTRICTED)) {
            releaseTick = elapsed;
            finish(level, String.format("PASS freeze=%d capture=%d (%d after freeze) release=%d casts=%d",
                    freezeTick, captureTick, captureTick - Math.max(freezeTick, 0), releaseTick, casts));
        } else if (elapsed >= TIMEOUT_TICKS) {
            finish(level, String.format("FAIL no capture within %d ticks (freeze=%d casts=%d caster=%s prey=%s)",
                    TIMEOUT_TICKS, freezeTick, casts, caster.position(), prey.position()));
        }
    }

    /** Remove every mob in the arena other than the two fighters; natural spawns keep arriving at night. */
    private static int purge(ServerLevel level) {
        var arena = new net.minecraft.world.phys.AABB(-HALF - 2, FLOOR_Y - 8, -HALF - 2, HALF + 2, FLOOR_Y + 12, HALF + 2);
        var intruders = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Mob.class), arena,
                mob -> mob != caster && mob != prey);
        intruders.forEach(net.minecraft.world.entity.Entity::discard);
        return intruders.size();
    }

    private static void finish(ServerLevel level, String verdict) {
        done = true;
        Constants.LOG.info("[scenario] {}", verdict);
        level.getServer().halt(false);
    }
}
