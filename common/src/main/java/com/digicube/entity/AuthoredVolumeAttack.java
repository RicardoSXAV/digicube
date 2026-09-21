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
    private final Map<UUID,Set<Integer>> beats=new HashMap<>();
    /** Where the leading volume was last tick, and whether the landing burst has gone off: the cues for trail, release and landing. */
    private Vec3 lastLead;
    private boolean released,landed;
    public void reset() { counts.clear();lastHit.clear();beats.clear();lastLead=null;released=landed=false; }
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
        return yaw(attack,feet,target,false);
    }
    public static float yaw(DigimonAttack attack,Vec3 feet,Vec3 target,boolean mirrored) {
        var definition=AuthoredAttacks.get(attack);
        // A summoned strike lands where the target is; the caster only has to face it.
        if(definition!=null && definition.anchored())return AttackGeometry.yaw(feet,target);
        if(definition!=null && !definition.hitWindows().isEmpty()) {
            // Aim a real striking surface at narrow prey. Facing the empty gap
            // between paired tentacles can never connect, at any centre distance.
            Vec3 delta=target.subtract(feet);double best=Double.POSITIVE_INFINITY;float offset=0;
            for(var window:definition.hitWindows())for(var box:definition.sample((window[0]+window[1])*.5,false,mirrored))if(box!=null) {
                float angle=AttackGeometry.yaw(Vec3.ZERO,box.center());
                if(Math.abs(angle)>45)continue;
                double radial=box.center().horizontalDistance()-delta.horizontalDistance();
                double height=box.center().y-delta.y;
                double score=radial*radial+height*height+Math.abs(angle)*.001;
                if(score<best){best=score;offset=angle;}
            }
            return AttackGeometry.yaw(feet,target)-offset;
        }
        // The native sweep provides the turn. Rotating the entity to compensate for
        // a tail marker adds a sideways snap before and after the performance.
        return AttackGeometry.yaw(feet,target);
    }
    public static boolean clear(Level level,DigimonEntity caster,Vec3 from,Vec3 to) {
        return level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,caster)).getType()==HitResult.Type.MISS;
    }
    /** How far below a target a summoned strike still finds the floor it lands on. */
    private static final double LANDING_DROP=3, LANDING_STEP=5;
    /** How much of the way in a summoned strike must find open; above that it may break through leaves or a roof edge. */
    private static final double APPROACH_CLEAR=3;
    /** The floor a summoned strike lands on under a point; a swimmer is struck where it floats. Null over a drop. */
    public static Vec3 landing(Level level,DigimonEntity caster,Vec3 under) {
        if(!level.getFluidState(net.minecraft.core.BlockPos.containing(under)).isEmpty())return under;
        var hit=level.clip(new ClipContext(under.add(0,.5,0),under.add(0,-LANDING_DROP,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,caster));
        return hit.getType()==HitResult.Type.MISS?null:hit.getLocation();
    }
    /** Origin of an authored volume in the world: the caster's feet, or the landing point of a summoned strike. */
    public static Vec3 origin(DigimonEntity caster,DigimonAttack attack) {
        var d=AuthoredAttacks.get(attack);
        return d!=null && d.anchored()?caster.strikeAnchor():caster.position();
    }
    /** Occluded cells are hidden by the client and excluded from server damage. */
    public static boolean visible(Level level,DigimonEntity caster,DigimonAttack attack,double tick,Vec3 feet,float yaw,AttackBox box) {
        if(AuthoredAttacks.get(attack).anchored()) {
            // The stone falls down an open column; a roof between it and its landing point stops it.
            return level.getWorldBorder().isWithinBounds(box.bounds()) && clear(level,caster,feet.add(0,.1,0),box.center().add(0,.1,0));
        }
        var f=AuthoredAttacks.get(attack).motion(caster.isInWater()).sample(tick);
        Vec3 origin=AttackGeometry.world(feet,f.head(),yaw);
        if(!level.getWorldBorder().isWithinBounds(box.bounds()))return false;
        if(attack.kind()==DigimonAttack.Kind.BOX_SWEEP || AuthoredAttacks.get(attack).effect()==null) {
            // A physical limb can extend its pivot through thin cover. Checking
            // only pivot-to-tip would then start on the far side of that cover.
            if(!clear(level,caster,feet.add(0,f.head().y,0),origin)
                    || KineticGeometry.blocked(level,caster,box))return false;
        }
        if(!clear(level,caster,origin,box.center()))return false;
        // Terrain depth clips the visible part of a cell crossing the floor. A separate
        // target sight line below prevents its wide edge from damaging through cover.
        return true;
    }
    /** Big Crack cells need real floor beneath them; ordinary bursts remain free-space effects. */
    public static boolean supported(Level level, DigimonEntity caster, AttackBox box) {
        AABB bounds=box.bounds();Vec3 center=bounds.getCenter();
        Vec3 top=new Vec3(center.x,bounds.maxY+1.1,center.z), bottom=new Vec3(center.x,bounds.minY-1.1,center.z);
        var hit=level.clip(new ClipContext(top,bottom,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,caster));
        return hit.getType()!=HitResult.Type.MISS && level.getFluidState(hit.getBlockPos()).isEmpty()
                && Math.abs(hit.getLocation().y-caster.getY())<=1.01;
    }
    public static boolean canReach(DigimonEntity caster,DigimonAttack attack,Vec3 feet,LivingEntity target) {
        var d=AuthoredAttacks.get(attack);Vec3 targetPoint=target.getBoundingBox().getCenter();
        if(d.anchored()) {
            // Seen from where the caster would stand, on a floor near its own, with the last stretch of the fall open.
            Vec3 landing=landing(caster.level(),caster,target.position());
            if(landing==null || Math.abs(landing.y-feet.y)>LANDING_STEP
                    || !clear(caster.level(),caster,feet.add(0,caster.getEyeHeight(),0),targetPoint))return false;
            Vec3 way=d.anchorApproach();
            if(way.length()>APPROACH_CLEAR)way=way.normalize().scale(APPROACH_CLEAR);
            return clear(caster.level(),caster,landing.add(0,.1,0),AttackGeometry.world(landing.add(0,.1,0),way,AttackGeometry.yaw(feet,landing)));
        }
        boolean mirrored=caster.contactMirrored(attack);
        float facing=yaw(attack,feet,targetPoint,mirrored), pitch=pitch(attack,feet,targetPoint,facing);
        for(double t=attack.motion().activeFrom();t<=attack.motion().activeUntil();t+=.5) {
            for(var local:d.sample(t,caster.isInWater(),mirrored)) if(local!=null && Math.abs(local.x().dot(local.y().cross(local.z())))>1e-8) {
                var box=aimed(local,attack,t,pitch).world(feet,facing,0);
                for(var volume:HitParts.of(target))if(box.intersects(volume) && visible(caster.level(),caster,attack,t,feet,facing,box)
                        && (!d.grounded() || supported(caster.level(),caster,box))
                        && clear(caster.level(),caster,AttackGeometry.world(feet,d.motion(caster.isInWater()).sample(t).head(),facing),volume.getCenter()))return true;
            }
        }
        return false;
    }
    private static Vec3 nearest(Vec3 point,AABB box) {
        return new Vec3(Math.clamp(point.x,box.minX,box.maxX),Math.clamp(point.y,box.minY,box.maxY),Math.clamp(point.z,box.minZ,box.maxZ));
    }
    /** Trail behind the leading volume while it moves; a summoned strike also announces its release and its landing. */
    private void cues(ServerLevel level,DigimonEntity caster,DigimonAttack attack,AuthoredAttacks.Definition d,Vec3 feet,int tick) {
        if(d.particles()==StrikeParticles.NONE)return;
        var cells=d.sample(tick,caster.isInWater(),caster.contactMirrored(attack));
        Vec3 lead=cells[0]==null?null:cells[0].world(feet,caster.getYRot(),0).center();
        // A fist only trails through its strike, not while it is drawn back; a summoned stone trails whenever it travels.
        boolean moving=lead!=null && lastLead!=null && lead.distanceToSqr(lastLead)>1e-4
                && (d.anchored() || tick>=attack.motion().activeFrom() && tick<=attack.motion().activeUntil());
        if(moving && visible(level,caster,attack,tick,feet,caster.getYRot(),cells[0].world(feet,caster.getYRot(),0))) {
            if(d.anchored() && !released){released=true;d.particles().release(level,lastLead);}
            d.particles().trail(level,lead,d.anchored());
        }
        if(!d.anchored() && lead!=null && tick==attack.motion().activeFrom())d.particles().swing(level,lead);
        lastLead=lead;
        if(d.anchored() && !landed && cells.length>1 && cells[1]!=null) {
            landed=true;
            var burst=cells[1].world(feet,caster.getYRot(),0);
            // A stone stopped by a roof never reaches the floor; its burst stays silent with it.
            if(visible(level,caster,attack,tick,feet,caster.getYRot(),burst))d.particles().landing(level,feet,cells[1].x().length());
        }
    }
    public void tick(ServerLevel level,DigimonEntity caster,DigimonAttack attack,int tick) {
        var d=AuthoredAttacks.get(attack);Vec3 feet=origin(caster,attack);
        if(feet==null)return;
        cues(level,caster,attack,d,feet,tick);
        if(tick<attack.motion().activeFrom() || tick>attack.motion().activeUntil())return;
        for(int q=0;q<=8;q++) {
            double time=tick+q/8.0;
            if(time>attack.motion().activeUntil())break;
            int beat=d.beat(time);
            if(!d.hitWindows().isEmpty() && beat<0)continue;
            for(var local:d.sample(time,caster.isInWater(),caster.contactMirrored(attack))) if(local!=null && Math.abs(local.x().dot(local.y().cross(local.z())))>1e-8) {
                var box=aimed(local,attack,time,caster.getAttackAimPitch(1)).world(feet,caster.getYRot(),0);
                for(var entity:level.getEntities(caster,box.bounds(),e->DigimonPart.livingOf(e) instanceof LivingEntity)) {
                    LivingEntity victim=DigimonPart.livingOf(entity);UUID id=victim.getUUID();
                    if(victim==caster || !victim.isAlive() || !caster.canAttack(victim) || caster.isAllyOf(victim)
                            || counts.getOrDefault(id,0)>=d.maxHits() || tick-lastHit.getOrDefault(id,-10000)<d.hitInterval()
                            || beat>=0 && beats.getOrDefault(id,Set.of()).contains(beat)
                            || !box.intersects(entity.getBoundingBox()) || !visible(level,caster,attack,time,feet,caster.getYRot(),box)
                            || d.grounded() && !supported(level,caster,box)
                            || !clear(level,caster,d.anchored()?box.center():AttackGeometry.world(caster.position(),d.motion(caster.isInWater()).sample(time).head(),caster.getYRot()),entity.getBoundingBox().getCenter()))continue;
                    // A summoned strike throws its victims away from where it lands, and a little off the floor.
                    if(caster.hitWithAttack(level,attack,victim,d.anchored()?feet:caster.position())) {
                        if(d.anchored()){victim.push(0,.3,0);victim.hurtMarked=true;}
                        else d.particles().contact(level,nearest(box.center(),entity.getBoundingBox()));
                        if(!d.contactParts().isEmpty())level.broadcastEntityEvent(caster,DigimonAnimationEvents.CONTACT);
                        counts.merge(id,1,Integer::sum);lastHit.put(id,tick);
                        if(beat>=0)beats.computeIfAbsent(id,k->new HashSet<>()).add(beat);
                    }
                }
            }
        }
    }
}
