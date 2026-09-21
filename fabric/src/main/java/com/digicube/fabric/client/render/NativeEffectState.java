package com.digicube.fabric.client.render;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
/** Independent clock and per-spike terrain placement. */
public final class NativeEffectState extends EntityRenderState {
    public float tick, yaw, scale=.5F;
    public float aimPitch;
    public net.minecraft.world.phys.Vec3 aimPivot=net.minecraft.world.phys.Vec3.ZERO;
    public float[] heights;
    public java.util.Set<String> hidden = java.util.Set.of();
    public float pitch;
    public String projectile;
    public String clip="effect";
    /** Where the effect stands relative to the entity drawing it; a summoned strike is drawn at its landing point. */
    public net.minecraft.world.phys.Vec3 offset=net.minecraft.world.phys.Vec3.ZERO;
    public boolean emissive=true;
}
