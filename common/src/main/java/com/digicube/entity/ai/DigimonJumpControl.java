package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.ai.control.JumpControl;

/** Discard queued navigation jumps while an aquatic creature performs an attack. */
public final class DigimonJumpControl extends JumpControl {
    private final DigimonEntity owner;

    public DigimonJumpControl(DigimonEntity owner) {
        super(owner);
        this.owner = owner;
    }

    @Override
    public void tick() {
        if (owner.combatControlsLocked()) jump = false;
        super.tick();
    }
}
