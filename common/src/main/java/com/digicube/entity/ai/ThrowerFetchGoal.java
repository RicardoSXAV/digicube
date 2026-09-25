package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** A thrower with no fight left still catches its bone coming home, or fetches it from the ground ({@link ThrowerBrain#tickIdle}). */
public final class ThrowerFetchGoal extends Goal {
    private final DigimonEntity mob;
    private ThrowerBrain brain;
    private boolean busy = true;

    public ThrowerFetchGoal(DigimonEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean idle() {
        return (mob.getTarget() == null || !mob.getTarget().isAlive()) && !mob.isVehicle() && !mob.isHolding() && mob.thrower().bone() != null;
    }

    @Override public boolean canUse() { return idle() && mob.thrower().active(); }
    @Override public void start() { busy = true; }
    @Override public boolean canContinueToUse() { return idle() && busy; }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if (brain == null) brain = new ThrowerBrain(mob);
        busy = brain.tickIdle();
    }
}
