package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.*;
import net.minecraft.world.phys.*;

/** Native timing, body-independent contact, finite pulses and oriented narrow phase. */
public final class AuthoredAttackRegressionTest {
    private AuthoredAttackRegressionTest() {}
    public static void run() {
        var dark=DigimonSpeciesRegistry.getOrThrow(Constants.id("darktyrannomon"));
        var grey=DigimonSpeciesRegistry.getOrThrow(Constants.id("greymon"));
        check(dark.baseHealth()==grey.baseHealth() && dark.baseAttack()==grey.baseAttack()
                && dark.baseDefence()==grey.baseDefence(),"Greymon tier base stats");
        check(dark.attribute()==DigimonAttribute.VIRUS,"preserve the attribute triangle");
        var fire=dark.attacks().getFirst();var tail=dark.attacks().getLast();
        check(fire.id().equals(Constants.id("fire_blast")) && tail.id().equals(Constants.id("iron_tail")),"fire priority and tail fallback");
        check(fire.durationTicks()==80 && tail.durationTicks()==72,"authored 4s fire / 3.6s half-turn");
        check(AuthoredAttacks.get(fire).maxHits()==3 && AuthoredAttacks.get(tail).maxHits()==1,"bounded burst and once-per-sweep contract");
        for(var d:AuthoredAttacks.all()) {
            check(d.frames().size()==d.attack().durationTicks()*d.samplesPerTick()+1,"source clock covers closing key");
            for(float yaw:new float[]{0,45,90,135,180,225,270,315})for(int height:new int[]{-1,0,1}) {
                var boxes=d.sample(d.attack().hitTick()+.375);
                boolean found=false;
                for(var local:boxes)if(local!=null) {
                    var box=local.world(new Vec3(5,height,-7),yaw,0);var point=box.center();
                    check(box.intersects(new AABB(point.subtract(.01,.01,.01),point.add(.01,.01,.01))),"occupied cuboid centre at diagonal/elevation");
                    check(!box.intersects(box.bounds().move(100,0,0)),"no distant damage");found=true;
                }
                check(found,"visible authored strike at hit_tick for " + d.attack().id());
            }
        }
        var thin=new AttackBox(Vec3.ZERO,new Vec3(1,0,1),new Vec3(0,.1,0),new Vec3(-.05,0,.05));
        var emptyCorner=new AABB(.9,-.01,-.9,1,.01,-.8);
        check(thin.bounds().intersects(emptyCorner) && !thin.intersects(emptyCorner),"broad AABB corners never deal damage");
        for(var cell:AuthoredAttacks.get(fire).sample(0))check(cell==null,"no flame at rest");
        for(var cell:AuthoredAttacks.get(fire).sample(80))check(cell==null,"cutoff removes flame volumes");
        check(Math.abs(AuthoredVolumeAttack.yaw(tail,Vec3.ZERO,new Vec3(0,1,5)))<8,
                "tail reaches forward without a sideways whole-body snap");
        int aimedCases=0;
        for(float yaw:new float[]{0,45,90,135,180,225,270,315})for(int height:new int[]{-1,0,1})for(int distance:new int[]{4,6,8}) {
            Vec3 feet=new Vec3(3,5,-7);
            Vec3 centre=AttackGeometry.world(feet,new Vec3(0,height+.4,distance),yaw);
            AABB small=new AABB(centre.add(-.3,-.4,-.3),centre.add(.3,.4,.3));
            float pitch=AuthoredVolumeAttack.pitch(fire,feet,centre,yaw);
            check(pitch>0 && pitch<=60,"bounded downward aim at a small enemy");
            boolean hit=false;
            for(double t=fire.motion().activeFrom();t<=fire.motion().activeUntil() && !hit;t+=.5)
                for(var box:AuthoredAttacks.get(fire).sample(t))if(box!=null
                        && Math.abs(box.x().dot(box.y().cross(box.z())))>1e-8
                        && AuthoredVolumeAttack.aimed(box,fire,t,pitch).world(feet,yaw,0).intersects(small)){hit=true;break;}
            check(hit,"pitched solid flame misses small enemy: yaw="+yaw+" height="+height+" range="+distance);
            aimedCases++;
        }
        Constants.LOG.info("Authored small-target aim passed {} heading/elevation/range fixtures.",aimedCases);
        var gold = DigimonSpeciesRegistry.getOrThrow(Constants.id("digmon")).attacks().getFirst();
        for (float yaw : new float[]{0, 45, 90, 135, 180, 225, 270, 315}) {
            // Stay inside the authored travel; at the outer range cap the planner must close in,
            // especially when pitching shortens horizontal reach.
            for (int height : new int[]{-1, 0, 1}) for (double distance : new double[]{2.5, 3, 3.5}) {
                Vec3 feet = new Vec3(3, 5, -7);
                Vec3 center = AttackGeometry.world(feet, new Vec3(0, height + .5, distance), yaw);
                AABB target = new AABB(center.add(-.3, -.5, -.3), center.add(.3, .5, .3));
                float pitch = AuthoredVolumeAttack.pitch(gold, feet, center, yaw);
                boolean hit = false;
                for (double tick = gold.motion().activeFrom(); tick <= gold.motion().activeUntil() && !hit; tick += .125) {
                    for (var box : AuthoredAttacks.get(gold).sample(tick)) if (box != null
                            && AuthoredVolumeAttack.aimed(box, gold, tick, pitch).world(feet, yaw, 0).intersects(target)) {
                        hit = true;
                        break;
                    }
                }
                check(hit, "Gold Rush small-target aim: yaw=" + yaw + " height=" + height + " range=" + distance);
            }
        }
        Constants.LOG.info("Authored attack regression checks passed: clocks, stat tier, oriented volume, finite pulse contract and cutoff.");
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
