package com.digicube.digimon;

import com.digicube.Constants;
import com.digicube.party.*;
import net.minecraft.nbt.*;
import java.util.*;

/** Durable rules, save compatibility and reserve health arithmetic, independent of a game world. */
public final class EvolutionRegressionTest {
    private static int assertions;
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    public static void main(String[] args) {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();DigimonSpeciesBootstrap.registerBuiltIn();
            var rookie=Constants.id("agumon");var adult=Constants.id("greymon");
            var empty=EvolutionState.load(new CompoundTag());check(empty.origin==null&&empty.source==null&&empty.completed.isEmpty(),"empty identifiers do not become minecraft empty paths");
            check(EvolutionRules.target(rookie,19).isEmpty(),"level 19 locked");check(EvolutionRules.target(rookie,20).orElseThrow().equals(adult),"20 unlocked");
            check(!EvolutionRules.supported(new Evolution(adult,20,1,-1,0,null)),"unsupported bond fails closed");
            check(EvolutionRules.target(Constants.id("koromon"),50).isEmpty(),"baby growth separate");
            int routes=0;for(var s:DigimonSpeciesRegistry.all())if(s.stage()==DigimonStage.CHILD)for(var e:s.evolutions())if(EvolutionRules.supported(e)){routes++;check(EvolutionRules.validOrigin(s.id(),e.target()),"valid authored origin");}
            check(routes==4,"all four authored routes enumerated; update coverage deliberately when content changes");
            var state=new EvolutionState();state.unlock(rookie,19);check(!state.initialized,"not early");state.unlock(rookie,20);check(state.charge==3600&&state.initialized,"first unlock");
            state.charge=0;state.unlock(rookie,21);check(state.charge==0,"no repeated refill");state.recover(false);check(state.charge==0,"combat blocks recovery");
            for(int i=0;i<7199;i++)state.recover(true);check(state.charge==3599,"exact recharge interval");state.recover(true);check(state.charge==3600,"six minutes full");
            state.charge=540;state.fee=true;state.refund();state.refund();check(state.charge==900,"refund exactly once");
            state.origin=rookie;state.phase=EvolutionState.Phase.EVOLVING;state.source=rookie;state.target=adult;state.sequence=8;state.fee=true;
            var loaded=EvolutionState.load(state.save());check(loaded.fee&&loaded.origin.equals(rookie)&&loaded.phase==state.phase&&loaded.sequence==8,"transaction persists");
            check(!loaded.completed.contains(adult),"unfinished never consumes first use");loaded.completed.add(adult);check(EvolutionState.load(loaded.save()).completed.contains(adult),"history persists");
            check(new EvolutionState().needsOrigin(adult),"legacy Champion unresolved");check(!state.needsOrigin(adult),"chosen supported origin valid");
            check(EvolutionRules.origins(Constants.id("gesomon")).isEmpty(),"no invented ancestry");
            check(EvolutionTimeline.LONG.duration()==220&&EvolutionTimeline.SHORT.duration()==32&&EvolutionTimeline.RETURN.duration()==16,"separate preset durations");
            var tag=new CompoundTag();tag.putFloat("Health",13.25F);
            var member=new PartyMember(UUID.randomUUID(),UUID.randomUUID(),rookie,"test",13.25F,35,20,17,-1,9,tag);
            double before=member.health()/(double)member.maxHealth();for(int i=0;i<100;i++){member.editStored(adult,20,false);member.editStored(rookie,20,false);}
            check(member.health()/member.maxHealth()<=before+1e-7,"no cycle healing");check(member.xp()==17&&member.generation()==9,"identity and progression retained");
            member.editStored(rookie,25,true);check(member.xp()==0&&member.level()==25,"dev edit clears XP");
            var dead=new PartyMember(UUID.randomUUID(),member.owner(),rookie,"dead",0,20,1,0,-1,0,new CompoundTag(),6000);dead.editStored(rookie,20,true);check(dead.health()==0&&dead.restTicks()==6000,"dev edit preserves defeat rest");
            var encoded=PartyMember.CODEC.encodeStart(NbtOps.INSTANCE,dead).getOrThrow();var restored=PartyMember.CODEC.parse(NbtOps.INSTANCE,encoded).getOrThrow();check(restored.restTicks()==6000&&restored.level()==20,"old roster codec stays compatible");
            for(int duration:new int[]{16,32,160,220,230})check(EvolutionEvent.decode(new EvolutionEvent(rookie,adult,100,duration,2,false).encode()).duration()==duration,"new and legacy durations decode");
            check(EvolutionTimeline.LONG.leadIn()==60&&EvolutionTimeline.LONG.shed(60)==0,"3 seconds absorption precedes shedding");
            var event=new EvolutionEvent(rookie,adult,100,EvolutionTimeline.LONG.duration(),2,false);check(event.equals(EvolutionEvent.decode(event.encode())),"tracking round trip");check(EvolutionEvent.decode("bad")==null,"malformed event rejected");
            Constants.LOG.info("[evolution-rules] PASS {} assertions; routes={}",assertions,routes);
        } finally {net.minecraft.util.Util.shutdownExecutors();}
    }
}
