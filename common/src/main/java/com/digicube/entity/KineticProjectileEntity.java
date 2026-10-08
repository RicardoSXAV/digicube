package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.KineticAttacks;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** Straight solid shot: the exported visible cuboids sweep through blocks and combatants. */
public final class KineticProjectileEntity extends Projectile {
    private static final EntityDataAccessor<String> ATTACK = SynchedEntityData.defineId(KineticProjectileEntity.class, EntityDataSerializers.STRING);
    private int age;
    private static final EntityDataAccessor<Integer> AGE = SynchedEntityData.defineId(KineticProjectileEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> IMPACT = SynchedEntityData.defineId(KineticProjectileEntity.class, EntityDataSerializers.INT);
    /** The bodies a shocking shot struck last, as {@code "count:id,id"} (a new count for every batch): the clients' bolts. */
    private static final EntityDataAccessor<String> SHOCKS = SynchedEntityData.defineId(KineticProjectileEntity.class, EntityDataSerializers.STRING);
    public boolean impacting() { return entityData.get(IMPACT)>=0; }
    public float effectTick(float partial) { return impacting()?entityData.get(IMPACT)+partial:Math.max(0,entityData.get(AGE)-1+partial); }

    public KineticProjectileEntity(EntityType<? extends KineticProjectileEntity> type, Level level) { super(type, level); }
    public KineticProjectileEntity(Level level, DigimonEntity owner, KineticAttacks.Definition definition, Vec3 origin, Vec3 direction) {
        this(level, owner, definition, origin, direction, 1, 1);
    }

    /** @param power share of the attack's damage it deals; {@code speed} scales its flight. A rider's drawn shot sets both. */
    public KineticProjectileEntity(Level level, DigimonEntity owner, KineticAttacks.Definition definition, Vec3 origin, Vec3 direction, float power, float speed) {
        this(DCEntityTypes.KINETIC_PROJECTILE, level);
        setOwner(owner);
        entityData.set(ATTACK, definition.attack().id().getPath());
        this.power = power;
        this.muzzle = origin;
        setPos(origin);
        setDeltaMovement(direction.scale(definition.projectileSpeed() * speed));
    }
    private float power = 1;
    /** Where the shot left (its muzzle): a shot's falloff is measured from here to where it strikes. */
    private Vec3 muzzle;

    /** The share of the shot's damage that lands at {@code at}: its falloff from the muzzle (KineticAttacks.Falloff), else whole. */
    private float falloffPower(KineticAttacks.Definition d, Vec3 at) {
        return d.falloff() == null || muzzle == null ? 1 : d.falloff().power(at.distanceTo(muzzle));
    }

    /** The share of the shot's push (knockback or launch) that lands at {@code at}. */
    private float falloffPush(KineticAttacks.Definition d, Vec3 at) {
        return d.falloff() == null || muzzle == null ? 1 : d.falloff().knockback(at.distanceTo(muzzle));
    }
    public KineticAttacks.Definition definition() {
        String name = entityData.get(ATTACK);
        return name.isEmpty() ? null : KineticAttacks.get(Constants.id(name));
    }
    /** Attack visuals are never culled by hitbox size (vanilla hides a .1-block entity past 6 blocks); tracking range decides. */
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < com.digicube.registry.DCEntityTypes.ATTACK_RENDER_DISTANCE_SQR; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(ATTACK, "");builder.define(IMPACT,-1);builder.define(AGE,0);builder.define(SHOCKS,""); }

    private void impact(Vec3 point) { impact(point, null); }

    /** {@code struck}: the body a direct hit landed on (the clients draw it shocked with the rest), or null. */
    private void impact(Vec3 point, net.minecraft.world.entity.LivingEntity struck) {
        setPos(point);
        // A shocking ball bursts: whatever it was closing on, or stands within its reach, takes its share now.
        if (level() instanceof ServerLevel level && getOwner() instanceof DigimonEntity owner && definition() != null && definition().proximity() != null)
            burst(level, owner, definition(), point, struck);
        if(definition().projectileMotion()==null) { discard();return; }
        entityData.set(IMPACT,0);
        // Retain velocity as the impact's orientation; the phase no longer moves.
    }

