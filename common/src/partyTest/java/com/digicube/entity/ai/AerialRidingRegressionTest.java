package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import net.minecraft.world.phys.Vec3;

/** Handling bounds: acceleration, braking, diagonals, pitch and stamina. */
public final class AerialRidingRegressionTest {
    private AerialRidingRegressionTest() {}
    public static void run() {
        var species=DigimonSpeciesRegistry.getOrThrow(Constants.id("kabuterimon"));
        var p=species.body().mount().orElseThrow().flight();
        check(species.locomotion().canFly() && p!=null,"species explicitly grants aerial riding");
        var forward=AerialHandling.target(p,1,0,0,0,AerialInput.NONE);
        var diagonal=AerialHandling.target(p,1,1,0,0,AerialInput.NONE);
        check(forward.length()<=p.cruiseSpeed()+1e-8 && diagonal.length()<=p.cruiseSpeed()+1e-8,"diagonals cannot exceed cruise");
        Vec3 v=Vec3.ZERO;
        for(int i=0;i<80;i++) {
            Vec3 next=AerialHandling.step(p,v,forward,false);
            check(next.subtract(v).length()<=p.acceleration()+1e-8,"bounded acceleration");v=next;
        }
        check(v.distanceTo(forward)<1e-8,"reaches cruise exactly");
        double stopDistance=0;
        for(int i=0;i<8;i++){v=AerialHandling.step(p,v,Vec3.ZERO,true);stopDistance+=v.length();}
        check(v.equals(Vec3.ZERO) && stopDistance<2,"air brake stops within eight ticks and two blocks");
        var up=new AerialInput(true,false);
        check(AerialHandling.target(p,0,0,0,0,up).y==p.climbSpeed(),"vertical ascent without forward movement");
        check(AerialHandling.target(p,1,0,0,75,AerialInput.NONE).y<0,"look down and forward descends");
        check(AerialHandling.target(p,1,1,0,0,up).length()<=p.cruiseSpeed()+1e-8,"climbing diagonally stays bounded");
        check(AerialHandling.target(p,0,0,0,70,AerialInput.NONE).equals(Vec3.ZERO),"looking alone preserves hover");
        check(AerialHandling.target(p,1,0,0,-70,AerialInput.NONE).y>0,"look-forward climb");
        check(AerialHandling.target(p,1,0,90,0,AerialInput.NONE).x<0,"yaw transforms control direction");
        momentum(p);
        for(int bits=0;bits<4;bits++)check(AerialInput.fromBits(bits).bits()==bits,"button codec roundtrip");
        var tank=new com.digicube.digimon.FlightReserve(species.locomotion().flight());
        for(int i=0;i<2400;i++)tank.consume();
        check(tank.exhausted() && !tank.ready(),"empty reserve cannot authorize launch");
    }

