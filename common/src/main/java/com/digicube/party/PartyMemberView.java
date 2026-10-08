package com.digicube.party;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Small public summary. Full entity NBT and other players' collections never leave the
 * server. Level and XP come from the live entity while deployed and from the saved
 * roster entry otherwise; the client computes the XP requirement from the same formula.
 * {@code restTicks} is the rest a defeated partner still owes before it regenerates.
 * {@code holding} and {@code attacking} feed the command wheel: standing still on order, and having a live target.
 * {@code soulHeld}: the owner is in creative, where DigiSoul never drains, so the client counts nothing down.
 * {@code manual} is the universal control's AUTO setting, one bit per attack slot set where the attack waits for the
 * tamer's order ({@link com.digicube.digimon.ManualAttacks}); {@code target} is the live target's entity id, -1 without
 * one, so the wheel can name it. {@code line} is the Champion the partner is bound to ({@link
 * com.digicube.digimon.EvolutionState#line}), empty while its choice is open; {@code noticed} has a bit per route of its
 * Baby II or Rookie form ({@link com.digicube.digimon.EvolutionRules#routes}) the tamer has seen ready in its tree.
 * {@code hatchTicks} is the time a Digitama still needs before it hatches ({@link #egg}), 0 once it has.
 */
public record PartyMemberView(UUID id, Identifier species, String nickname, float health, float maxHealth,
                              int level, int xp, int slot, boolean deployed, int restTicks, int soul, String phase,
                              int cooldown, boolean originRequired, String origin, boolean firstEvolution,long generation,long sequence,
                              boolean holding, boolean attacking, boolean soulHeld, int manual, int target, String line, int noticed,
                              int hatchTicks) {
    public PartyMemberView(UUID id,Identifier species,String nickname,float health,float maxHealth,int level,int xp,int slot,boolean deployed,int restTicks,int soul,String phase,
                           int cooldown,boolean originRequired,String origin,boolean firstEvolution,long generation,long sequence,boolean holding,boolean attacking,boolean soulHeld,int manual,int target,String line,int noticed) {
        this(id,species,nickname,health,maxHealth,level,xp,slot,deployed,restTicks,soul,phase,cooldown,originRequired,origin,firstEvolution,generation,sequence,holding,attacking,soulHeld,manual,target,line,noticed,0);
    }
    public PartyMemberView(UUID id,Identifier species,String nickname,float health,float maxHealth,int level,int xp,int slot,boolean deployed,int restTicks,int soul,String phase,
                           int cooldown,boolean originRequired,String origin,boolean firstEvolution,long generation,long sequence,boolean holding,boolean attacking,boolean soulHeld,int manual,int target) {
        this(id,species,nickname,health,maxHealth,level,xp,slot,deployed,restTicks,soul,phase,cooldown,originRequired,origin,firstEvolution,generation,sequence,holding,attacking,soulHeld,manual,target,"",0);
    }
    public PartyMemberView(UUID id,Identifier species,String nickname,float health,float maxHealth,int level,int xp,int slot,boolean deployed,int restTicks,int soul,String phase,
                           int cooldown,boolean originRequired,String origin,boolean firstEvolution,long generation,long sequence,boolean holding,boolean attacking,boolean soulHeld) {
        this(id,species,nickname,health,maxHealth,level,xp,slot,deployed,restTicks,soul,phase,cooldown,originRequired,origin,firstEvolution,generation,sequence,holding,attacking,soulHeld,0,-1);
    }
    public PartyMemberView(UUID id,Identifier species,String nickname,float health,float maxHealth,int level,int xp,int slot,boolean deployed,int restTicks,int soul,String phase,
                           int cooldown,boolean originRequired,String origin,boolean firstEvolution,long generation,long sequence,boolean holding,boolean attacking) {
        this(id,species,nickname,health,maxHealth,level,xp,slot,deployed,restTicks,soul,phase,cooldown,originRequired,origin,firstEvolution,generation,sequence,holding,attacking,false);
    }
    public PartyMemberView(UUID id,Identifier species,String nickname,float health,float maxHealth,int level,int xp,int slot,boolean deployed,int restTicks,int soul,String phase,
                           int cooldown,boolean originRequired,String origin,boolean firstEvolution,long generation,long sequence) {
        this(id,species,nickname,health,maxHealth,level,xp,slot,deployed,restTicks,soul,phase,cooldown,originRequired,origin,firstEvolution,generation,sequence,false,false);
    }
    public PartyMemberView(UUID id,Identifier species,String nickname,float health,float maxHealth,int level,int xp,int slot,boolean deployed,int restTicks) {
        this(id,species,nickname,health,maxHealth,level,xp,slot,deployed,restTicks,0,"RESTING",0,false,"",true,0,0);
    }
    public static PartyMemberView of(PartySavedData data, PartyMember member) {
        String name = member.nickname();
        // The saved entity snapshot is intentionally less frequent than UI health updates.
        var live = data.live.get(member.id());
        var state=live==null?member.evolution():live.evolution();var species=live==null?member.species():live.getSpeciesId();
        var bound=state.line(species);
        var target=bound!=null?bound:com.digicube.digimon.EvolutionRules.target(species,Math.max(20,member.level())).orElse(null);
        return new PartyMemberView(member.id(), species, name.substring(0, Math.min(name.length(), 128)),
                live == null ? member.health() : live.getHealth(),
                live == null ? member.maxHealth() : live.getMaxHealth(),
                live == null ? member.level() : live.getLevel(),
                live == null ? member.xp() : live.getXp(),
                member.slot(), live != null, live == null ? member.restTicks() : 0,state.charge,state.phase.name(),state.cooldown,
                state.needsOrigin(species)&&!PartyManager.creative(data,member.owner()),
                state.origin==null?"":state.origin.toString(),target!=null&&!state.completed.contains(target),member.generation(),state.sequence,
                live != null && live.isHolding(), live != null && live.hasLiveTarget(), PartyManager.creative(data, member.owner()),
                live != null ? live.manualMask() : member.manualMask(), live != null ? live.liveTargetId() : -1,
                lineOf(state, species), noticedMask(state, species), live == null ? member.hatchTicks() : 0);
    }

    /** A Digitama from the scan, still waiting to hatch: it stays in the Digivice and cannot join the party. */
    public boolean egg() { return hatchTicks > 0; }

    private static String lineOf(com.digicube.digimon.EvolutionState state, Identifier species) {
        Identifier line = state.line(species);
        return line == null ? "" : line.toString();
    }

    /** The routes of the Baby II or Rookie form behind {@code species} the tamer has seen ready, one bit each. */
    private static int noticedMask(com.digicube.digimon.EvolutionState state, Identifier species) {
        Identifier chooser = com.digicube.digimon.EvolutionRules.chooser(species) ? species : state.origin;
        if (chooser == null) return 0;
        var routes = com.digicube.digimon.EvolutionRules.routes(chooser);
        int mask = 0;
        for (int i = 0; i < Math.min(31, routes.size()); i++) if (state.noticed.contains(routes.get(i).target())) mask |= 1 << i;
        return mask;
    }

    /** The bound Champion, or null while the choice is open. */
    public Identifier lineId() { return line.isEmpty() ? null : Identifier.tryParse(line); }

    /** Whether the tamer has seen route {@code index} of its Rookie form ready in its tree. */
    public boolean noticed(int index) { return index >= 0 && index < 31 && (noticed & 1 << index) != 0; }

    /** Whether the attack in sheet slot {@code slot} waits for the tamer's order (AUTO off). */
    public boolean manual(int slot) {
        return com.digicube.digimon.ManualAttacks.manual(manual, slot);
    }

    /** The same individual with the attack in {@code slot} put on manual or back on AUTO, as the wheel shows a flip at once. */
    public PartyMemberView withManual(int slot, boolean manual) {
        int mask = manual ? this.manual | 1 << slot : this.manual & ~(1 << slot);
        return new PartyMemberView(id, species, nickname, health, maxHealth, level, xp, slot(), deployed, restTicks,soul,phase,cooldown,originRequired,origin,firstEvolution,generation,sequence,holding,attacking,soulHeld,mask,target,line,noticed,hatchTicks);
    }

    public static PartyMemberView read(FriendlyByteBuf buffer) {
        return new PartyMemberView(buffer.readUUID(), buffer.readIdentifier(), buffer.readUtf(128),
                buffer.readFloat(), buffer.readFloat(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt(),buffer.readVarInt(),buffer.readUtf(16),buffer.readVarInt(),buffer.readBoolean(),buffer.readUtf(256),buffer.readBoolean(),buffer.readVarLong(),buffer.readVarLong(),buffer.readBoolean(),buffer.readBoolean(),buffer.readBoolean(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(256), buffer.readVarInt(), buffer.readVarInt());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeIdentifier(species);
        buffer.writeUtf(nickname, 128);
        buffer.writeFloat(health);
        buffer.writeFloat(maxHealth);
        buffer.writeVarInt(level);
        buffer.writeVarInt(xp);
        buffer.writeVarInt(slot);
        buffer.writeBoolean(deployed);
        buffer.writeVarInt(restTicks);
        buffer.writeVarInt(soul);buffer.writeUtf(phase,16);buffer.writeVarInt(cooldown);buffer.writeBoolean(originRequired);buffer.writeUtf(origin,256);buffer.writeBoolean(firstEvolution);buffer.writeVarLong(generation);buffer.writeVarLong(sequence);
        buffer.writeBoolean(holding);buffer.writeBoolean(attacking);buffer.writeBoolean(soulHeld);
        buffer.writeVarInt(manual);
        buffer.writeVarInt(target);
        buffer.writeUtf(line, 256);
        buffer.writeVarInt(noticed);
        buffer.writeVarInt(hatchTicks);
    }

    /** The same individual with fresher health, keeping identity and progression as they were. */
    public PartyMemberView withHealth(float health, float maxHealth) {
        return new PartyMemberView(id, species, nickname, health, maxHealth, level, xp, slot, deployed, restTicks,soul,phase,cooldown,originRequired,origin,firstEvolution,generation,sequence,holding,attacking,soulHeld,manual,target,line,noticed,hatchTicks);
    }

    /** The same individual with every route ready now marked as seen, as its tree shows at once; the next snapshot confirms it. */
    public PartyMemberView withNoticed(int mask) {
        return new PartyMemberView(id, species, nickname, health, maxHealth, level, xp, slot, deployed, restTicks,soul,phase,cooldown,originRequired,origin,firstEvolution,generation,sequence,holding,attacking,soulHeld,manual,target,line,noticed | mask,hatchTicks);
    }
}
