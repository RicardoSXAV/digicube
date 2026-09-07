package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.AttackMotion;
import com.digicube.digimon.FuelReserve;
import com.digicube.registry.DCDamageTypes;
import com.digicube.digimon.DigimonBody;
import com.digicube.digimon.DigimonLocomotion;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.ai.DigimonAttackGoal;
import com.digicube.entity.ai.DigimonLookControl;
import com.digicube.entity.ai.DigimonMoveControl;
import com.digicube.entity.ai.FollowOwnerGoal;
import com.digicube.entity.ai.OwnerHurtByTargetGoal;
import com.digicube.entity.ai.OwnerHurtTargetGoal;
import com.digicube.party.PartyManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityReference;
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
 * <p>Summon a wild one with {@code /digicube spawn agumon}; a partner with
 * {@code /digicube give agumon}.
 */
public class DigimonEntity extends PathfinderMob implements OwnableEntity, PlayerRideable {

    /** NBT key holding the species id. Changing it is a save-data migration. */
    public static final String SPECIES_TAG = "Species";
    /** NBT key holding the owner reference. Changing it is a save-data migration. */
    public static final String OWNER_TAG = "Owner";

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
    private static final EntityDataAccessor<Optional<EntityReference<LivingEntity>>> DATA_OWNER =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE);
    private static final EntityDataAccessor<Boolean> DATA_RUNNING_TO_OWNER =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_SUSTAINED_ATTACK =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_SUSTAINED_TICK =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.INT);

    // --- server-side combat state ---------------------------------------------------
    private DigimonAttack activeAttack;
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
    /** Attack id -> {@link #tickCount} at which it may be used again. */
    private final Map<Identifier, Integer> cooldownUntil = new HashMap<>();
    private final Map<Identifier, FuelReserve> attackFuel = new HashMap<>();
    private long partyGeneration;

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
    private float swimBank;
    private float landWaterMalus;
    private float landWaterBorderMalus;

    public DigimonEntity(EntityType<? extends DigimonEntity> type, Level level) {
        super(type, level);
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
        this.goalSelector.addGoal(1, new WildPanicGoal(this, 1.4));
        this.goalSelector.addGoal(2, new DigimonAttackGoal(this, 1.25));
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
        builder.define(DATA_SPECIES, DEFAULT_SPECIES.toString());
        builder.define(DATA_ATTACK_AIM_PITCH, 0.0F);
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_RUNNING_TO_OWNER, false);
        builder.define(DATA_SUSTAINED_ATTACK, "");
        builder.define(DATA_SUSTAINED_TICK, 0);
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

    /**
     * Keep the walking pose in very shallow water at the shore.
     * @return whether the body is immersed enough to adopt its swimming pose
     */
    public boolean isSwimmingMovement() {
        return canSwim() && isInWater() && getFluidHeight(FluidTags.WATER) > getBbHeight() * 0.35;
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

    private void configureSpeciesMovement() {
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(getSpecies().map(DigimonSpecies::baseSpeed).orElse(.3F));
        if (canSwim() && !(this.moveControl instanceof DigimonMoveControl)) {
            this.moveControl = new DigimonMoveControl(this);
            this.lookControl = new DigimonLookControl(this);
        } else if (!canSwim() && this.moveControl instanceof DigimonMoveControl) {
            this.moveControl = new MoveControl<>(this);
            this.lookControl = new LookControl(this);
        }
        boolean amphibious = getNavigation() instanceof AmphibiousPathNavigation;
        if (canSwim() && !amphibious) {
            landWaterMalus = getPathfindingMalus(PathType.WATER);
            landWaterBorderMalus = getPathfindingMalus(PathType.WATER_BORDER);
            getNavigation().stop();
            this.navigation = new AmphibiousPathNavigation(this, level());
            setPathfindingMalus(PathType.WATER, 0);
            setPathfindingMalus(PathType.WATER_BORDER, 0);
        } else if (!canSwim() && amphibious) {
            getNavigation().stop();
            this.navigation = super.createNavigation(level());
            setPathfindingMalus(PathType.WATER, landWaterMalus);
            setPathfindingMalus(PathType.WATER_BORDER, landWaterBorderMalus);
            setXRot(0);
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
        return this.entityData == null ? super.getDefaultDimensions(pose) : getBody().dimensions();
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (level().isClientSide() && (DATA_SUSTAINED_ATTACK.equals(accessor) || DATA_SUSTAINED_TICK.equals(accessor))) {
            syncSustainedAnimation();
        }
        if (DATA_SPECIES.equals(accessor)) {
            refreshDimensions();
            if (!level().isClientSide()) configureSpeciesMovement();
            if (!level().isClientSide() && getBody().mount().isEmpty()) {
                ejectPassengers();
            }
        }
    }

    // --- riding: species data supplies the seat; vanilla handles movement packets -----

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (getBody().mount().isPresent() && isOwnedBy(player) && !isVehicle()
                && !player.isSecondaryUseActive()) {
            if (level().isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            if (player.startRiding(this)) {
                cancelAttack();
                getNavigation().stop();
                setTarget(null);
                return InteractionResult.SUCCESS_SERVER;
            }
        }
        return super.mobInteract(player, hand);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getBody().mount().isPresent() && !isVehicle()
                && passenger instanceof Player player && isOwnedBy(player)
                && super.canAddPassenger(passenger);
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return getBody().mount().isPresent() && getFirstPassenger() instanceof Player player
                && isOwnedBy(player) ? player : null;
    }

    @Override
    protected void tickRidden(Player player, Vec3 input) {
        super.tickRidden(player, input);
        setYRot(player.getYRot());
        setXRot(player.getXRot() * 0.35F);
        yBodyRot = getYRot();
        yHeadRot = getYRot();
    }

    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 input) {
        return new Vec3(player.xxa * 0.5F, 0.0, player.zza > 0.0F ? player.zza : player.zza * 0.25F);
    }

    @Override
    protected float getRiddenSpeed(Player player) {
        return getBody().mount().map(DigimonBody.Mount::speed).orElseGet(() -> super.getRiddenSpeed(player));
    }

    @Override
    public float maxUpStep() {
        return getBody().mount().map(DigimonBody.Mount::stepHeight).orElseGet(super::maxUpStep);
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return getBody().mount()
                .map(mount -> mount.seat().scale(scale).yRot(-getYRot() * Mth.DEG_TO_RAD))
                .orElseGet(() -> super.getPassengerAttachmentPoint(passenger, dimensions, scale));
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
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

    /** Reserve movement and look controls while the tamer drives the vanilla ridden path. */
    private final class RiderControlGoal extends Goal {
        RiderControlGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return getControllingPassenger() != null;
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

    // --- combat: choosing and running attacks ---------------------------------------

    public boolean hasAttacks() {
        return !attacks().isEmpty();
    }

    public boolean isAttacking() {
        return activeAttack != null;
    }

    public boolean isAttackReady(DigimonAttack attack) {
        return attack.fuel() != null ? fuelFor(attack).isReady()
                : tickCount >= cooldownUntil.getOrDefault(attack.id(), 0);
    }

    private FuelReserve fuelFor(DigimonAttack attack) {
        return attackFuel.computeIfAbsent(attack.id(), id -> new FuelReserve(attack.fuel()));
    }

    /**
     * The first attack in species order that is off cooldown and can reach {@code target}
     * right now, or null. Species order is priority order, so Pepper Breath wins over the
     * claws whenever it is ready and the target is in sight.
     */
    public DigimonAttack chooseAttack(LivingEntity target) {
        if (isVehicle() || isAttacking() || target == null || !target.isAlive() || !canAttack(target)) return null;
        for (DigimonAttack attack : attacks()) {
            if (isAttackReady(attack) && inRange(attack, target)) {
                return attack;
            }
        }
        return null;
    }

    private boolean inRange(DigimonAttack attack, LivingEntity target) {
        return switch (attack.kind()) {
            case MELEE -> isWithinMeleeAttackRange(target);
            case FIREBALL, BUBBLES -> distanceToSqr(target) <= attack.range() * attack.range()
                    && getSensing().hasLineOfSight(target);
            case FLAME_SHOT, HORN_RAM, FLAME_STREAM, WATER_WAVE -> distanceToSqr(target) <= attack.range() * attack.range()
                    && distanceToSqr(target) >= attack.motion().minimumRange() * attack.motion().minimumRange()
                    && getSensing().hasLineOfSight(target) && (attack.isRanged() || onGround());
        };
    }

    /**
     * Space needed to bring an authored snout or horn to bear on a nearby target.
     * @return minimum usable distance in blocks, or zero for ordinary melee
     */
    public double minimumAttackSpacing() {
        if (attacks().stream().anyMatch(a -> a.kind() == DigimonAttack.Kind.MELEE)) return 0.0;
        return attacks().stream().filter(a -> a.motion() != null)
                .mapToDouble(a -> a.motion().minimumRange()).min().orElse(0.0);
    }

    /** Server only. Begins the attack timeline and tells clients to animate it. */
    public void startAttack(DigimonAttack attack, LivingEntity target) {
        if (level().isClientSide() || isVehicle() || activeAttack != null || target == null
                || !target.isAlive() || !canAttack(target) || !isAttackReady(attack) || !inRange(attack, target)) return;
        List<DigimonAttack> attacks = attacks();
        int index = attacks.indexOf(attack);
        if (index < 0 || index >= DigimonAnimationEvents.MAX_ATTACKS) {
            Constants.LOG.warn("{} cannot use {}: not in its attack list", getSpeciesId(), attack.id());
            return;
        }
        activeAttack = attack;
        attackTarget = target;
        attackTick = 0;
        bubbleAimPoint = null;
        authoredAimPoint = null;
        hornConnected = chargeBlocked = false;
        this.entityData.set(DATA_ATTACK_AIM_PITCH, 0.0F);
        attackMirrored = attack.alternateSides() && nextAttackMirrored;
        if (attack.alternateSides()) {
            nextAttackMirrored = !nextAttackMirrored;
        }
        if (attack.fuel() != null) fuelFor(attack).begin();
        else cooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
        lookAt(target, 60.0F, 60.0F);
        if (attack.kind() == DigimonAttack.Kind.BUBBLES) {
            aimBubbleBlow();
        } else if (attack.motion() != null) {
            aimAuthoredAttack();
            level().playSound(null, getX(), getY(), getZ(),
                    attack.fuel() != null || attack.kind() == DigimonAttack.Kind.WATER_WAVE
                            ? SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE : SoundEvents.RAVAGER_AMBIENT,
                    SoundSource.NEUTRAL, 0.65F, attack.fuel() != null ? 1.4F : 0.72F);
        }
        if (attack.fuel() != null) {
            this.entityData.set(DATA_SUSTAINED_TICK, 0);
            this.entityData.set(DATA_SUSTAINED_ATTACK, attack.id().getPath());
        } else level().broadcastEntityEvent(this, DigimonAnimationEvents.start(index, attackMirrored));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        attackFuel.values().forEach(FuelReserve::tickRecharge);
        if (activeAttack == null) {
            return;
        }
        if (isVehicle() || !isAlive() || (activeAttack.fuel() == null && attackTick < activeAttack.hitTick()
                && (attackTarget == null || !attackTarget.isAlive() || !canAttack(attackTarget)))) {
            cancelAttack();
            return;
        }
        if (activeAttack.fuel() != null && attackTick <= activeAttack.motion().activeUntil()
                && (attackTarget == null || !attackTarget.isAlive() || !canAttack(attackTarget)
                || isAllyOf(attackTarget) || !inRange(activeAttack, attackTarget))) {
            finishStream();
        }
        if (activeAttack.kind() == DigimonAttack.Kind.BUBBLES) {
            aimBubbleBlow();
        } else if (activeAttack.motion() != null) {
            aimAuthoredAttack();
            getNavigation().stop();
            setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
            if (activeAttack.kind() == DigimonAttack.Kind.HORN_RAM) tickHornDrive(level);
        } else if (attackTarget != null && attackTarget.isAlive()) {
            getLookControl().setLookAt(attackTarget, 30.0F, 30.0F);
        }
        if (activeAttack.kind() == DigimonAttack.Kind.FIREBALL) {
            tickFireballCharge(level);
        }
        if (activeAttack.kind() == DigimonAttack.Kind.FLAME_STREAM) tickFlameStream(level);
        if (attackTick == activeAttack.hitTick()) {
            deliver(level, activeAttack);
        }
        attackTick++;
        if (activeAttack.fuel() != null) {
            if (attackTick > activeAttack.motion().activeUntil()) fuelFor(activeAttack).end();
            this.entityData.set(DATA_SUSTAINED_TICK, attackTick);
        }
        if (attackTick >= activeAttack.durationTicks()) {
            if (activeAttack.fuel() != null) this.entityData.set(DATA_SUSTAINED_ATTACK, "");
            activeAttack = null;
            attackTarget = null;
            bubbleAimPoint = null;
            authoredAimPoint = null;
        }
    }

    /** Interrupt combat before rider controls take over; the client also resets its pose. */
    private void cancelAttack() {
        if (activeAttack == null) return;
        if (activeAttack.fuel() != null) {
            fuelFor(activeAttack).end();
            this.entityData.set(DATA_SUSTAINED_ATTACK, "");
        }
        activeAttack = null;
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
        if (elapsed % activeAttack.fuel().damageIntervalTicks() == 0) {
            for (Entity entity : level.getEntities(this, stream.bounds(),
                    e -> e instanceof LivingEntity living && living.isAlive() && canAttack(living) && !isAllyOf(living))) {
                LivingEntity victim = (LivingEntity) entity;
                if (!stream.intersects(victim.getBoundingBox())) continue;
                // Test the actual victim too: a corner of its broadphase box may be behind cover.
                Vec3 contact = victim.getBoundingBox().clip(stream.origin(), stream.end())
                        .orElseGet(() -> victim.getBoundingBox().getCenter());
                if (level.clip(new ClipContext(stream.origin(), contact, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) continue;
                if (victim.hurtServer(level, DCDamageTypes.partnerAttack(this), damageAgainst(activeAttack, victim))) {
                    setLastHurtMob(victim);
                }
            }
            level.playSound(null, stream.origin().x, stream.origin().y, stream.origin().z,
                    SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.NEUTRAL, 0.65F, 0.8F);
        }
        if (elapsed % 3 == 0) {
            Vec3 end = stream.end();
            level.sendParticles(ParticleTypes.SNOWFLAKE, end.x, end.y, end.z, 3, .18, .18, .18, .015);
        }
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

    /** Face the aim during anticipation, then commit to that direction through the strike. */
    private void aimAuthoredAttack() {
        boolean streaming = activeAttack.kind() == DigimonAttack.Kind.FLAME_STREAM;
        if ((attackTick <= activeAttack.hitTick() || streaming && attackTick <= activeAttack.motion().activeUntil())
                && attackTarget != null && attackTarget.isAlive()) {
            AttackMotion.Frame release = activeAttack.motion().sample(streaming ? attackTick : activeAttack.hitTick());
            Vec3 origin = authoredPoint(release.mouth());
            authoredAimPoint = activeAttack.kind() == DigimonAttack.Kind.FLAME_SHOT
                    ? PepperBreathEntity.predictImpactPoint(attackTarget, origin, MegaFlameEntity.SPEED, MegaFlameEntity.MAX_AIM_LEAD)
                    : activeAttack.kind() == DigimonAttack.Kind.WATER_WAVE
                    ? MarchingFishesEntity.aimPoint(attackTarget, origin)
                    : attackTarget.getBoundingBox().getCenter();
            Vec3 direction = authoredAimPoint.subtract(position());
            if (direction.horizontalDistanceSqr() > 1.0E-8) {
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                setYRot(streaming ? Mth.approachDegrees(getYRot(), yaw,
                        attackTick < activeAttack.motion().activeFrom() ? 18.0F : 8.0F) : yaw);
            }
            if (streaming) {
                float previous = this.entityData.get(DATA_ATTACK_AIM_PITCH);
                float desired = FlameStream.aimPitch(release, position(), authoredAimPoint, getYRot(), previous);
                this.entityData.set(DATA_ATTACK_AIM_PITCH, Mth.approach(previous, desired, 6));
            } else if (activeAttack.kind() == DigimonAttack.Kind.FLAME_SHOT) {
                float pitch = this.entityData.get(DATA_ATTACK_AIM_PITCH);
                // The mouth moves around the head pivot as it aims: solve that offset too.
                for (int i = 0; i < 4; i++) {
                    Vec3 aim = authoredAimPoint.subtract(authoredPoint(release.aimedMouth(pitch)));
                    pitch = Mth.clamp((float) Math.toDegrees(Math.atan2(-aim.y, aim.horizontalDistance()))
                            - release.headPitch(), -30.0F, 45.0F);
                }
                this.entityData.set(DATA_ATTACK_AIM_PITCH, pitch);
            }
        }
        yHeadRot = yBodyRot = getYRot();
    }

    /** Move only by the exported root displacement; vanilla collision resolves walls. */
    private void tickHornDrive(ServerLevel level) {
        AttackMotion motion = activeAttack.motion();
        Vec3 before = position();
        double travel = motion.sample(attackTick + 1).travel() - motion.sample(attackTick).travel();
        if (activeAttack.knockback() == 0) {
            if (hornConnected) travel = 0;
            else if (attackTarget != null) {
                // A no-knockback thrust must not push the victim through ordinary body collision either.
                double clearance = attackTarget.position().subtract(before).horizontalDistance()
                        - (getBbWidth() + attackTarget.getBbWidth()) * 0.5 - 0.08;
                travel = Math.min(travel, Math.max(0, clearance));
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
        // Subdivide the moving horn, not an arbitrary melee radius around the feet.
        for (int i = 0; i <= 4 && !hornConnected; i++) {
            double partial = i / 4.0;
            AttackMotion.Frame frame = motion.sample(attackTick + partial);
            Vec3 at = before.lerp(position(), partial);
            Vec3 base = at.add(frame.hornBase().yRot(-getYRot() * Mth.DEG_TO_RAD));
            Vec3 tip = at.add(frame.hornTip().yRot(-getYRot() * Mth.DEG_TO_RAD));
            AABB region = new AABB(base, tip).inflate(motion.contactRadius());
            for (Entity entity : level.getEntities(this, region,
                    e -> e instanceof LivingEntity living && living.isAlive() && canAttack(living) && !isAllyOf(living))) {
                var contact = entity.getBoundingBox().inflate(motion.contactRadius()).clip(base, tip);
                if (!entity.getBoundingBox().inflate(motion.contactRadius()).contains(base) && contact.isEmpty()) continue;
                Vec3 end = contact.orElse(base);
                if (level.clip(new ClipContext(authoredPoint(frame.head()), end,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) continue;
                LivingEntity victim = (LivingEntity) entity;
                float damage = damageAgainst(activeAttack, victim);
                var source = activeAttack.knockback() == 0 ? DCDamageTypes.partnerAttack(this) : damageSources().mobAttack(this);
                if (victim.hurtServer(level, source, damage)) {
                    if (activeAttack.knockback() > 0) {
                        victim.knockback(activeAttack.knockback(), getX() - victim.getX(), getZ() - victim.getZ(), source, damage);
                    }
                    setLastHurtMob(victim);
                    level.playSound(null, end.x, end.y, end.z,
                            activeAttack.knockback() > 0 ? SoundEvents.PLAYER_ATTACK_KNOCKBACK : SoundEvents.PLAYER_ATTACK_STRONG,
                            SoundSource.NEUTRAL, activeAttack.knockback() > 0 ? 1.0F : 0.8F,
                            activeAttack.knockback() > 0 ? 0.65F : 1.1F);
                    level.sendParticles(ParticleTypes.CRIT, end.x, end.y, end.z, 12, .2, .2, .2, .08);
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
        Vec3 origin = position().add(0.0, BUBBLE_MOUTH_HEIGHT, 0.0);
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

    /** Embers gather at the mouth while Agumon inhales, then a whoosh as it fires. */
    private void tickFireballCharge(ServerLevel level) {
        int ticksToFire = activeAttack.hitTick() - attackTick;
        if (ticksToFire > FIREBALL_CHARGE_TICKS || ticksToFire < 0) {
            return;
        }
        Vec3 mouth = mouthPosition();
        int count = 1 + (FIREBALL_CHARGE_TICKS - ticksToFire) / 2;
        level.sendParticles(ParticleTypes.SMALL_FLAME, mouth.x, mouth.y, mouth.z, count, 0.12, 0.08, 0.12, 0.01);
        if (ticksToFire == FIREBALL_CHARGE_TICKS) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.BLAZE_AMBIENT, SoundSource.NEUTRAL, 0.6F, 1.4F);
        }
    }

    private void deliver(ServerLevel level, DigimonAttack attack) {
        LivingEntity target = attackTarget;
        switch (attack.kind()) {
            case MELEE -> {
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 0.8F, 1.1F);
                if (target == null || !target.isAlive() || !canAttack(target) || isAllyOf(target)
                        || !isWithinMeleeAttackRange(target) || !getSensing().hasLineOfSight(target)) {
                    return;
                }
                if (target.hurtServer(level, damageSources().mobAttack(this), damageAgainst(attack, target))) {
                    setLastHurtMob(target);
                    level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                            target.getX(), target.getY(0.5), target.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
            case FIREBALL -> {
                Vec3 mouth = mouthPosition();
                boolean aimed = target != null && target.isAlive();
                Vec3 aim = aimed
                        ? PepperBreathEntity.predictImpactPoint(target, mouth, PepperBreathEntity.SPEED, PepperBreathEntity.MAX_AIM_LEAD)
                        : mouth.add(getViewVector(1.0F).scale(4.0));
                Vec3 direction = aim.subtract(mouth);
                PepperBreathEntity fireball = new PepperBreathEntity(level, this, mouth, damageAgainst(attack, target), aimed ? target : null);
                fireball.shoot(direction.x, direction.y, direction.z, PepperBreathEntity.SPEED, 0.0F);
                level.addFreshEntity(fireball);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.15F);
                level.sendParticles(ParticleTypes.FLAME, mouth.x, mouth.y, mouth.z, 12, 0.2, 0.2, 0.2, 0.1);
            }
            case BUBBLES -> {
                // Use the same facing and predicted point as the windup, with the
                // mouth offset along that axis so the volley cannot leave sideways.
                double yaw = Math.toRadians(getYRot());
                Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
                Vec3 mouth = position().add(0.0, BUBBLE_MOUTH_HEIGHT, 0.0).add(forward.scale(BUBBLE_MOUTH_FORWARD));
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
                level.sendParticles(ParticleTypes.SPLASH, origin.x, origin.y, origin.z,
                        18, 0.5, 0.15, 0.3, 0.08);
            }
            case HORN_RAM, FLAME_STREAM -> { /* Continuous contact is evaluated by the timeline. */ }
        }
    }

    /** Attack power times this Digimon's attack attribute, with the attribute triangle vs other Digimon. */
    private float damageAgainst(DigimonAttack attack, LivingEntity target) {
        float damage = (float) (getAttributeValue(Attributes.ATTACK_DAMAGE) * attack.power());
        if (target instanceof DigimonEntity other) {
            Optional<DigimonSpecies> mine = getSpecies();
            Optional<DigimonSpecies> theirs = other.getSpecies();
            if (mine.isPresent() && theirs.isPresent()) {
                damage *= mine.get().attribute().damageMultiplierAgainst(theirs.get().attribute());
            }
        }
        return damage;
    }

    /** World position of the snout, following the body's facing. */
    private Vec3 mouthPosition() {
        double yaw = Math.toRadians(yBodyRot);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        return position().add(0.0, MOUTH_HEIGHT, 0.0).add(forward.scale(MOUTH_FORWARD));
    }

    // --- client-side animation -------------------------------------------------------

    /** Synced timeline also reaches players who start tracking halfway through a long breath. */
    private void syncSustainedAnimation() {
        String name = this.entityData.get(DATA_SUSTAINED_ATTACK);
        if (name.isEmpty()) {
            DigimonAttack animating = getAnimatingAttack();
            if (animating != null && animating.fuel() != null) {
                attackAnimationState.stop();
                attackAnimationName = null;
            }
            return;
        }
        for (DigimonAttack attack : attacks()) {
            if (attack.fuel() != null && attack.id().getPath().equals(name)) {
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
        int index = DigimonAnimationEvents.attackIndex(id);
        if (index >= 0) {
            boolean mirrored = DigimonAnimationEvents.mirrored(id);
            List<DigimonAttack> attacks = attacks();
            if (index < attacks.size()) {
                DigimonAttack attack = attacks.get(index);
                attackAnimationName = attack.animationName(mirrored);
                attackAnimationEndTick = tickCount + attack.durationTicks();
                attackAnimationState.start(tickCount);
            }
            return;
        }
        super.handleEntityEvent(id);
    }

    @Override
    public void tick() {
        if (level() instanceof ServerLevel serverLevel && !PartyManager.beforeEntityTick(this, serverLevel)) return;
        previousAttackAimPitch = this.entityData.get(DATA_ATTACK_AIM_PITCH);
        super.tick();
        if (!level().isClientSide() && !isAlive()) cancelAttack();
        if (level().isClientSide()) {
            previousRunAnimationAmount = runAnimationAmount;
            runAnimationAmount = Mth.approach(runAnimationAmount, isRunningToOwner() ? 1.0F : 0.0F, 0.2F);
            previousSwimAnimationAmount = swimAnimationAmount;
            previousSwimAnimationPhase = swimAnimationPhase;
            previousSwimMotionAmount = swimMotionAmount;
            previousGroundAnimationPhase = groundAnimationPhase;
            previousSwimBank = swimBank;
            float target = isSwimmingMovement() ? 1 : 0;
            swimAnimationAmount = Mth.approach(swimAnimationAmount, target, target > swimAnimationAmount ? .08F : .10F);
            double dx = getX() - xo;
            double dy = getY() - yo;
            double dz = getZ() - zo;
            double horizontalTravel = Math.sqrt(dx * dx + dz * dz);
            double travelled = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double speed = Math.max(travelled, getDeltaMovement().length());
            float motion = canSwim() ? (float) Mth.clamp(speed / (getLocomotion().swimSpeed() * .7), 0, 1) : 0;
            swimMotionAmount = Mth.lerp(.15F, swimMotionAmount, motion);
            swimAnimationPhase += swimAnimationAmount * Mth.lerp(swimMotionAmount, .45F, 1.0F);
            if (canSwim() && onGround()) {
                // Remote entities can move through position interpolation between
                // velocity packets; keep the paws moving with that displacement too.
                double groundSpeed = Math.max(horizontalTravel, Math.sqrt(getDeltaMovement().horizontalDistanceSqr()));
                groundAnimationPhase += .55F * (float) Mth.clamp(groundSpeed / .025, 0, 1);
            }
            swimBank = Mth.lerp(.15F, swimBank, Mth.clamp(-Mth.wrapDegrees(getYRot() - yRotO) * 2.0F, -12, 12));
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
    public DigimonAttack getAnimatingAttack() {
        return attacks().stream().filter(a -> a.animationName(false).equals(attackAnimationName)
                || a.animationName(true).equals(attackAnimationName)).findFirst().orElse(null);
    }

    /**
     * Interpolated server-authoritative head aim, relative to the authored attack pose.
     * @param partialTick fraction between entity ticks
     * @return additional downward head pitch in degrees
     */
    public float getAttackAimPitch(float partialTick) {
        return Mth.lerp(partialTick, previousAttackAimPitch, this.entityData.get(DATA_ATTACK_AIM_PITCH));
    }

    // --- persistence -----------------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString(SPECIES_TAG, getSpeciesId().toString());
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
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        Identifier speciesId = Identifier.tryParse(input.getStringOr(SPECIES_TAG, DEFAULT_SPECIES.toString()));
        setSpecies(speciesId != null ? speciesId : DEFAULT_SPECIES);
        EntityReference<LivingEntity> owner = EntityReference.read(input, OWNER_TAG);
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
        partyGeneration = input.getLongOr("PartyGeneration", 0L);
        cooldownUntil.clear();
        attackFuel.clear();
        ValueInput cooldowns = input.childOrEmpty("AttackCooldowns");
        ValueInput fuel = input.childOrEmpty("AttackFuel");
        for (DigimonAttack attack : attacks()) {
            int remaining = cooldowns.getIntOr(attack.id().toString(), 0);
            if (remaining > 0) cooldownUntil.put(attack.id(), tickCount + remaining);
            if (attack.fuel() != null) {
                FuelReserve reserve = fuelFor(attack);
                ValueInput tank = fuel.childOrEmpty(attack.id().toString());
                reserve.restore(tank.getIntOr("Charge", reserve.savedCharge()), tank.getBooleanOr("Recharging", false));
            }
        }
    }

    /** Name plates, death messages and the like show the species name, not "Digimon". */
    @Override
    protected Component getTypeName() {
        return getSpecies()
                .map(species -> (Component) Component.translatable(species.translationKey()))
                .orElseGet(super::getTypeName);
    }

    /** Wild Digimon flee when hurt; partners stand and fight. */
    private static class WildPanicGoal extends PanicGoal {

        private final DigimonEntity digimon;

        WildPanicGoal(DigimonEntity digimon, double speedModifier) {
            super(digimon, speedModifier);
            this.digimon = digimon;
        }

        @Override
        protected boolean shouldPanic() {
            return !digimon.isOwned() && super.shouldPanic();
        }
    }
}
