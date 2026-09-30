package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.AttackMotion;
import com.digicube.digimon.FuelReserve;
import com.digicube.digimon.IceCombo;
import com.digicube.digimon.BreathAttacks;
import com.digicube.digimon.FreezeMark;
import com.digicube.digimon.PounceAttacks;
import com.digicube.registry.DCEffects;
import net.minecraft.world.effect.MobEffectInstance;
import com.digicube.digimon.FlightReserve;
import com.digicube.digimon.DigimonFlight;
import com.digicube.entity.ai.DigimonFlightGoal;
import com.digicube.entity.ai.FlightPhase;
import com.digicube.registry.DCDamageTypes;
import com.digicube.registry.DCEntityTypes;
import com.digicube.digimon.DigimonBody;
import com.digicube.digimon.DigimonGait;
import com.digicube.digimon.DigimonLocomotion;
import com.digicube.digimon.DamageLedger;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.entity.ai.DigimonAttackGoal;
import com.digicube.entity.ai.DigimonLookControl;
import com.digicube.entity.ai.DigimonMoveControl;
import com.digicube.entity.ai.DigimonAmphibiousNavigation;
import com.digicube.entity.ai.DigimonGroundNavigation;
import com.digicube.entity.ai.FollowOwnerGoal;
import com.digicube.entity.ai.OwnerHurtByTargetGoal;
import com.digicube.entity.ai.OwnerHurtTargetGoal;
import com.digicube.party.PartyManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.PlayerRideable;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one entity type every Digimon shares. Which Digimon it is comes from the
 * {@link DigimonSpecies} it points at, so adding a species never needs a new entity.
 *
 * <p>Everything that varies per individual lives here: the species, the tamer (owner),
 * attack cooldowns and the attack in progress. Everything shared by the species stays on
 * the immutable species sheet, including the attack list.
 *
 * <p>Combat is server-authoritative: {@link #startAttack} runs the attack timeline in
 * {@link #customServerAiStep} (damage or projectile on the hit tick) and tells clients to
 * play the matching keyframe animation through an entity event.
 *
 * <p>Every Digimon has a level and XP, scaled and awarded by the rules in
 * {@link Progression}. A wild one keeps a {@link DamageLedger} of the health it lost to
 * partners and, when defeated, splits its yield among them ({@code ExperienceAward}).
 *
 * <p>Summon a wild one with {@code /digicube spawn agumon}; a partner with
 * {@code /digicube give agumon}.
 */
public class DigimonEntity extends PathfinderMob implements OwnableEntity, PlayerRideable {
    private static final EntityDataAccessor<String> DATA_EVOLUTION = SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.STRING);
    private com.digicube.digimon.EvolutionState evolution = new com.digicube.digimon.EvolutionState();
    private int evolutionAttackUntil;
    public com.digicube.digimon.EvolutionState evolution() { return evolution; }
    public com.digicube.digimon.EvolutionEvent evolutionEvent() { return com.digicube.digimon.EvolutionEvent.decode(entityData.get(DATA_EVOLUTION)); }
    public boolean evolutionLocked() { return !level().isClientSide() && (evolution.transitioning() || evolution.phase==com.digicube.digimon.EvolutionState.Phase.EVOLVED && evolution.charge<=0); }
    public void syncEvolutionEvent(boolean preview) {
        if(level().isClientSide())return;
        entityData.set(DATA_EVOLUTION,evolution.transitioning() ? new com.digicube.digimon.EvolutionEvent(evolution.source,evolution.target,evolution.start,evolution.duration,evolution.sequence,preview).encode() : "");
    }
    /** Cosmetic only: no state, charge, history or invulnerability changes. */
    public void previewEvolution(Identifier target,int duration) {
        if(!evolution.transitioning()&&DigimonSpeciesRegistry.get(target).isPresent()) {
            entityData.set(DATA_EVOLUTION,new com.digicube.digimon.EvolutionEvent(getSpeciesId(),target,level().getGameTime(),duration,++evolution.sequence,true).encode());
            EvolutionController.openingSound(this,duration);
        }
    }
    public void stopForEvolution() { cancelAttack();resetConstrictionApproach();getNavigation().stop();attackAnimationState.stop();setDeltaMovement(Vec3.ZERO); }
    public void freezeForEvolution() {
        getNavigation().stop();setDeltaMovement(Vec3.ZERO);setSpeed(0);
        cooldownUntil.replaceAll((id,until)->until+1);chargeRefills.values().forEach(clocks->clocks.replaceAll(until->until+1));
        if(constrictionRetryTick>tickCount)constrictionRetryTick++;
    }
    public void changeEvolutionForm(Identifier form) {
        double fraction=getMaxHealth()>0?(double)getHealth()/getMaxHealth():0;
        stopForEvolution();setNoGravity(false);setFlightPhase(FlightPhase.GROUNDED);
        setSpecies(form);configureSpeciesMovement();applyLevelAttributes();refreshDimensions();
        setFractionHealth(fraction);evolutionAttackUntil=tickCount+Progression.EVOLUTION_ATTACK_DELAY;
    }
    private void setFractionHealth(double fraction) {
        float hp=(float)(fraction*getMaxHealth());
        if(getMaxHealth()>0&&(double)hp/getMaxHealth()>fraction)hp=Math.nextDown(hp);
        setHealth(Math.max(0,hp));
    }
    /** Developer edit retains injury and defeat, independently of the full-heal spawn helper. */
    public void setPartyLevel(int level) {
        double fraction=getMaxHealth()>0?(double)getHealth()/getMaxHealth():0;setLevel(level);xp=0;setFractionHealth(fraction);
        evolution.unlock(getSpeciesId(),getLevel());PartyManager.progressChanged(this);
    }
    @Override public void heal(float amount) { if(!evolutionLocked())super.heal(amount); }
    @Override public boolean isPushable() { return !evolutionLocked() && super.isPushable(); }

    /** NBT key holding the species id. Changing it is a save-data migration. */
    public static final String SPECIES_TAG = "Species";
    /** NBT key holding the owner reference. Changing it is a save-data migration. */
    public static final String OWNER_TAG = "Owner";
    /** NBT keys holding the level and the XP progress within it. Changing them is a save-data migration. */
    public static final String LEVEL_TAG = "Level";
    public static final String XP_TAG = "Xp";
    /** Vanilla remembers a player's hit this long for orb drops; a partner's hit counts as its tamer's. */
    private static final int TAMER_CREDIT_TICKS = 100;

    /** Species used when none was given, e.g. a plain {@code /summon digicube:digimon}. */
    public static final Identifier DEFAULT_SPECIES = Constants.id("agumon");

    /** Where Pepper Breath leaves the model, relative to the feet: mouth height and snout reach. */
    private static final double MOUTH_HEIGHT = 0.95;
    private static final double MOUTH_FORWARD = 0.6;
    private static final int FIREBALL_CHARGE_TICKS = 8;
    /** Centre of the pursed mouth at the Bubble Blow release pose, at model scale 0.75. */
    private static final double BUBBLE_MOUTH_HEIGHT = 0.2026;
    private static final double BUBBLE_MOUTH_FORWARD = 0.3615;

    private static final EntityDataAccessor<String> DATA_SPECIES =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> DATA_ATTACK_AIM_PITCH =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_ATTACK_YAW =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Optional<EntityReference<LivingEntity>>> DATA_OWNER =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE);
    private static final EntityDataAccessor<Boolean> DATA_RUNNING_TO_OWNER =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_SUSTAINED_ATTACK =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_SUSTAINED_TICK =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> DATA_KINETIC_ORIGIN =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<org.joml.Vector3fc> DATA_KINETIC_FRACTION =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Float> DATA_KINETIC_YAW =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    /** Landing point of a summoned strike (block + fraction, exact far from the origin); y below the world = none. */
    private static final EntityDataAccessor<BlockPos> DATA_STRIKE_ORIGIN =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<org.joml.Vector3fc> DATA_STRIKE_FRACTION =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.VECTOR3);
    private static final BlockPos NO_STRIKE = new BlockPos(0, -2048, 0); // the lowest y a packed BlockPos carries
    /** Developer Battle Testing side (0 none, 1 A, 2 B): the side's fighters spare each other and any player may ride one. */
    private static final EntityDataAccessor<Integer> DATA_BATTLE_SIDE =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_WRAP_RADIUS =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_WRAP_PITCH =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<BlockPos> DATA_WRAP_ORIGIN =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<org.joml.Vector3fc> DATA_WRAP_FRACTION =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Float> DATA_WRAP_DISTANCE =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_WRAP_YAW =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    /** Synched so nameplates and the HUD can show it; XP itself stays on the server. */
    private static final EntityDataAccessor<Integer> DATA_LEVEL =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_FLIGHT_PHASE =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_FLIGHT_START =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_FLIGHT_LOOP_START =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> DATA_FLIGHT_FUEL =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    /** The tank of the rider's stream attack, 0..1, kept current only under a rider. */
    private static final EntityDataAccessor<Float> DATA_RIDER_FUEL =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    /** The prey a rider's hold would take right now (entity id, -1 for none), and whether the mount is lunging at it. */
    private static final EntityDataAccessor<Integer> DATA_GRAB_PREY =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_GRAB_LUNGE =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.BOOLEAN);
    /** A whip as the server runs it (a rider's or the AI's): stage, arm, charge and a count of wind-ups (whipCode). */
    private static final EntityDataAccessor<Integer> DATA_WHIP =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    /** Where the AI's whip is aimed, relative to the body (a rider's follows the rider's own view on every side). */
    private static final EntityDataAccessor<Float> DATA_WHIP_YAW =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_WHIP_PITCH =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    /** A jet swimmer's pulses as the side moving the body starts them: a count, whether it thrusts, its length (jetCode). */
    private static final EntityDataAccessor<Integer> DATA_JET_PULSE =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    /** A sea mount's barrel rolls as the side moving the body starts them: a count and the side (rollCode). */
    private static final EntityDataAccessor<Integer> DATA_SWIM_ROLL =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    /**
     * A rider's jet charge: ticks since the press plus one while it runs, -1 through the buck it may end in, 0 otherwise.
     * Not 0 means the server moves the body.
     */
    private static final EntityDataAccessor<Integer> DATA_RIDER_CHARGE =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    /** A rider's drawn shot held raised: how far it has charged, 0 to 1; -1 when nothing is drawn. */
    private static final EntityDataAccessor<Float> DATA_RIDER_DRAW =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    /** A thrower's charged throw: how far the one in its hands has grown, 0 to 1 (the hold and release clips blend by it). */
    private static final EntityDataAccessor<Float> DATA_THROW_CHARGE =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
    /** A thrower carries its returning weapon (drawn on its back); false while it flies or lies lost. */
    private static final EntityDataAccessor<Boolean> DATA_BONE_CARRIED =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.BOOLEAN);
    /** The last electric discharge this body let go ({@link ArcDischarge#encode}): clients draw its bolts. */
    private static final EntityDataAccessor<String> DATA_ARC =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.STRING);
    /** Server: the thrown weapons (see {@link ThrowerState}); inert for a species without them. */
    private ThrowerState thrower;
    public ThrowerState thrower() { if (thrower == null) thrower = new ThrowerState(this); return thrower; }
    private FlightReserve flightReserve;
    private DigimonFlight flightDefinition;
    private boolean needsFlightLanding;
    private com.digicube.entity.ai.AerialRiding aerialRiding;
    public com.digicube.digimon.AerialMount aerialMount() { return getBody().mount().map(DigimonBody.Mount::flight).orElse(null); }
    public com.digicube.entity.ai.AerialRiding aerialRiding() {
        if (aerialRiding==null) aerialRiding=new com.digicube.entity.ai.AerialRiding(this);
        return aerialRiding;
    }
    public void requestFlightLanding() { needsFlightLanding=true; }
    public int flightLiftTick() { return aerialMount()==null ? 13 : aerialMount().liftTick(); }
    public int flightTakeoffTicks() { return aerialMount()==null ? 32 : aerialMount().takeoffTicks(); }
    public int flightLandingTicks() { return aerialMount()==null ? 32 : aerialMount().landingTicks(); }
    public int flightLoopTicks() { return aerialMount()==null ? 40 : aerialMount().wingLoopTicks(); }
    private float previousFlightWalkAmount;
    private float aerialBank,previousAerialBank,aerialPitch,previousAerialPitch;
    public float getAerialBank(float partial) { return Mth.lerp(partial,previousAerialBank,aerialBank); }
    public float getAerialPitch(float partial) { return Mth.lerp(partial,previousAerialPitch,aerialPitch); }
    private float flightWalkAmount;

    // --- server-side combat state ---------------------------------------------------
    private DigimonAttack activeAttack;
    private KineticSession kinetic;
    private LivingEntity attackTarget;
    private int attackTick;
    private boolean attackMirrored;
    private boolean nextAttackMirrored;
    /** Predicted release point, updated during the bubble windup and held through recovery. */
    private Vec3 bubbleAimPoint;
    private Vec3 authoredAimPoint;
    private boolean hornConnected;
    private boolean chargeBlocked;
    private float previousAttackAimPitch;
    private float previousAttackYaw;
    /** Attack id -> {@link #tickCount} at which it may be used again. */
    private final Map<Identifier, Integer> cooldownUntil = new HashMap<>();
    /** Server: refill clocks of the spent uses of stacked attacks ({@link com.digicube.digimon.AttackCharges}). */
    private final Map<Identifier, List<Integer>> chargeRefills = new HashMap<>();
    private final Map<Identifier, FuelReserve> attackFuel = new HashMap<>();
    /** Server: the pounce under way ({@link PounceSession}), and the breath's puffs while a breath attack burns. */
    private PounceSession pounce;
    private FrostBreath breath;
    /** Server: when each victim of the breath last took a damage pulse (entity id to tick); made on first use. */
    private Map<Integer, Integer> breathPulses;
    private Map<Integer, Integer> breathPulses() { return breathPulses != null ? breathPulses : (breathPulses = new HashMap<>()); }
    private ConstrictionSession constriction;
    private int constrictionRetryTick;
    private ConstrictionPlanner constrictionPlanner;
    public com.digicube.digimon.ConstrictionMotion constrictionMotion() {
        return com.digicube.digimon.DigimonSpeciesBootstrap.CONSTRICTION_MOTION;
    }
    public com.digicube.digimon.ConstrictionMotion.Fit getConstrictionFit() {
        return new com.digicube.digimon.ConstrictionMotion.Fit(entityData.get(DATA_WRAP_RADIUS),entityData.get(DATA_WRAP_PITCH));
    }
    DigimonAttack activeAttackDefinition() { return activeAttack; }
    private void syncConstrictionAnchor() {
        Vec3 start = constriction.start();
        BlockPos origin = BlockPos.containing(start);
        entityData.set(DATA_WRAP_ORIGIN, origin);
        entityData.set(DATA_WRAP_FRACTION, new org.joml.Vector3f((float) (start.x - origin.getX()),
                (float) (start.y - origin.getY()), (float) (start.z - origin.getZ())));
    }
    /** Anchor the rendered coil to the shared cast path despite ordinary entity packet interpolation. */
    public Vec3 getConstrictionRenderOffset(float partial) {
        var attack=getAnimatingAttack();
        if(attack==null || attack.kind()!=DigimonAttack.Kind.CONSTRICTION || !attackAnimationState.isStarted())return Vec3.ZERO;
        BlockPos origin=entityData.get(DATA_WRAP_ORIGIN);var f=entityData.get(DATA_WRAP_FRACTION);
        Vec3 start=new Vec3(origin.getX()+f.x(),origin.getY()+f.y(),origin.getZ()+f.z());
        float time=attackAnimationState.getTimeInMillis(tickCount+partial)/50F;
        float yaw=entityData.get(DATA_WRAP_YAW);
        return start.add(constrictionMotion().root(getConstrictionFit(),time,entityData.get(DATA_WRAP_DISTANCE),getBody().modelScale())
                .yRot(-yaw*Mth.DEG_TO_RAD)).subtract(getPosition(partial));
    }
    private long partyGeneration;

    private com.digicube.digimon.KineticAttacks.Frame kineticRenderFrame(float partial) {
        var attack = getAnimatingAttack();
        if (attack == null || attack.kind() != DigimonAttack.Kind.RETREAT_KICK || !attackAnimationState.isStarted()) return null;
        var definition = com.digicube.digimon.KineticAttacks.get(attack);
        float tick = attackAnimationState.getTimeInMillis(tickCount + partial) / 50F;
        boolean kick = !attack.id().getPath().equals(attackAnimationName);
        return definition.motion(kick).sample(definition.motionTime(attackAnimationName, tick));
    }

    /** Reproduce the saved turn between network packets, on the same clock as the legs. */
    public float getKineticRenderYaw(float partial) {
        var frame = kineticRenderFrame(partial);
        return frame == null ? Mth.rotLerp(partial, yRotO, getYRot()) : entityData.get(DATA_KINETIC_YAW) + frame.yaw();
    }

    public Vec3 getKineticRenderOffset(float partial) {
        var frame = kineticRenderFrame(partial);
        if (frame == null) return Vec3.ZERO;
        var origin = entityData.get(DATA_KINETIC_ORIGIN); var fraction = entityData.get(DATA_KINETIC_FRACTION);
        Vec3 start = new Vec3(origin.getX() + fraction.x(), origin.getY() + fraction.y(), origin.getZ() + fraction.z());
        return AttackGeometry.world(start, frame.offset(), entityData.get(DATA_KINETIC_YAW)).subtract(getPosition(partial));
    }

    // --- server-side progression state ------------------------------------------------
    /** Progress toward the next level; the level itself is synched entity data. */
    private int xp;
    /** Wild only: health lost to each partner, for the XP split on defeat. Not saved. */
    private final DamageLedger contributions = new DamageLedger();
    private boolean experienceAwarded;

    /** Ordered to stand still: no following, no catch-up teleport. Not saved, so a fresh deployment follows again. */
    private boolean holding;
    /** What the party sync last reported for {@link #hasLiveTarget()}, so a change can trigger a snapshot. */
    private boolean reportedTarget;

    public boolean isHolding() { return holding; }
    public void setHolding(boolean holding) { this.holding = holding; if (holding) { getNavigation().stop(); setRunningToOwner(false); } }
    public boolean hasLiveTarget() { return getTarget() != null && getTarget().isAlive(); }
    /** @return whether the target state changed since the last call */
    public boolean targetStateChanged() { boolean now = hasLiveTarget(); boolean changed = now != reportedTarget; reportedTarget = now; return changed; }
    /** The owner called the attack off: forget the target and whoever provoked it. */
    public void cancelTarget() {
        // A running target goal re-asserts its remembered victim every tick, so stop the goals, not just the field.
        targetSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning).forEach(net.minecraft.world.entity.ai.goal.WrappedGoal::stop);
        setTarget(null);
        setLastHurtByMob(null);
        getNavigation().stop();
    }

    public long getPartyGeneration() { return partyGeneration; }
    public void setPartyGeneration(long generation) { partyGeneration = generation; }

    // --- client-side animation state ------------------------------------------------
    /** Started by {@link #handleEntityEvent}; read by the renderer. Meaningful on the client only. */
    public final AnimationState attackAnimationState = new AnimationState();
    private String attackAnimationName;
    private int attackAnimationEndTick;
    private float previousRunAnimationAmount;
    private float runAnimationAmount;
    private float previousSwimAnimationAmount;
    private float swimAnimationAmount;
    private float previousSwimAnimationPhase;
    private float swimAnimationPhase;
    private float previousSwimMotionAmount;
    private float swimMotionAmount;
    private float previousGroundAnimationPhase;
    private float groundAnimationPhase;
    private float previousSwimBank;
    private float groundAnimationAmount;
    private float groundRunAmount, previousGroundRunAmount;
    public float getGroundRunAmount(float partial) { return Mth.lerp(partial, previousGroundRunAmount, groundRunAmount); }
    /** Client: shares of a directional gait spent forwards, backwards, left and right; they sum to one. */
    private final float[] gaitShares = {1, 0, 0, 0}, previousGaitShares = {1, 0, 0, 0};
    public float[] getGaitShares(float partial) {
        float[] shares = new float[4];
        float sum = 0;
        for (int i = 0; i < 4; i++) sum += shares[i] = Mth.lerp(partial, previousGaitShares[i], gaitShares[i]);
        if (sum < 1.0E-4F) return new float[]{1, 0, 0, 0};
        for (int i = 0; i < 4; i++) shares[i] /= sum;
        return shares;
    }
    private float previousGroundAnimationAmount;
    private float mountWaterAmount;
    private float swimStroke;
    private float previousMountWaterAmount;
    private float swimBank;
    private float landWaterMalus;
    private float landWaterBorderMalus;
    /** Client only: a preview drawn inside a screen, never in the level. Transient, never saved or synced. */
    private boolean guiPreview;

    public DigimonEntity(EntityType<? extends DigimonEntity> type, Level level) {
        super(type, level);
    }

    // --- hit parts: extra hittable volumes for bodies far larger than the collision box ------

    private static final DigimonPart[] NO_PARTS = new DigimonPart[0];
    // Offline fixtures skip field initialisers, so every reader tolerates null.
    private DigimonPart[] parts;
    private boolean partsBuilt;

    /** This body's extra hit volumes; empty until species data arrives, and for compact species. */
    public DigimonPart[] parts() { return parts == null ? NO_PARTS : parts; }

    /** Parts have negative ids derived from the parent's, identical on both sides. */
    @Override
    public void setId(int id) {
        super.setId(id);
        for (DigimonPart part : parts()) part.setId(DigimonPart.idFor(id, part.index));
    }

    /** A serpent's trail: the path its head took, which its body lies along (both sides, each its own); null for any other body. */
    private SerpentTrail trail;

    public SerpentTrail serpentTrail() { return trail; }

    /** Blocks above the feet a fresh trail is laid clear of blocks at: the body lying on the ground. */
    private static final double TRAIL_LIFT = .3;
    /** Blocks behind the head over which the way a serpent's body runs up to its head is read (its neck). */
    private static final double NECK_SPAN = 1.5;

    /**
     * A serpent's head turns no further off the way its body runs up to it than its neck bends ({@code serpent.neck_turn}):
     * to come further round it has to go on, its body curling after it (the move control and the ridden input keep it
     * going through a turn). Where the body moves, after it moved and laid its trail; on land (turned on the spot the head
     * swung round over its own body, and the drawn neck folded to follow it), not swimming, where the body is free round
     * it and, held, a swimmer gliding slowly round to a node below it circled the node instead.
     */
    private void holdNeck() {
        var serpent = serpent();
        if (serpent == null || trail == null || serpent.neckTurn() >= 180 || !isLocalInstanceAuthoritative() || isPassenger()
                || isInWater() || serverOwnsBody() || "constriction".equals(entityData.get(DATA_SUSTAINED_ATTACK))) return;
        float heading = trail.heading(NECK_SPAN);
        if (Float.isNaN(heading)) return;
        float off = Mth.wrapDegrees(getYRot() - heading);
        if (Math.abs(off) <= serpent.neckTurn()) return;
        float yaw = heading + Math.copySign(serpent.neckTurn(), off);
        setYRot(yaw);
        yBodyRot = yaw;
    }

    /**
     * Carry the parts along every tick on both sides. At rest they follow the authored offsets behind
     * the body yaw (a serpent's along the path its head took); during a wrap they trace the coil's own swept volumes
     * around the prey.
     */
    private void placeParts() {
        if (!partsBuilt && getSpecies().isPresent()) {
            partsBuilt = true;
            var authored = getBody().hitParts();
            parts = new DigimonPart[authored.size()];
            for (int i = 0; i < parts.length; i++) {
                var part = authored.get(i);
                parts[i] = new DigimonPart(this, i, part.width(), part.height());
                parts[i].setId(DigimonPart.idFor(getId(), i));
            }
        }
        if (parts().length == 0) return;
        ((PartedLevel) level()).digicube$track(this);
        var serpent = getBody().serpent();
        if (serpent != null) {
            if (trail == null) trail = new SerpentTrail(getBody().length());
            trail.follow(position(), yBodyRot, 1, level(), TRAIL_LIFT);
        }
        if ("constriction".equals(entityData.get(DATA_SUSTAINED_ATTACK))) {
            BlockPos origin = entityData.get(DATA_WRAP_ORIGIN);
            var fraction = entityData.get(DATA_WRAP_FRACTION);
            Vec3 anchor = new Vec3(origin.getX() + fraction.x(), origin.getY() + fraction.y(), origin.getZ() + fraction.z());
            var swept = constrictionMotion().sweptBody(getConstrictionFit(), entityData.get(DATA_WRAP_DISTANCE), getBody().modelScale(),
                    anchor, entityData.get(DATA_WRAP_YAW));
            var sample = swept.sample(Math.clamp(entityData.get(DATA_SUSTAINED_TICK) / 4, 0, swept.samples().size() - 1));
            for (int i = 0; i < parts.length; i++) {
                // The coil has its own box count; spread the parts over it so the whole coil is covered.
                parts[i].place(sample.get(Math.min(sample.size() - 1, i * sample.size() / parts.length)));
            }
            return;
        }
        var authored = getBody().hitParts();
        if (serpent != null) {
            boolean swimming = isSwimmingMovement();
            for (int i = 0; i < parts.length; i++) parts[i].place(HitParts.place(authored.get(i), trail, swimming, serpent.swimHeight()));
            return;
        }
        for (int i = 0; i < parts.length; i++) parts[i].place(HitParts.place(authored.get(i), position(), yBodyRot));
    }

    /**
     * Where a swing at a long prey should go: the first hit volume the authored strike can actually
     * reach from here, in the order the reach check rehearses them, else the closest one.
     */
    private AABB nearestVolume(LivingEntity target) {
        var volumes = HitParts.of(target);
        if (activeAttack != null && activeAttack.motion() != null && (activeAttack.kind() == DigimonAttack.Kind.FIST
                || activeAttack.kind() == DigimonAttack.Kind.HORN_RAM)) {
            for (AABB volume : volumes) {
                if (AttackGeometry.canContact(activeAttack, position(), getBbWidth(), getBbHeight(), volume,
                        this::clearAttackLine, box -> level().noCollision(this, box), this::hasChargeGround)) return volume;
            }
        }
        AABB best = target.getBoundingBox();
        double bestDistance = best.getCenter().distanceToSqr(position());
        for (AABB volume : volumes) {
            double distance = volume.getCenter().distanceToSqr(position());
            if (distance < bestDistance) { best = volume; bestDistance = distance; }
        }
        return best;
    }

    /** Melee reach counts any of the victim's hit volumes, not only the head's collision box. */
    @Override
    public boolean isWithinMeleeAttackRange(LivingEntity target) {
        if (super.isWithinMeleeAttackRange(target)) return true;
        if (!(target instanceof DigimonEntity digimon) || digimon.parts().length == 0) return false;
        AABB reach = getAttackBoundingBox(0);
        for (DigimonPart part : digimon.parts()) if (reach.intersects(part.getBoundingBox())) return true;
        return false;
    }

    /** Default attributes; species movement speed is applied when species data arrives. */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this) {
            @Override public boolean canUse() { return !canSwim() && super.canUse(); }
        });
        this.goalSelector.addGoal(1, new RiderControlGoal());
        this.goalSelector.addGoal(1, new DigimonFlightGoal(this));
        this.goalSelector.addGoal(1, new WildPanicGoal(this, 1.4));
        this.goalSelector.addGoal(2, new DigimonAttackGoal(this, 1.25));
        this.goalSelector.addGoal(2, new com.digicube.entity.ai.ThrowerFetchGoal(this));
        this.goalSelector.addGoal(2, new com.digicube.entity.ai.BlindGuardGoal(this, 1.25));
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this));
        this.goalSelector.addGoal(5, new RandomSwimmingGoal(this, 1.0, 60) {
            @Override public boolean canUse() { return canSwim() && isInWater() && super.canUse(); }
            @Override public boolean canContinueToUse() { return canSwim() && isInWater() && super.canContinueToUse(); }
        });
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0) {
            @Override public boolean canUse() { return (!canSwim() || !isInWater()) && super.canUse(); }
        });
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
        this.targetSelector.addGoal(3, new HurtByTargetGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_EVOLUTION, "");
        builder.define(DATA_SPECIES, DEFAULT_SPECIES.toString());
        builder.define(DATA_ATTACK_AIM_PITCH, 0.0F);
        builder.define(DATA_ATTACK_YAW, 0.0F);
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_RUNNING_TO_OWNER, false);
        builder.define(DATA_SUSTAINED_ATTACK, "");
        builder.define(DATA_SUSTAINED_TICK, 0);
        builder.define(DATA_KINETIC_ORIGIN, BlockPos.ZERO);
        builder.define(DATA_KINETIC_FRACTION, new org.joml.Vector3f());
        builder.define(DATA_KINETIC_YAW, 0F);
        builder.define(DATA_STRIKE_ORIGIN, NO_STRIKE);
        builder.define(DATA_STRIKE_FRACTION, new org.joml.Vector3f());
        builder.define(DATA_WRAP_RADIUS, 34.0F);
        builder.define(DATA_WRAP_PITCH, 36.0F);
        builder.define(DATA_WRAP_ORIGIN,BlockPos.ZERO);
        builder.define(DATA_WRAP_FRACTION,new org.joml.Vector3f());
        builder.define(DATA_WRAP_DISTANCE,0F);
        builder.define(DATA_WRAP_YAW,0F);
        builder.define(DATA_LEVEL, Progression.MIN_LEVEL);
        builder.define(DATA_FLIGHT_PHASE, FlightPhase.GROUNDED.ordinal());
        builder.define(DATA_FLIGHT_START, 0L);
        builder.define(DATA_FLIGHT_LOOP_START, 0L);
        builder.define(DATA_FLIGHT_FUEL, 1.0F);
        builder.define(DATA_RIDER_FUEL, 1.0F);
        builder.define(DATA_GRAB_PREY, -1);
        builder.define(DATA_GRAB_LUNGE, false);
        builder.define(DATA_JET_PULSE, 0);
        builder.define(DATA_SWIM_ROLL, 0);
        builder.define(DATA_WHIP, 0);
        builder.define(DATA_WHIP_YAW, 0F);
        builder.define(DATA_WHIP_PITCH, 0F);
        builder.define(DATA_RIDER_CHARGE, 0);
        builder.define(DATA_RIDER_DRAW, -1F);
        builder.define(DATA_BATTLE_SIDE, 0);
        builder.define(DATA_THROW_CHARGE, 0F);
        builder.define(DATA_BONE_CARRIED, true);
        builder.define(DATA_ARC, "");
    }

    // --- thrown weapons (ThrowerState runs them; these are its hooks) -----------------------------------------------

    /** The species' moves, for the thrower. */
    public List<DigimonAttack> speciesAttacks() { return attacks(); }
    /** Client: the charged throw in hand, 0 to 1. */
    public float throwCharge() { return this.entityData.get(DATA_THROW_CHARGE); }
    /** Client: the returning weapon is on its back. */
    public boolean boneCarried() { return this.entityData.get(DATA_BONE_CARRIED); }
    void syncThrowCharge(float charge) { this.entityData.set(DATA_THROW_CHARGE, charge); }
    void syncCarried(boolean carried) { this.entityData.set(DATA_BONE_CARRIED, carried); }
    boolean isAttackReadyForThrower(DigimonAttack attack) {
        return !evolutionLocked() && tickCount >= evolutionAttackUntil && tickCount >= windedUntil
                && !hasEffect(DCEffects.FROZEN) && !hasEffect(DCEffects.CONSTRICTED) && tickCount >= cooldownUntil.getOrDefault(attack.id(), 0);
    }
    void putThrownCooldown(DigimonAttack attack) { putThrownCooldown(attack, attack.cooldownTicks()); }
    void putThrownCooldown(DigimonAttack attack, int ticks) { cooldownUntil.put(attack.id(), tickCount + ticks); }
    /** A thrown attack begins: the body counts as attacking (its target, the opponents' reads), the thrower runs it. */
    void thrownAttackStarted(DigimonAttack attack, LivingEntity target) {
        activeAttack = attack; attackTarget = target; attackTick = 0; riderAttack = false;
    }
    void thrownAttackEnded() {
        if (com.digicube.digimon.ThrownAttacks.handles(activeAttack)) { activeAttack = null; attackTarget = null; }
    }
    /** The clip the thrower plays, on the synced sustained channel; empty ends it. */
    void showThrowerClip(String clip, int tick) {
        this.entityData.set(DATA_SUSTAINED_TICK, tick);
        this.entityData.set(DATA_SUSTAINED_ATTACK, clip);
    }

    // --- species ---------------------------------------------------------------------

    public Identifier getSpeciesId() {
        return Identifier.parse(this.entityData.get(DATA_SPECIES));
    }

    /** Empty if the saved species is not registered (e.g. a removed datapack species). */
    public Optional<DigimonSpecies> getSpecies() {
        return DigimonSpeciesRegistry.get(getSpeciesId());
    }

    public void setSpecies(Identifier speciesId) {
        this.entityData.set(DATA_SPECIES, speciesId.toString());
    }

    public DigimonBody getBody() {
        return getSpecies().map(DigimonSpecies::body).orElse(DigimonBody.DEFAULT);
    }

    /** @return this species' follow settings, or the original defaults if unavailable */
    public DigimonLocomotion getLocomotion() {
        return getSpecies().map(DigimonSpecies::locomotion).orElse(DigimonLocomotion.DEFAULT);
    }

    /**
     * Read the species' aquatic capability.
     * @return whether this species is adapted to sustained swimming
     */
    public boolean canSwim() { return getLocomotion().canSwim(); }

    public boolean canFly() { return getLocomotion().canFly(); }
    public FlightPhase getFlightPhase() { return FlightPhase.byId(entityData.get(DATA_FLIGHT_PHASE)); }
    public float getFlightPhaseTime(float partialTick) {
        return Math.max(0, level().getGameTime() - entityData.get(DATA_FLIGHT_START) + partialTick);
    }
    public float getFlightLoopTime(float partialTick) {
        return Math.max(0, level().getGameTime() - entityData.get(DATA_FLIGHT_LOOP_START) + partialTick);
    }
    public float getFlightFuel() { return entityData.get(DATA_FLIGHT_FUEL); }

    /** Client: the approach being drawn (its start stamp) and the rendered height above the ground it began at. */
    private long approachStamp = Long.MIN_VALUE;
    private double approachFrom;

    /**
     * Client: how far an approach has come down, 0 where it began to 1 on the ground, from the rendered height. The
     * landing clip plays on this, so it starts from the flight pose at whatever height the approach began and never
     * jumps (a fixed three-block scale began it half played, about 1.5 blocks up).
     */
    public float landingProgress(float partialTick) {
        var phase = getFlightPhase();
        if (phase != FlightPhase.APPROACH || aerialMount() == null) return phase.airborne() ? 0 : 1;
        double height = Math.max(0, aerialRiding().groundDistance(6) + Mth.lerp(partialTick, yo, getY()) - getY());
        long stamp = entityData.get(DATA_FLIGHT_START);
        if (stamp != approachStamp) { approachStamp = stamp; approachFrom = Math.max(height, .5); }
        return (float) Mth.clamp(1 - height / approachFrom, 0, 1);
    }
    public float getFlightWalkAmount(float partialTick) {
        return Mth.lerp(partialTick, previousFlightWalkAmount, flightWalkAmount);
    }
    public boolean isFlyingMovement() {
        FlightPhase phase = getFlightPhase();
        return canFly() && isAlive() && phase.airborne()
                && (phase != FlightPhase.TAKEOFF || getFlightPhaseTime(0) >= flightLiftTick());
    }
    public boolean needsFlightLanding() { return needsFlightLanding; }
    public void clearFlightLandingRequest() { needsFlightLanding = false; }
    public void setFlightPhase(FlightPhase phase) {
        if (level().isClientSide() || phase == getFlightPhase()) return;
        entityData.set(DATA_FLIGHT_START, level().getGameTime());
        if (phase == FlightPhase.FLYING || phase == FlightPhase.APPROACH && getFlightPhase() == FlightPhase.GROUNDED) {
            entityData.set(DATA_FLIGHT_LOOP_START, level().getGameTime());
        }
        entityData.set(DATA_FLIGHT_PHASE, phase.ordinal());
    }
    public FlightReserve flightReserve() {
        DigimonFlight definition = getLocomotion().flight();
        if (definition == null) return flightReserve;
        if (flightReserve == null || !definition.equals(flightDefinition)) {
            FlightReserve old = flightReserve;
            flightReserve = new FlightReserve(definition);
            if (old != null) flightReserve.restore(old.fraction() * definition.capacityTicks(), old.restRemaining());
            flightDefinition = definition;
        }
        return flightReserve;
    }
    /** Only the flight goal swaps these, and restores the exact previous ground/swim controllers. */
    public void useFlightNavigation(PathNavigation navigation, MoveControl<?> control) {
        this.navigation = navigation;
        this.moveControl = control;
    }

    @Override public void travel(Vec3 input) {
        if(evolutionLocked()){setDeltaMovement(Vec3.ZERO);return;}
        if (isFlyingMovement() && !isInWater() && !isInLava()) {
            if (aerialMount()!=null && getControllingPassenger() instanceof Player rider) {
                setDeltaMovement(aerialRiding().velocity(rider));
            }
            move(MoverType.SELF, getDeltaMovement());
            resetFallDistance();
        } else {
            boolean grounded = onGround();
            Vec3 before = position();
            float push = sureFooting(input);
            if (push != 1) {
                // Mob.setSpeed sets the forward input too: both are put back after the push
                float speed = getSpeed(), forward = zza;
                setSpeed(speed * push);
                super.travel(input);
                setSpeed(speed);
                zza = forward;
            } else super.travel(input);
            stepDown(grounded);
            glide();
            climb(input);
            capChargingPace(before);
        }
    }

    /** Blocks a tick a serpent climbs a ledge, and lowers itself off one. */
    public static final double CLIMB_PACE = .2;
    /** Blocks ahead of its box a serpent feels for the top of the ledge it presses into. */
    private static final double CLIMB_FEEL = .35;

    /** Blocks of wall this body climbs (DigimonBody.climbHeight), 0 for one that does not. */
    public double climbHeight() {
        // Offline fixtures skip the constructor, synced data included.
        return this.entityData == null ? 0 : getBody().climbHeight();
    }
    /**
     * Where a serpent last set off from (a climb is measured from there): the feet's height on the ground, or the water's
     * surface; and whether that was the ground (it lowers itself off a ledge, never out of a breach).
     */
    private double climbFloor = Double.NaN;
    private boolean climbsFromGround;

    /**
     * A serpent's climb ({@code serpent.climb_share}, {@link DigimonBody#climbHeight}), where its body moves, after it
     * moved: pressed forward into a wall whose top is no higher than its climb above the ground it set off from (or the
     * water's surface), with room there for its box, it rises up the face at CLIMB_PACE until its feet clear the top, and
     * its push carries it over; its body follows up the face after its head. A higher wall stops it, and it never clings
     * to one: let go of the push, it drops back. Off a ledge no deeper than its climb it lowers itself at the same pace
     * instead of dropping.
     */
    private void climb(Vec3 input) {
        double height = climbHeight();
        if (height <= 0 || isPassenger() || isInLava()) return;
        if (onGround()) { climbFloor = getY(); climbsFromGround = true; }
        else if (isInWater()) { climbFloor = getY() + getFluidHeight(FluidTags.WATER); climbsFromGround = false; }
        if (Double.isNaN(climbFloor)) return;
        Vec3 v = getDeltaMovement();
        double reach = height - (getY() - climbFloor);
        if (input.z > 1.0E-3 && horizontalCollision && reach > 0 && ledge(reach)) {
            setDeltaMovement(v.x, Math.max(v.y, CLIMB_PACE), v.z);
            resetFallDistance();
        } else if (climbsFromGround && !onGround() && !isInWater() && v.y < -CLIMB_PACE && climbFloor - getY() < height) {
            setDeltaMovement(v.x, -CLIMB_PACE, v.z);
            resetFallDistance();
        }
    }

    /**
     * Whether the wall a serpent presses into tops out within {@code reach} blocks up: its box, felt CLIMB_FEEL ahead along
     * its heading, is clear that far up with room above it all the way (its own column clear as it rises).
     */
    private boolean ledge(double reach) {
        AABB box = getBoundingBox();
        AABB ahead = box.move(Vec3.directionFromRotation(0, getYRot()).scale(CLIMB_FEEL));
        if (level().noCollision(this, ahead)) return false;
        for (double rise = .25; rise <= reach + 1.0E-6; rise += .25) {
            if (!level().noCollision(this, box.move(0, rise, 0))) return false;
            if (level().noCollision(this, ahead.move(0, rise, 0))) return true;
        }
        return false;
    }

    /** Ordinary ground's grip (vanilla's block friction under all but ice and slime), ice's, and how far a tick a sure foot turns its run. */
    private static final float FIRM_GROUND = .6F, ICE = .98F, GRIP_TURN = 30, GRIP_REACH = 100;
    /** The share of its speed firm ground takes from a body a tick (vanilla: friction 0.6 times the air's 0.91). */
    public static final double FIRM_LOSS = 1 - FIRM_GROUND * .91;
    private static final Identifier SURE_FOOTING = Constants.id("sure_footing");

    /**
     * How much of firm ground's grip a sure-footed body's paws keep on the block under it ({@code locomotion.ice_grip}):
     * 1 on firm ground and off it, the species' ice grip on ice (a little less on blue ice), slime in between; 1 for a
     * body that is not sure-footed, which vanilla's own friction leaves to slide.
     */
    public double footing() {
        double grip = getLocomotion().iceGrip();
        if (grip <= 0 || !onGround()) return 1;
        float block = level().getBlockState(getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction();
        if (block <= FIRM_GROUND) return 1;
        return Math.max(.05, 1 - (block - FIRM_GROUND) / (ICE - FIRM_GROUND) * (1 - grip));
    }

    /**
     * A sure-footed body ({@code locomotion.ice_grip}) keeps part of its grip on ice and slime ({@link #footing}): vanilla's
     * friction modifier, set per tick for the block under it, makes the block take {@link #FIRM_LOSS} times the grip of
     * the body's speed a tick, and the push is the grip's share of firm ground's, so it gathers pace slowly there, skids
     * a few blocks when it stops, and runs no faster than on stone (vanilla's ice let it run on for eight blocks, and at
     * full grip it stopped dead). On firm ground its momentum also turns with where its legs drive it (up to
     * {@link #GRIP_TURN} degrees a tick), where vanilla lets the old line carry on and only friction bends it: a rider
     * swinging the view at a gallop turned the body well ahead of its travel, and the body slid through the bend
     * sideways. On ice friction alone bends it, a drift the body gallops through along its own length ({@link IceSlip}
     * reads its legs from that friction). A drive further round than {@link #GRIP_REACH} (turning back) is left to
     * friction, and a body with no drive (coasting, a strike, a knock) keeps its line.
     * @return the share of its speed the body pushes with this tick (1 on firm ground)
     */
    private float sureFooting(Vec3 input) {
        if (!getLocomotion().sureFooted()) return 1;
        double grip = footing();
        // the friction that takes FIRM_LOSS x grip of the speed a tick (firm ground's at a full grip)
        double wanted = grip >= 1 ? FIRM_GROUND : (1 - FIRM_LOSS * grip) / .91;
        var friction = getAttribute(Attributes.FRICTION_MODIFIER);
        if (friction != null) {
            float block = onGround() ? level().getBlockState(getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction() : FIRM_GROUND;
            // vanilla: 1 - (1 - friction) x modifier
            double modifier = block > FIRM_GROUND ? (1 - wanted) / (1 - block) : 1;
            var current = friction.getModifier(SURE_FOOTING);
            if (modifier == 1) { if (current != null) friction.removeModifier(SURE_FOOTING); }
            else if (current == null || current.amount() != modifier - 1)
                friction.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(SURE_FOOTING, modifier - 1,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        }
        // vanilla pushes a body on a slippery block with its speed x 0.216 / friction^3; this makes that grip x speed, so
        // the top pace (the push over the share lost a tick) is firm ground's
        float push = grip >= 1 ? 1 : (float) (grip * wanted * wanted * wanted / .21600002);
        if (grip < 1 || !onGround() || isInWater() || input.x * input.x + input.z * input.z < 1.0E-7) return push;
        // An attack that moves the body itself (a lunge, a pounce) owns its line; only a breath is run through.
        if (!level().isClientSide() && activeAttack != null && !BreathAttacks.handles(activeAttack)) return push;
        Vec3 v = getDeltaMovement();
        double speed = v.horizontalDistance();
        if (speed < 1.0E-4) return push;
        // The drive in the world, as vanilla turns the input by the body's heading (Entity.getInputVector).
        float sin = Mth.sin(getYRot() * Mth.DEG_TO_RAD), cos = Mth.cos(getYRot() * Mth.DEG_TO_RAD);
        double driveX = input.x * cos - input.z * sin, driveZ = input.z * cos + input.x * sin;
        double off = Mth.wrapDegrees(Math.toDegrees(Math.atan2(driveZ, driveX) - Math.atan2(v.z, v.x)));
        if (Math.abs(off) > GRIP_REACH) return push;
        double turn = Math.toRadians(Math.clamp(off, -GRIP_TURN, GRIP_TURN)), c = Math.cos(turn), s = Math.sin(turn);
        setDeltaMovement(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
        return push;
    }

    /**
     * A thrower forming or holding its charged throw walks, never runs, and the heavier the charge the slower: its
     * ground travel this tick is held to the gait's walk (three quarters of it at a full charge), whoever steers.
     */
    private void capChargingPace(Vec3 before) {
        // Under a rider the rider's client moves the body; its ridden speed holds the walk (chargingPaceCap).
        if (level().isClientSide() || thrower == null || !thrower.charging() || getLocomotion().groundGait() == null
                || getControllingPassenger() instanceof Player) return;
        double pace = getLocomotion().groundGait().fullSpeed(getBody().modelScale()) * (1 - .25 * thrower.charge());
        Vec3 moved = position().subtract(before);
        double travelled = moved.horizontalDistance();
        if (travelled <= pace * 1.02) return;
        double keep = pace / travelled;
        setPos(before.x + moved.x * keep, getY(), before.z + moved.z * keep);
        setDeltaMovement(getDeltaMovement().multiply(keep, 1, keep));
    }

    /** A hovering body sinks off a ledge on its fins instead of dropping: its fall is held to the species' glide speed. */
    private void glide() {
        double limit = getLocomotion().hoverFallSpeed();
        if (limit <= 0 || onGround() || isInWater() || isInLava() || isNoGravity() || isPassenger()) return;
        Vec3 velocity = getDeltaMovement();
        if (velocity.y < -limit) setDeltaMovement(velocity.x, -limit, velocity.z);
        resetFallDistance();
    }

    /**
     * Client, each tick: where the legs take the body and how far it skids ahead of them ({@link IceSlip}), smoothed
     * over a couple of ticks. On firm ground the legs are its move; on ice the read waits for three ticks on the ground,
     * so the friction and both moves it reads from are all the ground's.
     * @return whether the legs' drive is read from the ice this tick
     */
    private boolean tickLegs(double dx, double dz) {
        double grip = footing();
        boolean slipping = grip < 1 && groundTicks >= 3;
        if (slipping) {
            double[] legs = IceSlip.legs(dx, dz, lastMoveX, lastMoveZ, grip, getLocomotion().iceGrip());
            legsX = Mth.lerp(.5, legsX, legs[0]);
            legsZ = Mth.lerp(.5, legsZ, legs[1]);
        } else { legsX = dx; legsZ = dz; }
        lastMoveX = dx; lastMoveZ = dz;
        previousSkid = skid;
        skid = Mth.approach(skid, onGround() ? IceSlip.skid(dx, dz, legsX, legsZ) : 0, .25F);
        return slipping;
    }

    /**
     * A walker goes down a hillside of blocks as it goes up one: stepping off a ledge no higher than its step, with
     * nothing lifting it, it is set on the ground below at once, as vanilla sets it on a step above. Left to fall, every
     * block of the way down froze the gait mid-stride for the drop and played a landing.
     */
    private void stepDown(boolean grounded) {
        if (!grounded || onGround() || getDeltaMovement().y > 0 || isInWater() || isInLava() || isNoGravity() || isPassenger()
                || getLocomotion().groundGait() == null || getFlightPhase() != FlightPhase.GROUNDED || riderCharging()) return;
        double reach = maxUpStep() + 1.0E-3;
        if (level().noCollision(this, getBoundingBox().move(0, -reach, 0))) return;
        move(MoverType.SELF, new Vec3(0, -reach, 0));
        setDeltaMovement(getDeltaMovement().multiply(1, 0, 1));
        resetFallDistance();
    }

    /**
     * Keep the walking pose in very shallow water at the shore.
     * @return whether the body is immersed enough to adopt its swimming pose
     */
    public boolean isSwimmingMovement() {
        return canSwim() && isInWater() && getFluidHeight(FluidTags.WATER) > getBbHeight() * 0.35;
    }

    public float getGroundAnimationAmount(float partialTick) {
        return Mth.lerp(partialTick, previousGroundAnimationAmount, groundAnimationAmount);
    }

    /** Physical local attachment, including the gradual buoyancy change on both sides. */
    public Vec3 getMountAnchor(float partialTick) {
        return getBody().mount().map(m -> m.position(Mth.lerp(partialTick, previousMountWaterAmount, mountWaterAmount)))
                .orElse(Vec3.ZERO);
    }

    /**
     * Interpolate the transition into the water pose.
     * @param partialTick render interpolation
     * @return gradual water-pose weight
     */
    public float getSwimAnimationAmount(float partialTick) {
        return Mth.lerp(partialTick, previousSwimAnimationAmount, swimAnimationAmount);
    }

    /**
     * Interpolate this creature's swim clock.
     * @param partialTick render interpolation
     * @return per-entity swim clock in ticks
     */
    public float getSwimAnimationPhase(float partialTick) {
        return Mth.lerp(partialTick, previousSwimAnimationPhase, swimAnimationPhase);
    }

    /**
     * Interpolate the stroke intensity from observed movement.
     * @param partialTick render interpolation
     * @return glide-to-power-stroke blend
     */
    public float getSwimMotionAmount(float partialTick) {
        return Mth.lerp(partialTick, previousSwimMotionAmount, swimMotionAmount);
    }

    /**
     * Interpolate the slow land cycle for aquatic species.
     * @param partialTick render interpolation
     * @return slow land-cycle clock
     */
    public float getGroundAnimationPhase(float partialTick) {
        return Mth.lerp(partialTick, previousGroundAnimationPhase, groundAnimationPhase);
    }

    /**
     * Interpolate the visual bank into a swimming turn.
     * @param partialTick render interpolation
     * @return gentle bank in degrees
     */
    public float getSwimBank(float partialTick) {
        return Mth.lerp(partialTick, previousSwimBank, swimBank);
    }

    /** Client: the deep bank into a hard swimming turn, degrees, for a model that leans that far (swim_bank). */
    public float getTurnBank(float partialTick) { return Mth.lerp(partialTick, previousTurnBank, turnBank); }
    /** Client: how far into its dash a swimmer is (past its cruise, the rider's surge), 0 to 1. */
    public float getSwimDash(float partialTick) { return Mth.lerp(partialTick, previousSwimDash, swimDash); }
    /** Client: how far into a leap from the water (a breach) the body is, 0 to 1. */
    public float getSwimLeap(float partialTick) { return Mth.lerp(partialTick, previousSwimLeap, swimLeap); }
    /** Client: how far a sea mount is afloat at its float line (it swims its surface stroke, head out), 0 to 1. */
    public float getSwimSurface(float partialTick) { return Mth.lerp(partialTick, previousSwimSurface, swimSurface); }
    private float swimSurface, previousSwimSurface;
    /** Client: ticks since the body last came into the water (a splash), and how fast it was falling then. */
    public int ticksSinceSplash() { return tickCount - splashTick; }
    public float splashSpeed() { return splashSpeed; }
    private float turnBank, previousTurnBank, swimDash, previousSwimDash, swimLeap, previousSwimLeap, splashSpeed;
    private int splashTick = -1000;
    private boolean wasInWaterClient;
    /** Degrees a swimmer banks at most into a turn (turnBank), and the share of its cruise past which it is dashing. */
    private static final float TURN_BANK = 42;
    private static final double DASH_FROM = 1.12;
    /** Blocks in a tick past which a ground body's move is a teleport, not a stride: its gait's phase skips it. */
    private static final double GAIT_SNAP = 3;
    /** Share of the walk's full pace from which a turning body walks its turn instead of pivoting on the spot. */
    private static final double PIVOT_WALK = .5;
    /** Share of its full amplitude the ground gait gains or loses a tick. */
    public static final float AMPLITUDE_EASE = .125F;
    /** Client: a gait that changes all at once is in its run (DigimonGait.runFrom). */
    private boolean gaitRunning;
    /** Client: the share of the gait a turn on the spot takes, positive turning right (DigimonGait.pivotReach). */
    private float pivotTurn, previousPivotTurn;
    public float getPivotTurn(float partial) { return Mth.lerp(partial, previousPivotTurn, pivotTurn); }
    /** Client: ticks in a row it has ended on the ground, and last tick's move over it. */
    private int groundTicks;
    private double lastMoveX, lastMoveZ;
    /** Client: where its legs take it, blocks a tick along the world's axes (its move, but for a skid or a slip on ice). */
    private double legsX, legsZ;
    /** Client: how far into its skid the body is, 0 to 1 (braced on its paws as it slides ahead of them on ice). */
    private float skid, previousSkid;
    public float getSkid(float partial) { return Mth.lerp(partial, previousSkid, skid); }
    /** Client: this tick's move went from the ground to the ground (a step up or down, never a leap or a fall). */
    public boolean groundedMove() { return groundTicks >= 2; }

    /** Degrees the body turned last tick (SteadyBodyControl, a rider's easing), and whether its move control turned it this tick. */
    private float bodyTurn;
    private boolean steered;

    @Override
    protected net.minecraft.world.entity.ai.control.BodyRotationControl createBodyControl() {
        return new com.digicube.entity.ai.SteadyBodyControl(this);
    }

    /** A body whose gait steps round on the spot (a pivot): it turns no faster than its pivot plants its paws. */
    public boolean stepsRound() {
        // Offline fixtures skip the constructor, synced data included.
        var gait = this.entityData == null ? null : getLocomotion().groundGait();
        return gait != null && gait.pivotTurnRate(getBody().modelScale()) > 0;
    }

    /** A serpent's body (it lies along the path its head took and turns only as it goes), or null. */
    public DigimonBody.Serpent serpent() {
        // Offline fixtures skip the constructor, synced data included.
        return this.entityData == null ? null : getBody().serpent();
    }

    /**
     * Whether the server turns this body at its own steady pace and every client draws the facing it is sent
     * (SteadyBodyControl): one that steps round on its paws, or a serpent.
     */
    public boolean turnsSteadily() { return stepsRound() || serpent() != null; }

    /**
     * Degrees a tick the body turns at most, gathering into the turn and braking out of it (SteadyBodyControl), unless a
     * rider steers it: its pivot's rate for a body that steps round; a serpent's circle at its pace (DigimonBody.Serpent);
     * zero, vanilla's turning, for any other.
     */
    public float steadyTurnRate() {
        // (offline fixtures, with no synced data, turn as vanilla does)
        var serpent = serpent();
        if (serpent == null && !stepsRound() || rider() != null) return 0;
        if (serpent != null) return serpent.turnRate(getDeltaMovement().horizontalDistance(), isInWater());
        return getLocomotion().groundGait().pivotTurnRate(getBody().modelScale());
    }

    public float bodyTurn() { return bodyTurn; }
    /** Degrees a tick this body turns at most swimming unridden (DigimonMoveControl): a serpent's circle at its pace. */
    public float swimTurnRate() {
        var serpent = serpent();
        return serpent != null ? serpent.turnRate(getDeltaMovement().length(), true) : com.digicube.entity.ai.DigimonMoveControl.SWIM_TURN;
    }
    /** End of the tick's body turning: remembers how far it went, for the next tick's easing. */
    public void bodyTurned() { bodyTurn = Mth.wrapDegrees(yBodyRot - yBodyRotO); }
    /** The move control turned the body onto its path this tick (DigimonMoveControl): the body keeps that facing. */
    public void steered() { steered = true; }
    public boolean takeSteered() { boolean was = steered; steered = false; return was; }

    @Override
    public boolean canBreatheUnderwater() { return canSwim() || super.canBreatheUnderwater(); }

    @Override
    public boolean isPushedByFluid() { return !canSwim() && super.isPushedByFluid(); }

    @Override
    protected void travelInWater(Vec3 input, double gravity, boolean falling, double previousY) {
        if (!canSwim()) {
            super.travelInWater(input, gravity, falling, previousY);
            return;
        }
        // A jet swimmer thrusts in pulses (jetStroke) on the side that moves it; it averages the same speed.
        float thrust = jet() != null && isLocalInstanceAuthoritative() ? jetStroke(input) : 1;
        moveRelative(getSpeed() * thrust, input);
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(DigimonMoveControl.WATER_DRAG));
    }

    /** Server. Re-reads the species sheet after it was tuned at runtime: speed and move control. */
    public void refreshSpeciesData() {
        configureSpeciesMovement();
    }

    private void configureSpeciesMovement() {
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(getSpecies().map(DigimonSpecies::baseSpeed).orElse(.3F));
        // A body that steps round on its paws needs the move control that turns it onto its path at its own pace.
        boolean authoredControls = canSwim() || attacks().stream().anyMatch(com.digicube.digimon.KineticAttacks::handles)
                || attacks().stream().anyMatch(com.digicube.digimon.ThrownAttacks::handles) || stepsRound();
        if (authoredControls && !(this.moveControl instanceof DigimonMoveControl)) {
            this.moveControl = new DigimonMoveControl(this);
            this.lookControl = new DigimonLookControl(this);
            this.jumpControl = new com.digicube.entity.ai.DigimonJumpControl(this);
        } else if (!authoredControls && this.moveControl instanceof DigimonMoveControl) {
            this.moveControl = new MoveControl<>(this);
            this.lookControl = new LookControl(this);
            this.jumpControl = new net.minecraft.world.entity.ai.control.JumpControl(this);
        }
        boolean amphibious = getNavigation() instanceof AmphibiousPathNavigation;
        if (canSwim() && !amphibious) {
            landWaterMalus = getPathfindingMalus(PathType.WATER);
            landWaterBorderMalus = getPathfindingMalus(PathType.WATER_BORDER);
            getNavigation().stop();
            this.navigation = new DigimonAmphibiousNavigation(this, level());
            setPathfindingMalus(PathType.WATER, 0);
            setPathfindingMalus(PathType.WATER_BORDER, 0);
        } else if (!canSwim() && amphibious) {
            getNavigation().stop();
            this.navigation = createNavigation(level());
            setPathfindingMalus(PathType.WATER, landWaterMalus);
            setPathfindingMalus(PathType.WATER_BORDER, landWaterBorderMalus);
            setXRot(0);
        }
    }

    /** Land navigation that also counts the steering target as node arrival, for bodies wider than a block. */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new DigimonGroundNavigation(this, level);
    }

    // --- progression: level, XP and the stats they scale ------------------------------

    public int getLevel() {
        return this.entityData.get(DATA_LEVEL);
    }

    /** Progress toward the next level. Meaningful on the server only. */
    public int getXp() {
        return xp;
    }

    /**
     * Server. Sets the level and rescales max health and attack from the species sheet.
     * Current health is only clamped, so callers decide whether to heal.
     */
    public void setLevel(int level) {
        this.entityData.set(DATA_LEVEL, Progression.clampLevel(level));
        applyLevelAttributes();
    }

    /** Server. Sets the level, discards XP progress and restores full health, as a command or spawn does. */
    public void resetToLevel(int level) {
        setLevel(level);
        xp = 0;
        setHealth(getMaxHealth());
        PartyManager.progressChanged(this);
    }

    /** Server. Configures a freshly created Digimon: species, level, no XP and full health. */
    public void initializeAs(DigimonSpecies species, int level) {
        setSpecies(species.id());
        resetToLevel(level);
    }

    /**
     * Server. Places a wild Digimon of the species at {@code position}, as a command or the
     * developer panel does. It is persistent, so it stays put instead of despawning like a
     * natural spawn.
     * @return the entity, or null when the entity type could not be created
     */
    public static DigimonEntity spawnWild(ServerLevel level, DigimonSpecies species, int digimonLevel, Vec3 position) {
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        if (digimon == null) return null;
        digimon.initializeAs(species, digimonLevel);
        digimon.setPos(position.x, position.y, position.z);
        digimon.setPersistenceRequired();
        level.addFreshEntity(digimon);
        return digimon;
    }

    /**
     * Server. Grants XP through the normal path: overflow carries over, level-ups rescale
     * the stats, heal the gained health and announce themselves to the tamer.
     */
    public void addExperience(int amount) {
        if (!(level() instanceof ServerLevel serverLevel) || amount <= 0) return;
        Progression.Gain gain = Progression.gain(getLevel(), xp, amount);
        xp = gain.xp();
        if (gain.levelsGained() > 0) {
            float previousMax = getMaxHealth();
            setLevel(gain.level());
            heal(Math.max(0.0F, getMaxHealth() - previousMax));
            celebrateLevelUp(serverLevel);
        }
        PartyManager.progressChanged(this);
    }

    /** Server. Species base stats scaled by level become the attribute base values. */
    private void applyLevelAttributes() {
        if (level().isClientSide()) return;
        DigimonSpecies species = getSpecies().orElse(null);
        if (species == null) return;
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(Progression.maxHealth(species.baseHealth(), getLevel()));
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(Progression.attack(species.baseAttack(), getLevel()));
        getAttribute(Attributes.ARMOR).setBaseValue(Progression.armor(species.baseDefence()));
    }

    private void celebrateLevelUp(ServerLevel level) {
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.8F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, true, true, getX(), getY(0.5), getZ(), 12,
                getBbWidth() * 0.5, getBbHeight() * 0.3, getBbWidth() * 0.5, 0.05);
        if (getOwner() instanceof ServerPlayer tamer) {
            tamer.sendSystemMessage(Component.translatable("digimon.digicube.level_up", getDisplayName(), getLevel()));
        }
    }

    /** @return the server's current sprint-following state */
    public boolean isRunningToOwner() {
        return this.entityData.get(DATA_RUNNING_TO_OWNER);
    }

    /**
     * Set by the server follow goal; independent of vanilla's sprint attribute modifier.
     * @param running whether the active follow goal is running to a sprinting owner
     */
    public void setRunningToOwner(boolean running) {
        this.entityData.set(DATA_RUNNING_TO_OWNER, running);
    }

    /**
     * Each entity owns its blend, so rendering another Digimon cannot affect this one.
     * @param partialTick fraction between client ticks
     * @return the walking (0) to running (1) blend
     */
    public float getRunAnimationAmount(float partialTick) {
        return Mth.lerp(partialTick, previousRunAnimationAmount, runAnimationAmount);
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        // LivingEntity asks for dimensions during construction, before entity data exists.
        if (this.entityData == null) return super.getDefaultDimensions(pose);
        var body = getBody().dimensions();
        if (canFly() && getFlightPhase() != FlightPhase.GROUNDED) {
            var flight = getLocomotion().flight();
            return EntityDimensions.scalable(Math.max(body.width(), flight.clearanceWidth()),
                    Math.max(body.height(), flight.clearanceHeight())).withEyeHeight(body.eyeHeight());
        }
        return body;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_FLIGHT_PHASE.equals(accessor)) refreshDimensions();
        if (level().isClientSide() && (DATA_SUSTAINED_ATTACK.equals(accessor) || DATA_SUSTAINED_TICK.equals(accessor))) {
            syncSustainedAnimation();
        }
        if (level().isClientSide() && DATA_RIDER_CHARGE.equals(accessor)) seenCharge();
        if (level().isClientSide() && DATA_ARC.equals(accessor)) seenArc();
        if (DATA_SPECIES.equals(accessor)) {
            refreshDimensions();
            if (!level().isClientSide()) {
                configureSpeciesMovement();
                applyLevelAttributes();
            }
            if (!level().isClientSide() && getBody().mount().isEmpty()) {
                ejectPassengers();
            }
        }
    }

    // --- riding: species data supplies the seat; vanilla handles movement packets -----

    /** How far away its partner can be when the owner asks for a ride, in blocks from the player to the body. */
    public static final double RIDE_REACH = 6.0;

    /** Whether {@code player} could ask this Digimon for a ride now; the command wheel offers Ride on the same rule. */
    public boolean canGiveRide(Player player) {
        return isAlive() && getBody().mount().isPresent() && isOwnedBy(player) && !isVehicle() && !isPassenger() && !player.isPassenger()
                && !evolutionLocked() && getBoundingBox().distanceToSqr(player.getEyePosition()) <= RIDE_REACH * RIDE_REACH;
    }

    /**
     * Server only. Riding is an order from the command wheel (the use button belongs to the mount's attacks, so a
     * click that mounted also cast); this is the one way onto a partner. Battle Testing fighters take a right click.
     */
    public boolean giveRide(Player player) {
        if (level().isClientSide() || !canGiveRide(player) || !player.startRiding(this)) return false;
        takeReins();
        return true;
    }

    private void takeReins() {
        cancelAttack();
        getNavigation().stop();
        setTarget(null);
    }

    /** The Battle Testing side this fighter stands on, 0 outside a developer fight. */
    public int battleSide() {
        return this.entityData.get(DATA_BATTLE_SIDE);
    }

    /** Server, development only: stages this Digimon as a Battle Testing fighter on {@code side} (1 or 2). */
    public void joinBattleSide(int side) {
        if (com.digicube.platform.Services.PLATFORM.isDevelopmentEnvironment()) this.entityData.set(DATA_BATTLE_SIDE, side);
    }

    /** Battle Testing only: a right click takes the reins of a staged fighter that can carry a rider, owned or not. */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (battleSide() == 0 || getBody().mount().isEmpty() || !isAlive() || isVehicle() || player.isPassenger()
                || player.isSecondaryUseActive() || evolutionLocked()) return super.mobInteract(player, hand);
        if (!level().isClientSide()) {
            if (!player.startRiding(this)) return InteractionResult.FAIL;
            takeReins();
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getBody().mount().isPresent() && !isVehicle()
                && passenger instanceof Player player && (isOwnedBy(player) || battleSide() != 0)
                && super.canAddPassenger(passenger);
    }

    /**
     * The rider steers, except through a hold or a jet charge: the lunge at the prey and the wrap's body path around
     * it, and the charge with the buck it ends in, are the server's, tick by tick, so until they end the rider is
     * carried like any passenger and the AI step runs the timeline as it does unridden.
     */
    @Override
    public LivingEntity getControllingPassenger() {
        return serverOwnsBody() ? null : rider();
    }

    /** The tamer in the saddle, also while a wrap has taken the reins. */
    public Player rider() {
        return getBody().mount().isPresent() && getFirstPassenger() instanceof Player player
                && (isOwnedBy(player) || player == scenarioRider || battleSide() != 0) ? player : null;
    }

    private boolean serverOwnsBody() {
        return this.entityData.get(DATA_GRAB_LUNGE) || this.entityData.get(DATA_RIDER_CHARGE) != 0 || this.entityData.get(DATA_SUSTAINED_ATTACK).equals(com.digicube.digimon.DigimonSpeciesBootstrap.CONSTRICTION.id().getPath());
    }

    /** Development scenarios only: a rider who controls this mount without owning it (a party member cannot be staged headless). */
    private Player scenarioRider;
    public void seatScenarioRider(Player rider) {
        if (com.digicube.platform.Services.PLATFORM.isDevelopmentEnvironment()) scenarioRider = rider;
    }

    /**
     * Development scenarios only: the server moves this mount under its scenario rider, exactly as the rider's client
     * would (vanilla leaves a ridden body to its rider's client, which a headless run does not have): the rider's keys
     * and view go through {@link #getRiddenInput}, {@link #tickRidden} and {@link #getRiddenSpeed} every tick.
     */
    private boolean scenarioDrives, scenarioDives;
    public void driveScenarioRider(boolean drives, boolean dives) {
        if (!com.digicube.platform.Services.PLATFORM.isDevelopmentEnvironment()) return;
        scenarioDrives = drives;
        scenarioDives = dives;
    }

    @Override
    public boolean isClientAuthoritative() {
        return !(scenarioDrives && scenarioRider != null && getControllingPassenger() == scenarioRider) && super.isClientAuthoritative();
    }

    /** Where the ridden body is moved: on its rider's client, or on the server under a driving scenario rider. */
    private boolean movesRiddenBody() {
        return level().isClientSide() || scenarioDrives && scenarioRider != null && getControllingPassenger() == scenarioRider;
    }

    /**
     * Development scenarios only: the scenario rider presses rider slot {@code slot} as a client would: a pounce is flown
     * here as the rider's client flies it, and the server's cast (which bites along the path) starts with it.
     */
    public boolean scenarioRiderCast(Player rider, int slot) {
        if (!com.digicube.platform.Services.PLATFORM.isDevelopmentEnvironment() || rider != scenarioRider || slot >= riderAttacks().size()) return false;
        var attack = riderAttacks().get(slot);
        if (PounceAttacks.handles(attack) && movesRiddenBody() && !predictRiderPounce(rider, attack)) return false;
        return startRiderAttack(rider, slot);
    }

    @Override
    protected void tickRidden(Player player, Vec3 input) {
        super.tickRidden(player, input);
        if (aerialMount()!=null && getFlightPhase()!=FlightPhase.GROUNDED) {
            aerialRiding().steer(player);
            return;
        }
        // No AI floats a ridden body: it keeps itself afloat as its float goal would, and the jump key lifts it sooner.
        if (!canSwim() && isInWater() && (player.isJumping() || getFluidHeight(FluidTags.WATER) > getFluidJumpThreshold()))
            setDeltaMovement(getDeltaMovement().add(0, player.isJumping() ? .06 : .04, 0));
        boolean swimming = canSwim() && isInWater();
        if (seaMount()) {
            surfaceAndHaul(player);
            barrelRoll(player, swimming);
        }
        float turnRate = getBody().mount().map(mount -> swimming ? mount.waterTurnRate() : mount.turnRate()).orElse(0F);
        boolean pushing = player.zza > 0 || turnsToTravel() && player.xxa != 0 && player.zza >= 0;
        rideMomentum = Mth.approach(rideMomentum, pushing ? 1 : 0, pushing ? .07F : .2F);
        buildGallop(player);
        leap(player);
        carryLeap();
        if (movesRiddenBody() && tickLocalPounce()) return;
        if (riderAttackLocked()) {
            // The strike owns the facing (a soft target may pull it); the rider is free to look around.
            float attackYaw = this.entityData.get(DATA_ATTACK_YAW);
            if (attackYaw != riderStaleYaw || tickCount - attackAnimationStartTick > 3) riderLockYaw = attackYaw;
            setYRot(Mth.approachDegrees(getYRot(), riderLockYaw, 18));
            yBodyRot = yHeadRot = getYRot();
            rideMomentum = 0;
            if (level().isClientSide()) riderLunge();
            return;
        }
        // Where the body wants to point: the view, or for a body that walks along its own length, the way the keys go.
        float heading = player.getYRot() + (swimming && !swimsAlongLength() || riderDrawing() ? 0 : travelTurn(player));
        float off = Mth.wrapDegrees(heading - getYRot());
        if (turnRate > 0 && !swimming) {
            // A galloping body turns wider; one far from the view (after a buck, or a look over the shoulder) comes round faster.
            turnRate *= (1 - GALLOP_TURN * gallopMomentum) * (1 + Mth.clamp((Math.abs(off) - 60) / 60, 0, 1.5F));
            // Standing, a body that steps round on the spot turns no faster than its pivot sets its paws down, and
            // gets its full turn back as it walks off (a spin faster than its legs slid them round).
            var gait = getLocomotion().groundGait();
            float pivot = gait == null || !onGround() ? 0 : gait.pivotTurnRate(getBody().modelScale());
            if (pivot > 0) {
                float pace = (float) Math.min(1, getDeltaMovement().horizontalDistance() / gait.fullSpeed(getBody().modelScale()));
                turnRate = Math.min(turnRate, Mth.lerp(pace, pivot, turnRate));
            }
        }
        // Squaring up to a throw: the body comes round to the crosshair before the bone leaves the fist.
        if (turnRate > 0 && !swimming && throwerClipPlaying(ThrowerClip.WIND_UP)) turnRate = Math.max(turnRate, THROW_TURN);
        if (turnRate > 0 && !swimming && riderDrawing()) {
            // Drawn, the upper body aims and the horse body holds its line: the strafe keys steer it, and it only turns
            // after the view when the aim is further round than the upper body can twist.
            float over = Math.abs(off) - KineticSession.MAX_TWIST;
            riderLockYaw = getYRot() - player.xxa * turnRate * DRAWN_STEER + (over > 0 ? Math.signum(off) * Math.min(over, turnRate) : 0);
        } else if (turnRate > 0 && serpent() != null) {
            // A serpent carves its turn: no tighter than its body's circle at its pace, gathering into it and easing out.
            float circle = serpent().turnRate(swimming ? getDeltaMovement().length() : getDeltaMovement().horizontalDistance(), swimming);
            riderLockYaw = getYRot() + com.digicube.entity.ai.SteadyBodyControl.ease(bodyTurn, off, Math.min(turnRate, circle));
        } else if (turnRate > 0 && !swimming && stepsRound()) {
            // A body that steps round on its paws gathers into the turn and brakes out of it, as it does unridden: standing
            // over as long as its pivot's stride takes to grow, at its walk's full pace and faster in half that.
            float pace = (float) Math.min(1, getDeltaMovement().horizontalDistance() / getLocomotion().groundGait().fullSpeed(getBody().modelScale()));
            float ease = com.digicube.entity.ai.SteadyBodyControl.EASE_TICKS;
            riderLockYaw = getYRot() + com.digicube.entity.ai.SteadyBodyControl.ease(bodyTurn, off, turnRate, Mth.lerp(pace, ease, ease / 2));
        } else riderLockYaw = turnRate > 0 ? Mth.approachDegrees(getYRot(), heading, turnRate) : heading;
        setYRot(riderLockYaw);
        if (swimming && turnRate > 0) {
            // The input follows the view at once (getRiddenInput); only the body eases after it. Held at the surface it
            // swims level, whatever the view.
            float want = holdsSurface(player) ? 0 : Mth.clamp(player.getXRot(), -SWIM_PITCH, SWIM_PITCH);
            setXRot(Mth.approachDegrees(getXRot(), want, 6));
        } else if (canSwim() && turnRate > 0 && !onGround() && getDeltaMovement().lengthSqr() > .01) {
            // Out of the water on a breach: the body follows its arc, nose up on the way out and down on the way back.
            Vec3 arc = getDeltaMovement();
            setXRot(Mth.approachDegrees(getXRot(), (float) -Math.toDegrees(Math.atan2(arc.y, arc.horizontalDistance())), 8));
        } else setXRot(player.getXRot() * (swimming ? 0.7F : 0.35F));
        yBodyRot = getYRot();
        yHeadRot = getYRot();
    }

    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 input) {
        if (riderAttackLocked()) return Vec3.ZERO;
        if (canSwim() && isInWater() && !seaMount()) {
            float forward = player.zza > 0 ? player.zza : player.zza * .25F;
            float pitch = Mth.clamp(player.getXRot() * .7F, -60, 60) * Mth.DEG_TO_RAD;
            return new Vec3(player.xxa * .5F, -Mth.sin(pitch) * forward, Mth.cos(pitch) * forward);
        }
        if (canSwim() && isInWater()) {
            // Forward is where the rider looks, depth included; jump and dive add plain rise and fall on top. A body that
            // swims along its own length (turn_to_travel) goes where the keys point instead of strafing (tickRidden turns
            // it), and keeps swimming through the turn.
            boolean along = swimsAlongLength();
            float forward = along ? swimPush(player) : player.zza > 0 ? player.zza : player.zza * .25F;
            float pitch = Mth.clamp(player.getXRot(), -SWIM_PITCH, SWIM_PITCH) * Mth.DEG_TO_RAD;
            double rise;
            if (holdsSurface(player)) {
                // Afloat at its float line it swims level, head out, whatever the view (surfaceAndHaul keeps it there).
                pitch = 0;
                rise = 0;
            } else {
                rise = -Mth.sin(pitch) * forward + (player.isJumping() ? SWIM_LIFT : 0) - (riderDives(player) ? SWIM_LIFT : 0);
                // The surface holds the body: it cruises with its back out of the water, and only a surge leaps out. Above
                // that float line (coasting up, or back from a breach) it settles down to it again.
                double above = floatLine() - getFluidHeight(FluidTags.WATER);
                if (above > 0 && !player.isSprinting()) rise = Math.min(rise, -Math.min(1, above / (getBbHeight() * SETTLE_BAND)));
            }
            // Pushed into a bank or a ledge it can get over, it hauls itself up it instead of pressing on the wall.
            if (player.zza > 0 && haulsOut()) rise = 1;
            return new Vec3(along ? 0 : player.xxa * .5F, rise, Mth.cos(pitch) * forward);
        }
        if (turnsToTravel() && !riderDrawing()) {
            // It walks along its own length: forwards as it comes round to where the keys point (a horse walks its turn,
            // it does not pivot on the spot), or reined back, slowly, turned the way a reversing cart turns.
            float push = Math.min(1, Mth.sqrt(player.zza * player.zza + player.xxa * player.xxa));
            float align = Mth.cos(Mth.wrapDegrees(player.getYRot() + travelTurn(player) - getYRot()) * Mth.DEG_TO_RAD);
            return new Vec3(0, 0, player.zza < 0 ? -push * BACK_PACE * Math.max(0, align) : push * Math.max(WALK_THE_TURN, align));
        }
        // Drawn, the strafe keys steer the body instead (tickRidden).
        return new Vec3(riderDrawing() ? 0 : player.xxa * 0.5F, 0.0, player.zza > 0.0F ? player.zza : player.zza * 0.25F);
    }

    /**
     * A gait that sounds its own footfalls on its phase ({@code footfalls}) makes none of vanilla's step-per-block ones,
     * and neither does a body that hovers: it has no feet on the ground.
     */
    private boolean ownFootfalls() {
        return getLocomotion().hovers() || getLocomotion().groundGait() != null && getLocomotion().groundGait().footfalls();
    }

    @Override
    protected void playStepSound(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (!ownFootfalls()) super.playStepSound(pos, state);
    }

    @Override
    protected void playCombinationStepSounds(net.minecraft.world.level.block.state.BlockState primary, net.minecraft.world.level.block.state.BlockState secondary) {
        if (!ownFootfalls()) super.playCombinationStepSounds(primary, secondary);
    }

    // --- voice: data/digicube/voices.json, vanilla's where a species has none ------------------------------------------

    private com.digicube.digimon.DigimonVoices voice() { return com.digicube.digimon.DigimonVoices.of(getSpeciesId()); }

    /** The species' own battle cry (voices.json {@code cry}) as one of its moves starts; false when it has none. */
    private boolean battleCry() {
        var voice = voice();
        if (voice == null || voice.cry() == null) return false;
        level().playSound(null, getX(), getY(), getZ(), voice.cry(), getSoundSource(), 1F, voice.pitch() * (.95F + random.nextFloat() * .1F));
        return true;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        var voice = voice();
        return voice == null ? super.getAmbientSound() : voice.ambient();
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        var voice = voice();
        return voice == null || voice.hurt() == null ? super.getHurtSound(source) : voice.hurt();
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        var voice = voice();
        return voice == null || voice.death() == null ? super.getDeathSound() : voice.death();
    }

    @Override
    public float getVoicePitch() {
        var voice = voice();
        return voice == null ? super.getVoicePitch() : super.getVoicePitch() * voice.pitch();
    }

    @Override
    public int getAmbientSoundInterval() {
        var voice = voice();
        return voice == null ? super.getAmbientSoundInterval() : voice.ambientInterval();
    }

    @Override
    protected void playMuffledStepSound(net.minecraft.world.level.block.state.BlockState state) {
        if (!ownFootfalls()) super.playMuffledStepSound(state);
    }

    private boolean turnsToTravel() {
        return getBody().mount().map(DigimonBody.Mount::turnToTravel).orElse(false);
    }

    /**
     * Degrees from the rider's view to where a body that walks along its own length points: into the strafe keys going
     * forwards (A alone turns it a quarter left), and going back its tail swings toward them, up to {@link #BACK_TURN}.
     */
    private float travelTurn(Player player) {
        if (!turnsToTravel() || Math.abs(player.xxa) < 1.0E-3F) return 0;
        if (player.zza >= 0) return (float) -Math.toDegrees(Math.atan2(player.xxa, player.zza));
        return Mth.clamp((float) Math.toDegrees(Math.atan2(player.xxa, -player.zza)), -BACK_TURN, BACK_TURN);
    }

    /** Reining back: its share of the pace, and how far the body turns to back toward a side. */
    private static final float BACK_PACE = .4F, BACK_TURN = 45;
    /** How much of its pace a body keeps while it is still coming round to a new heading. */
    private static final float WALK_THE_TURN = .25F;

    /**
     * Sprinting forward builds into the gallop on land and a sea mount's surge in water (body.mount.sprint_build ticks
     * to the top, a straight ramp), and settles back faster.
     */
    protected void buildGallop(Player player) {
        boolean galloping = player.isSprinting() && player.zza > 0;
        float build = getBody().mount().map(DigimonBody.Mount::sprintBuild).orElse(DigimonBody.Mount.SPRINT_BUILD);
        gallopMomentum = Mth.approach(gallopMomentum, galloping ? 1 : 0, galloping ? 1 / build : .1F);
    }

    @Override
    protected float getRiddenSpeed(Player player) {
        if (canSwim() && isInWater()) return (float) (getLocomotion().swimSpeed() * (1 - DigimonMoveControl.WATER_DRAG))
                * (seaMount() ? 1 + (getBody().mount().map(DigimonBody.Mount::waterSprint).orElse(1F) - 1) * gallopMomentum : 1);
        return getBody().mount().map(mount -> Math.min(chargingPaceCap(mount), mount.turnRate() <= 0 ? ridePace(mount)
                // a heavy mount gathers pace, and breaks into its charge while the rider sprints
                : ridePace(mount) * (.45F + .55F * rideMomentum) * (1 + (mount.sprint() - 1) * gallopMomentum))).orElseGet(() -> super.getRiddenSpeed(player));
    }

    /**
     * A thrower forming or holding its icicle walks under its rider as it does alone ({@link #capChargingPace}): the
     * ridden pace is the gait's run, so the walk is that share of it, three quarters of that at a full charge.
     */
    private float chargingPaceCap(DigimonBody.Mount mount) {
        var gait = getLocomotion().groundGait();
        if (gait == null || !throwerClipPlaying(ThrowerClip.CHARGING)) return Float.MAX_VALUE;
        float walkShare = (float) (gait.fullSpeed(getBody().modelScale()) / Math.max(1.0E-3, gait.runSpeed(getBody().modelScale())));
        return ridePace(mount) * Math.min(1, walkShare) * (1 - .25F * throwCharge());
    }

    /**
     * The rule for mounts: the same pace with a rider as without. A sheet without {@code body.mount.speed} gets the
     * pace its own AI walks at; older sheets still carry a number of their own.
     */
    private float ridePace(DigimonBody.Mount mount) {
        if (!mount.ownPace()) return mount.speed();
        float own = (float) (getAttributeValue(Attributes.MOVEMENT_SPEED) * getLocomotion().runSpeed());
        // Vanilla's move control feeds the speed in twice (as speed and as forward input); only swimmers' control undoes that.
        return canSwim() ? own : own * own * .98F;
    }

    /** How much of its turn rate a mount gives up at a full gallop, and how fast a drawn rider's strafe keys steer it. */
    private static final float GALLOP_TURN = .35F, DRAWN_STEER = .6F;
    /** Degrees a tick a thrower turns to the crosshair while it winds up a rider's throw. */
    private static final float THROW_TURN = 18;
    private float gallopMomentum;
    private int leapCooldown;

    /**
     * Client, where the ridden body moves: the jump key leaps a mount that has a leap in its sheet ({@code body.mount.jump}),
     * a fixed height at a tap (Minecraft's charge-bar horse jump is the one players ask to be rid of). The run keeps
     * its speed through the air.
     */
    private void leap(Player player) {
        if (!movesRiddenBody()) return;
        if (leapCooldown > 0) leapCooldown--;
        float jump = getBody().mount().map(DigimonBody.Mount::jump).orElse(0F);
        if (jump <= 0 || !player.isJumping() || !onGround() || isInWater() || leapCooldown > 0 || riderAttackLocked()) return;
        // Speed is the impulse: at a full gallop the leap goes a little higher and is thrown forward, so it carries
        // several times as far as one from a walk. The sound and the pose follow the body (HoofBeats, tickLeapPose).
        var mount = getBody().mount().orElseThrow();
        Vec3 run = getDeltaMovement().multiply(1, 0, 1);
        float speed = (float) Mth.clamp(run.length() / (ridePace(mount) * mount.sprint()), 0, 1);
        Vec3 ahead = Vec3.directionFromRotation(0, getYRot());
        Vec3 push = ahead.scale(LEAP_PUSH * speed * ridePace(mount) * mount.sprint());
        // The top of the gallop is where the high jump is: most of the extra height comes in its last stretch.
        float lift = LEAP_LIFT * speed + LEAP_TOP * speed * speed * speed * speed * speed * speed;
        setDeltaMovement(run.x + push.x, jump * (1 + lift), run.z + push.z);
        leapCooldown = 10;
        leaping = true;
    }

    /**
     * Client, where the ridden body moves: in a leap from a mount that carries its momentum ({@code body.mount.leap_carry})
     * the air takes that share of the run a tick instead of vanilla's 0.91, so a leap at a gallop flies far.
     */
    private void carryLeap() {
        if (!movesRiddenBody()) return;
        if (onGround() || isInWater()) { leaping = false; return; }
        float carry = getBody().mount().map(DigimonBody.Mount::leapCarry).orElse(0F);
        if (!leaping || carry <= 0 || localPounceTick >= 0) return;
        Vec3 v = getDeltaMovement();
        double k = carry / .91;
        setDeltaMovement(v.x * k, v.y, v.z * k);
    }

    /**
     * Client, the rider's own mount: a pounce is flown from the press, before the server's word comes back, along the
     * crosshair (bent toward the outlined enemy); the server checks its bite on the path the body really takes.
     * @return whether it started (false when no use is ready here)
     */
    public boolean predictRiderPounce(Player rider, DigimonAttack attack) {
        var spec = PounceAttacks.get(attack);
        if (spec == null || rider != rider() || !(rider.isLocalPlayer() || !level().isClientSide() && movesRiddenBody()) || readyUses(attack) <= 0 || localPounceTick >= 0
                || hasEffect(DCEffects.FROZEN) || hasEffect(DCEffects.CONSTRICTED)) return false;
        if (getAnimatingAttack() != null && attackAnimationState.isStarted() && tickCount < attackAnimationEndTick) return false;
        boolean air = !onGround() && !isInWater();
        localPounceSpec = spec;
        localPounceAir = air;
        localPounceLine = PounceLines.rider(this, spec, rider.getEyePosition(), rider.getLookAngle(), softTarget(rider, attack), air);
        localPounceTick = spec.startTick(air);
        localPounceBit = false;
        localPouncePress = tickCount;
        // Its clip starts here too, and its use is spent here as the server will spend it.
        int lead = spec.startTick(air);
        attackAnimationName = attack.animationName(false);
        attackAnimationStartTick = tickCount - lead;
        attackAnimationEndTick = attackAnimationStartTick + attack.durationTicks();
        seenCooldownUntil.put(attack.id(), com.digicube.digimon.AttackCharges.spend(seenChargeRefills, attack, tickCount));
        hitStopTicks = 0;
        swingConnected = attackConnected = false;
        attackAnimationState.start(attackAnimationStartTick);
        return true;
    }

    /**
     * Client, in tickRidden: one tick of the local pounce. The gather holds the body; the burst sets its speed along the
     * line each tick (gravity held, travel moves it by exactly that); the bite or the burst's end hands the momentum
     * back, and the leap's pose and the gait take the body back over.
     * @return whether the pounce owns the body's facing this tick
     */
    private boolean tickLocalPounce() {
        if (localPounceTick < 0) return false;
        var spec = localPounceSpec;
        int tick = localPounceTick++, burst = tick - spec.gather();
        float yaw = (float) Math.toDegrees(Math.atan2(-localPounceLine.x, localPounceLine.z));
        setYRot(Mth.approachDegrees(getYRot(), yaw, 40));
        yBodyRot = yHeadRot = getYRot();
        riderLockYaw = getYRot();
        rideMomentum = 0;
        if (burst < 0) {
            setDeltaMovement(0, Math.min(0, getDeltaMovement().y), 0);
        } else if (burst < spec.burst() && !localPounceBit) {
            Vec3 v = localPounceLine.scale(spec.speedAt(burst));
            // travel() moves the body by this velocity before gravity is taken off it
            setDeltaMovement(v);
            resetFallDistance();
        } else {
            Vec3 exit = localPounceBit ? localPounceLine.scale(.12) : localPounceLine.scale(spec.exit());
            setDeltaMovement(exit.x, Math.min(exit.y, .15), exit.z);
            localPounceTick = -1;
            gallopMomentum = Math.max(gallopMomentum, localPounceBit ? 0 : .6F);
            rideMomentum = 1;
            return true;
        }
        if (horizontalCollision && burst > 0) {
            // A wall ends it where it stands.
            setDeltaMovement(0, getDeltaMovement().y, 0);
            localPounceTick = -1;
        }
        return true;
    }

    /** Client, every tick: the body pitches along a pounce's burst (its own motion), and back once it is spent. */
    private void tickPouncePitch() {
        previousPouncePitch = pouncePitch;
        DigimonAttack attack = getAnimatingAttack();
        var spec = PounceAttacks.get(attack);
        float want = 0;
        if (spec != null && attackAnimationState.isStarted()) {
            float clip = attackAnimationState.getTimeInMillis(tickCount) / 50F;
            if (clip >= spec.gather() && clip < spec.gather() + spec.burst() + 1 && !swingConnected) {
                Vec3 moved = localPounceTick >= 0 ? localPounceLine : new Vec3(getX() - xo, getY() - yo, getZ() - zo);
                if (moved.lengthSqr() > 1.0E-4)
                    want = Mth.clamp((float) Math.toDegrees(Math.atan2(moved.y, moved.horizontalDistance())), -60, 45);
                pounceTrail(spec, clip, moved);
            }
        }
        pouncePitch = Mth.approach(pouncePitch, want, 9);
    }

    /**
     * Client: the icy jaws of a pounce under way stream frost behind them: snowflakes and a thin cold smoke left along the
     * burst, a glint of ice now and then. Every client strews its own.
     */
    private void pounceTrail(PounceAttacks.Spec spec, float clip, Vec3 moved) {
        var frame = spec.attack().motion().sample(clip);
        Vec3 jaws = AttackGeometry.world(position(), frame.mouth(), yBodyRot);
        Vec3 back = moved.lengthSqr() > 1.0E-4 ? moved.normalize().scale(-.12) : Vec3.ZERO;
        for (int i = 0; i < 3; i++) {
            // spread over the stretch the jaws covered this tick, so a fast burst leaves a whole streak
            Vec3 at = jaws.subtract(moved.scale(i / 3.0));
            level().addParticle(ParticleTypes.SNOWFLAKE, at.x + (random.nextDouble() - .5) * .3, at.y + (random.nextDouble() - .5) * .3,
                    at.z + (random.nextDouble() - .5) * .3, back.x + (random.nextDouble() - .5) * .04, back.y + .02, back.z + (random.nextDouble() - .5) * .04);
        }
        if (tickCount % 2 == 0) level().addParticle(ParticleTypes.WHITE_SMOKE, jaws.x, jaws.y, jaws.z, back.x * .5, .01, back.z * .5);
        if (random.nextInt(3) == 0) level().addParticle(ParticleTypes.ITEM_SNOWBALL, jaws.x, jaws.y, jaws.z, back.x, .05, back.z);
    }

    /**
     * Client: what the breath sheds as it flies (snowflakes riding the puffs, cold smoke off the end of the stream) and
     * where it meets something (a burst of snow and ice thrown along the surface). Every client strews its own, from the
     * puffs it flies.
     */
    private void breathParticles(FrostBreath breath) {
        int life = breath.spec().life();
        for (var p : breath.puffs()) {
            int beat = p.seed + tickCount;
            if (p.hit != null) {
                Vec3 along = p.velocity();
                for (int i = 0; i < 2; i++)
                    level().addParticle(ParticleTypes.SNOWFLAKE, p.hit.x, p.hit.y, p.hit.z, along.x * .35 + (random.nextDouble() - .5) * .15,
                            along.y * .35 + random.nextDouble() * .06, along.z * .35 + (random.nextDouble() - .5) * .15);
                if (beat % 3 == 0) level().addParticle(ParticleTypes.ITEM_SNOWBALL, p.hit.x, p.hit.y, p.hit.z, along.x * .2, .08, along.z * .2);
                if (beat % 4 == 0) level().addParticle(ParticleTypes.WHITE_SMOKE, p.hit.x, p.hit.y, p.hit.z, along.x * .1, .02, along.z * .1);
                continue;
            }
            // on the flame's skin, not inside its solid body
            Vec3 skin = breathSkin(p, breath.radius(p));
            if (beat % 4 == 0 && p.age < life * .85F)
                level().addParticle(ParticleTypes.SNOWFLAKE, skin.x, skin.y, skin.z, p.vx * .4 + (random.nextDouble() - .5) * .06,
                        p.vy * .4 + (random.nextDouble() - .5) * .06, p.vz * .4 + (random.nextDouble() - .5) * .06);
            if (beat % 7 == 0 && p.age > life * .55F)
                level().addParticle(ParticleTypes.WHITE_SMOKE, skin.x, skin.y, skin.z, p.vx * .15, p.vy * .15 + .01, p.vz * .15);
        }
    }

    /** A random point on a puff's skin: {@code radius} (a little more) out from it, across its flight. */
    private Vec3 breathSkin(FrostBreath.Puff p, float radius) {
        Vec3 flow = new Vec3(p.lookX, p.lookY, p.lookZ);
        Vec3 way = new Vec3(random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5);
        way = way.subtract(flow.scale(way.dot(flow)));
        if (way.lengthSqr() < 1.0E-6) return p.position();
        return p.position().add(way.normalize().scale(radius * (1 + random.nextDouble() * .25)));
    }

    /** Client: the breath's puffs as this client draws them; the local rider's own crosshair aims its newest ones. */
    private void tickClientBreath() {
        DigimonAttack attack = getAnimatingAttack();
        var spec = BreathAttacks.get(attack);
        boolean burning = false;
        if (spec != null && attackAnimationState.isStarted() && tickCount < attackAnimationEndTick) {
            if (clientBreath == null || clientBreath.spec() != spec) {
                clientBreath = new FrostBreath(spec);
                localBreathYaw = this.entityData.get(DATA_ATTACK_YAW);
                localBreathPitch = this.entityData.get(DATA_ATTACK_AIM_PITCH);
            }
            float clip = attackAnimationState.getTimeInMillis(tickCount) / 50F;
            burning = clip >= attack.motion().activeFrom() && clip <= attack.motion().activeUntil();
            if (burning) {
                float aimYaw = this.entityData.get(DATA_ATTACK_YAW), aimPitch = this.entityData.get(DATA_ATTACK_AIM_PITCH);
                Player rider = rider();
                if (rider != null && rider.isLocalPlayer()) {
                    // The rider sees the breath answer the mouse at once; the server follows a moment later.
                    Vec3 mouth = breathMouth(attack, clip, yBodyRot, localBreathYaw, localBreathPitch);
                    Vec3 to = riderAim(rider, attack).subtract(mouth);
                    if (to.lengthSqr() > 1.0E-6) {
                        localBreathYaw = Mth.approachDegrees(localBreathYaw, (float) Math.toDegrees(Math.atan2(-to.x, to.z)), spec.turn());
                        localBreathPitch = Mth.approach(localBreathPitch,
                                Mth.clamp((float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance())), -55, 70), spec.pitchTurn());
                    }
                    aimYaw = localBreathYaw;
                    aimPitch = localBreathPitch;
                }
                Vec3 mouth = breathMouth(attack, clip, yBodyRot, aimYaw, aimPitch);
                clientBreath.emit(mouth, Vec3.directionFromRotation(aimPitch, aimYaw), new Vec3(getX() - xo, getY() - yo, getZ() - zo), random);
                clientBreathMouth = mouth;
            }
        }
        if (burning && !clientBreathing) clientBreathSince = tickCount;
        clientBreathing = burning;
        if (clientBreath != null) {
            if (!burning) clientBreath.breakTrain();
            clientBreath.step(level());
            breathParticles(clientBreath);
            if (!burning && clientBreath.isEmpty()) clientBreath = null;
        }
    }

    /** Upward speed of the species' leap ({@code body.mount.jump}), blocks a tick; 0 for one that does not leap. */
    public float leapPower() { return getBody().mount().map(DigimonBody.Mount::jump).orElse(0F); }

    /**
     * Server, the AI's own body: a leap straight up from where it stands, the rider's leap without the run (a thrower
     * takes off so its weapon leaves the hand near the top, {@link com.digicube.entity.ai.ThrowerBrain}).
     */
    public boolean leapForThrow() {
        if (level().isClientSide() || isVehicle() || leapPower() <= 0 || !onGround() || isInWater()) return false;
        Vec3 v = getDeltaMovement();
        setDeltaMovement(v.x, leapPower(), v.z);
        needsSync = true;
        return true;
    }

    /** A leap at the top of the gallop: this share of its pace added forwards, and this share more height (a little along the way, most at the top). */
    private static final float LEAP_PUSH = .3F, LEAP_LIFT = .12F, LEAP_TOP = .33F;
    /** Blocks of fall a leaping mount (and its rider) lands without harm: its own high jump, with a slope under it. */
    private static final double LEAP_SAFE_FALL = 6;

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        if (getLocomotion().hovers()) return false;
        boolean leaper = getBody().mount().map(m -> m.jump() > 0).orElse(false);
        return super.causeFallDamage(leaper ? Math.max(0, fallDistance - LEAP_SAFE_FALL) : fallDistance, multiplier, source);
    }

    // --- the leap's pose, client side: takeoff and landing on time, the flight on vertical speed -----------------

    /** Ticks of the {@code jump} clip: the takeoff runs 0 to RISE, the flight RISE to LAND, the landing LAND to END. */
    public static final float LEAP_RISE = 3, LEAP_APEX = 8, LEAP_LAND = 13, LEAP_END = 22;
    private float leapTick = -1, previousLeapTick = -1, leapWeight, previousLeapWeight, leapLaunch, leapImpact;
    private boolean leapLanded;
    private int airTicks, leapLandedTick = Integer.MIN_VALUE / 2, leapTakeoffTick = Integer.MIN_VALUE / 2;

    /** Client: where the {@code jump} clip is (-1 on the ground), and how much of the pose it has. */
    public float getLeapTick(float partial) { return leapTick < 0 ? -1 : Mth.lerp(partial, Math.max(0, previousLeapTick), leapTick); }
    public float getLeapWeight(float partial) { return Mth.lerp(partial, previousLeapWeight, leapWeight); }
    public int ticksSinceLeapLanding() { return tickCount - leapLandedTick; }
    public int ticksSinceLeapTakeoff() { return tickCount - leapTakeoffTick; }
    /** How hard the last landing was, 0 to 1 (a gallop leap's fall is about 1). */
    public float leapImpact() { return leapImpact; }

    /**
     * A leap's pose follows the body, so every client sees it the same, the rider's and everyone else's: leaving the
     * ground upwards starts the takeoff, the flight is scrubbed by vertical speed (nose up and legs folded rising,
     * forelegs reaching falling), and touching down plays the landing, faster at pace, blending back into the gait.
     * A drop of a few ticks (walking off a ledge) joins at the apex.
     */
    private void tickLeapPose(double rise, double pace) {
        previousLeapTick = leapTick;
        previousLeapWeight = leapWeight;
        boolean air = !onGround() && !isInWater() && !isPassenger() && getFlightPhase() == FlightPhase.GROUNDED;
        airTicks = air ? airTicks + 1 : 0;
        if (air) {
            if (leapTick < 0 || leapLanded) {
                if (rise > .15) { leapTick = 0; leapLaunch = (float) rise; leapLanded = false; leapTakeoffTick = tickCount; }
                else if (airTicks >= 6 && rise < -.2) { leapTick = LEAP_APEX; leapLaunch = .6F; leapLanded = false; }
            } else if (leapTick < LEAP_RISE) leapTick = Math.min(LEAP_RISE, leapTick + 1);
            else {
                float fall = Mth.clamp((float) (leapLaunch - rise) / (2 * leapLaunch), 0, 1);
                leapTick = Math.max(leapTick, LEAP_RISE + (LEAP_LAND - LEAP_RISE) * fall);
            }
            if (leapTick >= 0) leapWeight = Mth.approach(leapWeight, 1, .5F);
        } else if (leapTick >= 0) {
            if (!leapLanded) {
                leapLanded = true;
                leapLandedTick = tickCount;
                leapImpact = Mth.clamp((float) -previousRise / .6F, 0, 1);
            }
            // Short of the touchdown pose (a landing on higher ground) it is caught up quickly; then on time.
            leapTick = leapTick < LEAP_LAND ? Math.min(LEAP_LAND, leapTick + 3) : leapTick + 1 + (float) Mth.clamp(pace / .3, 0, 1.5);
            float out = Mth.clamp((leapTick - LEAP_LAND - 1) / (LEAP_END - LEAP_LAND - 1), 0, 1);
            leapWeight = Math.min(leapWeight, 1 - out * out * (3 - 2 * out));
            if (leapTick >= LEAP_END) { leapTick = -1; leapWeight = 0; }
        }
        previousRise = rise;
    }
    private double previousRise;

    /**
     * Both sides: a rider has pressed a drawn shot and it has not left yet (from the press, through the raise and
     * the hold, to the release). The client reads its own animation, the server its timeline.
     */
    public boolean riderDrawing() {
        if (level() == null || rider() == null) return false;
        DigimonAttack attack = level().isClientSide() ? getAnimatingAttack() : riderAttack ? activeAttack : null;
        var spec = riderSpec(attack);
        if (spec == null || spec.aim() != com.digicube.digimon.RiderAttack.Aim.SHOT || spec.input() != com.digicube.digimon.RiderAttack.Input.HOLD) return false;
        // A thrown weapon is held in the hand, not drawn over a running body: the rider steers as ever.
        if (com.digicube.digimon.ThrownAttacks.handles(attack)) return false;
        if (!level().isClientSide()) return attackTick < attack.hitTick();
        return attackAnimationState.isStarted() && attackAnimationState.getTimeInMillis(tickCount) / 50F < attack.hitTick();
    }

    /** Lets the rider's sprint key work from the saddle; what it does is {@link DigimonBody.Mount#sprint}, in water {@code waterSprint}. */
    @Override public boolean canSprint() { return getBody().mount().map(mount -> mount.sprint() > 1 || mount.waterSprint() > 1).orElse(false); }

    /** Steepest climb or dive a ridden swimmer follows the view into, in degrees. */
    public static final float SWIM_PITCH = 70;
    /** Share of the swim input the jump key (rise) and the dive key (sink) add, whatever the view. */
    private static final float SWIM_LIFT = .7F;
    /** Water over the feet, as a share of the body's height, under which a ridden swimmer counts as at the surface. */
    private static final double SURFACE_DEPTH = .9;
    /** Client only: the local rider holds the dive key. Only the rider's own client moves its mount, so one flag serves. */
    public static boolean localRiderDives;

    /**
     * A mount at home in the water ({@code body.mount.water_turn_rate}): it follows the view into steep dives, rises
     * and sinks on keys, holds the surface, surges on the sprint key and leaps out of the water on a surge.
     */
    private boolean seaMount() {
        return getBody().mount().map(mount -> mount.waterTurnRate() > 0).orElse(false);
    }

    /** Water over the feet at which a ridden swimmer floats at the surface ({@code body.mount.float_line} of its height). */
    private double floatLine() {
        return getBbHeight() * getBody().mount().map(mount -> (double) mount.sea().floatLine()).orElse(SURFACE_DEPTH);
    }
    /** Share of the body's height above its float line at which it settles back at full sink. */
    private static final double SETTLE_BAND = .15;

    /**
     * Once per ridden tick, before the body moves: a sea mount at the surface does not coast on up out of the water (the
     * surface takes its climb, only a surge breaches), and a haul up a bank climbs at a steady pace, on out of the water
     * until the feet clear the top ({@link #haulsOut}), whatever the species' swimming speed.
     */
    private void surfaceAndHaul(Player player) {
        Vec3 v = getDeltaMovement();
        boolean hauls = player.zza > 0 && (isInWater() || haulGrace > 0) && haulsOut();
        haulGrace = hauls ? HAUL_GRACE : Math.max(0, haulGrace - 1);
        if (hauls) setDeltaMovement(v.x, Math.max(v.y, HAUL_PACE), v.z);
        else if (holdsSurface(player)) {
            // Held afloat: eased onto its float line from above or below, never on past it, so it neither bobs out nor dips.
            double below = getFluidHeight(FluidTags.WATER) - floatLine();
            setDeltaMovement(v.x, Mth.clamp(below * FLOAT_PULL, -FLOAT_RATE, FLOAT_RATE), v.z);
        }
        else if (isInWater() && v.y > 0 && !player.isSprinting() && getFluidHeight(FluidTags.WATER) < floatLine()) setDeltaMovement(v.x, v.y * .4, v.z);
    }

    /**
     * A sea mount that holds the surface ({@code body.mount.surface_dive}): within {@link #SURFACE_BAND} of its float
     * line it stays afloat and swims level, head out, however the rider looks, until the rider looks down past
     * surface_dive degrees with a key held (a dive), holds the dive key, or surges looking up past it (a breach).
     * Climbing from below it levels off at the surface instead of shooting on out of the water.
     */
    private boolean holdsSurface(Player player) {
        float dive = getBody().mount().map(mount -> mount.sea().surfaceDive()).orElse(0F);
        if (dive <= 0 || !isInWater() || getFluidHeight(FluidTags.WATER) - floatLine() > getBbHeight() * SURFACE_BAND) return false;
        if (riderDives(player) || player.getXRot() > dive && (player.zza != 0 || player.xxa != 0)) return false;
        return !(player.isSprinting() && player.getXRot() < -dive);
    }
    /** Share of the body's height under its float line within which the surface still holds it. */
    private static final double SURFACE_BAND = .2;
    /** How hard the float line pulls a held body back (blocks a tick per block off it), and its fastest pull. */
    private static final double FLOAT_PULL = .25, FLOAT_RATE = .08;

    /** A sea mount that swims along its own length ({@code turn_to_travel}): it turns into the keys in water too. */
    private boolean swimsAlongLength() {
        return seaMount() && turnsToTravel();
    }

    /**
     * The forward push of a body swimming along its own length: the keys' push, most of it kept while the body is still
     * coming round (a swimmer carves its turn), a slow back-paddle reined back.
     */
    private float swimPush(Player player) {
        float push = Math.min(1, Mth.sqrt(player.zza * player.zza + player.xxa * player.xxa));
        if (player.zza < 0) return -push * BACK_SWIM;
        float align = Mth.cos(Mth.wrapDegrees(player.getYRot() + travelTurn(player) - getYRot()) * Mth.DEG_TO_RAD);
        return push * Math.max(SWIM_THE_TURN, align);
    }
    private static final float BACK_SWIM = .25F, SWIM_THE_TURN = .6F;

    /** Out of the water on a leap (a breach): a few ticks clear of it, not a skim along the surface. */
    public boolean leapingFromWater() {
        int out = tickCount - lastSwimTick;
        return canSwim() && !isInWater() && !onGround() && out >= LEAP_CLEAR && out < WATER_POSE_AIR;
    }
    private static final int LEAP_CLEAR = 3;

    /**
     * A sea mount that barrel-rolls ({@code body.mount.water_roll}): a double tap of the jump key in the water rolls it
     * once round its length, thrown on along its way and swung toward the side it is turning to (straight on, rolling
     * the way it last turned, when it is not); the synced roll plays it on every client. It does not roll again before
     * ROLL_AGAIN; runs on the side that moves the body.
     */
    private void barrelRoll(Player player, boolean swimming) {
        float speed = rollSpeed();
        int steer = steerSide(player);
        boolean jump = player.isJumping(), tap = jump && !lastJump;
        lastJump = jump;
        if (!tap) return;
        boolean second = tickCount - lastJumpTap <= ROLL_TAP;
        lastJumpTap = second ? -1000 : tickCount;
        if (speed <= 0 || !swimming || !second || tickCount - rollStartTick < ROLL_AGAIN || riderAttackLocked() || !isLocalInstanceAuthoritative()) return;
        int side = steer != 0 ? steer : lastSteerSide;
        double yaw = getYRot() * Mth.DEG_TO_RAD, pitch = getXRot() * Mth.DEG_TO_RAD;
        Vec3 ahead = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        setDeltaMovement(getDeltaMovement().add(ahead.scale(speed * ROLL_AHEAD)).add(left.scale(steer * speed)));
        startRoll(side);
        if (!level().isClientSide()) this.entityData.set(DATA_SWIM_ROLL, rollCode((this.entityData.get(DATA_SWIM_ROLL) >>> 1) + 1, side));
        else rollToReport = side > 0 ? 1 : 0;
    }
    /**
     * Which way the rider steers the body, 1 left, -1 right, 0 straight on: where the view and the keys point against
     * where the body faces, past STEER_ROLL degrees. The last side it steered to is kept for a roll with no turn.
     */
    private int steerSide(Player player) {
        float heading = player.getYRot() + (swimsAlongLength() ? travelTurn(player) : 0);
        float off = Mth.wrapDegrees(heading - getYRot());
        int side = off > STEER_ROLL ? -1 : off < -STEER_ROLL ? 1 : 0;
        if (side != 0) lastSteerSide = side;
        return side;
    }
    /** Ticks a barrel roll lasts, ticks before the next may start, and the most ticks between the two taps of the jump key. */
    public static final int ROLL_TICKS = 11, ROLL_AGAIN = 16, ROLL_TAP = 7;
    /** Share of the roll's speed thrown along the way the body faces, and degrees off its heading that count as a turn. */
    private static final float ROLL_AHEAD = .8F, STEER_ROLL = 6;
    private int rollStartTick = -1000, rollSide, rollSeen = -1, rolls, rollToReport = -1, lastJumpTap = -1000, lastSteerSide = -1;
    private boolean lastJump;
    private float rollSpeed() { return getBody().mount().map(mount -> mount.sea().roll()).orElse(0F); }
    private static int rollCode(int count, int side) { return (count & 0x3FFFFFFF) << 1 | (side > 0 ? 1 : 0); }
    private void startRoll(int side) { rollStartTick = tickCount; rollSide = side; rolls++; }
    /** Barrel rolls started so far, and the side the last one rolled to (1 left, -1 right; the scenarios read them). */
    public int rolls() { return rolls; }
    public int lastRollSide() { return rollSide; }
    /** The rider's client: a roll its mount started for the server to pass on (1 left, 0 right, -1 none); taking it clears it. */
    public int takeRollReport() { int report = rollToReport; rollToReport = -1; return report; }

    /** Server: the controlling rider's client reports a barrel roll its mount started ({@code side} 1 left, 0 right). */
    public void noteRiderSwimRoll(Player rider, int side) {
        if (level().isClientSide() || getControllingPassenger() != rider || rollSpeed() <= 0 || tickCount - lastRollReport < ROLL_AGAIN / 2) return;
        lastRollReport = tickCount;
        this.entityData.set(DATA_SWIM_ROLL, rollCode((this.entityData.get(DATA_SWIM_ROLL) >>> 1) + 1, side > 0 ? 1 : -1));
    }
    private int lastRollReport = -1000;

    /** Client, every tick: a body this client does not move rolls when its synced roll changes. */
    private void followRoll() {
        int code = this.entityData.get(DATA_SWIM_ROLL);
        boolean fresh = rollSeen >= 0 && code != rollSeen;
        rollSeen = code;
        if (fresh && !isLocalInstanceAuthoritative()) startRoll((code & 1) != 0 ? 1 : -1);
    }

    /** Degrees of the barrel roll under way (0 when none): a whole turn toward the side it rolls to, eased at both ends. */
    public float getSwimRoll(float partial) {
        float t = (tickCount - rollStartTick + partial) / ROLL_TICKS;
        if (t <= 0 || t >= 1) return 0;
        return 360 * t * t * (3 - 2 * t) * rollSide;
    }

    /**
     * The swimming pose holds out of the water for as long as a leap from it lasts, so a breach arcs in it
     * (and the seat with it) instead of dropping into the standing pose mid-air.
     */
    public boolean waterPose() {
        return canSwim() && (isSwimmingMovement() || !onGround() && !isInWater() && tickCount - lastSwimTick < WATER_POSE_AIR);
    }
    private static final int WATER_POSE_AIR = 40;
    private int lastSwimTick = -1000;

    /** Blocks a tick a haul climbs, and ticks out of the water it may go on climbing after the last tick it hauled. */
    private static final double HAUL_PACE = .3;
    private static final int HAUL_GRACE = 3;
    private int haulGrace;

    /** The dive key: the local rider's (client), or a scenario rider's (development only, where the server drives). */
    private boolean riderDives(Player player) {
        // Offline fixtures skip the constructor, level included.
        if (level() == null) return false;
        return level().isClientSide() ? localRiderDives : player == scenarioRider && scenarioDives;
    }

    /**
     * Client, every client. A sea mount surging under its rider (well past its cruise) jets: a stream of bubbles runs
     * back from under its body along the way it came, and the surge sets off with a squirt of water. Read from how far
     * the body moved, which every client sees, so nothing is synced for it.
     */
    private void seaWake() {
        followJet();
        boolean surging = false;
        Vec3 moved = position().subtract(xo, yo, zo);
        // A jet swimmer's every pulse outruns its cruise for a moment: it sets off its own wake, pulse by pulse (jetWake).
        if (seaMount() && rider() != null && isInWater() && jet() == null) surging = moved.length() > getLocomotion().swimSpeed() * SURGE_WAKE;
        if (surging && !wakeSurging)
            level().playLocalSound(getX(), getY() + getBbHeight() * .3, getZ(), net.minecraft.sounds.SoundEvents.SQUID_SQUIRT,
                    net.minecraft.sounds.SoundSource.NEUTRAL, .7F, .55F + random.nextFloat() * .1F, false);
        wakeSurging = surging;
        if (!surging) return;
        Vec3 back = moved.normalize().scale(-1);
        Vec3 from = position().add(0, getBbHeight() * .3, 0).add(back.scale(getBbWidth() * .5));
        for (int i = 0; i < 3; i++)
            level().addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE, from.x + (random.nextDouble() - .5) * .8,
                    from.y + (random.nextDouble() - .5) * .8, from.z + (random.nextDouble() - .5) * .8, back.x * .25, back.y * .25, back.z * .25);
    }
    /** Share of the cruise past which a ridden sea mount is surging, not swimming (the surge key reaches water_sprint). */
    private static final double SURGE_WAKE = 1.2;
    private boolean wakeSurging;

    // --- jet swimming (locomotion.jet, JetSwim): a squid moves in pulses -------------------------------------------

    /**
     * The pulse under way: {@code jetPhase} 0 to 1 of a pulse {@code jetPulseTicks} long. The side that moves the body
     * owns it (the rider's client under a rider, the server otherwise: isLocalInstanceAuthoritative) and starts every
     * pulse in {@link #jetStroke}; the others follow {@link #DATA_JET_PULSE}, which the server sets from its own pulses
     * or from those the rider's client reports ({@link #noteRiderJetPulse}). The swim clip, one pulse, plays on it.
     */
    private float jetPhase = 1, jetPulseTicks = 18;
    private boolean jetPushing;
    private int jetSeen = -1, jetPulses, lastJetThrustTick = -1000, lastJetReportTick = -1000;
    /** Client, the rider's own: a pulse its mount started, to report to the server (-1 for none). */
    private int jetToReport = -1;

    public com.digicube.digimon.JetSwim jet() { return getLocomotion().jet(); }
    /** Ticks since the last pulse that thrust (every client sees them), for the rider's camera. */
    public int ticksSinceJetThrust() { return tickCount - lastJetThrustTick; }
    /** The pulses started so far (either side), for the scenarios. */
    public int jetPulses() { return jetPulses; }
    /** The rider's client: a pulse its mount started for the server to pass on (-1 for none); taking it clears it. */
    public int takeJetReport() { int report = jetToReport; jetToReport = -1; return report; }

    private static int jetCode(int count, boolean thrust, float ticks) {
        return (count & 0x3FFFFF) << 9 | (thrust ? 256 : 0) | Mth.clamp(Math.round(ticks), 1, 255);
    }

    /** Server: the controlling rider's client reports a pulse its mount started (thrust bit and length, as jetCode). */
    public void noteRiderJetPulse(Player rider, int code) {
        if (level().isClientSide() || getControllingPassenger() != rider || jet() == null || tickCount - lastJetReportTick < JET_REPORT_GAP) return;
        lastJetReportTick = tickCount;
        this.entityData.set(DATA_JET_PULSE, jetCode((this.entityData.get(DATA_JET_PULSE) >>> 9) + 1, (code & 256) != 0, code & 255));
    }
    /** Ticks a rider's reported pulses are at least apart (a surge's are 12): anything faster is dropped. */
    private static final int JET_REPORT_GAP = 3;

    /**
     * The owning side, once per tick of travel in water: runs the pulse and returns the thrust multiplier. Pushing, it
     * pulses at the cruise's rate or the surge's (a rider on the sprint key); setting off from a glide it squeezes at
     * once. Not going anywhere it keeps pulsing slowly ({@code hover_rate}) without thrust, as a squid breathes.
     */
    private float jetStroke(Vec3 input) {
        var jet = jet();
        boolean pushing = input.lengthSqr() > 1.0E-4;
        boolean surging = seaMount() && getControllingPassenger() instanceof Player rider && rider.isSprinting();
        float ticks = surging ? jet.surgePulseTicks() : jet.pulseTicks();
        // Setting off it squeezes at once, unless a squeeze has only just gone (tapping the key does not jet any faster).
        if (pushing && !jetPushing && tickCount - lastJetThrustTick >= ticks * .5F) jetPhase = 1;
        jetPushing = pushing;
        if (jetPhase >= 1) startJetPulse(pushing, pushing ? ticks : jet.pulseTicks() / Math.max(.05F, jet.hoverRate()), surging);
        // A pulse under way takes the pace asked for now (setting off within a breath, or the surge key going down).
        else if (pushing) jetPulseTicks = ticks;
        float thrust = pushing ? jet.thrust(jetPhase) : 1;
        jetPhase += 1 / jetPulseTicks;
        return thrust;
    }

    private void startJetPulse(boolean thrust, float ticks, boolean surge) {
        jetPhase = 0;
        jetPulseTicks = ticks;
        jetPulses++;
        if (thrust) lastJetThrustTick = tickCount;
        if (!level().isClientSide()) {
            this.entityData.set(DATA_JET_PULSE, jetCode((this.entityData.get(DATA_JET_PULSE) >>> 9) + 1, thrust, ticks));
            return;
        }
        jetToReport = jetCode(0, thrust, ticks);
        if (thrust) jetWake(surge);
    }

    /** Client, every tick: a body this client does not move follows the pulses it is sent; each that thrusts has its wake. */
    private void followJet() {
        var jet = jet();
        if (jet == null) return;
        int code = this.entityData.get(DATA_JET_PULSE);
        boolean fresh = jetSeen >= 0 && code != jetSeen;
        jetSeen = code;
        if (isLocalInstanceAuthoritative() && isInWater()) return;
        if (fresh) {
            jetPhase = 0;
            jetPulseTicks = code & 255;
            if ((code & 256) != 0) {
                lastJetThrustTick = tickCount;
                jetWake(jetPulseTicks < jet.pulseTicks());
            }
        }
        jetPhase += 1 / Math.max(1, jetPulseTicks);
        // Past the end of a pulse with none new yet, it breathes on slowly.
        if (jetPhase >= 1) { jetPhase -= 1; jetPulseTicks = jet.pulseTicks() / Math.max(.05F, jet.hoverRate()); }
    }

    // --- a rider's whip (RiderAttack.Aim.WHIP, WhipAttacks, WhipArm) ----------------------------------------------

    /**
     * The long arm used as a whip, when the species has one (Gesomon's Devil Bashing); null otherwise. A rider whips with
     * it, and so does the AI. The server runs it to hit with, every client to draw it: the server starts each wind-up and
     * lash and says so through {@link #DATA_WHIP}, the rider's own client starts them itself the moment the button moves
     * ({@link #predictWhip}) so the arm answers the mouse at once, and each side steers a rider's with the rider's view as
     * it sees it, the AI's with the aim the server syncs ({@link #DATA_WHIP_YAW}).
     */
    private WhipArm whip;
    private boolean whipLooked;
    private DigimonAttack whipAttack;
    private int lastWhipSide = -1, whipSeen = -1, whipWinds;
    private Vec3[] whipBefore;
    private final java.util.Set<Integer> whipStruck = new java.util.HashSet<>();
    private boolean whipFelt;
    /** Server: the whip out is the AI's (not a rider's), and how long it plans to hold the wind-up. */
    private boolean whipByAi;
    private int whipPlan;
    /** Server: the AI's whip is aimed at this point, the prey's last predicted place (kept if the prey is lost mid-lash). */
    private Vec3 whipPoint;

    /** The whip, made the first time it is asked for; null for a species without one. */
    public WhipArm whip() {
        if (!whipLooked) {
            whipLooked = true;
            for (DigimonAttack attack : attacks()) {
                var data = com.digicube.digimon.WhipAttacks.get(attack);
                if (data != null && com.digicube.digimon.WhipAttacks.handles(attack)) { whip = new WhipArm(data); whipAttack = attack; break; }
            }
        }
        return whip;
    }
    /** The whip is out (held back, lashing or falling back). */
    public boolean whipping() { return whip != null && whip.busy(); }

    private static int whipCode(int winds, WhipArm arm) {
        return (winds & 0xFFFF) << 8 | Math.round(arm.charge() * 31) << 3 | (arm.side() > 0 ? 4 : 0) | arm.stage().ordinal();
    }
    private void syncWhip() { this.entityData.set(DATA_WHIP, whipCode(whipWinds, whip)); }

    /**
     * The arm across from the crosshair: it sweeps through the front to reach it (a crosshair to the right draws the left
     * arm). Aimed straight ahead, the arms take turns.
     */
    private int whipSide(Player rider) {
        float off = Mth.wrapDegrees(rider.getYRot() - getYRot());
        return off > WHIP_STRAIGHT ? 1 : off < -WHIP_STRAIGHT ? -1 : -lastWhipSide;
    }
    private static final float WHIP_STRAIGHT = 10;

    /** The arm across from a point (the AI's prey): it sweeps through the front onto it. */
    private int whipSideTo(Vec3 point) {
        float off = Mth.wrapDegrees(AttackGeometry.yaw(position(), point) - whipBodyYaw());
        return off > WHIP_STRAIGHT ? 1 : off < -WHIP_STRAIGHT ? -1 : -lastWhipSide;
    }

    /**
     * The facing the whip's angles are measured from: a ridden body's own (its rider's client sets it and the body with
     * it), otherwise the body's, which is the one drawn (an AI mob's head may be turned away from it).
     */
    private float whipBodyYaw() { return rider() != null ? getYRot() : yBodyRot; }

    /** The body's dive pitch the whip's root turns with: only a ridden swimmer pitches (ground_models ridden_pitch). */
    private float whipBodyPitch() { return rider() != null ? getXRot() : 0; }

    /**
     * Server: the AI whips its prey. The arm across from it is drawn back and held for as long as the moment calls for
     * (quick against an enemy about to strike, full against a hampered or committed one), then lashes at where the prey
     * will be and runs through it; the move is on its cooldown from the start, so a broken wind-up is not free.
     */
    private void beginAiWhip(DigimonAttack attack, LivingEntity target) {
        var arm = whip();
        if (arm == null || arm.busy() || target == null) return;
        activeAttack = attack;
        attackTarget = target;
        attackTick = 0;
        riderAttack = false;
        whipByAi = true;
        cooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
        int side = whipSideTo(target.position());
        arm.wind(side);
        lastWhipSide = side;
        whipWinds++;
        whipPlan = whipPlan(target, arm.spec().ai());
        whipPoint = target.getBoundingBox().getCenter();
        syncWhip();
        lookAt(target, 60.0F, 60.0F);
        if (COMBAT_TRACE) Constants.LOG.info("[whip-ai] {} winds its {} arm for {} ticks at {}", getSpeciesId().getPath(),
                side > 0 ? "left" : "right", whipPlan, target.getType().toShortString());
    }

    /** How long the AI holds its wind-up: the lash that is worth it at this moment. */
    private int whipPlan(LivingEntity target, com.digicube.digimon.WhipAttacks.Ai ai) {
        int quick = ai.winds()[0], ordinary = ai.winds()[1], full = ai.winds()[2];
        if (target instanceof DigimonEntity other && other.isAttacking()) {
            int lands = other.attackLandsIn();
            // Its blow is coming before an ordinary lash would: snap at it now.
            if (lands >= 0 && lands <= ordinary + ai.lashLead()) return quick;
            // Committed to a long move of its own: a full lash lands first.
            if (lands > full + ai.lashLead()) return full;
        }
        return impaired(target) || target.hasEffect(DCEffects.CONSTRICTED) ? full : ordinary;
    }

    /**
     * Server, every tick of the AI's whip: where it aims (relative to the body), and when the wound arm lets go. It leads
     * the prey to where the lash will meet it, lets go once the plan's wind-up is in and the prey will be in reach, gives
     * the arm up when the prey stays out of reach too long, and through the lash sweeps its aim from the arm's own side of
     * the prey to past it, so the pad crosses the body at speed.
     */
    private float[] aiWhipAim(WhipArm arm) {
        var ai = arm.spec().ai();
        LivingEntity prey = attackTarget;
        boolean lost = prey == null || !prey.isAlive() || !canStrike(prey) || isAllyOf(prey);
        Vec3 root = arm.root(whipSeat(1), whipBodyYaw(), whipBodyPitch(), mountWaterAmount);
        if (arm.stage() == WhipArm.Stage.WIND) {
            if (lost) { arm.cancel(); endAiWhip(); return whipAimAt(arm, root, whipPoint); }
            whipPoint = targetMotion().predict(prey, Math.max(0, whipPlan - arm.heldTicks()) + ai.lashLead());
            if (arm.heldTicks() >= whipPlan && whipReaches(arm, root, prey, whipPoint)) arm.release();
            else if (arm.heldTicks() > whipPlan + ai.patience()) { arm.cancel(); endAiWhip(); }
            return whipAimAt(arm, root, whipPoint);
        }
        if (arm.stage() == WhipArm.Stage.LASH) {
            if (!lost) whipPoint = targetMotion().predict(prey, Math.max(0, ai.lashLead() - arm.stageTicks()));
            float[] aim = whipAimAt(arm, root, whipPoint);
            float through = Math.min(1, arm.stageTicks() / (2F * Math.max(1, ai.lashLead())));
            aim[0] += arm.side() * ai.sweep() * (2 * through - 1);
            return aim;
        }
        if (activeAttack == whipAttack) endAiWhip();
        return new float[]{this.entityData.get(DATA_WHIP_YAW), this.entityData.get(DATA_WHIP_PITCH)};
    }

    /** Blocks a tick the target is coming at us. */
    private double closingSpeed(LivingEntity target) {
        Vec3 toUs = position().subtract(target.position()).multiply(1, 0, 1);
        // From where it went this tick: a jet charge moves the body itself and leaves no speed on it.
        Vec3 moved = new Vec3(target.getX() - target.xo, 0, target.getZ() - target.zo);
        return toUs.lengthSqr() < 1.0E-6 ? 0 : moved.dot(toUs.normalize());
    }
    /** A target closing faster than this (blocks a tick) is charging: the whip is wound for it before it arrives. */
    private static final double WHIP_CLOSING = .25;


    /** The AI's move is over (the lash is done, or it gave the arm up); the arm falls back on its own. */
    private void endAiWhip() {
        if (activeAttack != null && activeAttack == whipAttack) { activeAttack = null; attackTarget = null; }
    }

    /**
     * Yaw (relative to the body) and pitch from the arm's root to a point. On the ground the lash is aimed no steeper than
     * lets the pad just scrape the floor at the arm's full length, instead of ploughing on through it.
     */
    private float[] whipAimAt(WhipArm arm, Vec3 root, Vec3 at) {
        Vec3 to = at.subtract(root);
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z)), pitch = (float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance()));
        if (onGround() && !isInWater()) {
            double reach = arm.spec().arm(arm.side()).reach();
            pitch = Math.min(pitch, (float) Math.toDegrees(Math.asin(Math.clamp((root.y - getY() + WHIP_SCRAPE) / reach, 0, 1))));
        }
        return new float[]{Mth.wrapDegrees(yaw - whipBodyYaw()), pitch};
    }
    /** Blocks below the floor the pad may reach at the end of a lash aimed down on land. */
    private static final double WHIP_SCRAPE = .3;

    /** The prey, at {@code at}, is within the AI's share of the arm's reach from its root, with nothing solid between. */
    private boolean whipReaches(WhipArm arm, Vec3 root, LivingEntity prey, Vec3 at) {
        double reach = arm.spec().arm(arm.side()).reach() * arm.spec().ai().reach() + prey.getBbWidth() * .5;
        return at.distanceToSqr(root) <= reach * reach && clearAttackLine(root, at);
    }

    /** Server: the AI's whip is held back, waiting for its moment. */
    public boolean whipWinding() { return whipByAi && whip != null && whip.stage() == WhipArm.Stage.WIND; }

    /** Server: the AI's wound whip cannot reach its prey from here (it closes in while it waits). */
    public boolean whipOutOfReach() {
        if (!whipWinding() || attackTarget == null) return false;
        Vec3 root = whip.root(whipSeat(1), whipBodyYaw(), whipBodyPitch(), mountWaterAmount);
        return !whipReaches(whip, root, attackTarget, attackTarget.getBoundingBox().getCenter());
    }

    /**
     * Server: ticks until the attack under way lands, or -1 when it has (or there is none); what an enemy reads to get out
     * of the way. The AI's whip lands on its own plan: the rest of the wind-up and the lash's lead.
     */
    public int attackLandsIn() {
        if (activeAttack == null) return -1;
        if (whipByAi && activeAttack == whipAttack && whip != null)
            return whip.stage() == WhipArm.Stage.WIND ? Math.max(0, whipPlan - whip.heldTicks()) + whip.spec().ai().lashLead() : -1;
        int remaining = activeAttack.hitTick() - attackTick;
        return remaining > 0 ? remaining : -1;
    }

    /**
     * Whether the whip can strike the target from {@code feet}, facing it (the AI turns onto its prey as it winds): either
     * arm's root within the AI's share of its reach of the target's body, with nothing solid between.
     */
    private boolean whipReachesFrom(DigimonAttack attack, LivingEntity target, Vec3 feet, Vec3 chest) {
        var spec = com.digicube.digimon.WhipAttacks.get(attack);
        if (spec == null) return false;
        float yaw = AttackGeometry.yaw(feet, target.position());
        float water = isInWater() ? 1 : 0;
        Vec3 seat = feet.add(getBody().mount().map(m -> m.position(water)).orElse(Vec3.ZERO).yRot(-yaw * Mth.DEG_TO_RAD));
        double reach = spec.arm(1).reach() * spec.ai().reach() + target.getBbWidth() * .5;
        for (int side : new int[]{1, -1}) {
            Vec3 root = seat.add(spec.arm(side).base().lerp(spec.arm(side).waterBase(), water).yRot(-yaw * Mth.DEG_TO_RAD));
            if (root.distanceToSqr(chest) <= reach * reach && clearAttackLine(root, chest)) return true;
        }
        return false;
    }

    /** Server: the button went down. Only when the whip is ready: it is not buffered (the release may come first). */
    private boolean startRiderWhip(Player rider, DigimonAttack attack) {
        var arm = whip();
        if (arm == null || arm.stage() == WhipArm.Stage.WIND || arm.stage() == WhipArm.Stage.LASH
                || cooldownUntil.getOrDefault(attack.id(), 0) > tickCount) return false;
        int side = whipSide(rider);
        arm.wind(side);
        lastWhipSide = side;
        whipWinds++;
        syncWhip();
        return true;
    }

    /** The rider's own client, the moment the button goes down: the arm is drawn back at once (the server follows). */
    public boolean predictWhip(Player rider) {
        var arm = whip();
        if (arm == null || arm.stage() == WhipArm.Stage.WIND || arm.stage() == WhipArm.Stage.LASH || whipAttack == null
                || seenCooldown(whipAttack) > 0) return false;
        int side = whipSide(rider);
        arm.wind(side);
        lastWhipSide = side;
        return true;
    }
    /** The rider's own client, the moment the button comes up. */
    public void predictWhipRelease() { if (whip != null) whip.release(); }

    /** The rider's seat in the world, where the arm's root is measured from. */
    private Vec3 whipSeat(float partial) {
        return getBody().mount().map(m -> position().add(m.position(Mth.lerp(partial, previousMountWaterAmount, mountWaterAmount))
                .yRot(-whipBodyYaw() * Mth.DEG_TO_RAD))).orElse(position());
    }

    /**
     * Both sides, every tick: a rider's arm follows the rider's view, the AI's its prey (the server aims it, the clients
     * follow the synced aim); the server strikes with it while it lashes.
     */
    private void tickWhip() {
        if (whip == null && !whipLooked) { if (rider() == null && this.entityData.get(DATA_WHIP) == 0) return; whip(); }
        var arm = whip;
        if (arm == null) return;
        Player rider = getControllingPassenger() instanceof Player p ? p : null;
        if (level().isClientSide()) followWhip(arm);
        else if (rider == null && arm.busy() && !whipByAi) { arm.cancel(); syncWhip(); }
        else if (rider != null && whipByAi) { arm.cancel(); endAiWhip(); whipByAi = false; syncWhip(); }
        if (!arm.busy() && arm.weight(1) <= 0) { whipBefore = null; if (!level().isClientSide()) whipByAi = false; return; }
        float[] aim;
        if (rider != null) aim = whipAim(rider, arm);
        else if (!level().isClientSide() && whipByAi) {
            var before = arm.stage();
            aim = aiWhipAim(arm);
            this.entityData.set(DATA_WHIP_YAW, aim[0]);
            this.entityData.set(DATA_WHIP_PITCH, aim[1]);
            if (arm.stage() != before) syncWhip();
        } else aim = new float[]{this.entityData.get(DATA_WHIP_YAW), this.entityData.get(DATA_WHIP_PITCH)};
        if (WHIP_TRACE && !level().isClientSide() && rider != null)
            Constants.LOG.info("[whip-trace] {} body yaw {} rider {}/{} eye {} aim {}/{}", arm.stage(), String.format("%.0f", getYRot()), String.format("%.0f", rider.getYRot()),
                    String.format("%.0f", rider.getXRot()), rider.getEyePosition().subtract(position()), String.format("%.0f", aim[0]), String.format("%.0f", aim[1]));
        var event = arm.tick(aim[0], aim[1]);
        if (event == WhipArm.Event.LASH) {
            whipStruck.clear();
            whipFelt = false;
            whipBefore = null;
            // A rider's whip reloads from the lash; the AI's has been on its cooldown since the wind-up began.
            if (whipAttack != null) {
                if (level().isClientSide()) seenCooldownUntil.put(whipAttack.id(), tickCount + whipAttack.cooldownTicks());
                else if (!whipByAi) cooldownUntil.put(whipAttack.id(), tickCount + whipAttack.cooldownTicks());
            }
        }
        if (!level().isClientSide() && whipByAi && arm.stage() != WhipArm.Stage.WIND && arm.stage() != WhipArm.Stage.LASH) endAiWhip();
        if (level() instanceof ServerLevel server) {
            if (event != WhipArm.Event.NONE) syncWhip();
            if (event == WhipArm.Event.LASH)
                server.playSound(null, getX(), getY() + getBbHeight() * .5, getZ(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                        net.minecraft.sounds.SoundSource.HOSTILE, 1.1F, .55F + arm.charge() * .15F);
            if (arm.stage() == WhipArm.Stage.LASH) whipHits(server, rider, arm);
        } else if (arm.stage() == WhipArm.Stage.LASH && !whipFelt && isLocalInstanceAuthoritative()) whipFeel(arm);
    }

    /**
     * Where the whip goes: at what the crosshair is on (the first block along the rider's view within
     * {@link #WHIP_SIGHT}, or that far along it), seen from the arm's own root, which is far below the rider's eye; as
     * yaw relative to the body and pitch.
     */
    private float[] whipAim(Player rider, WhipArm arm) {
        Vec3 eye = rider.getEyePosition(), look = rider.getLookAngle();
        var hit = level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(WHIP_SIGHT)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, rider));
        Vec3 at = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? eye.add(look.scale(WHIP_SIGHT * .6)) : hit.getLocation();
        return whipAimAt(arm, arm.root(whipSeat(1), whipBodyYaw(), whipBodyPitch(), mountWaterAmount), at);
    }
    /** Blocks along the rider's view the whip looks for what the crosshair is on. */
    private static final double WHIP_SIGHT = 9;

    /** Client: follows the server's wind-ups and lashes; the rider's own client has started them itself already. */
    private void followWhip(WhipArm arm) {
        int code = this.entityData.get(DATA_WHIP);
        if (code == whipSeen) return;
        boolean first = whipSeen < 0;
        whipSeen = code;
        if (first) return;
        var stage = WhipArm.Stage.values()[code & 3];
        int side = (code & 4) != 0 ? 1 : -1;
        boolean own = isLocalInstanceAuthoritative();
        if (stage == WhipArm.Stage.WIND && (!own || arm.side() != side || arm.stage() != WhipArm.Stage.WIND)) {
            arm.wind(side);
            lastWhipSide = side;
        } else if (stage == WhipArm.Stage.LASH && arm.stage() != WhipArm.Stage.LASH && arm.stage() != WhipArm.Stage.RECOVER) {
            arm.lashNow((code >> 3 & 31) / 31F);
            whipStruck.clear();
            whipFelt = false;
            if (whipAttack != null) seenCooldownUntil.put(whipAttack.id(), tickCount + whipAttack.cooldownTicks());
        }
    }

    /**
     * Server, every tick of a lash: the arm swept from where it was last tick to where it is now; whatever it passes
     * through is struck once a lash, harder the more momentum was gathered and the faster the pad is going, and slapped
     * along the way the pad goes. A body behind a wall from the arm's root is not struck. The AI's whip
     * strikes only what it fights (its prey, and whatever is after it or its owner); a rider's anything but an ally.
     */
    private void whipHits(ServerLevel level, Player rider, WhipArm arm) {
        var spec = arm.spec();
        var limb = spec.arm(arm.side());
        Vec3[] now = arm.joints(whipSeat(1), whipBodyYaw(), whipBodyPitch(), mountWaterAmount, 1);
        Vec3[] before = whipBefore == null ? now : whipBefore;
        whipBefore = now;
        int tip = now.length - 1;
        Vec3 swing = now[tip].subtract(before[tip]);
        float padSpeed = (float) swing.length();
        if (WHIP_TRACE) {
            float[] r = arm.angles(0, 1), p = arm.angles(tip - 1, 1);
            Constants.LOG.info("[whip-trace] side {} root {}/{} pad {}/{} root at {} tip at {} speed {}", arm.side(), String.format("%.0f", r[0]), String.format("%.0f", r[1]),
                    String.format("%.0f", p[0]), String.format("%.0f", p[1]), now[0].subtract(position()), now[tip].subtract(position()), String.format("%.2f", padSpeed));
        }
        var reach = new net.minecraft.world.phys.AABB(now[0], now[0]).inflate(limb.reach() + 2);
        for (Entity entity : level.getEntities(this, reach)) {
            LivingEntity victim = DigimonPart.livingOf(entity);
            if (victim == null || victim == this || victim == rider || !victim.isAlive() || whipStruck.contains(victim.getId())
                    || !canStrike(victim) || isAllyOf(victim) || whipByAi && !fightsWith(victim)) continue;
            var boxes = HitParts.of(victim);
            Vec3 contact = null;
            for (int i = 0; i < tip && contact == null; i++) {
                double radius = i == tip - 1 ? limb.padRadius() : spec.radius();
                // swept: this section at a few steps between where it was last tick and where it is now
                for (int k = 0; k <= 3 && contact == null; k++) {
                    double f = k / 3.0;
                    contact = touch(boxes, before[i].lerp(now[i], f), before[i + 1].lerp(now[i + 1], f), radius);
                }
            }
            if (contact == null || !clearAttackLine(now[0], victim.getBoundingBox().getCenter())) continue;
            whipStruck.add(victim.getId());
            // The blow is as hard as the pad meets the body: a body running into the lash (a charge) takes both speeds.
            float impact = (float) swing.subtract(victim.getX() - victim.xo, victim.getY() - victim.yo, victim.getZ() - victim.zo).length();
            float scale = spec.power(arm.charge()) * spec.speedScale(Math.max(padSpeed, impact));
            Vec3 along = new Vec3(swing.x, 0, swing.z);
            along = along.lengthSqr() < 1.0E-6 ? victim.position().subtract(position()).multiply(1, 0, 1).normalize() : along.normalize();
            if (!hitWithAttack(level, whipAttack, victim, contact.subtract(along), scale)) continue;
            if (victim instanceof DigimonEntity struck) struck.feel(this);
            // A slap moves a body by its bulk: a player or a cow is flung, a Golemon rocks.
            float slap = spec.knockback() * spec.speedScale(padSpeed) / Math.max(1, victim.getBbWidth() * victim.getBbHeight() / 2);
            victim.push(along.x * slap, .16 * slap, along.z * slap);
            victim.hurtMarked = true;
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK, contact.x, contact.y, contact.z, 1, 0, 0, 0, 0);
            level.sendParticles(victim.isInWater() ? net.minecraft.core.particles.ParticleTypes.SPLASH : net.minecraft.core.particles.ParticleTypes.CRIT,
                    contact.x, contact.y, contact.z, 10, .25, .25, .25, .2);
            level.playSound(null, contact.x, contact.y, contact.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG,
                    net.minecraft.sounds.SoundSource.HOSTILE, 1F, .65F + level.getRandom().nextFloat() * .1F);
            level.playSound(null, contact.x, contact.y, contact.z, net.minecraft.sounds.SoundEvents.SLIME_SQUISH,
                    net.minecraft.sounds.SoundSource.HOSTILE, .8F, .75F);
            Constants.LOG.info("[whip] {} lashed {} x{} (charge {}, pad {} blocks a tick, {} against the body)", getSpeciesId().getPath(),
                    victim.getType().toShortString(), String.format("%.2f", scale), String.format("%.2f", arm.charge()), String.format("%.2f", padSpeed),
                    String.format("%.2f", impact));
        }
    }

    /** Server: who last struck this body by touch (a whip's lash), and until when it knows where they are. */
    private int feltFrom = -1, feltUntil;
    /** Ticks a body a lash touched knows where the whip came from, even Inked (DCEffects.blindTo). */
    private static final int FELT_TICKS = 100;
    void feel(Entity attacker) { feltFrom = attacker.getId(); feltUntil = tickCount + FELT_TICKS; }
    public boolean feels(Entity attacker) { return attacker.getId() == feltFrom && tickCount < feltUntil; }

    /** Server: the AI's own fight: the prey it whips at, or a mob after it or its owner. */
    private boolean fightsWith(LivingEntity victim) {
        if (victim == attackTarget || victim == getTarget()) return true;
        return victim instanceof Mob mob && (mob.getTarget() == this || getOwner() != null && mob.getTarget() == getOwner());
    }

    /** Development: {@code DIGICUBE_WHIP_TRACE=true} logs the lashing arm every tick. */
    private static final boolean WHIP_TRACE = "true".equals(System.getenv("DIGICUBE_WHIP_TRACE"));

    /** The first point of segment pq within {@code radius} of any of the boxes, or null. */
    private static Vec3 touch(java.util.List<net.minecraft.world.phys.AABB> boxes, Vec3 p, Vec3 q, double radius) {
        for (int k = 0; k <= 5; k++) {
            Vec3 at = p.lerp(q, k / 5.0);
            for (var box : boxes) {
                double dx = Math.max(Math.max(box.minX - at.x, 0), at.x - box.maxX);
                double dy = Math.max(Math.max(box.minY - at.y, 0), at.y - box.maxY);
                double dz = Math.max(Math.max(box.minZ - at.z, 0), at.z - box.maxZ);
                if (dx * dx + dy * dy + dz * dz <= radius * radius) return at;
            }
        }
        return null;
    }

    /** The rider's own client: the camera's shudder when its lash meets a body (the server decides the hit itself). */
    private void whipFeel(WhipArm arm) {
        Vec3[] now = arm.joints(whipSeat(1), whipBodyYaw(), whipBodyPitch(), mountWaterAmount, 1);
        var reach = new net.minecraft.world.phys.AABB(now[0], now[0]).inflate(arm.spec().arm(arm.side()).reach() + 2);
        for (Entity entity : level().getEntities(this, reach)) {
            if (!(entity instanceof LivingEntity living) || entity == rider() || !living.isAlive()) continue;
            var box = java.util.List.of(living.getBoundingBox());
            for (int i = 0; i < now.length - 1; i++)
                if (touch(box, now[i], now[i + 1], arm.spec().radius()) != null) { seenImpactTick = tickCount; whipFelt = true; return; }
        }
    }

    /**
     * Client: a pulse's wake. Water blows out of the siphon under the mantle, back along the way the body goes, in a
     * puff of bubbles, with a soft squirt; a surge's pulse blows harder.
     */
    private void jetWake(boolean surge) {
        if (!isInWater()) return;
        Vec3 ahead = Vec3.directionFromRotation(getXRot(), getYRot());
        Vec3 siphon = position().add(0, getBbHeight() * .3, 0).subtract(ahead.scale(getBbWidth() * .4));
        int count = surge ? 16 : 9;
        for (int i = 0; i < count; i++) {
            double spread = .12;
            level().addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE, siphon.x + (random.nextDouble() - .5) * .9,
                    siphon.y + (random.nextDouble() - .5) * .9, siphon.z + (random.nextDouble() - .5) * .9,
                    -ahead.x * .35 + (random.nextDouble() - .5) * spread, -ahead.y * .35 + (random.nextDouble() - .5) * spread,
                    -ahead.z * .35 + (random.nextDouble() - .5) * spread);
        }
        level().playLocalSound(siphon.x, siphon.y, siphon.z, net.minecraft.sounds.SoundEvents.SQUID_SQUIRT, net.minecraft.sounds.SoundSource.NEUTRAL,
                surge ? .45F : .22F, (surge ? .95F : .8F) + random.nextFloat() * .12F, false);
    }

    /** Blocks above the water's surface a swimmer can haul its feet onto a bank: a one-block quay with room to spare. */
    private static final double HAUL_ABOVE = 1.6;

    /**
     * A ridden swimmer pressing into something ahead that it could stand on top of: a bank or a quay no higher than
     * {@link #HAUL_ABOVE} over the water (a serpent's climb, if higher), or a ledge under water. It rises until its feet
     * clear the top, and the push carries it over (on land it walks on from there). Nothing to climb onto, or a wall too
     * high: it stays put.
     */
    private boolean haulsOut() {
        if (!horizontalCollision) return false;
        AABB ahead = getBoundingBox().move(Vec3.directionFromRotation(0, getYRot()).scale(.35));
        if (level().noCollision(this, ahead)) return false;
        double surface = getY() + getFluidHeight(FluidTags.WATER);
        double above = Math.max(HAUL_ABOVE, climbHeight());
        for (double lift = .25; lift <= getBbHeight() + above; lift += .25)
            if (level().noCollision(this, ahead.move(0, lift, 0))) return getY() + lift <= surface + above;
        return false;
    }

    @Override
    public float maxUpStep() {
        float step = getBody().mount().map(DigimonBody.Mount::stepHeight).orElseGet(super::maxUpStep);
        // A path is planned up any ledge a serpent climbs; its feet still only step as high as their step.
        return planningPath ? Math.max(step, (float) climbHeight()) : step;
    }

    private boolean planningPath;

    /** The navigation is planning a path for this body (true) or done planning (false): see {@link #maxUpStep}. */
    public void planningPath(boolean planning) { planningPath = planning; }

    /** How far the head looks off the body before the body comes round after it ({@code body.head_turn}). */
    @Override
    public int getMaxHeadYRot() {
        // Offline fixtures skip the constructor, synced data included.
        return this.entityData == null ? super.getMaxHeadYRot() : Math.round(getBody().headTurn());
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return getBody().mount()
                .map(mount -> {
                    Vec3 point = mount.position(mountWaterAmount).scale(scale).yRot(-getYRot() * Mth.DEG_TO_RAD);
                    // Vanilla subtracts the player's 0.6-block vehicle attachment.
                    // Cancel that for a standing mount whose data describes the feet.
                    return mount.standing() ? point.add(passenger.getVehicleAttachmentPoint(this)) : point;
                })
                .orElseGet(() -> super.getPassengerAttachmentPoint(passenger, dimensions, scale));
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (aerialMount()!=null && !onGround() && !level().isClientSide()) {
            requestFlightLanding();
        }
        if (getBody().mount().isPresent()) {
            // Search beside the feet, so leaving a tall mount does not drop the tamer
            // from its shoulders. Vanilla checks floor, dangerous blocks and clearance.
            double radius = (getBbWidth() + passenger.getBbWidth()) * 0.5 + 0.5;
            for (int angle : new int[]{90, -90, 135, -135, 45, -45, 180, 0}) {
                Vec3 offset = new Vec3(0.0, 0.0, radius).yRot(-(getYRot() + angle) * Mth.DEG_TO_RAD);
                for (int dy : new int[]{0, 1, -1, 2}) {
                    BlockPos block = BlockPos.containing(getX() + offset.x, getY() + dy, getZ() + offset.z);
                    Vec3 safe = DismountHelper.findSafeDismountLocation(passenger.getType(), level(), block, true);
                    if (safe != null) {
                        return safe;
                    }
                }
            }
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    // --- mounted combat: the rider casts, the way the mount faces is the aim ---------------------

    /** A press this close to the end of a strike or a cooldown is kept and cast on the first ready tick. */
    public static final int RIDER_BUFFER_TICKS = 6;
    /** Half angle of the soft-target cone around the rider's view, and ticks a swing's pose holds on contact. */
    public static final float SOFT_TARGET_CONE = 35;
    public static final int HIT_STOP_TICKS = 3;
    /** Ticks a drawn shot is held raised before it is fully charged (a bow's full draw is 20 too). */
    public static final int FULL_DRAW_TICKS = 14;
    private int riderDrawTicks;
    /** Client only: the local rider holds a drawn shot's button. Only the rider's own client animates ahead of the server. */
    public static boolean localRiderDraws;
    private boolean riderAttack, riderReleased;
    private float riderLockYaw, riderStaleYaw, rideMomentum;
    private int bufferedRiderSlot = -1, bufferedRiderUntil;
    /** Client only: the running attack animation's first tick, moved forward while a hit-stop holds the pose. */
    private int attackAnimationStartTick, hitStopTicks;
    private boolean swingConnected;
    /** Client only: an authored volume of the running attack landed, so its contact-only effect cells may show. */
    private boolean attackConnected;
    public boolean attackConnected() { return attackConnected; }

    // --- a rider's pounce, flown by the rider's own client (it owns the ridden body) --------------------
    /** Client: the clip tick of the pounce this client flies (-1 for none), and the tick it was pressed. */
    private int localPounceTick = -1, localPouncePress = Integer.MIN_VALUE / 2;
    private Vec3 localPounceLine;
    private boolean localPounceAir, localPounceBit;
    private PounceAttacks.Spec localPounceSpec;
    /** Client: when a pounce's jaws last shut on something (the renderer bursts the ice at the jaws). */
    private int pounceBiteTick = Integer.MIN_VALUE / 2;
    public int ticksSincePounceBite() { return tickCount - pounceBiteTick; }
    /** Client: how far the body pitches along a pounce's line while it bursts, degrees up, and its previous tick. */
    private float pouncePitch, previousPouncePitch;
    public float getPouncePitch(float partial) { return Mth.lerp(partial, previousPouncePitch, pouncePitch); }
    /** Client: the breath's puffs as this client draws them, and the local rider's own aim for them. */
    private FrostBreath clientBreath;
    private float localBreathYaw, localBreathPitch;
    public FrostBreath clientBreath() { return clientBreath; }
    /** Client: whether the mouth is shedding its breath now, where the mouth last shed from, and since which tick. */
    private boolean clientBreathing;
    private Vec3 clientBreathMouth;
    private int clientBreathSince;
    public boolean isClientBreathing() { return clientBreathing; }
    public Vec3 clientBreathMouth() { return clientBreathMouth; }
    /** Client: ticks the mouth has been shedding its breath, 0 when it is not. */
    public int clientBreathTicks() { return clientBreathing ? tickCount - clientBreathSince : 0; }
    /** Client: a leap's momentum is being carried (body.mount.leap_carry) until the body lands. */
    private boolean leaping;
    /** Client only: when the last connected swing and the last ground slam were seen, for the camera. */
    private int seenImpactTick = -1000, seenSlamTick = -1000;
    public int ticksSinceImpact() { return tickCount - seenImpactTick; }
    public int ticksSinceSlam() { return tickCount - seenSlamTick; }
    /** Client only: when a shot last left this body (its clip passed the hit tick), for the rider's recoil. */
    private int seenShotTick = -1000;
    private float lastShotClock = -1;
    public int ticksSinceShot() { return tickCount - seenShotTick; }
    /** Client only: when each attack this client saw start is ready again, in this entity's ticks. */
    private final Map<Identifier, Integer> seenCooldownUntil = new HashMap<>();
    /** Client only: refill clocks of the stacked uses this client saw spent. */
    private final Map<Identifier, List<Integer>> seenChargeRefills = new HashMap<>();

    /** Attacks a rider can cast, in the sheet's slot order ({@code body.mount.rider_attacks}); empty without mounted combat. */
    public List<DigimonAttack> riderAttacks() {
        var specs = getBody().mount().map(DigimonBody.Mount::riderAttacks).orElse(List.of());
        if (specs.isEmpty()) return List.of();
        List<DigimonAttack> own = attacks();
        return specs.stream().map(spec -> own.stream().filter(a -> a.id().equals(spec.attack())).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull).toList();
    }

    /** How a rider aims and presses {@code attack}, or null when it is not a rider attack of this mount. */
    public com.digicube.digimon.RiderAttack riderSpec(DigimonAttack attack) {
        if (attack == null) return null;
        for (var spec : getBody().mount().map(DigimonBody.Mount::riderAttacks).orElse(List.of())) if (spec.attack().equals(attack.id())) return spec;
        return null;
    }

    /** Both sides: how full a rider attack's tile is, 0 (just used) to 1 (ready). A stream shows its tank. */
    public float riderReadiness(DigimonAttack attack, float partial) {
        if (attack.fuel() != null) return this.entityData.get(DATA_RIDER_FUEL);
        var returning = com.digicube.digimon.ThrownAttacks.returning(attack);
        if (returning != null) return boneReadiness(returning, partial);
        // An icicle in hand keeps its tile lit: it cools down from the throw.
        if (com.digicube.digimon.ThrownAttacks.charged(attack) != null && throwerClipPlaying(ThrowerClip.CHARGING)) return 1;
        // Raised and drawn, a shot's tile stays lit: it reloads from the shot (a sweep while held read as a reload).
        if (attack == getAnimatingAttack() && attack.kind() == DigimonAttack.Kind.KINETIC_SHOT && attackAnimationState.isStarted()
                && attackAnimationState.getTimeInMillis(tickCount) / 50F < attack.hitTick()) return 1;
        if (attack.cooldownTicks() <= 0) return 1;
        if (com.digicube.digimon.AttackCharges.of(attack) > 1) {
            // Stacked: how far the soonest spent use has come back; full when none is out.
            var refills = level().isClientSide() ? seenChargeRefills : chargeRefills;
            int next = com.digicube.digimon.AttackCharges.nextRefill(refills, attack, tickCount);
            return next <= tickCount ? 1 : 1 - Mth.clamp((next - tickCount - partial) / attack.cooldownTicks(), 0, 1);
        }
        return 1 - Mth.clamp((seenCooldown(attack) - partial) / attack.cooldownTicks(), 0, 1);
    }

    /** Both sides: uses of a stacked attack ready now (the client's from the starts it has seen); 1 or 0 for any other. */
    public int readyUses(DigimonAttack attack) {
        if (com.digicube.digimon.AttackCharges.of(attack) <= 1) return riderReadiness(attack, 0) >= 1 ? 1 : 0;
        return com.digicube.digimon.AttackCharges.ready(level().isClientSide() ? seenChargeRefills : chargeRefills, attack, tickCount);
    }

    /** Client: ticks until {@code attack} is ready again, from the attack starts this client has seen. */
    public int seenCooldown(DigimonAttack attack) {
        // A lost bone is back when it grows again, or at once when the rider takes it off the ground.
        var returning = com.digicube.digimon.ThrownAttacks.returning(attack);
        if (returning != null && !boneCarried()) {
            var bone = seenBone();
            return bone == null || boneWithinReach(bone, returning) ? 0 : Math.max(0, bone.regrowIn());
        }
        return Math.max(0, seenCooldownUntil.getOrDefault(attack.id(), 0) - tickCount);
    }

    /** Client: this thrower's bone, last seen ticking within the last two ticks. */
    private BoomerangEntity seenBone;
    private int seenBoneTick;
    public void noticeBone(BoomerangEntity bone) { seenBone = bone; seenBoneTick = tickCount; }
    public BoomerangEntity seenBone() {
        return seenBone != null && seenBone.isAlive() && tickCount - seenBoneTick <= 2 ? seenBone : null;
    }

    /** A lost bone lies where a press would take it off the ground. */
    public boolean boneWithinReach(BoomerangEntity bone, com.digicube.digimon.ThrownAttacks.Returning spec) {
        return bone.phase() == BoomerangEntity.Phase.GROUNDED && bone.position().distanceTo(position()) <= spec.pickupRadius();
    }

    /**
     * Client: how full the bone's tile is. On the back, lit. In flight its colour comes back as it flies home, a clock
     * of the return; lost, as the new one grows, and lit again when the bone lies within reach of a press.
     */
    private float boneReadiness(com.digicube.digimon.ThrownAttacks.Returning spec, float partial) {
        if (boneCarried()) return 1;
        var bone = seenBone();
        if (bone == null) return 0;
        return switch (bone.phase()) {
            case FLYING -> bone.path() == null ? 0 : Mth.clamp((bone.flight() + partial) / (float) bone.path().ticks(), 0, .99F);
            case CATCHING -> .99F;
            case DROPPING, GROUNDED -> boneWithinReach(bone, spec) ? 1 : 1 - Mth.clamp((bone.regrowIn() - partial) / spec.dropTicks(), 0, 1);
        };
    }

    /**
     * What a rider's swing would go for: the nearest hostile inside the attack's reach and within
     * {@link #SOFT_TARGET_CONE} of the rider's view. Both sides: the client outlines it, the server turns the
     * swing to it. Players are never soft targets, so a duel between riders stays a matter of aim.
     */
    public LivingEntity softTarget(Player rider, DigimonAttack attack) {
        var spec = riderSpec(attack);
        if (spec == null || spec.cone() <= 0 || spec.aim() == com.digicube.digimon.RiderAttack.Aim.GRAB) return null;
        double reach = (spec.reach() > 0 ? spec.reach() : attack.range()) + getBbWidth() * .5;
        // A charge goes where the movement keys point (A alone dashes left), so it picks its prey around that heading.
        float heading = rider.getYRot() + (spec.aim() == com.digicube.digimon.RiderAttack.Aim.CHARGE ? riderKeysTurn(rider) : 0);
        Vec3 view = Vec3.directionFromRotation(0, heading);
        boolean thrown = com.digicube.digimon.ThrownAttacks.handles(attack) || spec.aim() == com.digicube.digimon.RiderAttack.Aim.POUNCE;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity candidate : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(reach + 1, Math.max(2, reach * .5), reach + 1),
                e -> e.isAlive() && e != this && e != rider && !(e instanceof Player) && !e.isSpectator() && canAttack(e) && !isAllyOf(e))) {
            Vec3 to = candidate.position().subtract(position()).multiply(1, 0, 1);
            double distance = Math.max(0, to.length() - candidate.getBbWidth() * .5);
            if (distance > reach || to.lengthSqr() < 1.0E-6) continue;
            double angle = Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(view), -1, 1)));
            if (angle > spec.cone() || !hasLineOfSight(candidate)) continue;
            // A swing goes for what is close; a throw for what the crosshair is on, near or far.
            double score = angle + distance * (thrown ? .3 : 6);
            if (score < bestScore) { bestScore = score; best = candidate; }
        }
        return best;
    }

    /** How far above or below the mount swimming prey may be when the wrap starts; the body glides to its level on the way in. */
    public static final double GRAB_DEPTH = 2.0;
    /** A lunge climbs a step to its prey on land, lasts this long at most, and costs this much when it comes to nothing. */
    private static final double LUNGE_STEP = 1.25;
    private static final int LUNGE_TICKS = 30, LUNGE_FAIL_COOLDOWN = 40;
    /** Blocks a tick of the lunge: a serpent's strike on land, a surge in water. */
    private static final double LUNGE_PACE = .32, LUNGE_PACE_WATER = .7;
    private LivingEntity lungePrey;
    private int lungeTicks;

    /** Both sides: the prey the rider's hold would take if pressed now, or null. The server picks it; the client outlines it. */
    public LivingEntity grabPrey() {
        return level().getEntity(this.entityData.get(DATA_GRAB_PREY)) instanceof LivingEntity prey && prey.isAlive() ? prey : null;
    }

    /**
     * Server. The prey nearest the rider's crosshair that the wrap would accept, within the hold's reach and cone
     * ({@code body.mount.rider_attacks}). The level only has to be reachable: a step on land, anything in water.
     */
    private LivingEntity grabPick(Player rider, DigimonAttack attack) {
        var spec = riderSpec(attack);
        if (spec == null || activeAttack != null || lungePrey != null || constrictionReadyIn(attack) > 0 || !isAttackReady(attack)
                || hasEffect(DCEffects.FROZEN) || hasEffect(DCEffects.CONSTRICTED) || !onGround() && !isInWater()) return null;
        double reach = spec.reach() > 0 ? spec.reach() : attack.range();
        Vec3 eye = rider.getEyePosition(), look = rider.getLookAngle();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity candidate : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(reach + 1),
                e -> e.isAlive() && e != this && e != rider && !(e instanceof Player) && !e.isSpectator() && canAttack(e) && !isAllyOf(e))) {
            double distance = position().distanceTo(candidate.position()), off = Math.abs(candidate.getY() - getY());
            if (distance > reach || off > (isInWater() && candidate.isInWater() ? reach : LUNGE_STEP)) continue;
            if (ConstrictionSession.whyIneligible(this, candidate, attack, new Vec3(getX(), candidate.getY(), getZ())) != null) continue;
            double angle = Math.toDegrees(Math.acos(Mth.clamp(candidate.getBoundingBox().getCenter().subtract(eye).normalize().dot(look), -1, 1)));
            if (angle > spec.cone() || !hasLineOfSight(candidate)) continue;
            double score = angle + distance * 2;
            if (score < bestScore) { bestScore = score; best = candidate; }
        }
        return best;
    }

    /** Server. One tick of the lunge: straight at the prey, then into the wrap from the first spot it can start from. */
    private void tickGrabLunge() {
        DigimonAttack wrap = wrapMove();
        Player rider = rider();
        if (wrap == null || rider == null || ++lungeTicks > LUNGE_TICKS || !lungePrey.isAlive() || lungePrey.level() != level()
                || ConstrictionSession.whyIneligible(this, lungePrey, wrap, new Vec3(getX(), lungePrey.getY(), getZ())) != null) { endLunge(wrap, true); return; }
        Vec3 to = lungePrey.position().subtract(position());
        double flat = to.horizontalDistance();
        boolean afloat = isInWater() && lungePrey.isInWater();
        if (flat <= wrap.range() - .3) {
            Vec3 feet = grabFeet(lungePrey);
            int index = attacks().indexOf(wrap);
            constriction = feet == null || index < 0 ? null : ConstrictionSession.prepareAt(this, lungePrey, wrap, feet);
            if (constriction != null) {
                entityData.set(DATA_WRAP_RADIUS, constriction.fit().radius());
                entityData.set(DATA_WRAP_PITCH, constriction.fit().pitch());
                syncConstrictionAnchor();
                entityData.set(DATA_WRAP_DISTANCE, (float) constriction.distance());
                entityData.set(DATA_WRAP_YAW, constriction.yaw());
                resetConstrictionApproach();
                riderReleased = false;
                this.entityData.set(DATA_ATTACK_YAW, constriction.yaw());
                LivingEntity prey = lungePrey;
                beginAttack(wrap, index, prey, rider);
                endLunge(wrap, false);
                return;
            }
        }
        float yaw = AttackGeometry.yaw(position(), lungePrey.position());
        setYRot(Mth.approachDegrees(getYRot(), yaw, 25));
        yBodyRot = yHeadRot = getYRot();
        double stop = wrap.range() - 1, pace = Math.min(isInWater() ? LUNGE_PACE_WATER : LUNGE_PACE, Math.max(0, flat - stop));
        Vec3 step = flat < 1.0E-4 ? Vec3.ZERO : to.multiply(1, 0, 1).scale(pace / flat);
        if (afloat) step = step.add(0, Mth.clamp(to.y, -.35, .35), 0);
        setDeltaMovement(0, isInWater() ? 0 : getDeltaMovement().y, 0);
        move(MoverType.SELF, step);
    }

    private void endLunge(DigimonAttack wrap, boolean failed) {
        lungePrey = null;
        this.entityData.set(DATA_GRAB_LUNGE, false);
        // A lunge that came to nothing costs a moment, not the wrap's cooldown.
        if (failed && wrap != null) cooldownUntil.put(wrap.id(), tickCount + LUNGE_FAIL_COOLDOWN);
    }

    // --- the jet charge: a rider's burst that tramples, and ends in a buck on the prey it homed on ---------------

    /** Ticks of the gather before the jets fire, and of the burst; blocks a tick at its peak and as it hands back. */
    private static final int CHARGE_WINDUP = 4, CHARGE_BURST = 10;
    /** Ticks past the burst a charge that reached its prey in the air waits to come down and buck. */
    private static final int CHARGE_LANDING = 30;
    private static final double CHARGE_CREEP = .15, CHARGE_TOP = .95, CHARGE_EXIT = .42;
    /** Degrees a tick the charge turns after the view, and after the prey it homes on. */
    private static final float CHARGE_STEER = 7, CHARGE_HOME = 14;
    /**
     * The buck starts within BUCK_REACH of its prey (the kick then skids on to where it was authored to start, the
     * bodies BUCK_GAP into each other) and inside this cone of the charge; an enemy crossing the charge's path within
     * CHARGE_ACQUIRE blocks and the same cone becomes its prey.
     */
    private static final double BUCK_GAP = -.45, BUCK_REACH = 1.8, BUCK_CONE = 60, CHARGE_ACQUIRE = 5;
    /** A charge fired in the air: the thrust lifts it this much at once, and holds its fall to this while it burns. */
    private static final double AIR_THRUST = .12, AIR_SINK = -.05;
    /** Sideways reach beyond the body that the trample sweeps. */
    private static final double TRAMPLE_REACH = .35;
    private LivingEntity chargePrey;
    private int chargeTicks;
    private float chargeYaw;
    /** Ticks the burst lasts: a rider's charge the full {@link #CHARGE_BURST}, the AI's dodge {@link #DODGE_BURST}. */
    private int chargeBurst = CHARGE_BURST;
    /** The AI's burst away from a blow is a shorter one, fired at once. */
    private static final int DODGE_BURST = 6;
    private final java.util.Set<java.util.UUID> trampled = new java.util.HashSet<>();

    /** Both sides: ticks since a rider's jet charge started while it runs (the buck excluded), 0 otherwise. */
    public int riderChargeTicks() { return Math.max(0, this.entityData.get(DATA_RIDER_CHARGE) - 1); }
    public boolean riderCharging() { return this.entityData.get(DATA_RIDER_CHARGE) > 0; }
    /** Blocks a tick the charge leaves in the body when it hands the reins back: the run carries on into the gallop. */
    public static double chargeExitPace() { return CHARGE_EXIT; }

    /** The move a rider casts as the jet charge ({@code aim: charge}); the AI bursts with it too. Null for most species. */
    public DigimonAttack jetMove() {
        for (DigimonAttack attack : attacks()) {
            var spec = riderSpec(attack);
            if (spec != null && spec.aim() == com.digicube.digimon.RiderAttack.Aim.CHARGE) return attack;
        }
        return null;
    }

    /** Server: the jet could fire now for the AI: ready, standing on dry ground, free to act. */
    public boolean jetReady() {
        DigimonAttack jet = jetMove();
        return jet != null && !isVehicle() && isAttackReady(jet) && tickCount >= windedUntil && standing() && !isInWater() && !isInLava()
                && getFlightPhase() == FlightPhase.GROUNDED && !hasEffect(DCEffects.FROZEN) && !hasEffect(DCEffects.CONSTRICTED)
                && (activeAttack == null || shotFollowingThrough());
    }

    /**
     * Server, the AI's jet: a charge at {@code prey}, homing and ending in the buck as a rider's does, or with no prey a
     * straight burst along {@code yaw} that strikes nothing (a dodge, fired at once and short, or a getaway). The
     * follow-through of a shot loosed on the run gives way to it. {@code target} is who the fight is with; {@code skill}
     * names the use in the balance tally.
     */
    public boolean startJetBurst(LivingEntity target, LivingEntity prey, float yaw, boolean dodge, String skill) {
        if (level().isClientSide() || !jetReady()) return false;
        if (activeAttack != null) cancelAttack();
        DigimonAttack jet = jetMove();
        chargePrey = prey;
        chargeYaw = prey != null ? AttackGeometry.yaw(position(), prey.position()) : yaw;
        chargeBurst = dodge ? DODGE_BURST : CHARGE_BURST;
        chargeTicks = dodge ? CHARGE_WINDUP : 0;
        trampled.clear();
        activeAttack = jet;
        riderAttack = false;
        attackTarget = target;
        attackTick = 0;
        cooldownUntil.put(jet.id(), tickCount + jet.cooldownTicks());
        getNavigation().stop();
        this.entityData.set(DATA_ATTACK_YAW, chargeYaw);
        this.entityData.set(DATA_RIDER_CHARGE, 1);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .8F, .55F);
        countSkill(skill);
        if (COMBAT_TRACE) Constants.LOG.info("[jet] {} {}", getSpeciesId(), prey != null ? "charges " + prey.getType().toShortString()
                : skill.equals("jet_dodge") ? "dodges" : "breaks away");
        return true;
    }

    /**
     * Server. A rider's charge: the prey is the enemy the charge's cone picks at the press (outlined, like a swing's
     * soft target), or none. The body is the server's until it ends.
     */
    private void beginJetCharge(Player rider, DigimonAttack attack) {
        chargePrey = softTarget(rider, attack);
        chargeYaw = chargePrey != null ? AttackGeometry.yaw(position(), chargePrey.position()) : rider.getYRot() + riderKeysTurn(rider);
        // In the air there is nothing to gather against: the jets fire at once and the leap's momentum carries into them.
        chargeTicks = standing() ? 0 : CHARGE_WINDUP;
        chargeBurst = CHARGE_BURST;
        trampled.clear();
        activeAttack = attack;
        riderAttack = true;
        riderReleased = false;
        attackTarget = chargePrey;
        attackTick = 0;
        cooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
        this.entityData.set(DATA_ATTACK_YAW, chargeYaw);
        this.entityData.set(DATA_RIDER_CHARGE, 1);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .8F, .55F);
        Constants.LOG.info("[rider-charge] {} charges{}", getSpeciesId(), chargePrey == null ? " along the view" : " at " + chargePrey.getType().toShortString());
    }

    /**
     * Server, one tick of the charge. It gathers (slows, the jets light), then bursts: fast at first and easing into
     * the gallop's pace, steered after the view or homing on its prey. Whatever else it runs through is shoved aside
     * unhurt (only the buck strikes); reaching the prey it plants and bucks, in the air once it is down. A wall stops it.
     */
    private void tickJetCharge(ServerLevel level) {
        Player rider = rider();
        if (riderAttack && rider == null || isInWater() || !isAlive()) { endJetCharge(); return; }
        int tick = ++chargeTicks;
        this.entityData.set(DATA_RIDER_CHARGE, tick + 1);
        boolean burst = tick > CHARGE_WINDUP;
        LivingEntity prey = chargePrey != null && chargePrey.isAlive() && chargePrey.level() == level ? chargePrey : null;
        // Only a rider's run picks up prey on the way; the AI's dodge or getaway strikes nothing.
        if (prey == null && burst && riderAttack) prey = chargePrey = chargeAcquire(level, rider, Vec3.directionFromRotation(0, chargeYaw));
        float heading = prey != null ? AttackGeometry.yaw(position(), prey.position()) : riderAttack ? rider.getYRot() + riderKeysTurn(rider) : chargeYaw;
        chargeYaw = Mth.approachDegrees(chargeYaw, heading, prey != null ? CHARGE_HOME : CHARGE_STEER);
        setYRot(chargeYaw);
        yBodyRot = yHeadRot = chargeYaw;
        this.entityData.set(DATA_ATTACK_YAW, chargeYaw);
        Vec3 dir = Vec3.directionFromRotation(0, chargeYaw);
        double pace;
        if (!burst) pace = CHARGE_CREEP * (CHARGE_WINDUP - tick + 1) / CHARGE_WINDUP;
        else {
            double t = Math.min(1, (tick - CHARGE_WINDUP - 1) / (double) (chargeBurst - 1));
            pace = Mth.lerp(t * t, CHARGE_TOP, CHARGE_EXIT);
        }
        if (burst && tick == CHARGE_WINDUP + 1) level.playSound(null, getX(), getY(), getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 1F, .7F);
        boolean arrive = false;
        if (burst && prey != null) {
            Vec3 to = prey.position().subtract(position()).multiply(1, 0, 1);
            double gap = to.length() - prey.getBbWidth() * .5 - getBbWidth() * .5;
            double angle = to.lengthSqr() < 1.0E-6 ? 0 : Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(dir), -1, 1)));
            if (angle <= BUCK_CONE && gap <= BUCK_REACH + pace) { pace = Math.min(pace, Math.max(0, gap - BUCK_GAP)); arrive = true; }
        }
        Vec3 before = position();
        double fall = getDeltaMovement().y;
        boolean aloft = !standing();
        // Over its prey in the air it stops burning and drops to buck from the ground.
        if (burst && aloft && !arrive) fall = tick == CHARGE_WINDUP + 1 ? Math.max(fall, AIR_THRUST) : Math.max(fall, AIR_SINK);
        setDeltaMovement(0, fall, 0);
        move(MoverType.SELF, dir.scale(pace));
        if (burst) {
            jetExhaust(level, dir);
            trample(level, before, dir, pace, prey);
        }
        if (arrive && !aloft) { beginBuck(level, prey); return; }
        if (burst && horizontalCollision && position().subtract(before).horizontalDistance() < pace * .4) {
            level.broadcastEntityEvent(this, DigimonAnimationEvents.IMPACT);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.NEUTRAL, 1F, .6F);
            endJetCharge();
            return;
        }
        if (tick >= CHARGE_WINDUP + chargeBurst && !(arrive && tick < CHARGE_WINDUP + chargeBurst + CHARGE_LANDING)) endJetCharge();
    }

    /** Server. Anything the charge's body sweeps this tick, but the prey it keeps for the buck, is shoved aside unhurt. */
    private void trample(ServerLevel level, Vec3 before, Vec3 dir, double pace, LivingEntity prey) {
        AABB swept = getBoundingBox().minmax(getBoundingBox().move(before.subtract(position()))).inflate(TRAMPLE_REACH, 0, TRAMPLE_REACH);
        for (var entity : level.getEntities(this, swept)) {
            LivingEntity victim = DigimonPart.livingOf(entity);
            if (victim == null || victim == this || victim == prey || victim instanceof Player || victim.isSpectator() || !victim.isAlive()
                    || !canAttack(victim) || isAllyOf(victim) || !trampled.add(victim.getUUID())) continue;
            Vec3 side = new Vec3(-dir.z, 0, dir.x);
            if (side.dot(victim.position().subtract(position())) < 0) side = side.scale(-1);
            victim.push(side.x * .8 + dir.x * .5, .3, side.z * .8 + dir.z * .5);
            victim.hurtMarked = true;
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.NEUTRAL, 1F, .7F);
            Constants.LOG.info("[rider-charge] shoved {} aside", victim.getType().toShortString());
        }
    }

    /**
     * Whether the body stands on something: {@code onGround} is only as good as the last move, and the charge's own
     * level moves clear it, so the floor just under the feet is looked at instead.
     */
    private boolean standing() {
        return onGround() || !level().noCollision(this, getBoundingBox().move(0, -.08, 0));
    }

    /** Server. With no prey yet, the nearest enemy that crosses the charge's path (close ahead, in its cone) becomes it. */
    private LivingEntity chargeAcquire(ServerLevel level, Player rider, Vec3 dir) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(CHARGE_ACQUIRE, 2, CHARGE_ACQUIRE),
                e -> e.isAlive() && e != this && e != rider && !(e instanceof Player) && !e.isSpectator() && canAttack(e) && !isAllyOf(e)
                        && !trampled.contains(e.getUUID()))) {
            Vec3 to = candidate.position().subtract(position()).multiply(1, 0, 1);
            double distance = to.length() - candidate.getBbWidth() * .5 - getBbWidth() * .5;
            if (distance > CHARGE_ACQUIRE || to.lengthSqr() < 1.0E-6
                    || Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(dir), -1, 1))) > BUCK_CONE || !hasLineOfSight(candidate)) continue;
            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
        }
        if (best != null) Constants.LOG.info("[rider-charge] picks up {} on the way", best.getType().toShortString());
        return best;
    }

    /**
     * Both sides: degrees from the rider's view to where the movement keys point, forwards or to the side (A alone is a
     * quarter left), 0 with no side key. The server reads the rider's last input, the rider's client its own keys.
     */
    private float riderKeysTurn(Player rider) {
        float forward, left;
        if (rider instanceof net.minecraft.server.level.ServerPlayer server) {
            var input = server.getLastClientInput();
            forward = input.forward() ? 1 : 0;
            left = input.left() == input.right() ? 0 : input.left() ? 1 : -1;
        } else { forward = Math.max(0, rider.zza); left = rider.xxa; }
        return Math.abs(left) < 1.0E-3F ? 0 : (float) -Math.toDegrees(Math.atan2(left, forward));
    }

    /** Server. Fire and smoke from the back jets, dust from the hooves. */
    private void jetExhaust(ServerLevel level, Vec3 dir) {
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        for (int s = -1; s <= 1; s += 2) {
            Vec3 jet = position().add(0, getBbHeight() * .82, 0).add(side.scale(.18 * s)).subtract(dir.scale(.15));
            level.sendParticles(ParticleTypes.FLAME, jet.x, jet.y, jet.z, 1, .05, .05, .05, .02);
            level.sendParticles(ParticleTypes.SMOKE, jet.x - dir.x * .4, jet.y + .1, jet.z - dir.z * .4, 1, .08, .08, .08, .01);
        }
        level.sendParticles(ParticleTypes.CLOUD, getX() - dir.x * .8, getY() + .1, getZ() - dir.z * .8, 2, .35, .05, .35, .02);
    }

    /** Server. The charge has run its prey down: plant, wheel and buck, the retreat kick committed from here. */
    private void beginBuck(ServerLevel level, LivingEntity prey) {
        kinetic = new KineticSession(this, prey, activeAttack, position());
        syncKineticStart();
        attackTick = 0;
        attackTarget = prey;
        this.entityData.set(DATA_RIDER_CHARGE, -1);
        this.entityData.set(DATA_SUSTAINED_TICK, 0);
        this.entityData.set(DATA_SUSTAINED_ATTACK, kinetic.animation());
        kinetic.tick(level, 0);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.HORSE_BREATHE, SoundSource.NEUTRAL, 1F, .6F);
        Constants.LOG.info("[rider-charge] bucks at {} after {} ticks", prey.getType().toShortString(), chargeTicks);
    }

    /** Server. The charge is over without a buck: the reins go back to the rider, whose client carries the run on. */
    private void endJetCharge() {
        this.entityData.set(DATA_RIDER_CHARGE, 0);
        chargePrey = null;
        activeAttack = null;
        riderAttack = false;
        attackTarget = null;
    }

    private void syncKineticStart() {
        BlockPos origin = BlockPos.containing(kinetic.start());
        entityData.set(DATA_KINETIC_ORIGIN, origin);
        entityData.set(DATA_KINETIC_FRACTION, new org.joml.Vector3f((float) (kinetic.start().x - origin.getX()),
                (float) (kinetic.start().y - origin.getY()), (float) (kinetic.start().z - origin.getZ())));
        entityData.set(DATA_KINETIC_YAW, kinetic.startYaw());
    }

    /**
     * Where a rider's wrap of {@code prey} would start: the mount's feet, at the prey's level when the water lets it
     * glide there. Null when the prey is on another level.
     */
    public Vec3 grabFeet(LivingEntity prey) {
        double off = prey.getY() - getY();
        if (Math.abs(off) <= .25) return position();
        return isInWater() && prey.isInWater() && Math.abs(off) <= GRAB_DEPTH ? new Vec3(getX(), prey.getY(), getZ()) : null;
    }

    /**
     * Server only. The controlling rider casts rider slot {@code slot}: a swing turns to its soft target, anything
     * else goes where the rider looks. A press just before the mount is free is buffered.
     * @return whether the attack started now
     */
    public boolean startRiderAttack(Player rider, int slot) {
        List<DigimonAttack> usable = riderAttacks();
        if (level().isClientSide() || getControllingPassenger() != rider || slot < 0 || slot >= usable.size()
                || hasEffect(DCEffects.FROZEN) || hasEffect(DCEffects.CONSTRICTED) || getFlightPhase() != FlightPhase.GROUNDED) return false;
        DigimonAttack attack = usable.get(slot);
        if (isInWater() && !wadingAttack(attack)) return false;
        var spec = riderSpec(attack);
        if (spec == null) return false;
        if (riderAttack && kinetic != null && kinetic.riderShot() && attack != activeAttack) {
            // A drawn shot gives way to the other button (unfired, so nothing cools down), and a loosed one's follow-through to anything.
            if (attackTick < activeAttack.hitTick()) { cooldownUntil.remove(activeAttack.id()); cancelAttack(); }
            else if (attackTick > activeAttack.hitTick() + 3) cancelAttack();
        }
        // Only the charge fires in the air (mid-leap, its momentum carries into the jets), a pounce (it dives or rises
        // from a leap), a thrown weapon (a leap throws it harder) and a breath on the run (it breathes on through a leap,
        // its puffs carried by the body's flight); nothing else leaves the ground.
        boolean airborne = spec.aim() == com.digicube.digimon.RiderAttack.Aim.CHARGE || spec.aim() == com.digicube.digimon.RiderAttack.Aim.POUNCE
                || spec.aim() == com.digicube.digimon.RiderAttack.Aim.STREAM && spec.move();
        if (spec.aim() == com.digicube.digimon.RiderAttack.Aim.CHARGE ? isInWater()
                : !airborne && !com.digicube.digimon.ThrownAttacks.handles(attack) && !onGround() && !isInWater()) return false;
        if (com.digicube.digimon.ThrownAttacks.handles(attack)) return startRiderThrow(rider, attack);
        if (spec.aim() == com.digicube.digimon.RiderAttack.Aim.WHIP) return startRiderWhip(rider, attack);
        int wait = Math.max(activeAttack == null ? 0 : activeAttack.durationTicks() - attackTick,
                cooldownUntil.getOrDefault(attack.id(), 0) - tickCount);
        if (wait > 0 || !isAttackReady(attack)) {
            if (wait > 0 && wait <= RIDER_BUFFER_TICKS) { bufferedRiderSlot = slot; bufferedRiderUntil = tickCount + wait + 2; }
            return false;
        }
        bufferedRiderSlot = -1;
        int index = attacks().indexOf(attack);
        if (index < 0 || index >= DigimonAnimationEvents.MAX_ATTACKS || riderSpec(attack) == null) return false;
        if (attack.kind() == DigimonAttack.Kind.CONSTRICTION) {
            // A hold needs its prey: without one nothing is cast and nothing cools down. With one, the mount goes and gets it.
            lungePrey = grabPick(rider, attack);
            if (lungePrey == null) return false;
            lungeTicks = 0;
            this.entityData.set(DATA_GRAB_LUNGE, true);
            this.entityData.set(DATA_GRAB_PREY, -1);
            return true;
        }
        if (spec.aim() == com.digicube.digimon.RiderAttack.Aim.CHARGE) {
            beginJetCharge(rider, attack);
            return true;
        }
        LivingEntity soft = softTarget(rider, attack);
        riderReleased = false;
        // A move with forms casts the one whose key the rider holds (AttackForms.rider).
        DigimonAttack cast = riderForm(rider, attack);
        // The turn itself is played out by the rider's client (it owns the mount's facing), a wind-up's worth of degrees a tick.
        this.entityData.set(DATA_ATTACK_YAW, soft == null ? riderCastYaw(rider, cast) : AttackGeometry.contactYaw(cast, position(), soft.getBoundingBox().getCenter()));
        beginAttack(cast, index, soft, rider);
        return true;
    }

    /** Server: the form of {@code move} for the movement keys the rider holds; the move itself when it has no forms. */
    private static DigimonAttack riderForm(Player rider, DigimonAttack move) {
        if (com.digicube.digimon.AuthoredAttacks.forms(move) == null) return move;
        if (rider instanceof net.minecraft.server.level.ServerPlayer server) {
            var input = server.getLastClientInput();
            return AttackForms.rider(move, input.forward(), input.left(), input.right());
        }
        return AttackForms.rider(move, rider.zza > 0, rider.xxa > 0, rider.xxa < 0);
    }

    /**
     * Both sides: the facing a rider's cast takes without a soft target. It is the rider's view, except for the spike
     * wave: that one starts at the fist, beside the body, so it is turned until its line crosses the view at the
     * wave's reach, as the AI turns it through its target. The phantom preview uses the same yaw.
     */
    public float riderCastYaw(Player rider, DigimonAttack attack) {
        if (attack.kind() != DigimonAttack.Kind.GROUND_WAVE) return rider.getYRot();
        return TectonicWave.yaw(position(), position().add(Vec3.directionFromRotation(0, rider.getYRot()).scale(attack.range())), attack.motion());
    }

    /** Server only. The rider let go of a held attack: a stream stops breathing and plays its exhale. */
    public void stopRiderAttack(Player rider) {
        if (level().isClientSide() || getControllingPassenger() != rider) return;
        bufferedRiderSlot = -1;
        if (riderAttack && activeAttack != null) riderReleased = true;
        // A held whip lets go and lashes at the crosshair.
        if (whip != null) whip.release();
        // A held icicle goes where the crosshair is now: straight from the hand once formed, a snap dart if it is still forming.
        if (thrower != null && thrower.charging()) {
            var charged = thrower.charged();
            thrower.aim(riderIcicleAim(rider, charged));
            thrower.releaseNow();
        }
        // The bone's held wind-up goes as far as it has been charged; let go before it was cocked, it is a tap.
        else if (thrower != null && thrower.windingUp()) {
            aimRiderThrows(rider);
            thrower.releaseNow();
        }
    }

    /**
     * Server. A rider's thrown weapon: the bone flies at the crosshair (through the soft target if one is outlined) and
     * curves home on the side of the strafe key held (left without one, as a right-handed throw curves), re-aimed every
     * tick of the wind-up; tapped it goes at once, held it stays cocked and winds up to a far throw, going on release; a
     * press beside the bone lying lost picks it up (walking over it does too). The icicle forms, grows for as long as the
     * button is held and goes on release, or as a snap dart from a tap. Either may be thrown from a leap, harder.
     */
    private boolean startRiderThrow(Player rider, DigimonAttack attack) {
        var thrower = thrower();
        var returning = com.digicube.digimon.ThrownAttacks.returning(attack);
        if (returning != null) {
            if (!thrower.carried()) {
                var bone = thrower.bone();
                if (bone == null || !thrower.beginPickup(bone)) return false;
                this.entityData.set(DATA_ATTACK_YAW, getYRot());
                return true;
            }
            if (!thrower.boneReady()) return false;
            LivingEntity soft = softTarget(rider, attack);
            int side = riderThrowSide(rider);
            var aim = com.digicube.entity.ai.ThrowerBrain.riderThrow(this, riderAim(rider, attack), soft, side, returning.throwClip().event(), 0);
            if (!thrower.startThrow(soft, aim.yaw(), aim.range(), side, aim.lift())) return false;
            // Held from the press: cocked at the top of the wind-up it waits for the button (a tap has let go already).
            thrower.riderHold();
            riderAttack = true;
            riderReleased = false;
            return true;
        }
        var charged = com.digicube.digimon.ThrownAttacks.charged(attack);
        if (charged == null || !thrower.startIcicle(softTarget(rider, attack), 1)) return false;
        thrower.riderHold();
        thrower.aim(riderIcicleAim(rider, charged));
        riderAttack = true;
        riderReleased = false;
        return true;
    }

    /** +1 curves the bone to the left and home on that side, -1 to the right: the strafe key held, left without one. */
    private static int riderThrowSide(Player rider) {
        if (rider instanceof net.minecraft.server.level.ServerPlayer server) {
            var input = server.getLastClientInput();
            return input.right() && !input.left() ? -1 : 1;
        }
        return rider.xxa < 0 ? -1 : 1;
    }

    /**
     * Server. Where a rider's icicle is thrown: the crosshair's point, or where the outlined target (or an enemy a
     * few degrees off the crosshair) will be when the spear gets there, for the arc of the charge in hand.
     */
    private Vec3 riderIcicleAim(Player rider, com.digicube.digimon.ThrownAttacks.Charged spec) {
        DigimonAttack attack = spec.attack();
        LivingEntity aimed = softTarget(rider, attack);
        Vec3 point = riderAim(rider, attack);
        if (aimed == null) return point;
        var t = thrower();
        float c = Math.max(t.charge(), .01F);
        Vec3 origin = ThrowerState.local(position(), spec.releasePoint(c), getYRot());
        Vec3 chest = AttackGeometry.chest(aimed.getBoundingBox());
        // Led for the speed it will leave with: harder from a leap or a run.
        double speed = spec.mix(spec.speed(), c) * t.impulse(chest.subtract(origin), spec.mix(spec.speed(), c), spec.airBoost());
        double gravity = spec.mix(spec.gravity(), c);
        Vec3 velocity = aimed.position().subtract(aimed.xOld, aimed.yOld, aimed.zOld).multiply(1, 0, 1);
        int toRelease = t.stage() == ThrowerState.Stage.ICE_RELEASE ? Math.max(0, spec.release().event() - t.stageTick()) : spec.release().event() + 1;
        Vec3 aim = chest;
        for (int pass = 0; pass < 3; pass++) {
            Vec3 v = Ballistics.launch(origin, aim, speed, gravity);
            if (v == null) break;
            aim = chest.add(velocity.scale(Math.min(30, toRelease + Ballistics.flightTicks(origin, aim, v))));
        }
        return aim;
    }

    /**
     * Server, every ridden tick: the bone lying lost is taken up as the body comes over it (the rider only has to walk
     * there), unless the hands are busy.
     */
    private void pickUpUnderRider() {
        var returning = thrower.returning();
        var bone = thrower.bone();
        if (returning == null || thrower.carried() || thrower.busy() || bone == null || bone.phase() != BoomerangEntity.Phase.GROUNDED
                || !onGround() || bone.position().subtract(position()).horizontalDistance() > returning.pickupRadius() - PICKUP_SLACK
                || Math.abs(bone.getY() - getY()) > 1.5) return;
        if (thrower.beginPickup(bone)) this.entityData.set(DATA_ATTACK_YAW, AttackGeometry.yaw(position(), bone.position()));
    }
    /** How far inside its reach the bone must lie before a ridden body bends for it on its own. */
    private static final double PICKUP_SLACK = .3;

    /** Server, every ridden tick: the rider's throw winding up and the icicle in hand follow the crosshair. */
    private void aimRiderThrows(Player rider) {
        if (thrower == null || !riderAttack) return;
        var returning = thrower.returning();
        if (returning != null && thrower.windingUp()) {
            LivingEntity soft = softTarget(rider, returning.attack());
            int side = riderThrowSide(rider);
            var aim = com.digicube.entity.ai.ThrowerBrain.riderThrow(this, riderAim(rider, returning.attack()), soft, side,
                    thrower.boneReleaseIfLetGo(), thrower.charge());
            thrower.retarget(soft, aim.yaw(), aim.range(), side, aim.lift());
        }
        var charged = thrower.charged();
        if (charged != null && (thrower.charging() || thrower.stage() == ThrowerState.Stage.ICE_RELEASE && thrower.stageTick() < charged.release().event()))
            thrower.aim(riderIcicleAim(rider, charged));
    }

    /**
     * Where a rider's shot or stream goes: the first thing under the crosshair. The third-person camera sits on the
     * line through the rider's eye, so the eye's ray is the crosshair's ray in either view.
     */
    private Vec3 riderAim(Player rider, DigimonAttack attack) {
        Vec3 eye = rider.getEyePosition(), end = eye.add(rider.getLookAngle().scale(attack.range() + 6));
        var block = level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        LivingEntity aimed = null;
        double nearest = eye.distanceToSqr(end);
        for (LivingEntity candidate : level().getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(1),
                e -> e.isAlive() && e != this && e != rider && !e.isSpectator() && canAttack(e) && !isAllyOf(e))) {
            var hit = candidate.getBoundingBox().inflate(.3).clip(eye, end);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < nearest) {
                nearest = eye.distanceToSqr(hit.get());
                aimed = candidate;
            }
        }
        var kineticShot = com.digicube.digimon.KineticAttacks.get(attack);
        if (kineticShot == null || kineticShot.projectile() == null) return aimed == null ? end : AttackGeometry.chest(aimed.getBoundingBox());
        // A bolt from a running mount: a crosshair a little off a nonplayer enemy still finds it, and the shot leads it.
        if (aimed == null) {
            double best = SHOT_MAGNET;
            for (LivingEntity candidate : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(attack.range() + 6),
                    e -> e.isAlive() && e != this && e != rider && !(e instanceof Player) && !e.isSpectator() && canAttack(e) && !isAllyOf(e))) {
                Vec3 to = AttackGeometry.chest(candidate.getBoundingBox()).subtract(eye);
                if (to.lengthSqr() > (attack.range() + 6) * (attack.range() + 6) || to.lengthSqr() < 1.0E-4) continue;
                double angle = Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(rider.getLookAngle()), -1, 1)));
                if (angle < best && hasLineOfSight(candidate)) { best = angle; aimed = candidate; }
            }
        }
        return aimed == null ? end : PepperBreathEntity.predictImpactPoint(aimed, position(), kineticShot.projectileSpeed(), kineticShot.maxLead());
    }

    /** Degrees off the crosshair within which a rider's shot still finds a nonplayer enemy. */
    private static final double SHOT_MAGNET = 4;

    /** The rider's crosshair point for the attack under way, when that attack is aimed by the view; null otherwise. */
    private Vec3 riderAimNow() {
        if (!riderAttack || activeAttack == null || !(getControllingPassenger() instanceof Player rider)) return null;
        var spec = riderSpec(activeAttack);
        boolean viewAimed = spec != null && (spec.aim() == com.digicube.digimon.RiderAttack.Aim.SHOT || spec.aim() == com.digicube.digimon.RiderAttack.Aim.STREAM
                || activeAttack.kind() == DigimonAttack.Kind.BOX_BURST);
        return viewAimed ? riderAim(rider, activeAttack) : null;
    }

    /** Both sides: whether {@code attack} is a rider's cast that the mount keeps running under (its upper body plays it). */
    public boolean riderMovesDuring(DigimonAttack attack) { return attack != null && rider() != null && riderMoves(attack); }

    /** Both sides: the body keeps moving under {@code attack} and its upper body plays it, for a rider or for the AI. */
    public boolean movesDuring(DigimonAttack attack) { return riderMovesDuring(attack) || rider() == null && aiShootsMoving(attack); }

    /** Whether the mount keeps walking under this rider attack instead of standing through it. */
    private boolean riderMoves(DigimonAttack attack) {
        var spec = riderSpec(attack);
        return spec != null && spec.move();
    }

    /**
     * Both sides: while a rider's strike plays the mount stands and the strike owns its facing. A swing lets go
     * soon after its hit frames, so a punch flows back into movement; a ground slam is a full commitment.
     */
    private boolean riderAttackLocked() {
        // Offline fixtures have no level and skip field initialisers.
        if (level() == null) return activeAttack != null && !riderMoves(activeAttack);
        // A thrower walks through all its performances but the pickup, which bends down to the ground.
        if (throwerClipPlaying(ThrowerClip.PICKUP)) return true;
        if (!level().isClientSide()) return activeAttack != null && !riderMoves(activeAttack);
        if (attackAnimationState == null || !attackAnimationState.isStarted() || tickCount >= attackAnimationEndTick) return false;
        DigimonAttack attack = getAnimatingAttack();
        if (attack != null && riderMoves(attack)) return false;
        return attack == null || attack.kind() != DigimonAttack.Kind.FIST || attack.motion() == null
                || tickCount - attackAnimationStartTick <= attack.motion().activeUntil() + 3;
    }

    /** Client or server: the bone's throw is winding up (held or not) and has not left the fist; {@link #throwCharge} is how far it is charged. */
    public boolean windingUpThrow() { return throwerClipPlaying(ThrowerClip.WIND_UP); }

    /** Thrower performances a rider feels: the bend for a lost bone, the wind-up of a throw, the icicle forming or held. */
    private enum ThrowerClip { PICKUP, WIND_UP, CHARGING }

    /**
     * Both sides: the thrower is in that part of a performance. The server reads its {@link ThrowerState}; a client,
     * where the rider's own mount is moved, the thrower clip it plays on the server's clock.
     */
    private boolean throwerClipPlaying(ThrowerClip what) {
        // Offline fixtures have no level and skip field initialisers.
        if (level() == null) return false;
        if (!level().isClientSide()) {
            if (thrower == null) return false;
            return switch (what) { case PICKUP -> thrower.locksLegs(); case WIND_UP -> thrower.windingUp(); case CHARGING -> thrower.charging(); };
        }
        if (attackAnimationState == null || !attackAnimationState.isStarted() || attackAnimationName == null || tickCount >= attackAnimationEndTick) return false;
        var attack = com.digicube.digimon.ThrownAttacks.owner(attackAnimationName);
        var returning = com.digicube.digimon.ThrownAttacks.returning(attack);
        var charged = com.digicube.digimon.ThrownAttacks.charged(attack);
        return switch (what) {
            case PICKUP -> returning != null && attackAnimationName.equals(returning.pickupClip().name())
                    && attackAnimationState.getTimeInMillis(tickCount) / 50F <= returning.pickupClip().event() + ThrowerState.PICKUP_STANDS;
            case WIND_UP -> returning != null && (attackAnimationName.equals(returning.throwClip().name())
                    && attackAnimationState.getTimeInMillis(tickCount) / 50F < returning.throwClip().event()
                    || attackAnimationName.equals(returning.holdClip())
                    || attackAnimationName.equals(returning.releaseClip().name())
                    && attackAnimationState.getTimeInMillis(tickCount) / 50F < returning.releaseClip().event());
            case CHARGING -> charged != null && (attackAnimationName.equals(charged.form().name()) || attackAnimationName.equals(charged.hold()));
        };
    }

    /** Client: the authored root travel of a swing, a ram or a bite, applied where the position is owned. Stops at walls and ledges. */
    private void riderLunge() {
        DigimonAttack attack = getAnimatingAttack();
        if (attack == null || attack.motion() == null || !onGround() || hitStopTicks > 0 || attack.kind() != DigimonAttack.Kind.FIST
                && attack.kind() != DigimonAttack.Kind.HORN_RAM) return;
        int tick = tickCount - attackAnimationStartTick;
        double travel = attack.motion().sample(tick + 1).travel() - attack.motion().sample(tick).travel();
        if (travel <= 0 || swingConnected) return;
        Vec3 step = new Vec3(0, 0, travel).yRot(-getYRot() * Mth.DEG_TO_RAD);
        Vec3 probe = position().add(step).add(0, .15, 0);
        if (level().clip(new ClipContext(probe, probe.add(0, -1.25, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) {
            move(MoverType.SELF, step);
        }
    }

    /** Reserve movement and look controls while the tamer drives the vanilla ridden path. */
    private final class RiderControlGoal extends Goal {
        RiderControlGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return rider() != null;
        }

        @Override
        public void start() {
            getNavigation().stop();
        }
    }

    private List<DigimonAttack> attacks() {
        return getSpecies().map(DigimonSpecies::attacks).orElse(List.of());
    }

    // --- ownership -------------------------------------------------------------------

    @Override
    public EntityReference<LivingEntity> getOwnerReference() {
        return this.entityData.get(DATA_OWNER).orElse(null);
    }

    /** Makes {@code owner} this Digimon's tamer (null releases it into the wild). */
    public void setOwner(LivingEntity owner) {
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner).map(EntityReference::of));
        if (!level().isClientSide() && isVehicle() && getControllingPassenger() == null) {
            ejectPassengers();
        }
        if (owner != null) {
            setPersistenceRequired();
        }
    }

    public boolean isOwned() {
        return getOwnerReference() != null;
    }

    public boolean isOwnedBy(LivingEntity entity) {
        EntityReference<LivingEntity> owner = getOwnerReference();
        return entity != null && owner != null && owner.matches(entity);
    }

    /** Whether joining {@code owner}'s fight against {@code target} makes sense. */
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
        if (target == null || target == owner || target == this) {
            return false;
        }
        if (target instanceof DigimonEntity other && other.isOwnedBy(owner)) {
            return false;
        }
        return canAttack(target) && !target.isAlliedTo(owner);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (isOwnedBy(target)) {
            return false;
        }
        if (target instanceof DigimonEntity other && isOwned() && other.isOwnedBy(getOwner())) {
            return false;
        }
        // Ink: what it cannot see it cannot target, so the hurt-by goal does not hand the target back every few ticks.
        if (!struckBlind && DCEffects.blindTo(this, target)) return false;
        return super.canAttack(target);
    }

    /** Set while an attack under way asks whether it may go on and land: Ink stops aiming, not a blow already begun. */
    private boolean struckBlind;

    /** Server: the AI's attack under way keeps aiming itself at its prey (a homing jet charge, a drawn kinetic shot). */
    public boolean attackTracksTarget() {
        return !riderAttack && (kinetic != null || this.entityData.get(DATA_RIDER_CHARGE) > 0);
    }

    /** {@link #canAttack}, blind or not: an attack already under way plays out, and a blow that lands, lands. */
    public boolean canStrike(LivingEntity target) {
        struckBlind = true;
        try { return canAttack(target); } finally { struckBlind = false; }
    }

    /** Public view of {@link #considersEntityAsAlly} for this Digimon's own projectiles. */
    public boolean isAllyOf(Entity other) {
        return considersEntityAsAlly(other);
    }

    /** Tamer and stable-mates count as allies (no friendly fire from sweeps, no retaliation). */
    @Override
    protected boolean considersEntityAsAlly(Entity other) {
        int side = battleSide();
        if (side != 0 && (other instanceof DigimonEntity digimon ? digimon.battleSide() == side
                : other.getVehicle() instanceof DigimonEntity mount && mount.battleSide() == side)) {
            return true;
        }
        if (isOwned()) {
            LivingEntity owner = getOwner();
            if (other == owner) {
                return true;
            }
            if (other instanceof DigimonEntity digimon && digimon.isOwnedBy(owner)) {
                return true;
            }
            if (owner != null && owner.isAlliedTo(other)) {
                return true;
            }
        }
        return super.considersEntityAsAlly(other);
    }

    // --- wild Digimon: persistence, defeat and the XP it yields ------------------------

    /** Partners persist through {@link #setOwner}; wild Digimon despawn like any animal. */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return !isOwned();
    }

    /** Vanilla orbs for the tamer: a small taste of the yield the partners split. */
    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        if (isOwned()) return 0;
        return getSpecies().map(species -> Progression.stageYield(species.stage()) / 2).orElse(0);
    }

    /** Wild Digimon always show a nameplate; the renderer prefixes it with the level. GUI previews show none. */
    @Override
    public boolean shouldShowName() {
        return !guiPreview && (!isOwned() || super.shouldShowName());
    }

    /** Marks this entity as a screen preview: no nameplate, no shadow. It must never be added to a level. */
    public void markGuiPreview() {
        guiPreview = true;
    }

    public boolean isGuiPreview() {
        return guiPreview;
    }

    /** Ordinary hits do not shake a coiling body off its prey; a push attack does, and frees the prey. */
    @Override
    public void knockback(double strength, double x, double z, DamageSource source, float damage, boolean flag) {
        if (constriction != null) {
            if (strength < com.digicube.digimon.ConstrictionMotion.BREAKING_PUSH) return;
            if (COMBAT_TRACE) Constants.LOG.info("[wrap-trace] {} wrap broken by a push of {}", getSpeciesId(), strength);
            cancelAttack();
        }
        super.knockback(strength, x, z, source, damage, flag);
    }

    /**
     * Records what a wild Digimon loses to each partner, as health actually lost, and
     * splits its XP the moment the last hit lands. Vanilla decides the hit; this only
     * watches the outcome, so armour, immunity frames and overkill never inflate a share.
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if(evolutionLocked()&&!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY))return false;
        PartyManager.markEvolutionCombat(this);
        if(source.getEntity() instanceof DigimonEntity attacker)PartyManager.markEvolutionCombat(attacker);
        DigimonEntity partner = !isOwned() && source.getEntity() instanceof DigimonEntity attacker && attacker.isOwned()
                ? attacker : null;
        // A partner's hit counts as its tamer's, like a tamed wolf's, so vanilla orbs drop for the tamer.
        if (partner != null && partner.getOwner() instanceof Player tamer) setLastHurtByPlayer(tamer, TAMER_CREDIT_TICKS);
        float healthBefore = getHealth();
        if (!super.hurtServer(level, source, amount)) return false;
        // A blow knocks a thrower's heavy charge out of its hands.
        if (thrower != null && source.getEntity() != null) thrower.struck(level, healthBefore - Math.max(0.0F, getHealth()));
        if (partner != null) {
            contributions.record(partner.getUUID(), healthBefore - Math.max(0.0F, getHealth()), level.getGameTime());
        }
        if (!isOwned() && isDeadOrDying()) awardExperienceOnDefeat(level);
        return true;
    }

    /** Server. Splits this wild Digimon's yield among its recent contributors, once. */
    private void awardExperienceOnDefeat(ServerLevel level) {
        if (experienceAwarded) return;
        experienceAwarded = true;
        ExperienceAward.award(level, this, contributions);
        contributions.clear();
    }

    // --- combat: choosing and running attacks ---------------------------------------

    public boolean hasAttacks() {
        return !attacks().isEmpty();
    }

    /** Anything it strikes with up close (a wrap included). */
    public boolean hasCloseAttack() {
        return attacks().stream().anyMatch(a -> !a.isRanged());
    }

    /** Whether any of its moves reaches beyond contact: what makes strafing worth it for an opponent. */
    public boolean hasRangedAttack() {
        return attacks().stream().anyMatch(DigimonAttack::isRanged);
    }

    public boolean isAttacking() {
        return activeAttack != null;
    }

    /** Read-only server timeline for development scenarios and diagnostics. */
    public String currentAttackAnimation() { return kinetic != null ? kinetic.animation() : activeAttack == null ? "" : activeAttack.id().getPath(); }
    public int currentAttackTick() { return attackTick; }

    /** Ordinary locomotion cannot overwrite an attack's authored movement. */
    public boolean combatControlsLocked() {
        // A thrower walks through its performances; only the pickup holds it still.
        if (thrower != null && thrower.busy()) return thrower.locksLegs();
        // The AI's wound whip may close in on its prey while it waits for its moment.
        if (whipWinding()) return false;
        return isAttacking() && !shootingOnTheRun() || constrictionPlanner != null && constrictionPlanner.aligning();
    }

    /** Server: the AI's shot under way is loosed on the run; its legs stay the combat goal's. */
    public boolean shootingOnTheRun() {
        return activeAttack != null && !riderAttack && kinetic != null && kinetic.twists();
    }

    /** Server: a shot loosed on the run has left, and its follow-through may give way to anything. */
    public boolean shotFollowingThrough() {
        return shootingOnTheRun() && attackTick > activeAttack.hitTick() + SHOT_FOLLOW_THROUGH;
    }
    private static final int SHOT_FOLLOW_THROUGH = 3;

    /**
     * Both sides: whether the AI looses {@code attack} on the run, a shot its rider may loose moving ({@code move}) for a
     * species whose tactics say so ({@code shoot_moving}).
     */
    public boolean aiShootsMoving(DigimonAttack attack) {
        return attack != null && attack.kind() == DigimonAttack.Kind.KINETIC_SHOT && tactics().shootMoving() && riderMoves(attack);
    }

    /** Constriction owns heading; ranged attacks still aim their head at the target. */
    public boolean constrictionHeadingLocked() {
        return kinetic != null || activeAttack != null && activeAttack.kind() == DigimonAttack.Kind.CONSTRICTION
                || constrictionPlanner != null && constrictionPlanner.aligning();
    }

    /** The combat goal lets a wrap finish its stable approach and turn before casting. */
    public boolean tickConstrictionApproach(LivingEntity target, double speed) {
        if (isAttacking() || isVehicle() || getFlightPhase() != FlightPhase.GROUNDED
                || hasEffect(DCEffects.FROZEN) || hasEffect(DCEffects.CONSTRICTED)) {
            resetConstrictionApproach();
            return false;
        }
        double pace = target.hasEffect(DCEffects.FROZEN) || target.hasEffect(DCEffects.COLD) ? speed * com.digicube.digimon.ConstrictionMotion.FROZEN_PURSUIT_SPEED : speed;
        return approachingConstriction(target) != null && constrictionPlanner.steer(pace);
    }

    private final AuthoredVolumeAttack authoredVolumes = new AuthoredVolumeAttack();

    static final boolean COMBAT_TRACE = combatTraceEnabled();
    private int traceTick = -100; // not MIN_VALUE: the subtraction below must not overflow

    private static boolean combatTraceEnabled() {
        if (Boolean.getBoolean("digicube.combatTrace")) return true;
        try {
            return com.digicube.platform.Services.PLATFORM.isDevelopmentEnvironment();
        } catch (Throwable absent) {
            return false; // offline fixtures load no platform
        }
    }

    private static String fmt(Vec3 v) { return String.format("(%.2f %.2f %.2f)", v.x, v.y, v.z); }
    private static String fmt(AABB b) { return String.format("[%.2f..%.2f, %.2f..%.2f, %.2f..%.2f]", b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ); }

    /** Development trace, once a second: why a chill-loop caster is or is not wrapping its target. */
    public void traceCombat(LivingEntity target) {
        if (!COMBAT_TRACE || tickCount - traceTick < 20 || !chillLoop()) return;
        traceTick = tickCount;
        DigimonAttack wrap = wrapMove();
        DigimonAttack stream = attacks().stream().filter(a -> a.kind() == DigimonAttack.Kind.FROST_STREAM).findFirst().orElse(null);
        Constants.LOG.info("[wrap-trace] {} -> {} d={} dy={} cold={} frozen={} holdRes={} water={}/{} ground={}/{} attacking={} fuel={}/{} wrapReadyIn={} planner=[{}] castFromHere={}",
                getSpeciesId(), target.getType().toShortString(),
                String.format("%.1f", position().distanceTo(target.position())), String.format("%.2f", target.getY() - getY()),
                target.hasEffect(DCEffects.COLD), target.hasEffect(DCEffects.FROZEN), target.hasEffect(DCEffects.CONSTRICTION_RESISTANCE),
                isInWater(), target.isInWater(), onGround(), target.onGround(),
                activeAttack == null ? "-" : activeAttack.id().getPath() + "@" + attackTick,
                stream == null ? 0 : fuelFor(stream).availableTicks(), stream == null ? 0 : stream.fuel().capacityTicks(),
                constrictionReadyIn(wrap), constrictionPlanner == null ? "none" : constrictionPlanner.describe(),
                String.valueOf(ConstrictionSession.rejection(this, target, wrap, position())));
    }

    /** A crowd's latest hit must not restart a committed wrap approach; clearing the target still passes. */
    @Override
    public void setTarget(LivingEntity target) {
        LivingEntity current = getTarget();
        if (target != null && target != current && constrictionPlanner != null && constrictionPlanner.committedTo(current)) return;
        super.setTarget(target);
    }

    public void resetConstrictionApproach() {
        if (constrictionPlanner != null) constrictionPlanner.clear();
    }

    int constrictionReadyIn(DigimonAttack attack) {
        return Math.max(0,Math.max(constrictionRetryTick,cooldownUntil.getOrDefault(attack.id(),0))-tickCount);
    }

    public boolean isAttackReady(DigimonAttack attack) {
        if(evolutionLocked()||tickCount<evolutionAttackUntil)return false;
        if (attack.kind() == DigimonAttack.Kind.CONSTRICTION && tickCount < constrictionRetryTick) return false;
        return attack.fuel() != null ? fuelFor(attack).isReady()
                : tickCount >= cooldownUntil.getOrDefault(com.digicube.digimon.AuthoredAttacks.move(attack).id(), 0);
    }

    /** Server: how the current target has been moving, for shots that fly straight; made on first use. */
    private TargetMotion targetMotion;
    private TargetMotion targetMotion() { return targetMotion != null ? targetMotion : (targetMotion = new TargetMotion()); }

    /** Server: the last form of a move with forms this body cast, and the tick its performance ends (AttackForms' combos). */
    private DigimonAttack lastForm;
    private int lastFormEnd = Integer.MIN_VALUE / 2;
    DigimonAttack lastForm() { return lastForm; }
    int lastFormEnd() { return lastFormEnd; }

    /**
     * Server: the chance a leaping form lands on {@code target}: its landing point is fixed at the launch, so a target
     * whose moves the aim cannot foretell over the flight ({@link TargetMotion#miss}) is likely gone when it comes down.
     * An impaired or Exposed target, and any strike that does not leap, count as certain.
     */
    double strikeChance(DigimonAttack form, LivingEntity target) {
        var authored = com.digicube.digimon.AuthoredAttacks.get(form);
        if (authored == null || authored.leap() == null || impaired(target) || com.digicube.digimon.ExposedMark.exposed(target)) return 1;
        var leap = authored.leap();
        double reach = strikeReach(authored) + target.getBbWidth() / 2;
        if (!targetMotion().follows(target)) return 1;
        double miss = targetMotion().miss(leap.land() - leap.launch()).across();
        return Math.clamp(1 - miss / reach, .05, 1);
    }

    /** How far around its aim point an authored strike still catches a body: its widest volume in its first hit window, blocks. */
    private static double strikeReach(com.digicube.digimon.AuthoredAttacks.Definition authored) {
        double reach = .5;
        if (authored.hitWindows().isEmpty()) return reach;
        var window = authored.hitWindows().getFirst();
        for (var box : authored.sample((window[0] + window[1]) * .5)) if (box != null) {
            var b = box.bounds();
            reach = Math.max(reach, Math.max(b.getXsize(), b.getZsize()) * .5);
        }
        return reach;
    }
    /** Server: where the ball goes when it leaves, re-aimed every tick of the wind-up and fixed at the release. */
    private Vec3 fireballAim;
    /** Degrees a tick the body turns onto the aim while it draws breath; the release settles it exactly. */
    private static final float FIREBALL_TURN = 24;
    /**
     * A ball flies straight, so it is only thrown while the aim can be trusted over its flight: the aim model's measured
     * error per tick of lead, times the flight, must stay inside the reach of the ball around the target.
     */
    private static final double FIREBALL_TRUST = 1.0;
    /** Blocks ahead of the snout the meeting point must be; closer than that the target is met straight ahead. */
    private static final double FIREBALL_AHEAD = .5;

    /** Scenario tooling: every move ready again at once and every tank full, so a check can fire many times in one run. */
    public void readyAttacks() { cooldownUntil.clear(); chargeRefills.clear(); if (activeAttack == null) attackFuel.clear(); }

    private FuelReserve fuelFor(DigimonAttack attack) {
        return attackFuel.computeIfAbsent(attack.id(), id -> new FuelReserve(attack.fuel()));
    }

    /** Server: squeezed out of breath; it may move, but starts no attack before this tick. */
    private int windedUntil;
    void windFor(int ticks) { windedUntil = Math.max(windedUntil, tickCount + ticks); }

    /**
     * Frost-capable move sets plan mark then breath using fuel and target status.
     * Other move sets retain their authored species order.
     */
    public DigimonAttack chooseAttack(LivingEntity target) {
        if (getFlightPhase() != FlightPhase.GROUNDED || isVehicle() || isAttacking() || hasEffect(DCEffects.FROZEN) || hasEffect(DCEffects.CONSTRICTED)
                || tickCount < windedUntil
                || target == null || !target.isAlive() || !canAttack(target)) return null;
        DigimonAttack wrap = approachingConstriction(target);
        if (wrap != null) return constrictionPlanner.ready() ? wrap : null;
        DigimonAttack pounceMove = attacks().stream().filter(PounceAttacks::handles).findFirst().orElse(null);
        if (pounceMove != null) return choosePounce(target, pounceMove);
        DigimonAttack chosen = null;
        for (DigimonAttack attack : attacks()) {
            if (attack.kind() == DigimonAttack.Kind.CONSTRICTION || !usefulShot(attack, target)) continue;
            // A brawler lands its melee when it can; its opener is for the walk in. Others keep the sheet's order.
            if (chosen == null || tactics().preferClose() && attack.range() < chosen.range()) chosen = attack;
            if (!tactics().preferClose()) break;
        }
        return chosen;
    }

    /** A pouncer's breath opens from beyond this distance while the prey's Freeze gauge can still fill. */
    private static final double BREATH_FROM = 3.5;
    /** Share of its tank a pouncer's breath starts on (it is spent filling a gauge, not tickling one). */
    private static final float BREATH_OPENING_FUEL = .45F;

    /**
     * A pouncer (Garurumon: Freeze Fang with Howling Blaster beside it): Frozen prey is shattered with a pounce; prey
     * whose Freeze gauge can still fill is breathed on from a few blocks out, the stream swept after it, until it
     * freezes; otherwise, and up close, it pounces (a second use follows a first that bit). Nothing usable: close in.
     */
    private DigimonAttack choosePounce(LivingEntity target, DigimonAttack pounceMove) {
        if (pounceLeapPrey != null) return null;
        DigimonAttack breathMove = attacks().stream().filter(BreathAttacks::handles).findFirst().orElse(null);
        boolean pounceReady = isAttackReady(pounceMove) && inRange(pounceMove, target);
        boolean breathReady = breathMove != null && isAttackReady(breathMove) && inRange(breathMove, target)
                && fuelFor(breathMove).availableTicks() >= breathMove.fuel().capacityTicks() * BREATH_OPENING_FUEL;
        if (FreezeMark.frozen(target)) {
            // Frozen prey is for the shatter: pounced on, or leapt at when it is out of a pounce's reach.
            if (pounceReady) return pounceMove;
            leapToPounce(target, pounceMove, true);
            return null;
        }
        if (breathReady && !FreezeMark.resists(target) && distanceTo(target) >= BREATH_FROM) return breathMove;
        if (pounceReady) return pounceMove;
        // Prey on a ledge above, or just out of a pounce's reach with nothing better to do, is leapt at and pounced on
        // from the top of the leap.
        if (leapToPounce(target, pounceMove, !breathReady || FreezeMark.resists(target))) return null;
        // Up close the pounce is waited for (it is back within a moment); further out the breath goes on its own.
        return breathReady && distanceTo(target) >= BREATH_FROM ? breathMove : null;
    }

    /** Server: the prey the AI leapt at to pounce from the air (null when it has not), and the tick it left the ground. */
    private int pounceLeapTick;
    private LivingEntity pounceLeapPrey;
    /** Server: the AI is in a leap it will pounce from. */
    public boolean leapingToPounce() { return pounceLeapPrey != null; }
    /** A leap to pounce from: how much further it reaches than a pounce from the ground, and the ledges it goes up for. */
    private static final double LEAP_POUNCE_BEYOND = 4, LEAP_POUNCE_LEDGE = 1.8, LEAP_POUNCE_TOP = 5;
    /** How far over a ledge's top the feet clear at the top of the leap, and the most a leap may exceed the sheet's. */
    private static final double LEAP_POUNCE_CLEAR = .6;
    private static final float LEAP_POUNCE_MOST = 1.75F;

    /**
     * Server, the AI's own body: a pouncer leaps at prey standing on a ledge above it, or ({@code fromAfar}) at prey just
     * beyond a pounce from the ground, when the top of the leap sees it within the pitch a pounce from the air may take.
     * The pounce itself goes near the top ({@link #tickPounceLeap}).
     * @return whether it leapt
     */
    private boolean leapToPounce(LivingEntity target, DigimonAttack pounceMove, boolean fromAfar) {
        if (leapPower() <= 0 || !onGround() || isInWater() || pounceLeapPrey != null || !isAttackReady(pounceMove)) return false;
        var spec = PounceAttacks.get(pounceMove);
        Vec3 chest = AttackGeometry.chest(target.getBoundingBox());
        Vec3 to = chest.subtract(position());
        double flat = to.horizontalDistance(), rise = target.getY() - getY();
        boolean ledge = rise >= LEAP_POUNCE_LEDGE && rise <= LEAP_POUNCE_TOP && flat <= pounceMove.range() + 1;
        boolean afar = fromAfar && Math.abs(rise) < LEAP_POUNCE_LEDGE && flat > pounceMove.range() && flat <= pounceMove.range() + LEAP_POUNCE_BEYOND;
        if (!ledge && !afar) return false;
        // Up to a ledge the leap carries the feet over its top (vanilla gravity .08 a tick, a little lost to drag); the
        // pounce from its top then goes level or down onto the prey.
        float power = ledge ? (float) Mth.clamp(Math.sqrt(.16 * (rise + LEAP_POUNCE_CLEAR) / .85), leapPower(), leapPower() * LEAP_POUNCE_MOST)
                : leapPower() * 1.05F;
        double climb = power * power / .16 * .85;
        if (ledge && climb < rise + .2) return false;
        Vec3 head = position().add(0, getBbHeight() * .6, 0), top = head.add(0, climb, 0);
        Vec3 aim = chest.subtract(top);
        float pitch = (float) Math.toDegrees(Math.atan2(aim.y, Math.max(1.0E-3, aim.horizontalDistance())));
        if (Math.abs(spec.pitch(pitch, true) - pitch) > 5 || !clearAttackLine(head, top) || !clearAttackLine(top, chest)) return false;
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        setYRot(yaw);
        yBodyRot = yHeadRot = yaw;
        Vec3 ahead = Vec3.directionFromRotation(0, yaw).scale(afar ? .55 : .12);
        setDeltaMovement(ahead.x, power, ahead.z);
        needsSync = true;
        getNavigation().stop();
        pounceLeapTick = tickCount;
        pounceLeapPrey = target;
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BREEZE_JUMP, SoundSource.NEUTRAL, .6F, .8F);
        countSkill("leap_pounce");
        return true;
    }

    /** Server, each tick of such a leap: once it stops rising (or near its top) the pounce goes, from the air. */
    private void tickPounceLeap() {
        if (pounceLeapPrey == null) return;
        int since = tickCount - pounceLeapTick;
        var prey = pounceLeapPrey;
        DigimonAttack move = attacks().stream().filter(PounceAttacks::handles).findFirst().orElse(null);
        if (prey == null || !prey.isAlive() || move == null || isAttacking() || isVehicle() || isInWater()
                || hasEffect(DCEffects.FROZEN) || hasEffect(DCEffects.CONSTRICTED) || since > 16 || since > 2 && onGround()) {
            pounceLeapPrey = null;
            return;
        }
        if (since < 3 || getDeltaMovement().y > .12 || !isAttackReady(move)) return;
        pounceLeapPrey = null;
        int index = attacks().indexOf(move);
        if (index >= 0 && index < DigimonAnimationEvents.MAX_ATTACKS) beginAttack(move, index, prey, null);
    }

    /** Where the enemy was when sight was lost; what a blind Digimon acts on. Server only, not saved. */
    private Vec3 lastSeenThreat;
    private int lastSeenTick;
    public void rememberThreat(Vec3 where) { lastSeenThreat = where; lastSeenTick = tickCount; }
    public Vec3 lastSeenThreat() { return lastSeenThreat != null && tickCount - lastSeenTick < 200 ? lastSeenThreat : null; }

    public com.digicube.digimon.DigimonTactics tactics() {
        return getSpecies().map(DigimonSpecies::tactics).orElse(com.digicube.digimon.DigimonTactics.DEFAULT);
    }

    /** Blind, inked, Cold, frozen or held: a target a presser rushes. */
    public static boolean impaired(LivingEntity target) {
        return target.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) || target.hasEffect(DCEffects.INKED)
                || target.hasEffect(DCEffects.COLD) || target.hasEffect(DCEffects.FROZEN) || target.hasEffect(DCEffects.CONSTRICTED);
    }

    private DigimonAttack wrapMove() {
        return attacks().stream().filter(a -> a.kind() == DigimonAttack.Kind.CONSTRICTION).findFirst().orElse(null);
    }

    /** A frost stream that is not a breath of puffs chills: its contact charges Cold (Seadramon's Ice Blast). */
    private boolean chilling() {
        return attacks().stream().anyMatch(a -> a.kind() == DigimonAttack.Kind.FROST_STREAM && !BreathAttacks.handles(a));
    }

    /** Chill, wrap the slowed prey, then spend spare fuel while it stays Cold: the chilling wrap loop. */
    private boolean chillLoop() {
        return chilling() && wrapMove() != null;
    }

    /** Cold is already running, or can never take hold. */
    private boolean coldOrImmune(LivingEntity target) {
        return target.hasEffect(DCEffects.COLD)
                || !target.canBeAffected(new MobEffectInstance(DCEffects.COLD, IceCombo.COLD_TICKS));
    }

    /** Frost ends at once against frozen prey; a wrap loop also banks fuel for its next Cold charge. */
    private boolean usefulShot(DigimonAttack attack, LivingEntity target) {
        return isAttackReady(attack) && inRange(attack, target)
                && (attack.kind() != DigimonAttack.Kind.FROST_STREAM || streamWorthStarting(attack, target));
    }

    /** Frozen prey needs the wrap, chillable prey a charge's worth of fuel, Cold prey only a full tank's spare. */
    private boolean streamWorthStarting(DigimonAttack stream, LivingEntity target) {
        if (target.hasEffect(DCEffects.FROZEN)) return false;
        if (!chillLoop()) return true;
        int available = fuelFor(stream).availableTicks();
        if (coldOrImmune(target)) return available >= stream.fuel().capacityTicks();
        return available >= IceCombo.chillFuelTicks(stream.fuel()) && !closingInBeforeChill(target);
    }

    private int closeInTargetId = -1, closeInCheckTick, closeInDeadline;
    private boolean closeInFound;

    /**
     * Prey chilled beyond wrap reach spends its Cold being walked to. When a stance inside reach on the
     * same level is reachable, close in first; a walk that never arrives withholds the stream only briefly.
     */
    private boolean closingInBeforeChill(LivingEntity target) {
        DigimonAttack wrap = wrapMove();
        // A range holder chills from its band and walks in on the slowed prey instead.
        if (wrap == null || tactics().holdsRange() || Math.abs(target.getY() - getY()) > .4 && !afloatWith(target)
                || position().distanceToSqr(target.position()) <= wrap.range() * wrap.range()) {
            closeInTargetId = -1;
            return false;
        }
        if (closeInTargetId != target.getId()) {
            closeInTargetId = target.getId();
            closeInDeadline = tickCount + com.digicube.digimon.ConstrictionMotion.CLOSE_IN_TICKS;
            closeInCheckTick = Integer.MIN_VALUE;
        }
        if (tickCount >= closeInDeadline) return false;
        if (tickCount >= closeInCheckTick) {
            closeInCheckTick = tickCount + 40;
            var path = com.digicube.entity.ai.DigimonCombatPosition.find(this, target, false);
            closeInFound = path != null && path.getEntityPosAtNode(this, path.getNodeCount() - 1)
                    .distanceToSqr(target.position()) <= wrap.range() * wrap.range();
        }
        return closeInFound;
    }

    /** Two swimmers: depth is free to change, so a level difference is no cliff. */
    private boolean afloatWith(LivingEntity target) {
        return isInWater() && target.isInWater();
    }

    /** Stances for a chilling stream are best inside wrap range, so the wrap follows without a walk. */
    public double preferredStanceRange(DigimonAttack attack) {
        DigimonAttack wrap = wrapMove();
        return attack.kind() == DigimonAttack.Kind.FROST_STREAM && wrap != null && chilling() && !tactics().holdsRange()
                ? wrap.range() : Double.POSITIVE_INFINITY;
    }

    /** A Cold charge is on the table when the stream is ready with enough fuel; range is navigation's job. */
    private boolean chillAvailable(LivingEntity target) {
        if (!chillLoop() || target.hasEffect(DCEffects.FROZEN) || coldOrImmune(target)) return false;
        return attacks().stream().anyMatch(a -> a.kind() == DigimonAttack.Kind.FROST_STREAM && isAttackReady(a)
                && fuelFor(a).availableTicks() >= IceCombo.chillFuelTicks(a.fuel()));
    }

    /** Navigation must prepare the same combo phase as attack selection, rather than backing away from frozen prey. */
    public List<DigimonAttack> positioningAttacks(LivingEntity target) {
        var moves = attacks();
        DigimonAttack wrap = approachingConstriction(target);
        if (wrap != null) return List.of(wrap);
        boolean frozen = target.hasEffect(DCEffects.FROZEN);
        // Frozen or Cold prey is the wrap's opening: close in and hold beside it even before the wrap can be rehearsed,
        // but only when the wrap will be ready while the opening still lasts; otherwise waiting beside it is a free hit.
        if ((frozen || target.hasEffect(DCEffects.COLD)) && wrapMove() != null && wrapOpening(target)) return List.of(wrapMove());
        moves = moves.stream().filter(a -> a.kind() != DigimonAttack.Kind.CONSTRICTION && a.kind() != DigimonAttack.Kind.RETREAT_KICK).toList();
        // Frozen prey is for a blow, never more frost: a pouncer goes in for the shatter.
        return frozen ? moves.stream().filter(a -> a.kind() != DigimonAttack.Kind.FROST_STREAM).toList() : moves;
    }

    /** A move this strong is worth waiting out before coiling beside its owner. */
    private static final float HEAVY_POWER = 1.0F;
    /** A heavy move that comes off cooldown within this many ticks is waited out too. */
    private static final int LOOMING_TICKS = 10;
    /** Beyond the wrap's reach by more than this, a brawler has not caught us yet. */
    private static final double CAUGHT_MARGIN = 1.0;

    /**
     * Coiling takes two seconds beside the prey in which the caster cannot dodge, so against a Digimon that is
     * fighting us the wrap is a matter of timing. One that fights in melee is never walked into: a caster that keeps
     * its distance loses more on the way in and out than the hold pays. Once the brawler has caught us the wrap is
     * the answer: it stops the blows, crushes through armour and leaves the prey Cold and out of breath, which is the
     * way out. A heavy move that is ready or under way would land for free, so the wrap waits until it is spent.
     * Quick jabs are accepted; ordinary hits no longer shake the coil off.
     */
    private boolean wrapPunished(LivingEntity target) {
        if (!(target instanceof DigimonEntity other) || other.getTarget() != this
                || other.hasEffect(DCEffects.FROZEN) || other.hasEffect(DCEffects.CONSTRICTED)) return false;
        DigimonAttack wrap = wrapMove();
        boolean brawler = other.attacks().stream().anyMatch(a -> !a.isRanged() && a.kind() != DigimonAttack.Kind.CONSTRICTION);
        double reach = wrap.range() + CAUGHT_MARGIN;
        if (brawler && tactics().holdsRange() && position().distanceToSqr(other.position()) > reach * reach) return true;
        for (DigimonAttack move : other.attacks()) {
            if (move.power() < HEAVY_POWER || move.kind() == DigimonAttack.Kind.CONSTRICTION) continue;
            if (other.activeAttack != null && com.digicube.digimon.AuthoredAttacks.move(other.activeAttack) == move && other.attackTick <= other.activeAttack.hitTick()) return true;
            if (other.cooldownUntil.getOrDefault(move.id(), 0) - other.tickCount <= LOOMING_TICKS) return true;
        }
        return false;
    }

    /** The opening's remaining ticks cover the wrap's cooldown and its capture. */
    private boolean wrapOpening(LivingEntity target) {
        if (wrapPunished(target)) return false;
        var opening = target.getEffect(DCEffects.FROZEN) != null ? target.getEffect(DCEffects.FROZEN) : target.getEffect(DCEffects.COLD);
        if (opening == null) return false;
        return constrictionReadyIn(wrapMove()) + com.digicube.digimon.ConstrictionMotion.CAPTURE_TICK <= opening.getDuration();
    }

    /** Only reserve a wrap when its complete body path has a reachable stance. */
    private DigimonAttack approachingConstriction(LivingEntity target) {
        DigimonAttack move = wrapMove();
        if (move == null) return null;
        if (target != null && wrapPunished(target)) {
            resetConstrictionApproach();
            return null;
        }
        // Chill first: while a Cold charge is on the table the wrap waits for slowed prey; any prey
        // may be wrapped once it is not. Otherwise use a clear ranged shot across elevations instead of spending
        // several seconds climbing to a wrap. Preparing a cooling wrap must not delay
        // a ready shot either; preparation fills recovery time, not attack time.
        if (target != null && (chillAvailable(target)
                || (!isAttackReady(move) || Math.abs(target.getY()-getY()) > .4 && !afloatWith(target))
                && attacks().stream().anyMatch(a -> a != move && a.isRanged() && usefulShot(a,target)))) {
            resetConstrictionApproach();
            return null;
        }
        if (constrictionPlanner == null) constrictionPlanner = new ConstrictionPlanner(this);
        return constrictionPlanner.update(target,move);
    }

    private boolean inRange(DigimonAttack attack, LivingEntity target) {
        if (attack.kind() == DigimonAttack.Kind.GROUND_WAVE && (!onGround() || isInWater() || isInLava())) return false;
        var authored=com.digicube.digimon.AuthoredAttacks.get(attack);
        if (authored!=null && authored.grounded() && (!onGround() || isInWater() || isInLava())) return false;
        return (attack.motion() == null || attack.isRanged() || onGround() || isInWater()) && canAttackFrom(attack, target, position());
    }

    /** What a body in water can still do: anything but a move that needs the ground under it. A floating brawler keeps its fists. */
    private boolean wadingAttack(DigimonAttack attack) {
        var authored = com.digicube.digimon.AuthoredAttacks.get(attack);
        return attack.kind() != DigimonAttack.Kind.GROUND_WAVE && (authored == null || !authored.grounded());
    }

    /** A land body floats chest-deep instead of standing on the water; vanilla's 0.4 suits a body one block tall. */
    @Override
    public double getFluidJumpThreshold() {
        return canSwim() ? super.getFluidJumpThreshold() : Math.max(super.getFluidJumpThreshold(), getBbHeight() * FLOAT_DEPTH);
    }
    private static final double FLOAT_DEPTH = .55;

    /**
     * Rehearse the move at a prospective foot position, including its real launch/contact geometry. A move with forms
     * can when the form the AI would cast from there can ({@link AttackForms#choose}).
     */
    public boolean canAttackFrom(DigimonAttack attack, LivingEntity target, Vec3 feet) {
        if (com.digicube.digimon.AuthoredAttacks.forms(attack) != null) return AttackForms.choose(this, attack, target, feet) != null;
        return canStrikeFrom(attack, target, feet);
    }

    /** As {@link #canAttackFrom}, for exactly this attack (one form of a move, never its choice among them). */
    public boolean canStrikeFrom(DigimonAttack attack, LivingEntity target, Vec3 feet) {
        // Thrown weapons are planned by the thrower's own AI (ThrowerBrain), never picked by the generic chooser.
        if (com.digicube.digimon.ThrownAttacks.handles(attack)) return false;
        if (com.digicube.digimon.WhipAttacks.handles(attack)) {
            if (whipReachesFrom(attack, target, feet, target.getBoundingBox().getCenter())) return true;
            // From where it stands, a target charging in is met: the whip is wound while it comes.
            var spec = com.digicube.digimon.WhipAttacks.get(attack);
            return feet.equals(position()) && closingSpeed(target) > WHIP_CLOSING && targetMotion().follows(target)
                    && whipReachesFrom(attack, target, feet, targetMotion().predict(target, spec.ai().winds()[1] + spec.ai().lashLead()));
        }
        double distance = feet.distanceToSqr(target.position());
        if (attack.kind() == DigimonAttack.Kind.MELEE) {
            // Prospective claw positions use a conservative margin inside vanilla mob reach.
            // At the actual position and on impact, vanilla remains the authority.
            return (feet.equals(position()) ? isWithinMeleeAttackRange(target)
                    : getAttackBoundingBox(.6).move(feet.subtract(position())).intersects(target.getBoundingBox()))
                    && clearAttackLine(feet.add(0, getEyeHeight(), 0), AttackGeometry.chest(target.getBoundingBox()));
        }
        if (distance > attack.range() * attack.range()) return false;
        if (com.digicube.digimon.KineticAttacks.handles(attack)) {
            return distance >= attack.motion().minimumRange() * attack.motion().minimumRange()
                    && KineticSession.canStart(this, target, attack, feet);
        }
        if (attack.kind() == DigimonAttack.Kind.CONSTRICTION) {
            return ConstrictionSession.prepareAt(this,target,attack,feet) != null;
        }
        // A stream's authored minimum is a preferred stance, not a blind spot.
        // Large enemies can remain inside the real jet while pressing into the body.
        if (attack.motion() != null && attack.fuel() == null && feet.subtract(target.position()).horizontalDistanceSqr()
                < attack.motion().minimumRange() * attack.motion().minimumRange()) return false;
        if (com.digicube.digimon.AuthoredAttacks.handles(attack)) return AuthoredVolumeAttack.canReach(this,attack,feet,target);
        if (attack.kind() == DigimonAttack.Kind.GROUND_WAVE) {
            return TectonicWave.canReach(level(), this, feet, target.getBoundingBox(), attack.motion());
        }
        if (PounceAttacks.handles(attack)) return pounceReaches(attack, target, feet);
        if (BreathAttacks.handles(attack)) return breathReaches(attack, target, feet);
        if (attack.kind() == DigimonAttack.Kind.HORN_RAM || attack.kind() == DigimonAttack.Kind.FIST) {
            // Any hit volume will do: a long body is struck wherever the fist or fang can reach it.
            for (AABB volume : HitParts.of(target)) {
                if (AttackGeometry.canContact(attack, feet, getBbWidth(), getBbHeight(), volume,
                        this::clearAttackLine, box -> level().noCollision(this, box), this::hasChargeGround)) return true;
            }
            return false;
        }
        float yaw = AttackGeometry.yaw(feet, target.position());
        if (attack.fuel() != null) {
            return AttackGeometry.streamAim(attack, attack.hitTick(), feet, target.getBoundingBox(), yaw, this::clipAttackLine) != null;
        }
        Vec3 point = AttackGeometry.chest(target.getBoundingBox());
        Vec3 mouth, head;
        if (attack.motion() != null) {
            var frame = attack.motion().sample(attack.hitTick());
            float pitch = attack.kind() == DigimonAttack.Kind.FLAME_SHOT
                    ? FlameStream.aimPitch(frame, feet, point, yaw, 0) : 0;
            head = AttackGeometry.world(feet, frame.head(), yaw);
            mouth = AttackGeometry.world(feet, frame.aimedMouth(pitch), yaw);
        } else if (com.digicube.digimon.FireballMuzzles.get(attack).isPresent()) {
            var frame = com.digicube.digimon.FireballMuzzles.get(attack).get().sample(attack.hitTick());
            head = AttackGeometry.world(feet, frame.head(), yaw);
            mouth = AttackGeometry.world(feet, frame.mouth(), yaw);
        } else {
            boolean bubbles = attack.kind() == DigimonAttack.Kind.BUBBLES;
            head = feet.add(0, bubbles ? BUBBLE_MOUTH_HEIGHT : MOUTH_HEIGHT, 0);
            mouth = head.add(Vec3.directionFromRotation(0, yaw).scale(bubbles ? BUBBLE_MOUTH_FORWARD : MOUTH_FORWARD));
        }
        return point.subtract(mouth).dot(Vec3.directionFromRotation(0, yaw)) > .05
                && clearAttackLine(head, mouth) && clearAttackLine(mouth, point)
                && (attack.kind() != DigimonAttack.Kind.FIREBALL || fireballWorthIt(attack, target, feet, yaw));
    }

    /**
     * A pounce reaches from {@code feet} when its prey is within its range and within the pitch a pounce from the ground
     * may take, and nothing stands between the head and the prey.
     */
    private boolean pounceReaches(DigimonAttack attack, LivingEntity target, Vec3 feet) {
        var spec = PounceAttacks.get(attack);
        Vec3 chest = AttackGeometry.chest(target.getBoundingBox());
        Vec3 head = feet.add(0, getBbHeight() * .6, 0);
        Vec3 to = chest.subtract(head);
        double flat = to.horizontalDistance();
        if (flat > attack.range() + target.getBbWidth() * .5 + getBbWidth() * .5) return false;
        float pitch = (float) Math.toDegrees(Math.atan2(to.y, Math.max(1.0E-3, flat)));
        if (flat > 2 && Math.abs(spec.pitch(pitch, false) - pitch) > 10) return false;
        return clearAttackLine(head, chest);
    }

    /** A breath reaches from {@code feet} when its prey is within most of its puffs' flight and in clear view of the mouth. */
    private boolean breathReaches(DigimonAttack attack, LivingEntity target, Vec3 feet) {
        var spec = BreathAttacks.get(attack);
        Vec3 chest = AttackGeometry.chest(target.getBoundingBox());
        var frame = attack.motion().sample(attack.motion().activeFrom());
        float yaw = AttackGeometry.yaw(feet, target.position());
        Vec3 mouth = AttackGeometry.world(feet, frame.mouth(), yaw);
        double reach = Math.min(attack.range(), spec.reach() * .9) + target.getBbWidth() * .5;
        return mouth.distanceToSqr(chest) <= reach * reach && clearAttackLine(AttackGeometry.world(feet, frame.head(), yaw), mouth)
                && clearAttackLine(mouth, chest);
    }

    private Vec3 clipAttackLine(Vec3 from, Vec3 to) {
        HitResult hit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
    }

    private boolean clearAttackLine(Vec3 from, Vec3 to) {
        return clipAttackLine(from, to).distanceToSqr(to) < 1.0E-8;
    }

    private boolean hasChargeGround(Vec3 feet) {
        Vec3 probe = feet.add(0, .15, 0);
        return !clearAttackLine(probe, probe.add(0, -1.25, 0));
    }

    /**
     * Space needed to bring an authored snout or horn to bear on a nearby target.
     * @return minimum usable distance in blocks, or zero for ordinary melee
     */
    public double minimumAttackSpacing() {
        LivingEntity target = getTarget();
        if (approachingConstriction(target) != null) return 0;
        if (target != null && target.hasEffect(DCEffects.FROZEN) && wrapMove() != null) return 0;
        // A fist, a whip or a pounce works up close: a body that has one never backs off to make room for its other moves.
        if (attacks().stream().anyMatch(a -> a.kind() == DigimonAttack.Kind.MELEE || a.kind() == DigimonAttack.Kind.WHIP
                || a.kind() == DigimonAttack.Kind.POUNCE)) return 0.0;
        return attacks().stream().filter(a -> a.motion() != null && a.kind() != DigimonAttack.Kind.RETREAT_KICK)
                .mapToDouble(a -> a.motion().minimumRange()).min().orElse(0.0);
    }

    /** Server only. Begins the attack timeline and tells clients to animate it. */
    public void startAttack(DigimonAttack attack, LivingEntity target) {
        if (level().isClientSide() || hasEffect(DCEffects.FROZEN) || getFlightPhase() != FlightPhase.GROUNDED || isVehicle() || activeAttack != null || target == null
                || !target.isAlive() || !canAttack(target) || !isAttackReady(attack) || !inRange(attack, target)) return;
        List<DigimonAttack> attacks = attacks();
        int index = attacks.indexOf(attack);
        if (index < 0 || index >= DigimonAnimationEvents.MAX_ATTACKS) {
            Constants.LOG.warn("{} cannot use {}: not in its attack list", getSpeciesId(), attack.id());
            return;
        }
        if (hasEffect(DCEffects.CONSTRICTED)) return;
        if (attack.kind() == DigimonAttack.Kind.CONSTRICTION) {
            constriction = ConstrictionSession.prepare(this,target,attack);
            if (constriction == null) { constrictionRetryTick=tickCount+com.digicube.digimon.ConstrictionMotion.APPROACH_RETRY_TICKS;return; }
            entityData.set(DATA_WRAP_RADIUS,constriction.fit().radius());
            entityData.set(DATA_WRAP_PITCH,constriction.fit().pitch());
            syncConstrictionAnchor();
            entityData.set(DATA_WRAP_DISTANCE,(float)constriction.distance());
            entityData.set(DATA_WRAP_YAW,constriction.yaw());
            resetConstrictionApproach();
        }
        // A move with forms casts the one that suits the target from here (AttackForms), on the move's uses.
        DigimonAttack cast = attack;
        if (com.digicube.digimon.AuthoredAttacks.forms(attack) != null && (cast = AttackForms.choose(this, attack, target, position())) == null) return;
        beginAttack(cast, index, target, null);
    }

    /**
     * Server only. The timeline's first tick, for the AI ({@code rider} null, {@code target} set) and for a rider
     * ({@code target} is the soft target or null, and the attack goes where the rider looks).
     */
    private void beginAttack(DigimonAttack attack, int index, LivingEntity target, Player rider) {
        if (rider == null && com.digicube.digimon.WhipAttacks.handles(attack)) { beginAiWhip(attack, target); return; }
        if (com.digicube.digimon.AuthoredAttacks.handles(attack)) authoredVolumes.reset();
        activeAttack = attack;
        riderAttack = rider != null;
        if (com.digicube.digimon.KineticAttacks.handles(attack)) {
            kinetic = new KineticSession(this, target, attack, rider == null ? null : () -> riderAim(rider, attack));
            syncKineticStart();
            // A rider's drawn shot starts uncharged, and holding it raised charges it (tickAttackTimeline); one on the run twists.
            var spec = riderSpec(attack);
            if (kinetic.riderShot() && spec != null) {
                kinetic.riderStyle(spec.input() == com.digicube.digimon.RiderAttack.Input.HOLD, spec.move());
                riderDrawTicks = 0;
            } else if (rider == null && aiShootsMoving(attack)) {
                kinetic.riderStyle(false, true);
                countSkill("shot_on_the_run");
            }
        }
        attackTarget = target;
        attackTick = 0;
        bubbleAimPoint = null;
        authoredAimPoint = null;
        setStrikeAnchor(null);
        hornConnected = chargeBlocked = false;
        leapFrom = leapTo = null;
        pounce = null;
        breath = null;
        if (breathPulses != null) breathPulses.clear();
        this.entityData.set(DATA_ATTACK_AIM_PITCH, 0.0F);
        attackMirrored = attack.alternateSides() && nextAttackMirrored;
        if (attack.alternateSides()) {
            nextAttackMirrored = !nextAttackMirrored;
        }
        // A form spends its move's uses; the move's cooldown clock is the one everything reads.
        var move = com.digicube.digimon.AuthoredAttacks.move(attack);
        var forms = com.digicube.digimon.AuthoredAttacks.forms(move);
        int form = forms == null ? 0 : forms.index(attack);
        if (forms != null) { lastForm = attack; lastFormEnd = tickCount + attack.durationTicks(); }
        if (attack.fuel() != null) { fuelFor(attack).begin(); closeInTargetId = -1; }
        else cooldownUntil.put(move.id(), com.digicube.digimon.AttackCharges.spend(chargeRefills, move, tickCount));
        // A breath from a body that steps round on its paws turns it steadily from where it faces (aimBreath), never at once.
        if (rider == null && target != null && !breathesOnItsLegs(attack)) lookAt(target, 60.0F, 60.0F);
        int pounceForm = 0;
        if (PounceAttacks.handles(attack)) {
            // A pounce from a leap skips the gather: its clip and timeline start at the burst.
            boolean air = !onGround() && !isInWater();
            var spec = PounceAttacks.get(attack);
            Vec3 line = rider != null ? PounceLines.rider(this, spec, rider.getEyePosition(), rider.getLookAngle(), target, air)
                    : PounceLines.ai(this, spec, target, targetMotion());
            pounce = new PounceSession(this, attack, target, line, rider != null, air);
            attackTick = spec.startTick(air);
            pounceForm = air ? 1 : 0;
            this.entityData.set(DATA_ATTACK_YAW, pounce.yaw());
            if (rider == null) { setYRot(pounce.yaw()); yHeadRot = yBodyRot = getYRot(); }
            if (!battleCry()) level().playSound(null, getX(), getY(), getZ(), SoundEvents.WOLF_SHAKE, SoundSource.NEUTRAL, .8F, .6F);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.BREEZE_JUMP, SoundSource.NEUTRAL, .7F, 1.35F);
        }
        if (BreathAttacks.handles(attack)) this.entityData.set(DATA_ATTACK_YAW, getYRot());
        if (pounce != null) {
            // the pounce plays its own clip on the start event (below)
        } else if (kinetic != null) {
            battleCry();
            kinetic.tick((ServerLevel) level(), 0);
            this.entityData.set(DATA_ATTACK_AIM_PITCH, kinetic.pitch());
            if (kinetic.twists()) this.entityData.set(DATA_ATTACK_YAW, kinetic.aimYaw());
        } else if (attack.kind() == DigimonAttack.Kind.BUBBLES) {
            aimBubbleBlow();
        } else if (attack.kind() == DigimonAttack.Kind.FIREBALL) {
            // The turn onto the shot starts from where the body faces, never from the snap of lookAt above.
            fireballAim = null;
            this.entityData.set(DATA_ATTACK_YAW, yBodyRot);
            aimFireball();
        } else if (attack.motion() != null) {
            aimAuthoredAttack();
            var summoned = com.digicube.digimon.AuthoredAttacks.get(attack);
            if (summoned != null && summoned.anchored()) aimStrikeAnchor(summoned);
            // A move with a wind-up sound of its own opens with it (its style keeps quiet then, and nothing growls).
            boolean ownWindUp = summoned != null && summoned.cue((ServerLevel) level(), "wind_up", position());
            if (!ownWindUp && (summoned == null || !summoned.particles().windUp((ServerLevel) level(), position(), summoned.anchored()))) {
                boolean water = attack.fuel() != null || attack.kind() == DigimonAttack.Kind.WATER_WAVE;
                if (water || !battleCry())
                    level().playSound(null, getX(), getY(), getZ(), water ? SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE : SoundEvents.RAVAGER_AMBIENT,
                            SoundSource.NEUTRAL, 0.65F, attack.fuel() != null ? 1.4F : 0.72F);
            }
        }
        if (kinetic != null || attack.fuel() != null || attack.kind() == DigimonAttack.Kind.CONSTRICTION) {
            this.entityData.set(DATA_SUSTAINED_TICK, 0);
            this.entityData.set(DATA_SUSTAINED_ATTACK, attack.id().getPath());
        } else if (pounce != null) level().broadcastEntityEvent(this, DigimonAnimationEvents.start(index, false, pounceForm));
        else level().broadcastEntityEvent(this, DigimonAnimationEvents.start(index, attackMirrored, form));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if(evolutionLocked())return;
        LivingEntity tracked = attackTarget != null ? attackTarget : getTarget();
        if (tracked != null && tracked.isAlive()) targetMotion().sample(tracked, tickCount);
        if (canFly()) {
            if (getFlightPhase() == FlightPhase.GROUNDED && onGround() && !isInWater() && !isInLava()
                    && !(aerialMount()!=null && isVehicle())) flightReserve().rest();
            entityData.set(DATA_FLIGHT_FUEL, flightReserve().fraction());
        }
        attackFuel.values().forEach(FuelReserve::tickRecharge);
        thrower().tick(level);
        tickPounceLeap();
        tickAttackTimeline(level);
    }

    /**
     * One tick of the attack in progress. The AI step drives it; under a rider vanilla skips that step
     * (the rider's client is the authority), so {@link #tick} drives a rider's attack instead.
     */
    private void tickAttackTimeline(ServerLevel level) {
        if (activeAttack == null) {
            return;
        }
        // The AI's whip runs on the arm's own clock (tickWhip, every tick); this only counts.
        if (com.digicube.digimon.WhipAttacks.handles(activeAttack)) {
            if (!isAlive() || isVehicle()) cancelAttack();
            else attackTick++;
            return;
        }
        // A thrown attack runs on the thrower's own timeline (ThrowerState), ticked just before this.
        if (com.digicube.digimon.ThrownAttacks.handles(activeAttack)) {
            if (!isAlive() || isVehicle() && !riderAttack) cancelAttack();
            else attackTick++;
            return;
        }
        if (constriction != null && !constriction.tick(attackTick)) {
            if (COMBAT_TRACE) Constants.LOG.info("[wrap-trace] {} wrap interrupted at tick {}: {}", getSpeciesId(), attackTick, constriction.interruption());
            cancelAttack();return;
        }
        if (constriction != null) syncConstrictionAnchor();
        if (activeAttack == null) return;
        // The AI's jet runs on without a live target (a blinded getaway has none); a charge whose prey is gone just runs out.
        boolean jetting = kinetic == null && this.entityData.get(DATA_RIDER_CHARGE) > 0;
        if (isVehicle() && !riderAttack || !isAlive() || (!riderAttack && !jetting && activeAttack.fuel() == null && attackTick < activeAttack.hitTick()
                && (attackTarget == null || !attackTarget.isAlive() || !canStrike(attackTarget)))) {
            cancelAttack();
            return;
        }
        // The AI breathes at a target; a rider breathes where they look, for as long as they hold the button.
        if (activeAttack.fuel() != null && attackTick <= activeAttack.motion().activeUntil()
                && (riderAttack ? riderReleased && attackTick >= activeAttack.motion().activeFrom() || getControllingPassenger() == null
                : attackTarget == null || !attackTarget.isAlive() || !canAttack(attackTarget)
                || isAllyOf(attackTarget) || !inRange(activeAttack, attackTarget))) {
            finishStream();
        }
        if (kinetic == null && this.entityData.get(DATA_RIDER_CHARGE) > 0) {
            tickJetCharge(level);
            return;
        }
        if (kinetic != null && riderAttack && kinetic.riderShot()) {
            var shot = com.digicube.digimon.KineticAttacks.get(activeAttack);
            var spec = riderSpec(activeAttack);
            if (!riderReleased && spec != null && spec.input() == com.digicube.digimon.RiderAttack.Input.HOLD && attackTick >= shot.riderDrawTick()
                    && getControllingPassenger() instanceof Player) {
                // Drawn: the weapon stays raised on the aim while the button is held, charging toward a full shot.
                kinetic.tick(level, attackTick);
                float charge = Math.min(1, ++riderDrawTicks / (float) FULL_DRAW_TICKS);
                kinetic.charge(charge);
                if (riderDrawTicks == FULL_DRAW_TICKS) level.playSound(null, getX(), getY(), getZ(), SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.NEUTRAL, 1F, .7F);
                this.entityData.set(DATA_RIDER_DRAW, charge);
                this.entityData.set(DATA_ATTACK_AIM_PITCH, kinetic.pitch());
                this.entityData.set(DATA_ATTACK_YAW, kinetic.aimYaw());
                return;
            }
            if (this.entityData.get(DATA_RIDER_DRAW) >= 0) this.entityData.set(DATA_RIDER_DRAW, -1F);
        }
        if (kinetic != null) {
            int next = attackTick + 1;
            if (!kinetic.tick(level, next)) { cancelAttack(); return; }
            if (kinetic.homing(next)) syncKineticStart();
            attackTick = next;
            this.entityData.set(DATA_ATTACK_AIM_PITCH, kinetic.pitch());
            if (riderAttack || kinetic.twists()) this.entityData.set(DATA_ATTACK_YAW, kinetic.aimYaw());
            this.entityData.set(DATA_SUSTAINED_TICK, next);
            this.entityData.set(DATA_SUSTAINED_ATTACK, kinetic.animation());
            if (next == activeAttack.hitTick() && activeAttack.kind() == DigimonAttack.Kind.KINETIC_SHOT) {
                kinetic.fire(level);
                // The reload runs from the shot, not the raise: a drawn shot may be held on the aim as long as the rider likes.
                cooldownUntil.put(activeAttack.id(), tickCount + activeAttack.cooldownTicks());
            }
            if (next >= kinetic.duration()) {
                kinetic = null; activeAttack = null; attackTarget = null; riderAttack = false;
                this.entityData.set(DATA_SUSTAINED_ATTACK, "");
                this.entityData.set(DATA_RIDER_CHARGE, 0);
            }
            return;
        }
        if (pounce != null) {
            getNavigation().stop();
            pounce.tick(level, attackTick);
        } else if (activeAttack.kind() == DigimonAttack.Kind.BUBBLES) {
            aimBubbleBlow();
        } else if (activeAttack.motion() != null) {
            aimAuthoredAttack();
            getNavigation().stop();
            // A swimmer holds its depth through the performance instead of sinking under its own jet; a body that moves
            // under its attack (a rider's stream on the move) keeps going.
            if (!movesDuring(activeAttack)) setDeltaMovement(0.0, isInWater() ? 0.0 : getDeltaMovement().y, 0.0);
            if (AttackTravelSync.drivesRoot(activeAttack)) tickHornDrive(level);
            var jumping = com.digicube.digimon.AuthoredAttacks.get(activeAttack);
            if (jumping != null && jumping.leap() != null) tickLeap(level, jumping);
        } else if (activeAttack.kind() == DigimonAttack.Kind.FIREBALL) {
            aimFireball();
        } else if (constriction == null && attackTarget != null && attackTarget.isAlive()) {
            getLookControl().setLookAt(attackTarget, 30.0F, 30.0F);
        }
        if (activeAttack.kind() == DigimonAttack.Kind.FIREBALL) {
            tickFireballCharge(level);
        }
        var summoned = com.digicube.digimon.AuthoredAttacks.get(activeAttack);
        if (summoned != null && summoned.anchored() && !aimStrikeAnchor(summoned)) { cancelAttack(); return; }
        if (com.digicube.digimon.AuthoredAttacks.handles(activeAttack)) authoredVolumes.tick(level,this,activeAttack,attackTick);
        if (activeAttack.fuel() != null) tickFlameStream(level);
        if (attackTick == activeAttack.hitTick()) {
            deliver(level, activeAttack);
        }
        attackTick++;
        if (constriction != null) this.entityData.set(DATA_SUSTAINED_TICK, attackTick);
        if (activeAttack.fuel() != null) {
            if (attackTick > activeAttack.motion().activeUntil()) fuelFor(activeAttack).end();
            this.entityData.set(DATA_SUSTAINED_TICK, attackTick);
        }
        if (attackTick >= activeAttack.durationTicks()) {
            if (activeAttack.fuel() != null || constriction != null) this.entityData.set(DATA_SUSTAINED_ATTACK, "");
            if (constriction != null) { constriction.release();constriction=null; }
            activeAttack = null;
            riderAttack = false;
            attackTarget = null;
            bubbleAimPoint = null;
            authoredAimPoint = null;
            pounce = null;
            breath = null;
        }
    }

    /** Interrupt combat before rider controls take over; the client also resets its pose. */
    public void interruptAttack() {
        if (!level().isClientSide()) cancelAttack();
    }

    /** Interrupt combat before rider controls take over; the client also resets its pose. */
    private void cancelAttack() {
        if (thrower != null && thrower.busy() && !level().isClientSide()) thrower.cancel();
        if (activeAttack == null) return;
        if (com.digicube.digimon.ThrownAttacks.handles(activeAttack)) { activeAttack = null; attackTarget = null; return; }
        if (activeAttack == whipAttack && whipByAi) {
            // The AI's whip falls back unstruck; its cooldown stands.
            if (whip != null && whip.busy()) { whip.cancel(); syncWhip(); }
            activeAttack = null; attackTarget = null;
            return;
        }
        if (kinetic != null) { kinetic = null; this.entityData.set(DATA_SUSTAINED_ATTACK, ""); }
        this.entityData.set(DATA_RIDER_CHARGE, 0);
        this.entityData.set(DATA_RIDER_DRAW, -1F);
        chargePrey = null;
        if (constriction != null) {
            // A wrap broken before capture is a whiff; it costs a short retry, not the full cooldown.
            if (!constriction.captured()) cooldownUntil.put(activeAttack.id(), tickCount + com.digicube.digimon.ConstrictionMotion.APPROACH_RETRY_TICKS);
            constriction.release();constriction=null;this.entityData.set(DATA_SUSTAINED_ATTACK, "");
        }
        if (activeAttack.fuel() != null) {
            fuelFor(activeAttack).end();
            this.entityData.set(DATA_SUSTAINED_ATTACK, "");
        }
        activeAttack = null;
        riderAttack = false;
        pounce = null;
        breath = null;
        attackTarget = null;
        bubbleAimPoint = authoredAimPoint = null;
        level().broadcastEntityEvent(this, DigimonAnimationEvents.CANCEL);
    }

    private Vec3 authoredPoint(Vec3 local) {
        return position().add(local.yRot(-getYRot() * Mth.DEG_TO_RAD));
    }

    /** Enter the authored exhale without refunding spent fuel or waiting out the full clip. */
    private void finishStream() {
        fuelFor(activeAttack).end();
        attackTick = activeAttack.motion().activeUntil() + 1;
    }

    private void tickFlameStream(ServerLevel level) {
        AttackMotion motion = activeAttack.motion();
        if (attackTick < motion.activeFrom() || attackTick > motion.activeUntil()) return;
        if (BreathAttacks.handles(activeAttack)) {
            tickBreath(level);
            return;
        }
        boolean frost = activeAttack.kind() == DigimonAttack.Kind.FROST_STREAM;
        if (frost && attackTarget != null && attackTarget.hasEffect(DCEffects.FROZEN)) {
            finishStream();
            return;
        }
        // Prey that is already Cold only gets the spare fuel; the next charge keeps its reserve.
        if (frost && chillLoop() && attackTarget != null && coldOrImmune(attackTarget)
                && fuelFor(activeAttack).availableTicks() <= IceCombo.chillFuelTicks(activeAttack.fuel())) {
            finishStream();
            return;
        }
        if (!fuelFor(activeAttack).consume()) {
            finishStream();
            return;
        }
        FlameStream stream = flameStream(activeAttack, attackTick,
                this.entityData.get(DATA_ATTACK_AIM_PITCH), streamYaw(activeAttack));
        if (stream.length() < 0.05) {
            finishStream();
            return;
        }
        int elapsed = attackTick - motion.activeFrom();
        boolean pulse = elapsed % activeAttack.fuel().damageIntervalTicks() == 0;
        boolean frozeTarget = false;
        if (pulse || frost) {
            var struck = new java.util.HashSet<LivingEntity>();
            for (Entity entity : level.getEntities(this, stream.bounds(),
                    e -> DigimonPart.livingOf(e) instanceof LivingEntity living && living.isAlive() && canAttack(living) && !isAllyOf(living))) {
                // A body part is struck where it is, but the damage belongs to its owner, once per tick.
                LivingEntity victim = DigimonPart.livingOf(entity);
                AABB struckBox = entity.getBoundingBox();
                if (struck.contains(victim) || !stream.intersects(struckBox)) continue;
                // Test the actual victim too: a corner of its broadphase box may be behind cover.
                Vec3 contact = struckBox.clip(stream.origin(), stream.end())
                        .orElseGet(struckBox::getCenter);
                if (level.clip(new ClipContext(stream.origin(), contact, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) continue;
                struck.add(victim);
                var source = DCDamageTypes.partnerAttack(this);
                if (victim.isInvulnerableTo(level, source)) continue;
                if (pulse && victim.hurtServer(level, source, damageAgainst(activeAttack, victim))) {
                    setLastHurtMob(victim);
                }
                if (!frost && victim.isAlive()) ((CombatMarkState) victim).digicube$thaw();
                if (frost && victim.isAlive() && chilling()) chill(level, victim);
            }
            if (pulse) level.playSound(null, stream.origin().x, stream.origin().y, stream.origin().z,
                    SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.NEUTRAL, frost ? 1.0F : .65F, frost ? .55F : .8F);
        }
        if (elapsed % 3 == 0) {
            Vec3 end = stream.end();
            level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, end.x, end.y, end.z, frost ? 8 : 3,
                    frost ? .45 : .18, frost ? .45 : .18, frost ? .45 : .18, frost ? .035 : .015);
        }
        if (frost && elapsed % 2 == 0) frostSurface(level, stream);
        if (frozeTarget) finishStream();
    }

    /**
     * Server: one tick of a breath of puffs ({@link BreathAttacks}): shed from the mouth along the aim with the body's
     * own motion, flown, and whatever they touch paid into Freeze each tick and struck every damage pulse; still water
     * they cross freezes and fire goes out. The AI stops breathing on prey that has frozen (a pounce shatters it). Its
     * sounds are each client's own, from the breath it flies (the breath's sheet names them).
     */
    private void tickBreath(ServerLevel level) {
        var spec = BreathAttacks.get(activeAttack);
        if (!riderAttack && attackTarget != null && FreezeMark.frozen(attackTarget)) { finishStream(); return; }
        if (!fuelFor(activeAttack).consume()) { finishStream(); return; }
        if (breath == null) breath = new FrostBreath(spec);
        float aimYaw = this.entityData.get(DATA_ATTACK_YAW), aimPitch = this.entityData.get(DATA_ATTACK_AIM_PITCH);
        Vec3 mouth = breathMouth(activeAttack, attackTick, getYRot(), aimYaw, aimPitch);
        breath.emit(mouth, Vec3.directionFromRotation(aimPitch, aimYaw), new Vec3(getX() - xo, getY() - yo, getZ() - zo), random);
        breath.step(level);
        AABB reach = breath.bounds();
        if (reach != null) {
            var struck = new java.util.HashSet<LivingEntity>();
            for (Entity entity : level.getEntities(this, reach,
                    e -> DigimonPart.livingOf(e) instanceof LivingEntity living && living.isAlive() && canAttack(living) && !isAllyOf(living))) {
                LivingEntity victim = DigimonPart.livingOf(entity);
                if (struck.contains(victim) || !breath.touches(entity.getBoundingBox())) continue;
                struck.add(victim);
                var source = DCDamageTypes.partnerAttack(this);
                if (victim.isInvulnerableTo(level, source)) continue;
                FreezeMark.freeze(level, victim, spec.freeze(), this);
                // Each victim takes a pulse as it enters the frost and every damage interval it stays in it.
                int last = breathPulses().getOrDefault(victim.getId(), Integer.MIN_VALUE / 2);
                if (tickCount - last >= activeAttack.fuel().damageIntervalTicks()) {
                    breathPulses().put(victim.getId(), tickCount);
                    if (victim.hurtServer(level, source, damageAgainst(activeAttack, victim))) setLastHurtMob(victim);
                }
            }
        }
        frostTheWorld(level, spec);
    }

    /** Server: what the breath's puffs do to the world where they strike: still water freezes over, fire goes out. */
    /** Blocks between the points a frost stream is tested for the sea along, none frozen nearer its mouth, and the most frozen a time. */
    private static final double FROST_STEP = .6, FROST_CLEAR = 2;
    private static final int FROST_MOST = 4;

    /**
     * Server: a frost stream (Seadramon's Ice Blast) freezes the still water at the surface where it ends (on its prey, or
     * as far as it reaches skimming the sea) into frosted ice, as the frost walker's does, which melts back: a floe round
     * the spot that grows as the stream plays on it, and a trail of them as it sweeps; prey swimming there is ringed in
     * ice. Coming down from above (from a shore, a breach) it freezes where it first meets the sea instead. Never within
     * FROST_CLEAR of the mouth (a block closing over it would stop the stream), nor under the water, nor where a body is.
     */
    private void frostSurface(ServerLevel level, FlameStream stream) {
        if (!level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING)) return;
        Vec3 from = stream.origin(), to = stream.end();
        BlockPos spot = surfaceAt(level, to);
        int steps = (int) Math.ceil(from.distanceTo(to) / FROST_STEP);
        for (int i = 1; i <= steps && spot == null; i++) {
            Vec3 at = from.lerp(to, i / (double) steps), before = from.lerp(to, (i - 1) / (double) steps);
            // where it passes from the air into the sea
            if (!level.getFluidState(BlockPos.containing(before)).isEmpty() || level.getFluidState(BlockPos.containing(at)).isEmpty()) continue;
            spot = surfaceAt(level, at);
        }
        if (spot == null || Vec3.atCenterOf(spot).distanceTo(from) < FROST_CLEAR) return;
        var ice = net.minecraft.world.level.block.Blocks.FROSTED_ICE.defaultBlockState();
        // the floe: the spot first, then the water round it in a random order
        var floe = new java.util.ArrayList<BlockPos>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) floe.add(spot.offset(dx, 0, dz));
        java.util.Collections.shuffle(floe, new java.util.Random(random.nextLong()));
        floe.addFirst(spot);
        int changed = 0;
        for (BlockPos pos : floe) {
            if (changed >= FROST_MOST) break;
            if (!surfaceWater(level, pos) || Vec3.atCenterOf(pos).distanceTo(from) < FROST_CLEAR
                    || !level.isUnobstructed(ice, pos, net.minecraft.world.phys.shapes.CollisionContext.empty())
                    || getBoundingBox().intersects(new AABB(pos)) || java.util.Arrays.stream(parts()).anyMatch(part -> part.getBoundingBox().intersects(new AABB(pos)))) continue;
            level.setBlockAndUpdate(pos, ice);
            level.scheduleTick(pos, net.minecraft.world.level.block.Blocks.FROSTED_ICE, Mth.nextInt(random, 60, 120));
            changed++;
        }
    }

    /**
     * The top block of the sea at a point within a block of it (still water under air, or a floe already frozen there),
     * or null when the point is not near the surface.
     */
    private static BlockPos surfaceAt(ServerLevel level, Vec3 point) {
        for (double drop : new double[]{0, .6, -1}) {
            BlockPos pos = BlockPos.containing(point.x, point.y - drop, point.z);
            if (surfaceWater(level, pos) || level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FROSTED_ICE)) return pos;
        }
        return null;
    }

    /** Still water at the top of the sea: a source with air over it. */
    private static boolean surfaceWater(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.is(net.minecraft.world.level.block.Blocks.WATER) && state.getFluidState().isSource() && level.getBlockState(pos.above()).isAir();
    }

    private void frostTheWorld(ServerLevel level, BreathAttacks.Spec spec) {
        if (!spec.waterIce() && !spec.douse() || !level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING)) return;
        int changed = 0;
        for (BlockPos pos : breath.touchedBlocks()) {
            if (changed >= 6) break;
            var state = level.getBlockState(pos);
            if (spec.douse() && state.is(net.minecraft.world.level.block.Blocks.FIRE)) {
                level.removeBlock(pos, false);
                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, .6F, 1.4F);
                changed++;
            } else if (spec.waterIce() && state.is(net.minecraft.world.level.block.Blocks.WATER) && state.getFluidState().isSource()
                    && level.getBlockState(pos.above()).isAir()) {
                level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.FROSTED_ICE.defaultBlockState());
                level.scheduleTick(pos, net.minecraft.world.level.block.Blocks.FROSTED_ICE, Mth.nextInt(random, 60, 120));
                changed++;
            }
        }
    }

    /**
     * Both sides: where a breath's mouth is at clip tick {@code tick}: the clip's mouth pitched about the neck by the aim
     * (as the model pitches its aim part) and turned about the neck toward the aim's yaw up to the breath's twist (a
     * rider's neck turns to the crosshair while the body runs on), then carried by the body's heading.
     */
    public Vec3 breathMouth(DigimonAttack attack, float tick, float bodyYaw, float aimYaw, float aimPitch) {
        var spec = BreathAttacks.get(attack);
        AttackMotion.Frame frame = attack.motion().sample(tick);
        float twist = Mth.clamp(Mth.wrapDegrees(aimYaw - bodyYaw), -spec.twist(), spec.twist());
        Vec3 neck = frame.head();
        Vec3 local = neck.add(frame.aimedMouth(aimPitch).subtract(neck).yRot(-twist * Mth.DEG_TO_RAD));
        return position().add(local.yRot(-bodyYaw * Mth.DEG_TO_RAD));
    }

    /**
     * Server: the breath's aim. A rider's follows the point under the crosshair; the AI's the point where its prey will
     * be when the frost gets there. The aim turns at the breath's own rates (fast: the puffs, not the head, carry the
     * lag), and the AI's body turns with it.
     */
    private void aimBreath() {
        var spec = BreathAttacks.get(activeAttack);
        LivingEntity aimed = attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
        float yawNow = this.entityData.get(DATA_ATTACK_YAW), pitchNow = this.entityData.get(DATA_ATTACK_AIM_PITCH);
        Vec3 mouth = breathMouth(activeAttack, attackTick, getYRot(), yawNow, pitchNow);
        Vec3 point = aimed == null ? riderAimNow() : breathLead(aimed, spec, mouth);
        if (point != null) {
            Vec3 to = point.subtract(mouth);
            if (to.lengthSqr() > 1.0E-6) {
                float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                float down = (float) -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance()));
                boolean drawing = attackTick < activeAttack.motion().activeFrom();
                this.entityData.set(DATA_ATTACK_YAW, Mth.approachDegrees(yawNow, yaw, drawing ? 40 : spec.turn()));
                this.entityData.set(DATA_ATTACK_AIM_PITCH, Mth.approach(pitchNow, Mth.clamp(down, -55, 70), drawing ? 30 : spec.pitchTurn()));
            }
        }
        if (!riderAttack && breathesOnItsLegs(activeAttack)) {
            // A body that steps round on its paws comes round after the aim no faster than it plants them (hurried, eased),
            // its neck turning the rest of the way: the aim never leaves the breath's twist of the body.
            float aim = this.entityData.get(DATA_ATTACK_YAW);
            setYRot(getYRot() + com.digicube.entity.ai.SteadyBodyControl.ease(bodyTurn, Mth.wrapDegrees(aim - getYRot()),
                    steadyTurnRate() * com.digicube.entity.ai.SteadyBodyControl.BRISK));
            yBodyRot = getYRot();
            aim = getYRot() + Mth.clamp(Mth.wrapDegrees(aim - getYRot()), -spec.twist(), spec.twist());
            this.entityData.set(DATA_ATTACK_YAW, aim);
            yHeadRot = aim;
        } else if (!riderAttack) {
            // Unridden, the whole body comes round with the aim (the neck only twists under a running rider).
            setYRot(Mth.approachDegrees(getYRot(), this.entityData.get(DATA_ATTACK_YAW), 30));
            yHeadRot = yBodyRot = getYRot();
        }
    }

    /**
     * Both sides: the AI's breath from a body that steps round on its paws keeps its legs: they play the gait (the pivot as
     * the body comes round after the aim) while the neck breathes and turns to the aim, as a rider's breath on the run does.
     */
    public boolean breathesOnItsLegs(DigimonAttack attack) {
        return attack != null && rider() == null && stepsRound() && BreathAttacks.handles(attack);
    }

    /** Where the breath should meet a moving prey: its chest, led by how far it goes while the frost flies there. */
    private Vec3 breathLead(LivingEntity target, BreathAttacks.Spec spec, Vec3 mouth) {
        Vec3 chest = AttackGeometry.chest(target.getBoundingBox());
        double ticks = Math.min(spec.life() * .6, mouth.distanceTo(chest) / Math.max(.3, spec.speed() * .7));
        if (!targetMotion().follows(target)) return chest;
        Vec3 ahead = targetMotion().predict(target, ticks);
        return new Vec3(ahead.x, chest.y + (ahead.y - target.getBoundingBox().getCenter().y), ahead.z);
    }

    /** Package: a pounce's committed facing, for every client (the rider's owns it and draws it the same). */
    void syncAttackYaw(float yaw) { this.entityData.set(DATA_ATTACK_YAW, yaw); }

    /** Package: a pounce's bite on {@code victim}, critical roll included. */
    float pounceDamage(DigimonAttack attack, LivingEntity victim) { return damageAgainst(attack, victim); }

    /**
     * Same authored mouth, aim and clipped volume on server and renderer; no visual projectile.
     * @param attack sustained move definition
     * @param tick elapsed animation ticks
     * @param pitch additional head aim in degrees
     * @param yaw body facing in degrees
     * @return terrain-clipped stream in world space
     */
    public FlameStream flameStream(DigimonAttack attack, float tick, float pitch, float yaw) {
        AttackMotion.Frame frame = attack.motion().sample(tick);
        // A serpent swims with its head lower than the land pose the motion was measured in.
        var serpent = serpent();
        Vec3 feet = serpent != null && isSwimmingMovement() ? position().subtract(0, serpent.swimHeadDrop(), 0) : position();
        Vec3 mouth = feet.add(frame.aimedMouth(pitch).yRot(-yaw * Mth.DEG_TO_RAD));
        Vec3 head = feet.add(frame.head().yRot(-yaw * Mth.DEG_TO_RAD));
        Vec3 direction = FlameStream.direction(frame, pitch, yaw);
        double reach = Math.min(attack.range(), FlameStream.flowDistance(tick - attack.hitTick() + 1));
        return FlameStream.trace(this, head, mouth, direction, reach, attack.motion().contactRadius());
    }

    /** Both sides: where the running summoned strike lands, or null when there is none. */
    public Vec3 strikeAnchor() {
        BlockPos origin = entityData.get(DATA_STRIKE_ORIGIN);
        if (origin.getY() == NO_STRIKE.getY()) return null;
        var f = entityData.get(DATA_STRIKE_FRACTION);
        return new Vec3(origin.getX() + f.x(), origin.getY() + f.y(), origin.getZ() + f.z());
    }

    private void setStrikeAnchor(Vec3 point) {
        if (point == null) { entityData.set(DATA_STRIKE_ORIGIN, NO_STRIKE); return; }
        BlockPos origin = BlockPos.containing(point);
        entityData.set(DATA_STRIKE_ORIGIN, origin);
        entityData.set(DATA_STRIKE_FRACTION, new org.joml.Vector3f((float) (point.x - origin.getX()),
                (float) (point.y - origin.getY()), (float) (point.z - origin.getZ())));
    }

    /** How far ahead of a moving target a summoned strike may be placed, in blocks. */
    private static final double STRIKE_MAX_LEAD = 1.5;

    /**
     * A summoned strike follows the floor under its target, led by the target's pace, until its landing point locks.
     * After that the point stays: whoever walks out from under the warning is missed.
     * @return false when there is nowhere for it to land
     */
    private boolean aimStrikeAnchor(com.digicube.digimon.AuthoredAttacks.Definition definition) {
        if (attackTick > definition.anchorLockTick()) return strikeAnchor() != null;
        LivingEntity aimed = attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
        if (aimed != null) {
            Vec3 lead = aimed.getDeltaMovement().multiply(1, 0, 1).scale(Math.max(0, activeAttack.hitTick() - attackTick));
            if (lead.length() > STRIKE_MAX_LEAD) lead = lead.normalize().scale(STRIKE_MAX_LEAD);
            Vec3 landing = AuthoredVolumeAttack.landing(level(), this, aimed.position().add(lead));
            if (landing == null) landing = AuthoredVolumeAttack.landing(level(), this, aimed.position());
            if (landing != null) setStrikeAnchor(landing);
        }
        return strikeAnchor() != null;
    }

    /** Degrees a tick a rider's breath sweeps after the crosshair while it burns, across and up or down. */
    private static final float RIDER_BREATH_TURN = 4.5F, RIDER_BREATH_PITCH = 3;
    /**
     * Degrees a rider's stream breathed on the move (Seadramon's Ice Blast, swimming) turns the head off the body toward
     * the crosshair, and how fast it follows it a tick.
     */
    public static final float STREAM_TWIST = 70, STREAM_TURN = 12;

    /**
     * Both sides: the yaw a stream leaves along: toward the crosshair within {@link #STREAM_TWIST} of the body for one
     * breathed on the move (its head turns there while the rider steers the body), else the body's own heading.
     */
    public float streamYaw(DigimonAttack attack) {
        return riderMovesDuring(attack) && attack.fuel() != null ? this.entityData.get(DATA_ATTACK_YAW) : getYRot();
    }

    /** Face the aim during anticipation, then commit to that direction through the strike. */
    private void aimAuthoredAttack() {
        if (BreathAttacks.handles(activeAttack)) { aimBreath(); return; }
        boolean streaming = activeAttack.fuel() != null;
        // A rider's client owns the facing, so every rider attack commits its yaw through the synced value.
        boolean committed = riderAttack || activeAttack.kind() == DigimonAttack.Kind.GROUND_WAVE || activeAttack.kind() == DigimonAttack.Kind.FIST || com.digicube.digimon.AuthoredAttacks.handles(activeAttack);
        int aimUntil = activeAttack.hitTick() - (activeAttack.kind() == DigimonAttack.Kind.GROUND_WAVE ? 4 : committed ? 2 : 0);
        var travelling = com.digicube.digimon.AuthoredAttacks.get(activeAttack);
        // A jump is planned at launch, so the facing is settled there; a travelling combo keeps turning after its victim between blows.
        if (travelling != null && travelling.leap() != null) aimUntil = travelling.leap().launch();
        else if (travelling != null && travelling.rootTravel() && !travelling.hitWindows().isEmpty()) aimUntil = (int) travelling.hitWindows().getLast()[0] - 2;
        // A rider's breath follows the crosshair (or the soft target) for as long as it burns, slower than the wind-up
        // turned: a burst that strikes several times (Fire Blast) is swept across the enemies in front of the mount.
        boolean sweeping = riderAttack && activeAttack.kind() == DigimonAttack.Kind.BOX_BURST && travelling != null
                && travelling.maxHits() > 1 && travelling.leap() == null && !travelling.anchored();
        if (sweeping) aimUntil = activeAttack.motion().activeUntil();
        boolean burning = sweeping && attackTick >= activeAttack.hitTick();
        // While it burns the view steers it: the soft target is picked again from where the rider looks, or none.
        if (burning && getControllingPassenger() instanceof Player breather) attackTarget = softTarget(breather, activeAttack);
        LivingEntity aimed = attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
        // Without a soft target a rider's shot, stream or burst goes to the point under the crosshair.
        Vec3 viewPoint = aimed == null ? riderAimNow() : null;
        // A stream on the move: the rider steers the body, and the head turns to the aim within its twist.
        boolean onTheMove = streaming && riderMoves(activeAttack) && riderAttack;
        if ((attackTick <= aimUntil || streaming && attackTick <= activeAttack.motion().activeUntil())
                && (aimed != null || viewPoint != null)) {
            AttackMotion.Frame release = activeAttack.motion().sample(streaming ? attackTick : activeAttack.hitTick());
            Vec3 origin = authoredPoint(release.mouth());
            authoredAimPoint = viewPoint != null ? viewPoint : activeAttack.kind() == DigimonAttack.Kind.FLAME_SHOT
                    ? PepperBreathEntity.predictImpactPoint(attackTarget, origin, MegaFlameEntity.SPEED, MegaFlameEntity.MAX_AIM_LEAD)
                    : activeAttack.kind() == DigimonAttack.Kind.WATER_WAVE
                    ? MarchingFishesEntity.aimPoint(attackTarget, origin)
                    : activeAttack.kind() == DigimonAttack.Kind.GROUND_WAVE
                    ? TectonicWave.aimPoint(position(), attackTarget, activeAttack.hitTick()-attackTick)
                    : nearestVolume(attackTarget).getCenter();
            var authoredDefinition=com.digicube.digimon.AuthoredAttacks.get(activeAttack);
            if(aimed!=null && authoredDefinition!=null && !authoredDefinition.hitWindows().isEmpty()) {
                double contact=authoredDefinition.hitWindows().getLast()[0];
                Vec3 lead=attackTarget.getDeltaMovement().multiply(1,0,1).scale(Math.max(0,contact-attackTick));
                if(lead.length()>.6)lead=lead.normalize().scale(.6);
                authoredAimPoint=authoredAimPoint.add(lead);
            }
            Vec3 direction = authoredAimPoint.subtract(position());
            if (direction.horizontalDistanceSqr() > 1.0E-8) {
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                if (activeAttack.kind() == DigimonAttack.Kind.GROUND_WAVE) yaw = TectonicWave.yaw(position(), authoredAimPoint, activeAttack.motion());
                if (activeAttack.kind() == DigimonAttack.Kind.FIST) yaw = AttackGeometry.contactYaw(activeAttack, position(), authoredAimPoint);
                if (com.digicube.digimon.AuthoredAttacks.handles(activeAttack)) yaw = AuthoredVolumeAttack.yaw(activeAttack,position(),authoredAimPoint,attackMirrored);
                if (onTheMove) {
                    float aim = Mth.approachDegrees(entityData.get(DATA_ATTACK_YAW), yaw, STREAM_TURN);
                    entityData.set(DATA_ATTACK_YAW, getYRot() + Mth.clamp(Mth.wrapDegrees(aim - getYRot()), -STREAM_TWIST, STREAM_TWIST));
                } else {
                    setYRot(streaming ? Mth.approachDegrees(getYRot(), yaw,
                            attackTick < activeAttack.motion().activeFrom() ? 18.0F : 8.0F)
                            : committed && attackTick>0 ? Mth.approachDegrees(entityData.get(DATA_ATTACK_YAW),yaw,burning ? RIDER_BREATH_TURN : 10) : yaw);
                    if(committed) entityData.set(DATA_ATTACK_YAW,getYRot());
                }
            }
            if (streaming) {
                float previous = this.entityData.get(DATA_ATTACK_AIM_PITCH);
                // Solve anticipation against the emission pose, before aimWeight has fully blended in.
                double aimTick = Math.max(attackTick, activeAttack.hitTick());
                if (aimed != null) {
                    var aim = AttackGeometry.streamAim(activeAttack, aimTick, position(), attackTarget.getBoundingBox(),
                            getYRot(), this::clipAttackLine);
                    if (aim != null) authoredAimPoint = aim.target();
                    else authoredAimPoint = AttackGeometry.chest(attackTarget.getBoundingBox());
                }
                float desired = FlameStream.aimPitch(activeAttack.motion().sample(aimTick), position(), authoredAimPoint, streamYaw(activeAttack), previous);
                this.entityData.set(DATA_ATTACK_AIM_PITCH, Mth.approach(previous, desired, 6));
            } else if (activeAttack.kind() == DigimonAttack.Kind.BOX_BURST) {
                float desired=AuthoredVolumeAttack.pitch(activeAttack,position(),authoredAimPoint,getYRot());
                // A clip whose aim starts at zero supplies its own smooth anticipation.
                // Seed its destination now; a short windup cannot otherwise reach a low target.
                this.entityData.set(DATA_ATTACK_AIM_PITCH,attackTick==0 && activeAttack.motion().sample(0).aimWeight()==0
                        ? desired : Mth.approach(this.entityData.get(DATA_ATTACK_AIM_PITCH),desired,burning ? RIDER_BREATH_PITCH : 4));
            } else if (activeAttack.kind() == DigimonAttack.Kind.FLAME_SHOT) {
                float pitch = FlameStream.aimPitch(release, position(), authoredAimPoint, getYRot(),
                        this.entityData.get(DATA_ATTACK_AIM_PITCH));
                this.entityData.set(DATA_ATTACK_AIM_PITCH, pitch);
            }
        }
        if (onTheMove) { yHeadRot = entityData.get(DATA_ATTACK_YAW); return; }
        if(committed)setYRot(entityData.get(DATA_ATTACK_YAW));
        yHeadRot = yBodyRot = getYRot();
    }

    /** The flight of a jumping strike, planned at launch and flown as a closed loop so blocks still stop it. */
    private Vec3 leapFrom, leapTo;
    private void tickLeap(ServerLevel level, com.digicube.digimon.AuthoredAttacks.Definition authored) {
        var leap = authored.leap();
        if (attackTick == leap.launch()) {
            LivingEntity aimed = attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
            // Where the victim will stand when the jumper comes down: read from how it has been moving (players too).
            int flight = leap.land() - leap.launch();
            Vec3 target = aimed == null ? authoredAimPoint : targetMotion().follows(aimed)
                    ? targetMotion().predict(aimed, flight).subtract(0, aimed.getBbHeight() / 2, 0)
                    : aimed.position().add(aimed.getDeltaMovement().multiply(1, 0, 1).scale(flight));
            if (target == null) { cancelAttack(); return; }
            Vec3 flat = target.subtract(position()).multiply(1, 0, 1);
            double lead = leap.lead(aimed);
            if (aimed != null && flat.horizontalDistanceSqr() > 1.0E-4) {
                // The facing is settled here for the whole flight: onto where the victim will be, not where it was.
                float yaw = AuthoredVolumeAttack.yaw(activeAttack, position(), target.add(0, aimed.getBbHeight() / 2, 0), attackMirrored);
                setYRot(yaw); yHeadRot = yBodyRot = yaw;
                this.entityData.set(DATA_ATTACK_YAW, yaw);
            }
            // A victim that has closed in during the wind-up is still struck from a blade's length away: a short hop back.
            Vec3 forward = new Vec3(0, 0, 1).yRot(-getYRot() * Mth.DEG_TO_RAD);
            Vec3 spot = flat.length() < lead ? target.subtract(forward.scale(lead)) : position().add(flat.normalize().scale(flat.length() - lead));
            Vec3 floor = AuthoredVolumeAttack.landing(level, this, new Vec3(spot.x, target.y, spot.z));
            leapFrom = position(); leapTo = floor != null ? floor : new Vec3(spot.x, getY(), spot.z);
            resetFallDistance();
            authored.particles().release(level, position());
        }
        if (leapFrom == null || !leap.airborne(attackTick)) return;
        double u = (attackTick + 1 - leap.launch()) / (double) (leap.land() - leap.launch());
        Vec3 planned = AuthoredVolumeAttack.arc(leapFrom, leapTo, leap.apex(), u);
        move(MoverType.SELF, planned.subtract(position()));
        if (COMBAT_TRACE) Constants.LOG.info("[leap-trace] {} tick {} at {} planned {} landing {} onGround={}", getSpeciesId(), attackTick + 1, fmt(position()), fmt(planned), fmt(leapTo), onGround());
        // travel() takes this tick's gravity off the velocity next; leave it exactly that much so the body neither sags nor drifts.
        setDeltaMovement(0, getGravity(), 0);
        needsSync = true; resetFallDistance();
        if (attackTick + 1 == leap.land()) {
            setDeltaMovement(Vec3.ZERO);
            Vec3 ahead = position().add(new Vec3(0, 0, leap.lead()).yRot(-getYRot() * Mth.DEG_TO_RAD));
            authored.particles().landing(level, ahead, 2.0);
        }
    }

    /** Move only by the exported root displacement; vanilla collision resolves walls. */
    private void tickHornDrive(ServerLevel level) {
        AttackMotion motion = activeAttack.motion();
        Vec3 before = position();
        // Under a rider the position belongs to the rider's client: the strike lands from where the mount stands.
        double travel = riderAttack ? 0 : motion.sample(attackTick + 1).travel() - motion.sample(attackTick).travel();
        var travelling = com.digicube.digimon.AuthoredAttacks.get(activeAttack);
        // A dashing blow (an impact pose) stops at its victim's body, and once it has struck, however hard it knocks.
        boolean dash = travelling != null && travelling.hasImpact();
        if (activeAttack.knockback() == 0 || dash) {
            if (hornConnected || dash && authoredVolumes.struck()) travel = 0;
            else if (dash && attackTarget != null) {
                // the dash runs up to the victim's box, corner and all, and no further (its struck volume reaches past
                // the body's own box, so the blow lands there)
                travel = Math.min(travel, AttackGeometry.boxClearance(getBoundingBox(), attackTarget.getBoundingBox(),
                        new Vec3(0, 0, 1).yRot(-getYRot() * Mth.DEG_TO_RAD)));
            } else if (attackTarget != null) {
                // A no-knockback thrust must not push the victim through ordinary body collision either.
                travel = Math.min(travel, AttackGeometry.thrustClearance(before, attackTarget.getBoundingBox(), getBbWidth()));
            }
        }
        if (!chargeBlocked && travel > 0.0) {
            Vec3 step = new Vec3(0, 0, travel).yRot(-getYRot() * Mth.DEG_TO_RAD);
            Vec3 groundProbe = before.add(step).add(0, 0.15, 0);
            boolean groundAhead = level.clip(new ClipContext(groundProbe, groundProbe.add(0, -1.25, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS;
            // A swimmer's dash carries it through the water; on land the ground must go on under it.
            if (dash && isInWater() && canSwim() || onGround() && groundAhead) {
                move(MoverType.SELF, step);
                chargeBlocked = horizontalCollision;
            } else {
                chargeBlocked = true;
            }
        }
        // An authored sweep strikes with its own volumes and sounds with its own style (AuthoredVolumeAttack): the
        // drive only carries the body.
        if (com.digicube.digimon.AuthoredAttacks.handles(activeAttack)) return;
        if (attackTick == motion.activeFrom()) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.RAVAGER_ATTACK,
                    SoundSource.NEUTRAL, 1.0F, 0.8F);
        }
        if (hornConnected || chargeBlocked || attackTick < motion.activeFrom() || attackTick > motion.activeUntil()) return;
        if (COMBAT_TRACE && attackTarget != null) {
            // Development trace: where the fist is relative to the intended victim on every active tick.
            AttackMotion.Frame frame = motion.sample(attackTick);
            Vec3 base = authoredPoint(frame.hornBase()), tip = authoredPoint(frame.hornTip());
            AABB padded = attackTarget.getBoundingBox().inflate(motion.contactRadius());
            Constants.LOG.info("[swing-trace] {} {}@{} base={} tip={} victimBox={} reaches={} yaw={} targetYaw={}",
                    getSpeciesId(), activeAttack.id().getPath(), attackTick, fmt(base), fmt(tip),
                    fmt(padded), padded.contains(base) || padded.clip(base, tip).isPresent(),
                    String.format("%.0f", getYRot()), String.format("%.0f", AttackGeometry.contactYaw(activeAttack, position(), attackTarget.getBoundingBox().getCenter())));
        }
        // Subdivide the moving horn, not an arbitrary melee radius around the feet.
        for (int i = 0; i <= 4 && !hornConnected; i++) {
            double partial = i / 4.0;
            AttackMotion.Frame frame = motion.sample(attackTick + partial);
            Vec3 at = before.lerp(position(), partial);
            Vec3 base = at.add(frame.hornBase().yRot(-getYRot() * Mth.DEG_TO_RAD));
            Vec3 tip = at.add(frame.hornTip().yRot(-getYRot() * Mth.DEG_TO_RAD));
            AABB region = new AABB(base, tip).inflate(motion.contactRadius());
            for (Entity entity : level.getEntities(this, region,
                    e -> DigimonPart.livingOf(e) instanceof LivingEntity living && living.isAlive() && canAttack(living) && !isAllyOf(living))) {
                if (hornConnected) break; // one strike per swing, even when a long body offers several volumes
                var contact = entity.getBoundingBox().inflate(motion.contactRadius()).clip(base, tip);
                if (!entity.getBoundingBox().inflate(motion.contactRadius()).contains(base) && contact.isEmpty()) continue;
                Vec3 end = contact.orElse(base);
                if (level.clip(new ClipContext(authoredPoint(frame.head()), end,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) continue;
                // A struck body part credits its owner.
                LivingEntity victim = DigimonPart.livingOf(entity);
                float damage = damageAgainst(activeAttack, victim);
                var source = activeAttack.knockback() == 0 ? DCDamageTypes.partnerAttack(this) : damageSources().mobAttack(this);
                boolean hurt = victim.hurtServer(level, source, damage);
                if (COMBAT_TRACE) Constants.LOG.info("[swing-trace] {} contact on {} ({}) at {}: damage={} hurt={} victimHealth={}",
                        activeAttack.id().getPath(), victim.getType().toShortString(),
                        entity instanceof DigimonPart part ? "part " + part.index : "body", fmt(end), String.format("%.1f", damage), hurt,
                        String.format("%.1f", victim.getHealth()));
                if (hurt) {
                    if (riderAttack) {
                        level.broadcastEntityEvent(this, DigimonAnimationEvents.IMPACT);
                        level.playSound(null, end.x, end.y, end.z, SoundEvents.MACE_SMASH_GROUND, SoundSource.NEUTRAL, .7F, .7F);
                    }
                    if (activeAttack.knockback() > 0) {
                        victim.knockback(activeAttack.knockback(), getX() - victim.getX(), getZ() - victim.getZ(), source, damage);
                    }
                    com.digicube.digimon.CrackMark.strike(activeAttack, victim);
                    setLastHurtMob(victim);
                    level.playSound(null, end.x, end.y, end.z,
                            activeAttack.knockback() > 0 ? SoundEvents.PLAYER_ATTACK_KNOCKBACK : SoundEvents.PLAYER_ATTACK_STRONG,
                            SoundSource.NEUTRAL, activeAttack.knockback() > 0 ? 1.0F : 0.8F,
                            activeAttack.knockback() > 0 ? 0.65F : 1.1F);
                    level.sendParticles(ParticleTypes.CRIT, true, true, end.x, end.y, end.z, 12, .2, .2, .2, .08);
                }
                hornConnected = true;
                break;
            }
        }
    }

    /**
     * A blob has no separate head to turn. Face the same predicted point the volley
     * uses, then keep that direction while blowing instead of following the next target.
     * Entity yaw is synced normally; the renderer uses it directly during this attack
     * so vanilla's delayed body-follow-head control cannot leave the face sideways.
     */
    private void aimBubbleBlow() {
        Vec3 origin = activeAttack.motion() == null ? position().add(0, BUBBLE_MOUTH_HEIGHT, 0)
                : bubbleMouth(activeAttack, activeAttack.hitTick());
        if (attackTick <= activeAttack.hitTick() && attackTarget != null && attackTarget.isAlive()) {
            bubbleAimPoint = PepperBreathEntity.predictImpactPoint(attackTarget, origin,
                    BubbleBlowEntity.SPEED, BubbleBlowEntity.MAX_AIM_LEAD);
        }
        if (bubbleAimPoint == null) {
            return;
        }
        Vec3 direction = bubbleAimPoint.subtract(origin);
        if (direction.x * direction.x + direction.z * direction.z > 1.0E-8) {
            setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        }
        yHeadRot = getYRot();
        yBodyRot = getYRot();
    }

    /** Use the impending side during reach planning and the committed side during contact. */
    public boolean contactMirrored(DigimonAttack attack) {
        return attack.alternateSides() && (activeAttack == attack ? attackMirrored : nextAttackMirrored);
    }

    /** Species-authored muzzle, or the original shared baby mouth for legacy models. */
    public Vec3 bubbleMouth(DigimonAttack attack, double tick) {
        return attack.motion() != null
                ? AttackGeometry.world(position(), attack.motion().sample(tick).mouth(), getYRot())
                : position().add(0, BUBBLE_MOUTH_HEIGHT, 0)
                    .add(Vec3.directionFromRotation(0, getYRot()).scale(BUBBLE_MOUTH_FORWARD));
    }

    /**
     * The body turns onto the shot through the wind-up, so the ball always leaves the way the snout points: each tick
     * the meeting point is solved again from the snout as it will be at the release, the body turns toward it by at
     * most {@link #FIREBALL_TURN} degrees, and the release tick settles it exactly. After the release the facing holds.
     */
    private void aimFireball() {
        if (riderAttack) return;
        float yaw = this.entityData.get(DATA_ATTACK_YAW);
        LivingEntity aimed = attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
        if (aimed != null && attackTick <= activeAttack.hitTick()) {
            float wanted = yaw;
            // Turning moves the snout, and the snout is where the flight is measured from: two passes settle both.
            for (int pass = 0; pass < 2; pass++) {
                fireballAim = fireballIntercept(activeAttack, aimed, position(), wanted, activeAttack.hitTick() - attackTick);
                wanted = AttackGeometry.yaw(position(), fireballAim);
            }
            yaw = attackTick >= activeAttack.hitTick() ? wanted : Mth.approachDegrees(yaw, wanted, FIREBALL_TURN);
        }
        setYRot(yaw);
        yHeadRot = yBodyRot = yaw;
        this.entityData.set(DATA_ATTACK_YAW, yaw);
    }

    /** Where a ball released {@code delay} ticks from now, from these feet at this facing, meets the target. */
    private Vec3 fireballIntercept(DigimonAttack attack, LivingEntity target, Vec3 feet, float yaw, double delay) {
        Vec3 mouth = releaseMouth(attack, feet, yaw);
        return targetMotion().follows(target) ? targetMotion().intercept(target, mouth, PepperBreathEntity.SPEED, delay)
                : PepperBreathEntity.predictImpactPoint(target, mouth, PepperBreathEntity.SPEED, PepperBreathEntity.MAX_AIM_LEAD);
    }

    /**
     * Whether a ball thrown from these feet would meet the target: the meeting point in reach and in the open, and the
     * aim trusted over the flight ({@link #FIREBALL_TRUST}). A body that keeps changing its mind is shot at from
     * closer, where a change of mind cannot carry it clear of the ball.
     */
    private boolean fireballWorthIt(DigimonAttack attack, LivingEntity target, Vec3 feet, float yaw) {
        if (!targetMotion().follows(target)) return true;
        Vec3 mouth = releaseMouth(attack, feet, yaw);
        Vec3 meet = targetMotion().intercept(target, mouth, PepperBreathEntity.SPEED, attack.hitTick());
        var miss = targetMotion().miss(meet.distanceTo(mouth) / PepperBreathEntity.SPEED);
        // The ball's own box, swept with a margin, meets the target's box: its reach across and in height.
        double ball = com.digicube.registry.DCEntityTypes.PEPPER_BREATH.getWidth() / 2 + PepperBreathEntity.HIT_MARGIN;
        double across = ball + target.getBbWidth() / 2, height = ball + target.getBbHeight() / 2;
        return meet.subtract(feet).horizontalDistance() <= attack.range() + across
                && meet.subtract(mouth).dot(Vec3.directionFromRotation(0, yaw)) >= FIREBALL_AHEAD
                && miss.across() <= across * FIREBALL_TRUST && miss.height() <= height * FIREBALL_TRUST
                && clearAttackLine(mouth, meet);
    }

    /** The snout at the release, for a body standing on these feet and facing this way. */
    private Vec3 releaseMouth(DigimonAttack attack, Vec3 feet, float yaw) {
        var muzzle = com.digicube.digimon.FireballMuzzles.get(attack);
        if (muzzle.isPresent()) return AttackGeometry.world(feet, muzzle.get().sample(attack.hitTick()).mouth(), yaw);
        return feet.add(0.0, MOUTH_HEIGHT, 0.0).add(Vec3.directionFromRotation(0, yaw).scale(MOUTH_FORWARD));
    }

    /** Embers gather at the mouth while Agumon inhales, then a whoosh as it fires. */
    private void tickFireballCharge(ServerLevel level) {
        int ticksToFire = activeAttack.hitTick() - attackTick;
        if (ticksToFire > FIREBALL_CHARGE_TICKS || ticksToFire < 0) {
            return;
        }
        Vec3 mouth = mouthPosition(activeAttack, attackTick);
        // The ember itself is drawn in the mouth (the species' charge clip); a flicker every other tick escapes it.
        if (ticksToFire % 2 == 0) level.sendParticles(ParticleTypes.SMALL_FLAME, true, true, mouth.x, mouth.y, mouth.z, 1, 0.06, 0.04, 0.06, 0.01);
        if (ticksToFire == FIREBALL_CHARGE_TICKS) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.BLAZE_AMBIENT, SoundSource.NEUTRAL, 0.6F, 1.4F);
        }
    }

    private void deliver(ServerLevel level, DigimonAttack attack) {
        LivingEntity target = attackTarget;
        switch (attack.kind()) {
            case KINETIC_SHOT -> { if (kinetic != null) kinetic.fire(level); }
            case RETREAT_KICK -> { /* Only the timed, exported hoof sweep can deal damage. */ }
            case MELEE -> {
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 0.8F, 1.1F);
                if (target == null || !target.isAlive() || !canAttack(target) || isAllyOf(target)
                        || !isWithinMeleeAttackRange(target) || !getSensing().hasLineOfSight(target)) {
                    return;
                }
                if (target.hurtServer(level, damageSources().mobAttack(this), damageAgainst(attack, target))) {
                    setLastHurtMob(target);
                    level.sendParticles(ParticleTypes.SWEEP_ATTACK, true, true,
                            target.getX(), target.getY(0.5), target.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
            case FIREBALL -> {
                Vec3 mouth = mouthPosition(attack, attack.hitTick());
                boolean aimed = target != null && target.isAlive();
                // The body already faces the meeting point (aimFireball); from here the ball flies straight, no homing.
                Vec3 aim = aimed && fireballAim != null ? fireballAim : mouth.add(getViewVector(1.0F).scale(4.0));
                Vec3 direction = aim.subtract(mouth), forward = Vec3.directionFromRotation(0, yBodyRot);
                // A target that ran in under the snout is met straight ahead: the ball never leaves over the shoulder.
                if (direction.dot(forward) < FIREBALL_AHEAD) direction = forward;
                // The ball's centre, where its core is drawn, leaves the mouth; the entity stands on the bottom of its box.
                Vec3 base = mouth.subtract(0, com.digicube.registry.DCEntityTypes.PEPPER_BREATH.getHeight() / 2, 0);
                PepperBreathEntity fireball = new PepperBreathEntity(level, this, base, damageAgainst(attack, target));
                fireball.shoot(direction.x, direction.y, direction.z, PepperBreathEntity.SPEED, 0.0F);
                level.addFreshEntity(fireball);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.15F);
                level.sendParticles(ParticleTypes.FLAME, true, true, mouth.x, mouth.y, mouth.z, 4, 0.12, 0.12, 0.12, 0.06);
            }
            case BUBBLES -> {
                // Use the same facing and predicted point as the windup, with the
                // mouth offset along that axis so the volley cannot leave sideways.
                double yaw = Math.toRadians(getYRot());
                Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
                Vec3 mouth = bubbleMouth(attack, attackTick);
                Vec3 head = attack.motion() == null ? position().add(0, BUBBLE_MOUTH_HEIGHT, 0)
                        : AttackGeometry.world(position(), attack.motion().sample(attackTick).head(), getYRot());
                if (!clearAttackLine(head, mouth)) return;
                boolean aimed = target != null && target.isAlive();
                Vec3 aim = bubbleAimPoint != null ? bubbleAimPoint : mouth.add(forward.scale(4.0));
                Vec3 direction = aim.subtract(mouth);
                BubbleBlowEntity bubbles = new BubbleBlowEntity(level, this, mouth,
                        damageAgainst(attack, target), aimed ? target : null);
                bubbles.shoot(direction.x, direction.y, direction.z, BubbleBlowEntity.SPEED, 0.0F);
                level.addFreshEntity(bubbles);
                level.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE,
                        SoundSource.NEUTRAL, 0.6F, 1.5F);
            }
            case FLAME_SHOT -> {
                AttackMotion.Frame frame = attack.motion().sample(attackTick);
                Vec3 mouth = authoredPoint(frame.aimedMouth(this.entityData.get(DATA_ATTACK_AIM_PITCH)));
                // A snout outside the body box must not spawn a shot through a wall.
                HitResult obstruction = level.clip(new ClipContext(authoredPoint(frame.head()), mouth,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
                if (obstruction.getType() != HitResult.Type.MISS) mouth = obstruction.getLocation();
                Vec3 aim = authoredAimPoint != null ? authoredAimPoint : mouth.add(getViewVector(1).scale(8));
                Vec3 direction = aim.subtract(mouth);
                MegaFlameEntity flame = new MegaFlameEntity(level, this, mouth, damageAgainst(attack, null), target);
                flame.shoot(direction.x, direction.y, direction.z, MegaFlameEntity.SPEED, 0.0F);
                level.addFreshEntity(flame);
                if (obstruction.getType() != HitResult.Type.MISS) flame.burst(level);
                level.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.BLAZE_SHOOT,
                        SoundSource.NEUTRAL, 1.4F, 0.65F);
            }
            case WATER_WAVE -> {
                AttackMotion.Frame frame = attack.motion().sample(attackTick);
                Vec3 origin = authoredPoint(frame.mouth());
                HitResult obstruction = level.clip(new ClipContext(authoredPoint(frame.head()), origin,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
                if (obstruction.getType() != HitResult.Type.MISS) origin = obstruction.getLocation();
                Vec3 aim = authoredAimPoint != null ? authoredAimPoint : origin.add(getViewVector(1).scale(8));
                Vec3 direction = aim.subtract(origin);
                MarchingFishesEntity wave = new MarchingFishesEntity(level, this, origin,
                        damageAgainst(attack, null), attack.knockback(), target);
                wave.shoot(direction.x, direction.y, direction.z, MarchingFishesEntity.SPEED, 0.0F);
                level.addFreshEntity(wave);
                if (obstruction.getType() != HitResult.Type.MISS) wave.splash(level, false);
                level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED,
                        SoundSource.NEUTRAL, 0.85F, 1.25F);
                level.sendParticles(ParticleTypes.SPLASH, true, true, origin.x, origin.y, origin.z,
                        18, 0.5, 0.15, 0.3, 0.08);
            }
            case GROUND_WAVE -> {
                if (onGround() && !isInWater() && !isInLava()) {
                    level.addFreshEntity(new TectonicWaveEntity(level, this, attack));
                    if (riderAttack) level.broadcastEntityEvent(this, DigimonAnimationEvents.SLAM);
                }
            }
            case HORN_RAM, POUNCE, FLAME_STREAM, FROST_STREAM, FIST, CONSTRICTION, BOX_SWEEP, BOX_BURST -> { /* Continuous contact is evaluated by the timeline. */ }
        }
    }

    /** One tick of landed chilling frost: pay into the victim's Cold charge, or keep a running Cold topped up. */
    private void chill(ServerLevel level, LivingEntity victim) {
        var cold = new MobEffectInstance(DCEffects.COLD, IceCombo.COLD_TICKS, 0, false, true);
        if (victim.hasEffect(DCEffects.FROZEN) || !victim.canBeAffected(cold)) return;
        MobEffectInstance running = victim.getEffect(DCEffects.COLD);
        if (running != null) {
            if (running.getDuration() <= IceCombo.COLD_TICKS - IceCombo.COLD_REFRESH_STEP_TICKS) victim.addEffect(cold, this);
            return;
        }
        if (!((CombatMarkState) victim).digicube$chill() || !victim.addEffect(cold, this)) return;
        level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, victim.getX(), victim.getY(.5), victim.getZ(),
                20, victim.getBbWidth() * .55, victim.getBbHeight() * .5, victim.getBbWidth() * .55, .04);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.POWDER_SNOW_STEP, SoundSource.NEUTRAL, 1.0F, .6F);
    }

    /** The damage of one hit, critical roll included; the triangle lives in that roll. */
    private float damageAgainst(DigimonAttack attack, LivingEntity target) {
        float damage = (float) (getAttributeValue(Attributes.ATTACK_DAMAGE) * attack.power());
        return level() instanceof ServerLevel server ? com.digicube.digimon.CriticalHits.roll(server, this, target, damage) : damage;
    }

    /** Server-side tallies for balance runs. */
    private int criticalHits, dodges;
    public void countCriticalHit() { criticalHits++; }
    public int criticalHits() { return criticalHits; }
    public void countDodge() { dodges++; }
    public int dodges() { return dodges; }

    /** Server, for balance runs: how often each tactic of the AI was used (a jet charge, a shot on the run...). */
    private final java.util.Map<String, Integer> skillUses = new java.util.TreeMap<>();
    public void countSkill(String skill) { skillUses.merge(skill, 1, Integer::sum); }
    public java.util.Map<String, Integer> skillUses() { return skillUses; }

    /** Server: what the attack under way is aimed at (the AI's target or a rider's soft target), or null. */
    public LivingEntity strikeTarget() {
        return activeAttack != null && attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
    }

    /** Server: the point the attack under way was last aimed at (a rider's crosshair without a soft target), or null. */
    public Vec3 strikeAimPoint() { return activeAttack == null ? null : authoredAimPoint; }

    /** Delayed authored area attacks retain the caster's attribute triangle and ally rules. */
    public boolean hitWithAttack(ServerLevel level, DigimonAttack attack, LivingEntity victim) {
        return hitWithAttack(level, attack, victim, position());
    }

    /** @param from where the blow pushes its victim away from: the caster, or the landing point of a summoned strike */
    public boolean hitWithAttack(ServerLevel level, DigimonAttack attack, LivingEntity victim, Vec3 from) {
        return hitWithAttack(level, attack, victim, from, 1);
    }

    /** @param scale share of the attack's damage this blow deals: a rider's snap shot, a trample at the tail of a charge */
    public boolean hitWithAttack(ServerLevel level, DigimonAttack attack, LivingEntity victim, Vec3 from, float scale) {
        if (!victim.isAlive() || !canStrike(victim) || isAllyOf(victim)) return false;
        float damage=damageAgainst(attack,victim)*scale;
        var authored=com.digicube.digimon.AuthoredAttacks.get(attack);
        // Blows that come several to a cast (a combo's beats, a volley's missiles) each land: they bypass the hurt cooldown.
        var source=authored!=null && (!authored.hitWindows().isEmpty() || authored.fires())?DCDamageTypes.volleyAttack(this):damageSources().mobAttack(this);
        if (!victim.hurtServer(level,source,damage)) return false;
        victim.knockback(attack.knockback(),from.x-victim.getX(),from.z-victim.getZ(),source,damage);
        com.digicube.digimon.CrackMark.strike(attack,victim);
        setLastHurtMob(victim);return true;
    }

    /** Server, at the hit tick of a discharging move: the bolts strike ({@link ArcDischarge}) and the clients get them. */
    void discharge(ServerLevel level, DigimonAttack attack, com.digicube.digimon.AuthoredAttacks.Definition d) {
        LivingEntity aimed = attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
        var strike = ArcDischarge.strike(level, this, attack, d, aimed, attackTick);
        this.entityData.set(DATA_ARC, ArcDischarge.encode(strike));
    }

    /** The last discharge this body let go as its clients get it ({@link ArcDischarge#encode}); empty before any. */
    public String dischargeText() { return this.entityData.get(DATA_ARC); }

    /** Client: the last discharge this body let go, and the client tick it arrived; null before any. */
    private ArcDischarge.Strike clientArc;
    private int clientArcTick;
    private void seenArc() {
        clientArc = ArcDischarge.decode(this.entityData.get(DATA_ARC));
        clientArcTick = tickCount;
    }
    /** Client: the discharge being drawn, or null once its bolts have faded ({@code life} ticks). */
    public ArcDischarge.Strike clientArc(int life) {
        return clientArc != null && tickCount - clientArcTick <= life + 1 ? clientArc : null;
    }
    /** Client: ticks since the discharge arrived, partial included. */
    public float clientArcAge(float partial) {
        return tickCount - clientArcTick + partial;
    }

    boolean damageWithActiveAttack(LivingEntity target) {
        if (!(level() instanceof ServerLevel server) || activeAttack == null || !canStrike(target) || isAllyOf(target)) return false;
        var source=activeAttack.kind()==DigimonAttack.Kind.CONSTRICTION ? DCDamageTypes.crushAttack(this) : DCDamageTypes.partnerAttack(this);
        float damage=damageAgainst(activeAttack,target);
        // A squeeze also takes a share of what the prey has: big bodies have more to crush, small ones are not deleted.
        if(activeAttack.kind()==DigimonAttack.Kind.CONSTRICTION)damage+=target.getMaxHealth()*com.digicube.digimon.ConstrictionMotion.CRUSH_SHARE;
        boolean hit=target.hurtServer(server,source,damage);
        if(hit)setLastHurtMob(target);
        return hit;
    }

    /** World position of the snout at this tick of the attack, following the body's facing: the animated one when the move has a muzzle table. */
    private Vec3 mouthPosition(DigimonAttack attack, double tick) {
        var muzzle = com.digicube.digimon.FireballMuzzles.get(attack);
        if (muzzle.isPresent()) return AttackGeometry.world(position(), muzzle.get().sample(tick).mouth(), yBodyRot);
        double yaw = Math.toRadians(yBodyRot);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        return position().add(0.0, MOUTH_HEIGHT, 0.0).add(forward.scale(MOUTH_FORWARD));
    }

    // --- client-side animation -------------------------------------------------------

    /** Client: the charge value last seen, to tell a start and an end apart. */
    private int lastSeenCharge;

    /**
     * Client. A charge starting puts its attack on the cooldown clock; a charge handing the reins back to this client
     * carries its run on into the gallop, from where the body was drawn.
     */
    private void seenCharge() {
        int now = this.entityData.get(DATA_RIDER_CHARGE);
        if (now > 0 && lastSeenCharge == 0) {
            for (DigimonAttack attack : riderAttacks()) {
                var spec = riderSpec(attack);
                if (spec != null && spec.aim() == com.digicube.digimon.RiderAttack.Aim.CHARGE) seenCooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
            }
        }
        // Only a run hands back at speed; a buck ends standing, facing away from what it kicked.
        if (now == 0 && lastSeenCharge != 0 && rider() != null && rider().isLocalPlayer()) getInterpolation().cancel();
        if (now == 0 && lastSeenCharge > 0 && rider() != null && rider().isLocalPlayer()) {
            Vec3 run = Vec3.directionFromRotation(0, getYRot()).scale(CHARGE_EXIT);
            setDeltaMovement(run.x, getDeltaMovement().y, run.z);
            rideMomentum = 1;
            gallopMomentum = 1;
        }
        lastSeenCharge = now;
    }

    /**
     * Client. A drawn shot holds its raised pose while it is held: the server's word (the draw's charge), or ahead of
     * it the local rider's own button, so the arm never overshoots the hold and snaps back.
     */
    private void holdDrawnPose() {
        DigimonAttack attack = getAnimatingAttack();
        var shot = com.digicube.digimon.KineticAttacks.get(attack);
        if (shot == null || shot.projectile() == null || !attackAnimationState.isStarted() || rider() == null) return;
        boolean held = this.entityData.get(DATA_RIDER_DRAW) >= 0 || localRiderDraws && rider().isLocalPlayer() && riderDrawing();
        if (!held || attackAnimationState.getTimeInMillis(tickCount) / 50F < shot.riderDrawTick()) return;
        attackAnimationState.start(tickCount - shot.riderDrawTick());
        attackAnimationEndTick = tickCount + shot.duration(false) - shot.riderDrawTick();
    }

    /** Client. The shot leaves as the clip passes its hit tick (a drawn shot's clip holds before it until release). */
    private void noticeShot() {
        DigimonAttack attack = getAnimatingAttack();
        if (attack == null || attack.kind() != DigimonAttack.Kind.KINETIC_SHOT || !attackAnimationState.isStarted()) { lastShotClock = -1; return; }
        float clock = attackAnimationState.getTimeInMillis(tickCount) / 50F;
        // Its tile reloads from the shot, as the server's cooldown does: raised and drawn, the clock waits.
        if (clock < attack.hitTick()) seenCooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
        if (lastShotClock >= 0 && lastShotClock < attack.hitTick() && clock >= attack.hitTick()) seenShotTick = tickCount;
        lastShotClock = clock;
    }

    private String lastThrowerClip = "";
    private int lastThrowerElapsed = -1;

    /**
     * Client. A thrower clip passing its work tick: a throw leaving the hand kicks the rider's view like a shot (and the
     * icicle's tile cools down from it, as the server's does); the bone slapping into the fists jolts it like a blow.
     */
    private void noticeThrowerEvent(String clip, int elapsed) {
        var attack = com.digicube.digimon.ThrownAttacks.owner(clip);
        var returning = com.digicube.digimon.ThrownAttacks.returning(attack);
        var charged = com.digicube.digimon.ThrownAttacks.charged(attack);
        int event = returning != null && clip.equals(returning.throwClip().name()) ? returning.throwClip().event()
                : returning != null && clip.equals(returning.releaseClip().name()) ? returning.releaseClip().event()
                : returning != null && clip.equals(returning.catchClip().name()) ? returning.catchClip().event()
                : charged != null && clip.equals(charged.release().name()) ? charged.release().event() : -1;
        boolean passed = event >= 0 && elapsed >= event && !(clip.equals(lastThrowerClip) && lastThrowerElapsed >= event);
        lastThrowerClip = clip;
        lastThrowerElapsed = elapsed;
        // Tracking that began after the moment has nothing to show for it.
        if (!passed || elapsed - event > 2) return;
        if (returning != null && clip.equals(returning.catchClip().name())) { seenImpactTick = tickCount; return; }
        seenShotTick = tickCount;
        if (charged != null) seenCooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks() - (elapsed - event));
    }

    /** Synced timeline also reaches players who start tracking halfway through a long breath. */
    private void syncSustainedAnimation() {
        String name = this.entityData.get(DATA_SUSTAINED_ATTACK);
        if (name.isEmpty()) {
            DigimonAttack animating = getAnimatingAttack();
            if (animating != null && (com.digicube.digimon.KineticAttacks.handles(animating) || animating.fuel() != null || animating.kind() == DigimonAttack.Kind.CONSTRICTION
                    || com.digicube.digimon.ThrownAttacks.handles(animating))) {
                if (animating.kind() == DigimonAttack.Kind.KINETIC_SHOT && attackAnimationState.isStarted()
                        && attackAnimationState.getTimeInMillis(tickCount) / 50F < animating.hitTick()) seenCooldownUntil.remove(animating.id());
                attackAnimationState.stop();
                attackAnimationName = null;
            }
            return;
        }
        var thrown = com.digicube.digimon.ThrownAttacks.owner(name);
        if (thrown != null && attacks().contains(thrown)) {
            // Thrower clips (throw, catch, pickup, form, hold, release) run on the server's stage clock, sent every tick.
            int elapsed = this.entityData.get(DATA_SUSTAINED_TICK);
            noticeThrowerEvent(name, elapsed);
            attackAnimationName = name;
            attackAnimationState.start(tickCount - elapsed);
            attackAnimationEndTick = tickCount + com.digicube.digimon.ThrownAttacks.length(name) - elapsed;
            return;
        }
        for (DigimonAttack attack : attacks()) {
            var kineticDefinition = com.digicube.digimon.KineticAttacks.get(attack);
            if (kineticDefinition != null && kineticDefinition.matches(name)) {
                int elapsed = this.entityData.get(DATA_SUSTAINED_TICK);
                if (!name.equals(attackAnimationName) && elapsed == 0 && attack.kind() == DigimonAttack.Kind.KINETIC_SHOT)
                    seenCooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
                attackAnimationName = name;
                attackAnimationState.start(tickCount - elapsed);
                attackAnimationEndTick = tickCount + kineticDefinition.duration(name) - elapsed;
                return;
            }
            if ((attack.fuel() != null || attack.kind() == DigimonAttack.Kind.CONSTRICTION) && attack.id().getPath().equals(name)) {
                int elapsed = this.entityData.get(DATA_SUSTAINED_TICK);
                attackAnimationName = name;
                attackAnimationState.start(tickCount - elapsed);
                attackAnimationEndTick = tickCount + attack.durationTicks() - elapsed;
                return;
            }
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == DigimonAnimationEvents.CANCEL) {
            attackAnimationState.stop();
            attackAnimationName = null;
            return;
        }
        if (id == DigimonAnimationEvents.IMPACT) {
            seenImpactTick = tickCount;
            swingConnected = true;
            DigimonAttack animating = getAnimatingAttack();
            var bite = PounceAttacks.get(animating);
            if (bite != null && attackAnimationState.isStarted()) {
                // A pounce's jaws shut on the bite whenever it lands: the clip jumps to its snap, then holds a moment.
                pounceBiteTick = tickCount;
                localPounceBit = true;
                float clip = attackAnimationState.getTimeInMillis(tickCount) / 50F;
                if (clip < bite.snap()) {
                    attackAnimationStartTick -= Math.round(bite.snap() - clip);
                    attackAnimationEndTick = attackAnimationStartTick + animating.durationTicks();
                    attackAnimationState.start(attackAnimationStartTick);
                }
                hitStopTicks = 2;
                return;
            }
            hitStopTicks = HIT_STOP_TICKS;
            return;
        }
        if (id == DigimonAnimationEvents.SLAM) {
            seenSlamTick = tickCount;
            return;
        }
        if (id == DigimonAnimationEvents.CONTACT) {
            attackConnected = true;
            // A dashing blow that lands sooner than its impact pose (a target close by) jumps its clip there, as a
            // pounce's bite does: the squash and the burst meet the real contact.
            DigimonAttack animating = getAnimatingAttack();
            var dash = animating == null ? null : com.digicube.digimon.AuthoredAttacks.get(animating);
            if (dash != null && dash.hasImpact() && attackAnimationState.isStarted()) {
                float clip = attackAnimationState.getTimeInMillis(tickCount) / 50F;
                if (clip < dash.impactTick()) {
                    attackAnimationStartTick -= Math.round((float) dash.impactTick() - clip);
                    attackAnimationEndTick = attackAnimationStartTick + animating.durationTicks();
                    attackAnimationState.start(attackAnimationStartTick);
                }
            }
            return;
        }
        int index = DigimonAnimationEvents.attackIndex(id);
        if (index >= 0) {
            boolean mirrored = DigimonAnimationEvents.mirrored(id);
            List<DigimonAttack> attacks = attacks();
            if (index < attacks.size()) {
                DigimonAttack move = attacks.get(index), attack = move;
                var pounceStart = PounceAttacks.get(move);
                // The local rider's own pounce is already under way here, from the press.
                if (pounceStart != null && pounceStart == localPounceSpec && tickCount - localPouncePress <= 10) return;
                var forms = com.digicube.digimon.AuthoredAttacks.forms(move);
                int form = DigimonAnimationEvents.form(id);
                if (forms != null && form < forms.all().size()) attack = forms.all().get(form);
                int lead = pounceStart == null ? 0 : pounceStart.startTick(form == 1);
                attackAnimationName = attack.animationName(mirrored);
                attackAnimationEndTick = tickCount - lead + attack.durationTicks();
                seenCooldownUntil.put(move.id(), com.digicube.digimon.AttackCharges.spend(seenChargeRefills, move, tickCount));
                attackAnimationStartTick = tickCount - lead;
                riderStaleYaw = this.entityData.get(DATA_ATTACK_YAW);
                hitStopTicks = 0;
                swingConnected = attackConnected = false;
                attackAnimationState.start(attackAnimationStartTick);
            }
            return;
        }
        super.handleEntityEvent(id);
    }

    @Override
    public void tick() {
        if (level() instanceof ServerLevel serverLevel && !PartyManager.beforeEntityTick(this, serverLevel)) return;
        EvolutionController.tick(this);
        if(isRemoved())return;
        previousMountWaterAmount = mountWaterAmount;
        if (isSwimmingMovement()) lastSwimTick = tickCount;
        mountWaterAmount = Mth.approach(mountWaterAmount, waterPose() ? 1 : 0, .08F);
        previousAttackAimPitch = this.entityData.get(DATA_ATTACK_AIM_PITCH);
        previousAttackYaw = this.entityData.get(DATA_ATTACK_YAW);
        if (aerialMount()!=null) aerialRiding().serverTick();
        if (!level().isClientSide() && aerialMount()!=null) entityData.set(DATA_FLIGHT_FUEL,flightReserve().fraction());
        super.tick();
        if(evolutionLocked())setDeltaMovement(Vec3.ZERO);
        if (level() instanceof ServerLevel serverLevel && !isEffectiveAi() && !evolutionLocked()) {
            // Vanilla skips a ridden mob's AI step, and with it the tanks' recharge and the timeline.
            attackFuel.values().forEach(FuelReserve::tickRecharge);
            // A thrower's weapons live on under a rider: the throw and the icicle follow the crosshair, the bone flies
            // home and is caught, a lost one grows back.
            if (thrower().active()) {
                if (getControllingPassenger() instanceof Player rider) { aimRiderThrows(rider); pickUpUnderRider(); }
                thrower.tick(serverLevel);
            }
            if (riderAttack) tickAttackTimeline(serverLevel);
            for (DigimonAttack attack : riderAttacks()) if (attack.fuel() != null) {
                var tank = fuelFor(attack);
                this.entityData.set(DATA_RIDER_FUEL, tank.isRecharging() ? 0F : Mth.clamp(tank.availableTicks() / (float) attack.fuel().capacityTicks(), 0, 1));
            }
        }
        if (!level().isClientSide()) {
            if (lungePrey != null) tickGrabLunge();
            // What the hold would take is the server's call, so the outline and the lit tile never promise what it would refuse.
            else if (tickCount % 2 == 0) {
                DigimonAttack wrap = rider() == null ? null : riderAttacks().stream().filter(a -> a.kind() == DigimonAttack.Kind.CONSTRICTION).findFirst().orElse(null);
                LivingEntity pick = wrap == null ? null : grabPick(rider(), wrap);
                this.entityData.set(DATA_GRAB_PREY, pick == null ? -1 : pick.getId());
            }
        }
        // A swimmer shares its breath: its rider never drowns in the saddle, and a spent breath comes back.
        if (!level().isClientSide() && canSwim() && rider() != null && rider().isEyeInFluid(FluidTags.WATER))
            rider().setAirSupply(Math.min(rider().getMaxAirSupply(), rider().getAirSupply() + 3));
        if (level().isClientSide()) seaWake();
        tickWhip();
        if (bufferedRiderSlot >= 0 && !level().isClientSide()) {
            if (tickCount > bufferedRiderUntil || !(getControllingPassenger() instanceof Player rider)) bufferedRiderSlot = -1;
            else startRiderAttack(rider, bufferedRiderSlot);
        }
        if (hitStopTicks > 0 && level().isClientSide() && attackAnimationState.isStarted()) {
            // Hit-stop: the pose holds for a moment on contact; the clip resumes where it stopped.
            hitStopTicks--;
            attackAnimationState.start(++attackAnimationStartTick);
            attackAnimationEndTick++;
        }
        placeParts();
        holdNeck();
        if (!level().isClientSide() && !isAlive()) {
            cancelAttack();
            if (getFlightPhase() != FlightPhase.GROUNDED) {
                setNoGravity(false);
                setFlightPhase(FlightPhase.GROUNDED);
            }
        }
        if (level().isClientSide()) {
            // The length is read when a packet lands, so this tick sets it for the packets the next one consumes.
            // A jet charge covers most of a block a tick: its packets are chased over the lunge's two steps as well.
            getInterpolation().setInterpolationLength(this.entityData.get(DATA_RIDER_CHARGE) != 0 ? AttackTravelSync.LUNGE_STEPS : AttackTravelSync.steps(
                    attackAnimationState.isStarted() ? getAnimatingAttack() : null, tickCount + 1 - attackAnimationStartTick));
            holdDrawnPose();
            noticeShot();
            previousAerialBank=aerialBank;previousAerialPitch=aerialPitch;
            aerialBank=Mth.lerp(.2F,aerialBank,Mth.clamp(-Mth.wrapDegrees(getYRot()-yRotO)*2,-18,18));
            aerialPitch=Mth.lerp(.18F,aerialPitch,
                    com.digicube.entity.ai.AerialHandling.flightPitch(getDeltaMovement()));
            previousRunAnimationAmount = runAnimationAmount;
            runAnimationAmount = Mth.approach(runAnimationAmount, isRunningToOwner() ? 1.0F : 0.0F, 0.2F);
            previousSwimAnimationAmount = swimAnimationAmount;
            previousSwimAnimationPhase = swimAnimationPhase;
            previousSwimMotionAmount = swimMotionAmount;
            previousGroundAnimationPhase = groundAnimationPhase;
            previousGroundAnimationAmount = groundAnimationAmount;
            previousGroundRunAmount = groundRunAmount;
            previousFlightWalkAmount = flightWalkAmount;
            previousSwimBank = swimBank;
            previousSwimDash = swimDash;
            previousSwimLeap = swimLeap;
            previousSwimSurface = swimSurface;
            previousTurnBank = turnBank;
            float target = waterPose() ? 1 : 0;
            swimAnimationAmount = Mth.approach(swimAnimationAmount, target, target > swimAnimationAmount ? .08F : .10F);
            double dx = getX() - xo;
            double dy = getY() - yo;
            double dz = getZ() - zo;
            double horizontalTravel = Math.sqrt(dx * dx + dz * dz);
            double travelled = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double speed = Math.max(travelled, getDeltaMovement().length());
            if (canFly() && getLocomotion().groundGait()==null) {
                double groundSpeed = Math.max(horizontalTravel, getDeltaMovement().horizontalDistance());
                float wanted = getFlightPhase() == FlightPhase.GROUNDED && onGround()
                        ? (float) Mth.clamp(groundSpeed / .021, 0, 1) : 0;
                flightWalkAmount = Mth.approach(flightWalkAmount, wanted, .125F);
                if (getFlightPhase() == FlightPhase.GROUNDED && groundSpeed < 1) {
                    // Native cycle: 6 px of planted travel over 62% of a second.
                    double stride = (6.0 / .62 / 16) * getBody().modelScale();
                    groundAnimationPhase += (float) Math.min(2.5, groundSpeed * 20 / stride / Math.max(.25F, flightWalkAmount));
                }
            }
            float motion = canSwim() ? (float) Mth.clamp(speed / (getLocomotion().swimSpeed() * .7), 0, 1) : 0;
            // Under a rider the body stays stretched out in its swimming pose at rest too (the resting pose rears up
            // three blocks and would lift the saddle with it); only the stroke slows down.
            swimStroke = Mth.lerp(.15F, swimStroke, motion);
            swimMotionAmount = Mth.lerp(.15F, swimMotionAmount, rider() != null ? 1 : motion);
            if (jet() != null) {
                // The swim clip is one pulse: it plays on the pulse's clock, and a new pulse starts it again from its
                // squeeze (only ever forward, so the frames between two ticks never run back through the clip).
                float clip = jet().clipTicks(), within = swimAnimationPhase % clip, pulseAt = Mth.clamp(jetPhase, 0, 1) * clip;
                float step = pulseAt - within;
                if (step < -1.0E-3F) step += clip;
                swimAnimationPhase += step;
            } else swimAnimationPhase += swimAnimationAmount * Mth.lerp(swimStroke, .45F, 1.0F);
            var gait = getLocomotion().groundGait();
            groundTicks = onGround() ? groundTicks + 1 : 0;
            boolean slipping = tickLegs(dx, dz);
            if (gait != null) {
                double groundSpeed = Math.max(horizontalTravel, getDeltaMovement().horizontalDistance());
                if (attackAnimationState.isStarted() && getAnimatingAttack()!=null
                        && getAnimatingAttack().kind()==DigimonAttack.Kind.CONSTRICTION) groundSpeed=0;
                // On ice the gait plays where the legs take the body, not where it slides (tickLegs).
                double pace = slipping ? Math.sqrt(legsX * legsX + legsZ * legsZ) : groundSpeed;
                System.arraycopy(gaitShares, 0, previousGaitShares, 0, 4);
                DigimonGait.Drive drive = gait.drive(pace, 0, getBody().modelScale(), gaitRunning);
                if (gait.directional() && pace > 1.0E-4) {
                    // Which way the body moves in its own frame decides which planted clips play, and how fast the shared phase runs.
                    Vec3 moved = slipping ? new Vec3(legsX, 0, legsZ)
                            : dx * dx + dz * dz > 1.0E-8 ? new Vec3(dx, 0, dz) : getDeltaMovement().multiply(1, 0, 1);
                    double yaw = yBodyRot * Mth.DEG_TO_RAD, scale = pace / Math.max(1.0E-6, moved.length());
                    drive = gait.drive((-moved.x * Math.sin(yaw) + moved.z * Math.cos(yaw)) * scale,
                            (moved.x * Math.cos(yaw) + moved.z * Math.sin(yaw)) * scale, getBody().modelScale(), gaitRunning);
                    for (int i = 0; i < 4; i++) gaitShares[i] = Mth.approach(gaitShares[i], (float) drive.shares()[i], .2F);
                }
                // The run is held through a leap and a stumble: only the ground's own pace changes the gait.
                if (onGround()) gaitRunning = drive.running();
                groundRunAmount = Mth.approach(groundRunAmount, drive.run(), gait.runEase());
                double travel = drive.travel(groundRunAmount);
                // Turning on the spot the paws step round the body's centre (a gait with pivot_reach): the turn is paid on
                // the phase with the travel, and its share of the two goes to the model, signed by the turn's way. Only a
                // body all but standing pivots; one walking turns along its stride (the two step on different beats).
                float turned = Mth.wrapDegrees(yBodyRot - yBodyRotO);
                double pivot = onGround() && !isSwimmingMovement() ? gait.pivotTravel(turned, groundRunAmount)
                        * Mth.clamp(1 - travel / (PIVOT_WALK * gait.fullSpeed(getBody().modelScale())), 0, 1) : 0;
                previousPivotTurn = pivotTurn;
                pivotTurn = Mth.approach(pivotTurn, pivot < 1.0E-4 ? 0 : (float) (Math.signum(turned) * pivot / (pivot + travel)), .25F);
                travel += pivot;
                // A hovering body flies its travel clip off the ground too: over a ledge, a step or a jump.
                boolean supported = onGround() || getLocomotion().hovers() && !isInWater();
                float wanted = supported && !isSwimmingMovement()
                        ? (float) Mth.clamp(travel / gait.fullSpeed(getBody().modelScale()), 0, 1) : 0;
                // A ridden leap keeps its stride, held mid-air, instead of settling into the standing pose.
                if (!onGround() && rider() != null && !isInWater()) wanted = groundAnimationAmount;
                groundAnimationAmount = Mth.approach(groundAnimationAmount, wanted, AMPLITUDE_EASE);
                // Any pace a body can run at turns the legs over (a sprint on ice once froze them mid-stride past a block a
                // tick); only a jump no gait covers, a teleport, is skipped.
                if (supported && groundSpeed < GAIT_SNAP) {
                    float step = gait.advance(travel, groundAnimationAmount, getBody().modelScale(), groundRunAmount);
                    // A gait with no clip of its own for going back plays its walk backwards, which plants the feet as well.
                    boolean backing = !gait.directional() && dx * dx + dz * dz > 1.0E-8
                            && -dx * Math.sin(yBodyRot * Mth.DEG_TO_RAD) + dz * Math.cos(yBodyRot * Mth.DEG_TO_RAD) < -.5 * horizontalTravel;
                    groundAnimationPhase += backing ? -step : step;
                    if (groundAnimationPhase < 0) {
                        // Clips read no negative clock; whole laps keep the pose where it is.
                        float laps = gait.cycleTicks() * 64;
                        groundAnimationPhase += laps;
                        previousGroundAnimationPhase += laps;
                    }
                }
            } else if (canSwim() && onGround()) {
                // Remote entities can move through position interpolation between
                // velocity packets; keep the paws moving with that displacement too.
                double groundSpeed = Math.max(horizontalTravel, Math.sqrt(getDeltaMovement().horizontalDistanceSqr()));
                // Cadence keeps following travel up to 1.65x the native clip, so the paws
                // still plant about every three blocks at full walking pace.
                groundAnimationPhase += .55F * (float) Mth.clamp(groundSpeed / .025, 0, 3);
            }
            swimBank = Mth.lerp(.15F, swimBank, Mth.clamp(-Mth.wrapDegrees(getYRot() - yRotO) * 2.0F, -12, 12));
            // A swimmer with room to lean (swim_bank in the model catalog) banks deep into a hard turn.
            turnBank = Mth.lerp(.12F, turnBank, Mth.clamp(-Mth.wrapDegrees(getYRot() - yRotO) * 3.2F, -TURN_BANK, TURN_BANK));
            // Past its cruise it is dashing (the rider's surge), and out of the water on a leap it arcs.
            double cruise = getLocomotion().swimSpeed();
            swimDash = Mth.approach(swimDash, isSwimmingMovement() && cruise > 0 && speed > cruise * DASH_FROM ? 1 : 0, .12F);
            swimLeap = Mth.approach(swimLeap, leapingFromWater() ? 1 : 0, leapingFromWater() ? .3F : .22F);
            // At its float line a sea mount swims its surface stroke, head out; a few blocks under it, its dive.
            float afloat = 0;
            if (seaMount() && isInWater())
                afloat = (float) Mth.clamp(1 - (getFluidHeight(FluidTags.WATER) - floatLine()) / (getBbHeight() * SURFACE_BAND), 0, 1);
            swimSurface = Mth.approach(swimSurface, afloat, .1F);
            if (isInWater() && !wasInWaterClient) { splashTick = tickCount; splashSpeed = (float) Math.max(0, -getDeltaMovement().y); }
            wasInWaterClient = isInWater();
            followRoll();
            if (getLocomotion().groundGait() != null) tickLeapPose(dy, horizontalTravel);
            tickPouncePitch();
            tickClientBreath();
            ArcDischarge.clientTick(this);
        }
        if (level().isClientSide() && attackAnimationState.isStarted() && tickCount >= attackAnimationEndTick) {
            attackAnimationState.stop();
            attackAnimationName = null;
        }
    }

    /** Attack clip name currently playing on the client, or null when idle. */
    public String getAttackAnimationName() {
        return attackAnimationName;
    }

    /**
     * Attack data for the current client animation.
     * @return attack definition, or null when idle
     */
    public DigimonAttack getActiveAttack() { return activeAttack; }

    public DigimonAttack getAnimatingAttack() {
        var thrown = com.digicube.digimon.ThrownAttacks.owner(attackAnimationName);
        if (thrown != null && attacks().contains(thrown)) return thrown;
        // A move's later forms play clips of their own names.
        for (DigimonAttack move : attacks()) {
            var forms = com.digicube.digimon.AuthoredAttacks.forms(move);
            if (forms != null) for (DigimonAttack form : forms.all()) if (form.animationName(false).equals(attackAnimationName)) return form;
        }
        return attacks().stream().filter(a -> a.animationName(false).equals(attackAnimationName)
                || a.animationName(true).equals(attackAnimationName)
                || com.digicube.digimon.KineticAttacks.get(a) != null && com.digicube.digimon.KineticAttacks.get(a).matches(attackAnimationName)).findFirst().orElse(null);
    }

    /**
     * Interpolated server-authoritative head aim, relative to the authored attack pose.
     * @param partialTick fraction between entity ticks
     * @return additional downward head pitch in degrees
     */
    public float getAttackAimPitch(float partialTick) {
        return Mth.lerp(partialTick, previousAttackAimPitch, this.entityData.get(DATA_ATTACK_AIM_PITCH));
    }
    /**
     * Client: the synced facing belongs to the attack now playing. The start event travels at once and the entity data at
     * the end of the server tick, so for a tick the value can still be the previous attack's.
     */
    public boolean attackYawFresh() {
        return this.entityData.get(DATA_ATTACK_YAW) != riderStaleYaw || tickCount - attackAnimationStartTick > 1;
    }

    /** Exact authored facing, independent of vanilla's delayed body/head interpolation. */
    public float getAttackYaw(float partialTick) {
        return Mth.rotLerp(partialTick,previousAttackYaw,entityData.get(DATA_ATTACK_YAW));
    }

    // --- persistence -----------------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store(com.digicube.digimon.EvolutionState.TAG, CompoundTag.CODEC, evolution.save());
        output.putString(SPECIES_TAG, getSpeciesId().toString());
        output.putInt(LEVEL_TAG, getLevel());
        output.putInt(XP_TAG, xp);
        EntityReference.store(getOwnerReference(), output, OWNER_TAG);
        output.putLong("PartyGeneration", partyGeneration);
        ValueOutput cooldowns = output.child("AttackCooldowns");
        cooldownUntil.forEach((id, until) -> {
            if (until > tickCount) cooldowns.putInt(id.toString(), until - tickCount);
        });
        ValueOutput charges = output.child("AttackCharges");
        chargeRefills.forEach((id, clocks) -> {
            ValueOutput spent = charges.child(id.toString());
            int n = 0;
            for (int until : clocks) if (until > tickCount) spent.putInt(Integer.toString(n++), until - tickCount);
        });
        ValueOutput fuel = output.child("AttackFuel");
        attackFuel.forEach((id, reserve) -> {
            ValueOutput tank = fuel.child(id.toString());
            tank.putInt("Charge", reserve.savedCharge());
            tank.putBoolean("Recharging", reserve.isRecharging());
        });
        if (canFly()) {
            ValueOutput flight = output.child("Flight");
            flight.putDouble("Charge", flightReserve().charge());
            flight.putInt("RestTicks", flightReserve().restRemaining());
            flight.putBoolean("Airborne", getFlightPhase().airborne() && !onGround());
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        // Species and level first, so max health is already right when vanilla reads "Health" below.
        Identifier speciesId = Identifier.tryParse(input.getStringOr(SPECIES_TAG, DEFAULT_SPECIES.toString()));
        setSpecies(speciesId != null ? speciesId : DEFAULT_SPECIES);
        setLevel(input.getIntOr(LEVEL_TAG, Progression.MIN_LEVEL));
        xp = Math.max(0, input.getIntOr(XP_TAG, 0));
        super.readAdditionalSaveData(input);
        // The saved attribute list may predate a balance change; the formula wins.
        applyLevelAttributes();
        EntityReference<LivingEntity> owner = EntityReference.read(input, OWNER_TAG);
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
        partyGeneration = input.getLongOr("PartyGeneration", 0L);
        evolution=com.digicube.digimon.EvolutionState.load(input.read(com.digicube.digimon.EvolutionState.TAG,CompoundTag.CODEC).orElseGet(CompoundTag::new));
        if(evolution.transitioning())EvolutionController.normalize(this);
        cooldownUntil.clear();
        attackFuel.clear();
        ValueInput cooldowns = input.childOrEmpty("AttackCooldowns");
        ValueInput charges = input.childOrEmpty("AttackCharges");
        chargeRefills.clear();
        ValueInput fuel = input.childOrEmpty("AttackFuel");
        // Inactive-form reserves must survive recall/restart too; otherwise cycling and saving refills them.
        for (DigimonAttack attack : DigimonSpeciesRegistry.all().stream().flatMap(species->species.attacks().stream()).distinct().toList()) {
            int remaining = cooldowns.getIntOr(attack.id().toString(), 0);
            if (remaining > 0) cooldownUntil.put(attack.id(), tickCount + remaining);
            ValueInput spent = charges.childOrEmpty(attack.id().toString());
            for (int n = 0; n < com.digicube.digimon.AttackCharges.of(attack); n++) {
                int refill = spent.getIntOr(Integer.toString(n), 0);
                if (refill > 0) chargeRefills.computeIfAbsent(attack.id(), id -> new java.util.ArrayList<>()).add(tickCount + refill);
            }
            if (attack.fuel() != null) {
                ValueInput tank = fuel.childOrEmpty(attack.id().toString());
                int savedCharge=tank.getIntOr("Charge",-1);
                if(savedCharge>=0)fuelFor(attack).restore(savedCharge,tank.getBooleanOr("Recharging",false));
            }
        }
        if (canFly()) {
            ValueInput flight = input.childOrEmpty("Flight");
            flightReserve().restore(flight.getDoubleOr("Charge", flightReserve().charge()), flight.getIntOr("RestTicks", 0));
            needsFlightLanding = flight.getBooleanOr("Airborne", false);
            // Flight must earn its controls again after loading; never persist a gravity-free ground entity.
            setNoGravity(false);
            entityData.set(DATA_FLIGHT_FUEL, flightReserve().fraction());
        }
    }

    /** Name plates, death messages and the like show the species name, not "Digimon". */
    @Override
    protected Component getTypeName() {
        return getSpecies()
                .map(species -> (Component) Component.translatable(species.translationKey()))
                .orElseGet(super::getTypeName);
    }

    /**
     * Wild Digimon are neutral: they never start a fight, and when hurt they retaliate
     * through {@link HurtByTargetGoal}. Only a wild species with no attacks flees instead.
     * Partners never panic.
     */
    private static class WildPanicGoal extends PanicGoal {

        private final DigimonEntity digimon;

        WildPanicGoal(DigimonEntity digimon, double speedModifier) {
            super(digimon, speedModifier);
            this.digimon = digimon;
        }

        @Override
        protected boolean shouldPanic() {
            return !digimon.isOwned() && !digimon.hasAttacks() && super.shouldPanic();
        }
    }
}
