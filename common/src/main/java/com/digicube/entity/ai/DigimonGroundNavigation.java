package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla ground navigation walked as an animal walks it:
 * <ul>
 * <li>it steers at the farthest of the next few nodes on its floor it can walk to in a straight line ({@link #cutAhead}),
 * so a diagonal is one straight walk and a path laid afresh from where it stands never sends it back to the middle of
 * its own block first (vanilla steered at every block's middle in turn and zig-zagged);</li>
 * <li>with no enemy it slows as it arrives, over the last strides to the path's end ({@link #ARRIVE_TICKS}), where vanilla
 * ran on at full pace and stopped dead;</li>
 * <li>it also counts the steering target as node arrival, for bodies wider than a block ({@link SteeringArrival}).</li>
 * </ul>
 */
public final class DigimonGroundNavigation extends GroundPathNavigation {
    /** Nodes ahead of the next one it may cut to, and blocks off it looks for them at most. */
    private static final int LOOKAHEAD = 4;
    private static final double LOOKAHEAD_REACH = 4;
    /** Blocks between the samples of a straight walk's check. */
    private static final double SAMPLE = .3;
    /** Ticks of its pace it slows down over as it arrives, the blocks that makes at least and at most, and the share of its pace it keeps at the end. */
    private static final double ARRIVE_TICKS = 9, ARRIVE_LEAST = .6, ARRIVE_MOST = 2.2, ARRIVE_KEEP = .3;
    /** Ticks between two looks ahead along the same node. */
    private static final int CUT_EVERY = 4;
    private int cutAt = -1, cutTick;

    /**
     * Bind the land navigation to its creature.
     * @param mob the Digimon
     * @param level the level it walks in
     */
    public DigimonGroundNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected void followThePath() {
        int before = path.getNextNodeIndex();
        super.followThePath();
        SteeringArrival.advance(path, mob, before, maxDistanceToWaypoint, getMaxVerticalDistanceToWaypoint());
        if (path != null && !path.isDone() && mob.onGround() && (path.getNextNodeIndex() != cutAt || ++cutTick >= CUT_EVERY)) cutAhead();
    }

    @Override
    public void tick() {
        super.tick();
        if (isDone() || !(mob instanceof DigimonEntity digimon) || digimon.getTarget() != null || digimon.isAttacking()) return;
        // Arriving: the pace falls off over the last strides, to a share of it at the end (pace goes as the modifier squared).
        double left = remaining();
        double pace = mob.getDeltaMovement().horizontalDistance() / .546;
        double from = Mth.clamp(pace * ARRIVE_TICKS, ARRIVE_LEAST, ARRIVE_MOST);
        if (left >= from) return;
        double share = Mth.lerp(left / from, ARRIVE_KEEP, 1);
        Vec3 target = path.getNextEntityPos(mob);
        mob.getMoveControl().setWantedPosition(target.x, getGroundY(target), target.z, speedModifier * Math.sqrt(share));
    }

    /** Blocks along the path from the body to its end. */
    private double remaining() {
        Vec3 at = mob.position();
        double sum = 0;
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Vec3 node = path.getEntityPosAtNode(mob, i);
            sum += node.subtract(at).horizontalDistance();
            at = node;
        }
        return sum;
    }

    /**
     * Steer at the farthest of the next {@link #LOOKAHEAD} nodes on the body's own floor that it can walk to in a straight
     * line, its whole width on walkable ground all the way and nothing in its way (string pulling): it then walks there
     * straight instead of from block middle to block middle.
     */
    private void cutAhead() {
        cutAt = path.getNextNodeIndex();
        cutTick = 0;
        Vec3 from = mob.position();
        int floor = Mth.floor(from.y + .5), next = path.getNextNodeIndex();
        if (path.getNode(next).y != floor) return;
        int last = next;
        while (last + 1 < path.getNodeCount() && last + 1 <= next + LOOKAHEAD && path.getNode(last + 1).y == floor) last++;
        for (int i = last; i > next; i--) {
            Vec3 to = path.getEntityPosAtNode(mob, i);
            if (to.subtract(from).horizontalDistanceSqr() > LOOKAHEAD_REACH * LOOKAHEAD_REACH) continue;
            if (walksStraight(from, to)) {
                path.setNextNodeIndex(i);
                cutAt = i;
                return;
            }
        }
    }

    /** The body walks from {@code from} to {@code to} in a straight line: its box free and its whole width on walkable ground. */
    private boolean walksStraight(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from).multiply(1, 0, 1);
        double length = d.length();
        if (length < 1.0E-3) return true;
        Vec3 along = d.scale(1 / length), side = new Vec3(-along.z, 0, along.x).scale(mob.getBbWidth() * .5);
        int steps = (int) Math.ceil(length / SAMPLE);
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int s = 1; s <= steps; s++) {
            Vec3 p = from.add(d.scale((double) s / steps));
            if (!level.noCollision(mob, mob.getDimensions(mob.getPose()).makeBoundingBox(p))) return false;
            for (Vec3 q : new Vec3[]{p, p.add(side), p.subtract(side)}) {
                cell.set(q.x, from.y + .5, q.z);
                if (WalkNodeEvaluator.getPathTypeStatic(mob, cell) != PathType.WALKABLE) return false;
            }
        }
        return true;
    }
}
