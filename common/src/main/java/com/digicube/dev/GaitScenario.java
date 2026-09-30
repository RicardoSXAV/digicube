package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * {@code DIGICUBE_SCENARIO=gait_checks[:<species>]} (Meramon by default): does the species' real ground pace fit the
 * strides its clips were authored on? A wild one walks the length of the flat arena with vanilla navigation at the
 * paces the AI uses (the walk modifier for wandering and following, the run modifier for following a sprinting tamer,
 * the wild panic modifier), and each pace is measured over the straight middle of the course. The client plays the
 * gait at travel / stride; a pace the phase cannot follow within {@code max_playback_rate} would slide the feet, and a
 * walk far short of the authored stride would shuffle. Logs {@code [gait] PASS|FAIL} per pace and a verdict.
 */
final class GaitScenario {
    private record Pace(String name, double modifier) {}
    private static final AABB ARENA = new AABB(-16, 294, -16, 16, 312, 16);
    private static final int SETTLE = 30, MEASURE = 50;
    private static DigimonSpecies species;
    private static List<Pace> paces;
    private static int index = -1, ticks, failed;
    private static boolean initialized, done;
    private static DigimonEntity walker;
    private static Vec3 from, last;
    /** Summed angle between the body's facing and its travel over the measured ticks (a crab's side-on scuttle). */
    private static double offTravel;
    private static int offSamples;

    private GaitScenario() {}

    static void tick(ServerLevel level, String name) {
        if (done) return;
        try {
            if (!initialized) {
                initialized = true;
                species = DigimonSpeciesRegistry.getOrThrow(Constants.id(name.isEmpty() ? "meramon" : name));
                var locomotion = species.locomotion();
                if (locomotion.groundGait() == null) throw new IllegalStateException(species.id() + " has no ground gait");
                paces = List.of(new Pace("walk", locomotion.walkSpeed()), new Pace("panic", 1.4), new Pace("run", locomotion.runSpeed()));
                for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) level.setChunkForced(x, z, true);
                CombatScenario.build(level, "flat");
                level.getServer().tickRateManager().requestGameToSprint(30000);
                next(level);
            } else observe(level);
        } catch (RuntimeException e) {
            Constants.LOG.error("[gait] aborted", e);
            failed++; finish(level);
        }
    }

    private static void next(ServerLevel level) {
        level.getEntities((Entity) null, ARENA).forEach(Entity::discard);
        if (++index == paces.size()) { finish(level); return; }
        ticks = 0; from = last = null; offTravel = 0; offSamples = 0;
        walker = DigimonEntity.spawnWild(level, species, 20, new Vec3(.5, CombatScenario.FLOOR_Y, -11.5));
        walker.setYRot(0); walker.yBodyRot = walker.yHeadRot = 0;
    }

    private static void observe(ServerLevel level) {
        var pace = paces.get(index); ticks++;
        // Keep the course: a stroll the wild AI starts is replaced at once.
        var nav = walker.getNavigation(); var goal = new Vec3(.5, CombatScenario.FLOOR_Y, 12.5);
        if (nav.isDone() || nav.getTargetPos() == null || nav.getTargetPos().getZ() != 12 || ticks % 5 == 0)
            nav.moveTo(goal.x, goal.y, goal.z, pace.modifier());
        if (ticks == SETTLE) from = walker.position();
        if (ticks > SETTLE && last != null) {
            Vec3 step = walker.position().subtract(last);
            if (step.horizontalDistanceSqr() > 1.0E-6) {
                float travel = (float) (Math.atan2(step.z, step.x) * 180 / Math.PI) - 90;
                offTravel += Math.abs(net.minecraft.util.Mth.wrapDegrees(walker.getYRot() - travel)); offSamples++;
            }
        }
        last = walker.position();
        if (ticks < SETTLE + MEASURE && walker.getZ() < 10) return;
        var gait = species.locomotion().groundGait(); float scale = species.body().modelScale();
        double speed = walker.position().subtract(from).horizontalDistance() / (ticks - SETTLE);
        // the run the client plays at this steady pace (a gait that changes all at once is all run or none)
        float amount = (float) Math.min(1, speed / gait.fullSpeed(scale)), run = gait.drive(speed, 0, scale, false).run();
        float playback = gait.advance(speed, amount, scale, run);
        // Uncapped cadence the travel asks for: above the cap the feet would slide.
        double wanted = speed * gait.cycleTicks() / ((gait.stride() + (gait.runStride() - gait.stride()) * run) * scale * Math.max(.001F, amount));
        boolean planted = wanted <= gait.maxPlaybackRate() + 1.0E-6;
        boolean fits = switch (pace.name()) {
            case "walk" -> amount >= .85;         // the walk plays its full stride, not a shuffle
            // The run clip carries the run (if it has one). A species whose AI runs at its walking modifier (DarkTyrannomon)
            // keeps its run clip for a rider's sprint and a panic: its AI run is a walk and must play one.
            case "run" -> run >= .8 || gait.runStride() == gait.stride()
                    || species.locomotion().runSpeed() <= species.locomotion().walkSpeed() && amount >= .85;
            default -> true;
        };
        boolean pass = planted && fits && ticks - SETTLE >= 20;
        if (!pass) failed++;
        Constants.LOG.info(String.format(java.util.Locale.ROOT,
                "[gait] %s %s %s modifier=%.3f pace=%.4f blocks/tick (walk clip %.4f, run clip %.4f) amount=%.2f run=%.2f cadence=%.2f (cap %.2f) facing %.0f deg off travel over %d ticks",
                pass ? "PASS" : "FAIL", species.id().getPath(), pace.name(), pace.modifier(), speed, gait.fullSpeed(scale), gait.runSpeed(scale),
                amount, run, wanted, gait.maxPlaybackRate(), offSamples == 0 ? 0 : offTravel / offSamples, ticks - SETTLE));
        next(level);
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[scenario] {} gait_checks {} {} of {} paces", failed == 0 ? "PASS" : "FAIL",
                species == null ? "?" : species.id().getPath(), paces == null ? 0 : paces.size() - failed, paces == null ? 0 : paces.size());
        level.getServer().halt(false);
    }
}
