package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * A partner gives its tamer room, as a pet at heel does: a tamer may walk right up to it (it never scurries off as they
 * come), but one who stands at its side, within {@link #CLOSE} blocks, for {@link #STAY} ticks has it move off to
 * {@link #ROOM} blocks from them, on its own side: a step or two back, facing them, when it already faces them, or else a
 * turn and a few steps away ({@link DigimonMoveControl#walkFacing}, the navigation). Running away sideways as a tamer
 * approached read as a crab's scuttle, and it kept the tamer from reaching it.
 */
public final class GiveRoomGoal extends Goal {
    /** Blocks between centres within which the tamer stands too close, and the blocks off them it moves to. */
    static final double CLOSE = 1.3, ROOM = 2.4;
    /** Ticks the tamer has to stand that close first. */
    static final int STAY = 15;
    /** Degrees off facing the tamer within which it steps back facing them rather than turning away. */
    private static final float FACING = 60;
    /** Blocks a tick of the tamer's pace below which they stand. */
    private static final double STANDS = .03;
    /** Ticks it gives the move at most. */
    private static final int MOST = 60;

    private final DigimonEntity mob;
    private Vec3 tamerAt;
    private int closeSince = -1, ticks;
    private boolean backing;
    private Vec3 to;

    public GiveRoomGoal(DigimonEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        LivingEntity tamer = mob.getOwner();
        if (tamer == null || mob.getTarget() != null || mob.isAttacking() || mob.isPassenger() || mob.isVehicle()
                || mob.canSwim() && mob.isInWater()) { closeSince = -1; return false; }
        // (goals are asked every other tick: the stay is counted in ticks)
        Vec3 at = tamer.position();
        boolean stands = tamerAt != null && at.distanceToSqr(tamerAt) < STANDS * STANDS * 4;
        tamerAt = at;
        boolean close = mob.position().subtract(at).horizontalDistanceSqr() < CLOSE * CLOSE;
        if (!close || !stands) { closeSince = -1; return false; }
        if (closeSince < 0) closeSince = mob.tickCount;
        return mob.tickCount - closeSince >= STAY;
    }

    @Override
    public void start() {
        ticks = 0;
        closeSince = -1;
        LivingEntity tamer = mob.getOwner();
        Vec3 away = mob.position().subtract(tamer.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(Mth.sin(tamer.getYRot() * Mth.DEG_TO_RAD), 0, -Mth.cos(tamer.getYRot() * Mth.DEG_TO_RAD));
        to = tamer.position().add(away.normalize().scale(ROOM));
        float facing = (float) (Mth.atan2(tamer.getZ() - mob.getZ(), tamer.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90;
        backing = mob.getMoveControl() instanceof DigimonMoveControl && Math.abs(Mth.wrapDegrees(facing - mob.yBodyRot)) < FACING;
        if (!backing) mob.getNavigation().moveTo(to.x, to.y, to.z, mob.modifierForPace(mob.walkPace()));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!backing || !(mob.getMoveControl() instanceof DigimonMoveControl control)) return;
        LivingEntity tamer = mob.getOwner();
        if (tamer == null) return;
        float facing = (float) (Mth.atan2(tamer.getZ() - mob.getZ(), tamer.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90;
        mob.getNavigation().stop();
        control.walkFacing(facing, -1, 0, mob.modifierForPace(mob.walkPace() * .6));
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity tamer = mob.getOwner();
        if (tamer == null || mob.getTarget() != null || ++ticks >= MOST) return false;
        double off = mob.position().subtract(tamer.position()).horizontalDistance();
        return backing ? off < ROOM - .2 : !mob.getNavigation().isDone();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
