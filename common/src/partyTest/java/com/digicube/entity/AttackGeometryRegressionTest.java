package com.digicube.entity;

import com.digicube.digimon.DigimonSpeciesBootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Real authored contact curves against stationary cows, changes of elevation and solid cover. */
public final class AttackGeometryRegressionTest {
    private AttackGeometryRegressionTest() {}

    public static void run() {
        AABB cow = new AABB(-.45, 0, 2.55, .45, 1.4, 3.45);
        var floor = new AABB(-40, -2, -40, 40, 0, 40);
        var ledge = new AABB(-2, 0, -4, 2, 1, 1.2);
        for (var move : List.of(DigimonSpeciesBootstrap.BLUE_BLASTER)) {
            for (float yaw : new float[]{0, 90, 180, 270}) {
                for (double elevation : new double[]{-1, 0, 1}) {
                    Vec3 feet = new Vec3(0, elevation, 0);
                    Vec3 center = new Vec3(0, 0, 4).yRot((float) Math.toRadians(-yaw));
                    AABB target = new AABB(center.x - .45, 0, center.z - .45, center.x + .45, 1.4, center.z + .45);
                    check(AttackGeometry.streamAim(move, move.hitTick(), feet, target, yaw, (a, b) -> b) != null,
                            move.id() + " can aim at a cow at elevation " + elevation + " yaw " + yaw);
                }
            }
            for (int tick = move.motion().activeFrom(); tick <= move.motion().activeUntil(); tick++) {
                check(AttackGeometry.streamAim(move, tick, new Vec3(0, 1, 0), cow.move(0, 0, 1), 0,
                        (a, b) -> clip(a, b, List.of(floor, ledge))) != null,
                        move.id() + " keeps a usable jet on a cow below a ledge, tick " + tick);
            }
            var wall = new AABB(-5, 0, 2.6, 5, 6, 2.9);
            check(AttackGeometry.streamAim(move, move.hitTick(), new Vec3(0, 1, 0), cow.move(0, 0, 2), 0,
                    (a, b) -> clip(a, b, List.of(floor, wall))) == null, "Solid cover requires repositioning");
        }
        var horn = DigimonSpeciesBootstrap.HORN_ATTACK;
        check(AttackGeometry.canContact(horn, Vec3.ZERO, .95, 1.45,
                new AABB(-.45, 0, 1.35, .45, 1.4, 2.25), (a, b) -> true, box -> true, p -> true),
                "Gabumon's existing horn still reaches an easy cow");
    }

    private static Vec3 clip(Vec3 from, Vec3 to, List<AABB> blocks) {
        Vec3 nearest = to;
        for (var block : blocks) {
            if (block.contains(from)) return from;
            Vec3 hit = block.clip(from, to).orElse(to);
            if (from.distanceToSqr(hit) < from.distanceToSqr(nearest)) nearest = hit;
        }
        return nearest;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
