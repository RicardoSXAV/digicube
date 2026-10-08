package com.digicube.digimon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import java.util.*;

/** One individual's durable resource and transaction. Never shared through a species sheet. */
public final class EvolutionState {
    public static final String TAG="Digivolution";
    public enum Phase { RESTING, EVOLVING, EVOLVED, REVERTING }
    public Phase phase=Phase.RESTING;
    public Identifier origin, source, target, migrationChampion;
    public int charge,cooldown,rechargeTick,duration;
    public long sequence,start;
    public boolean initialized,fee;
    public String rejection="";
    public final Set<Identifier> completed=new LinkedHashSet<>();
    /**
     * The Champion the first digivolution took, for good: from then on the Rookie digivolves into it alone. Set when
     * that digivolution commits, or when a Champion with no Rookie form is given one.
     */
    public Identifier line;
    /** Routes the tamer has seen ready in this Digimon's tree; a ready route not in here is news. */
    public final Set<Identifier> noticed=new LinkedHashSet<>();
    public boolean transitioning(){return phase==Phase.EVOLVING||phase==Phase.REVERTING;}
    public boolean needsOrigin(Identifier current){return EvolutionRules.champion(current)&&!EvolutionRules.validOrigin(origin,current);}
    /**
     * The Champion this individual is bound to while it is {@code current}, or null while its choice is still open. A
     * Champion with no Rookie form is bound to the form it is. Saves from before the choice existed bind to the
     * Champion they already reached.
     */
    public Identifier line(Identifier current) {
        if(current==null)return line;
        Identifier rookie=EvolutionRules.rookie(current)?current:EvolutionRules.validOrigin(origin,current)?origin:null;
        if(rookie==null)return EvolutionRules.champion(current)?current:line;
        if(line!=null&&EvolutionRules.validOrigin(rookie,line))return line;
        for(Identifier reached:completed)if(EvolutionRules.validOrigin(rookie,reached))return reached;
        if(migrationChampion!=null&&EvolutionRules.validOrigin(rookie,migrationChampion))return migrationChampion;
        return null;
    }
    /** What {@code current} at {@code level} may digivolve into now: its line once bound, else every ready route. */
    public List<Identifier> choices(Identifier current,int level) {
        var ready=EvolutionRules.targets(current,level);var bound=line(current);
        return bound==null?ready:ready.stream().filter(bound::equals).toList();
    }
    /** Whether {@code current} has a ready route the tamer has not looked at in its tree yet. */
    public boolean news(Identifier current,int level) {
        return line(current)==null&&choices(current,level).stream().anyMatch(t->!noticed.contains(t));
    }
    public void unlock(Identifier current,int level) {
        if(!initialized&&EvolutionRules.rookie(current)&&level>=Progression.CHAMPION_LEVEL){charge=Progression.DIGISOUL_CAPACITY;initialized=true;}
    }
    public void recover(boolean eligible) {
        if(cooldown>0)cooldown--;
        if(!eligible||!initialized||charge>=Progression.DIGISOUL_CAPACITY){rechargeTick=0;return;}
        if(++rechargeTick>=Progression.DIGISOUL_RECHARGE_INTERVAL){charge++;rechargeTick=0;}
    }
    public void refund() {if(fee){charge=Math.min(Progression.DIGISOUL_CAPACITY,charge+Progression.DIGISOUL_FEE);fee=false;}}
    public CompoundTag save() {
        var tag=new CompoundTag();tag.putInt("schema",1);tag.putString("phase",phase.name());
        if(origin!=null)tag.putString("origin",origin.toString());if(source!=null)tag.putString("source",source.toString());if(target!=null)tag.putString("target",target.toString());
        if(migrationChampion!=null)tag.putString("migration",migrationChampion.toString());
        tag.putInt("charge",charge);tag.putBoolean("initialized",initialized);tag.putInt("cooldown",cooldown);tag.putBoolean("fee",fee);
        tag.putLong("sequence",sequence);tag.putLong("start",start);tag.putInt("duration",duration);tag.putInt("recharge",rechargeTick);
        tag.putString("rejection",rejection);tag.putString("completed",String.join(",",completed.stream().map(Object::toString).toList()));
        if(line!=null)tag.putString("line",line.toString());if(!noticed.isEmpty())tag.putString("noticed",String.join(",",noticed.stream().map(Object::toString).toList()));return tag;
    }
    public static EvolutionState load(CompoundTag tag) {
        var s=new EvolutionState();try{s.phase=Phase.valueOf(tag.getStringOr("phase","RESTING"));}catch(IllegalArgumentException ignored){s.phase=Phase.RESTING;}
        s.origin=optionalId(tag,"origin");s.source=optionalId(tag,"source");s.target=optionalId(tag,"target");s.migrationChampion=optionalId(tag,"migration");
        s.charge=Math.clamp(tag.getIntOr("charge",0),0,Progression.DIGISOUL_CAPACITY);s.initialized=tag.getBooleanOr("initialized",false);
        s.cooldown=Math.clamp(tag.getIntOr("cooldown",0),0,Progression.EVOLUTION_COOLDOWN);s.fee=tag.getBooleanOr("fee",false);
        s.sequence=Math.max(0,tag.getLongOr("sequence",0));s.start=tag.getLongOr("start",0);s.duration=tag.getIntOr("duration",0);s.rechargeTick=Math.clamp(tag.getIntOr("recharge",0),0,1);
        for(String name:tag.getStringOr("completed","").split(",")){if(name.isBlank())continue;var id=Identifier.tryParse(name);if(id!=null&&s.completed.size()<256)s.completed.add(id);}
        for(String name:tag.getStringOr("noticed","").split(",")){if(name.isBlank())continue;var id=Identifier.tryParse(name);if(id!=null&&s.noticed.size()<256)s.noticed.add(id);}
        s.line=optionalId(tag,"line");
        s.rejection=tag.getStringOr("rejection","");
        return s;
    }
    private static Identifier optionalId(CompoundTag tag,String key){String value=tag.getStringOr(key,"");return value.isBlank()?null:Identifier.tryParse(value);}
}
