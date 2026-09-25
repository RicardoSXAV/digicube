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
 * (no tick in the air), as it does going up. A stacked attack (Gold Rush) is also pressed again each time its cast ends:
 * every stack must go at once, and the press after the last must be refused. The verdict line starts with {@code [rider] RESULT}.
 */
public final class RiderScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int TIMEOUT = 20 * 8, STREAM_HOLD = 45;

    /**
     * {@code deep}: staged in the pool, the prey swimming {@link #DEEP} blocks under the mount's level; a hold glides down to it.
     * {@code air}: a charge fired mid-leap, {@link #AIR_HEIGHT} blocks up with nothing in reach; it must carry the body far.
     * {@code stacks}: a stacked attack pressed again as each cast ends, until the stack is spent.
     */
    /**
     * @param far cast from just inside the attack's own range (a volley's missiles must fly the whole way); for a
     *            returning throw, held to a full charge at a dummy {@link #FAR_THROW} blocks off, out of a tap's reach
     * @param home a thrown weapon's rider case: a bone is tapped, the rider steers into the catch ring and it must come
     *             home to the fist, then tapped again and left to fall, and walking over it must pick it up; an icicle
     *             is tapped (pressed and let go at once) and the snap dart must land
     * With {@code air}, a thrown weapon is tapped from the top of a leap: it must land, and leave the hand harder.
     */
    private record Case(DigimonSpecies species, int slot, boolean deep, boolean air, boolean stacks, boolean far, boolean home) {
        Case(DigimonSpecies species, int slot, boolean deep, boolean air, boolean stacks, boolean far) { this(species, slot, deep, air, stacks, far, false); }
    }
    private static final double AIR_HEIGHT = 3, AIR_CARRY = 6;
    /** A far throw's dummy, blocks off; and how high a thrown weapon's leap holds the mount. */
    private static final double FAR_THROW = 20, LEAP_HOLD = 1.4;
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
                        cases.add(new Case(species, slot, false, false, false, false));
                        var aim = species.body().mount().orElseThrow().riderAttacks().get(slot).aim();
                        if (aim == RiderAttack.Aim.GRAB) cases.add(new Case(species, slot, true, false, false, false));
                        if (aim == RiderAttack.Aim.CHARGE) cases.add(new Case(species, slot, false, true, false, false));
                        var id = species.body().mount().orElseThrow().riderAttacks().get(slot).attack();
                        if (species.attacks().stream().anyMatch(a -> a.id().equals(id) && com.digicube.digimon.AttackCharges.of(a) > 1))
                            cases.add(new Case(species, slot, false, false, true, false));
                        if (species.attacks().stream().anyMatch(a -> a.id().equals(id) && com.digicube.digimon.AuthoredAttacks.handles(a)
                                && com.digicube.digimon.AuthoredAttacks.get(a).fires()))
                            cases.add(new Case(species, slot, false, false, false, true));
                        if (species.attacks().stream().anyMatch(a -> a.id().equals(id) && com.digicube.digimon.ThrownAttacks.handles(a))) {
                            cases.add(new Case(species, slot, false, false, false, false, true));
                            cases.add(new Case(species, slot, false, true, false, false));
                        }
                        if (species.attacks().stream().anyMatch(a -> a.id().equals(id) && com.digicube.digimon.ThrownAttacks.returning(a) != null))
                            cases.add(new Case(species, slot, false, false, false, true));
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

    private static double gap(Case test, DigimonAttack attack, RiderAttack spec) {
        if (com.digicube.digimon.ThrownAttacks.handles(attack) && (test.air() || test.far())) return test.far() ? FAR_THROW : FAR[0];
        return test.air() ? 30 : test.far() ? attack.range() - 3 : gaps(spec)[attempt];
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
        boolean throwing = com.digicube.digimon.ThrownAttacks.handles(attack);
        // A far throw starts near one wall of the arena, so its dummy fits inside the other.
        double from = throwing && test.far() ? -12.5 : -3.5;
        if (from != -3.5) mount.setPos(.5, y, from);
        // Strikes are tried at arm's length (no client here to play the lunge); shots and streams from a distance.
        double gap = gap(test, attack, spec);
        dummy = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")), 20, Vec3.ZERO);
        dummy.setPos(.5, test.deep() ? y - DEEP : y, from + mount.getBbWidth() * .5 + dummy.getBbWidth() * .5 + gap);
        dummy.setNoAi(true);
        var health = dummy.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(10_000);
        dummy.setHealth(dummy.getMaxHealth());
        healthBefore = dummy.getHealth();
        if (test.air() && !throwing) mount.setPos(.5, y + AIR_HEIGHT, -3.5);
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
        boolean throwing = com.digicube.digimon.ThrownAttacks.handles(attack);
        // Nothing moves a ridden mount here (its rider's client would), so it never lands by itself.
        if (throwing && test.air()) {
            // A leap: on the ground, then held at the top of it while the weapon is thrown.
            if (caseTick == 20) mount.setPos(mount.getX(), mount.getY() + LEAP_HOLD, mount.getZ());
            mount.setOnGround(caseTick < 20);
        } else if (!test.air() || caseTick < 25) mount.setOnGround(!test.air());
        dummy.setOnGround(true);
        // Look at the dummy's chest, as a rider with the crosshair on it would.
        Vec3 to = dummy.getBoundingBox().getCenter().subtract(rider.getEyePosition());
        rider.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        rider.setXRot((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())));
        if (test.stacks()) { observeStacks(level, test, attack); return; }
        if (test.home()) { observeHome(level, test, attack); return; }
        if (throwing && (test.air() || test.far())) { observeThrown(level, test, attack); return; }
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
                        attack.id().getPath() + (test.deep() ? " on prey " + DEEP + " blocks below, in water" : "") + (test.far() ? " at long range" : "")
                        + (bystander != null ? ", the dummy beside the run unhurt" : "")
                        + (held.aim() == RiderAttack.Aim.CHARGE && thrown != null ? String.format(", kicked %.1f blocks up and %.1f away", flight(thrown)[0], flight(thrown)[1]) : ""),
                        mount.riderSpec(attack).aim(), String.format("%.1f", dealt), gap(test, attack, mount.riderSpec(attack)), caseTick - 25);
            next(level);
        }
    }

    private static final List<Integer> stackStarts = new ArrayList<>();
    private static int stackRefusedAt = -1;

    /** Presses the stacked attack again the tick each cast ends: all its uses go back to back, the next press is refused. */
    private static void observeStacks(ServerLevel level, Case test, DigimonAttack attack) {
        int uses = com.digicube.digimon.AttackCharges.of(attack);
        if (caseTick < 25) { stackStarts.clear(); stackRefusedAt = -1; return; }
        if (mount.isAttacking() || stackRefusedAt >= 0) {
            if (stackRefusedAt >= 0 && caseTick >= stackRefusedAt + 20) {
                if (mount.isAttacking()) fail(test, attack, "a press after the last stack started a cast anyway");
                else Constants.LOG.info("[rider] PASS {} slot {} {} stacks: {} casts back to back at ticks {}, the next press refused ({} uses ready)",
                        test.species().id().getPath(), test.slot(), attack.id().getPath(), stackStarts.size(), stackStarts, mount.readyUses(attack));
                next(level);
            }
            if (caseTick > TIMEOUT + 3 * attack.durationTicks()) { fail(test, attack, "stuck after " + stackStarts.size() + " casts"); next(level); }
            return;
        }
        boolean cast = mount.startRiderAttack(rider, test.slot());
        if (stackStarts.size() < uses) {
            if (!cast) {
                fail(test, attack, "stack " + (stackStarts.size() + 1) + " of " + uses + " was refused after casts at " + stackStarts + " (tick "
                        + (caseTick - 25) + ", " + mount.readyUses(attack) + " uses ready, active " + mount.getActiveAttack() + ", ready " + mount.isAttackReady(attack)
                        + ", on ground " + mount.onGround() + ")");
                next(level);
                return;
            }
            stackStarts.add(caseTick - 25);
        } else if (cast) { fail(test, attack, "a press after " + uses + " stacks started a cast"); next(level); }
        else stackRefusedAt = caseTick;
    }

    // --- a thrower's weapons under a rider ------------------------------------------------------------------------

    /** 0 thrown, steering home; 1 caught, thrown again; 2 left to fall; 3 walked over to it and pressed; 4 picked up. */
    private static int homeStep, homeMark;
    private static String homeNotes;
    /** Blocks a tick the scenario walks the mount, a ridden run's pace. */
    private static final double HOME_PACE = .2;
    private static final int HOME_TIMEOUT = 20 * 14;

    /**
     * The rider's side of a thrown weapon. The bone: tapped at the dummy, the mount is walked (as the rider would steer
     * it) into the catch ring, the spot its client draws, and the bone must be caught there; tapped again and left, it
     * falls, and walking the mount over it must pick it up with no press. The icicle: pressed and let go the next
     * tick, the snap dart must land.
     */
    private static void observeHome(ServerLevel level, Case test, DigimonAttack attack) {
        var thrower = mount.thrower();
        if (caseTick < 25) { homeStep = 0; homeMark = 25; homeNotes = ""; return; }
        if (caseTick > 25 + HOME_TIMEOUT) { fail(test, attack, "timed out at step " + homeStep + homeNotes); next(level); return; }
        if (com.digicube.digimon.ThrownAttacks.charged(attack) != null) {
            if (caseTick == 25 && !mount.startRiderAttack(rider, test.slot())) { fail(test, attack, "the tap was refused"); next(level); return; }
            if (caseTick == 26) mount.stopRiderAttack(rider);
            float dealt = healthBefore - dummy.getHealth();
            if (dealt > 0 && !mount.isAttacking()) {
                Constants.LOG.info("[rider] PASS {} slot {} {} tapped: a snap dart, {} damage from {} blocks, over at tick {}",
                        test.species().id().getPath(), test.slot(), attack.id().getPath(), String.format("%.1f", dealt), FAR[0], caseTick - 25);
                next(level);
            }
            return;
        }
        var bone = thrower.bone();
        switch (homeStep) {
            case 0, 1 -> {
                if (caseTick == homeMark && !mount.startRiderAttack(rider, test.slot())) { fail(test, attack, "throw " + (homeStep + 1) + " was refused"); next(level); return; }
                // A tap: the button comes straight back up, before the wind-up is cocked.
                if (caseTick == homeMark + 1) mount.stopRiderAttack(rider);
                if (homeStep == 0 && bone != null && bone.phase() == com.digicube.entity.BoomerangEntity.Phase.FLYING) walk(bone.path().catchPoint());
                if (homeStep == 0 && thrower.carried() && caseTick > homeMark + 20) {
                    homeNotes += String.format("; caught %d ticks after the throw, %.1f damage", caseTick - homeMark, healthBefore - dummy.getHealth());
                    homeStep = 1; homeMark = caseTick + 40;
                } else if (homeStep == 0 && bone != null && bone.phase() != com.digicube.entity.BoomerangEntity.Phase.FLYING
                        && bone.phase() != com.digicube.entity.BoomerangEntity.Phase.CATCHING) {
                    fail(test, attack, "the bone fell although the mount stood in the catch ring" + homeNotes); next(level); return;
                }
                if (homeStep == 1 && caseTick > homeMark && !thrower.carried()) homeStep = 2;
            }
            case 2 -> {
                if (bone != null && bone.phase() == com.digicube.entity.BoomerangEntity.Phase.GROUNDED) { homeStep = 3; homeMark = caseTick; }
                else if (thrower.carried()) { fail(test, attack, "the second bone came home by itself: the mount never moved" + homeNotes); next(level); return; }
            }
            case 3 -> {
                // Walked onto it, no press: the body bends for it by itself.
                if (thrower.stage() == com.digicube.entity.ThrowerState.Stage.PICKUP) { homeStep = 4; homeMark = caseTick; return; }
                if (bone == null) { fail(test, attack, "the lost bone vanished before it was reached" + homeNotes); next(level); return; }
                walk(bone.position());
            }
            case 4 -> {
                if (thrower.carried()) {
                    Constants.LOG.info("[rider] PASS {} slot {} {} home{}; lost, then walked over and picked up without a press, in hand {} ticks later",
                            test.species().id().getPath(), test.slot(), attack.id().getPath(), homeNotes, caseTick - homeMark);
                    next(level);
                }
            }
            default -> {}
        }
    }

    /**
     * A thrown weapon from the saddle, the hard way. {@code far}: the bone held to a full charge at a dummy out of a
     * tap's reach; its turn must be past the tap's reach and it must strike. {@code air}: tapped from the top of a leap;
     * it must strike, and have left the hand harder than a throw from the ground.
     */
    private static void observeThrown(ServerLevel level, Case test, DigimonAttack attack) {
        var thrower = mount.thrower();
        var returning = com.digicube.digimon.ThrownAttacks.returning(attack);
        if (caseTick < 25) return;
        if (caseTick == 25 && !mount.startRiderAttack(rider, test.slot())) { fail(test, attack, "the press was refused"); next(level); return; }
        int hold = test.far() ? STREAM_HOLD : 1;
        if (caseTick == 25 + hold) mount.stopRiderAttack(rider);
        var bone = thrower.bone();
        if (bone != null && bone.path() != null && flownRange < 0) flownRange = bone.path().range();
        float dealt = healthBefore - dummy.getHealth();
        if (caseTick == 25) flownRange = -1;
        if (dealt > 0 && caseTick > 25 + hold) {
            float airBoost = returning != null ? returning.airBoost() : com.digicube.digimon.ThrownAttacks.charged(attack).airBoost();
            if (test.far() && flownRange <= returning.maxRange())
                fail(test, attack, String.format("held to a full charge, the bone turned at %.1f blocks, no further than a tap's %.1f", flownRange, returning.maxRange()));
            else if (test.air() && thrower.lastImpulse() < 1 + airBoost - .01F)
                fail(test, attack, String.format("thrown from a leap it left the hand no harder (impulse %.2f)", thrower.lastImpulse()));
            else Constants.LOG.info("[rider] PASS {} slot {} {} {}: {} damage from {} blocks, impulse {}{}, over at tick {}", test.species().id().getPath(),
                        test.slot(), attack.id().getPath(), test.far() ? "held to a full charge" : "from a leap", String.format("%.1f", dealt),
                        gap(test, attack, mount.riderSpec(attack)), String.format("%.2f", thrower.lastImpulse()),
                        flownRange > 0 ? String.format(", turned at %.1f", flownRange) : "", caseTick - 25);
            next(level);
        } else if (caseTick > 25 + HOME_TIMEOUT) { fail(test, attack, "no damage" + (flownRange > 0 ? String.format(" (the bone turned at %.1f)", flownRange) : "")); next(level); }
    }
    private static double flownRange = -1;

    /** Walks the mount a step toward {@code point} at a ridden pace; true once it stands there. */
    private static boolean walk(Vec3 point) {
        Vec3 to = point.subtract(mount.position()).multiply(1, 0, 1);
        if (to.length() < .05) return true;
        Vec3 step = to.length() <= HOME_PACE ? to : to.normalize().scale(HOME_PACE);
        mount.setPos(mount.getX() + step.x, mount.getY(), mount.getZ() + step.z);
        return false;
    }

    private static void fail(Case test, DigimonAttack attack, String why) {
        String line = test.species().id().getPath() + " slot " + test.slot() + " " + attack.id().getPath() + (test.deep() ? " (deep)" : "") + (test.air() ? " (air)" : "") + (test.far() ? " (far)" : "") + (test.home() ? " (home)" : "") + ": " + why;
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
