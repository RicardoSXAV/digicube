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
            var koromon=Constants.id("koromon");
            check(EvolutionRules.targets(koromon,9).isEmpty()&&EvolutionRules.targets(koromon,10).equals(List.of(rookie,Constants.id("betamon"))),"a Baby II grows into either Rookie of its family from level 10");
            check(EvolutionRules.minimumLevel(koromon)==10&&EvolutionRules.minimumLevel(rookie)==20&&!EvolutionRules.validOrigin(koromon,rookie),"growth has its own level and is no Champion return form");
            int routes=0;for(var s:DigimonSpeciesRegistry.all())if(s.stage()==DigimonStage.CHILD)for(var e:s.evolutions())if(EvolutionRules.supported(e)){routes++;check(EvolutionRules.validOrigin(s.id(),e.target()),"valid authored origin");}
            check(routes==16,"all sixteen authored routes enumerated; update coverage deliberately when content changes");
            check(EvolutionRules.target(Constants.id("betamon"),20).orElseThrow().equals(Constants.id("seadramon")),"betamon routes to seadramon at 20");
            // The choice: every ready route is open until the first digivolution commits, then only that one.
            var meramon=Constants.id("meramon");
            check(EvolutionRules.targets(rookie,20).equals(List.of(adult,meramon))&&EvolutionRules.targets(rookie,19).isEmpty(),"agumon offers greymon and meramon at 20, in sheet order");
            check(EvolutionRules.validOrigin(Constants.id("tentomon"),Constants.id("digmon"))&&EvolutionRules.champion(Constants.id("digmon")),"an Armor route counts as a Champion");
            check(EvolutionRules.targets(Constants.id("elecmon"),20).equals(List.of(Constants.id("leomon"),Constants.id("darktyrannomon"))),"elecmon offers leomon and darktyrannomon at 20, in sheet order");
            var open=new EvolutionState();check(open.line(rookie)==null&&open.choices(rookie,20).size()==2,"unbound: both open");
            check(open.news(rookie,20)&&!open.news(rookie,19),"ready routes not looked at are news");
            open.noticed.addAll(open.choices(rookie,20));check(!open.news(rookie,20),"looked at, no news");
            open.line=meramon;check(open.line(rookie).equals(meramon)&&open.choices(rookie,20).equals(List.of(meramon)),"bound: only the chosen form");
            check(!open.news(rookie,20),"a bound Digimon has no choice to announce");
            open.origin=rookie;check(open.line(meramon).equals(meramon),"the Champion form keeps its line");
            var bound=EvolutionState.load(open.save());check(meramon.equals(bound.line)&&bound.noticed.containsAll(List.of(adult,meramon)),"line and noticed persist");
            check(EvolutionState.load(new CompoundTag()).line==null&&EvolutionState.load(new CompoundTag()).noticed.isEmpty(),"old saves load unbound");
            var legacy=new EvolutionState();legacy.completed.add(adult);check(adult.equals(legacy.line(rookie))&&legacy.choices(rookie,20).equals(List.of(adult)),"a save that reached greymon before the choice existed is bound to it");
            var migrated=new EvolutionState();migrated.origin=rookie;migrated.migrationChampion=meramon;check(meramon.equals(migrated.line(rookie)),"a Champion given a Rookie form keeps its Champion");
            check(new EvolutionState().line(Constants.id("gesomon")).equals(Constants.id("gesomon")),"a Champion with no Rookie form is bound to itself");
            check(com.digicube.party.PartyActionPayload.routeValue(rookie,meramon)==2&&meramon.equals(com.digicube.party.PartyActionPayload.route(rookie,2))
                    &&com.digicube.party.PartyActionPayload.route(rookie,0)==null&&com.digicube.party.PartyActionPayload.route(rookie,3)==null,"a route travels as 1 + its index");
            var state=new EvolutionState();state.unlock(rookie,19);check(!state.initialized,"not early");state.unlock(rookie,20);check(state.charge==3600&&state.initialized,"first unlock");
            state.charge=0;state.unlock(rookie,21);check(state.charge==0,"no repeated refill");state.recover(false);check(state.charge==0,"combat blocks recovery");
            for(int i=0;i<7199;i++)state.recover(true);check(state.charge==3599,"exact recharge interval");state.recover(true);check(state.charge==3600,"six minutes full");
            state.charge=540;state.fee=true;state.refund();state.refund();check(state.charge==900,"refund exactly once");
            state.origin=rookie;state.phase=EvolutionState.Phase.EVOLVING;state.source=rookie;state.target=adult;state.sequence=8;state.fee=true;
            var loaded=EvolutionState.load(state.save());check(loaded.fee&&loaded.origin.equals(rookie)&&loaded.phase==state.phase&&loaded.sequence==8,"transaction persists");
            check(!loaded.completed.contains(adult),"unfinished never consumes first use");loaded.completed.add(adult);check(EvolutionState.load(loaded.save()).completed.contains(adult),"history persists");
            check(new EvolutionState().needsOrigin(adult),"legacy Champion unresolved");check(!state.needsOrigin(adult),"chosen supported origin valid");
            check(EvolutionRules.origins(Constants.id("gesomon")).equals(List.of(Constants.id("ganimon"))),"ancestry only from the sheets");
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
