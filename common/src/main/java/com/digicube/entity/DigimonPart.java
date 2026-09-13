package com.digicube.entity;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * One extra hittable volume of a Digimon whose body reaches far beyond its collision box,
 * like the Ender Dragon's sub-hitboxes. Parts are never added to the level: the parent
 * places them every tick on both sides, the level mixins surface them to entity queries
 * and to attack packets, and any damage they take is the parent's.
 */
public final class DigimonPart extends Entity {
    public final DigimonEntity parent;
    public final int index;
    private EntityDimensions size;

    DigimonPart(DigimonEntity parent, int index, float width, float height) {
        super(parent.getType(), parent.level());
        this.parent = parent;
        this.index = index;
        this.size = EntityDimensions.scalable(width, height);
        refreshDimensions();
    }

    /** Part ids derive from the parent's id and stay negative, so they never shadow a real entity. */
    static int idFor(int parentId, int index) { return -(parentId * 32 + index + 1); }

    /** The parent when an entity is one of its parts, the entity itself when it is a living thing, else null. */
    public static LivingEntity livingOf(Entity entity) {
        if (entity instanceof DigimonPart part) return part.parent;
        return entity instanceof LivingEntity living ? living : null;
    }

    /** Move this part so its box becomes exactly the given volume. */
    void place(AABB box) {
        setPos(box.getCenter().x, box.minY, box.getCenter().z);
        setBoundingBox(box);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
    @Override protected void readAdditionalSaveData(ValueInput input) {}
    @Override protected void addAdditionalSaveData(ValueOutput output) {}
    @Override public boolean isPickable() { return parent.isAlive(); }
    @Override public boolean shouldBeSaved() { return false; }
    @Override public EntityDimensions getDimensions(Pose pose) { return size; }
    @Override public boolean is(Entity other) { return this == other || parent == other; }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return !isInvulnerableToBase(source) && parent.hurtServer(level, source, amount);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity tracker) {
        throw new UnsupportedOperationException("Digimon parts are placed by their parent, never spawned");
    }
}
