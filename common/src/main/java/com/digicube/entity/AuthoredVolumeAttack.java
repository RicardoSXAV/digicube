package com.digicube.entity;

import com.digicube.digimon.AuthoredAttacks;
import com.digicube.digimon.DigimonAttack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

/** Sweeps native cuboids at sub-tick resolution; rejected hurt attempts remain retryable. */
public final class AuthoredVolumeAttack {
    private final Map<UUID,Integer> counts=new HashMap<>(),lastHit=new HashMap<>();
    public void reset() { counts.clear();lastHit.clear(); }
    public static AttackBox aimed(AttackBox box,DigimonAttack attack,double tick,float pitch) {
        if(attack.kind()!=DigimonAttack.Kind.BOX_BURST)return box;
        var f=attack.motion().sample(tick);
        return box.aimed(f.head(),pitch*f.aimWeight());
    }
    public static float pitch(DigimonAttack attack,Vec3 feet,Vec3 target,float yaw) {
        return attack.kind()==DigimonAttack.Kind.BOX_BURST
                ? FlameStream.aimPitch(attack.motion().sample(attack.hitTick()),feet,target,yaw,0) : 0;
    }
    public static float yaw(DigimonAttack attack,Vec3 feet,Vec3 target) {
        // The native sweep provides the turn. Rotating the entity to compensate for
        // a tail marker adds a sideways snap before and after the performance.
        return AttackGeometry.yaw(feet,target);
    }
    public static boolean clear(Level level,DigimonEntity caster,Vec3 from,Vec3 to) {
        return level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,caster)).getType()==HitResult.Type.MISS;
    }
    /** Occluded cells are hidden by the client and excluded from server damage. */
    public static boolean visible(Level level,DigimonEntity caster,DigimonAttack attack,double tick,Vec3 feet,float yaw,AttackBox box) {
        var f=attack.motion().sample(tick);
        Vec3 origin=AttackGeometry.world(feet,f.head(),yaw);
        if(!level.getWorldBorder().isWithinBounds(box.bounds()))return false;
        if(!clear(level,caster,origin,box.center()))return false;
        // Terrain depth clips the visible part of a cell crossing the floor. A separate
        // target sight line below prevents its wide edge from damaging through cover.
        return true;
    }
    public static boolean canReach(DigimonEntity caster,DigimonAttack attack,Vec3 feet,LivingEntity target) {
        var d=AuthoredAttacks.get(attack);Vec3 targetPoint=target.getBoundingBox().getCenter();
        float facing=yaw(attack,feet,targetPoint), pitch=pitch(attack,feet,targetPoint,facing);
        for(double t=attack.motion().activeFrom();t<=attack.motion().activeUntil();t+=.5) {
            for(var local:d.sample(t)) if(local!=null && Math.abs(local.x().dot(local.y().cross(local.z())))>1e-8) {
                var box=aimed(local,attack,t,pitch).world(feet,facing,0);
                for(var volume:HitParts.of(target))if(box.intersects(volume) && visible(caster.level(),caster,attack,t,feet,facing,box)
                        && clear(caster.level(),caster,AttackGeometry.world(feet,attack.motion().sample(t).head(),facing),volume.getCenter()))return true;
            }
        }
        return false;
    }
    public void tick(ServerLevel level,DigimonEntity caster,DigimonAttack attack,int tick) {
        if(tick<attack.motion().activeFrom() || tick>attack.motion().activeUntil())return;
        var d=AuthoredAttacks.get(attack);
        for(int q=0;q<=8;q++) {
            double time=tick+q/8.0;
            if(time>attack.motion().activeUntil())break;
            for(var local:d.sample(time)) if(local!=null && Math.abs(local.x().dot(local.y().cross(local.z())))>1e-8) {
                var box=aimed(local,attack,time,caster.getAttackAimPitch(1)).world(caster.position(),caster.getYRot(),0);
                for(var entity:level.getEntities(caster,box.bounds(),e->DigimonPart.livingOf(e) instanceof LivingEntity)) {
                    LivingEntity victim=DigimonPart.livingOf(entity);UUID id=victim.getUUID();
                    if(victim==caster || !victim.isAlive() || !caster.canAttack(victim) || caster.isAllyOf(victim)
                            || counts.getOrDefault(id,0)>=d.maxHits() || tick-lastHit.getOrDefault(id,-10000)<d.hitInterval()
                            || !box.intersects(entity.getBoundingBox()) || !visible(level,caster,attack,time,caster.position(),caster.getYRot(),box)
                            || !clear(level,caster,AttackGeometry.world(caster.position(),attack.motion().sample(time).head(),caster.getYRot()),entity.getBoundingBox().getCenter()))continue;
                    if(caster.hitWithAttack(level,attack,victim)) {
                        counts.merge(id,1,Integer::sum);lastHit.put(id,tick);
                    }
                }
            }
        }
    }
}
