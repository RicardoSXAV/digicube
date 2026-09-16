package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.util.*;

/** Opt-in real-server contact/flight fixtures, separate from navigation duels. */
final class IkkakumonScenario {
    record Fixture(String move,int yaw,int elevation,String target,double speed,String boundary,boolean longitudinal) {
        Fixture(String move,int yaw,int elevation,String target,double speed,String boundary) {
            this(move,yaw,elevation,target,speed,boundary,false);
        }
    }
    static final List<Fixture> CASES=new ArrayList<>();
    static {
        for(String move:new String[]{"heat_top","harpoon_vulcan"})for(int yaw=0;yaw<360;yaw+=45)
            for(int elevation=-1;elevation<=1;elevation++)for(String target:new String[]{"agumon","golemon"})for(double speed:new double[]{0,-.025,.025})
                CASES.add(new Fixture(move,yaw,elevation,target,speed,""));
        for(String move:new String[]{"heat_top","harpoon_vulcan"})for(int yaw=0;yaw<360;yaw+=45)
            for(int elevation=-1;elevation<=1;elevation++)for(String target:new String[]{"agumon","golemon"})for(double speed:new double[]{-.025,.025})
                CASES.add(new Fixture(move,yaw,elevation,target,speed,"",true));
        for(String move:new String[]{"heat_top","harpoon_vulcan"})for(String boundary:new String[]{"ally","invulnerable","interrupt","cover","lost","retry"})
            CASES.add(new Fixture(move,0,0,"golemon",0,boundary));
        CASES.add(new Fixture("harpoon_vulcan",0,0,"golemon",0,"late_interrupt"));
    }
    static final Vec3 ORIGIN=new Vec3(.5,302,.5);
    static final AABB ARENA=new AABB(-21,294,-21,22,315,22);
    static int index=-1,ticks,hits,passed,failed;static boolean initialized,done;
    static DigimonEntity caster,target;static Mob owner;static DigimonAttack attack;static Vec3 start,stance;static float health;
    static void tick(ServerLevel level) {
        if(done)return;
        try {
            if(!initialized) {
                initialized=true;
                for(int x=-2;x<=1;x++)for(int z=-2;z<=1;z++)level.setChunkForced(x,z,true);
                level.getServer().tickRateManager().requestGameToSprint(60000);
                for(int x=-19;x<=19;x++)for(int z=-19;z<=19;z++)for(int y=298;y<=312;y++)
                    level.setBlock(new BlockPos(x,y,z),(y<300?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
                next(level);return;
            }
            observe(level);
        } catch(RuntimeException|AssertionError e) {
            Constants.LOG.error("[ikkakumon-case] aborted {}",index,e);failed++;finish(level);
        }
    }
    static void next(ServerLevel level) {
        level.getEntities((Entity)null,ARENA).forEach(Entity::discard);
        for(int x=-7;x<=7;x++)for(int y=300;y<=312;y++)for(int z=1;z<=2;z++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        if(++index==CASES.size()){finish(level);return;}
        var c=CASES.get(index);ticks=hits=0;
        caster=DigimonEntity.spawnWild(level,DigimonSpeciesRegistry.getOrThrow(Constants.id("ikkakumon")),20,ORIGIN);
        target=DigimonEntity.spawnWild(level,DigimonSpeciesRegistry.getOrThrow(Constants.id(c.target)),20,ORIGIN.add(0,c.elevation,6));
        target.setNoAi(true);target.setNoGravity(true);caster.setNoGravity(true);
        for(var mob:new Mob[]{caster,target}){mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);mob.setHealth(mob.getMaxHealth());}
        attack=caster.getSpecies().orElseThrow().attacks().stream().filter(a->a.id().getPath().equals(c.move)).findFirst().orElseThrow();
        start=null;stance=ORIGIN;
        for(double distance=c.move.equals("harpoon_vulcan")?7:4.5;distance>=1;distance-=.1) {
            var candidate=AttackGeometry.world(ORIGIN,new Vec3(0,c.elevation,distance),c.yaw);target.setPos(candidate);
            if(caster.canAttackFrom(attack,target,ORIGIN)){start=candidate;break;}
        }
        if(start==null && c.elevation!=0 && c.move.equals("heat_top")) {
            // A horn cannot reach below its physical arc. Verify refusal above, then
            // the geometry at the required stance; separate terrain duels exercise navigation.
            stance=ORIGIN.add(0,c.elevation,0);caster.setPos(stance);
            for(double distance=4.5;distance>=1;distance-=.1) {
                var candidate=AttackGeometry.world(stance,new Vec3(0,0,distance),c.yaw);target.setPos(candidate);
                if(caster.canAttackFrom(attack,target,stance)){start=candidate;break;}
            }
        }
        if(start==null)throw new AssertionError("No reachable fixture "+c);
        if(c.move.equals("heat_top")) {
            // Use the interior of the real contact range, not the single outermost
            // grazing corner of a square target, which legitimately misses as it moves.
            var options=new ArrayList<Vec3>();double dy=start.y-stance.y;
            for(double distance=4.5;distance>=1;distance-=.1) {
                var candidate=AttackGeometry.world(stance,new Vec3(0,dy,distance),c.yaw);target.setPos(candidate);
                if(caster.canAttackFrom(attack,target,stance))options.add(candidate);
            }
            start=options.get(options.size()/2);target.setPos(start);
        }
        caster.setYRot(c.yaw);caster.yBodyRot=caster.yHeadRot=c.yaw;health=target.getHealth();
        if(c.boundary.equals("ally")) {
            owner=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);owner.setPos(14,302,14);owner.setNoAi(true);level.addFreshEntity(owner);caster.setOwner(owner);target.setOwner(owner);
        }
        if(c.boundary.equals("invulnerable"))target.setInvulnerable(true);
    }
    static void observe(ServerLevel level) {
        var c=CASES.get(index);ticks++;
        if(target.getHealth()<health-.01)hits++;health=target.getHealth();
        caster.setTarget(null);caster.setLastHurtByMob(null);caster.getNavigation().stop();caster.setPos(stance);caster.setOnGround(true);caster.setDeltaMovement(Vec3.ZERO);
        var velocity=(c.longitudinal?new Vec3(0,0,c.speed):new Vec3(c.speed,0,0)).yRot((float)-Math.toRadians(c.yaw));
        if(!target.isRemoved()){target.setPos(start.add(velocity.scale(Math.max(0,ticks-10))));target.setDeltaMovement(velocity);}
        if(ticks==10) {
            caster.startAttack(attack,target);
            if(!caster.isAttacking() && c.boundary.isEmpty())throw new AssertionError("refused "+c);
        }
        if(ticks==12) {
            if(c.boundary.equals("interrupt")){caster.interruptAttack();if(caster.isAttackReady(attack))throw new AssertionError("lost cooldown");}
            if(c.boundary.equals("lost"))target.discard();
            if(c.boundary.equals("cover"))for(int x=-7;x<=7;x++)for(int y=300;y<=312;y++) {
                // Thin contact-range cover leaves the target outside the block;
                // otherwise suffocation would be miscounted as attack damage.
                boolean close=c.move.equals("heat_top");
                level.setBlock(new BlockPos(x,y,close?1:2),(close?Blocks.GLASS_PANE:Blocks.STONE).defaultBlockState(),3);
            }
        }
        if(ticks==14 && c.boundary.equals("retry")) {
            target.hurtServer(level,target.damageSources().generic(),100);
            target.setHealth(target.getMaxHealth());health=target.getHealth();
        }
        if(ticks==17 && c.boundary.equals("retry"))target.invulnerableTime=0;
        if(ticks==22 && c.boundary.equals("late_interrupt"))caster.interruptAttack();
        if(ticks>=90) {
            boolean expectsHit=c.boundary.isEmpty() || Set.of("retry","late_interrupt").contains(c.boundary);
            boolean pass=expectsHit?hits==1:hits==0;
            if(pass)passed++;else failed++;
            Constants.LOG.info("[ikkakumon-case] {} {} hits={} target={} stance={}",pass?"PASS":"FAIL",c,hits,start,stance);
            next(level);
        }
    }
    static void finish(ServerLevel level) {
        done=true;Constants.LOG.info("[scenario] {} ikkakumon_checks passed={} failed={} total={}",failed==0&&passed==CASES.size()?"PASS":"FAIL",passed,failed,CASES.size());level.getServer().halt(false);
    }
}
