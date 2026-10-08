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
    /** A run lattice's column (DigimonGait.runShare), the pace against the run's own; -1 for a run that is one clip. */
    public float groundRunShare = -1;
    /** A rider's attack on the run: only the upper body plays it, turned this many degrees from the body toward the aim. */
    public boolean attackUpperBody;
    public float attackTwist;
    /** Ticks into a rider's jet charge (partial included), -1 when none runs. */
    public float riderCharge = -1;
    /**
     * A held rush (BullRush): ticks since the press (partial included; held where it struck through its blow), -1 with
     * none; whether its brace stands, whether its blow is playing, and the ticks its brace takes.
     */
    public float rushTicks = -1;
    public boolean rushStanding, rushBlow;
    public int rushBuild = 1;
    /**
     * A spin in the shell (ShellSpin): its phase (null with none), ticks into it (partial included), the shell's own turn
     * (degrees), its rate (degrees a tick), how fast it travels (blocks a tick), how far it has spun up (0 to 1), and its
     * wobble: the lean (degrees) and the way it leans (degrees round).
     */
    public com.digicube.entity.ShellSpin.Phase spinPhase;
    public float spinTicks, spinAngle, spinRate, spinSpeed, spinCharge, spinLean, spinWobble;
    /** A seed of this body's own, for what each one does in its own time (the mouth's open and shut spells). */
    public int seed;
    /** The leap's clip tick (-1 on the ground) and how much of the pose it has; see DigimonEntity.tickLeapPose. */
    public float leapTick = -1, leapWeight;
    /**
     * How far into its crouch the body is drawn (0 to 1: every gait clip shares the weight with its {@code _crouch} twin), how
     * much of the pose a combat roll has, and the roll clip's tick (-1 with none); see {@code Agility}.
     */
    public float crouchWeight, rollWeight, rollTick = -1;
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
    /**
     * A serpent's wrap as drawn: the prey's feet (the coil's axis, world blocks), the coil's shape and the body's girth
     * there, which way it winds (1 counterclockwise seen from above, -1 clockwise) and the ticks since the capture
     * (negative through the strike). Inactive: no wrap.
     */
    public static final class Wrap {
        public boolean active;
        public double x, y, z;
        public com.digicube.digimon.ConstrictionCoil.Shape shape;
        public float girth, since;
        public int winding = 1;
    }

    /** A serpent's drawn trail, one per entity, kept by the renderer between frames; null for any other body. */
    public com.digicube.fabric.client.model.SerpentSpine.State serpent;
    /** A serpent's body: how much of it lies along its trail (none while a wrap coils it), and how far it sways aside (blocks). */
    public float spineWeight, spineWave;
    /** Blocks lower a serpent's swimming head breathes its stream from than the land pose its motion was measured in. */
    public float streamDrop;
    /**
     * Where a serpent's joints came to lie this frame (world blocks, x y z a joint, from the chain's start to its tip) and
     * the body's half-thickness at each, for its shadow (SerpentShadow); null until the chain is laid, or for any other body.
     */
    public double[] serpentLine;
    public float[] serpentRadius;
    /** The level a serpent's shadow falls in; null for a screen preview. */
    public net.minecraft.world.level.Level shadowLevel;
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
    /**
     * How a flyer carries itself on the wing ({@code FlightLook}): shares of its cruise, dash, dive and flare postures and of
     * its wings' power, its bank and the pitch its path gives it (degrees), and the wing clock its beats play on.
     */
    public float flightCruise, flightDash, flightDive, flightBrake, flightBank, flightPitch, flightPower, wingClock;
    /** The attack under way was cast on the wing: its clip's wing form plays ({@code <clip>_air}). */
    public boolean attackAir;
    /** The pounce under way was cast from a run: its clip's run form plays ({@code <clip>_run}) when the model has one. */
    public boolean attackRun;
    /**
     * The clip the attack under way cut into (a compound's chain), the time it stopped at, its air and run variants, and the
     * ticks since the cut: the new clip blends in from that pose, not from the gait. Null when it started from the gait.
     */
    public String chainFrom;
    public float chainFromTime, sinceChain;
    public boolean chainFromAir, chainFromRun;
    /**
     * A drawn weapon's stance ({@code AttackStance}): the move's clip prefix (its id's path) and its compound (the stance's
     * lengths, the forms it strikes with), the phase, the ticks into it and whether the weapon is out; null move without one.
     */
    public String stanceMove;
    public com.digicube.digimon.CompoundAttacks.Definition stanceCompound;
    public com.digicube.entity.AttackStance.Phase stancePhase;
    public float stanceTicks;
    public boolean stanceDrawn;
    /**
     * The renderer draws this body's glow parts ({@code glow_parts}) in a pass of their own, full-bright, and its own pass
     * leaves them out; set as the body is submitted.
     */
    public boolean glowSplit;
    /** The body's root as drawn this frame (x, y, z, xRot, yRot, zRot): effects that follow it are drawn in its frame. */
    public final float[] drawnRoot = new float[6];
    /** The part an attack effect follows ({@code attack_effects.follow}) as drawn this frame, in the model's frame, the same six numbers. */
    public final float[] drawnFollow = new float[6];
    public com.digicube.digimon.DigimonAttack attackDefinition;
    public float attackAimPitch;
    /** A serpent's wrap in progress ({@code ConstrictionCoil}): the coil its body is laid along, and when it closed. */
    public final Wrap wrap = new Wrap();
    public final NativeEffectState fistEffect = new NativeEffectState();
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
