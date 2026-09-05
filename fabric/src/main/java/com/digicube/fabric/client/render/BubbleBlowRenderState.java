package com.digicube.fabric.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Snapshot shared by the bubble billboard renderer and the generated model. */
public class BubbleBlowRenderState extends EntityRenderState {
    /** Creates an empty snapshot for the renderer to fill. */
    public BubbleBlowRenderState() {}

    /** Flight yaw, in degrees. */
    public float yRot;
    /** Flight pitch, in degrees. */
    public float xRot;
    /** Whether the server has ended the damaging part of this volley. */
    public boolean popped;
    /** Fractional time in the six-tick pop animation. */
    public float popTicks;
    /** Camera-facing pitch in model space, in radians. */
    public float billboardPitch;
    /** Camera-facing yaw in model space, in radians. */
    public float billboardYaw;
}
