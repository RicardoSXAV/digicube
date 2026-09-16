package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.*;
import com.digicube.registry.DCEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.util.*;

/** Opt-in live entity checks: native contact, moving victims and ink status lifecycle. */
final class GesomonScenario {
    record Fixture(String move,int yaw,String target,double sideways,String boundary) {}
    static final List<Fixture> CASES=new ArrayList<>();
    static {
        for(String move:new String[]{"devil_bashing","deadly_shade"})for(int yaw=0;yaw<360;yaw+=45)
            for(String target:new String[]{"cow","golemon"})for(double speed:new double[]{0,-.025,.025})
                CASES.add(new Fixture(move,yaw,target,speed,""));
        for(String move:new String[]{"devil_bashing","deadly_shade"})for(String boundary:new String[]{"ally","invulnerable","interrupt","cover","lost"})
            CASES.add(new Fixture(move,0,"golemon",0,boundary));
        CASES.add(new Fixture("devil_bashing",0,"golemon",0,"four_beats"));
        CASES.add(new Fixture("devil_bashing",0,"golemon",0,"retry"));
        CASES.add(new Fixture("deadly_shade",0,"golemon",0,"late_interrupt"));
    }
    static final Vec3 ORIGIN=new Vec3(.5,300,.5);
    static final AABB ARENA=new AABB(-20,294,-20,21,313,21);
    static int index=-1,ticks,hits,passed,failed;static boolean initialized,done,started,inkSeen;
    static DigimonEntity caster;static Mob target,owner;static DigimonAttack attack;static Vec3 start;static float health;
    static void tick(ServerLevel level) {
        if(done)return;
        try {
            if(!initialized) {
                initialized=true;
                for(int x=-2;x<=1;x++)for(int z=-2;z<=1;z++)level.setChunkForced(x,z,true);
                level.getServer().tickRateManager().requestGameToSprint(30000);
                for(int x=-18;x<=18;x++)for(int z=-18;z<=18;z++)for(int y=298;y<=310;y++)
                    level.setBlock(new BlockPos(x,y,z),(y<300?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
                next(level);return;
            }
            observe(level);
        } catch(RuntimeException|AssertionError e) {
            Constants.LOG.error("[gesomon-case] aborted {}",index,e);finish(level);
        }
    }
    static void next(ServerLevel level) {
        level.getEntities((Entity)null,ARENA).forEach(Entity::discard);
        for(int x=-6;x<=6;x++)for(int y=300;y<=308;y++)level.setBlock(new BlockPos(x,y,2),Blocks.AIR.defaultBlockState(),3);
        if(++index==CASES.size()){finish(level);return;}
        var c=CASES.get(index);ticks=hits=0;started=inkSeen=false;
        caster=DigimonEntity.spawnWild(level,DigimonSpeciesRegistry.getOrThrow(Constants.id("gesomon")),20,ORIGIN);
        target=c.target.equals("cow")?EntityTypes.COW.create(level,EntitySpawnReason.COMMAND)
                :DigimonEntity.spawnWild(level,DigimonSpeciesRegistry.getOrThrow(Constants.id(c.target)),20,ORIGIN.add(0,0,5));
        if(c.target.equals("cow"))level.addFreshEntity(target);
        target.setNoAi(true);target.setNoGravity(true);caster.setNoGravity(true);
        if(c.boundary.equals("four_beats") || c.boundary.equals("retry")) {
            target.getAttribute(Attributes.SCALE).setBaseValue(2);target.refreshDimensions();
        }
        for(var mob:new Mob[]{caster,target}){mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);mob.setHealth(mob.getMaxHealth());}
        attack=caster.getSpecies().orElseThrow().attacks().stream().filter(a->a.id().getPath().equals(c.move)).findFirst().orElseThrow();
        start=null;
        for(double distance=c.move.equals("deadly_shade")?7:4;distance>=1;distance-=.1) {
            var candidate=AttackGeometry.world(ORIGIN,new Vec3(0,0,distance),c.yaw);target.setPos(candidate);
            if(caster.canAttackFrom(attack,target,ORIGIN)){start=candidate;break;}
        }
        if(start==null)throw new AssertionError("No reachable fixture "+c);
        if(c.boundary.equals("four_beats") || c.boundary.equals("retry")) {
            start=ORIGIN.add(0,0,2.5);target.setPos(start);
        }
        caster.setYRot(c.yaw);caster.yBodyRot=caster.yHeadRot=c.yaw;
        health=target.getHealth();
        if(c.boundary.equals("ally")) {
            owner=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);owner.setPos(14,300,14);owner.setNoAi(true);level.addFreshEntity(owner);
            caster.setOwner(owner);((DigimonEntity)target).setOwner(owner);
        }
        if(c.boundary.equals("invulnerable"))target.setInvulnerable(true);
    }
    static void observe(ServerLevel level) {
        var c=CASES.get(index);ticks++;
        if(target.getHealth()<health-.01)hits++;
        health=target.getHealth();inkSeen|=target.hasEffect(DCEffects.INKED);
        caster.setTarget(null);caster.setLastHurtByMob(null);caster.getNavigation().stop();
        caster.setPos(ORIGIN);caster.setOnGround(true);caster.setDeltaMovement(Vec3.ZERO);
        var velocity=new Vec3(c.sideways,0,0).yRot((float)-Math.toRadians(c.yaw));
        if(!target.isRemoved()){target.setPos(start.add(velocity.scale(Math.max(0,ticks-10))));target.setDeltaMovement(velocity);}
        if(ticks==10) {
            caster.startAttack(attack,target);started=caster.isAttacking();
            if(!started && c.boundary.isEmpty())throw new AssertionError("refused "+c);
        }
        if(ticks==12) {
            if(c.boundary.equals("interrupt")) {
                caster.interruptAttack();if(caster.isAttackReady(attack))throw new AssertionError("lost cooldown");
            }
            if(c.boundary.equals("lost"))target.discard();
            if(c.boundary.equals("cover"))for(int x=-6;x<=6;x++)for(int y=300;y<=308;y++)
                level.setBlock(new BlockPos(x,y,2),Blocks.STONE.defaultBlockState(),3);
        }
        if(ticks==16 && c.boundary.equals("retry"))target.setInvulnerable(true);
        if(ticks==18 && c.boundary.equals("retry"))target.setInvulnerable(false);
        if(ticks==18 && c.boundary.equals("late_interrupt"))caster.interruptAttack();
        if(ticks>=90) {
            boolean expectsHit=c.boundary.isEmpty() || Set.of("four_beats","retry","late_interrupt").contains(c.boundary);
            boolean pass=expectsHit?hits>0:hits==0;
            if(c.boundary.equals("four_beats"))pass=hits==4;
            if(c.boundary.isEmpty() && c.move.equals("deadly_shade"))pass&=inkSeen&&!target.hasEffect(DCEffects.INKED);
            if(c.move.equals("devil_bashing"))pass&=hits<=4;
            if(pass)passed++;else failed++;
            Constants.LOG.info("[gesomon-case] {} {} hits={} inkSeen={}",pass?"PASS":"FAIL",c,hits,inkSeen);
            next(level);
        }
    }
    static void finish(ServerLevel level) {
        done=true;Constants.LOG.info("[scenario] {} gesomon_checks passed={} failed={} total={}",failed==0&&passed==CASES.size()?"PASS":"FAIL",passed,failed,CASES.size());
        level.getServer().halt(false);
    }
}
