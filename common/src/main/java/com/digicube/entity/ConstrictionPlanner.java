package com.digicube.entity;

import com.digicube.digimon.ConstrictionMotion;
import com.digicube.digimon.DigimonAttack;
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
    private boolean aligning, ready, submitted;

    ConstrictionPlanner(DigimonEntity owner) { this.owner = owner; }

    boolean aligning() { return aligning; }
    boolean ready() { return ready; }

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
        if (owner.tickCount < retryUntil) return null;
        if (checkedTick == owner.tickCount) return attack;
        checkedTick = owner.tickCount;
        if (attack != null && owner.tickCount >= deadline) return fail();
        if (attack != null && owner.position().distanceToSqr(progressPosition) > .04) {
            progressPosition = owner.position();
            progressTick = owner.tickCount;
        }
        if (attack != null && !aligning && owner.tickCount-progressTick >= 24) return fail();
        // A walking target can invalidate the stance, but never grants an endless chase.
        boolean moved = anchor != null && anchor.distanceToSqr(prey.position()) > .25;
        if (attack == null || moved) {
            boolean continuing = attack != null;
            if (!plan(prey,move)) return fail();
            if (!continuing) deadline = owner.tickCount + ConstrictionMotion.APPROACH_TICKS;
            progressPosition = owner.position();
            progressTick = owner.tickCount;
        }
        if (!aligning && owner.onGround() && (owner.position().distanceToSqr(feet) < .36
                || submitted && owner.getNavigation().isDone())) {
            // Validate the actual arrival, including any navigation stopping tolerance.
            if (ConstrictionSession.prepare(owner,prey,move) == null) return fail();
            aligning = true;
        }
        ready = aligning && owner.onGround() && owner.isAttackReady(move)
                && Math.abs(Mth.wrapDegrees(AttackGeometry.yaw(owner.position(),prey.position())-owner.getYRot())) <= 8;
        if (ready && ConstrictionSession.prepare(owner,prey,move) == null) return fail();
        return attack;
    }

    /** Called before ordinary attack selection/navigation. True owns this goal tick. */
    boolean steer(double speed) {
        if (attack == null) return false;
        if (aligning) {
            owner.getNavigation().stop();
            owner.setDeltaMovement(0,owner.getDeltaMovement().y,0);
            owner.setYRot(Mth.approachDegrees(owner.getYRot(),AttackGeometry.yaw(owner.position(),target.position()),ConstrictionMotion.ALIGN_DEGREES_PER_TICK));
            owner.yBodyRot = owner.yHeadRot = owner.getYRot();
            ready = owner.isAttackReady(attack) && owner.onGround()
                    && Math.abs(Mth.wrapDegrees(AttackGeometry.yaw(owner.position(),target.position())-owner.getYRot())) <= 8;
            if (ready && ConstrictionSession.prepare(owner,target,attack) == null) { fail(); return false; }
            return !ready;
        }
        if (!submitted) {
            if (!owner.getNavigation().moveTo(path,speed)) { fail(); return false; }
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

    private DigimonAttack fail() {
        clear();
        retryUntil = owner.tickCount + ConstrictionMotion.APPROACH_RETRY_TICKS;
        return null;
    }

    private boolean plan(LivingEntity prey, DigimonAttack move) {
        if (submitted) owner.getNavigation().stop();
        submitted = aligning = ready = false;
        anchor = prey.position();
        if (ConstrictionSession.prepare(owner,prey,move) != null) {
            attack = move;
            feet = owner.position();
            aligning = true;
            return true;
        }
        Vec3 away = owner.position().subtract(prey.position()).multiply(1,0,1).normalize();
        if (away.lengthSqr() < .001) away = new Vec3(0,0,1);
        var candidates = new ArrayList<Vec3>();
        for (double radius : new double[]{1.8,2.7}) {
            for (int step = 0; step < 8; step++) {
                Vec3 point = prey.position().add(away.yRot((float)(step*Math.PI/4)).scale(radius));
                var hit = owner.level().clip(new ClipContext(point.add(0,1,0),point.add(0,-1,0),
                        ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,owner));
                if (hit.getType() != HitResult.Type.MISS && Math.abs(hit.getLocation().y-prey.getY()) <= .25)
                    candidates.add(hit.getLocation());
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distanceToSqr(owner.position())));
        // Validate the actual block-snapped path endpoint, not an ideal radial point.
        for (int i = 0; i < Math.min(8,candidates.size()); i++) {
            Vec3 point = candidates.get(i);
            Path candidate = owner.getNavigation().createPath(point.x,point.y,point.z,0);
            if (candidate == null || !candidate.canReach() || candidate.getNodeCount() == 0) continue;
            Vec3 end = candidate.getEntityPosAtNode(owner,candidate.getNodeCount()-1);
            if (owner.getBoundingBox().move(end.subtract(owner.position())).intersects(prey.getBoundingBox())
                    || ConstrictionSession.prepareAt(owner,prey,move,end) == null) continue;
            attack = move;
            path = candidate;
            feet = end;
            return true;
        }
        return false;
    }
}
