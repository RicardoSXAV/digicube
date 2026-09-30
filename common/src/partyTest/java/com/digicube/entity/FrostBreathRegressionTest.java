package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.BreathAttacks;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Howling Blaster's puffs in flight: reach in still air, floors and walls they wash along (a wall met head-on splashes
 * them over it), a sweep and a runner's trail, flames that point along the aim.
 */
public final class FrostBreathRegressionTest {
    private FrostBreathRegressionTest() {}

    /** Run after the bundled species have been registered. */
    public static void run() {
        var spec = BreathAttacks.get(DigimonSpeciesBootstrap.attacks().get(Constants.id("howling_blaster")));
        Vec3 mouth = new Vec3(0, 1.5, 0), ahead = new Vec3(0, 0, 1);

        // Still air: a single shed flies its reach and dies at the end of its life.
        var breath = new FrostBreath(spec);
        breath.emit(mouth, ahead, Vec3.ZERO, RandomSource.create(1));
        double furthest = 0;
        for (int tick = 0; tick < spec.life(); tick++) {
            breath.step(null);
            for (var puff : breath.puffs()) furthest = Math.max(furthest, puff.z);
        }
        check(furthest > spec.reach() * .85 && furthest < spec.reach() * 1.1, "a puff flies its reach: " + furthest + " of " + spec.reach());
        check(breath.isEmpty(), "every puff is gone at the end of its life");

        // Aimed down at a floor, the frost washes along it and never passes through it.
        var floor = level(List.of(new AABB(-40, -2, -40, 40, 0, 40)));
        breath = new FrostBreath(spec);
        Vec3 down = new Vec3(0, -.5, 1).normalize();
        boolean slid = false;
        for (int tick = 0; tick < spec.life(); tick++) {
            if (tick < 6) breath.emit(mouth, down, Vec3.ZERO, RandomSource.create(tick));
            breath.step(floor);
            for (var puff : breath.puffs()) {
                check(puff.y >= -1.0E-6, "no puff passes under the floor: " + puff.y);
                slid |= puff.struck && puff.vz > .1 && Math.abs(puff.vy) < .2;
            }
        }
        check(slid, "puffs that meet the floor slide on along it");

        // A wall stops the stream, and the puffs that meet it spread along it.
        var wall = level(List.of(new AABB(-40, -2, 4, 40, 10, 5)));
        breath = new FrostBreath(spec);
        boolean struckWall = false, splashed = false;
        for (int tick = 0; tick < spec.life(); tick++) {
            if (tick < 6) breath.emit(mouth, ahead, Vec3.ZERO, RandomSource.create(tick));
            breath.step(wall);
            for (var puff : breath.puffs()) {
                check(puff.z <= 4 + 1.0E-6, "no puff passes through the wall: " + puff.z);
                if (puff.normal != null) struckWall |= puff.normal.z < -.99;
                splashed |= puff.struck && Math.hypot(puff.vx, puff.vy) > .1 && puff.surfaceZ < -.99F && Math.abs(puff.lookZ) < .1F;
            }
        }
        check(struckWall, "the wall's face is what the puffs strike");
        check(splashed, "met head-on, the wall splashes the puffs out over it, their flames lying on it");

        // A cow ahead is touched; one well off to the side of a straight breath is not.
        breath = new FrostBreath(spec);
        AABB cow = new AABB(-.45, 0, 5.55, .45, 1.4, 6.45), aside = cow.move(4, 0, 0);
        boolean touched = false, strayed = false;
        for (int tick = 0; tick < 12; tick++) {
            breath.emit(mouth, ahead, Vec3.ZERO, RandomSource.create(tick));
            breath.step(null);
            touched |= breath.touches(cow);
            strayed |= breath.touches(aside);
        }
        check(touched && !strayed, "the breath touches what it is aimed at and not what stands aside");

        // A swept aim bends the train like water from a hose: the newest puffs follow the aim, the oldest keep their way.
        breath = new FrostBreath(spec);
        for (int tick = 0; tick <= 10; tick++) {
            double yaw = Math.toRadians(9 * tick);
            breath.emit(mouth, new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), Vec3.ZERO, RandomSource.create(tick));
            breath.step(null);
        }
        var puffs = breath.puffs();
        var oldest = puffs.get(0).velocity().normalize();
        var newest = puffs.get(puffs.size() - 1).velocity().normalize();
        check(oldest.z > .95 && newest.x < -.9, "the oldest puffs fly where the aim was, the newest where it is");
        double gap = 0;
        for (int i = 1; i < puffs.size(); i++) gap = Math.max(gap, puffs.get(i).position().distanceTo(puffs.get(i - 1).position()));
        check(gap < 2.5, "a fast sweep leaves a whole curve, not a row of far-apart dots: " + gap);

        // A running breather trails its breath: each puff leaves with the body's motion added.
        breath = new FrostBreath(spec);
        breath.emit(mouth, ahead, new Vec3(.6, 0, 0), RandomSource.create(7));
        for (var puff : breath.puffs()) check(puff.vx > .45, "a runner's motion rides in its breath: " + puff.vx);

        // A steady breath's flames point along the aim, whatever each puff's own scatter.
        breath = new FrostBreath(spec);
        breath.emit(mouth, ahead, Vec3.ZERO, RandomSource.create(11));
        for (var puff : breath.puffs()) check(puff.lookZ > .999F, "a puff's flame points along the aim: " + puff.lookZ);

        // Puffs widen as they age, and more once they have struck something.
        var fresh = new FrostBreath(spec);
        fresh.emit(mouth, ahead, Vec3.ZERO, RandomSource.create(3));
        var puff = fresh.puffs().get(0);
        float young = fresh.radius(puff);
        fresh.step(null);
        float older = fresh.radius(puff);
        puff.struck = true;
        check(older > young && fresh.radius(puff) > older, "a puff widens with age and more once it has struck");
        Constants.LOG.info("Frost breath checks passed: reach {} blocks, floors and walls, a touched cow, a swept hose, a runner's trail.",
                String.format(java.util.Locale.ROOT, "%.1f", furthest));
    }

    /** A level of solid boxes and nothing else. */
    private static ServerLevel level(List<AABB> boxes) {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            var singleton = unsafe.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var level = (BoxLevel) unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), BoxLevel.class);
            var field = BoxLevel.class.getDeclaredField("boxes");
            field.setAccessible(true);
            field.set(level, boxes);
            return level;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build the offline level", e);
        }
    }

    private static final class BoxLevel extends ServerLevel {
        private List<AABB> boxes;
        private BoxLevel() { super(null, null, null, null, null, null, false, 0, List.of(), false); }
        @Override public BlockHitResult clip(ClipContext context) {
            Vec3 from = context.getFrom(), to = context.getTo();
            BlockHitResult hit = AABB.clip(boxes, from, to, BlockPos.ZERO);
            return hit != null ? hit : BlockHitResult.miss(to, Direction.UP, BlockPos.containing(to));
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
