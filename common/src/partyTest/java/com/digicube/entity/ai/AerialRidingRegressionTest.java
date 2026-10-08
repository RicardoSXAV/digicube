package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import net.minecraft.world.phys.Vec3;

/**
 * Handling bounds: acceleration, braking, diagonals, pitch and stamina, for the steady handling with its dive (a sheet
 * of the old Kabuterimon's numbers) and for agile flight (Kabuterimon's sheet): the view-led dive and its momentum,
 * the boost, the slide, wide fast turns, the ground's skim, and what flying hard and fighting on the wing cost.
 */
public final class AerialRidingRegressionTest {
    private AerialRidingRegressionTest() {}
    /** Steady handling with a dive (the numbers Kabuterimon flew on before agile flight). */
    private static final com.digicube.digimon.AerialMount STEADY = new com.digicube.digimon.AerialMount(.65, .045, .13, .42, .36,
            7, 28, 10, 10, 24, new com.digicube.digimon.AerialMount.Dive(1.2, .022, .006, .03, .06));
    public static void run() {
        var species=DigimonSpeciesRegistry.getOrThrow(Constants.id("kabuterimon"));
        check(species.locomotion().canFly() && species.body().mount().orElseThrow().flight()!=null,"species explicitly grants aerial riding");
        agile(species.body().mount().orElseThrow().flight(), species.locomotion().flight());
        var p=STEADY;
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
        for(int bits=0;bits<=AerialInput.MAX_BITS;bits++)check(AerialInput.fromBits(bits).bits()==bits,"button codec roundtrip");
        var tank=new com.digicube.digimon.FlightReserve(species.locomotion().flight());
        for(int i=0;i<2400;i++)tank.consume();
        check(tank.exhausted() && !tank.ready(),"empty reserve cannot authorize launch");
    }

