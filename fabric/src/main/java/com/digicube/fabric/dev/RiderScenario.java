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
 * view, and lands damage. The verdict line starts with {@code [rider] RESULT}.
 */
public final class RiderScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int TIMEOUT = 20 * 8, STREAM_HOLD = 45;

    /** {@code deep}: staged in the pool, the prey swimming {@link #DEEP} blocks under the mount's level; a hold glides down to it. */
    private record Case(DigimonSpecies species, int slot, boolean deep) {}
    private static final double DEEP = 1.5;
    /** Gaps between the two bodies to try, nearest first: a strike's reach without its lunge is not written anywhere. */
    private static final double[] NEAR = {.6, 1.4, 2.4, 3.4}, FAR = {6}, LINE = {4.5, 3.0}, GRAB = {5};
    private static int attempt;

    private static List<Case> cases;
    private static int index = -1, caseTick;
    private static DigimonEntity mount, dummy;
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
                        cases.add(new Case(species, slot, false));
                        if (species.body().mount().orElseThrow().riderAttacks().get(slot).aim() == RiderAttack.Aim.GRAB) cases.add(new Case(species, slot, true));
                    }
                }
                for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++) level.setChunkForced(cx, cz, true);
                level.getServer().tickRateManager().requestGameToSprint(cases.size() * (TIMEOUT + 60) * NEAR.length);
                next(level);
            } else observe(level);
        } catch (RuntimeException e) {
            Constants.LOG.error("[rider] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static double[] gaps(RiderAttack spec) {
        return spec.aim() == RiderAttack.Aim.SWEEP ? NEAR : spec.aim() == RiderAttack.Aim.LINE ? LINE : spec.aim() == RiderAttack.Aim.GRAB ? GRAB : FAR;
    }

    private static void next(ServerLevel level) { attempt = 0; index++; stage(level); }

    private static void stage(ServerLevel level) {
        if (mount != null) { mount.discard(); dummy.discard(); }
        if (index >= cases.size()) { finish(level); return; }
        Case test = cases.get(index);
        com.digicube.spawn.WildSpawner.clear(level);
        CombatScenario.purge(level, null, null);
        CombatScenario.build(level, test.deep() ? "water" : "flat");
        double y = test.deep() ? CombatScenario.FLOOR_Y - 3 : CombatScenario.FLOOR_Y;
        mount = DigimonEntity.spawnWild(level, test.species(), 20, new Vec3(.5, y, -3.5));
        DigimonAttack attack = mount.riderAttacks().get(test.slot());
        RiderAttack spec = mount.riderSpec(attack);
        // Strikes are tried at arm's length (no client here to play the lunge); shots and streams from a distance.
        double gap = gaps(spec)[attempt];
        dummy = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, Vec3.ZERO);
        dummy.setPos(.5, test.deep() ? y - DEEP : y, -3.5 + mount.getBbWidth() * .5 + dummy.getBbWidth() * .5 + gap);
        dummy.setNoAi(true);
        var health = dummy.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        healthBefore = dummy.getHealth();
        mount.setYRot(0); mount.yBodyRot = mount.yHeadRot = 0;
        rider = FakePlayer.get(level);
        rider.setPos(mount.position());
        mount.seatScenarioRider(rider);
        seat(rider, mount);
        caseTick = 0; started = released = false;
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
        mount.setOnGround(true);
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
        if (started && !released && attack.fuel() != null && caseTick == 25 + STREAM_HOLD) { mount.stopRiderAttack(rider); released = true; }
        float dealt = healthBefore - dummy.getHealth();
        boolean over = started && !mount.isAttacking() && caseTick > 27;
        if (dealt > 0 && over || caseTick > TIMEOUT) {
            if (dealt <= 0 && attempt + 1 < gaps(mount.riderSpec(attack)).length) { attempt++; stage(level); return; }
            if (dealt <= 0) fail(test, attack, "no damage at any distance tried");
            else if (attack.fuel() != null && !released) fail(test, attack, "the stream ended before the release");
            else Constants.LOG.info("[rider] PASS {} slot {} {} ({}): {} damage from {} blocks, over at tick {}", test.species().id().getPath(), test.slot(),
                        attack.id().getPath() + (test.deep() ? " on prey " + DEEP + " blocks below, in water" : ""), mount.riderSpec(attack).aim(), String.format("%.1f", dealt), gaps(mount.riderSpec(attack))[attempt], caseTick - 25);
            next(level);
        }
    }

    private static void fail(Case test, DigimonAttack attack, String why) {
        String line = test.species().id().getPath() + " slot " + test.slot() + " " + attack.id().getPath() + (test.deep() ? " (deep)" : "") + ": " + why;
        failures.add(line);
        Constants.LOG.info("[rider] FAIL {}", line);
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[rider] RESULT {} of {} casts landed{}", cases.size() - failures.size(), cases.size(),
                failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
