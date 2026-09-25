package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.ThrownAttacks;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.UUID;

/**
 * A returning throw in flight ({@link ThrownAttacks.Returning}): it follows its {@link BoomerangPath}, striking each
 * enemy once on the way out and once on the way back, until its thrower takes it out of the air on the last stretch.
 * Uncaught at the end of the path, or stopped by a wall, it falls and lies where it lands, harmless, until the thrower
 * picks it up or grows another. Both sides walk the same path from the synced release, so the curve is smooth.
 */
public final class BoomerangEntity extends Projectile {
    public enum Phase { FLYING, CATCHING, DROPPING, GROUNDED }
    private static final EntityDataAccessor<String> ATTACK = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> PHASE = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<BlockPos> START = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<org.joml.Vector3fc> START_FRACTION = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> RANGE = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> SIDE = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.BYTE);
    /** The flight's floor, pace and lifted turn ({@link BoomerangPath}): what the charge, a leap and the aim made of it. */
    private static final EntityDataAccessor<Float> FLOOR = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> PACE = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> LIFT = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.FLOAT);
    /** Ticks of flight since the release; the path's own clock turns it into a place. */
    private static final EntityDataAccessor<Integer> FLIGHT = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.INT);
    /** Game time it was lost (fell out of its flight); the thrower grows another {@code drop_ticks} later. */
    private static final EntityDataAccessor<Long> LOST_AT = SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.LONG);

    private static final boolean TRACE = Boolean.parseBoolean(System.getenv("DIGICUBE_THROWER_TRACE"));
    private BoomerangPath path;
    /** Server: the share of the attack's damage its blows deal (a charged throw, a leap). */
    private float power = 1;
    private final HashSet<UUID> outbound = new HashSet<>(), inbound = new HashSet<>();
    private int grounded, catching;
    private Vec3 catchFrom;
    /** Flight tick the catch began on time (the bone keeps its curve until the fist closes), or -1 for a late catch. */
    private int catchStart = -1;
    /** Client: the spin angle, degrees, and last tick's for interpolation. */
    public float spin, spinO;

    public BoomerangEntity(EntityType<? extends BoomerangEntity> type, Level level) { super(type, level); }

    public BoomerangEntity(ServerLevel level, DigimonEntity owner, ThrownAttacks.Returning spec, BoomerangPath path, float power) {
        this(DCEntityTypes.BOOMERANG, level);
        this.power = power;
        setOwner(owner);
        entityData.set(ATTACK, spec.attack().id().getPath());
        BlockPos origin = BlockPos.containing(path.start());
        entityData.set(START, origin);
        entityData.set(START_FRACTION, new org.joml.Vector3f((float) (path.start().x - origin.getX()), (float) (path.start().y - origin.getY()), (float) (path.start().z - origin.getZ())));
        entityData.set(YAW, path.yaw());
        entityData.set(RANGE, (float) path.range());
        entityData.set(SIDE, (byte) path.side());
        entityData.set(FLOOR, (float) path.floor());
        entityData.set(PACE, (float) path.pace());
        entityData.set(LIFT, (float) path.lift());
        this.path = path;
        setPos(path.start());
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ATTACK, "").define(PHASE, (byte) 0).define(START, BlockPos.ZERO).define(START_FRACTION, new org.joml.Vector3f())
                .define(YAW, 0F).define(RANGE, 0F).define(SIDE, (byte) 1).define(FLIGHT, 0).define(LOST_AT, -1L)
                .define(FLOOR, 0F).define(PACE, 1F).define(LIFT, 0F);
    }

    /** Ticks until the thrower grows another, for a bone that fell out of its flight; -1 while it flies. */
    public int regrowIn() {
        var spec = spec();
        long lost = entityData.get(LOST_AT);
        if (spec == null || lost < 0) return -1;
        return (int) Math.max(0, spec.dropTicks() - (level().getGameTime() - lost));
    }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (accessor == START || accessor == START_FRACTION || accessor == YAW || accessor == RANGE || accessor == SIDE || accessor == ATTACK
                || accessor == FLOOR || accessor == PACE || accessor == LIFT) path = null;
    }

    public ThrownAttacks.Returning spec() {
        String name = entityData.get(ATTACK);
        if (name.isEmpty()) return null;
        for (var r : ThrownAttacks.returning()) if (r.attack().id().getPath().equals(name)) return r;
        return null;
    }
    public Phase phase() { return Phase.values()[Math.clamp(entityData.get(PHASE), 0, Phase.values().length - 1)]; }
    private void phase(Phase phase) { entityData.set(PHASE, (byte) phase.ordinal()); }
    public int flight() { return entityData.get(FLIGHT); }

    /** The path this bone flies, rebuilt from the synced release on the client. */
    public BoomerangPath path() {
        if (path == null) {
            var spec = spec();
            if (spec == null) return null;
            var f = entityData.get(START_FRACTION);
            Vec3 start = Vec3.atLowerCornerOf(entityData.get(START)).add(f.x(), f.y(), f.z());
            path = BoomerangPath.of(start, entityData.get(YAW), entityData.get(RANGE), entityData.get(SIDE), spec,
                    entityData.get(FLOOR), entityData.get(PACE), entityData.get(LIFT));
        }
        return path;
    }

    /** Where it is drawn: along the path while it flies, its synced position otherwise. */
    public Vec3 renderPosition(float partial) {
        var p = path();
        if (phase() == Phase.FLYING && p != null) return p.atTick(flight() - 1 + partial);
        return getPosition(partial);
    }

    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < DCEntityTypes.ATTACK_RENDER_DISTANCE_SQR; }
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }

    /** Degrees the spin plane leans off the flat as it leaves the hand (thrown near upright) and as it comes home. */
    private static final double RELEASE_BANK = 72, HOME_BANK = 10;

    /**
     * The axis it spins about, for drawing: a real boomerang leaves the hand nearly upright, its plane leaning into the
     * turn, and lies down flat as it comes home. Null when it is not in the air on its curve.
     */
    public Vec3 spinAxis(float partial) {
        var p = path();
        if (p == null || phase() != Phase.FLYING && phase() != Phase.CATCHING) return null;
        double u = phase() == Phase.CATCHING ? 1 : Math.clamp(p.lengthAt(flight() - 1 + partial) / p.total(), 0, 1);
        double bank = Math.toRadians(Mth.lerp(u * u * (3 - 2 * u), RELEASE_BANK, HOME_BANK));
        return new Vec3(0, Math.cos(bank), 0).add(p.lateral().scale(Math.sin(bank)));
    }

    public DigimonEntity thrower() { return getOwner() instanceof DigimonEntity d ? d : null; }

    @Override public void tick() {
        super.tick();
        spinO = spin;
        var p = path();
        if (p == null) { if (!level().isClientSide()) discard(); return; }
        float spinRate = switch (phase()) { case FLYING, CATCHING -> p.spec().spin(); case DROPPING -> p.spec().spin() * .6F;
            case GROUNDED -> Math.max(0, p.spec().spin() * .6F * (1 - grounded / 12F)); };
        spin += spinRate;
        if (level().isClientSide()) {
            // Its thrower's rider reads the bone's state off it (the tile, the catch ring).
            if (thrower() != null) thrower().noticeBone(this);
            if (phase() == Phase.FLYING) { setPos(p.atTick(flight())); if (tickCount % 2 == 0) trail(p); }
            else if (phase() == Phase.GROUNDED) grounded++;
            return;
        }
        var owner = thrower();
        if (owner == null || !owner.isAlive() || owner.level() != level()) { discard(); return; }
        switch (phase()) {
            case FLYING -> fly((ServerLevel) level(), owner, p);
            case CATCHING -> {
                Vec3 hand = owner.thrower().catchHand();
                catching++;
                int contact = Math.max(1, p.spec().catchClip().event());
                // On time it flies on along its curve while the hands set and reach, and only the last two ticks
                // before the contact draw it into the fist; a late catch homes from where it was taken.
                if (catchStart >= 0) setPos(p.atTick(catchStart + catching).lerp(hand, Math.clamp((catching - (contact - 2)) / 2.0, 0, 1)));
                else setPos(catchFrom.lerp(hand, Math.min(1, catching / (double) contact)));
                if (catching >= contact) { flightOver(owner, "caught"); owner.thrower().caught(this); discard(); }
            }
            case DROPPING -> {
                setDeltaMovement(getDeltaMovement().add(0, -.05, 0).scale(.97));
                move(MoverType.SELF, getDeltaMovement());
                if (onGround() || getY() < level().getMinY() - 8) {
                    setDeltaMovement(Vec3.ZERO);
                    phase(Phase.GROUNDED);
                    level().playSound(null, getX(), getY(), getZ(), SoundEvents.BONE_BLOCK_HIT, SoundSource.NEUTRAL, 1F, .8F);
                }
            }
            case GROUNDED -> {
                grounded++;
                if (!onGround()) { setDeltaMovement(getDeltaMovement().add(0, -.05, 0)); move(MoverType.SELF, getDeltaMovement()); }
                if (owner.thrower().boneRegrown(this)) discard();
            }
        }
    }

    private void fly(ServerLevel level, DigimonEntity owner, BoomerangPath p) {
        int tick = flight() + 1;
        double s0 = p.lengthAt(tick - 1), s1 = p.lengthAt(tick);
        var spec = p.spec();
        int steps = Math.max(1, (int) Math.ceil((s1 - s0) / .3));
        for (int i = 1; i <= steps; i++) {
            double s = Mth.lerp((double) i / steps, s0, s1);
            Vec3 at = p.at(s);
            AABB box = new AABB(at, at).inflate(spec.hitRadius(), spec.hitHeight(), spec.hitRadius());
            // A wall stops it: it clatters off and falls, never flying through to the hand.
            if (level.getBlockCollisions(this, box.deflate(.12, .08, .12)).iterator().hasNext()) {
                Vec3 back = p.tangent(s).scale(-.18);
                setPos(p.at(Math.max(0, s - .35)));
                drop(level, owner, new Vec3(back.x, .12, back.z), "wall");
                return;
            }
            boolean returning = p.returning(s);
            var ledger = returning ? inbound : outbound;
            for (var entity : level.getEntities(this, box)) {
                LivingEntity victim = DigimonPart.livingOf(entity);
                if (victim == null || victim == owner || ledger.contains(victim.getUUID()) || !owner.canAttack(victim) || owner.isAllyOf(victim)
                        || HitParts.of(victim).stream().noneMatch(box::intersects)) continue;
                if (owner.hitWithAttack(level, spec.attack(), victim, at, (returning ? spec.returnPower() : 1) * power)) {
                    ledger.add(victim.getUUID());
                    owner.countSkill(returning ? "bone_hit_back" : "bone_hit_out");
                    level.playSound(null, at.x, at.y, at.z, SoundEvents.BONE_BLOCK_BREAK, SoundSource.NEUTRAL, 1.1F, 1.3F);
                    level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, .2, .2, .2, .2);
                    Constants.LOG.info("[thrown] {} bone hit {} on the way {} s={}/{}", owner.getSpeciesId().getPath(),
                            victim.getType().toShortString(), returning ? "back" : "out", String.format("%.1f", s), String.format("%.1f", p.total()));
                }
            }
            // The last stretch home: a hand within reach takes it out of the air.
            if (TRACE && i == steps && p.catchable(s)) Constants.LOG.info("[thrown-trace] catchable s={}/{} hand-bone={} canCatch={} stage={}",
                    String.format("%.1f", s), String.format("%.1f", p.total()), String.format("%.2f", owner.thrower().catchHand().distanceTo(at)),
                    owner.thrower().canCatch(), owner.thrower().stage());
            if (p.catchable(s) && owner.thrower().canCatch()) {
                Vec3 hand = owner.thrower().catchHand();
                if (hand.distanceTo(at) <= spec.catchRadius() && owner.thrower().beginCatch(this)) {
                    catchStart = -1; catchFrom = at; setPos(at); phase(Phase.CATCHING);
                    entityData.set(FLIGHT, tick);
                    return;
                }
            }
        }
        // On time: the catch clip starts when the bone will be at the fist on the clip's contact tick, so the hands set,
        // reach and close on it (the fist is where the brain walked it to).
        int contact = Math.max(1, spec.catchClip().event());
        double meet = Math.min(p.lengthAt(tick + contact), p.total());
        if (p.catchable(meet) && owner.thrower().canCatch() && owner.thrower().catchHand().distanceTo(p.at(meet)) <= spec.catchRadius()
                && owner.thrower().beginCatch(this)) {
            catchStart = tick; phase(Phase.CATCHING);
            entityData.set(FLIGHT, tick);
            setPos(p.at(s1));
            return;
        }
        entityData.set(FLIGHT, tick);
        setPos(p.at(s1));
        if (tick % 5 == 0) level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, .35F, 1.6F);
        if (s1 >= p.total() - 1.0E-6) {
            Vec3 on = p.tangent(p.total()).scale(p.pace() * BoomerangPath.pace(p.total(), p.outbound(), p.total(), spec) * .7);
            drop(level, owner, new Vec3(on.x, .08, on.z), "missed");
        }
    }

    /** One flight's tally: how many enemies the bone struck, out and back together. */
    private void flightOver(DigimonEntity owner, String how) {
        var struck = new HashSet<UUID>(outbound); struck.addAll(inbound);
        if (struck.size() >= 2) owner.countSkill("bone_multi_hit");
        Constants.LOG.info("[thrown] {} bone flight over ({}): {} enemies struck, {} out {} back", owner.getSpeciesId().getPath(), how,
                struck.size(), outbound.size(), inbound.size());
    }

    private void drop(ServerLevel level, DigimonEntity owner, Vec3 velocity, String why) {
        flightOver(owner, why);
        setDeltaMovement(velocity);
        phase(Phase.DROPPING);
        entityData.set(LOST_AT, level.getGameTime());
        owner.thrower().lost(this);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.BONE_BLOCK_HIT, SoundSource.NEUTRAL, 1F, 1.2F);
        Constants.LOG.info("[thrown] {} bone lost ({}) at {}", owner.getSpeciesId().getPath(), why,
                String.format("(%.1f %.1f %.1f)", getX(), getY(), getZ()));
    }

    /** Client: a thin warm-white streak of dust behind a bone in flight. */
    private void trail(BoomerangPath p) {
        Vec3 at = position();
        level().addParticle(ParticleTypes.WHITE_ASH, at.x, at.y, at.z, 0, 0, 0);
    }

    public int groundedTicks() { return grounded; }

    @Override protected void addAdditionalSaveData(ValueOutput output) { super.addAdditionalSaveData(output); }
    @Override protected void readAdditionalSaveData(ValueInput input) { super.readAdditionalSaveData(input); }
}
