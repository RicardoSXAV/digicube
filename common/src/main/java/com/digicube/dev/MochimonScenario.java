package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.*;
import java.util.*;

/** Real timeline checks: two consecutive casts, moving bodies and rejected contacts. */
final class MochimonScenario {
    private record Case(String move, String target, int yaw, double speed, String boundary) {}
    private static final List<Case> CASES = new ArrayList<>();
    static {
        for (String move : new String[]{"mochi_punch","bubble_blow"}) {
            for (String target : new String[]{"koromon","agumon","golemon"})
                for (int yaw=0; yaw<360; yaw+=45) for (double speed : new double[]{0,-.005,.005})
                    CASES.add(new Case(move,target,yaw,speed,""));
            for (String boundary : new String[]{"invulnerable","interrupt","lost","range"})
                CASES.add(new Case(move,"agumon",0,0,boundary));
        }
    }
    private static final Vec3 ORIGIN = new Vec3(.5,300,.5);
    private static final AABB ARENA = new AABB(-16,294,-16,16,312,16);
    private static int index=-1,ticks,hits,passed,failed;
    private static boolean initialized,done,firstSide;
    private static DigimonEntity caster,target;
    private static DigimonAttack attack;
    private static Vec3 start;
    private static float health;
    static void tick(ServerLevel level) {
        if(done)return;
        try {
            if(!initialized) {
                initialized=true;
                for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++)level.setChunkForced(x,z,true);
                CombatScenario.build(level,"flat");
                level.getServer().tickRateManager().requestGameToSprint(30000);
                next(level);
            } else observe(level);
        } catch(RuntimeException|AssertionError e) {
            failed++;Constants.LOG.error("[mochimon-case] aborted {}",index,e);finish(level);
        }
    }
    private static void next(ServerLevel level) {
        level.getEntities((Entity)null,ARENA).forEach(Entity::discard);
        if(++index==CASES.size()){finish(level);return;}
        var c=CASES.get(index);ticks=hits=0;
        caster=DigimonEntity.spawnWild(level,DigimonSpeciesRegistry.getOrThrow(Constants.id("mochimon")),20,ORIGIN);
        target=DigimonEntity.spawnWild(level,DigimonSpeciesRegistry.getOrThrow(Constants.id(c.target)),20,ORIGIN.add(0,0,2));
        target.setNoAi(true);
        for(var mob:List.of(caster,target)) {
            mob.setNoGravity(true);mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);mob.setHealth(mob.getMaxHealth());
        }
        attack=caster.getSpecies().orElseThrow().attacks().stream().filter(a->a.id().getPath().equals(c.move)).findFirst().orElseThrow();
        health=target.getHealth();start=target.position();
    }
    private static void observe(ServerLevel level) {
        var c=CASES.get(index);ticks++;
        if(target.getHealth()<health-.01 && target.getLastDamageSource()!=null && target.getLastDamageSource().getEntity()==caster)hits++;
        health=target.getHealth();
        caster.setTarget(null);caster.setLastHurtByMob(null);caster.getNavigation().stop();
        caster.setPos(ORIGIN);caster.setOnGround(true);caster.setDeltaMovement(Vec3.ZERO);
        int phase=ticks<60?ticks:ticks-60;
        if(phase==8) {
            caster.setYRot(c.yaw);caster.yBodyRot=caster.yHeadRot=c.yaw;
            start=null;
            for(double distance=attack.isRanged()?3:2.5;distance>=.45;distance-=.025) {
                var p=AttackGeometry.world(ORIGIN,new Vec3(0,0,distance),c.yaw);target.setPos(p);
                if(caster.canAttackFrom(attack,target,ORIGIN)){start=p;break;}
            }
            if(start==null)throw new AssertionError("No reachable fixture "+c);
            if(!attack.isRanged())start=ORIGIN.add(start.subtract(ORIGIN).scale(.9));
            if(c.boundary.equals("range"))start=ORIGIN.add(0,0,15);
            if(c.boundary.equals("invulnerable"))target.setInvulnerable(true);
        }
        var velocity=new Vec3(c.speed,0,0).yRot((float)-Math.toRadians(c.yaw));
        if(!target.isRemoved()) {target.setPos(start.add(velocity.scale(Math.max(0,phase-10))));target.setDeltaMovement(velocity);}
        if(phase==10) {
            boolean side=caster.contactMirrored(attack);
            if(ticks==10)firstSide=side;
            else if(attack.alternateSides() && c.boundary.isEmpty() && firstSide==side)throw new AssertionError("Punch did not alternate");
            caster.startAttack(attack,target);
            if(c.boundary.isEmpty() && !caster.isAttacking())throw new AssertionError("Refused "+c);
        }
        if(phase==12) {
            if(c.boundary.isEmpty() && caster.isAttackReady(attack))throw new AssertionError("Cooldown missing");
            if(c.boundary.equals("interrupt"))caster.interruptAttack();
            if(c.boundary.equals("lost"))target.discard();
        }
        if(ticks==55 && !c.boundary.isEmpty() || ticks==115) {
            int expected=c.boundary.isEmpty()?2:0;
            boolean pass=hits==expected && !caster.isAttacking() && caster.isAttackReady(attack);
            if(pass)passed++;else failed++;
            Constants.LOG.info("[mochimon-case] {} {} hits={}",pass?"PASS":"FAIL",c,hits);
            next(level);
        }
    }
    private static void finish(ServerLevel level) {
        done=true;Constants.LOG.info("[scenario] {} mochimon_checks passed={} failed={} total={}",failed==0 && passed==CASES.size()?"PASS":"FAIL",passed,failed,CASES.size());
        level.getServer().halt(false);
    }
}
