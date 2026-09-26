package com.digicube.fabric.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Clock and flight orientation for the authored fire effect. */
public class MegaFlameRenderState extends EntityRenderState {
    public float yRot;
    public float xRot;
    public boolean burst;
    public boolean charging;
    public float burstTicks;
}
