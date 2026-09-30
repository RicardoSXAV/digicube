package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AnimationState;

/** Per-frame snapshot of a {@link DigimonEntity} that the model and renderer read from. */
public class DigimonRenderState extends LivingEntityRenderState {
    public com.digicube.fabric.client.evolution.EvolutionPresentation.Snapshot evolution;
    public final NativeEffectState authoredEffect = new NativeEffectState();
    /** The species' caster-anchored clip for the attack in progress ({@code attack_effects}); drawn while the name is set. */
    public final NativeEffectState attackEffect = new NativeEffectState();
    public String attackEffectName;

    public float groundRunAmount;
    /** A rider's attack on the run: only the upper body plays it, turned this many degrees from the body toward the aim. */
    public boolean attackUpperBody;
    public float attackTwist;
    /** Ticks into a rider's jet charge (partial included), -1 when none runs. */
    public float riderCharge = -1;
    /** The leap's clip tick (-1 on the ground) and how much of the pose it has; see DigimonEntity.tickLeapPose. */
    public float leapTick = -1, leapWeight;
    /** How far the body pitches along a pounce's burst, degrees up (DigimonEntity.getPouncePitch). */
    public float pouncePitch;
    /** A breath of puffs (FrostBreath) this frame, and the effect model it is drawn with; none while the effect is null. */
    public final FrostBreathRenderer.State breath = new FrostBreathRenderer.State();
    public String breathEffect;
    /** A pounce's bite: its impact effect playing where the jaws met; none while the effect is null. */
    public final NativeEffectState bite = new NativeEffectState();
    public String biteEffect;
    /** An electric discharge's bolts (ArcDischarge) this frame; none while empty. */
    public final ArcRenderer.State arc = new ArcRenderer.State();
    /** The hanging cloth's simulation, one per entity, kept by the renderer between frames. */
    public com.digicube.fabric.client.model.ClothChains.State cloth;
    /** The hanging chains' simulation, one per entity, kept by the renderer between frames. */
    public com.digicube.fabric.client.model.RopeChains.State ropes;
    /** The tails' simulation, one per entity, kept by the renderer between frames. */
    public com.digicube.fabric.client.model.TailChains.State tails;
    /** A serpent's drawn trail, one per entity, kept by the renderer between frames; null for any other body. */
    public com.digicube.fabric.client.model.SerpentSpine.State serpent;
    /**
     * A serpent's body: how much of it lies along its trail (none while a wrap coils it), how far it sways aside (blocks),
     * and how far its head dives (degrees, nose down).
     */
    public float spineWeight, spineWave, spinePitch;
    /** Blocks lower a serpent's swimming head breathes its stream from than the land pose its motion was measured in. */
    public float streamDrop;
    /** A serpent's shadow: soft blobs along the body where it lies, in place of the one under its feet. */
    public final java.util.List<SerpentShadow> serpentShadows = new java.util.ArrayList<>();
    /** One blob of a serpent's shadow: its middle relative to the drawn feet, its radius and the ground it falls on. */
    public record SerpentShadow(float x, float y, float z, float radius, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> pieces) {}
    /** This tick's move went from the ground to the ground: a rise or drop in it is a step, not a leap or a fall. */
    public boolean groundedMove;
    /** How far into a skid on ice the body is, 0 to 1 (DigimonEntity.getSkid): the model's skid pose takes over the gait. */
    public float skid;
    /** Directional gait: shares forwards, backwards, left, right. */
    public float[] gaitShares = {1, 0, 0, 0};
    /** Directional gait: the share a turn on the spot takes, positive turning right: the forelegs step right, the hind legs left. */
    public float pivotTurn;
    /** A thrower: the charged throw in its hands (0 to 1) and whether its returning weapon is on its back. */
    public float throwCharge;
    public boolean boneCarried = true;
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
    /** How far an approach has come down, 0 where it began to 1 on the ground ({@code DigimonEntity.landingProgress}). */
    public float flightLandingProgress;
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
    /**
     * A whip (WhipArm, a rider's or the AI's): how much of the whipping arm's pose it has, which arm (and its side, +1 the
     * left), and each section's yaw and pitch relative to the body, the pad last, in pairs.
     */
    public float whipWeight;
    public com.digicube.digimon.WhipAttacks.Arm whipArm;
    public int whipSide = 1;
    public float[] whipAngles = new float[0];
    /** Slow land-cycle clock for aquatic species. */
    public float groundAnimationPhase;
    public float groundAnimationAmount;
    public net.minecraft.world.phys.Vec3 mountAnchor = net.minecraft.world.phys.Vec3.ZERO;
    /** Smoothed bank into a swimming turn, in degrees. */
    public float swimBank;
    /** A swimmer's deep bank into a hard turn (swim_bank), its dash and its leap from the water (0 to 1), and the degrees of a barrel roll. */
    public float turnBank, swimDash, swimLeap, swimRoll, swimSurface;

    /** Clock of the attack animation in progress; copied from the entity every frame. */
    public final AnimationState attackAnimation = new AnimationState();

    /**
     * Attack clip name to play with {@link #attackAnimation}, e.g. {@code claw},
     * {@code claw_mirrored}, {@code pepper_breath}; null while idle. Generated models look
     * it up in their baked animation map.
     */
    public String attackAnimationName;
}
