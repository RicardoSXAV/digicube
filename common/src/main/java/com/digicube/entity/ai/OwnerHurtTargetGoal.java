package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;

import java.util.EnumSet;

/**
 * Attack what the tamer attacks: when the owner lands a hit on something, the Digimon
 * targets it too. Same logic as the vanilla wolf goal, but for {@link DigimonEntity},
 * which is not a {@code TamableAnimal}.
 */
public final class OwnerHurtTargetGoal extends TargetGoal {

    private final DigimonEntity digimon;
    private LivingEntity ownerLastHurt;
    private int timestamp;

    public OwnerHurtTargetGoal(DigimonEntity digimon) {
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
        ownerLastHurt = owner.getLastHurtMob();
        int lastHurtTimestamp = owner.getLastHurtMobTimestamp();
        return lastHurtTimestamp != timestamp
                && canAttack(ownerLastHurt, TargetingConditions.DEFAULT)
                && digimon.wantsToAttack(ownerLastHurt, owner);
    }

    @Override
    public void start() {
        mob.setTarget(ownerLastHurt);
        LivingEntity owner = digimon.getOwner();
        if (owner != null) {
            timestamp = owner.getLastHurtMobTimestamp();
        }
        super.start();
    }
}
