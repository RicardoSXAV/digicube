package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.ai.control.JumpControl;

/**
 * Discard queued navigation jumps while an aquatic creature performs an attack, and on land for a serpent that climbs
 * ({@code serpent.climb_share}): it goes up a ledge on its body, never with a hop.
 */
public final class DigimonJumpControl extends JumpControl {
    private final DigimonEntity owner;

    public DigimonJumpControl(DigimonEntity owner) {
        super(owner);
        this.owner = owner;
    }

    @Override
    public void tick() {
        if (owner.combatControlsLocked() || owner.climbHeight() > 0 && !owner.isInWater()) jump = false;
        super.tick();
    }
}
