package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * An idle look round, as an animal glances about: mostly within its neck's reach of where its body faces
 * ({@code body.head_turn}), so only the head moves; now and then, standing, further round, and the body then comes round
 * after it ({@link SteadyBodyControl}). Walking, it glances only a little to the sides of its way. Each look holds a while,
 * a little up or down; between looks the head comes back to the front ({@link DigimonLookControl}). Vanilla's look round
 * picked any way at all, and a standing body turned after every one. With an enemy it never glances away.
 */
public final class GlanceGoal extends Goal {
    /** Chance a tick a glance starts (vanilla's look round). */
    private static final float CHANCE = .02F;
    /** Share of standing glances that look past the neck's reach (the body follows). */
    private static final float FAR_SHARE = .15F;
    /** Degrees round a far glance looks at most. */
    private static final float FAR_MOST = 150;
    /** Degrees a tick a glance turns the head at most. */
    private static final float RATE = 7;
    private final DigimonEntity mob;
    private double x, y, z;
    private int ticks;

    public GlanceGoal(DigimonEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() == null && mob.getRandom().nextFloat() < CHANCE;
    }

    @Override
    public boolean canContinueToUse() {
        return ticks > 0 && mob.getTarget() == null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        var random = mob.getRandom();
        float reach = mob.getMaxHeadYRot();
        boolean walking = !mob.getNavigation().isDone();
        float off = !walking && random.nextFloat() < FAR_SHARE
                ? reach + random.nextFloat() * Math.max(0, FAR_MOST - reach)
                : (.25F + .75F * random.nextFloat()) * reach * (walking ? .45F : .85F);
        if (random.nextBoolean()) off = -off;
        float yaw = (mob.yBodyRot + off) * Mth.DEG_TO_RAD;
        double distance = 4 + random.nextFloat() * 4;
        x = mob.getX() - Mth.sin(yaw) * distance;
        z = mob.getZ() + Mth.cos(yaw) * distance;
        // mostly level, a little up or down (looking at the ground ahead, or at something above)
        y = mob.getEyeY() + (random.nextFloat() - .55F) * distance * .6F;
        ticks = walking ? 20 + random.nextInt(20) : 30 + random.nextInt(45);
    }

    @Override
    public void tick() {
        ticks--;
        mob.getLookControl().setLookAt(x, y, z, RATE, mob.getMaxHeadXRot());
    }
}