    private static void momentum(com.digicube.digimon.AerialMount p) {
        Vec3 cruise = AerialHandling.target(p, 1, 0, 0, 0, AerialInput.NONE);
        Vec3 steep = AerialHandling.target(p, 1, 0, 0, 70, AerialInput.NONE);
        Vec3 shallow = AerialHandling.target(p, 1, 0, 0, 20, AerialInput.NONE);
        Vec3 fast = cruise, gentle = cruise;
        for (int tick = 0; tick < 100; tick++) {
            fast = AerialHandling.flightStep(p, fast, steep, true);
            gentle = AerialHandling.flightStep(p, gentle, shallow, true);
            check(fast.isFinite() && fast.length() <= p.dive().maxSpeed() + 1e-8, "dive has a finite terminal speed");
            if (tick == 1) check(fast.length() < p.cruiseSpeed() + .05, "looking down cannot instantly grant dive speed");
        }
        check(fast.length() > p.cruiseSpeed() * 1.6, "sustained steep descent gains meaningful speed");
        check(fast.length() > gentle.length() + .2, "steep dives earn more than shallow descents");
        check(-fast.y > fast.horizontalDistance() * 2, "a steep dive follows the requested angle");
        check(AerialHandling.flightPitch(fast) > 45, "the model visibly pitches nose down in a dive");
        Vec3 pullUp = fast;
        for (int tick = 0; tick < 22; tick++) pullUp = AerialHandling.flightStep(p, pullUp, cruise, false);
        check(pullUp.length() > p.cruiseSpeed() + .06, "a smooth pull-up carries earned momentum into level flight");
        check(Math.abs(pullUp.y) < .01, "pull-up eventually reaches level flight");
        Vec3 level = pullUp, climb = pullUp, turn = pullUp;
        Vec3 up = AerialHandling.target(p, 1, 0, 0, -60, AerialInput.NONE);
        Vec3 reverse = AerialHandling.target(p, 1, 0, 180, 0, AerialInput.NONE);
        for (int tick = 0; tick < 12; tick++) {
            level = AerialHandling.flightStep(p, level, cruise, false);
            climb = AerialHandling.flightStep(p, climb, up, false);
            turn = AerialHandling.flightStep(p, turn, reverse, false);
        }
        check(climb.length() < level.length() && turn.length() < level.length(), "climbs and hard turns spend momentum");
        check(AerialHandling.flightPitch(climb) < 0, "climbing pitches the model back up");
        for (int tick = 0; tick < 160; tick++) level = AerialHandling.flightStep(p, level, cruise, false);
        check(Math.abs(level.length() - p.cruiseSpeed()) < 1e-8, "carry speed settles back to powered cruise");
        Vec3 stop = fast;
        double stoppingDistance = 0;
        for (int tick = 0; tick < 12; tick++) {
            stop = AerialHandling.flightStep(p, stop, Vec3.ZERO, false);
            stoppingDistance += stop.length();
        }
        check(stop.equals(Vec3.ZERO) && stoppingDistance < 5, "release stops even a terminal dive within five blocks");
        check(AerialHandling.flightPitch(Vec3.ZERO) == 0, "hover clears the dive attitude");
        Vec3 diagonal = cruise;
        for (int tick = 0; tick < 200; tick++) diagonal = AerialHandling.flightStep(p, diagonal,
                AerialHandling.target(p, 1, 1, 0, 80, AerialInput.NONE), true);
        check(diagonal.length() <= p.dive().maxSpeed() + 1e-8, "diagonal dives cannot bypass terminal speed");
        check(AerialHandling.landingDistance(p, fast) > 8, "fast descent starts automatic braking sooner");
        for (int pitch : new int[]{20, 45, 70, 80}) landing(p, pitch);
        var steady = new com.digicube.digimon.AerialMount(p.cruiseSpeed(), p.acceleration(), p.braking(),
                p.climbSpeed(), p.descendSpeed(), p.turnDegrees(), p.takeoffTicks(), p.liftTick(),
                p.landingTicks(), p.wingLoopTicks(), null);
        Vec3 limited = Vec3.ZERO;
        for (int tick = 0; tick < 200; tick++) limited = AerialHandling.flightStep(steady, limited,
                AerialHandling.target(steady, 1, 0, 0, 70, AerialInput.NONE), true);
        check(limited.length() <= p.cruiseSpeed() && -limited.y <= p.descendSpeed(), "species can retain steady flight without dive tuning");
    }

    private static void landing(com.digicube.digimon.AerialMount p, int pitch) {
        Vec3 target = AerialHandling.target(p, 1, 0, 0, pitch, AerialInput.NONE);
        Vec3 velocity = target.normalize().scale(p.dive().maxSpeed());
        double height = 25;
        int nearTicks = 0;
        boolean approach = false;
        for (int tick = 0; tick < 250 && height > 0; tick++) {
            nearTicks = height < AerialHandling.landingDistance(p, velocity) ? nearTicks + 1 : 0;
            approach |= nearTicks >= 3;
            if (approach) {
                double down = Math.min(p.descendSpeed(), .07 + Math.min(3, height) * .12);
                velocity = AerialHandling.step(p, velocity, new Vec3(target.x * .3, -down, target.z * .3), true);
            } else velocity = AerialHandling.flightStep(p, velocity, target, true);
            height += velocity.y;
            if (height <= 0) check(approach && -velocity.y < .15, "automatic landing slows before contact from " + pitch + " degrees");
        }
        check(height <= 0, "automatic approach reaches the floor");
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
