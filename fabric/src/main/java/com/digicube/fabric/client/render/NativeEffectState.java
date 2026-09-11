package com.digicube.fabric.client.render;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
/** Independent clock and per-spike terrain placement. */
public final class NativeEffectState extends EntityRenderState {
    public float tick, yaw, scale=.5F;
    public float[] heights;
}
