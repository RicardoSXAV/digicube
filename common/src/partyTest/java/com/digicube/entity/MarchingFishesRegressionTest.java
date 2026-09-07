package com.digicube.entity;

import com.digicube.digimon.DigimonSpeciesBootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Regressions for broad collision, ground clearance, terrain ordering and bounded homing. */
public final class MarchingFishesRegressionTest {
    private MarchingFishesRegressionTest() {}

    public static void run() {
        Vec3 from = new Vec3(0, 0.69, 0), to = from.add(0, 0, 0.34);
        AABB offAxis = new AABB(.9, 0, 1.2, 1.4, 1.8, 1.7);
        check(offAxis.clip(from, to).isEmpty(), "the thin vanilla ray misses the edge target");
        Vec3 hit = MarchingFishesFlight.contact(from, to, offAxis, 1.1, .625);
        check(hit != null, "the visible wave width catches an edge target");
        AABB floor = new AABB(-5, -1, -5, 5, 0, 5);
        check(MarchingFishesFlight.contact(from, to, floor, 1.1, .625) == null,
                "a level wave clears the floor");
        AABB sideWall = new AABB(1, 0, -1, 2, 3, 2);
        check(MarchingFishesFlight.contact(from, to, sideWall, 1.1, .625).equals(from),
                "a wall touching the wave side blocks it even when its centre ray is clear");
        AABB wall = new AABB(-2, 0, 1.2, 2, 3, 1.4);
        Vec3 wallHit = MarchingFishesFlight.contact(from, to, wall, 1.1, .625);
        Vec3 beyond = MarchingFishesFlight.contact(from, to, new AABB(-.3,0,1.6,.3,2,2),1.1,.625);
        check(wallHit != null && beyond == null, "terrain precedes targets behind it");
        Vec3 velocity = new Vec3(0,0,MarchingFishesEntity.SPEED);
        Vec3 steered = MarchingFishesFlight.steer(velocity,new Vec3(5,0,1));
        check(Math.abs(steered.length()-velocity.length())<1e-8, "homing preserves speed");
        double turn = Math.toDegrees(Math.acos(velocity.normalize().dot(steered.normalize())));
        check(Math.abs(turn-5)<1e-5, "the wave turns smoothly at five degrees per tick");
        check(MarchingFishesFlight.steer(velocity,new Vec3(0,0,-4)).equals(velocity),
                "a passed enemy does not pull the wave into a U-turn");
        check(MarchingFishesFlight.steer(Vec3.ZERO,new Vec3(4,0,0)).equals(Vec3.ZERO), "zero velocity stays finite");
        var wave = DigimonSpeciesBootstrap.MARCHING_FISHES;
        var claw = DigimonSpeciesBootstrap.CLAW_ATTACK;
        check(wave.power() < claw.power() && wave.knockback() >= 1.2 && claw.cooldownTicks() <= 24,
                "the wave trades raw damage for knockback and the claw is the frequent fallback");
        check(wave.motion().activeFrom() == wave.hitTick() && wave.motion().sample(wave.hitTick()).mouth().y > .625,
                "the exported release is synchronized and clears ground");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
