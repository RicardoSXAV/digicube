package com.digicube.entity;

import com.digicube.digimon.DigimonSpeciesBootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** World-space aim and plume-volume checks, without launching or driving a game. */
public final class FlameStreamRegressionTest {
    private FlameStreamRegressionTest() {}

    /** Run after the bundled species have been registered. */
    public static void run() {
        for (var move : java.util.List.of(DigimonSpeciesBootstrap.BLUE_BLASTER, DigimonSpeciesBootstrap.HOWLING_BLASTER)) {
            var frame = move.motion().sample(30);
            for (float yaw : new float[]{0, 90, 180, 270}) {
                for (double distance : new double[]{move == DigimonSpeciesBootstrap.HOWLING_BLASTER ? 2.9 : 2, 6, 10}) {
                    for (double height : new double[]{0.3, 1, 3}) {
                        Vec3 target = new Vec3(0, height, distance).yRot((float) Math.toRadians(-yaw));
                        float pitch = FlameStream.aimPitch(frame, Vec3.ZERO, target, yaw, 0);
                        Vec3 mouth = frame.aimedMouth(pitch).yRot((float) Math.toRadians(-yaw));
                        Vec3 ray = FlameStream.direction(frame, pitch, yaw);
                        check(ray.dot(target.subtract(mouth).normalize()) > .9999,
                                move.id() + " aims from its mouth at yaw " + yaw + ", distance " + distance + ", height " + height);
                    }
                }
            }
        }
        var authored = DigimonSpeciesBootstrap.HOWLING_BLASTER.motion().sample(30);
        var blended = new com.digicube.digimon.AttackMotion.Frame(authored.travel(), authored.head(), authored.mouth(),
                authored.hornBase(), authored.hornTip(), authored.headPitch(), .5F);
        Vec3 lowCow = new Vec3(0, .3, 6);
        float pitch = FlameStream.aimPitch(blended, Vec3.ZERO, lowCow, 0, 0);
        check(FlameStream.direction(blended, pitch, 0).dot(lowCow.subtract(blended.aimedMouth(pitch)).normalize()) > .9999,
                "The solver includes the authored aim blend, rather than under-aiming during transitions");
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
