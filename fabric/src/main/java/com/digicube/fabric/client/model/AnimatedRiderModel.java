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
     * How far the animated seat has turned from the mount's heading, degrees; the rider's body turns with it.
     * @param state mount pose, as {@link #riderOffset} just set it up
     */
    default float riderYaw(DigimonRenderState state) { return 0; }

    /**
     * How the rider tips with the seat, degrees nose down and right side down, or null for a rider who sits upright.
     * @param state mount pose, as {@link #riderOffset} just set it up
     */
    default float[] riderLean(DigimonRenderState state) { return null; }

    /**
     * Fit the rider to the mount's width.
     * @return rider leg angles (zero for standing)
     */
    RiderPose riderPose();

    /**
     * Include the complete animated silhouette in frustum checks.
     * @return extra space for long tails and moving limbs, before model scale
     */
    double cullingMargin();

    /**
     * Symmetric rider leg pose, in radians.
     * @param pitch forward leg pitch
     * @param splay outward leg yaw
     * @param roll outward leg roll
     */
    /** Leg pitch, splay and roll in radians, and {@code hips}: model px each leg is set further out, to straddle a wide back. */
    record RiderPose(float pitch, float splay, float roll, float hips) {
        public RiderPose(float pitch, float splay, float roll) { this(pitch, splay, roll, 0); }
    }
}