    /** Agile flight: speed is energy, the faster the wider the turn, the ground levels a dive, the wing costs. */
    private static void agile(com.digicube.digimon.AerialMount p, com.digicube.digimon.DigimonFlight flight) {
        var a = p.agility();
        check(a != null, "Kabuterimon flies agile");
        Vec3 v = Vec3.ZERO;
        for (int i = 0; i < 60; i++) v = AerialHandling.agile(p, v, 1, 0, 0, 0, false, false, false);
        check(Math.abs(v.length() - p.cruiseSpeed()) < 1e-6 && Math.abs(v.y) < 1e-6, "level flight settles at cruise");
        Vec3 boost = v;
        for (int i = 0; i < 80; i++) boost = AerialHandling.agile(p, boost, 1, 0, 0, 0, false, false, true);
        check(Math.abs(boost.length() - p.cruiseSpeed() * a.boost()) < 1e-3, "the sprint key beats the wings to the boost's speed");
        Vec3 dive = v;
        for (int i = 0; i < 60; i++) dive = AerialHandling.agile(p, dive, 1, 0, 0, 70, false, false, false);
        check(dive.length() > p.cruiseSpeed() * 2 && dive.length() <= a.maxSpeed() + 1e-9, "a steep dive gathers speed up to its terminal speed");
        check(-dive.y > dive.horizontalDistance(), "the dive follows the view down");
        Vec3 shallow = v;
        for (int i = 0; i < 60; i++) shallow = AerialHandling.agile(p, shallow, 1, 0, 0, 20, false, false, false);
        check(dive.length() > shallow.length() + .2, "steep dives earn more than shallow ones");
        Vec3 carried = dive, climbed = dive;
        for (int i = 0; i < 20; i++) {
            carried = AerialHandling.agile(p, carried, 1, 0, 0, 0, false, false, false);
            climbed = AerialHandling.agile(p, climbed, 1, 0, 0, -50, false, false, false);
        }
        check(carried.length() > p.cruiseSpeed() + .3 && Math.abs(carried.y) < .05, "pulled out level, the dive's speed carries on ahead");
        check(climbed.length() < carried.length(), "a climb spends the dive's speed faster than level flight");
        for (int i = 0; i < 300; i++) carried = AerialHandling.agile(p, carried, 1, 0, 0, 0, false, false, false);
        check(Math.abs(carried.length() - p.cruiseSpeed()) < .01, "the carried speed bleeds back to cruise");
        // the faster, the wider the turn
        Vec3 fast = dive.multiply(1, 0, 1).normalize().scale(a.maxSpeed()), slow = v;
        Vec3 fastNext = AerialHandling.agile(p, fast, 1, 0, 90, 0, false, false, false);
        Vec3 slowNext = AerialHandling.agile(p, slow, 1, 0, 90, 0, false, false, false);
        double fastTurn = Math.toDegrees(Math.acos(Math.clamp(fast.normalize().dot(fastNext.normalize()), -1, 1)));
        double slowTurn = Math.toDegrees(Math.acos(Math.clamp(slow.normalize().dot(slowNext.normalize()), -1, 1)));
        check(fastTurn < slowTurn * .6 && slowTurn > 9, "a fast body turns wider than a slow one");
        // the strafe keys slide it aside, nothing held hovers
        Vec3 slide = Vec3.ZERO;
        for (int i = 0; i < 30; i++) slide = AerialHandling.agile(p, slide, 0, 1, 0, 0, false, false, false);
        check(slide.x > p.cruiseSpeed() * a.strafe() * .95 && Math.abs(slide.z) < 1e-6, "the strafe keys slide it to its left");
        Vec3 hover = dive;
        double flare = 0;
        for (int i = 0; i < 40; i++) { hover = AerialHandling.agile(p, hover, 0, 0, 0, 0, false, false, false); flare += hover.length(); }
        check(hover.equals(Vec3.ZERO) && flare < 18, "letting go flares even a terminal dive to a hover");
        // the ground levels a dive into a skim at the speed it had
        Vec3 skim = AerialHandling.skim(dive, 3);
        check(skim.y > dive.y && Math.abs(skim.length() - dive.length()) < 1e-6 && skim.y >= -(3 - AerialHandling.SKIM) * AerialHandling.SKIM_DROP - 1e-9,
                "over the ground a dive levels out without losing speed");
        check(AerialHandling.skim(new Vec3(.3, .2, 0), 1).equals(new Vec3(.3, .2, 0)), "a climb is never touched by the ground");
        // at the skim's height a fast body holds it, a slow one settles on down to land
        Vec3 skimming = AerialHandling.skim(new Vec3(0, -.5, 1.2), AerialHandling.SKIM - .2), settling = AerialHandling.skim(new Vec3(0, -.3, .1), AerialHandling.SKIM - .2);
        check(skimming.y == 0 && Math.abs(skimming.length() - new Vec3(0, -.5, 1.2).length()) < 1e-6 && settling.y < 0 && settling.y >= -.04 - 1e-9,
                "a skim at speed holds its height and a slow one settles");
        // costs: about two minutes of plain flight, about three casts on the wing, a slow refill in a fight
        var costs = flight.costs();
        var tank = new com.digicube.digimon.FlightReserve(flight);
        int ticks = 0;
        while (!tank.exhausted()) { tank.consume(); ticks++; }
        check(ticks >= 1200 && ticks <= 2400, "plain flight lasts one to two minutes");
        tank = new com.digicube.digimon.FlightReserve(flight);
        int casts = 0;
        while (!tank.mustLand()) { tank.spendAttack(); casts++; }
        check(casts >= 3 && casts <= 4, "about three casts on the wing drain the reserve");
        var calm = new com.digicube.digimon.FlightReserve(flight); var fighting = new com.digicube.digimon.FlightReserve(flight);
        calm.restore(0, 0); fighting.restore(0, 0);
        for (int i = 0; i < 200; i++) { calm.rest(1); fighting.rest(costs.combatRecharge()); }
        check(fighting.charge() < calm.charge() * .5, "the reserve refills slower in a fight");
        check(costs.boost() > 1 && costs.glide() < 1 && costs.roll() > 0, "boosting costs more and gliding less than plain flight");
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
