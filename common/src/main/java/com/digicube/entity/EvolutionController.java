package com.digicube.entity;

import com.digicube.digimon.*;
import com.digicube.party.PartyManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import static com.digicube.digimon.EvolutionState.Phase.*;

/** The single server lifecycle used by player actions, depletion, recall, saves and tooling. */
public final class EvolutionController {
    private EvolutionController() {}
    public static String evolve(DigimonEntity entity) {
        var s=entity.evolution();s.unlock(entity.getSpeciesId(),entity.getLevel());
        if(s.transitioning()||s.phase==EVOLVED)return reject(s,"busy");
        if(!entity.isAlive())return reject(s,"resting");
        if(entity.isVehicle()||entity.isPassenger())return reject(s,"dismount");
        if(entity.isFlyingMovement()||!entity.onGround()&&!entity.isInWater())return reject(s,"land");
        if(entity.hasEffect(com.digicube.registry.DCEffects.CONSTRICTED))return reject(s,"captured");
        if(entity.getLevel()<Progression.CHAMPION_LEVEL)return reject(s,"level");
        var target=EvolutionRules.target(entity.getSpeciesId(),entity.getLevel()).orElse(null);
        if(target==null)return reject(s,"route");
        if(s.cooldown>0)return reject(s,"cooldown");
        if(s.charge<Progression.DIGISOUL_MINIMUM)return reject(s,"charge");
        if(!fits(entity,target))return reject(s,"space");
        s.origin=entity.getSpeciesId();s.charge-=Progression.DIGISOUL_FEE;s.fee=true;
        begin(entity,target,s.completed.contains(target)?EvolutionTimeline.SHORT.duration():EvolutionTimeline.LONG.duration(),EVOLVING);return "";
    }
    private static String reject(EvolutionState s,String reason){s.rejection="gui.digicube.evolution."+reason;return s.rejection;}
    private static void begin(DigimonEntity entity,Identifier target,int ticks,EvolutionState.Phase phase) {
        var s=entity.evolution();entity.stopForEvolution();s.source=entity.getSpeciesId();s.target=target;s.duration=ticks;
        s.phase=phase;s.start=entity.level().getGameTime();s.sequence++;s.rejection="";entity.syncEvolutionEvent(false);
        openingSound(entity,ticks);
        PartyManager.progressChanged(entity);
    }
    public static String revert(DigimonEntity entity) {
        var s=entity.evolution();if(s.phase!=EVOLVED)return reject(s,"busy");
        if(entity.isVehicle()||entity.isPassenger())return reject(s,"dismount");
        if(entity.isFlyingMovement())return reject(s,"land");
        if(!EvolutionRules.validOrigin(s.origin,entity.getSpeciesId()))return reject(s,"origin");
        if(!fits(entity,s.origin))return reject(s,"space");
        begin(entity,s.origin,16,REVERTING);return "";
    }
    public static boolean fits(DigimonEntity entity,Identifier target) {
        var species=DigimonSpeciesRegistry.get(target).orElse(null);if(species==null)return false;
        var body=species.body();double r=body.dimensions().width()/2;
        var box=new AABB(entity.getX()-r,entity.getY()+.001,entity.getZ()-r,entity.getX()+r,entity.getY()+body.dimensions().height(),entity.getZ()+r);
        return entity.level().noCollision(entity,box)&&entity.level().getWorldBorder().isWithinBounds(box)
                && (!entity.isUnderWater()||species.locomotion().canSwim());
    }
    public static void tick(DigimonEntity entity) {
        if(!(entity.level() instanceof ServerLevel)||!entity.isOwned())return;
        var s=entity.evolution();s.unlock(entity.getSpeciesId(),entity.getLevel());
        if(!entity.isAlive())return;
        var preview=entity.evolutionEvent();if(preview!=null&&preview.preview()&&!s.transitioning())cue(entity,preview.duration(),entity.level().getGameTime()-preview.start());
        if(s.transitioning()) {
            entity.freezeForEvolution();
            long elapsed=entity.level().getGameTime()-s.start;
            cue(entity,s.duration,elapsed);
            if(entity.level().getGameTime()-s.start<s.duration)return;
            if(s.target==null||!fits(entity,s.target)) {
                if(s.phase==EVOLVING){normalize(entity);reject(s,"space");}
                else PartyManager.storeForEvolution(entity);
                return;
            }
            s.rejection="";boolean upward=s.phase==EVOLVING;entity.changeEvolutionForm(s.target);
            if(upward){s.completed.add(s.target);s.fee=false;s.phase=EVOLVED;}else{s.phase=RESTING;s.cooldown=Progression.EVOLUTION_COOLDOWN;}
            entity.syncEvolutionEvent(false);PartyManager.progressChanged(entity);return;
        }
        if(s.phase==EVOLVED) {
            if(entity.getLevel()<Progression.CHAMPION_LEVEL)s.charge=0;
            if(s.charge>0)s.charge--;
            if(s.charge==600||s.charge==200)if(entity.getOwner() instanceof net.minecraft.server.level.ServerPlayer player)
                player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("gui.digicube.evolution.warning",s.charge/20));
            if(s.charge==0) {
                entity.stopForEvolution();
                if(entity.isVehicle()||entity.isPassenger()||entity.isFlyingMovement()||!fits(entity,s.origin))PartyManager.storeForEvolution(entity);
                else begin(entity,s.origin,16,REVERTING);
            }
        } else s.recover(PartyManager.canRecoverDigiSoul(entity)&&EvolutionRules.rookie(entity.getSpeciesId()));
    }
    /** Normalize before capture; transaction fee is refunded exactly once, history only on commit. */
    public static void normalize(DigimonEntity entity) {
        var s=entity.evolution();boolean active=s.phase!=RESTING;
        if(s.phase==EVOLVING)s.refund();
        Identifier form=s.phase==EVOLVING?s.source:s.origin;
        if(active&&form!=null&&DigimonSpeciesRegistry.get(form).isPresent())entity.changeEvolutionForm(form);
        s.phase=RESTING;s.source=null;s.target=null;s.fee=false;
        if(active)s.cooldown=Progression.EVOLUTION_COOLDOWN;entity.syncEvolutionEvent(false);
    }
    public static void openingSound(DigimonEntity entity,int duration) {
        // Long audio follows the tracked event on each client, including cancellation and late observers.
        if(EvolutionTimeline.of(duration).longForm())return;
        sound(entity,duration==16?com.digicube.registry.DCSounds.RETURN:duration==32?com.digicube.registry.DCSounds.SHORT:com.digicube.registry.DCSounds.GATHER);
    }
    private static void cue(DigimonEntity entity,int duration,long elapsed) {
            if(duration==32&&elapsed==20)sound(entity,com.digicube.registry.DCSounds.SHORT_REVEAL);
    }
    private static void sound(DigimonEntity entity,net.minecraft.sounds.SoundEvent sound) {
        entity.level().playSound(null,entity.getX(),entity.getY(),entity.getZ(),sound,net.minecraft.sounds.SoundSource.NEUTRAL,.85F,1);
    }
}
