package com.digicube.party;

import com.digicube.digimon.Progression;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * A saved individual, including its complete vanilla/mod entity data. Level and XP are
 * mirrored out of that data so the Digivice can show them for partners in reserve; the
 * entity compound stays the source of truth when the partner is deployed.
 */
public final class PartyMember {
    public static final Codec<PartyMember> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(PartyMember::id),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(PartyMember::owner),
            Identifier.CODEC.fieldOf("species").forGetter(PartyMember::species),
            Codec.STRING.fieldOf("nickname").forGetter(PartyMember::nickname),
            Codec.FLOAT.fieldOf("health").forGetter(PartyMember::health),
            Codec.FLOAT.fieldOf("max_health").forGetter(PartyMember::maxHealth),
            // Optional so rosters saved before progression existed still load, at level 1.
            Codec.INT.optionalFieldOf("level", Progression.MIN_LEVEL).forGetter(PartyMember::level),
            Codec.INT.optionalFieldOf("xp", 0).forGetter(PartyMember::xp),
            Codec.INT.fieldOf("slot").forGetter(PartyMember::slot),
            Codec.LONG.fieldOf("generation").forGetter(PartyMember::generation),
            CompoundTag.CODEC.fieldOf("entity").forGetter(PartyMember::entityData),
            // Optional so rosters saved before defeat rest existed load rested.
            Codec.INT.optionalFieldOf("rest_ticks", 0).forGetter(PartyMember::restTicks),
            // Optional so rosters saved before the command wheel existed load deployed.
            Codec.BOOL.optionalFieldOf("stowed", false).forGetter(PartyMember::stowed)
    ).apply(instance, PartyMember::new));

    private final UUID id;
    private final UUID owner;
    private Identifier species;
    private String nickname;
    private float health;
    private float maxHealth;
    private int level;
    private int xp;
    private int slot;
    private long generation;
    private CompoundTag entityData;
    private int restTicks;
    /** Recalled from the command wheel: keeps its party slot but stays in the Digivice until sent out. */
    private boolean stowed;

    public PartyMember(UUID id, UUID owner, Identifier species, String nickname, float health, float maxHealth,
                       int level, int xp, int slot, long generation, CompoundTag entityData) {
        this(id, owner, species, nickname, health, maxHealth, level, xp, slot, generation, entityData, 0);
    }

    public PartyMember(UUID id, UUID owner, Identifier species, String nickname, float health, float maxHealth,
                       int level, int xp, int slot, long generation, CompoundTag entityData, int restTicks) {
        this(id, owner, species, nickname, health, maxHealth, level, xp, slot, generation, entityData, restTicks, false);
    }

    public PartyMember(UUID id, UUID owner, Identifier species, String nickname, float health, float maxHealth,
                       int level, int xp, int slot, long generation, CompoundTag entityData, int restTicks, boolean stowed) {
        this.id = id;
        this.owner = owner;
        this.species = species;
        this.nickname = nickname;
        this.health = health;
        this.maxHealth = maxHealth;
        this.level = Progression.clampLevel(level);
        this.xp = Math.max(0, xp);
        this.slot = slot;
        this.generation = generation;
        this.entityData = entityData.copy();
        this.restTicks = Math.max(0, restTicks);
        this.stowed = stowed;
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public Identifier species() { return species; }
    public String nickname() { return nickname; }
    public float health() { return health; }
    public float maxHealth() { return maxHealth; }
    public int level() { return level; }
    public int xp() { return xp; }
    public int slot() { return slot; }
    public long generation() { return generation; }
    public CompoundTag entityData() { return entityData.copy(); }
    public boolean active() { return slot >= 0; }
    public boolean defeated() { return health <= 0; }
    /** Ticks of rest a defeat still imposes before regeneration starts; zero for a living partner. */
    public int restTicks() { return restTicks; }
    public com.digicube.digimon.EvolutionState evolution() { return com.digicube.digimon.EvolutionState.load(entityData.getCompoundOrEmpty(com.digicube.digimon.EvolutionState.TAG)); }
    public void saveEvolution(com.digicube.digimon.EvolutionState state) { entityData.put(com.digicube.digimon.EvolutionState.TAG,state.save()); }
    public boolean originRequired() { return evolution().needsOrigin(species); }
    /** Updates a stored individual directly, with no hidden live entity and no healing. */
    public void editStored(net.minecraft.resources.Identifier form,int newLevel,boolean clearXp) {
        double fraction=maxHealth>0?health/(double)maxHealth:0;
        var sheet=com.digicube.digimon.DigimonSpeciesRegistry.getOrThrow(form);
        species=form;level=Progression.clampLevel(newLevel);if(clearXp)xp=0;
        maxHealth=Progression.maxHealth(sheet.baseHealth(),level);health=(float)(fraction*maxHealth);
        if(health/(double)maxHealth>fraction)health=Math.nextDown(health);
        entityData.putString(com.digicube.entity.DigimonEntity.SPECIES_TAG,form.toString());
        entityData.putInt(com.digicube.entity.DigimonEntity.LEVEL_TAG,level);entityData.putInt(com.digicube.entity.DigimonEntity.XP_TAG,xp);
        entityData.putFloat("Health",health);
    }
    /** Defeated and still waiting out its rest. */
    public boolean resting() { return defeated() && restTicks > 0; }

    public boolean stowed() { return stowed; }
    void setStowed(boolean stowed) { this.stowed = stowed; }

    /** A new slot is a fresh start: whoever is placed in the party comes out. */
    void setSlot(int slot) { this.slot = slot; this.stowed = false; }

    /** Invalidates any older incarnation still present in an unloaded chunk. */
    long nextGeneration() { return ++generation; }

    /** Sets the stored health and keeps the saved entity data in step, for a partner not in the world. */
    void setHealth(float health) {
        this.health = health;
        this.entityData.putFloat("Health", health);
        if (health > 0) restTicks = 0;
    }

    void capture(Identifier species, String nickname, float health, float maxHealth, int level, int xp, CompoundTag data) {
        this.species = species;
        this.nickname = nickname;
        this.health = health;
        this.maxHealth = maxHealth;
        this.level = Progression.clampLevel(level);
        this.xp = Math.max(0, xp);
        this.entityData = data.copy();
        if (health > 0) restTicks = 0;
    }

    /** Marks a defeat: the partner must rest this long in the Digivice before its first regeneration pulse. */
    void defeat(int restTicks) {
        this.restTicks = Math.max(0, restTicks);
    }

    /**
     * Lets {@code ticks} of rest pass.
     * @return whether there was rest left to spend
     */
    boolean rest(int ticks) {
        if (restTicks <= 0) return false;
        restTicks = Math.max(0, restTicks - ticks);
        return true;
    }
}
