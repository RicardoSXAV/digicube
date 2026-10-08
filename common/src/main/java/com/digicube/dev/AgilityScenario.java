package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.DigimonTactics;
import com.digicube.digimon.KineticAttacks;
import com.digicube.digimon.Launch;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.KineticProjectileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The body's own agility ({@code body.leap}, {@code body.crouch}: {@code Agility}) on one species, Leomon by default
 * ({@code agility_checks[:<species>]}): a standing and a running leap (height against vanilla's flight, length, the run
 * kept through the air, unhurt), the crouched box to the hundredth and standing up refused under a low roof, a shot that
 * strikes the standing body and passes over the crouched one, the roll (its pace kept, the tucked box only in its window,
 * no second roll inside it, up and running again after it), a shot that strikes a run but passes over the roll, a held
 * crouch's roll (crouched outside its window and after it) and a run, leap and tuck that rolls on landing; then the AI's own
 * answers (a duck under a shot standing, a roll under one at a run, a leap clear of a tectonic wave) and a kinetic
 * shot's falloff and launch measured near and far. Opt-in through {@link CombatScenario} as {@code agility_checks};
 * {@code DIGICUBE_AGILITY_ONLY=<prefix>} runs the checks whose names start so.
 */
final class AgilityScenario {
    private static final AABB ARENA = new AABB(-14, CombatScenario.FLOOR_Y - 6, -14, 15, CombatScenario.FLOOR_Y + 9, 15);
    private static final double FLOOR = CombatScenario.FLOOR_Y;
    private static final List<String> CASES = List.of("standing leap", "running leap", "crouch box", "low roof", "shot over the crouch",
            "roll", "shot over the roll", "roll held", "leap and tuck", "ai duck", "ai roll", "ai wave leap", "ai roll dodge", "ai footwork",
            "falloff and launch");
    private static final int CASE_TIMEOUT = 20 * 30;
    private static final String ONLY = System.getenv("DIGICUBE_AGILITY_ONLY");
    /** Vanilla's flight for a living body: gravity and drag a tick. */
    private static final double GRAVITY = .08, DRAG = .98;
    /** The shot every check fires (a level water shot), and the falloff and launch the last check gives it. */
    private static final String SHOT = "water_shot";
    private static final KineticAttacks.Falloff FALLOFF = new KineticAttacks.Falloff(2, 12, .45F, .3F);
    private static final Launch LAUNCH = new Launch(1.0F, .4F);

    private static int index = -1, ticks, passed, failed;
    private static boolean initialized, done;
    private static String subjectId;
    private static DigimonSpecies tested;
    private static DigimonEntity subject, other;
    private static Mob dummy;
    private static KineticAttacks.Definition shotBefore;
    private static final List<String> SUMMARY = new ArrayList<>();

    // Per-case readings.
    private static int mark = -1, phase, hits, airTicks, rollAt = -1, wrongBox, castAt = -1, damaged;
    private static double maxRise, pace, airSpeed, best, worst;
    private static boolean flag, secondRoll, low;
    private static Vec3 from, last;
    private static final List<Double> readings = new ArrayList<>(), readings2 = new ArrayList<>(), readings3 = new ArrayList<>(), readings4 = new ArrayList<>();
    private static KineticProjectileEntity shot;

    private AgilityScenario() {}

