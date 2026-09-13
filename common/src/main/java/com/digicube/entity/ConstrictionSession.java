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
    private final ConstrictionMotion.SweptBody body;
    private Vec3 approachOffset = Vec3.ZERO;
    private final boolean afloat;
    private boolean captured, released;

    static boolean eligible(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet) {
        return whyIneligible(owner,target,attack,feet) == null;
    }

    /** The first reason this prey cannot be wrapped from these feet, or null when it can. */
    static String whyIneligible(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet) {
        if (!target.isAlive()) return "prey dead";
        if (!owner.canAttack(target) || owner.isAllyOf(target)) return "not a valid prey";
        if (target.isInvulnerable() || target instanceof net.minecraft.world.entity.player.Player player
                && (player.isCreative() || player.isSpectator())) return "prey invulnerable";
        if (!target.onGround() && !target.isInWater()) return "prey airborne";
        if (target.isPassenger() || target.isVehicle()) return "prey riding";
        if (Math.abs(target.getY()-feet.y)>.25) return String.format("level off by %.2f", target.getY()-feet.y);
        if (target.hasEffect(DCEffects.CONSTRICTED)) return "prey already held";
        if (target.hasEffect(DCEffects.CONSTRICTION_RESISTANCE)) return "prey hold-resistant";
        // Nearly dead prey does not earn a ten-second cooldown, unless it is frozen and nothing else can touch it.
        if (!target.hasEffect(DCEffects.FROZEN)
                && target.getHealth() <= owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)*attack.power()) return "prey nearly dead";
        if (!target.canBeAffected(new MobEffectInstance(DCEffects.CONSTRICTED,3))) return "prey immune to holds";
        if (owner.constrictionMotion().fit(target.getBoundingBox(),owner.getBody().modelScale()) == null) return "prey size does not fit";
        return null;
    }

    static ConstrictionSession prepare(DigimonEntity owner, LivingEntity target, DigimonAttack attack) {
        return owner.onGround() || owner.isInWater() ? prepareAt(owner,target,attack,owner.position()) : null;
    }

    /** Navigation and casting must rehearse exactly the same complete body path. */
    static ConstrictionSession prepareAt(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet) {
        return rehearse(owner,target,attack,feet).cast;
    }

    /** Why a cast from these feet is refused, or null when it is possible. */
    static String rejection(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet) {
        return rehearse(owner,target,attack,feet).reason;
    }

    private record Rehearsal(ConstrictionSession cast, String reason) {}

    private static Rehearsal rehearse(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet) {
        if (feet.distanceToSqr(target.position()) > attack.range()*attack.range()) return new Rehearsal(null,"out of reach");
        String why = whyIneligible(owner,target,attack,feet);
        if (why != null) return new Rehearsal(null,why);
        if (owner.level().clip(new ClipContext(feet.add(0,owner.getEyeHeight(),0),
                AttackGeometry.chest(target.getBoundingBox()),ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,owner)).getType()!=HitResult.Type.MISS) return new Rehearsal(null,"no line to prey");
        var cast=new ConstrictionSession(owner,target,feet);
        if (cast.obstructed(Vec3.ZERO)) return new Rehearsal(null,"body obstructed");
        for(int t=0;t<=ConstrictionMotion.DURATION;t+=4) {
            Vec3 p=cast.at(t);
            if (!cast.supported(p)) return new Rehearsal(null,"unsupported at tick "+t);
            if (owner.level().getBlockCollisions(owner,owner.getBoundingBox().move(p.subtract(owner.position())).deflate(.01)).iterator().hasNext()) return new Rehearsal(null,"root blocked at tick "+t);
        }
        return new Rehearsal(cast,null);
    }

    boolean captured() { return captured; }

    private ConstrictionSession(DigimonEntity owner, LivingEntity target, Vec3 feet) {
        this.owner=owner;this.target=target;motion=owner.constrictionMotion();scale=owner.getBody().modelScale();
        afloat=owner.isInWater()||target.isInWater();
        fit=motion.fit(target.getBoundingBox(),scale);start=feet;center=target.position();
        distance=center.subtract(start).horizontalDistance();yaw=AttackGeometry.yaw(start,center);
        body=motion.sweptBody(fit,distance,scale,start,yaw);
    }
    /** A part can only touch a block its enclosing box touches, so the unions are exact early exits. */
    private boolean obstructed(Vec3 offset) {
        if (!collides(body.whole().move(offset))) return false;
        for (int i=0;i<body.samples().size();i++) if (sampleObstructed(i,offset)) return true;
        return false;
    }
    private boolean sampleObstructed(int sample, Vec3 offset) {
        if (!collides(body.samples().get(sample).move(offset))) return false;
        for (var box:body.sample(sample)) if (collides(box.move(offset))) return true;
        return false;
    }
    /** The body rides over anything one block high or lower, like a step; only taller terrain obstructs it. */
    private static final double STEP_OVER = 1.0;
    private boolean collides(net.minecraft.world.phys.AABB box) {
        double floor = start.y + STEP_OVER;
        if (box.maxY <= floor) return false;
        if (box.minY < floor) box = new net.minecraft.world.phys.AABB(box.minX,floor,box.minZ,box.maxX,box.maxY,box.maxZ);
        return owner.level().getBlockCollisions(owner,box).iterator().hasNext();
    }
    ConstrictionMotion.Fit fit() { return fit; }
    Vec3 start() { return start.add(approachOffset); }
    double distance() { return distance; }
    float yaw() { return yaw; }

    private Vec3 at(double tick) { return start().add(motion.root(fit,tick,distance,scale).yRot((float)Math.toRadians(-yaw))); }
    /** Ground within a block below, so the body may hang over a step; afloat, water itself carries it. */
    private boolean supported(Vec3 p) {
        if (owner.level().clip(new ClipContext(p.add(0,.15,0),p.add(0,-1.05,0),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,owner)).getType()!=HitResult.Type.MISS) return true;
        return afloat && owner.level().getFluidState(net.minecraft.core.BlockPos.containing(p)).is(net.minecraft.tags.FluidTags.WATER);
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
        if (sampleObstructed(tick/4,approachOffset)) return false;
        owner.move(MoverType.SELF,step);
        if (owner.position().subtract(next).horizontalDistanceSqr()>.0025) return false;
        if (tick==ConstrictionMotion.CAPTURE_TICK) {
            if (!eligible(owner,target,owner.activeAttackDefinition(),start)
                    || !target.addEffect(new MobEffectInstance(DCEffects.CONSTRICTED,3,0,false,false,true),owner)) return false;
            captured=true;
            // Wrapping frozen prey keeps the ice through the hold and a short tail after release.
            if (target.hasEffect(DCEffects.FROZEN)) target.addEffect(new MobEffectInstance(DCEffects.FROZEN,
                    ConstrictionMotion.RELEASE_TICK-ConstrictionMotion.CAPTURE_TICK+ConstrictionMotion.FROZEN_TAIL_TICKS,0,false,true),owner);
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