    @Override public void tick() {
        super.tick();
        var d = definition();
        if (d == null) { discard(); return; }
        if(impacting()) {
            if(!level().isClientSide())entityData.set(IMPACT,entityData.get(IMPACT)+1);
            if(d.projectileMotion()==null || effectTick(0)>d.projectileMotion().impactTicks())discard();
            return;
        }
        if (++age > d.projectileLife()) {
            if (level() instanceof ServerLevel level) d.shotStyle().fizzle(level, position());
            impact(position()); return;
        }
        if(!level().isClientSide())entityData.set(AGE,age);
        Vec3 velocity = getDeltaMovement(), origin = position();
        if (level().isClientSide()) remember(origin);
        if (level().isClientSide()) d.shotStyle().trail(level(), origin, origin.add(velocity), random);
        if (level() instanceof ServerLevel level) {
            if (!(getOwner() instanceof DigimonEntity owner) || !owner.isAlive()) { discard(); return; }
            int steps = Math.max(1, (int) Math.ceil(velocity.length() / .04));
            for (int sub = 0; sub <= steps; sub++) {
                Vec3 point = origin.add(velocity.scale((double) sub / steps));
                double phase=age-1+(double)sub/steps;
                var local=d.projectileMotion()==null?d.projectileBoxes():d.projectileMotion().sample(phase);
                var boxes = local.stream().filter(b->Math.abs(b.x().dot(b.y().cross(b.z())))>1e-10)
                        .map(b -> KineticGeometry.flightBox(b, point, velocity)).toList();
                if (boxes.stream().anyMatch(b -> KineticGeometry.blocked(level, this, b))) {
                    d.shotStyle().impact(level, point);
                    if (d.blast() != null) blast(level, owner, d, point, null);
                    impact(point); return;
                }
                for (var box : boxes) for (var entity : level.getEntities(this, box.bounds())) {
                    var victim = DigimonPart.livingOf(entity);
                    if (victim == null || victim == owner || !owner.canStrike(victim) || owner.isAllyOf(victim)
                            || HitParts.of(victim).stream().noneMatch(box::intersects)) continue;
                    if (owner.hitWithAttack(level, d.attack(), victim, owner.position(), power * falloffPower(d, point), falloffPush(d, point))) {
                        shocked.add(victim.getId());
                        if(d.impairmentTicks()>0) {
                            victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS,d.impairmentTicks()));
                            victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(com.digicube.registry.DCEffects.INKED,d.impairmentTicks()));
                            // Ink bursts over the victim: blobs thrown off it every way, landing around it as stains.
                            var chest = victim.getBoundingBox().getCenter();
                            level.sendParticles(com.digicube.registry.DCParticles.INK_SPLASH, chest.x, chest.y + victim.getBbHeight() * .15, chest.z,
                                    18 + (int) Math.min(22, victim.getBbWidth() * victim.getBbHeight() * 4), victim.getBbWidth() * .3, victim.getBbHeight() * .25, victim.getBbWidth() * .3, 0);
                            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SQUID_INK, chest.x, chest.y, chest.z, 10, victim.getBbWidth() * .3, victim.getBbHeight() * .3, victim.getBbWidth() * .3, .06);
                            level.playSound(null, chest.x, chest.y, chest.z, net.minecraft.sounds.SoundEvents.SLIME_SQUISH,
                                    net.minecraft.sounds.SoundSource.HOSTILE, .9F, .7F + level.getRandom().nextFloat() * .15F);
                        }
                        com.digicube.digimon.ExposedMark.expose(victim, d.exposeTicks());
                        // A shot of water puts out a burning victim, and so its Burn; a burning shot lights one.
                        if (d.shotStyle().douses() && victim.isOnFire()) victim.extinguishFire();
                        if (d.burns()) DigimonEntity.scorch(victim, d.burn());
                        Constants.LOG.info("[kinetic] {} projectile hit {} age={}", d.attack().id(), victim.getType().toShortString(), age);
                        d.shotStyle().impact(level, point);
                        d.shotStyle().shock(level, victim, 1);
                        if (d.blast() != null) blast(level, owner, d, point, victim);
                        impact(point, victim);return;
                    }
                    // Invulnerability is not a hit. Retry while the visible volume overlaps.
                    break;
                }
            }
        }
        if (level() instanceof ServerLevel level && getOwner() instanceof DigimonEntity owner && d.proximity() != null)
            passing(level, owner, d, origin.add(velocity));
        setPos(origin.add(velocity));
    }

    // --- a shot that bursts (KineticAttacks.Blast) ------------------------------------------------------------------------
    /**
     * Server: the shot bursts at {@code at}: every other body within the blast's radius, in sight of the burst, takes its
     * share of the shot and the shot's burn. {@code struck} (the body a direct hit landed on, or null) has had its whole.
     */
    private void blast(ServerLevel level, DigimonEntity owner, KineticAttacks.Definition d, Vec3 at, net.minecraft.world.entity.LivingEntity struck) {
        var blast = d.blast();
        var seen = new java.util.HashSet<Integer>();
        for (var entity : level.getEntities(this, new net.minecraft.world.phys.AABB(at, at).inflate(blast.radius() + 1))) {
            var victim = DigimonPart.livingOf(entity);
            if (victim == null || victim == owner || victim == struck || !seen.add(victim.getId()) || !victim.isAlive()
                    || !owner.canStrike(victim) || owner.isAllyOf(victim)) continue;
            double distance = reach(victim.getBoundingBox(), at);
            if (distance > blast.radius() || level.clip(new net.minecraft.world.level.ClipContext(at, victim.getBoundingBox().getCenter(),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, this)).getType()
                    != net.minecraft.world.phys.HitResult.Type.MISS) continue;
            float share = blast.share(distance);
            if (owner.hitWithAttack(level, d.attack(), victim, at, power * share * falloffPower(d, at), falloffPush(d, at))) {
                if (d.burns()) DigimonEntity.scorch(victim, d.burn());
                Constants.LOG.info("[kinetic] {} blast caught {} at {} share={}", d.attack().id(), victim.getType().toShortString(),
                        String.format("%.2f", distance), String.format("%.2f", share));
            }
        }
    }

    // --- a shocking shot (KineticAttacks.Proximity) ---------------------------------------------------------------------
    /** Server: each body the ball is closing on and its nearest distance so far; the bodies it has struck. */
    private final java.util.Map<Integer, Double> nearest = new java.util.HashMap<>();
    private final java.util.Set<Integer> shocked = new java.util.HashSet<>();
    private int shockCount;
    /** Client: the bodies struck and the client tick each bolt was seen, for the renderer. */
    private final java.util.Map<Integer, Integer> bolts = new java.util.HashMap<>();
    private String seenShocks = "";

    /** Distance from {@code p} to the nearest point of {@code box}. */
    private static double reach(net.minecraft.world.phys.AABB box, Vec3 p) {
        double x = Math.clamp(p.x, box.minX, box.maxX), y = Math.clamp(p.y, box.minY, box.maxY), z = Math.clamp(p.z, box.minZ, box.maxZ);
        return p.distanceTo(new Vec3(x, y, z));
    }

    /** Server: a tick of the ball's passing: a body it was closing on and now draws away from is struck at its nearest. */
    private void passing(ServerLevel level, DigimonEntity owner, KineticAttacks.Definition d, Vec3 ball) {
        var prox = d.proximity();
        var batch = new java.util.ArrayList<Integer>();
        var seen = new java.util.HashSet<Integer>();
        for (var entity : level.getEntities(this, new net.minecraft.world.phys.AABB(ball, ball).inflate(prox.radius() + 2))) {
            var victim = DigimonPart.livingOf(entity);
            if (victim == null || victim == owner || shocked.contains(victim.getId()) || !seen.add(victim.getId())
                    || !owner.canStrike(victim) || owner.isAllyOf(victim)) continue;
            double now = reach(victim.getBoundingBox(), ball);
            Double before = nearest.get(victim.getId());
            if (now <= prox.radius() && (before == null || now < before - 1.0E-3)) { nearest.put(victim.getId(), now); continue; }
            if (before != null) shock(level, owner, d, victim, before, batch);
        }
        // bodies it was closing on that are gone from its reach altogether
        for (var entry : java.util.List.copyOf(nearest.entrySet())) {
            if (seen.contains(entry.getKey()) || shocked.contains(entry.getKey())) continue;
            if (level.getEntity(entry.getKey()) instanceof net.minecraft.world.entity.LivingEntity victim) shock(level, owner, d, victim, entry.getValue(), batch);
            nearest.remove(entry.getKey());
        }
        sync(batch);
    }

    /**
     * Server: the ball bursts at {@code at}: every body within its reach not yet struck takes its share. A body it struck
     * outright ({@code struck}) is synced with them, so the clients draw it shocked.
     */
    private void burst(ServerLevel level, DigimonEntity owner, KineticAttacks.Definition d, Vec3 at, net.minecraft.world.entity.LivingEntity struck) {
        var prox = d.proximity();
        var batch = new java.util.ArrayList<Integer>();
        if (struck != null) batch.add(struck.getId());
        for (var entity : level.getEntities(this, new net.minecraft.world.phys.AABB(at, at).inflate(prox.radius()))) {
            var victim = DigimonPart.livingOf(entity);
            if (victim == null || victim == owner || shocked.contains(victim.getId()) || !owner.canStrike(victim) || owner.isAllyOf(victim)) continue;
            double distance = Math.min(reach(victim.getBoundingBox(), at), nearest.getOrDefault(victim.getId(), Double.MAX_VALUE));
            if (distance <= prox.radius()) shock(level, owner, d, victim, distance, batch);
        }
        sync(batch);
    }

    private void shock(ServerLevel level, DigimonEntity owner, KineticAttacks.Definition d, net.minecraft.world.entity.LivingEntity victim,
                       double distance, java.util.List<Integer> batch) {
        shocked.add(victim.getId());
        nearest.remove(victim.getId());
        if (!victim.isAlive() || !level.clip(new net.minecraft.world.level.ClipContext(position(), victim.getBoundingBox().getCenter(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, this)).getType()
                .equals(net.minecraft.world.phys.HitResult.Type.MISS)) return;
        float share = d.proximity().share(distance);
        if (owner.hitWithAttack(level, d.attack(), victim, position(), power * share * falloffPower(d, position()), falloffPush(d, position()))) {
            batch.add(victim.getId());
            d.shotStyle().shock(level, victim, share);
            Constants.LOG.info("[kinetic] {} shocked {} at {} share={}", d.attack().id(), victim.getType().toShortString(),
                    String.format("%.2f", distance), String.format("%.2f", share));
        }
    }

    private void sync(java.util.List<Integer> batch) {
        if (batch.isEmpty()) return;
        StringBuilder text = new StringBuilder().append(++shockCount).append(':');
        for (int i = 0; i < batch.size(); i++) text.append(i == 0 ? "" : ",").append(batch.get(i));
        entityData.set(SHOCKS, text.toString());
    }

    /** Client: where the ball was over its last {@link #WAKE} ticks (newest first), and where it was first seen. */
    private static final int WAKE = 6;
    private final double[] wake = new double[WAKE * 3];
    private int wakeCount;
    private Vec3 firstSeen;

    private void remember(Vec3 at) {
        if (firstSeen == null) firstSeen = at;
        System.arraycopy(wake, 0, wake, 3, (WAKE - 1) * 3);
        wake[0] = at.x; wake[1] = at.y; wake[2] = at.z;
        wakeCount = Math.min(WAKE, wakeCount + 1);
    }

    /** Client: the ball's last positions, newest first, relative to {@code from}, into {@code out}; returns how many. */
    public int wake(Vec3 from, float[] out) {
        int n = Math.min(wakeCount, out.length / 3);
        for (int i = 0; i < n; i++) {
            out[i * 3] = (float) (wake[i * 3] - from.x); out[i * 3 + 1] = (float) (wake[i * 3 + 1] - from.y); out[i * 3 + 2] = (float) (wake[i * 3 + 2] - from.z);
        }
        return n;
    }

    /** Client: where the ball was first seen (its muzzle), or null before its first tick. */
    public Vec3 firstSeen() { return firstSeen; }

    /** Client: the bodies its bolts reach this tick and the client tick each was struck (renderer). */
    public java.util.Map<Integer, Integer> bolts() {
        String text = entityData.get(SHOCKS);
        if (!text.equals(seenShocks)) {
            seenShocks = text;
            int colon = text.indexOf(':');
            if (colon > 0) for (String id : text.substring(colon + 1).split(",")) {
                bolts.put(Integer.parseInt(id), tickCount);
            }
        }
        return bolts;
    }
    @Override protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output); output.putString("Attack", entityData.get(ATTACK)); output.putInt("Age", age); output.putFloat("Power", power);
        output.putInt("ImpactAge",impacting()?(int)effectTick(0):-1);
        output.storeNullable("Muzzle", Vec3.CODEC, muzzle);
    }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input); entityData.set(ATTACK, input.getStringOr("Attack", "")); age = input.getIntOr("Age", 0); power = input.getFloatOr("Power", 1);
        entityData.set(AGE,age);
        entityData.set(IMPACT,input.getIntOr("ImpactAge",-1));
        muzzle = input.read("Muzzle", Vec3.CODEC).orElse(null);
    }
}
