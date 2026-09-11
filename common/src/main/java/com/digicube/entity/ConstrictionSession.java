package com.digicube.entity;

import com.digicube.digimon.ConstrictionMotion;
import com.digicube.digimon.DigimonAttack;
import com.digicube.registry.DCEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One cast owns its hold. Movement uses native root travel and ordinary block collision. */
final class ConstrictionSession {
    private final DigimonEntity owner;
    private final LivingEntity target;
    private final ConstrictionMotion motion;
    private final ConstrictionMotion.Fit fit;
    private final Vec3 start, center;
    private final double distance;
    private final float yaw, scale;
    private final java.util.List<net.minecraft.world.phys.AABB> bodyBounds;
    private Vec3 approachOffset = Vec3.ZERO;
    private boolean captured, released;

    static boolean eligible(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet) {
        if (!target.isAlive() || !owner.canAttack(target) || owner.isAllyOf(target) || target.isInvulnerable()
                || target instanceof net.minecraft.world.entity.player.Player player && (player.isCreative() || player.isSpectator())
                || !target.onGround() || owner.isInWater() || target.isInWater()
                || target.isPassenger() || target.isVehicle() || Math.abs(target.getY()-feet.y)>.25
                || target.hasEffect(DCEffects.CONSTRICTED) || target.hasEffect(DCEffects.CONSTRICTION_RESISTANCE)
                || target.hasEffect(DCEffects.FROZEN) || target.hasEffect(DCEffects.FROST_RESISTANCE)
                || target.getHealth() <= owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)*attack.power()
                || !target.canBeAffected(new MobEffectInstance(DCEffects.CONSTRICTED,3))) return false;
        return owner.constrictionMotion().fit(target.getBoundingBox(),owner.getBody().modelScale()) != null;
    }

    static ConstrictionSession prepare(DigimonEntity owner, LivingEntity target, DigimonAttack attack) {
        return owner.onGround() ? prepareAt(owner,target,attack,owner.position()) : null;
    }

    /** Navigation and casting must rehearse exactly the same complete body path. */
    static ConstrictionSession prepareAt(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet) {
        if (feet.distanceToSqr(target.position()) > attack.range()*attack.range()
                || !eligible(owner,target,attack,feet)) return null;
        if (owner.level().clip(new ClipContext(feet.add(0,owner.getEyeHeight(),0),
                AttackGeometry.chest(target.getBoundingBox()),ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,owner)).getType()!=HitResult.Type.MISS) return null;
        var cast=new ConstrictionSession(owner,target,feet);
        for (var box:cast.bodyBounds) {
            if (owner.level().getBlockCollisions(owner,box).iterator().hasNext()) return null;
        }
        for(int t=0;t<=ConstrictionMotion.DURATION;t+=4) {
            Vec3 p=cast.at(t);
            if (!cast.supported(p)) return null;
            if (owner.level().getBlockCollisions(owner,owner.getBoundingBox().move(p.subtract(owner.position())).deflate(.01)).iterator().hasNext()) return null;
        }
        return cast;
    }

    private ConstrictionSession(DigimonEntity owner, LivingEntity target, Vec3 feet) {
        this.owner=owner;this.target=target;motion=owner.constrictionMotion();scale=owner.getBody().modelScale();
        fit=motion.fit(target.getBoundingBox(),scale);start=feet;center=target.position();
        distance=center.subtract(start).horizontalDistance();yaw=AttackGeometry.yaw(start,center);
        bodyBounds=motion.sweptBody(fit,distance,scale,start,yaw);
    }
    ConstrictionMotion.Fit fit() { return fit; }
    Vec3 start() { return start.add(approachOffset); }
    double distance() { return distance; }
    float yaw() { return yaw; }

    private Vec3 at(double tick) { return start().add(motion.root(fit,tick,distance,scale).yRot((float)Math.toRadians(-yaw))); }
    private boolean supported(Vec3 p) {
        return owner.level().clip(new ClipContext(p.add(0,.15,0),p.add(0,-.3,0),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,owner)).getType()!=HitResult.Type.MISS;
    }

    /** False interrupts immediately: no hold survives a dead caster, escape, teleport or obstruction. */
    boolean tick(int tick) {
        if (!owner.isAlive() || owner.isRemoved()) return false;
        if (tick < ConstrictionMotion.CAPTURE_TICK) {
            // Follow ordinary walking during the approach. A sprint, teleport or
            // large displacement still evades capture; the hold never drags prey.
            Vec3 wanted = target.position().subtract(center);
            if (Math.abs(wanted.y) > .25
                    || wanted.horizontalDistanceSqr() > ConstrictionMotion.MAX_APPROACH_DRIFT * ConstrictionMotion.MAX_APPROACH_DRIFT
                    || wanted.subtract(approachOffset).horizontalDistanceSqr() > ConstrictionMotion.MAX_TARGET_STEP * ConstrictionMotion.MAX_TARGET_STEP) return false;
            approachOffset = wanted.multiply(1, 0, 1);
        }
        var currentFit=motion.fit(target.getBoundingBox(),scale);
        if(tick<ConstrictionMotion.RELEASE_TICK && (currentFit==null || Math.abs(currentFit.radius()-fit.radius())>.1))return false;
        if (tick<ConstrictionMotion.RELEASE_TICK && (!target.isAlive() || target.isRemoved()
                || target.level()!=owner.level() || !owner.canAttack(target) || owner.isAllyOf(target)
                || target.position().distanceToSqr(center.add(approachOffset))>ConstrictionMotion.ESCAPE_DISTANCE*ConstrictionMotion.ESCAPE_DISTANCE
                || target.isPassenger() || target.isVehicle())) return false;
        owner.setYRot(yaw);owner.yBodyRot=yaw;owner.yHeadRot=yaw;
        owner.getNavigation().stop();owner.setDeltaMovement(0,0,0);
        Vec3 next=at(tick+1),step=next.subtract(owner.position()).multiply(1,0,1);
        // External knockback cannot be turned into a teleport back to the path.
        if (step.lengthSqr()>1 || !supported(next)) return false;
        if (owner.level().getBlockCollisions(owner,owner.getBoundingBox().expandTowards(step).deflate(.01)).iterator().hasNext()) return false;
        {
            int parts=bodyBounds.size()/(ConstrictionMotion.DURATION/4+1),first=tick/4*parts;
            for(int i=first;i<first+parts;i++)if(owner.level().getBlockCollisions(owner,bodyBounds.get(i).move(approachOffset)).iterator().hasNext())return false;
        }
        owner.move(MoverType.SELF,step);
        if (owner.position().subtract(next).horizontalDistanceSqr()>.0025) return false;
        if (tick==ConstrictionMotion.CAPTURE_TICK) {
            if (!eligible(owner,target,owner.activeAttackDefinition(),start)
                    || !target.addEffect(new MobEffectInstance(DCEffects.CONSTRICTED,3,0,false,false,true),owner)) return false;
            captured=true;
            target.addEffect(new MobEffectInstance(DCEffects.CONSTRICTION_RESISTANCE,ConstrictionMotion.RESISTANCE_TICKS,0,false,false,true),owner);
            target.addEffect(new MobEffectInstance(DCEffects.FROST_RESISTANCE,ConstrictionMotion.RESISTANCE_TICKS,0,false,false,true),owner);
            if (target instanceof DigimonEntity digimon) digimon.interruptAttack();
        }
        if (captured && tick<ConstrictionMotion.RELEASE_TICK) {
            if (!target.hasEffect(DCEffects.CONSTRICTED)) return false; // cleansing breaks the hold
            target.addEffect(new MobEffectInstance(DCEffects.CONSTRICTED,3,0,false,false,true),owner);
            target.setDeltaMovement(0,Math.min(0,target.getDeltaMovement().y),0);
            if ((tick-ConstrictionMotion.CAPTURE_TICK)%ConstrictionMotion.INTERVAL==0) owner.damageWithActiveAttack(target);
        }
        if (tick>=ConstrictionMotion.RELEASE_TICK) release();
        return true;
    }

    void release() {
        if(captured&&!released) {target.removeEffect(DCEffects.CONSTRICTED);released=true;}
    }
}
