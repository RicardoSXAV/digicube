package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;

import java.util.EnumSet;

/**
 * Defend the tamer: when something hurts the owner, the Digimon goes after it.
 */
public final class OwnerHurtByTargetGoal extends TargetGoal {

    private final DigimonEntity digimon;
    private LivingEntity ownerLastHurtBy;
    private int timestamp;

    public OwnerHurtByTargetGoal(DigimonEntity digimon) {
        super(digimon, false);
        this.digimon = digimon;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        LivingEntity owner = digimon.getOwner();
        if (owner == null) {
            return false;
        }
        ownerLastHurtBy = owner.getLastHurtByMob();
        int lastHurtTimestamp = owner.getLastHurtByMobTimestamp();
        return lastHurtTimestamp != timestamp
                && canAttack(ownerLastHurtBy, TargetingConditions.DEFAULT)
                && digimon.wantsToAttack(ownerLastHurtBy, owner);
    }

    @Override
    public void start() {
        mob.setTarget(ownerLastHurtBy);
        LivingEntity owner = digimon.getOwner();
        if (owner != null) {
            timestamp = owner.getLastHurtByMobTimestamp();
        }
        super.start();
    }
}
