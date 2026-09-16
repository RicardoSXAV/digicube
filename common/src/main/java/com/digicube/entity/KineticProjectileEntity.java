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
    public boolean impacting() { return entityData.get(IMPACT)>=0; }
    public float effectTick(float partial) { return impacting()?entityData.get(IMPACT)+partial:Math.max(0,entityData.get(AGE)-1+partial); }

    public KineticProjectileEntity(EntityType<? extends KineticProjectileEntity> type, Level level) { super(type, level); }
    public KineticProjectileEntity(Level level, DigimonEntity owner, KineticAttacks.Definition definition, Vec3 origin, Vec3 direction) {
        this(DCEntityTypes.KINETIC_PROJECTILE, level);
        setOwner(owner);
        entityData.set(ATTACK, definition.attack().id().getPath());
        setPos(origin);
        setDeltaMovement(direction.scale(definition.projectileSpeed()));
    }
    public KineticAttacks.Definition definition() {
        String name = entityData.get(ATTACK);
        return name.isEmpty() ? null : KineticAttacks.get(Constants.id(name));
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(ATTACK, "");builder.define(IMPACT,-1);builder.define(AGE,0); }

    private void impact(Vec3 point) {
        setPos(point);
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
        if (++age > d.projectileLife()) { impact(position()); return; }
        if(!level().isClientSide())entityData.set(AGE,age);
        Vec3 velocity = getDeltaMovement(), origin = position();
        if (level() instanceof ServerLevel level) {
            if (!(getOwner() instanceof DigimonEntity owner) || !owner.isAlive()) { discard(); return; }
            int steps = Math.max(1, (int) Math.ceil(velocity.length() / .04));
            for (int sub = 0; sub <= steps; sub++) {
                Vec3 point = origin.add(velocity.scale((double) sub / steps));
                double phase=age-1+(double)sub/steps;
                var local=d.projectileMotion()==null?d.projectileBoxes():d.projectileMotion().sample(phase);
                var boxes = local.stream().filter(b->Math.abs(b.x().dot(b.y().cross(b.z())))>1e-10)
                        .map(b -> KineticGeometry.flightBox(b, point, velocity)).toList();
                if (boxes.stream().anyMatch(b -> KineticGeometry.blocked(level, this, b))) { impact(point); return; }
                for (var box : boxes) for (var entity : level.getEntities(this, box.bounds())) {
                    var victim = DigimonPart.livingOf(entity);
                    if (victim == null || victim == owner || !owner.canAttack(victim) || owner.isAllyOf(victim)
                            || HitParts.of(victim).stream().noneMatch(box::intersects)) continue;
                    if (owner.hitWithAttack(level, d.attack(), victim)) {
                        if(d.impairmentTicks()>0) {
                            victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS,d.impairmentTicks()));
                            victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(com.digicube.registry.DCEffects.INKED,d.impairmentTicks()));
                        }
                        Constants.LOG.info("[kinetic] {} projectile hit {} age={}", d.attack().id(), victim.getType().toShortString(), age);
                        impact(point);return;
                    }
                    // Invulnerability is not a hit. Retry while the visible volume overlaps.
                    break;
                }
            }
        }
        setPos(origin.add(velocity));
    }
    @Override protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output); output.putString("Attack", entityData.get(ATTACK)); output.putInt("Age", age);
        output.putInt("ImpactAge",impacting()?(int)effectTick(0):-1);
    }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input); entityData.set(ATTACK, input.getStringOr("Attack", "")); age = input.getIntOr("Age", 0);
        entityData.set(AGE,age);
        entityData.set(IMPACT,input.getIntOr("ImpactAge",-1));
    }
}
