package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.KineticAttacks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Contracts that must survive data reduction, translation and asymmetric aiming. */
public final class KineticRegressionTest {
    private KineticRegressionTest() {}
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void run() {
        var cannon = KineticAttacks.get(Constants.id("hunting_cannon"));
        var dash = KineticAttacks.get(Constants.id("jet_dash"));
        check(cannon.attack().hitTick() == 19 && cannon.duration(false) == 50, "Complete cannon and recoil clock");
        check(dash.duration(false) == 20 && dash.duration(true) == 25 && dash.decisionTick() == 12, "Fast escape and connected kick continuation");
        for (double tick = 0; tick <= dash.decisionTick(); tick += 1.0 / 12) {
            var clear = dash.motion().sample(tick); var kick = dash.kickMotion().sample(tick);
            check(clear.offset().distanceTo(kick.offset()) < .00001 && Math.abs(clear.yaw() - kick.yaw()) < .001, "Dash outcome cannot restart or jump root motion");
        }
        for (int heading = 0; heading < 360; heading += 45) for (double range : new double[]{2.5, 3, 5, 8, 13})
            for (double elevation : new double[]{-1, 0, 1}) {
                Vec3 feet = new Vec3(31.125, 80, -22.375);
                Vec3 target = AttackGeometry.world(feet, new Vec3(0, elevation + .8, range), heading);
                var aim = KineticGeometry.aim(cannon, feet, target);
                double distance = target.subtract(aim.muzzle()).cross(aim.direction()).length();
                check(distance < .005, "Cannon misses its bounded aim point at " + range + ": " + distance);
            }
        // The stepped bolt is a cross, not its rectangular broad-phase corners.
        Vec3 empty = new Vec3(.21, .21, 0);
        AABB probe = new AABB(empty.subtract(.005, .005, .005), empty.add(.005, .005, .005));
        check(cannon.projectileBoxes().stream().anyMatch(b -> b.bounds().inflate(.1).intersects(probe)), "Probe near visible projectile");
        check(cannon.projectileBoxes().stream().noneMatch(b -> b.intersects(probe)), "Empty projectile corner must not hurt");
        for (var frame : dash.kickMotion().frames()) for (var box : frame.hooves()) {
            check(Double.isFinite(box.center().lengthSqr()) && box.bounds().getSize() < 1, "Hoof damage excludes broad body envelope");
        }
        check(!dash.matches("claw") && dash.matches("jet_dash_kick"), "Kick continuation resolves to the same gameplay move");
        Constants.LOG.info("Kinetic regression checks passed: clocks, conditional root continuity, eight-heading near/far/elevated aiming and empty visual corners.");
    }
}
