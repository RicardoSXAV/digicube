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
    /** A thrown bone's spin axis in world space while it flies (null: spun about the vertical). */
    public net.minecraft.world.phys.Vec3 spinAxis;
    public String clip="effect";
    /** Where the effect stands relative to the entity drawing it; a summoned strike is drawn at its landing point. */
    public net.minecraft.world.phys.Vec3 offset=net.minecraft.world.phys.Vec3.ZERO;
    public boolean emissive=true;
    /** The caster's root as drawn (x, y, z, xRot, yRot, zRot), for an effect that follows it; null for one that does not. */
    public float[] root;
    /** A shocking shot's bolts to the bodies it struck (KineticAttacks.Proximity), relative to the shot. */
    public final ArcRenderer.State arc = new ArcRenderer.State();
    /** An electric shot's own lightning about its ball (ShotStyle.ELECTRIC). */
    public final ShockBall.State ball = new ShockBall.State();
}
