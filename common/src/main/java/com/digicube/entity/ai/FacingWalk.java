package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Walking a path while facing somewhere else: the body turns to a chosen heading (the enemy, an incoming weapon,
 * a throw's line) and steps along the path in its own frame, so the directional gait plays side steps and backing
 * steps instead of turning round. The path comes from the ordinary navigation, which is kept stopped.
 */
public final class FacingWalk {
    private final DigimonEntity mob;
    private Path path;
    private Vec3 goal;
    private int repathAt;
    /** Where this tick steered toward (the next path node or the goal), for traces. */
    public Vec3 steeredAt;

    public FacingWalk(DigimonEntity mob) { this.mob = mob; }

    /** Step toward {@code goal} at {@code speed} (a movement speed modifier), facing {@code yaw}. False once there, or when it cannot get there. */
    public boolean to(Vec3 goal, double speed, float yaw) {
        if (this.goal == null || this.goal.distanceToSqr(goal) > .5 || mob.tickCount >= repathAt || path == null) {
            this.goal = goal;
            path = mob.getNavigation().createPath(goal.x, goal.y, goal.z, 0);
            repathAt = mob.tickCount + 12;
        }
        mob.getNavigation().stop();
        Vec3 at = mob.position();
        Vec3 point = goal;
        if (path != null) {
            while (!path.isDone()) {
                Vec3 node = path.getEntityPosAtNode(mob, path.getNextNodeIndex());
                if (node.subtract(at).horizontalDistanceSqr() < Math.max(.3, mob.getBbWidth() * mob.getBbWidth() * .2)) path.advance();
                else break;
            }
            if (!path.isDone()) point = path.getEntityPosAtNode(mob, path.getNextNodeIndex());
            else if (!path.canReach() && path.getEndNode() != null && goal.subtract(at).horizontalDistanceSqr() > 1) {
                // The path ended short of the goal: stand where it ends.
                halt(yaw);
                return false;
            }
        } else if (goal.subtract(at).horizontalDistanceSqr() > 9) { halt(yaw); return false; }
        steeredAt = point;
        Vec3 d = point.subtract(at).multiply(1, 0, 1);
        if (point == goal && d.lengthSqr() < .12) { halt(yaw); return false; }
        steer(d, speed, yaw);
        if (point.y > at.y + .55 && (mob.horizontalCollision || d.lengthSqr() < 2.2) && mob.onGround()) mob.getJumpControl().jump();
        return true;
    }

    /** Stand, turning to {@code yaw}. */
    public void halt(float yaw) {
        mob.getNavigation().stop();
        if (mob.getMoveControl() instanceof DigimonMoveControl control) control.walkFacing(yaw, 0, 0, 0);
    }

    /** Walk along the world direction {@code d} this tick, facing {@code yaw}. */
    public void steer(Vec3 d, double speed, float yaw) {
        if (!(mob.getMoveControl() instanceof DigimonMoveControl control)) {
            Vec3 to = mob.position().add(d.normalize().scale(2));
            mob.getMoveControl().setWantedPosition(to.x, to.y, to.z, speed);
            return;
        }
        // Resolve the step in the frame the body will have after this tick's turn.
        float next = mob.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - mob.getYRot()), -24, 24);
        double r = Math.toRadians(next);
        Vec3 forward = new Vec3(-Math.sin(r), 0, Math.cos(r)), left = new Vec3(Math.cos(r), 0, Math.sin(r));
        double f = d.dot(forward), l = d.dot(left);
        control.walkFacing(yaw, f, l, speed);
    }

    public void clear() { path = null; goal = null; }
}
