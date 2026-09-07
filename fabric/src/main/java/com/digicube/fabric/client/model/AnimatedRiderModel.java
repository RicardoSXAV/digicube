package com.digicube.fabric.client.model;

import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.world.phys.Vec3;

/** Optional visual attachment carried by the same joints as a mount's back. */
public interface AnimatedRiderModel {
    /**
     * Follow the animated back without changing the physical rider attachment.
     * @param state mount pose
     * @return local offset from the fixed physical seat, in blocks
     */
    Vec3 riderOffset(DigimonRenderState state);

    /**
     * Fit the rider to the mount's width.
     * @return seated leg angles
     */
    RiderPose riderPose();

    /**
     * Include the complete animated silhouette in frustum checks.
     * @return extra space for long tails and moving limbs, before model scale
     */
    double cullingMargin();

    /**
     * Symmetric seated leg pose, in radians.
     * @param pitch forward leg pitch
     * @param splay outward leg yaw
     * @param roll outward leg roll
     */
    record RiderPose(float pitch, float splay, float roll) {}
}
