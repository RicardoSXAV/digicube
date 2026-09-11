package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.ai.control.LookControl;

import java.util.Optional;

/** Keep underwater body pitch under the movement controller's control. */
public final class DigimonLookControl extends LookControl {
    /**
     * Bind the aquatic look controller to its creature.
     * @param mob the swimming Digimon
     */
    public DigimonLookControl(DigimonEntity mob) {
        super(mob);
    }

    @Override
    public void tick() {
        if (((DigimonEntity)mob).constrictionHeadingLocked()) {
            lookAtCooldown = 0;
            mob.yHeadRot = mob.yBodyRot = mob.getYRot();
            mob.setXRot(0);
            return;
        }
        super.tick();
    }

    @Override
    protected boolean resetXRotOnTick() {
        return !mob.isInWater();
    }

    @Override
    protected Optional<Float> getXRotD() {
        return mob.isInWater() ? Optional.empty() : super.getXRotD();
    }
}
