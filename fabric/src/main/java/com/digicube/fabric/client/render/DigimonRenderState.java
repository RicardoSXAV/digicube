package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AnimationState;

/** Per-frame snapshot of a {@link DigimonEntity} that the model and renderer read from. */
public class DigimonRenderState extends LivingEntityRenderState {
    public com.digicube.fabric.client.evolution.EvolutionPresentation.Snapshot evolution;
    public final NativeEffectState authoredEffect = new NativeEffectState();

    public float groundRunAmount;
    /** The hanging cloth's simulation, one per entity, kept by the renderer between frames. */
    public com.digicube.fabric.client.model.ClothChains.State cloth;
    /** Directional gait: shares forwards, backwards, left, right. */
    public float[] gaitShares = {1, 0, 0, 0};
    /** The spike wave its rider is aiming, drawn as a phantom; heights null when there is none. */
    public final NativeEffectState riderAim = new NativeEffectState();
    public net.minecraft.world.phys.Vec3 kineticOffset = net.minecraft.world.phys.Vec3.ZERO;
    public com.digicube.entity.ai.FlightPhase flightPhase = com.digicube.entity.ai.FlightPhase.GROUNDED;
    public float flightPhaseTime;
    public float flightLoopTime;
    public float flightWalkAmount;
    public float aerialBank;
    public float aerialPitch;
    public float flightGroundDistance=3;
    public com.digicube.digimon.DigimonAttack attackDefinition;
    public float attackAimPitch;
    public com.digicube.digimon.ConstrictionMotion.Fit constrictionFit = new com.digicube.digimon.ConstrictionMotion.Fit(34,36);
    public net.minecraft.world.phys.Vec3 constrictionOffset = net.minecraft.world.phys.Vec3.ZERO;
    public final NativeEffectState fistEffect = new NativeEffectState();
    public final MegaFlameRenderState mouthFlame = new MegaFlameRenderState();
    public final BlueBlasterRenderState blueBlaster = new BlueBlasterRenderState();

    public Identifier species = DigimonEntity.DEFAULT_SPECIES;
    public float modelScale = 0.75F;

    /** The tamer is riding this Digimon; its visible seat must remain aligned. */
    public boolean isBeingRidden;

    /** Smooth walk/run blend from the server's follow state, zero at walking pace. */
    public float runAnimationAmount;

    /** Species-authored swimming pose, independent of the amount of forward movement. */
    public float swimAnimationAmount;
    /** Exact fluid state for matching authored water attack poses and server volumes. */
    public boolean attackInWater;
    /** Interpolated per-entity swimming clock, in authored ticks. */
    public float swimAnimationPhase;
    /** Observed movement blended between the glide and full stroke clips. */
    public float swimMotionAmount;
    /** Slow land-cycle clock for aquatic species. */
    public float groundAnimationPhase;
    public float groundAnimationAmount;
    public net.minecraft.world.phys.Vec3 mountAnchor = net.minecraft.world.phys.Vec3.ZERO;
    /** Smoothed bank into a swimming turn, in degrees. */
    public float swimBank;

    /** Clock of the attack animation in progress; copied from the entity every frame. */
    public final AnimationState attackAnimation = new AnimationState();

    /**
     * Harness animation name to play with {@link #attackAnimation}, e.g. {@code claw},
     * {@code claw_mirrored}, {@code pepper_breath}; null while idle. Generated models look
     * it up in their baked animation map.
     */
    public String attackAnimationName;
}
