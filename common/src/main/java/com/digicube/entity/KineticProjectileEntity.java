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
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(ATTACK, ""); }

    @Override public void tick() {
        super.tick();
        var d = definition();
        if (d == null || ++age > d.projectileLife()) { discard(); return; }
        Vec3 velocity = getDeltaMovement(), origin = position();
        if (level() instanceof ServerLevel level) {
            if (!(getOwner() instanceof DigimonEntity owner) || !owner.isAlive()) { discard(); return; }
            int steps = Math.max(1, (int) Math.ceil(velocity.length() / .04));
            for (int sub = 0; sub <= steps; sub++) {
                Vec3 point = origin.add(velocity.scale((double) sub / steps));
                var boxes = d.projectileBoxes().stream().map(b -> KineticGeometry.flightBox(b, point, velocity)).toList();
                if (boxes.stream().anyMatch(b -> KineticGeometry.blocked(level, this, b))) { setPos(point); discard(); return; }
                for (var box : boxes) for (var entity : level.getEntities(this, box.bounds())) {
                    var victim = DigimonPart.livingOf(entity);
                    if (victim == null || victim == owner || !owner.canAttack(victim) || owner.isAllyOf(victim)
                            || HitParts.of(victim).stream().noneMatch(box::intersects)) continue;
                    if (owner.hitWithAttack(level, d.attack(), victim)) {
                        Constants.LOG.info("[kinetic] {} projectile hit {} age={}", d.attack().id(), victim.getType().toShortString(), age);
                    }
                    setPos(point); discard(); return;
                }
            }
        }
        setPos(origin.add(velocity));
    }
    @Override protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output); output.putString("Attack", entityData.get(ATTACK)); output.putInt("Age", age);
    }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input); entityData.set(ATTACK, input.getStringOr("Attack", "")); age = input.getIntOr("Age", 0);
    }
}
