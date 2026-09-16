package com.digicube.party;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.*;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Actual dedicated-server lifecycle suite with a server-side player and a packet sink. No client UI. */
public final class EvolutionScenario {
    private static boolean started,done;
    private static ServerPlayer owner;
    private static DigimonEntity partner;
    private static PartySavedData data;
    private static PartyMember member;
    private static int step,waitUntil,startedTick;
    private static float fraction;
    private static int cases,routeIndex,routeStep;
    private static java.util.List<DigimonSpecies> routeSources;
    private static boolean mounted;
    private static DigimonEntity combatPrey;
    private static int combatStep,combatDeadline,commitTick;

    private EvolutionScenario() {}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label+" step="+step);}
    private static void pass(String name,String detail){cases++;Constants.LOG.info("[scenario] PASS {} {}",name,detail);}
    public static void tick(ServerLevel level) {
        if(done)return;
        try {
            if(!started){setup(level);return;}
            owner.tickCount++; // A connected client's listener normally advances its player clock.
            owner.setHealth(owner.getMaxHealth());
            int now=level.getServer().getTickCount();
            if(now%10==0)level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,new net.minecraft.world.phys.AABB(-18,298,-18,18,315,18)).stream().filter(m->m!=partner&&m!=combatPrey).forEach(net.minecraft.world.entity.Entity::discard);
            if(now<waitUntil)return;
            if(step>=13){routes(level,now);return;}
            switch(step++) {
                case 0 -> {
                    check(EvolutionController.evolve(partner).endsWith("level"),"level 19 gate");
                    float hp=partner.getHealth()/partner.getMaxHealth();
                    check(PartyEvolution.action(owner,member.id(),"level",20,null).isEmpty(),"dev deployed level");
                    check(Math.abs(hp-partner.getHealth()/partner.getMaxHealth())<1e-6,"level no healing");
                    fraction=hp;check(partner.evolution().charge==3600,"one-time unlock grant");
                    check(EvolutionController.evolve(partner).isEmpty(),"accept long");
                    check(partner.evolution().duration==EvolutionTimeline.LONG.duration(),"first duration");tracking(level);startedTick=now;waitUntil=now+(int)EvolutionTimeline.LONG.leadIn();
                    pass("evolution_unlock","19 rejected; 20 accepted; fee=360");
                }
                case 1 -> {
                    check(partner.evolution().transitioning()&&partner.getSpeciesId().equals(Constants.id("agumon")),"absorption retains source and transition lock");tracking(level);pass("evolution_tracking","two vanilla tracking-packet stand-ins agree; late tracking retains start and sequence");float hp=partner.getHealth();
                    partner.hurtServer(level,level.damageSources().generic(),3);partner.heal(3);
                    check(partner.getHealth()==hp,"transition damage/heal suppressed");
                    check(!partner.isAttackReady(partner.getSpecies().orElseThrow().attacks().getFirst()),"no attack leak");
                    check(!EvolutionController.evolve(partner).isEmpty()&&partner.evolution().charge==3240,"duplicate intent charges once");
                    var saved=save(partner);var restored=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.LOAD);
                    try(var problems=new net.minecraft.util.ProblemReporter.ScopedCollector(Constants.LOG)){restored.load(net.minecraft.world.level.storage.TagValueInput.create(problems,level.registryAccess(),saved));}
                    check(restored.getSpeciesId().equals(Constants.id("agumon"))&&!restored.evolution().transitioning()&&restored.evolution().charge==3600,"interrupted load refund");
                    check(restored.evolution().completed.isEmpty()&&restored.getUUID().equals(partner.getUUID()),"interrupted identity and history");
                    for(int boundary:new int[]{0,2,8,11,12,20,28,31,40,56,59,60,72,100,116,160,196,219}) {
                        var snapshot=saved.copy();var transaction=EvolutionState.load(snapshot.getCompoundOrEmpty(EvolutionState.TAG));transaction.start=level.getGameTime()-boundary;snapshot.put(EvolutionState.TAG,transaction.save());
                        var recovered=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.LOAD);
                        try(var problems=new net.minecraft.util.ProblemReporter.ScopedCollector(Constants.LOG)){recovered.load(net.minecraft.world.level.storage.TagValueInput.create(problems,level.registryAccess(),snapshot));}
                        check(recovered.getSpeciesId().equals(Constants.id("agumon"))&&recovered.evolution().charge==3600&&!recovered.evolution().transitioning()&&recovered.evolution().completed.isEmpty(),"save boundary "+boundary);
                    }
                    waitUntil=startedTick+EvolutionTimeline.LONG.duration();
                    pass("evolution_recall_reload","interrupted entity snapshot returns source; one refund; UUID retained");
                }
                case 2 -> {
                    check(partner.getSpeciesId().equals(Constants.id("greymon")),"commit at long duration");check(partner.evolutionEvent()==null,"commit clears tracked event with species");
                    check(partner.evolution().charge==3240,"drain begins after commit");check(partner.evolution().completed.contains(Constants.id("greymon")),"history commit");
                    check(Math.abs(fraction-partner.getHealth()/partner.getMaxHealth())<1e-6,"form no healing");
                    check(EvolutionController.revert(partner).isEmpty(),"return accepted");waitUntil=now+16;
                }
                case 3 -> {check(partner.getSpeciesId().equals(Constants.id("agumon")),"return16");check(partner.evolution().cooldown==200,"return cooldown");waitUntil=now+200;}
                case 4 -> {check(EvolutionController.evolve(partner).isEmpty(),"repeat accepted");check(partner.evolution().duration==32,"short repeat");waitUntil=now+32;}
                case 5 -> {check(partner.getSpeciesId().equals(Constants.id("greymon")),"short committed");pass("evolution_first_repeat","220/32/16 exact ticks; damage blocked; history durable");partner.evolution().charge=1;waitUntil=now+17;}
                case 6 -> {
                    check(partner.getSpeciesId().equals(Constants.id("agumon"))&&partner.evolution().phase==EvolutionState.Phase.RESTING,"depletion returns");
                    pass("digisoul_deplete","last unit spent then 16-tick return; no Champion attack extension");
                    PartyManager.select(owner,member.id(),-1);check(!data.live.containsKey(member.id()),"recall removes live incarnation");
                    var s=member.evolution();s.charge=0;s.rechargeTick=0;member.saveEvolution(s);data.session(owner.getUUID()).lastCombatTick=level.getGameTime()-500;
                    owner.tickCount=10000;waitUntil=now+20;
                }
                case 7 -> {
                    check(member.evolution().charge==10,"reserve recharge exactly10 after20");
                    data.session(owner.getUUID()).lastCombatTick=level.getGameTime();waitUntil=now+20;
                }
                case 8 -> {
                    check(member.evolution().charge==10,"party combat blocks reserve recharge");pass("digisoul_recharge","reserve 1/2 ticks; owner combat blocks");
                    float hp=member.health()/member.maxHealth();PartyEvolution.action(owner,member.id(),"level",25,null);
                    check(member.level()==25&&member.xp()==0&&Math.abs(member.health()/member.maxHealth()-hp)<1e-6,"reserve level parity");
                    pass("dev_party_level","live/reserve retain HP fraction; XP cleared");
                    PartyEvolution.action(owner,member.id(),"charge",3600,null);check(PartyManager.select(owner,member.id(),0).isEmpty(),"redeploy");partner=data.live.get(member.id());partner.setNoAi(true);partner.setPos(0,301,0);partner.setOnGround(true);partner.evolution().cooldown=0;
                    level.setBlock(new BlockPos(0,303,0),Blocks.STONE.defaultBlockState(),3);
                    check(EvolutionController.evolve(partner).endsWith("space")&&partner.evolution().charge==3600,"space rejection free");level.setBlock(new BlockPos(0,303,0),Blocks.AIR.defaultBlockState(),3);
                    check(EvolutionController.evolve(partner).isEmpty(),"begin before obstruction");level.setBlock(new BlockPos(0,303,0),Blocks.STONE.defaultBlockState(),3);waitUntil=now+32;
                }
                case 9 -> {
                    check(partner.getSpeciesId().equals(Constants.id("agumon"))&&partner.evolution().charge==3600&&!partner.evolution().transitioning(),"commit obstruction refund");
                    level.setBlock(new BlockPos(0,303,0),Blocks.AIR.defaultBlockState(),3);pass("evolution_blocked_space","preflight free; blocked commit refunded once");
                    var champion=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.COMMAND);champion.initializeAs(DigimonSpeciesRegistry.getOrThrow(Constants.id("greymon")),20);var old=PartyManager.give(owner,champion);
                    check(old.originRequired()&&!old.active()&&!data.live.containsKey(old.id()),"unresolved owned Champion safely stored");
                    check(PartyEvolution.action(owner,old.id(),"origin",0,Constants.id("gomamon")).endsWith("origin"),"unrelated origin rejected");
                    check(PartyEvolution.action(owner,old.id(),"origin",0,Constants.id("agumon")).isEmpty()&&old.species().equals(Constants.id("agumon")),"explicit origin repair");
                    var noRoute=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.COMMAND);noRoute.initializeAs(DigimonSpeciesRegistry.getOrThrow(Constants.id("gesomon")),20);var unresolved=PartyManager.give(owner,noRoute);check(unresolved.originRequired()&&!unresolved.active()&&EvolutionRules.origins(unresolved.species()).isEmpty(),"no ancestry guessed");
                    pass("evolution_origin_migration","direct Champions stored; explicit matching Rookie only; no-route retained");
                    partner.evolution().cooldown=0;EvolutionController.evolve(partner);waitUntil=now+32;
                }
                case 10 -> {
                    check(partner.evolution().phase==EvolutionState.Phase.EVOLVED,"evolved for recall");
                    var storedCharge=partner.evolution().charge;PartyManager.disconnect(owner);check(member.species().equals(Constants.id("agumon"))&&member.evolution().charge==storedCharge,"disconnect normalizes without refill");
                    PartyManager.select(owner,member.id(),-1);check(PartyManager.select(owner,member.id(),0).isEmpty(),"reselect after disconnect");PartyManager.tick(level.getServer());partner=data.live.get(member.id());check(partner!=null,"party restored");
                    partner.setPos(0,301,0);partner.setNoAi(true);partner.setOnGround(true);partner.evolution().cooldown=0;partner.evolution().charge=3600;EvolutionController.evolve(partner);waitUntil=now+32;
                }
                case 11 -> {
                    partner.setHealth(0);waitUntil=now+2;
                }
                case 12 -> {
                    check(member.health()==0&&member.species().equals(Constants.id("agumon"))&&member.restTicks()>0&&!data.live.containsKey(member.id()),"defeat resting form");
                    PartyEvolution.action(owner,member.id(),"level",30,null);check(member.health()==0&&member.restTicks()>0,"dead level preserves rest");
                    pass("evolution_defeat","stored Rookie at zero HP; dev levelling cannot revive");
                    routeSources=DigimonSpeciesRegistry.all().stream().filter(v->EvolutionRules.target(v.id(),20).isPresent()).sorted(java.util.Comparator.comparing(v->v.id().toString())).toList();
                }
                default -> throw new AssertionError("unexpected step");
            }
        } catch(RuntimeException|AssertionError error){Constants.LOG.error("[scenario] FAIL evolution_checks",error);done=true;level.getServer().halt(false);}
    }
    private static void routes(ServerLevel level,int now) {
        if(routeIndex>=routeSources.size()){combat(level,now);return;}
        var source=routeSources.get(routeIndex);var target=EvolutionRules.target(source.id(),20).orElseThrow();
        switch(routeStep++) {
            case 0 -> {
                var fresh=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.COMMAND);fresh.initializeAs(source,20);
                member=PartyManager.give(owner,fresh);partner=data.live.get(member.id());check(partner!=null,"route deployed");
                partner.setNoAi(true);partner.setPos(0,301,0);partner.setOnGround(true);
                var generation=member.generation();var sequence=partner.evolution().sequence;
                check(PartyEvolution.currentIntent(owner,member.id(),generation,sequence),"current intent");
                check(!PartyEvolution.currentIntent(owner,member.id(),generation-1,sequence),"old generation refused");
                check(!PartyEvolution.currentIntent(owner,UUID.randomUUID(),generation,sequence),"foreign member refused");
                check(EvolutionController.evolve(partner).isEmpty(),"route long accepted");
                check(!PartyEvolution.currentIntent(owner,member.id(),generation,sequence),"replayed sequence refused");
                var tracked=partner.evolutionEvent();var late=EvolutionEvent.decode(tracked.encode());
                check(late.equals(tracked)&&level.getGameTime()+80-late.start()==80,"late observer uses current phase");
                waitUntil=now+EvolutionTimeline.LONG.duration();
            }
            case 1 -> {check(partner.getSpeciesId().equals(target),"route long committed");check(partner.getUUID().equals(member.id()),"route UUID stable");EvolutionController.revert(partner);waitUntil=now+16;}
            case 2 -> {check(partner.getSpeciesId().equals(source.id()),"route return committed");waitUntil=now+200;}
            case 3 -> {check(EvolutionController.evolve(partner).isEmpty()&&partner.evolution().duration==32,"route repeat accepted");waitUntil=now+42;}
            case 4 -> {
                check(partner.getSpeciesId().equals(target),"route repeat committed");
                check(partner.getSpecies().orElseThrow().attacks().isEmpty()||partner.getSpecies().orElseThrow().attacks().stream().anyMatch(partner::isAttackReady),"post-evolution authored attacks resume");
                mounted=partner.getBody().mount().isPresent();
                if(mounted){check(owner.startRiding(partner,true,true),"ride evolved mount");partner.setPos(0,310,0);partner.setNoGravity(true);}
                partner.evolution().charge=0;waitUntil=now+(mounted?2:17);
            }
            case 5 -> {
                if(mounted){check(!owner.isPassenger()&&!data.live.containsKey(member.id())&&!member.active(),"mounted expiry safely stores");check(owner.getY()>=301&&owner.getY()<302&&owner.fallDistance==0,"rider on supported ground");}
                else check(partner.getSpeciesId().equals(source.id()),"route depletion return");
                check(member.species().equals(source.id()),"stored resting form");
                PartyManager.select(owner,member.id(),-1);owner.setPos(10,301,0);
                pass("evolution_route",source.id()+" -> "+target+" long/short/return; stable UUID; stale intent rejected; authored attack readiness checked (empty move sets unchanged); mounted="+mounted);
                routeIndex++;routeStep=0;
            }
            default -> throw new AssertionError("route phase");
        }
    }
    private static void combat(ServerLevel level,int now) {
        switch(combatStep) {
            case 0 -> {
                var fresh=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.COMMAND);fresh.initializeAs(DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")),20);
                member=PartyManager.give(owner,fresh);partner=data.live.get(member.id());partner.setPos(0,301,0);partner.setOnGround(true);partner.setYRot(0);
                combatPrey=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.COMMAND);combatPrey.initializeAs(DigimonSpeciesRegistry.getOrThrow(Constants.id("golemon")),20);combatPrey.setPos(0,301,8);combatPrey.setNoAi(true);level.addFreshEntity(combatPrey);
                partner.setTarget(combatPrey);check(EvolutionController.evolve(partner).isEmpty(),"combat request accepted");combatStep++;waitUntil=now+80;
            }
            case 1 -> {check(combatPrey.getHealth()==combatPrey.getMaxHealth()&&!partner.isAttacking(),"no attack during transform");combatStep++;waitUntil=now+EvolutionTimeline.LONG.duration()-80;}
            case 2 -> {check(partner.getSpeciesId().equals(Constants.id("greymon")),"combat commit");commitTick=now;combatDeadline=now+600;combatStep++;}
            case 3 -> {
                if(combatPrey.getHealth()<combatPrey.getMaxHealth()){pass("evolution_combat_resume","first landed Champion hit "+(now-commitTick)+" ticks after commit; no transition attack");combatPrey.discard();combatPrey=null;PartyManager.select(owner,member.id(),-1);combatStep++;}
                else check(now<combatDeadline,"combat resumed within30s");
            }
            case 4 -> {
                var fresh=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.COMMAND);fresh.initializeAs(DigimonSpeciesRegistry.getOrThrow(Constants.id("gomamon")),20);member=PartyManager.give(owner,fresh);partner=data.live.get(member.id());partner.setNoAi(true);partner.setPos(0,301,0);
                for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y=301;y<=305;y++)level.setBlock(new BlockPos(x,y,z),Blocks.WATER.defaultBlockState(),3);
                combatStep++;waitUntil=now+4;
            }
            case 5 -> {check(partner.isUnderWater(),"water fixture");check(EvolutionController.evolve(partner).isEmpty(),"aquatic route accepted underwater");combatStep++;waitUntil=now+EvolutionTimeline.LONG.duration();}
            case 6 -> {check(partner.getSpeciesId().equals(Constants.id("ikkakumon")),"underwater commit");PartyManager.select(owner,member.id(),-1);pass("evolution_water","underwater Gomamon to Ikkakumon; recall restores Rookie");finish(level);}
        }
    }
    private static void tracking(ServerLevel level) {
        var tracker=new ServerEntity(level,partner,3,true,new ServerEntity.Synchronizer() {
            @Override public void sendToTrackingPlayers(net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> p) {}
            @Override public void sendToTrackingPlayersAndSelf(net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> p) {}
            @Override public void sendToTrackingPlayersFiltered(net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> p,java.util.function.Predicate<ServerPlayer> filter) {}
        });
        for(int i=0;i<2;i++) {
            var observer=new ServerPlayer(level.getServer(),level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"Observer"+i),ClientInformation.createDefault());
            var found=new ArrayList<EvolutionEvent>();tracker.sendPairingData(observer,packet->{
                if(packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket sync) {
                    var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
                    net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buffer,sync);
                    var decoded=net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer);buffer.release();
                    for(var value:decoded.packedItems())if(value.value() instanceof String text){var event=EvolutionEvent.decode(text);if(event!=null)found.add(event);}
                }
            });
            check(found.contains(partner.evolutionEvent()),"vanilla observer packet round trip");
        }
    }
    private static net.minecraft.nbt.CompoundTag save(DigimonEntity entity) {
        try(var problems=new net.minecraft.util.ProblemReporter.ScopedCollector(Constants.LOG)) {var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(problems,entity.registryAccess());entity.saveWithoutId(out);return out.buildResult();}
    }
    private static void setup(ServerLevel level) {
        started=true;var server=level.getServer();server.tickRateManager().requestGameToSprint(20000);
        for(int x=-2;x<=1;x++)for(int z=-2;z<=1;z++)level.setChunkForced(x,z,true);
        for(int x=-16;x<=16;x++)for(int z=-16;z<=16;z++)for(int y=300;y<320;y++)level.setBlock(new BlockPos(x,y,z),(y==300?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
        level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,new net.minecraft.world.phys.AABB(-18,298,-18,18,315,18)).forEach(net.minecraft.world.entity.Entity::discard);
        for(int x=-17;x<=17;x++)for(int z=-17;z<=17;z++)if(Math.abs(x)==17||Math.abs(z)==17)for(int y=300;y<320;y++)level.setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),3);
        var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"EvolutionTest");
        owner=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,io.netty.channel.ChannelFutureListener listener) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,io.netty.channel.ChannelFutureListener listener,boolean flush) {}
            @Override public boolean isConnected(){return true;}
            @Override public void flushChannel() {}
        };
        new net.minecraft.server.network.ServerGamePacketListenerImpl(server,connection,owner,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));
        owner.setPos(10,301,0);owner.setInvulnerable(true);server.getPlayerList().getPlayers().add(owner);server.getPlayerList().getPlayersByUUID().put(owner.getUUID(),owner);level.addNewPlayer(owner);
        data=PartySavedData.get(server);var source=DCEntityTypes.DIGIMON.create(level,EntitySpawnReason.COMMAND);source.initializeAs(DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")),19);
        member=PartyManager.give(owner,source);partner=data.live.get(member.id());check(partner!=null,"live admission");partner.setPos(0,301,0);partner.setOnGround(true);partner.setNoAi(true);partner.setHealth(partner.getMaxHealth()*.43F);
        waitUntil=server.getTickCount()+2;
    }
    private static void finish(ServerLevel level) {
        done=true;PartyManager.disconnect(owner);level.getServer().getPlayerList().getPlayers().remove(owner);level.getServer().getPlayerList().getPlayersByUUID().remove(owner.getUUID());owner.discard();
        Constants.LOG.info("[scenario] PASS evolution_checks cases={}",cases);level.getServer().halt(false);
    }
}
