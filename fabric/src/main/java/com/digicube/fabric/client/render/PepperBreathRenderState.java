package com.digicube.fabric.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame snapshot of a Pepper Breath fireball: where it is flying. */
public class PepperBreathRenderState extends EntityRenderState {

    /** Interpolated yaw and pitch of the flight direction, in degrees (projectile convention). */
    public float yRot;
    public float xRot;
}
