package com.digicube.party;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** A saved individual, including its complete vanilla/mod entity data. */
public final class PartyMember {
    public static final Codec<PartyMember> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(PartyMember::id),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(PartyMember::owner),
            Identifier.CODEC.fieldOf("species").forGetter(PartyMember::species),
            Codec.STRING.fieldOf("nickname").forGetter(PartyMember::nickname),
            Codec.FLOAT.fieldOf("health").forGetter(PartyMember::health),
            Codec.FLOAT.fieldOf("max_health").forGetter(PartyMember::maxHealth),
            Codec.INT.fieldOf("slot").forGetter(PartyMember::slot),
            Codec.LONG.fieldOf("generation").forGetter(PartyMember::generation),
            CompoundTag.CODEC.fieldOf("entity").forGetter(PartyMember::entityData)
    ).apply(instance, PartyMember::new));

    private final UUID id;
    private final UUID owner;
    private Identifier species;
    private String nickname;
    private float health;
    private float maxHealth;
    private int slot;
    private long generation;
    private CompoundTag entityData;

    public PartyMember(UUID id, UUID owner, Identifier species, String nickname, float health,
                       float maxHealth, int slot, long generation, CompoundTag entityData) {
        this.id = id;
        this.owner = owner;
        this.species = species;
        this.nickname = nickname;
        this.health = health;
        this.maxHealth = maxHealth;
        this.slot = slot;
        this.generation = generation;
        this.entityData = entityData.copy();
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public Identifier species() { return species; }
    public String nickname() { return nickname; }
    public float health() { return health; }
    public float maxHealth() { return maxHealth; }
    public int slot() { return slot; }
    public long generation() { return generation; }
    public CompoundTag entityData() { return entityData.copy(); }
    public boolean active() { return slot >= 0; }
    public boolean defeated() { return health <= 0; }

    void setSlot(int slot) { this.slot = slot; }

    /** Invalidates any older incarnation still present in an unloaded chunk. */
    long nextGeneration() { return ++generation; }

    void capture(Identifier species, String nickname, float health, float maxHealth, CompoundTag data) {
        this.species = species;
        this.nickname = nickname;
        this.health = health;
        this.maxHealth = maxHealth;
        this.entityData = data.copy();
    }
}
