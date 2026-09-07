package com.digicube.entity;

import com.digicube.digimon.DigimonSpeciesBootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** World-space aim and plume-volume checks, without launching or driving a game. */
public final class FlameStreamRegressionTest {
    private FlameStreamRegressionTest() {}

    /** Run after the bundled species have been registered. */
    public static void run() {
        var frame = DigimonSpeciesBootstrap.BLUE_BLASTER.motion().sample(30);
        Vec3[] targets = {new Vec3(0, 1, 6), new Vec3(-6, 1, 0),
                new Vec3(0, 1, -6), new Vec3(6, 1, 0)};
        for (int i = 0; i < targets.length; i++) {
            float yaw = i * 90;
            for (double height : new double[]{0.3, 1, 3}) {
                Vec3 target = new Vec3(targets[i].x, height, targets[i].z);
                float pitch = FlameStream.aimPitch(frame, Vec3.ZERO, target, yaw, 0);
                Vec3 mouth = frame.aimedMouth(pitch).yRot((float) Math.toRadians(-yaw));
                Vec3 ray = FlameStream.direction(frame, pitch, yaw);
                check(ray.dot(target.subtract(mouth).normalize()) > .9999,
                        "Flame must aim at target in every cardinal direction and at different heights");
            }
        }
        var jet = new FlameStream(Vec3.ZERO, new Vec3(0, 0, 8), .18);
        check(jet.intersects(new AABB(.55, -.1, 7, .65, .1, 7.2)), "Widened distal plume reaches fringe");
        check(!jet.intersects(new AABB(.75, -.1, .3, .85, .1, .4)), "Mouth jet remains narrow");
        check(!jet.intersects(new AABB(1.5, -.1, 7, 1.6, .1, 7.2)), "Outside plume misses");
        check(!jet.intersects(new AABB(-.1, -.1, -1, .1, .1, -.8)), "Cannot hit behind the mouth");
        check(!new FlameStream(Vec3.ZERO, Vec3.ZERO, .18).intersects(new AABB(-1, -1, -1, 1, 1, 1)),
                "Completely blocked throat cannot deal damage");
        check(FlameStream.flowDistance(0) == 0 && FlameStream.flowDistance(-1) == 0, "No advance before emission");
        double previousSpeed = Double.POSITIVE_INFINITY;
        for (int t = 0; t < 12; t++) {
            double speed = FlameStream.flowDistance(t + 1) - FlameStream.flowDistance(t);
            check(speed > 0 && speed < previousSpeed, "Flames move forward and slow downstream");
            previousSpeed = speed;
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
