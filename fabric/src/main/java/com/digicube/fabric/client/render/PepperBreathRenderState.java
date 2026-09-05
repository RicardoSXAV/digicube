package com.digicube.fabric.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Per-frame snapshot of a Pepper Breath fireball: where it is flying, and how its flat
 * planes must turn to face the camera (filled in by the renderer, read by the model).
 */
public class PepperBreathRenderState extends EntityRenderState {

    /** Interpolated yaw and pitch of the flight direction, in degrees (projectile convention). */
    public float yRot;
    public float xRot;

    /** Roll of the flame sheet about the travel axis so its face points at the camera (radians). */
    public float tailRoll;
    /** Pitch and yaw of the ball sprite so it faces the camera (radians, model space). */
    public float headPitch;
    public float headYaw;
    /** Roll of the ball sprite in its own plane so its hot spot leads the flight (radians). */
    public float headRoll;
}
