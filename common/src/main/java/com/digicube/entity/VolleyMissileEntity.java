package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.AttackVolley;
import com.digicube.digimon.AuthoredAttacks;
import com.digicube.digimon.DigimonAttack;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One part of a caster's body fired as a missile ({@link AttackVolley}; Digmon's drills in Gold Rush). It coasts out of
 * its socket along the part's own axis, lights after its delay, gathers to the volley's speed and turns toward the
 * target (or the rider's aim point) until it has passed it. The tip sweeps the way ahead each tick against blocks and
 * bodies (hit parts included); the first thing it meets takes the missile's share of the attack and the burst. The
 * client draws the part itself, spinning about its axis, and strews the trail.
 */
public final class VolleyMissileEntity extends ThrowableProjectile {
    private static final EntityDataAccessor<String> ATTACK = SynchedEntityData.defineId(VolleyMissileEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> INDEX = SynchedEntityData.defineId(VolleyMissileEntity.class, EntityDataSerializers.INT);
    /** Speed out of the socket before the thrust lights, blocks a tick. */
    private static final double COAST = .3;
    /** Ticks the thrust takes from the coast to the volley's speed. */
    private static final int THRUST_TICKS = 4;
    /** How far around its axis the drill strikes. */
    private static final double RADIUS = .22;
    /** How far ahead of a moving target a missile leads it, in blocks. */
    private static final double MAX_LEAD = 2;

    private LivingEntity target;
    private Vec3 mark;
    private boolean passed;
    private int age;

    public VolleyMissileEntity(EntityType<? extends VolleyMissileEntity> type, Level level) { super(type, level); }

    private VolleyMissileEntity(Level level, DigimonEntity caster, DigimonAttack attack, int index, Vec3 centre, Vec3 heading,
                                LivingEntity target, Vec3 mark) {
        this(DCEntityTypes.VOLLEY_MISSILE, level);
        setOwner(caster);
        entityData.set(ATTACK, attack.id().getPath());
        entityData.set(INDEX, index);
        setPos(centre.x, centre.y - getBbHeight() * .5, centre.z);
        setDeltaMovement(heading.scale(COAST));
        this.target = target;
        this.mark = mark;
    }

    /** Server: every part of the volley leaves the caster, where the clip holds it at the release. */
    static void launch(ServerLevel level, DigimonEntity caster, DigimonAttack attack, AuthoredAttacks.Definition definition) {
        var volley = definition.volley();
        float yaw = caster.getYRot(), pitch = caster.getAttackAimPitch(1);
        LivingEntity target = caster.strikeTarget();
        Vec3 mark = caster.strikeAimPoint();
        for (int i = 0; i < volley.missiles().size(); i++) {
            var box = AuthoredVolumeAttack.aimed(volley.missiles().get(i).launch(), attack, volley.launchTick(), pitch)
                    .world(caster.position(), yaw, 0);
            level.addFreshEntity(new VolleyMissileEntity(level, caster, attack, i, box.center(), box.z().normalize(), target, mark));
        }
    }

    public AuthoredAttacks.Definition definition() {
        String name = entityData.get(ATTACK);
        var definition = name.isEmpty() ? null : AuthoredAttacks.get(Constants.id(name));
        return definition != null && definition.fires() && entityData.get(INDEX) < definition.volley().missiles().size() ? definition : null;
    }

    /** Which part of the volley this is; null until the synced data has arrived. */
    public AttackVolley.Missile missile() {
        var definition = definition();
        return definition == null ? null : definition.volley().missiles().get(entityData.get(INDEX));
    }

    /** Both sides: whether the thrust has lit. The client counts from its own first tick, close enough for the trail. */
    public boolean lit() {
        var missile = missile();
        return missile != null && (level().isClientSide() ? tickCount : age) > missile.delay();
    }

    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < DCEntityTypes.ATTACK_RENDER_DISTANCE_SQR; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(ATTACK, ""); builder.define(INDEX, 0); }
    @Override protected double getDefaultGravity() { return 0; }
    @Override protected float getAirDrag() { return 1; }
    /** The sweep in {@link #tick} finds what the tip meets; vanilla's thin ray would only add a late second hit. */
    @Override protected boolean canHitEntity(Entity entity) { return false; }

    private Vec3 centre() { return getBoundingBox().getCenter(); }

    @Override
    public void tick() {
        var definition = definition();
        var missile = missile();
        if (definition == null || missile == null) {
            if (!level().isClientSide() && ++age > 2) discard();
            super.tick();
            return;
        }
        var volley = definition.volley();
        Vec3 velocity = getDeltaMovement();
        if (level() instanceof ServerLevel level) {
            age++;
            if (!(getOwner() instanceof DigimonEntity owner) || !owner.isAlive() || age > missile.delay() + volley.life()) {
                definition.particles().fizzle(level, centre());
                discard();
                return;
            }
            if (age > missile.delay()) {
                if (age == missile.delay() + 1) definition.particles().ignite(level, this, velocity.normalize());
                Vec3 heading = velocity.normalize(), aim = aimPoint(velocity.length());
                if (aim != null && !passed) {
                    Vec3 toward = aim.subtract(centre());
                    // Once past its mark a missile flies on: it never loops back.
                    if (toward.dot(heading) < 0 && toward.lengthSqr() < 9) passed = true;
                    else if (toward.lengthSqr() > 1.0E-6) heading = turn(heading, toward.normalize(), volley.turn());
                }
                double speed = Math.min(volley.speed(), velocity.length() + (volley.speed() - COAST) / THRUST_TICKS);
                velocity = heading.scale(speed);
                setDeltaMovement(velocity);
            }
            if (sweep(level, owner, definition, velocity, missile)) return;
        } else if (lit()) {
            Vec3 tail = centre().subtract(velocity.normalize().scale(missile.launch().z().length()));
            definition.particles().missileTrail(level(), tail, velocity, tickCount - missile.delay());
        }
        super.tick();
        // Water drags a thrown thing; a lit drill keeps its thrust.
        setDeltaMovement(velocity);
    }

    /** Where the missile steers: ahead of its target by the target's pace, or the aim point it was given. */
    private Vec3 aimPoint(double speed) {
        if (target != null && target.isAlive() && !target.isRemoved()) {
            Vec3 at = target.getBoundingBox().getCenter();
            Vec3 lead = target.getDeltaMovement().scale(at.distanceTo(centre()) / Math.max(speed, .3));
            return at.add(lead.length() > MAX_LEAD ? lead.normalize().scale(MAX_LEAD) : lead);
        }
        return mark;
    }

    /** {@code from} turned toward {@code to} (both unit) by at most {@code degrees}. */
    static Vec3 turn(Vec3 from, Vec3 to, float degrees) {
        double angle = Math.acos(Mth.clamp(from.dot(to), -1, 1)), max = Math.toRadians(degrees);
        if (angle <= max) return to;
        if (angle > Math.PI - 1.0E-3) return from;
        double sin = Math.sin(angle), t = max / angle;
        return from.scale(Math.sin((1 - t) * angle) / sin).add(to.scale(Math.sin(t * angle) / sin)).normalize();
    }

    /** The tip's way this tick against blocks and bodies: the nearest thing met takes the hit. */
    private boolean sweep(ServerLevel level, DigimonEntity owner, AuthoredAttacks.Definition definition, Vec3 velocity, AttackVolley.Missile missile) {
        Vec3 ahead = velocity.normalize().scale(missile.launch().z().length());
        Vec3 from = centre().add(ahead), to = from.add(velocity);
        var block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        LivingEntity victim = null;
        Vec3 contact = null;
        double nearest = from.distanceToSqr(end) + 1.0E-6;
        for (Entity entity : level.getEntities(this, new AABB(from, end).inflate(RADIUS + .5), e -> DigimonPart.livingOf(e) != null)) {
            LivingEntity living = DigimonPart.livingOf(entity);
            if (living == owner || !living.isAlive() || owner.hasPassenger(living) || !owner.canStrike(living) || owner.isAllyOf(living)) continue;
            var hit = entity.getBoundingBox().inflate(RADIUS).clip(from, end);
            if (entity.getBoundingBox().inflate(RADIUS).contains(from)) hit = java.util.Optional.of(from);
            if (hit.isPresent() && from.distanceToSqr(hit.get()) < nearest) {
                nearest = from.distanceToSqr(hit.get());
                victim = living;
                contact = hit.get();
            }
        }
        if (victim != null) {
            // Pushed along the flight: the blow comes from behind the drill.
            owner.hitWithAttack(level, definition.attack(), victim, contact.subtract(velocity.normalize().scale(3)), definition.volley().power());
            definition.particles().burst(level, contact, null);
            discard();
            return true;
        }
        if (block.getType() != HitResult.Type.MISS) {
            definition.particles().burst(level, block.getLocation(), level.getBlockState(block.getBlockPos()));
            discard();
            return true;
        }
        return false;
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        var definition = definition();
        if (level() instanceof ServerLevel level && definition != null && hit instanceof BlockHitResult block && isAlive()) {
            definition.particles().burst(level, hit.getLocation(), level.getBlockState(block.getBlockPos()));
            discard();
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("Attack", entityData.get(ATTACK));
        output.putInt("Index", entityData.get(INDEX));
        output.putInt("Age", age);
    }

    /** A reloaded missile has lost its target and flies straight on until it burns out. */
    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        entityData.set(ATTACK, input.getStringOr("Attack", ""));
        entityData.set(INDEX, input.getIntOr("Index", 0));
        age = input.getIntOr("Age", 0);
    }
}
