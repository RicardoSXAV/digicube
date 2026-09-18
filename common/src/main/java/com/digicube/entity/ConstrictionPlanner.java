package com.digicube.entity;

import com.digicube.digimon.ConstrictionMotion;
import com.digicube.digimon.DigimonAttack;
import com.digicube.registry.DCEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;

/** A bounded, persistent approach to a rehearsed wrap stance, owned by one entity. */
final class ConstrictionPlanner {
    private final DigimonEntity owner;
    private LivingEntity target;
    private DigimonAttack attack;
    private Path path;
    private Vec3 anchor, feet, progressPosition;
    private int deadline, progressTick, retryUntil, checkedTick = Integer.MIN_VALUE;
    private boolean aligning, ready, submitted, preyFrozen;
    private String lastFail = "none";

    ConstrictionPlanner(DigimonEntity owner) { this.owner = owner; }

    boolean aligning() { return aligning; }
    boolean ready() { return ready; }
    /** Whether a rehearsed approach is committed to this prey. */
    boolean committedTo(LivingEntity prey) { return attack != null && prey != null && prey == target; }

    /** One-line state for the combat trace. */
    String describe() {
        String state = attack == null ? (owner.tickCount < retryUntil ? "backoff " + (retryUntil - owner.tickCount) : "idle")
                : ready ? "ready" : aligning ? "aligning" : "walking " + Math.max(0, deadline - owner.tickCount);
        return state + ", last fail: " + lastFail;
    }

    DigimonAttack update(LivingEntity prey, DigimonAttack move) {
        if (prey != target) {
            clear();
            target = prey;
            retryUntil = 0;
            checkedTick = Integer.MIN_VALUE;
        }
        if (prey == null || move == null || owner.constrictionReadyIn(move) > ConstrictionMotion.PREPARE_TICKS
                || owner.distanceToSqr(prey) > 144
                || !ConstrictionSession.eligible(owner,prey,move,new Vec3(owner.getX(),prey.getY(),owner.getZ()))) {
            clear();
            return null;
        }
        // Freshly frozen or Cold prey is a new opening: a backoff earned against it at full speed no longer applies.
        boolean frozen = prey.hasEffect(DCEffects.FROZEN) || prey.hasEffect(DCEffects.COLD);
        if (frozen && !preyFrozen) retryUntil = 0;
        preyFrozen = frozen;
        if (owner.tickCount < retryUntil) return null;
        if (checkedTick == owner.tickCount) return attack;
        checkedTick = owner.tickCount;
        if (attack != null && owner.tickCount >= deadline) return fail("approach deadline");
        if (attack != null && owner.position().distanceToSqr(progressPosition) > .04) {
            progressPosition = owner.position();
            progressTick = owner.tickCount;
        }
        if (attack != null && !aligning && owner.tickCount-progressTick >= 24) return fail("no progress");
        // A walking target can invalidate the stance, but never grants an endless chase.
        boolean moved = anchor != null && anchor.distanceToSqr(prey.position()) > .25;
        if (attack == null || moved) {
            boolean continuing = attack != null;
            if (!plan(prey,move)) return fail(lastFail);
            if (!continuing) deadline = owner.tickCount + ConstrictionMotion.APPROACH_TICKS;
            progressPosition = owner.position();
            progressTick = owner.tickCount;
        }
        if (!aligning && settled() && (owner.position().distanceToSqr(feet) < .36
                || submitted && owner.getNavigation().isDone())) {
            // Validate the actual arrival, including any navigation stopping tolerance.
            String why = ConstrictionSession.rejection(owner,prey,move,stance());
            if (why != null) return fail("arrival: " + why);
            aligning = true;
        }
        ready = aligned(prey);
        if (ready) {
            String why = ConstrictionSession.rejection(owner,prey,move,owner.position());
            if (why != null) return fail("commit: " + why);
        }
        return attack;
    }

    /** Called before ordinary attack selection/navigation. True owns this goal tick. */
    boolean steer(double speed) {
        if (attack == null) return false;
        if (aligning) {
            owner.getNavigation().stop();
            // Afloat, the coil forms at the prey's depth; the caster settles there while it turns.
            double rise = afloat() ? Mth.clamp(feet.y - owner.getY(), -.2, .2) : owner.getDeltaMovement().y;
            owner.setDeltaMovement(0,rise,0);
            owner.setYRot(Mth.approachDegrees(owner.getYRot(),AttackGeometry.yaw(owner.position(),target.position()),ConstrictionMotion.ALIGN_DEGREES_PER_TICK));
            owner.yBodyRot = owner.yHeadRot = owner.getYRot();
            ready = aligned(target);
            if (ready) {
                String why = ConstrictionSession.rejection(owner,target,attack,owner.position());
                if (why != null) { fail("commit: " + why); return false; }
            }
            return !ready;
        }
        if (!submitted) {
            if (!owner.getNavigation().moveTo(path,speed)) { fail("path refused"); return false; }
            submitted = true;
        }
        return true;
    }

