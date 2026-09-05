package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.ai.DigimonAttackGoal;
import com.digicube.entity.ai.FollowOwnerGoal;
import com.digicube.entity.ai.OwnerHurtByTargetGoal;
import com.digicube.entity.ai.OwnerHurtTargetGoal;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
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
 * {@code /givedigimon agumon}.
 */
public class DigimonEntity extends PathfinderMob implements OwnableEntity {

    /** NBT key holding the species id. Changing it is a save-data migration. */
    public static final String SPECIES_TAG = "Species";
    /** NBT key holding the owner reference. Changing it is a save-data migration. */
    public static final String OWNER_TAG = "Owner";

    /** Species used when none was given, e.g. a plain {@code /summon digicube:digimon}. */
    public static final Identifier DEFAULT_SPECIES = Constants.id("agumon");

    /**
     * Entity events {@code 64 + attackIndex} start an attack animation on clients; adding
     * {@link #ATTACK_EVENT_MIRROR} picks the mirrored (other-hand) variant. Vanilla's own
     * events stop well below 64.
     */
    private static final byte ATTACK_EVENT_BASE = 64;
    private static final byte ATTACK_EVENT_MIRROR = 16;
    private static final int MAX_ATTACKS_PER_SPECIES = ATTACK_EVENT_MIRROR;

    /** Where Pepper Breath leaves the model, relative to the feet: mouth height and snout reach. */
    private static final double MOUTH_HEIGHT = 0.95;
    private static final double MOUTH_FORWARD = 0.6;
    private static final int FIREBALL_CHARGE_TICKS = 8;

    private static final EntityDataAccessor<String> DATA_SPECIES =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<EntityReference<LivingEntity>>> DATA_OWNER =
            SynchedEntityData.defineId(DigimonEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE);

    // --- server-side combat state ---------------------------------------------------
    private DigimonAttack activeAttack;
    private LivingEntity attackTarget;
    private int attackTick;
    private boolean attackMirrored;
    private boolean nextAttackMirrored;
    /** Attack id -> {@link #tickCount} at which it may be used again. */
    private final Map<Identifier, Integer> cooldownUntil = new HashMap<>();

    // --- client-side animation state ------------------------------------------------
    /** Started by {@link #handleEntityEvent}; read by the renderer. Meaningful on the client only. */
    public final AnimationState attackAnimationState = new AnimationState();
    private String attackAnimationName;
    private int attackAnimationEndTick;

    public DigimonEntity(EntityType<? extends DigimonEntity> type, Level level) {
        super(type, level);
    }

    /** Placeholder attributes; per-species stats will be applied on top once levels exist. */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new WildPanicGoal(this, 1.4));
        this.goalSelector.addGoal(2, new DigimonAttackGoal(this, 1.25));
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.15, 10.0F, 3.0F));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
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
        builder.define(DATA_OWNER, Optional.empty());
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
        return tickCount >= cooldownUntil.getOrDefault(attack.id(), 0);
    }

    /**
     * The first attack in species order that is off cooldown and can reach {@code target}
     * right now, or null. Species order is priority order, so Pepper Breath wins over the
     * claws whenever it is ready and the target is in sight.
     */
    public DigimonAttack chooseAttack(LivingEntity target) {
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
            case FIREBALL -> distanceToSqr(target) <= attack.range() * attack.range()
                    && getSensing().hasLineOfSight(target);
        };
    }

    /** Server only. Begins the attack timeline and tells clients to animate it. */
    public void startAttack(DigimonAttack attack, LivingEntity target) {
        List<DigimonAttack> attacks = attacks();
        int index = attacks.indexOf(attack);
        if (index < 0 || index >= MAX_ATTACKS_PER_SPECIES) {
            Constants.LOG.warn("{} cannot use {}: not in its attack list", getSpeciesId(), attack.id());
            return;
        }
        activeAttack = attack;
        attackTarget = target;
        attackTick = 0;
        attackMirrored = attack.alternateSides() && nextAttackMirrored;
        if (attack.alternateSides()) {
            nextAttackMirrored = !nextAttackMirrored;
        }
        cooldownUntil.put(attack.id(), tickCount + attack.cooldownTicks());
        lookAt(target, 60.0F, 60.0F);
        level().broadcastEntityEvent(this, (byte) (ATTACK_EVENT_BASE + index + (attackMirrored ? ATTACK_EVENT_MIRROR : 0)));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (activeAttack == null) {
            return;
        }
        if (attackTarget != null && attackTarget.isAlive()) {
            getLookControl().setLookAt(attackTarget, 30.0F, 30.0F);
        }
        if (activeAttack.kind() == DigimonAttack.Kind.FIREBALL) {
            tickFireballCharge(level);
        }
        if (attackTick == activeAttack.hitTick()) {
            deliver(level, activeAttack);
        }
        attackTick++;
        if (attackTick >= activeAttack.durationTicks()) {
            activeAttack = null;
            attackTarget = null;
        }
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
                if (target == null || !target.isAlive() || !isWithinMeleeAttackRange(target)) {
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

    @Override
    public void handleEntityEvent(byte id) {
        int offset = id - ATTACK_EVENT_BASE;
        if (offset >= 0 && offset < 2 * ATTACK_EVENT_MIRROR) {
            boolean mirrored = (offset & ATTACK_EVENT_MIRROR) != 0;
            int index = offset & (ATTACK_EVENT_MIRROR - 1);
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
        super.tick();
        if (level().isClientSide() && attackAnimationState.isStarted() && tickCount >= attackAnimationEndTick) {
            attackAnimationState.stop();
            attackAnimationName = null;
        }
    }

    /** Harness animation name currently playing on the client, or null when idle. */
    public String getAttackAnimationName() {
        return attackAnimationName;
    }

    // --- persistence -----------------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString(SPECIES_TAG, getSpeciesId().toString());
        EntityReference.store(getOwnerReference(), output, OWNER_TAG);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        Identifier speciesId = Identifier.tryParse(input.getStringOr(SPECIES_TAG, DEFAULT_SPECIES.toString()));
        setSpecies(speciesId != null ? speciesId : DEFAULT_SPECIES);
        EntityReference<LivingEntity> owner = EntityReference.read(input, OWNER_TAG);
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
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
