package com.digicube.entity.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Node arrival judged against the point navigation actually steers toward.
 *
 * <p>Vanilla steers a body wider than a block to a block corner (the node plus half of
 * the rounded-up width) yet only counts the block centre as reached, within half the
 * width. For a 1.1-block body that leaves five hundredths of a block of slack: the
 * creature overshoots the corner, turns back through the tiny remaining offset, and
 * appears to spin in place until the path is dropped. Checking the steering target as
 * well lets such a body finish every node it was sent to.
 */
public final class SteeringArrival {
    private SteeringArrival() {}

    /**
     * Whether a creature stands at the point it is being steered toward.
     * @param mobPos the creature position
     * @param target the position navigation steers toward for the next node
     * @param node the next node
     * @param horizontal maximum horizontal distance per axis, as vanilla uses for the block centre
     * @param vertical maximum vertical distance to the node
     * @return whether the node counts as reached
     */
    public static boolean reached(Vec3 mobPos, Vec3 target, Node node, float horizontal, float vertical) {
        return Math.abs(mobPos.x - target.x) < horizontal && Math.abs(mobPos.z - target.z) < horizontal
                && Math.abs(mobPos.y - node.y) < vertical;
    }

    /**
     * After the vanilla centre check has run, advance past a node the creature has reached at
     * its steering target instead. Does nothing when vanilla already advanced or finished.
     * @param path the current path, possibly dropped by stuck detection
     * @param mob the creature following it
     * @param indexBefore the next node index before the vanilla check
     * @param horizontal maximum horizontal distance per axis
     * @param vertical maximum vertical distance to the node
     */
    public static void advance(Path path, Mob mob, int indexBefore, float horizontal, float vertical) {
        if (path == null || path.isDone() || path.getNextNodeIndex() != indexBefore) return;
        if (reached(mob.position(), path.getNextEntityPos(mob), path.getNextNode(), horizontal, vertical)) {
            path.advance();
        }
    }
}