    static void tick(ServerLevel level, String species) {
        if (done) return;
        try {
            if (!initialized) {
                initialized = true;
                subjectId = species.isBlank() ? "leomon" : species;
                for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) level.setChunkForced(x, z, true);
                CombatScenario.build(level, "flat");
                shotBefore = KineticAttacks.get(Constants.id(SHOT));
            } else if (index < 0) {
                // The forced chunks come to ticking first: a body spawned now would hang where it was put.
                if (++ticks >= 40) next(level);
            } else observe(level);
        } catch (RuntimeException | AssertionError e) {
            Constants.LOG.error("[agility] aborted at {}", index < 0 || index >= CASES.size() ? "start" : CASES.get(index), e);
            failed++;
            finish(level);
        }
    }

    // --- fixtures ------------------------------------------------------------------------------------------------------

    /** The subject's sheet as tested: its own, with a move (left on manual) so the fight goal runs, and tactics for the case. */
    private static DigimonSpecies subjectSheet(String... tactics) {
        var bundled = DigimonSpeciesBootstrap.bundled(Constants.id(subjectId));
        if (bundled.body().crouch() == null || bundled.body().leap() == null && bundled.body().mount().map(m -> m.jump()).orElse(0F) <= 0)
            throw new AssertionError(subjectId + " has no crouch or no leap of its own (body.crouch, body.leap)");
        List<DigimonAttack> moves = bundled.attacks().isEmpty() ? List.of(shotBefore.attack()) : bundled.attacks();
        DigimonTactics knobs = bundled.tactics();
        for (int i = 0; i + 1 < tactics.length; i += 2) knobs = knobs.with(tactics[i], tactics[i + 1]);
        var sheet = new DigimonSpecies(bundled.id(), bundled.stage(), bundled.attribute(), bundled.baseHealth(), bundled.baseAttack(),
                bundled.baseDefence(), bundled.baseSpeed(), bundled.evolutions(), moves, bundled.body(), bundled.locomotion(), knobs);
        DigimonSpeciesRegistry.replace(sheet);
        tested = sheet;
        return sheet;
    }

    private static DigimonEntity spawn(ServerLevel level, DigimonSpecies species, Vec3 at, float yaw) {
        var body = DigimonEntity.spawnWild(level, species, 20, at);
        if (body == null) throw new AssertionError("fixture spawn");
        body.snapTo(at.x, at.y, at.z, yaw, 0);
        body.yBodyRot = body.yHeadRot = yaw;
        body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
        body.setHealth(body.getMaxHealth());
        return body;
    }

    /** No target, no path, nothing that provokes it: the body does only what the check asks. */
    private static void quiet(DigimonEntity body) {
        body.setTarget(null);
        body.setLastHurtByMob(null);
        body.getNavigation().stop();
    }

    /** Runs the body ahead along +z at its AI's run (vanilla's move control at the sheet's run modifier). */
    private static void run(DigimonEntity body) {
        body.setTarget(null);
        body.setLastHurtByMob(null);
        body.getNavigation().stop();
        body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ() + 6, body.getLocomotion().runSpeed());
    }

    /** Whether the body lost health since the last look, healing it again. */
    private static boolean hurt(LivingEntity body) {
        boolean lost = body.getHealth() < body.getMaxHealth() - 1.0E-3;
        body.setHealth(body.getMaxHealth());
        return lost;
    }

    /** Fires the check's shot from {@code origin} along {@code direction}, owned by {@code owner}. */
    private static KineticProjectileEntity fire(ServerLevel level, DigimonEntity owner, Vec3 origin, Vec3 direction) {
        var definition = KineticAttacks.get(Constants.id(SHOT));
        var projectile = new KineticProjectileEntity(level, owner, definition, origin, direction.normalize());
        level.addFreshEntity(projectile);
        return projectile;
    }

    /** The apex vanilla's flight reaches from an upward speed of {@code vy}. */
    private static double apex(double vy) {
        double y = 0, top = 0;
        for (int i = 0; i < 200 && vy > 0; i++) { y += vy; top = Math.max(top, y); vy = (vy - GRAVITY) * DRAG; }
        return top;
    }

    private static void next(ServerLevel level) {
        level.getEntities((Entity) null, ARENA).forEach(Entity::discard);
        clearRoof(level);
        if (tested != null) DigimonSpeciesRegistry.replace(DigimonSpeciesBootstrap.bundled(tested.id()));
        subject = other = null;
        dummy = null;
        shot = null;
        ticks = 0;
        mark = rollAt = castAt = -1;
        phase = hits = airTicks = wrongBox = damaged = 0;
        maxRise = pace = airSpeed = best = worst = 0;
        flag = secondRoll = low = false;
        from = last = null;
        readings.clear(); readings2.clear(); readings3.clear(); readings4.clear();
        do index++; while (index < CASES.size() && ONLY != null && !CASES.get(index).startsWith(ONLY));
        if (index == CASES.size()) { finish(level); return; }
        Constants.LOG.info("[agility] case {}: {}", index, CASES.get(index));
        switch (CASES.get(index)) {
            case "standing leap", "crouch box", "low roof" -> subject = spawn(level, subjectSheet(), new Vec3(.5, FLOOR, .5), 0);
            case "running leap", "roll", "roll held", "leap and tuck" -> subject = spawn(level, subjectSheet(), new Vec3(.5, FLOOR, -12.5), 0);
            case "shot over the crouch" -> {
                subject = spawn(level, subjectSheet(), new Vec3(.5, FLOOR, -.5), 0);
                other = spawn(level, subjectSheet(), new Vec3(.5, FLOOR, 11.5), 180);
                other.setNoAi(true);
            }
            case "shot over the roll" -> {
                subject = spawn(level, subjectSheet(), new Vec3(-3.5, FLOOR, -12.5), 0);
                other = spawn(level, subjectSheet(), new Vec3(10.5, FLOOR, 12.5), 90);
                other.setNoAi(true);
            }
            case "ai duck" -> {
                var sheet = subjectSheet("duck_chance", "1", "dodge_chance", "0", "roll_dodge", "0", "footwork", "false", "stalk", "0",
                        "hold_min", "0", "hold_max", "0");
                subject = spawn(level, sheet, new Vec3(.5, FLOOR, -2.5), 0);
                other = spawn(level, sheet, new Vec3(.5, FLOOR, 2.0), 180);
                other.setNoAi(true);
                manual(subject);
                subject.setTarget(other);
            }
            case "ai roll" -> {
                var sheet = subjectSheet("duck_chance", "1", "dodge_chance", "0", "roll_dodge", "0", "footwork", "false", "stalk", "0",
                        "hold_min", "0", "hold_max", "0");
                subject = spawn(level, sheet, new Vec3(.5, FLOOR, -12.5), 0);
                other = spawn(level, sheet, new Vec3(.5, FLOOR, 11.5), 180);
                other.setNoAi(true);
                manual(subject);
                subject.setTarget(other);
            }
            case "ai wave leap" -> {
                // It keeps its distance (a band of 5 to 7 blocks): the wave's line reaches it, the fist's slam does not.
                var sheet = subjectSheet("leap_dodge", "true", "dodge_chance", "1", "duck_chance", "0", "hold_max", "7", "hold_min", "5",
                        "roll_dodge", "0", "footwork", "false", "stalk", "0");
                subject = spawn(level, sheet, new Vec3(.5, FLOOR, -3.5), 0);
                var golemon = DigimonSpeciesBootstrap.bundled(Constants.id("golemon"));
                var wave = golemon.attacks().stream().filter(a -> a.kind() == DigimonAttack.Kind.GROUND_WAVE).toList();
                if (wave.isEmpty()) throw new AssertionError("Golemon has no ground wave");
                var waver = new DigimonSpecies(golemon.id(), golemon.stage(), golemon.attribute(), golemon.baseHealth(), golemon.baseAttack(),
                        golemon.baseDefence(), golemon.baseSpeed(), golemon.evolutions(), wave, golemon.body(), golemon.locomotion(), golemon.tactics());
                DigimonSpeciesRegistry.replace(waver);
                other = spawn(level, waver, new Vec3(.5, FLOOR, 3.5), 180);
                manual(subject);
                subject.setTarget(other);
                other.setTarget(subject);
            }
            case "ai roll dodge" -> {
                // Nothing to duck under: a punch from close by is rolled away from, out of its reach.
                var sheet = subjectSheet("roll_dodge", "1", "dodge_chance", "0", "duck_chance", "0", "footwork", "false", "stalk", "0",
                        "hold_min", "0", "hold_max", "0");
                subject = spawn(level, sheet, new Vec3(.5, FLOOR, -1.5), 0);
                other = spawn(level, puncher(), new Vec3(.5, FLOOR, 1.5), 180);
                manual(subject);
                subject.setTarget(other);
                other.setTarget(subject);
            }
            case "ai footwork" -> {
                // Close to an enemy and nothing ready: it backs off facing it, then circles it, low (a stalk every stretch).
                var sheet = subjectSheet("footwork", "true", "strafe", "true", "stalk", "1", "hold_min", "3.5", "hold_max", "6.5",
                        "roll_dodge", "0", "dodge_chance", "0", "duck_chance", "0");
                subject = spawn(level, sheet, new Vec3(.5, FLOOR, -1.0), 0);
                other = spawn(level, puncher(), new Vec3(.5, FLOOR, 1.0), 180);
                other.setNoAi(true);
                manual(subject);
                subject.setTarget(other);
            }
            case "falloff and launch" -> {
                KineticAttacks.replace(shotBefore.withImpact(FALLOFF, LAUNCH));
                other = spawn(level, subjectSheet(), new Vec3(.5, FLOOR, -12.5), 0);
                other.setNoAi(true);
                dummy = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
                if (dummy == null) throw new AssertionError("fixture spawn");
                dummy.snapTo(.5, FLOOR, .5, 180, 0);
                dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10_000);
                dummy.setHealth(dummy.getMaxHealth());
                level.addFreshEntity(dummy);
            }
            default -> throw new AssertionError("unknown case");
        }
    }

    /** Golemon with its punch alone: a quick fist up close, nothing to duck under. */
    private static DigimonSpecies puncher() {
        var golemon = DigimonSpeciesBootstrap.bundled(Constants.id("golemon"));
        var punch = golemon.attacks().stream().filter(a -> a.kind() == DigimonAttack.Kind.FIST).toList();
        if (punch.isEmpty()) throw new AssertionError("Golemon has no punch");
        var sheet = new DigimonSpecies(golemon.id(), golemon.stage(), golemon.attribute(), golemon.baseHealth(), golemon.baseAttack(),
                golemon.baseDefence(), golemon.baseSpeed(), golemon.evolutions(), punch, golemon.body(), golemon.locomotion(), golemon.tactics());
        DigimonSpeciesRegistry.replace(sheet);
        return sheet;
    }

    /** Every move on manual: the AI follows the fight, keeps close and dodges, and never casts. */
    private static void manual(DigimonEntity body) {
        for (DigimonAttack move : body.getSpecies().map(DigimonSpecies::attacks).orElse(List.of())) body.setManual(move, true);
    }

    private static void clearRoof(ServerLevel level) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlock(new BlockPos(x, (int) FLOOR + 2, z), Blocks.AIR.defaultBlockState(), 3);
    }

    // --- the checks ----------------------------------------------------------------------------------------------------

    private static void observe(ServerLevel level) {
        ticks++;
        String verdict = switch (CASES.get(index)) {
            case "standing leap" -> standingLeap();
            case "running leap" -> runningLeap();
            case "crouch box" -> crouchBox();
            case "low roof" -> lowRoof(level);
            case "shot over the crouch" -> shotOverCrouch(level);
            case "roll" -> roll();
            case "shot over the roll" -> shotOverRoll(level);
            case "roll held" -> rollHeld();
            case "leap and tuck" -> leapAndTuck();
            case "ai duck" -> aiDuck(level);
            case "ai roll" -> aiRoll(level);
            case "ai wave leap" -> aiWaveLeap();
            case "ai roll dodge" -> aiRollDodge();
            case "ai footwork" -> aiFootwork();
            case "falloff and launch" -> falloffAndLaunch(level);
            default -> "FAIL unknown case";
        };
        if (verdict == null && ticks < CASE_TIMEOUT) return;
        if (verdict == null) verdict = "FAIL timed out after " + ticks + " ticks";
        boolean pass = verdict.startsWith("PASS");
        if (pass) passed++; else failed++;
        String line = verdict.substring(0, 4) + " " + CASES.get(index) + ":" + verdict.substring(4);
        SUMMARY.add(line);
        Constants.LOG.info("[agility] {}", line);
        next(level);
    }

    /** Standing still, a leap straight up: as high as vanilla's flight from the sheet's jump, landing where it left, unhurt. */
    private static String standingLeap() {
        if (mark < 0 && (ticks < 10 || !subject.onGround())) { quiet(subject); hurt(subject); return null; }
        if (mark < 0) {
            from = subject.position();
            if (!subject.leap()) return "FAIL the body would not leap";
            mark = ticks;
            return null;
        }
        quiet(subject);
        if (hurt(subject)) hits++;
        maxRise = Math.max(maxRise, subject.getY() - from.y);
        if (!subject.onGround()) { airTicks++; return null; }
        if (ticks - mark < 3) return null;
        double drift = subject.position().subtract(from).horizontalDistance(), expected = apex(subject.leapPower());
        boolean pass = Math.abs(maxRise - expected) < .3 && drift < .5 && hits == 0;
        return String.format(Locale.ROOT, "%s rose %.2f blocks (vanilla's flight from %.2f: %.2f), drifted %.2f, %d ticks in the air, hurt %d ticks",
                pass ? "PASS" : "FAIL", maxRise, subject.leapPower(), expected, drift, airTicks, hits);
    }

    /** At its run, a leap that keeps the run through the air: long, a little higher, unhurt. */
    private static String runningLeap() {
        if (mark < 0) {
            run(subject);
            hurt(subject);
            if (ticks < 20 || subject.getZ() < -6) return null;
            pace = subject.agility().pace();
            from = subject.position();
            if (!subject.leap()) return "FAIL the body would not leap at its run";
            mark = ticks;
            return null;
        }
        quiet(subject);
        if (hurt(subject)) hits++;
        maxRise = Math.max(maxRise, subject.getY() - from.y);
        if (!subject.onGround() || ticks - mark < 3) {
            airTicks++;
            airSpeed += subject.position().subtract(subject.xo, subject.yo, subject.zo).horizontalDistance();
            return null;
        }
        double length = subject.position().subtract(from).horizontalDistance(), mean = airSpeed / Math.max(1, airTicks);
        double standing = apex(subject.leapPower());
        boolean pass = length >= 3 && mean >= .75 * pace && maxRise >= standing - .1 && hits == 0;
        return String.format(Locale.ROOT, "%s at %.3f blocks a tick: %.2f blocks long, rose %.2f (standing %.2f), kept %.0f%% of its run through %d ticks in the air, hurt %d ticks",
                pass ? "PASS" : "FAIL", pace, length, maxRise, standing, mean / Math.max(1.0E-6, pace) * 100, airTicks, hits);
    }

    /** The crouched box exactly as the sheet has it, and the standing one back. */
    private static String crouchBox() {
        quiet(subject);
        var crouch = tested.body().crouch();
        if (ticks == 5) {
            subject.crouch(true);
            best = subject.getBbHeight();
            worst = subject.getEyeHeight();
            flag = subject.getPose() == Pose.CROUCHING;
            return null;
        }
        if (ticks < 10) return null;
        subject.crouch(false);
        double standing = subject.getBbHeight();
        boolean pass = flag && Math.abs(best - crouch.height()) < 1.0E-4 && Math.abs(worst - crouch.eyeHeight()) < 1.0E-4
                && Math.abs(standing - tested.body().dimensions().height()) < 1.0E-4 && subject.getPose() == Pose.STANDING;
        return String.format(Locale.ROOT, "%s crouched box %.3f (sheet %.3f), eye %.3f (sheet %.3f); standing again %.3f",
                pass ? "PASS" : "FAIL", best, crouch.height(), worst, crouch.eyeHeight(), standing);
    }

    /** Under a roof lower than its standing box the body stays crouched, and stands as soon as the roof is gone. */
    private static String lowRoof(ServerLevel level) {
        quiet(subject);
        if (ticks == 5) subject.crouch(true);
        if (ticks == 6) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            level.setBlock(new BlockPos(x, (int) FLOOR + 2, z), Blocks.STONE.defaultBlockState(), 3);
        if (ticks == 8) subject.crouch(false);
        if (ticks > 8 && ticks <= 30 && subject.getPose() != Pose.CROUCHING) wrongBox++;
        if (ticks == 30) clearRoof(level);
        if (ticks > 30 && mark < 0 && subject.getPose() == Pose.STANDING) mark = ticks;
        if (ticks < 36) return null;
        boolean pass = wrongBox == 0 && mark > 0 && mark - 30 <= 2;
        return String.format(Locale.ROOT, "%s under a roof 2 blocks up it stayed crouched (box %.2f) for 22 ticks after letting go (%d ticks not), stood %d ticks after the roof went",
                pass ? "PASS" : "FAIL", tested.body().crouch().height(), wrongBox, mark < 0 ? -1 : mark - 30);
    }

    /** A level shot at the standing eye strikes the standing body and passes over the crouched one. */
    private static String shotOverCrouch(ServerLevel level) {
        quiet(subject);
        double height = tested.body().crouch().height() + (tested.body().dimensions().height() - tested.body().crouch().height()) * .6;
        if (ticks == 5 || ticks == 45) {
            if (ticks == 45) subject.crouch(true);
            hurt(subject);
            shot = fire(level, other, new Vec3(.5, FLOOR + height, 7.5), new Vec3(0, 0, -1));
        }
        if (ticks > 5 && ticks < 40 && hurt(subject)) hits++;
        if (ticks > 45 && ticks < 80 && hurt(subject)) damaged++;
        if (ticks < 80) return null;
        subject.crouch(false);
        boolean pass = hits > 0 && damaged == 0;
        return String.format(Locale.ROOT, "%s a shot %.2f blocks up struck the standing body (%d ticks hurt) and passed over the crouched one (%d)",
                pass ? "PASS" : "FAIL", height, hits, damaged);
    }

    /** The roll: the run kept, the tucked box only in its window, no second roll, up and running after it. */
    private static String roll() {
        var roll = tested.body().crouch().roll();
        if (roll == null) return "FAIL the sheet has no roll (body.crouch.roll)";
        if (hurt(subject)) hits++;
        if (rollAt < 0) {
            run(subject);
            if (ticks < 20 || subject.getZ() < -7) return null;
            pace = subject.agility().pace();
            if (!subject.roll()) return "FAIL the body would not roll at a run of " + String.format(Locale.ROOT, "%.3f", pace);
            rollAt = ticks;
            return null;
        }
        int k = ticks - rollAt;
        run(subject);
        if (k == 5) secondRoll = subject.roll();
        if (k < roll.ticks()) {
            boolean tucked = roll.tucked(k);
            double want = tucked ? roll.height() : tested.body().dimensions().height();
            if (Math.abs(subject.getBbHeight() - want) > 1.0E-4 || (subject.getPose() == Pose.SPIN_ATTACK) != tucked) wrongBox++;
            airSpeed += subject.position().subtract(subject.xo, subject.yo, subject.zo).horizontalDistance();
            airTicks++;
            return null;
        }
        if (k == roll.ticks()) flag = subject.getPose() == Pose.STANDING && !subject.isRolling();
        if (k < roll.ticks() + 12) return null;
        double mean = airSpeed / Math.max(1, airTicks), after = subject.agility().pace();
        boolean pass = mean >= .85 * pace && wrongBox == 0 && !secondRoll && flag && after >= .8 * pace && hits == 0;
        return String.format(Locale.ROOT, "%s from a run of %.3f it rolled %d ticks at %.0f%% of it, tucked (box %.2f) only on ticks %d-%d (%d ticks wrong), a second roll inside it %s, on its feet after it %s and running again at %.3f",
                pass ? "PASS" : "FAIL", pace, roll.ticks(), mean / Math.max(1.0E-6, pace) * 100, roll.height(), roll.lowFrom(), roll.lowUntil() - 1,
                wrongBox, secondRoll ? "started" : "refused", flag, after);
    }

    /** A shot across the run at a crouched body's height strikes a plain run and passes over a roll's tucked window. */
    private static String shotOverRoll(ServerLevel level) {
        var roll = tested.body().crouch().roll();
        if (roll == null) return "FAIL the sheet has no roll (body.crouch.roll)";
        // Over the tucked box with room for the shot's own thickness, into the standing one.
        double height = roll.height() + .4;
        run(subject);
        boolean struck = hurt(subject);
        if (phase == 0) {
            // A plain run first: the shot crosses its line where it will be in three ticks.
            if (shot == null && ticks >= 20 && subject.getZ() >= -9) {
                double ahead = subject.agility().pace() * 3;
                shot = fire(level, other, new Vec3(subject.getX() + 2.5, FLOOR + height, subject.getZ() + ahead), new Vec3(-1, 0, 0));
                mark = ticks;
            }
            if (struck && shot != null) hits++;
            if (shot == null || ticks - mark < 12) return null;
            // Then back to the start for the roll.
            phase = 1;
            subject.snapTo(-3.5, FLOOR, -12.5, 0, 0);
            shot = null;
            mark = ticks;
            return null;
        }
        if (rollAt < 0) {
            if (ticks - mark < 20 || subject.getZ() < -9) return null;
            if (!subject.roll()) return String.format(Locale.ROOT, "FAIL the body would not roll (pace %.3f from %.3f, on the ground %s, pose %s, in water %s)",
                    subject.agility().pace(), subject.agility().rollFrom(), subject.onGround(), subject.getPose(), subject.isInWater());
            rollAt = ticks;
            return null;
        }
        int k = ticks - rollAt;
        if (k == 4) {
            double ahead = subject.agility().pace() * 3;
            shot = fire(level, other, new Vec3(subject.getX() + 2.5, FLOOR + height, subject.getZ() + ahead), new Vec3(-1, 0, 0));
        }
        if (struck && k > 4) damaged++;
        if (k < roll.ticks() + 4) return null;
        boolean pass = hits > 0 && damaged == 0;
        return String.format(Locale.ROOT, "%s a shot %.2f blocks up across its line struck a plain run (%d ticks hurt) and passed over the roll (%d)",
                pass ? "PASS" : "FAIL", height, hits, damaged);
    }

    /** A crouch held at a run: a roll, crouched outside its tucked window and after it, standing once let go. */
    private static String rollHeld() {
        var roll = tested.body().crouch().roll();
        if (roll == null) return "FAIL the sheet has no roll (body.crouch.roll)";
        run(subject);
        if (rollAt < 0) {
            if (ticks < 20 || subject.getZ() < -7) return null;
            subject.crouch(true);
            if (!subject.isRolling()) return "FAIL a crouch held at a run did not roll";
            rollAt = ticks;
            return null;
        }
        int k = ticks - rollAt;
        if (k < roll.ticks()) {
            if (subject.getPose() != (roll.tucked(k) ? Pose.SPIN_ATTACK : Pose.CROUCHING)) wrongBox++;
            return null;
        }
        if (k == roll.ticks()) flag = subject.getPose() == Pose.CROUCHING && !subject.isRolling();
        if (k == roll.ticks() + 2) subject.crouch(false);
        if (k < roll.ticks() + 4) return null;
        boolean pass = wrongBox == 0 && flag && subject.getPose() == Pose.STANDING;
        return String.format(Locale.ROOT, "%s held at a run it rolled, crouched outside the tucked window (%d ticks wrong), crouched after it %s, standing once let go %s",
                pass ? "PASS" : "FAIL", wrongBox, flag, subject.getPose() == Pose.STANDING);
    }

    /** Run, leap and tuck: the tuck's box in the air, a roll on landing from the running leap, crouched after it while held. */
    private static String leapAndTuck() {
        var roll = tested.body().crouch().roll();
        if (roll == null) return "FAIL the sheet has no roll (body.crouch.roll)";
        if (mark < 0) {
            run(subject);
            if (ticks < 20 || subject.getZ() < -9) return null;
            if (!subject.leap()) return "FAIL the body would not leap at its run";
            mark = ticks;
            return null;
        }
        quiet(subject);
        int k = ticks - mark;
        if (k == 2) subject.crouch(true);
        if (k > 2 && !subject.onGround() && rollAt < 0) {
            airTicks++;
            if (subject.getPose() != Pose.CROUCHING || Math.abs(subject.getBbHeight() - tested.body().crouch().height()) > 1.0E-4) wrongBox++;
        }
        if (k > 2 && rollAt < 0 && subject.isRolling()) rollAt = ticks;
        if (rollAt < 0) return k > 60 ? "FAIL it never rolled on landing" : null;
        int r = ticks - rollAt;
        if (r == roll.ticks() + 1) flag = subject.getPose() == Pose.CROUCHING && !subject.isRolling();
        if (r == roll.ticks() + 2) subject.crouch(false);
        if (r < roll.ticks() + 4) return null;
        boolean pass = airTicks > 0 && wrongBox == 0 && flag && subject.getPose() == Pose.STANDING;
        return String.format(Locale.ROOT, "%s tucked %d ticks in the air (box %.2f, %d ticks wrong), rolled on landing, crouched after it %s, standing once let go %s",
                pass ? "PASS" : "FAIL", airTicks, tested.body().crouch().height(), wrongBox, flag, subject.getPose() == Pose.STANDING);
    }

    /** The AI, standing in its fight, ducks a shot at its head and stands again after it. */
    private static String aiDuck(ServerLevel level) {
        subject.setTarget(other);
        if (ticks == 30) {
            double height = tested.body().crouch().height() + (tested.body().dimensions().height() - tested.body().crouch().height()) * .6;
            shot = fire(level, other, new Vec3(subject.getX(), FLOOR + height, subject.getZ() + 9), new Vec3(0, 0, -1));
        }
        if (ticks > 30 && hurt(subject)) hits++;
        if (ticks > 30 && subject.isLow()) low = true;
        if (ticks > 30 && shot != null && !flag && Math.abs(shot.getZ() - subject.getZ()) < .7) flag = subject.isLow();
        if (ticks < 70) return null;
        int ducks = subject.skillUses().getOrDefault("duck", 0);
        boolean pass = ducks >= 1 && flag && hits == 0 && subject.getPose() == Pose.STANDING;
        return String.format(Locale.ROOT, "%s ducked %d times, low as the shot passed %s, hurt %d ticks, standing again %s",
                pass ? "PASS" : "FAIL", ducks, flag, hits, subject.getPose() == Pose.STANDING);
    }

    /** The AI, running in to its fight, rolls under a shot as it comes, and is on its feet after. */
    private static String aiRoll(ServerLevel level) {
        var roll = tested.body().crouch().roll();
        if (roll == null) return "FAIL the sheet has no roll (body.crouch.roll)";
        subject.setTarget(other);
        // Over the tucked box with room for the shot's own thickness, into the standing one.
        double height = roll.height() + .4;
        if (shot == null && ticks >= 20 && subject.getZ() >= -8) {
            pace = subject.agility().pace();
            shot = fire(level, other, new Vec3(subject.getX(), FLOOR + height, subject.getZ() + 11), new Vec3(0, 0, -1));
            mark = ticks;
        }
        if (shot != null && hurt(subject)) hits++;
        if (shot != null && !flag && Math.abs(shot.getZ() - subject.getZ()) < .7) { flag = true; low = subject.isLow(); }
        if (shot == null || ticks - mark < 40) return null;
        int rolls = subject.skillUses().getOrDefault("roll", 0);
        boolean pass = rolls >= 1 && low && hits == 0 && !subject.isRolling() && subject.getPose() == Pose.STANDING;
        return String.format(Locale.ROOT, "%s at a run of %.3f it rolled %d times, tucked as the shot passed %s, hurt %d ticks, on its feet after %s",
                pass ? "PASS" : "FAIL", pace, rolls, low, hits, subject.getPose() == Pose.STANDING);
    }

    /** The AI leaps up and out of a tectonic wave's line just after its aim locks, and is not struck. */
    private static String aiWaveLeap() {
        subject.setTarget(other);
        other.setTarget(subject);
        boolean struck = hurt(subject);
        hurt(other);
        if (castAt < 0) {
            if (other.isAttacking() && other.getActiveAttack().kind() == DigimonAttack.Kind.GROUND_WAVE) { castAt = ticks; from = subject.position(); }
            return null;
        }
        if (struck) hits++;
        if (!subject.onGround()) { airTicks++; maxRise = Math.max(maxRise, subject.getY() - from.y); }
        if (ticks - castAt < 90) return null;
        int leaps = subject.skillUses().getOrDefault("leap_dodge", 0);
        double aside = subject.position().subtract(from).horizontalDistance();
        boolean pass = leaps >= 1 && airTicks > 0 && hits == 0;
        return String.format(Locale.ROOT, "%s leapt %d times clear of the wave (%d ticks in the air, rose %.2f, landed %.2f blocks from where it stood), struck %d ticks",
                pass ? "PASS" : "FAIL", leaps, airTicks, maxRise, aside, hits);
    }

    /**
     * The AI rolls away from Golemon's punch (no duck escapes a fist at the chest): the punch it saw coming misses, the body
     * faces its roll's heading as it rolls (the clip rolls forward), and it is on its feet after.
     */
    private static String aiRollDodge() {
        subject.setTarget(other);
        other.setTarget(subject);
        boolean struck = hurt(subject);
        hurt(other);
        if (castAt < 0 && other.isAttacking()) { castAt = ticks; from = subject.position(); }
        if (castAt >= 0 && ticks - castAt <= 22 && struck) hits++;
        if (subject.isRolling()) {
            Vec3 moved = subject.agility().lastMove();
            if (moved.horizontalDistance() > .05) {
                float travel = (float) (Math.atan2(moved.z, moved.x) * 180 / Math.PI) - 90;
                worst = Math.max(worst, Math.abs(net.minecraft.util.Mth.wrapDegrees(travel - subject.getYRot())));
            }
            airTicks++;
        }
        if (castAt < 0 || ticks - castAt < 60) return castAt < 0 && ticks > 200 ? "FAIL Golemon never punched" : null;
        // Golemon stops; the roll under way runs out before the verdict.
        other.setNoAi(true);
        if (subject.isRolling() && ticks - castAt < 100) return null;
        int rolls = subject.skillUses().getOrDefault("roll_dodge", 0);
        double away = subject.position().subtract(other.position()).horizontalDistance();
        boolean pass = rolls >= 1 && hits == 0 && worst < 12 && !subject.isRolling() && subject.getPose() == Pose.STANDING;
        return String.format(Locale.ROOT, "%s rolled away %d times (%d ticks rolling, facing its heading within %.1f degrees), struck by the punch %d ticks, %.2f blocks off after, on its feet %s",
                pass ? "PASS" : "FAIL", rolls, airTicks, worst, hits, away, subject.getPose() == Pose.STANDING);
    }

    /**
     * The AI's footwork: from too close it backs off facing the enemy (backward steps), then circles it in its band facing it
     * (side steps), crouched as it stalks; let off the leash (a move it may use), it stands and goes in.
     */
    private static String aiFootwork() {
        subject.setTarget(other);
        Vec3 toOther = other.position().subtract(subject.position()).multiply(1, 0, 1);
        double distance = toOther.horizontalDistance();
        float facingTarget = (float) (Math.atan2(toOther.z, toOther.x) * 180 / Math.PI) - 90;
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(facingTarget - subject.getYRot()));
        Vec3 moved = subject.agility().lastMove();
        double back = toOther.lengthSqr() < 1.0E-6 ? 0 : -moved.dot(toOther.normalize());
        if (ticks <= 60) {
            // Backing off: going away from it, its face still to it.
            if (back > .02) { readings.add((double) off); best = Math.max(best, distance); }
            return null;
        }
        if (ticks <= 220) {
            // Circling: the angle round the enemy changes, the face stays on it, and the body is low.
            double angle = Math.atan2(-toOther.z, -toOther.x);
            if (last != null) maxRise += Math.abs(net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(angle - last.x)));
            last = new Vec3(angle, 0, 0);
            if (moved.horizontalDistance() > .01) readings2.add((double) off);
            if (subject.isLow()) phase++;
            if (distance < 3.0 || distance > 7.5) wrongBox++;
            if (ticks == 220) for (DigimonAttack move : subject.getSpecies().map(DigimonSpecies::attacks).orElse(List.of())) subject.setManual(move, false);
            return null;
        }
        // Off the leash: up from the stalk within a few ticks.
        if (mark < 0 && subject.getPose() == Pose.STANDING) mark = ticks;
        if (ticks < 240) return null;
        double backOff = readings.stream().mapToDouble(Double::doubleValue).max().orElse(999);
        double circleOff = readings2.stream().mapToDouble(Double::doubleValue).max().orElse(999);
        int stalks = subject.skillUses().getOrDefault("stalk", 0);
        boolean pass = !readings.isEmpty() && backOff < 35 && best >= 3.2 && readings2.size() > 30 && circleOff < 40 && maxRise >= 30
                && phase > 60 && stalks >= 1 && wrongBox < 10 && mark >= 0 && mark - 220 <= 6;
        return String.format(Locale.ROOT, "%s backed off %d ticks to %.2f blocks facing it within %.1f degrees; circled %.0f degrees round it in %d stepping ticks facing it within %.1f (%d ticks low, %d stalks, %d ticks off the band); stood %d ticks after the leash came off",
                pass ? "PASS" : "FAIL", readings.size(), best, backOff, maxRise, readings2.size(), circleOff, phase, stalks, wrongBox, mark < 0 ? -1 : mark - 220);
    }

    /**
     * Three shots from near the dummy and three from far: the far ones deal less (the least of each, so a critical hit never
     * decides it) and throw it a shorter way; the near ones throw it well up and away (the launch).
     */
    private static String falloffAndLaunch(ServerLevel level) {
        double[] distances = {2.5, 10};
        int volley = 6, every = 40;
        int n = (ticks - 10) / every, within = (ticks - 10) % every;
        if (ticks < 10) { hurt(dummy); return null; }
        if (n >= volley) {
            double nearDamage = readings.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double farDamage = readings2.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double nearFlight = readings3.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double farFlight = readings4.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double expectPower = FALLOFF.power(distances[1] - .3), expectPush = FALLOFF.knockback(distances[1] - .3);
            boolean pass = readings.size() == 3 && readings2.size() == 3 && farDamage < nearDamage && Math.abs(farDamage / nearDamage - expectPower) < .08
                    && farFlight < nearFlight && nearFlight >= 4 && maxRise >= .6;
            return String.format(Locale.ROOT, "%s near (%.1f blocks): damage %.2f, thrown %.2f blocks, up %.2f; far (%.1f): damage %.2f (%.2f of near, falloff %.2f), thrown %.2f blocks (%.2f of near, push %.2f)",
                    pass ? "PASS" : "FAIL", distances[0], nearDamage, nearFlight, maxRise, distances[1], farDamage, farDamage / Math.max(1.0E-6, nearDamage),
                    expectPower, farFlight, farFlight / Math.max(1.0E-6, nearFlight), expectPush);
        }
        double distance = distances[n % 2];
        if (within == 0) {
            // The dummy back on its spot, the shooter behind the muzzle (a shot pushes away from its owner).
            dummy.snapTo(.5, FLOOR, .5, 180, 0);
            dummy.setDeltaMovement(Vec3.ZERO);
            dummy.getNavigation().stop();
            hurt(dummy);
            other.snapTo(.5, FLOOR, .5 - distance - 1.2, 0, 0);
            shot = fire(level, other, new Vec3(.5, FLOOR + .9, .5 - distance), new Vec3(0, 0, 1));
            flag = false;
            from = null;
            last = dummy.position();
            return null;
        }
        float lost = dummy.getMaxHealth() - dummy.getHealth();
        if (lost > 1.0E-3 && from == null) {
            // Thrown from where it stood the tick before the shot struck (it may already be on its way).
            (n % 2 == 0 ? readings : readings2).add((double) lost);
            dummy.setHealth(dummy.getMaxHealth());
            from = last;
        }
        if (from == null) last = dummy.position();
        if (from != null && !flag) {
            if (n % 2 == 0) maxRise = Math.max(maxRise, dummy.getY() - FLOOR);
            if (dummy.onGround() && dummy.getY() - FLOOR < .01 && dummy.position().subtract(from).horizontalDistance() > .05) {
                flag = true;
                (n % 2 == 0 ? readings3 : readings4).add(dummy.position().subtract(from).horizontalDistance());
            }
        }
        dummy.getNavigation().stop();
        return null;
    }

    private static void finish(ServerLevel level) {
        done = true;
        if (shotBefore != null) KineticAttacks.replace(shotBefore);
        if (tested != null) DigimonSpeciesRegistry.replace(DigimonSpeciesBootstrap.bundled(tested.id()));
        DigimonSpeciesRegistry.replace(DigimonSpeciesBootstrap.bundled(Constants.id("golemon")));
        for (String line : SUMMARY) Constants.LOG.info("[agility-checks] {}", line);
        Constants.LOG.info("[agility-checks] RESULT {} passed={} failed={}", failed == 0 && passed > 0 ? "PASS" : "FAIL", passed, failed);
        level.getServer().halt(false);
    }
}