    void clear() {
        if (submitted) owner.getNavigation().stop();
        attack = null;
        path = null;
        feet = anchor = null;
        aligning = ready = submitted = false;
    }

    private DigimonAttack fail(String why) {
        clear();
        lastFail = why;
        // Frozen prey cannot walk off and Cold prey barely can; retry almost at once while the goal keeps closing in.
        retryUntil = owner.tickCount + (preyFrozen ? ConstrictionMotion.FROZEN_RETRY_TICKS : ConstrictionMotion.APPROACH_RETRY_TICKS);
        return null;
    }

    /** Both swimming: the cast floats at the prey's depth instead of standing on a floor. */
    private boolean afloat() { return owner.isInWater() && target != null && target.isInWater(); }
    private boolean settled() { return owner.onGround() || afloat(); }
    /** Where the cast starts from the current column: the floor, or the prey's depth when afloat. */
    private Vec3 stance() { return afloat() ? new Vec3(owner.getX(),target.getY(),owner.getZ()) : owner.position(); }
    private boolean aligned(LivingEntity prey) {
        return aligning && settled() && owner.isAttackReady(attack)
                && (!afloat() || Math.abs(owner.getY() - feet.y) <= .1)
                && Math.abs(Mth.wrapDegrees(AttackGeometry.yaw(owner.position(),prey.position())-owner.getYRot())) <= 8;
    }

    private boolean plan(LivingEntity prey, DigimonAttack move) {
        if (submitted) owner.getNavigation().stop();
        submitted = aligning = ready = false;
        anchor = prey.position();
        Vec3 here = stance();
        String hereWhy = settled() ? ConstrictionSession.rejection(owner,prey,move,here) : "airborne";
        if (hereWhy == null) {
            attack = move;
            feet = here;
            aligning = true;
            return true;
        }
        Vec3 away = owner.position().subtract(prey.position()).multiply(1,0,1).normalize();
        if (away.lengthSqr() < .001) away = new Vec3(0,0,1);
        var candidates = new ArrayList<Vec3>();
        boolean afloat = afloat();
        // Rings start where the caster's body clears the prey's, and stop just inside wrap reach.
        double nearest = Math.hypot(prey.getBbWidth()/2,prey.getBbWidth()/2) + owner.getBbWidth()/2 + .15;
        var rings = new ArrayList<Double>();
        for (double radius = nearest; radius <= move.range() - .3; radius += .4) rings.add(radius);
        if (rings.isEmpty()) rings.add(nearest);
        for (double radius : rings) {
            for (int step = 0; step < 8; step++) {
                Vec3 point = prey.position().add(away.yRot((float)(step*Math.PI/4)).scale(radius));
                if (afloat) {
                    if (owner.level().getFluidState(BlockPos.containing(point)).is(FluidTags.WATER)) candidates.add(point);
                    continue;
                }
                var hit = owner.level().clip(new ClipContext(point.add(0,1,0),point.add(0,-1,0),
                        ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,owner));
                if (hit.getType() != HitResult.Type.MISS && Math.abs(hit.getLocation().y-prey.getY()) <= .25)
                    candidates.add(hit.getLocation());
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distanceToSqr(owner.position())));
        String why = "here: " + hereWhy;
        // Validate the actual block-snapped path endpoint, not an ideal radial point.
        for (int i = 0; i < Math.min(12,candidates.size()); i++) {
            Vec3 point = candidates.get(i);
            Path candidate = owner.getNavigation().createPath(point.x,point.y,point.z,0);
            if (candidate == null || !candidate.canReach() || candidate.getNodeCount() == 0) { why = "no path to a stance"; continue; }
            Vec3 end = candidate.getEntityPosAtNode(owner,candidate.getNodeCount()-1);
            if (afloat) end = new Vec3(end.x,prey.getY(),end.z);
            if (owner.getBoundingBox().move(end.subtract(owner.position())).intersects(prey.getBoundingBox())) { why = "stance inside prey"; continue; }
            String endWhy = ConstrictionSession.rejection(owner,prey,move,end);
            if (endWhy != null) { why = "stance: " + endWhy; continue; }
            attack = move;
            path = candidate;
            feet = end;
            return true;
        }
        lastFail = candidates.isEmpty() ? "no level stance around prey (" + hereWhy + ")" : why;
        return false;
    }
}
