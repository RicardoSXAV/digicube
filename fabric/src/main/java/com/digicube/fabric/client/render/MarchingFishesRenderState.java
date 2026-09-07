package com.digicube.fabric.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Interpolated wave orientation and independent splash clock. */
public class MarchingFishesRenderState extends EntityRenderState {
    public float yRot;
    public float xRot;
    public boolean splash;
    public float splashTicks;
}
