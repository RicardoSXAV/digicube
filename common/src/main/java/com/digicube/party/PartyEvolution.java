package com.digicube.party;

import com.digicube.digimon.*;
import com.digicube.entity.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Authenticated party mutations, reserve parity and owner-wide combat gating. */
public final class PartyEvolution {
    private PartyEvolution() {}
    public static boolean currentIntent(ServerPlayer player,UUID id,long generation,long sequence) {
        var data=PartySavedData.get(player.level().getServer());var member=data.roster().get(id);
        if(member==null||!member.owner().equals(player.getUUID())||member.generation()!=generation)return false;
        var live=data.live.get(id);return (live==null?member.evolution():live.evolution()).sequence==sequence;
    }
    public static String action(ServerPlayer player,UUID id,String action,int value,Identifier choice) {
        var data=PartySavedData.get(player.level().getServer());var member=data.roster().get(id);
        if(member==null||!member.owner().equals(player.getUUID())||!player.isAlive()||player.isSpectator())return "gui.digicube.party.invalid";
        var live=data.live.get(id);var state=live==null?member.evolution():live.evolution();String result="";
        switch(action) {
            case "evolve" -> {if(live==null)return "gui.digicube.evolution.deploy";result=EvolutionController.evolve(live);}
            case "revert" -> {if(live==null)return "gui.digicube.evolution.deploy";result=EvolutionController.revert(live);}
            case "origin" -> {
                if(live!=null||!member.originRequired()||!EvolutionRules.validOrigin(choice,member.species()))return "gui.digicube.evolution.origin";
                state.origin=choice;state.migrationChampion=member.species();state.phase=EvolutionState.Phase.RESTING;state.source=null;state.target=null;state.refund();
                member.editStored(choice,member.level(),false);state.unlock(choice,member.level());
            }
            case "level", "level_add" -> {
                if(action.equals("level_add"))value=(live==null?member.level():live.getLevel())+value;
                if(live!=null){if(state.transitioning())return "gui.digicube.evolution.busy";live.setPartyLevel(value);}
                else {member.editStored(member.species(),value,true);state.unlock(member.species(),member.level());}
            }
            case "charge" -> {state.charge=Math.clamp(value,0,Progression.DIGISOUL_CAPACITY);state.initialized=true;}
            case "preview" -> {
                if(live==null)return "gui.digicube.evolution.deploy";
                if(state.transitioning())return "gui.digicube.evolution.busy";
                if(choice==null||DigimonSpeciesRegistry.get(choice).isEmpty())return "gui.digicube.evolution.route";
                live.previewEvolution(choice,value==32?32:value==16?16:EvolutionTimeline.LONG.duration());
            }
            default -> {return "gui.digicube.party.invalid";}
        }
        if(live!=null)PartyManager.capture(data,member,live);else member.saveEvolution(state);
        data.setDirty();data.session(player.getUUID()).sync.invalidate();return result;
    }
    static void tick(MinecraftServer server,PartySavedData data) {
        long now=server.overworld().getGameTime();
        for(var owner:server.getPlayerList().getPlayers()) {
            var session=data.session(owner.getUUID());
            if(session.lastCombatTick==Long.MAX_VALUE)session.lastCombatTick=now;
            if(owner.onGround()&&!owner.isPassenger()&&owner.isAlive())session.lastSafePosition=new PartySavedData.VecSafePosition(owner.level(),owner.position());
            int hurt=owner.getLastHurtByMobTimestamp(),attack=owner.getLastHurtMobTimestamp();
            if(hurt>0&&hurt!=session.lastOwnerHurtAt||attack>0&&attack!=session.lastOwnerAttackAt)session.lastCombatTick=now;
            session.lastOwnerHurtAt=hurt;session.lastOwnerAttackAt=attack;
            var members=data.roster().owned(owner.getUUID());
            for(var member:members) {
                var live=data.live.get(member.id());
                if(live!=null&&live.getTarget()!=null&&live.getTarget().isAlive()&&live.distanceToSqr(live.getTarget())<64*64&&live.canAttack(live.getTarget()))session.lastCombatTick=now;
            }
            boolean eligible=owner.isAlive()&&!owner.isSpectator()&&now-session.lastCombatTick>=Progression.DIGISOUL_COMBAT_DELAY;
            for(var member:members) {
                if(data.live.containsKey(member.id()))continue;
                var state=member.evolution();
                if(member.originRequired()) {if(member.active()){member.setSlot(-1);data.setDirty();session.sync.invalidate();}continue;}
                // A roster saved while the entity was active must also normalize before reserve recovery.
                if(state.phase!=EvolutionState.Phase.RESTING) {
                    Identifier form=state.phase==EvolutionState.Phase.EVOLVING?state.source:state.origin;
                    if(state.phase==EvolutionState.Phase.EVOLVING)state.refund();
                    if(form!=null&&DigimonSpeciesRegistry.get(form).isPresent())member.editStored(form,member.level(),false);
                    state.phase=EvolutionState.Phase.RESTING;state.cooldown=Progression.EVOLUTION_COOLDOWN;state.source=null;state.target=null;
                }
                state.unlock(member.species(),member.level());state.recover(eligible&&!member.defeated()&&!member.resting()&&EvolutionRules.rookie(member.species()));
                member.saveEvolution(state);data.setDirty();
            }
            if(now%20==0)session.sync.invalidate();
        }
    }
    /** Find supported, loaded ground before a forced mount recall; otherwise use the last grounded owner position. */
    static void safePassengers(PartySavedData data,DigimonEntity mount) {
        for(var passenger:List.copyOf(mount.getPassengers())) {
            Vec3 safe=null;
            for(int radius=0;radius<=12&&safe==null;radius+=2)for(int angle=0;angle<8&&safe==null;angle++) {
                int x=(int)Math.floor(mount.getX()+Math.cos(angle*Math.PI/4)*radius),z=(int)Math.floor(mount.getZ()+Math.sin(angle*Math.PI/4)*radius);
                if(!mount.level().hasChunk(x>>4,z>>4))continue;
                for(int y=(int)Math.floor(mount.getY());y>=mount.level().getMinY();y--) {
                    safe=net.minecraft.world.entity.vehicle.DismountHelper.findSafeDismountLocation(passenger.getType(),mount.level(),new net.minecraft.core.BlockPos(x,y,z),true);
                    if(safe!=null)break;
                }
            }
            passenger.stopRiding();
            if(safe!=null){passenger.teleportTo(safe.x,safe.y,safe.z);passenger.setDeltaMovement(Vec3.ZERO);passenger.fallDistance=0;}
            else if(passenger instanceof ServerPlayer player) {
                var last=data.session(player.getUUID()).lastSafePosition;
                Vec3 remembered=null;
                if(last!=null&&last.level().hasChunk((int)Math.floor(last.position().x)>>4,(int)Math.floor(last.position().z)>>4))
                    remembered=net.minecraft.world.entity.vehicle.DismountHelper.findSafeDismountLocation(player.getType(),last.level(),net.minecraft.core.BlockPos.containing(last.position()),true);
                if(remembered!=null)player.teleportTo(last.level(),remembered.x,remembered.y,remembered.z,Set.of(),player.getYRot(),player.getXRot(),false);
                else player.teleport(player.findRespawnPositionAndUseSpawnBlock(false,net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
                player.setDeltaMovement(Vec3.ZERO);player.fallDistance=0;
            }
        }
    }
}
