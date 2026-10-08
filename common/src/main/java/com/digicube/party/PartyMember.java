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
            // Read only: an older save could keep a recalled partner in its party slot. It loads inside the Digivice.
            Codec.BOOL.optionalFieldOf("stowed", false).forGetter(member -> false),
            // A Digitama from the scan: the time it still needs to hatch. Absent once hatched.
            Codec.INT.optionalFieldOf("hatch_ticks", 0).forGetter(PartyMember::hatchTicks),
            // The party slot a partner sent into the Digivice by a defeat or a missing Digivice goes back to. -1: none.
            Codec.INT.optionalFieldOf("return_slot", -1).forGetter(PartyMember::returnSlot)
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
    private int hatchTicks;
    private int returnSlot;

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
        this(id, owner, species, nickname, health, maxHealth, level, xp, slot, generation, entityData, restTicks, stowed, 0);
    }

    public PartyMember(UUID id, UUID owner, Identifier species, String nickname, float health, float maxHealth,
                       int level, int xp, int slot, long generation, CompoundTag entityData, int restTicks, boolean stowed,
                       int hatchTicks) {
        this(id, owner, species, nickname, health, maxHealth, level, xp, slot, generation, entityData, restTicks, stowed, hatchTicks, -1);
    }

    public PartyMember(UUID id, UUID owner, Identifier species, String nickname, float health, float maxHealth,
                       int level, int xp, int slot, long generation, CompoundTag entityData, int restTicks, boolean stowed,
                       int hatchTicks, int returnSlot) {
        this.id = id;
        this.owner = owner;
        this.species = species;
        this.nickname = nickname;
        this.health = health;
        this.maxHealth = maxHealth;
        this.level = Progression.clampLevel(level);
        this.xp = Math.max(0, xp);
        this.slot = stowed ? -1 : slot;
        this.generation = generation;
        this.entityData = entityData.copy();
        this.restTicks = Math.max(0, restTicks);
        this.hatchTicks = Math.max(0, hatchTicks);
        // A Digitama never takes a party slot.
        if (this.hatchTicks > 0) this.slot = -1;
        this.returnSlot = returnSlot >= 0 && returnSlot < PartyRoster.PARTY_SIZE && this.slot < 0 && this.hatchTicks == 0 ? returnSlot : -1;
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
    /** Time a Digitama still needs to hatch; zero for a hatched Digimon. */
    public int hatchTicks() { return hatchTicks; }
    /** A Digitama from the scan, still waiting to hatch: it stays in the Digivice and cannot join the party. */
    public boolean egg() { return hatchTicks > 0; }
    public com.digicube.digimon.EvolutionState evolution() { return com.digicube.digimon.EvolutionState.load(entityData.getCompoundOrEmpty(com.digicube.digimon.EvolutionState.TAG)); }
    public void saveEvolution(com.digicube.digimon.EvolutionState state) { entityData.put(com.digicube.digimon.EvolutionState.TAG,state.save()); }
    public boolean originRequired() { return evolution().needsOrigin(species); }
    /** The stored form's attacks on manual (AUTO off), one bit per sheet slot. */
    public int manualMask() {
        var attacks = com.digicube.digimon.DigimonSpeciesRegistry.get(species).map(com.digicube.digimon.DigimonSpecies::attacks).orElse(java.util.List.of());
        return com.digicube.digimon.ManualAttacks.mask(attacks, com.digicube.digimon.ManualAttacks.read(entityData));
    }
    /** Puts the stored individual's {@code attack} on manual or back on AUTO, for a partner that is not in the world. */
    public void setManual(Identifier attack, boolean manual) {
        java.util.Set<Identifier> set = com.digicube.digimon.ManualAttacks.read(entityData);
        if (manual) set.add(attack);
        else set.remove(attack);
        com.digicube.digimon.ManualAttacks.write(entityData, set);
    }
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

    /**
     * The party slot this partner goes back to once it can (its rest over, the Digivice with its tamer), or -1. It is in
     * the Digivice meanwhile; this only remembers where it stood.
     */
    public int returnSlot() { return returnSlot; }

    /** A party slot means out in the world; -1 means inside the Digivice. There is nothing in between. */
    void setSlot(int slot) {
        this.slot = slot;
        // A slot given or taken by hand is the tamer's choice: the old place is forgotten.
        returnSlot = -1;
    }

    /** Into the Digivice through no choice of the tamer's (a defeat, the Digivice left behind): it remembers its slot to go back to. */
    void stow() {
        int from = slot;
        setSlot(-1);
        if (from >= 0) returnSlot = from;
    }

    /** The slot to go back to is no longer wanted (taken by another partner, or gone back to). */
    void forgetReturn() { returnSlot = -1; }

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

    /**
     * What a fallen body is snapshotted with that must not come back with it: the fire it burned in (a Burn), the ice
     * on it and its effects (Cracked, Frozen, Inked...). Vanilla clears effects only when the dying body is removed,
     * after the Digivice took the snapshot, and never puts the fire out.
     */
    private static final String[] FALL_STATE = {net.minecraft.world.entity.Entity.TAG_FIRE, "TicksFrozen", "active_effects"};

    /**
     * Marks a defeat: the partner must rest this long in the Digivice before its first regeneration pulse, and comes
     * back without the fire, ice or effects it fell with ({@link #FALL_STATE}).
     */
    void defeat(int restTicks) {
        this.restTicks = Math.max(0, restTicks);
        for (String tag : FALL_STATE) entityData.remove(tag);
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

    /**
     * Lets {@code ticks} of a Digitama's incubation pass.
     * @return whether it hatched just now
     */
    boolean incubate(int ticks) {
        if (hatchTicks <= 0) return false;
        hatchTicks = Math.max(0, hatchTicks - ticks);
        return hatchTicks == 0;
    }
}
