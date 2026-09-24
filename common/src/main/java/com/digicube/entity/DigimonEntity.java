package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.AttackMotion;
import com.digicube.digimon.FuelReserve;
import com.digicube.digimon.IceCombo;
import com.digicube.digimon.IceExposure;
import com.digicube.registry.DCEffects;
import net.minecraft.world.effect.MobEffectInstance;
import com.digicube.digimon.FlightReserve;
import com.digicube.digimon.DigimonFlight;
import com.digicube.entity.ai.DigimonFlightGoal;
import com.digicube.entity.ai.FlightPhase;
import com.digicube.registry.DCDamageTypes;
import com.digicube.registry.DCEntityTypes;
import com.digicube.digimon.DigimonBody;
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
        cooldownUntil.replaceAll((id,until)->until+1);if(constrictionRetryTick>tickCount)constrictionRetryTick++;
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
    /**
     * A rider's jet charge: ticks since the press plus one while it runs, -1 through the buck it may end in, 0 otherwise.
     * Not 0 means the server moves the body.
     */
    private static final EntityDataAccessor<Integer> DATA_RIDER_CHARGE =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);
    /** A rider's drawn shot held raised: how far it has charged, 0 to 1; -1 when nothing is drawn. */
    private static final EntityDataAccessor<Float> DATA_RIDER_DRAW =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.FLOAT);
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
    private int comboPathTargetId = -1;
    private int comboPathCheckTick;
    private boolean comboPathReachable;
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
    private final Map<Identifier, FuelReserve> attackFuel = new HashMap<>();
    private final IceExposure iceExposure = new IceExposure();
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

    /**
     * Carry the parts along every tick on both sides. At rest they follow the authored offsets behind
     * the body yaw; during a wrap they trace the coil's own swept volumes around the prey.
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
        for (int i = 0; i < parts.length; i++) parts[i].place(HitParts.place(authored.get(i), position(), yBodyRot));
    }

    /**
     * Where a swing at a long prey should go: the first hit volume the authored strike can actually
     * reach from here, in the order the reach check rehearses them, else the closest one.
     */
    private AABB nearestVolume(LivingEntity target) {
        var volumes = HitParts.of(target);
        if (activeAttack != null && activeAttack.motion() != null && (activeAttack.kind() == DigimonAttack.Kind.FIST
                || activeAttack.kind() == DigimonAttack.Kind.HORN_RAM || activeAttack.kind() == DigimonAttack.Kind.FROST_BITE)) {
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
        builder.define(DATA_RIDER_CHARGE, 0);
        builder.define(DATA_RIDER_DRAW, -1F);
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
            super.travel(input);
            stepDown(grounded);
        }
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
        moveRelative(getSpeed(), input);
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(DigimonMoveControl.WATER_DRAG));
    }

    /** Server. Re-reads the species sheet after it was tuned at runtime: speed and move control. */
    public void refreshSpeciesData() {
        configureSpeciesMovement();
    }

    private void configureSpeciesMovement() {
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(getSpecies().map(DigimonSpecies::baseSpeed).orElse(.3F));
        boolean authoredControls = canSwim() || attacks().stream().anyMatch(com.digicube.digimon.KineticAttacks::handles);
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
     * click that mounted also cast); this is the one way onto a Digimon.
     */
    public boolean giveRide(Player player) {
        if (level().isClientSide() || !canGiveRide(player) || !player.startRiding(this)) return false;
        cancelAttack();
        getNavigation().stop();
        setTarget(null);
        return true;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getBody().mount().isPresent() && !isVehicle()
                && passenger instanceof Player player && isOwnedBy(player)
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
                && (isOwnedBy(player) || player == scenarioRider) ? player : null;
    }

    private boolean serverOwnsBody() {
        return this.entityData.get(DATA_GRAB_LUNGE) || this.entityData.get(DATA_RIDER_CHARGE) != 0 || this.entityData.get(DATA_SUSTAINED_ATTACK).equals(com.digicube.digimon.DigimonSpeciesBootstrap.CONSTRICTION.id().getPath());
    }

    /** Development scenarios only: a rider who controls this mount without owning it (a party member cannot be staged headless). */
    private Player scenarioRider;
    public void seatScenarioRider(Player rider) {
        if (com.digicube.platform.Services.PLATFORM.isDevelopmentEnvironment()) scenarioRider = rider;
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
        float turnRate = getBody().mount().map(mount -> swimming ? mount.waterTurnRate() : mount.turnRate()).orElse(0F);
        boolean pushing = player.zza > 0 || turnsToTravel() && player.xxa != 0 && player.zza >= 0;
        rideMomentum = Mth.approach(rideMomentum, pushing ? 1 : 0, pushing ? .07F : .2F);
        // Sprinting forward builds into the gallop (body.mount.sprint_build ticks to the top) and settles back faster.
        boolean galloping = player.isSprinting() && player.zza > 0;
        float build = getBody().mount().map(DigimonBody.Mount::sprintBuild).orElse(DigimonBody.Mount.SPRINT_BUILD);
        gallopMomentum = Mth.approach(gallopMomentum, galloping ? 1 : 0, galloping ? 1 / build : .1F);
        leap(player);
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
        float heading = player.getYRot() + (swimming || riderDrawing() ? 0 : travelTurn(player));
        float off = Mth.wrapDegrees(heading - getYRot());
        if (turnRate > 0 && !swimming) {
            // A galloping body turns wider; one far from the view (after a buck, or a look over the shoulder) comes round faster.
            turnRate *= (1 - GALLOP_TURN * gallopMomentum) * (1 + Mth.clamp((Math.abs(off) - 60) / 60, 0, 1.5F));
        }
        if (turnRate > 0 && !swimming && riderDrawing()) {
            // Drawn, the upper body aims and the horse body holds its line: the strafe keys steer it, and it only turns
            // after the view when the aim is further round than the upper body can twist.
            float over = Math.abs(off) - KineticSession.MAX_TWIST;
            riderLockYaw = getYRot() - player.xxa * turnRate * DRAWN_STEER + (over > 0 ? Math.signum(off) * Math.min(over, turnRate) : 0);
        } else riderLockYaw = turnRate > 0 ? Mth.approachDegrees(getYRot(), heading, turnRate) : heading;
        setYRot(riderLockYaw);
        if (swimming && turnRate > 0) {
            // The input follows the view at once (getRiddenInput); only the body eases after it.
            setXRot(Mth.approachDegrees(getXRot(), Mth.clamp(player.getXRot(), -SWIM_PITCH, SWIM_PITCH), 6));
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
            // Forward is where the rider looks, depth included; jump and dive add plain rise and fall on top.
            float forward = player.zza > 0 ? player.zza : player.zza * .25F;
            float pitch = Mth.clamp(player.getXRot(), -SWIM_PITCH, SWIM_PITCH) * Mth.DEG_TO_RAD;
            double rise = -Mth.sin(pitch) * forward + (player.isJumping() ? SWIM_LIFT : 0) - (localRiderDives && level().isClientSide() ? SWIM_LIFT : 0);
            // The surface holds the body: it cruises with its back out of the water, and only a surge leaps out.
            if (rise > 0 && surfaced() && !player.isSprinting()) rise = 0;
            return new Vec3(player.xxa * .5F, rise, Mth.cos(pitch) * forward);
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

    /** A gait that sounds its own footfalls on its phase ({@code footfalls}) makes none of vanilla's step-per-block ones. */
    private boolean ownFootfalls() {
        return getLocomotion().groundGait() != null && getLocomotion().groundGait().footfalls();
    }

    @Override
    protected void playStepSound(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (!ownFootfalls()) super.playStepSound(pos, state);
    }

    @Override
    protected void playCombinationStepSounds(net.minecraft.world.level.block.state.BlockState primary, net.minecraft.world.level.block.state.BlockState secondary) {
        if (!ownFootfalls()) super.playCombinationStepSounds(primary, secondary);
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

    @Override
    protected float getRiddenSpeed(Player player) {
        if (canSwim() && isInWater()) return (float) (getLocomotion().swimSpeed() * (1 - DigimonMoveControl.WATER_DRAG))
                * (seaMount() && player.isSprinting() ? getBody().mount().map(DigimonBody.Mount::waterSprint).orElse(1F) : 1);
        return getBody().mount().map(mount -> mount.turnRate() <= 0 ? ridePace(mount)
                // a heavy mount gathers pace, and breaks into its charge while the rider sprints
                : ridePace(mount) * (.45F + .55F * rideMomentum) * (1 + (mount.sprint() - 1) * gallopMomentum)).orElseGet(() -> super.getRiddenSpeed(player));
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
    private float gallopMomentum;
    private int leapCooldown;

    /**
     * Client, where the ridden body moves: the jump key leaps a mount that has a leap in its sheet ({@code body.mount.jump}),
     * a fixed height at a tap (Minecraft's charge-bar horse jump is the one players ask to be rid of). The run keeps
     * its speed through the air.
     */
    private void leap(Player player) {
        if (!level().isClientSide()) return;
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
    }

    /** A leap at the top of the gallop: this share of its pace added forwards, and this share more height (a little along the way, most at the top). */
    private static final float LEAP_PUSH = .3F, LEAP_LIFT = .12F, LEAP_TOP = .33F;
    /** Blocks of fall a leaping mount (and its rider) lands without harm: its own high jump, with a slope under it. */
    private static final double LEAP_SAFE_FALL = 6;

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
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

    private boolean surfaced() {
        return getFluidHeight(FluidTags.WATER) < getBbHeight() * SURFACE_DEPTH;
    }

    @Override
    public float maxUpStep() {
        return getBody().mount().map(DigimonBody.Mount::stepHeight).orElseGet(super::maxUpStep);
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
        // Raised and drawn, a shot's tile stays lit: it reloads from the shot (a sweep while held read as a reload).
        if (attack == getAnimatingAttack() && attack.kind() == DigimonAttack.Kind.KINETIC_SHOT && attackAnimationState.isStarted()
                && attackAnimationState.getTimeInMillis(tickCount) / 50F < attack.hitTick()) return 1;
        if (attack.cooldownTicks() <= 0) return 1;
        return 1 - Mth.clamp((seenCooldown(attack) - partial) / attack.cooldownTicks(), 0, 1);
    }

    /** Client: ticks until {@code attack} is ready again, from the attack starts this client has seen. */
    public int seenCooldown(DigimonAttack attack) {
        return Math.max(0, seenCooldownUntil.getOrDefault(attack.id(), 0) - tickCount);
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
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity candidate : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(reach + 1, Math.max(2, reach * .5), reach + 1),
                e -> e.isAlive() && e != this && e != rider && !(e instanceof Player) && !e.isSpectator() && canAttack(e) && !isAllyOf(e))) {
            Vec3 to = candidate.position().subtract(position()).multiply(1, 0, 1);
            double distance = Math.max(0, to.length() - candidate.getBbWidth() * .5);
            if (distance > reach || to.lengthSqr() < 1.0E-6) continue;
            double angle = Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(view), -1, 1)));
            if (angle > spec.cone() || !hasLineOfSight(candidate)) continue;
            double score = angle + distance * 6;
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
        // Only the charge fires in the air (mid-leap, its momentum carries into the jets); nothing else leaves the ground.
        if (spec.aim() == com.digicube.digimon.RiderAttack.Aim.CHARGE ? isInWater() : !onGround() && !isInWater()) return false;
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
        // The turn itself is played out by the rider's client (it owns the mount's facing), a wind-up's worth of degrees a tick.
        this.entityData.set(DATA_ATTACK_YAW, soft == null ? riderCastYaw(rider, attack) : AttackGeometry.contactYaw(attack, position(), soft.getBoundingBox().getCenter()));
        beginAttack(attack, index, soft, rider);
        return true;
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
        if (level() == null || !level().isClientSide()) return activeAttack != null && !riderMoves(activeAttack);
        if (attackAnimationState == null || !attackAnimationState.isStarted() || tickCount >= attackAnimationEndTick) return false;
        DigimonAttack attack = getAnimatingAttack();
        if (attack != null && riderMoves(attack)) return false;
        return attack == null || attack.kind() != DigimonAttack.Kind.FIST || attack.motion() == null
                || tickCount - attackAnimationStartTick <= attack.motion().activeUntil() + 3;
    }

    /** Client: the authored root travel of a swing, a ram or a bite, applied where the position is owned. Stops at walls and ledges. */
    private void riderLunge() {
        DigimonAttack attack = getAnimatingAttack();
        if (attack == null || attack.motion() == null || !onGround() || hitStopTicks > 0 || attack.kind() != DigimonAttack.Kind.FIST
                && attack.kind() != DigimonAttack.Kind.HORN_RAM && attack.kind() != DigimonAttack.Kind.FROST_BITE) return;
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
        if (DCEffects.blindTo(this, target)) return false;
        return super.canAttack(target);
    }

    /** Public view of {@link #considersEntityAsAlly} for this Digimon's own projectiles. */
    public boolean isAllyOf(Entity other) {
        return considersEntityAsAlly(other);
    }

    /** Tamer and stable-mates count as allies (no friendly fire from sweeps, no retaliation). */
    @Override
    protected boolean considersEntityAsAlly(Entity other) {
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
                : tickCount >= cooldownUntil.getOrDefault(attack.id(), 0);
    }

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
        DigimonAttack bite = attacks().stream().filter(a -> a.kind() == DigimonAttack.Kind.FROST_BITE).findFirst().orElse(null);
        DigimonAttack breath = attacks().stream().filter(a -> a.kind() == DigimonAttack.Kind.FROST_STREAM).findFirst().orElse(null);
        if (bite != null && breath != null) {
            boolean resistant = target.hasEffect(DCEffects.FROST_RESISTANCE)
                    || !target.canBeAffected(new MobEffectInstance(DCEffects.FROZEN, IceCombo.FREEZE_TICKS));
            boolean approach = canApproachForBite(target);
            return switch (IceCombo.choose(isAttackReady(bite), inRange(bite, target),
                    isAttackReady(breath), inRange(breath, target), target.hasEffect(DCEffects.ICE_MARK),
                    target.hasEffect(DCEffects.FROZEN), resistant,
                    fuelFor(breath).availableTicks() >= IceCombo.comboFuelTicks(breath.fuel()), approach)) {
                case BITE -> bite;
                case BREATH -> breath;
                case APPROACH -> null;
            };
        }
        DigimonAttack chosen = null;
        for (DigimonAttack attack : attacks()) {
            if (attack.kind() == DigimonAttack.Kind.CONSTRICTION || !usefulShot(attack, target)) continue;
            // A brawler lands its melee when it can; its opener is for the walk in. Others keep the sheet's order.
            if (chosen == null || tactics().preferClose() && attack.range() < chosen.range()) chosen = attack;
            if (!tactics().preferClose()) break;
        }
        return chosen;
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

    /** A stream without a marking bite never freezes; its contact charges Cold instead. */
    private boolean chilling() {
        return attacks().stream().noneMatch(a -> a.kind() == DigimonAttack.Kind.FROST_BITE);
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
        var bite = moves.stream().filter(a -> a.kind() == DigimonAttack.Kind.FROST_BITE).findFirst().orElse(null);
        var breath = moves.stream().filter(a -> a.kind() == DigimonAttack.Kind.FROST_STREAM).findFirst().orElse(null);
        if (bite == null || breath == null) {
            return frozen ? moves.stream().filter(a -> a.kind() != DigimonAttack.Kind.FROST_STREAM).toList() : moves;
        }
        if (frozen) return List.of(bite);
        if (target.hasEffect(DCEffects.ICE_MARK) && !target.hasEffect(DCEffects.FROST_RESISTANCE)
                && isAttackReady(breath) && fuelFor(breath).availableTicks() >= IceCombo.comboFuelTicks(breath.fuel())) {
            return List.of(breath, bite);
        }
        if (canApproachForBite(target)) return List.of(bite);
        return moves;
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
            if (other.activeAttack == move && other.attackTick <= move.hitTick()) return true;
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

    /** Plan a bite against reachable prey before spending unmarked flame, even outside current fang range. */
    private boolean canApproachForBite(LivingEntity target) {
        if (distanceToSqr(target) > 16 * 16 || Math.abs(target.getY() - getY()) > 2) return false;
        if (comboPathTargetId != target.getId() || tickCount >= comboPathCheckTick) {
            comboPathTargetId = target.getId();
            comboPathCheckTick = tickCount + 20;
            var path = getNavigation().createPath(target, 0);
            comboPathReachable = path != null && path.canReach();
        }
        return comboPathReachable;
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

    /** Rehearse the move at a prospective foot position, including its real launch/contact geometry. */
    public boolean canAttackFrom(DigimonAttack attack, LivingEntity target, Vec3 feet) {
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
        if (attack.kind() == DigimonAttack.Kind.HORN_RAM || attack.kind() == DigimonAttack.Kind.FROST_BITE || attack.kind() == DigimonAttack.Kind.FIST) {
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
                && clearAttackLine(head, mouth) && clearAttackLine(mouth, point);
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
        if (target != null && target.hasEffect(DCEffects.ICE_MARK) && !target.hasEffect(DCEffects.FROST_RESISTANCE)) {
            for (DigimonAttack attack : attacks()) {
                if (attack.kind() == DigimonAttack.Kind.FROST_STREAM && isAttackReady(attack)
                        && fuelFor(attack).availableTicks() >= IceCombo.comboFuelTicks(attack.fuel())) {
                    return attack.motion().minimumRange() + .2;
                }
            }
        }
        if (attacks().stream().anyMatch(a -> a.kind() == DigimonAttack.Kind.MELEE)) return 0.0;
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
        beginAttack(attack, index, target, null);
    }

    /**
     * Server only. The timeline's first tick, for the AI ({@code rider} null, {@code target} set) and for a rider
     * ({@code target} is the soft target or null, and the attack goes where the rider looks).
     */
    private void beginAttack(DigimonAttack attack, int index, LivingEntity target, Player rider) {
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
        iceExposure.clear();
        this.entityData.set(DATA_ATTACK_AIM_PITCH, 0.0F);
        attackMirrored = attack.alternateSides() && nextAttackMirrored;
        if (attack.alternateSides()) {
            nextAttackMirrored = !nextAttackMirrored;
        }
        if (attack.fuel() != null) { fuelFor(attack).begin(); closeInTargetId = -1; }
        else cooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
        if (rider == null) lookAt(target, 60.0F, 60.0F);
        if (kinetic != null) {
            kinetic.tick((ServerLevel) level(), 0);
            this.entityData.set(DATA_ATTACK_AIM_PITCH, kinetic.pitch());
            if (kinetic.twists()) this.entityData.set(DATA_ATTACK_YAW, kinetic.aimYaw());
        } else if (attack.kind() == DigimonAttack.Kind.BUBBLES) {
            aimBubbleBlow();
        } else if (attack.motion() != null) {
            aimAuthoredAttack();
            var summoned = com.digicube.digimon.AuthoredAttacks.get(attack);
            if (summoned != null && summoned.anchored()) aimStrikeAnchor(summoned);
            if (summoned == null || !summoned.particles().windUp((ServerLevel) level(), position(), summoned.anchored()))
            level().playSound(null, getX(), getY(), getZ(),
                    attack.fuel() != null || attack.kind() == DigimonAttack.Kind.WATER_WAVE
                            ? SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE : SoundEvents.RAVAGER_AMBIENT,
                    SoundSource.NEUTRAL, 0.65F, attack.fuel() != null ? 1.4F : 0.72F);
        }
        if (kinetic != null || attack.fuel() != null || attack.kind() == DigimonAttack.Kind.CONSTRICTION) {
            this.entityData.set(DATA_SUSTAINED_TICK, 0);
            this.entityData.set(DATA_SUSTAINED_ATTACK, attack.id().getPath());
        } else level().broadcastEntityEvent(this, DigimonAnimationEvents.start(index, attackMirrored));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if(evolutionLocked())return;
        if (canFly()) {
            if (getFlightPhase() == FlightPhase.GROUNDED && onGround() && !isInWater() && !isInLava()
                    && !(aerialMount()!=null && isVehicle())) flightReserve().rest();
            entityData.set(DATA_FLIGHT_FUEL, flightReserve().fraction());
        }
        attackFuel.values().forEach(FuelReserve::tickRecharge);
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
        if (constriction != null && !constriction.tick(attackTick)) {
            if (COMBAT_TRACE) Constants.LOG.info("[wrap-trace] {} wrap interrupted at tick {}: {}", getSpeciesId(), attackTick, constriction.interruption());
            cancelAttack();return;
        }
        if (constriction != null) syncConstrictionAnchor();
        if (activeAttack == null) return;
        // The AI's jet runs on without a live target (a blinded getaway has none); a charge whose prey is gone just runs out.
        boolean jetting = kinetic == null && this.entityData.get(DATA_RIDER_CHARGE) > 0;
        if (isVehicle() && !riderAttack || !isAlive() || (!riderAttack && !jetting && activeAttack.fuel() == null && attackTick < activeAttack.hitTick()
                && (attackTarget == null || !attackTarget.isAlive() || !canAttack(attackTarget)))) {
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
        if (activeAttack.kind() == DigimonAttack.Kind.BUBBLES) {
            aimBubbleBlow();
        } else if (activeAttack.motion() != null) {
            aimAuthoredAttack();
            getNavigation().stop();
            // A swimmer holds its depth through the performance instead of sinking under its own jet.
            setDeltaMovement(0.0, isInWater() ? 0.0 : getDeltaMovement().y, 0.0);
            if (AttackTravelSync.drivesRoot(activeAttack)) tickHornDrive(level);
            var jumping = com.digicube.digimon.AuthoredAttacks.get(activeAttack);
            if (jumping != null && jumping.leap() != null) tickLeap(level, jumping);
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
        if (activeAttack.kind() == DigimonAttack.Kind.FROST_BITE && attackTick <= activeAttack.motion().activeUntil()) {
            Vec3 fang = authoredPoint(activeAttack.motion().sample(attackTick).hornTip());
            level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, fang.x, fang.y, fang.z, 3, .16, .07, .13, .01);
        }
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
        }
    }

    /** Interrupt combat before rider controls take over; the client also resets its pose. */
    public void interruptAttack() {
        if (!level().isClientSide()) cancelAttack();
    }

    /** Interrupt combat before rider controls take over; the client also resets its pose. */
    private void cancelAttack() {
        if (activeAttack == null) return;
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
        iceExposure.clear();
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
        boolean frost = activeAttack.kind() == DigimonAttack.Kind.FROST_STREAM;
        if (frost && attackTarget != null && attackTarget.hasEffect(DCEffects.FROZEN)) {
            finishStream();
            return;
        }
        if (frost && attacks().stream().anyMatch(a -> a.kind() == DigimonAttack.Kind.FROST_BITE)
                && attackTarget != null && (!attackTarget.hasEffect(DCEffects.ICE_MARK)
                || attackTarget.hasEffect(DCEffects.FROST_RESISTANCE)) && canApproachForBite(attackTarget)) {
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
                this.entityData.get(DATA_ATTACK_AIM_PITCH), getYRot());
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
                if (frost && victim.isAlive() && chilling()) {
                    chill(level, victim);
                } else if (frost && victim.isAlive() && iceExposure.touch(victim.getUUID(), elapsed,
                        victim.hasEffect(DCEffects.ICE_MARK), victim.hasEffect(DCEffects.FROST_RESISTANCE)
                                || victim.hasEffect(DCEffects.FROZEN),
                        IceCombo.requiredContactTicks(activeAttack.fuel()))) {
                    if (victim.addEffect(new MobEffectInstance(DCEffects.FROZEN, IceCombo.FREEZE_TICKS, 0, false, true), this)) {
                        victim.removeEffect(DCEffects.ICE_MARK);
                        victim.addEffect(new MobEffectInstance(DCEffects.FROST_RESISTANCE,
                                IceCombo.RESISTANCE_TICKS, 0, false, false, true), this);
                        if (victim instanceof DigimonEntity digimon) digimon.interruptAttack();
                        frozeTarget |= victim == attackTarget;
                        level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, victim.getX(), victim.getY(.5), victim.getZ(),
                                35, victim.getBbWidth() * .55, victim.getBbHeight() * .5, victim.getBbWidth() * .55, .06);
                        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                                SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, .8F, .65F);
                    }
                }
            }
            if (pulse) level.playSound(null, stream.origin().x, stream.origin().y, stream.origin().z,
                    SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.NEUTRAL, frost ? 1.0F : .65F, frost ? .55F : .8F);
        }
        if (elapsed % 3 == 0) {
            Vec3 end = stream.end();
            level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, end.x, end.y, end.z, frost ? 8 : 3,
                    frost ? .45 : .18, frost ? .45 : .18, frost ? .45 : .18, frost ? .035 : .015);
        }
        if (frozeTarget) finishStream();
    }

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
        Vec3 mouth = position().add(frame.aimedMouth(pitch).yRot(-yaw * Mth.DEG_TO_RAD));
        Vec3 head = position().add(frame.head().yRot(-yaw * Mth.DEG_TO_RAD));
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

    /** Face the aim during anticipation, then commit to that direction through the strike. */
    private void aimAuthoredAttack() {
        boolean streaming = activeAttack.fuel() != null;
        // A rider's client owns the facing, so every rider attack commits its yaw through the synced value.
        boolean committed = riderAttack || activeAttack.kind() == DigimonAttack.Kind.GROUND_WAVE || activeAttack.kind() == DigimonAttack.Kind.FIST || com.digicube.digimon.AuthoredAttacks.handles(activeAttack);
        int aimUntil = activeAttack.hitTick() - (activeAttack.kind() == DigimonAttack.Kind.GROUND_WAVE ? 4 : committed ? 2 : 0);
        var travelling = com.digicube.digimon.AuthoredAttacks.get(activeAttack);
        // A jump is planned at launch, so the facing is settled there; a travelling combo keeps turning after its victim between blows.
        if (travelling != null && travelling.leap() != null) aimUntil = travelling.leap().launch();
        else if (travelling != null && travelling.rootTravel() && !travelling.hitWindows().isEmpty()) aimUntil = (int) travelling.hitWindows().getLast()[0] - 2;
        LivingEntity aimed = attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
        // Without a soft target a rider's shot, stream or burst goes to the point under the crosshair.
        Vec3 viewPoint = aimed == null ? riderAimNow() : null;
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
                setYRot(streaming ? Mth.approachDegrees(getYRot(), yaw,
                        attackTick < activeAttack.motion().activeFrom() ? 18.0F : 8.0F)
                        : committed && attackTick>0 ? Mth.approachDegrees(entityData.get(DATA_ATTACK_YAW),yaw,10) : yaw);
                if(committed) entityData.set(DATA_ATTACK_YAW,getYRot());
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
                float desired = FlameStream.aimPitch(activeAttack.motion().sample(aimTick), position(), authoredAimPoint, getYRot(), previous);
                this.entityData.set(DATA_ATTACK_AIM_PITCH, Mth.approach(previous, desired, 6));
            } else if (activeAttack.kind() == DigimonAttack.Kind.BOX_BURST) {
                float desired=AuthoredVolumeAttack.pitch(activeAttack,position(),authoredAimPoint,getYRot());
                // A clip whose aim starts at zero supplies its own smooth anticipation.
                // Seed its destination now; a short windup cannot otherwise reach a low target.
                this.entityData.set(DATA_ATTACK_AIM_PITCH,attackTick==0 && activeAttack.motion().sample(0).aimWeight()==0
                        ? desired : Mth.approach(this.entityData.get(DATA_ATTACK_AIM_PITCH),desired,4));
            } else if (activeAttack.kind() == DigimonAttack.Kind.FLAME_SHOT) {
                float pitch = FlameStream.aimPitch(release, position(), authoredAimPoint, getYRot(),
                        this.entityData.get(DATA_ATTACK_AIM_PITCH));
                this.entityData.set(DATA_ATTACK_AIM_PITCH, pitch);
            }
        }
        if(committed)setYRot(entityData.get(DATA_ATTACK_YAW));
        yHeadRot = yBodyRot = getYRot();
    }

    /** The flight of a jumping strike, planned at launch and flown as a closed loop so blocks still stop it. */
    private Vec3 leapFrom, leapTo;
    private void tickLeap(ServerLevel level, com.digicube.digimon.AuthoredAttacks.Definition authored) {
        var leap = authored.leap();
        if (attackTick == leap.launch()) {
            LivingEntity aimed = attackTarget != null && attackTarget.isAlive() ? attackTarget : null;
            Vec3 target = aimed != null ? aimed.position().add(aimed.getDeltaMovement().multiply(1, 0, 1).scale(Math.min(leap.land() - leap.launch(), 12))) : authoredAimPoint;
            if (target == null) { cancelAttack(); return; }
            Vec3 flat = target.subtract(position()).multiply(1, 0, 1);
            // A victim that has closed in during the wind-up is still struck from a blade's length away: a short hop back.
            Vec3 forward = new Vec3(0, 0, 1).yRot(-getYRot() * Mth.DEG_TO_RAD);
            Vec3 spot = flat.length() < leap.lead() ? target.subtract(forward.scale(leap.lead())) : position().add(flat.normalize().scale(flat.length() - leap.lead()));
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
        if (activeAttack.knockback() == 0) {
            if (hornConnected) travel = 0;
            else if (attackTarget != null) {
                // A no-knockback thrust must not push the victim through ordinary body collision either.
                travel = Math.min(travel, AttackGeometry.thrustClearance(before, attackTarget.getBoundingBox(), getBbWidth()));
            }
        }
        if (!chargeBlocked && travel > 0.0) {
            Vec3 step = new Vec3(0, 0, travel).yRot(-getYRot() * Mth.DEG_TO_RAD);
            Vec3 groundProbe = before.add(step).add(0, 0.15, 0);
            boolean groundAhead = level.clip(new ClipContext(groundProbe, groundProbe.add(0, -1.25, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS;
            if (onGround() && groundAhead) {
                move(MoverType.SELF, step);
                chargeBlocked = horizontalCollision;
            } else {
                chargeBlocked = true;
            }
        }
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
                boolean shatter = activeAttack.kind() == DigimonAttack.Kind.FROST_BITE && victim.hasEffect(DCEffects.FROZEN);
                if (shatter) damage *= IceCombo.biteMultiplier(true);
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
                    if (shatter) {
                        victim.removeEffect(DCEffects.FROZEN);
                        level.sendParticles(ParticleTypes.SNOWFLAKE, true, true, end.x, end.y, end.z, 24, .3, .3, .3, .06);
                        level.playSound(null, end.x, end.y, end.z, SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, .9F, .9F);
                    }
                    if (!shatter && activeAttack.kind() == DigimonAttack.Kind.FROST_BITE && victim.isAlive()
                            && !victim.hasEffect(DCEffects.FROST_RESISTANCE) && !victim.hasEffect(DCEffects.FROZEN)) {
                        victim.addEffect(new MobEffectInstance(DCEffects.ICE_MARK, IceCombo.MARK_TICKS, 0, false, true), this);
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
                Vec3 aim = aimed
                        ? PepperBreathEntity.predictImpactPoint(target, mouth, PepperBreathEntity.SPEED, PepperBreathEntity.MAX_AIM_LEAD)
                        : mouth.add(getViewVector(1.0F).scale(4.0));
                Vec3 direction = aim.subtract(mouth);
                // The ball's centre, where its core is drawn, leaves the mouth; the entity stands on the bottom of its box.
                Vec3 base = mouth.subtract(0, com.digicube.registry.DCEntityTypes.PEPPER_BREATH.getHeight() / 2, 0);
                PepperBreathEntity fireball = new PepperBreathEntity(level, this, base, damageAgainst(attack, target), aimed ? target : null);
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
            case HORN_RAM, FROST_BITE, FLAME_STREAM, FROST_STREAM, FIST, CONSTRICTION, BOX_SWEEP, BOX_BURST -> { /* Continuous contact is evaluated by the timeline. */ }
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
        if (!victim.isAlive() || !canAttack(victim) || isAllyOf(victim)) return false;
        float damage=damageAgainst(attack,victim)*scale;
        var authored=com.digicube.digimon.AuthoredAttacks.get(attack);
        var source=authored!=null && !authored.hitWindows().isEmpty()?DCDamageTypes.volleyAttack(this):damageSources().mobAttack(this);
        if (!victim.hurtServer(level,source,damage)) return false;
        victim.knockback(attack.knockback(),from.x-victim.getX(),from.z-victim.getZ(),source,damage);
        com.digicube.digimon.CrackMark.strike(attack,victim);
        setLastHurtMob(victim);return true;
    }

    boolean damageWithActiveAttack(LivingEntity target) {
        if (!(level() instanceof ServerLevel server) || activeAttack == null || !canAttack(target) || isAllyOf(target)) return false;
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

    /** Synced timeline also reaches players who start tracking halfway through a long breath. */
    private void syncSustainedAnimation() {
        String name = this.entityData.get(DATA_SUSTAINED_ATTACK);
        if (name.isEmpty()) {
            DigimonAttack animating = getAnimatingAttack();
            if (animating != null && (com.digicube.digimon.KineticAttacks.handles(animating) || animating.fuel() != null || animating.kind() == DigimonAttack.Kind.CONSTRICTION)) {
                if (animating.kind() == DigimonAttack.Kind.KINETIC_SHOT && attackAnimationState.isStarted()
                        && attackAnimationState.getTimeInMillis(tickCount) / 50F < animating.hitTick()) seenCooldownUntil.remove(animating.id());
                attackAnimationState.stop();
                attackAnimationName = null;
            }
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
            hitStopTicks = HIT_STOP_TICKS;
            return;
        }
        if (id == DigimonAnimationEvents.SLAM) {
            seenSlamTick = tickCount;
            return;
        }
        if (id == DigimonAnimationEvents.CONTACT) {
            attackConnected = true;
            return;
        }
        int index = DigimonAnimationEvents.attackIndex(id);
        if (index >= 0) {
            boolean mirrored = DigimonAnimationEvents.mirrored(id);
            List<DigimonAttack> attacks = attacks();
            if (index < attacks.size()) {
                DigimonAttack attack = attacks.get(index);
                attackAnimationName = attack.animationName(mirrored);
                attackAnimationEndTick = tickCount + attack.durationTicks();
                seenCooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
                attackAnimationStartTick = tickCount;
                riderStaleYaw = this.entityData.get(DATA_ATTACK_YAW);
                hitStopTicks = 0;
                swingConnected = attackConnected = false;
                attackAnimationState.start(tickCount);
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
        mountWaterAmount = Mth.approach(mountWaterAmount, isSwimmingMovement() ? 1 : 0, .08F);
        previousAttackAimPitch = this.entityData.get(DATA_ATTACK_AIM_PITCH);
        previousAttackYaw = this.entityData.get(DATA_ATTACK_YAW);
        if (aerialMount()!=null) aerialRiding().serverTick();
        if (!level().isClientSide() && aerialMount()!=null) entityData.set(DATA_FLIGHT_FUEL,flightReserve().fraction());
        super.tick();
        if(evolutionLocked())setDeltaMovement(Vec3.ZERO);
        if (level() instanceof ServerLevel serverLevel && !isEffectiveAi() && !evolutionLocked()) {
            // Vanilla skips a ridden mob's AI step, and with it the tanks' recharge and the timeline.
            attackFuel.values().forEach(FuelReserve::tickRecharge);
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
            float target = isSwimmingMovement() ? 1 : 0;
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
            swimAnimationPhase += swimAnimationAmount * Mth.lerp(swimStroke, .45F, 1.0F);
            var gait = getLocomotion().groundGait();
            if (gait != null) {
                double groundSpeed = Math.max(horizontalTravel, getDeltaMovement().horizontalDistance());
                if (attackAnimationState.isStarted() && getAnimatingAttack()!=null
                        && getAnimatingAttack().kind()==DigimonAttack.Kind.CONSTRICTION) groundSpeed=0;
                System.arraycopy(gaitShares, 0, previousGaitShares, 0, 4);
                if (gait.directional() && groundSpeed > 1.0E-4) {
                    // Which way the body moves in its own frame decides which planted clips play, and how fast the shared phase runs.
                    Vec3 moved = dx * dx + dz * dz > 1.0E-8 ? new Vec3(dx, 0, dz) : getDeltaMovement().multiply(1, 0, 1);
                    double yaw = yBodyRot * Mth.DEG_TO_RAD, scale = groundSpeed / Math.max(1.0E-6, moved.length());
                    double[] directions = gait.directions((-moved.x * Math.sin(yaw) + moved.z * Math.cos(yaw)) * scale,
                            (moved.x * Math.cos(yaw) + moved.z * Math.sin(yaw)) * scale);
                    for (int i = 0; i < 4; i++) gaitShares[i] = Mth.approach(gaitShares[i], (float) directions[i], .2F);
                    groundSpeed = directions[4];
                }
                float wanted = onGround() && !isSwimmingMovement()
                        ? (float) Mth.clamp(groundSpeed / gait.fullSpeed(getBody().modelScale()), 0, 1) : 0;
                // A ridden leap keeps its stride, held mid-air, instead of settling into the standing pose.
                if (!onGround() && rider() != null && !isInWater()) wanted = groundAnimationAmount;
                groundAnimationAmount = Mth.approach(groundAnimationAmount, wanted, .125F);
                groundRunAmount = Mth.approach(groundRunAmount, gait.runAmount(groundSpeed, getBody().modelScale()), .125F);
                if (onGround() && groundSpeed < 1) {
                    float step = gait.advance(groundSpeed, groundAnimationAmount, getBody().modelScale(), groundRunAmount);
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
            if (getLocomotion().groundGait() != null) tickLeapPose(dy, horizontalTravel);
        }
        if (level().isClientSide() && attackAnimationState.isStarted() && tickCount >= attackAnimationEndTick) {
            attackAnimationState.stop();
            attackAnimationName = null;
        }
    }

    /** Harness animation name currently playing on the client, or null when idle. */
    public String getAttackAnimationName() {
        return attackAnimationName;
    }

    /**
     * Attack data for the current client animation.
     * @return attack definition, or null when idle
     */
    public DigimonAttack getActiveAttack() { return activeAttack; }

    public DigimonAttack getAnimatingAttack() {
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
        ValueInput fuel = input.childOrEmpty("AttackFuel");
        // Inactive-form reserves must survive recall/restart too; otherwise cycling and saving refills them.
        for (DigimonAttack attack : DigimonSpeciesRegistry.all().stream().flatMap(species->species.attacks().stream()).distinct().toList()) {
            int remaining = cooldowns.getIntOr(attack.id().toString(), 0);
            if (remaining > 0) cooldownUntil.put(attack.id(), tickCount + remaining);
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
