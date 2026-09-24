package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.dev.CombatScenario;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.RiderAttack;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless check of mounted combat: {@code DIGICUBE_SCENARIO=rider_checks} seats a fake player on every mount whose
 * sheet lists rider attacks and casts each slot at a dummy that stands still. No client turns or moves the mount
 * here, so this proves the server side only: a rider's cast starts without a target, aims by the soft target or the
 * view, and lands damage. Last, a mount walks down a hillside of one-block steps: it must keep its feet on every step
 * (no tick in the air), as it does going up. The verdict line starts with {@code [rider] RESULT}.
 */
public final class RiderScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int TIMEOUT = 20 * 8, STREAM_HOLD = 45;

    /**
     * {@code deep}: staged in the pool, the prey swimming {@link #DEEP} blocks under the mount's level; a hold glides down to it.
     * {@code air}: a charge fired mid-leap, {@link #AIR_HEIGHT} blocks up with nothing in reach; it must carry the body far.
     */
    private record Case(DigimonSpecies species, int slot, boolean deep, boolean air) {}
    private static final double AIR_HEIGHT = 3, AIR_CARRY = 6;
    private static Vec3 airStart;
    private static final double DEEP = 1.5;
    /** Gaps between the two bodies to try, nearest first: a strike's reach without its lunge is not written anywhere. */
    private static final double[] NEAR = {.6, 1.4, 2.4, 3.4}, FAR = {6}, LINE = {4.5, 3.0}, GRAB = {5}, CHARGE = {5, 3};
    private static int attempt;

    private static List<Case> cases;
    private static int index = -1, caseTick;
    private static DigimonEntity mount, dummy, bystander;
    private static float bystanderBefore;
    /** The prey's velocity the tick the charge's buck struck it: a kick shoves (at most 1.5 up, 4.5 away), it never throws (was 7.6 and 22). */
    private static Vec3 thrown;
    private static ServerPlayer rider;
    private static float healthBefore;
    private static boolean started, released, done;
    private static final List<String> failures = new ArrayList<>();

    private RiderScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"rider_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (cases == null) {
                cases = new ArrayList<>();
                for (DigimonSpecies species : DigimonSpeciesRegistry.all()) {
                    int slots = species.body().mount().map(m -> m.riderAttacks().size()).orElse(0);
                    for (int slot = 0; slot < slots; slot++) {
                        cases.add(new Case(species, slot, false, false));
                        var aim = species.body().mount().orElseThrow().riderAttacks().get(slot).aim();
                        if (aim == RiderAttack.Aim.GRAB) cases.add(new Case(species, slot, true, false));
                        if (aim == RiderAttack.Aim.CHARGE) cases.add(new Case(species, slot, false, true));
                    }
                }
                for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++) level.setChunkForced(cx, cz, true);
                level.getServer().tickRateManager().requestGameToSprint(cases.size() * (TIMEOUT + 60) * NEAR.length);
                next(level);
            } else if (walker != null) { if (observeDescent(level)) finish(level); }
            else observe(level);
        } catch (RuntimeException e) {
            Constants.LOG.error("[rider] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static double[] gaps(RiderAttack spec) {
        return spec.aim() == RiderAttack.Aim.SWEEP ? NEAR : spec.aim() == RiderAttack.Aim.LINE ? LINE : spec.aim() == RiderAttack.Aim.GRAB ? GRAB
                : spec.aim() == RiderAttack.Aim.CHARGE ? CHARGE : FAR;
    }

    private static void next(ServerLevel level) { attempt = 0; index++; stage(level); }

    // --- the descent: six steps of one block, two blocks deep, from a platform six blocks up -------------------------

    private static DigimonEntity walker;
    private static int descentTick, airTicks, longestAir, run;
    private static double startY;
    private static final int STEPS = 6, DESCENT_TIMEOUT = 20 * 10;
    private static final double PACE = .35;

    private static int stepHeight(int z) { return Math.clamp(STEPS - Math.floorDiv(z + 7, 2), 0, STEPS); }

    private static void stageDescent(ServerLevel level) {
        com.digicube.spawn.WildSpawner.clear(level);
        CombatScenario.purge(level, null, null);
        CombatScenario.build(level, "flat");
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int x = -4; x <= 4; x++) for (int z = -13; z <= 13; z++) {
            for (int h = 0; h < stepHeight(z); h++) level.setBlock(new net.minecraft.core.BlockPos(x, CombatScenario.FLOOR_Y + h, z), stone, 3);
            // The arena's roof is nine blocks up: open it over the steps, or the top one leaves no headroom.
            level.setBlock(new net.minecraft.core.BlockPos(x, CombatScenario.FLOOR_Y + 9, z), air, 3);
        }
        walker = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("centalmon")), 20, new Vec3(.5, CombatScenario.FLOOR_Y + STEPS, -11.5));
        walker.setYRot(0); walker.yBodyRot = walker.yHeadRot = 0;
        startY = walker.getY();
        descentTick = airTicks = longestAir = run = 0;
    }

    /** True once the descent is judged. */
    private static boolean observeDescent(ServerLevel level) {
        descentTick++;
        // Driven straight down the middle at a ridden canter (the AI's own walk is a crawl, and a rider's pace is the case).
        walker.setYRot(0); walker.yBodyRot = walker.yHeadRot = 0;
        walker.setDeltaMovement(0, walker.getDeltaMovement().y, PACE);
        // Only once it has set off, and while above the floor, is a tick off the ground a fall.
        if (descentTick > 20 && walker.getY() > CombatScenario.FLOOR_Y + .01) {
            run = walker.onGround() ? 0 : run + 1;
            if (run > 0) airTicks++;
            longestAir = Math.max(longestAir, run);
        }
        boolean down = walker.getY() < CombatScenario.FLOOR_Y + .01 && walker.getZ() > 6;
        if (!down && descentTick < DESCENT_TIMEOUT) return false;
        String line = String.format("walked down %.0f blocks of steps in %d ticks, %d ticks off the ground (longest %d)",
                startY - walker.getY(), descentTick, airTicks, longestAir);
        if (!down) failures.add("descent: never reached the foot of the steps; " + line);
        else if (longestAir > 1) failures.add("descent: fell from step to step; " + line);
        Constants.LOG.info("[rider] {} centalmon descent: {}", down && longestAir <= 1 ? "PASS" : "FAIL", line);
        walker.discard();
        return true;
    }

    private static void stage(ServerLevel level) {
        if (mount != null) { mount.discard(); dummy.discard(); }
        if (bystander != null) { bystander.discard(); bystander = null; }
        if (index >= cases.size()) { mount = null; stageDescent(level); return; }
        Case test = cases.get(index);
        com.digicube.spawn.WildSpawner.clear(level);
        CombatScenario.purge(level, null, null);
        CombatScenario.build(level, test.deep() ? "water" : "flat");
        double y = test.deep() ? CombatScenario.FLOOR_Y - 3 : CombatScenario.FLOOR_Y;
        mount = DigimonEntity.spawnWild(level, test.species(), 20, new Vec3(.5, y, -3.5));
        DigimonAttack attack = mount.riderAttacks().get(test.slot());
        RiderAttack spec = mount.riderSpec(attack);
        // Strikes are tried at arm's length (no client here to play the lunge); shots and streams from a distance.
        double gap = test.air() ? 30 : gaps(spec)[attempt];
        dummy = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, Vec3.ZERO);
        dummy.setPos(.5, test.deep() ? y - DEEP : y, -3.5 + mount.getBbWidth() * .5 + dummy.getBbWidth() * .5 + gap);
        dummy.setNoAi(true);
        var health = dummy.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        healthBefore = dummy.getHealth();
        if (test.air()) mount.setPos(.5, y + AIR_HEIGHT, -3.5);
        if (spec.aim() == RiderAttack.Aim.CHARGE && !test.air()) {
            // A second dummy off to one side of the run, nearer than the prey but outside what the charge homes on: it is
            // shoved aside unhurt (only the buck strikes).
            bystander = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, new Vec3(1.7, y, -3.5 + 2.4));
            bystander.setNoAi(true);
            var bystanderHealth = bystander.getAttribute(Attributes.MAX_HEALTH);
            if (bystanderHealth != null) bystanderHealth.setBaseValue(10_000);
            bystander.setHealth(bystander.getMaxHealth());
            bystanderBefore = bystander.getHealth();
        }
        mount.setYRot(0); mount.yBodyRot = mount.yHeadRot = 0;
        rider = FakePlayer.get(level);
        rider.setPos(mount.position());
        mount.seatScenarioRider(rider);
        seat(rider, mount);
        caseTick = 0; started = released = false; thrown = null;
    }

    /** Peak height and ground covered by a body launched at {@code v}, under vanilla's gravity and air drag. */
    private static double[] flight(Vec3 v) {
        double y = 0, peak = 0, across = 0, vy = v.y, vh = v.horizontalDistance();
        for (int tick = 0; tick < 100 && (tick == 0 || y > 0); tick++) {
            y += vy; across += vh; peak = Math.max(peak, y);
            vy = (vy - .08) * .98; vh *= .91;
        }
        return new double[]{peak, across};
    }

    /**
     * Fabric's fake player refuses every ride, so the two ends of the vanilla link are set directly. Development
     * only: the names are Mojang's, which is what a development run uses.
     */
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

    private static void observe(ServerLevel level) {
        Case test = cases.get(index);
        DigimonAttack attack = mount.riderAttacks().get(test.slot());
        caseTick++;
        // Nothing moves a ridden mount here (its rider's client would), so it never lands by itself.
        if (!test.air() || caseTick < 25) mount.setOnGround(!test.air());
        dummy.setOnGround(true);
        // Look at the dummy's chest, as a rider with the crosshair on it would.
        Vec3 to = dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
        if (caseTick == 25) {
            if (mount.getControllingPassenger() != rider) { fail(test, attack, "the fake rider does not control the mount"); next(level); return; }
            started = mount.startRiderAttack(rider, test.slot());
            if (!started) { fail(test, attack, "the cast was refused"); next(level); return; }
        }
        // A stream is breathed and a drawn shot held raised until the button comes up.
        RiderAttack held = mount.riderSpec(attack);
        boolean drawn = held.aim() == RiderAttack.Aim.SHOT && held.input() == RiderAttack.Input.HOLD;
        if (started && !released && (attack.fuel() != null || drawn) && caseTick == 25 + STREAM_HOLD) { mount.stopRiderAttack(rider); released = true; }
        if (test.air()) {
            if (caseTick == 25) airStart = mount.position();
            if (started && !mount.riderCharging() && caseTick > 27 || caseTick > TIMEOUT) {
                double carried = mount.position().subtract(airStart).horizontalDistance();
                if (carried < AIR_CARRY) fail(test, attack, String.format("a charge in the air carried the body only %.1f blocks", carried));
                else Constants.LOG.info("[rider] PASS {} slot {} {} in the air ({}): carried {} blocks, came down {} blocks, over at tick {}",
                        test.species().id().getPath(), test.slot(), attack.id().getPath(), mount.riderSpec(attack).aim(),
                        String.format("%.1f", carried), String.format("%.1f", airStart.y - mount.getY()), caseTick - 25);
                next(level);
            }
            return;
        }
        float dealt = healthBefore - dummy.getHealth();
        if (dealt > 0 && thrown == null) thrown = dummy.getDeltaMovement();
        boolean over = started && !mount.isAttacking() && caseTick > 27;
        if (dealt > 0 && over || caseTick > TIMEOUT) {
            if (dealt <= 0 && attempt + 1 < gaps(mount.riderSpec(attack)).length) { attempt++; stage(level); return; }
            if (dealt <= 0) fail(test, attack, "no damage at any distance tried");
            else if (bystander != null && bystanderBefore - bystander.getHealth() > 0) fail(test, attack, "the charge hurt the dummy beside its run");
            else if (attack.fuel() != null && !released) fail(test, attack, "the stream ended before the release");
            else if (drawn && !released) fail(test, attack, "the drawn shot left before the release");
            else if (held.aim() == RiderAttack.Aim.CHARGE && thrown != null && (flight(thrown)[0] > 1.5 || flight(thrown)[1] > 4.5))
                fail(test, attack, String.format("the buck throws its prey %.1f blocks up and %.1f away (launched at %.2f across, %.2f up)", flight(thrown)[0], flight(thrown)[1], thrown.horizontalDistance(), thrown.y));
            else Constants.LOG.info("[rider] PASS {} slot {} {} ({}): {} damage from {} blocks, over at tick {}", test.species().id().getPath(), test.slot(),
                        attack.id().getPath() + (test.deep() ? " on prey " + DEEP + " blocks below, in water" : "")
                        + (bystander != null ? ", the dummy beside the run unhurt" : "")
                        + (held.aim() == RiderAttack.Aim.CHARGE && thrown != null ? String.format(", kicked %.1f blocks up and %.1f away", flight(thrown)[0], flight(thrown)[1]) : ""),
                        mount.riderSpec(attack).aim(), String.format("%.1f", dealt), gaps(mount.riderSpec(attack))[attempt], caseTick - 25);
            next(level);
        }
    }

    private static void fail(Case test, DigimonAttack attack, String why) {
        String line = test.species().id().getPath() + " slot " + test.slot() + " " + attack.id().getPath() + (test.deep() ? " (deep)" : "") + (test.air() ? " (air)" : "") + ": " + why;
        failures.add(line);
        Constants.LOG.info("[rider] FAIL {}", line);
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[rider] RESULT {} of {} casts landed{}", cases.size() + 1 - failures.size(), cases.size() + 1,
                failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
