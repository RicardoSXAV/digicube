package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEffects;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * What a Digimon does while it cannot see: ink and blindness take its target away beyond three
 * blocks, and what it keeps is where the enemy was. A range holder backs away from that spot
 * and keeps facing it; a brawler goes to it. Either way it is not standing still waiting to be
 * hit, and the first hit that lands gives it its target back through the hurt-by goal.
 */
public final class BlindGuardGoal extends Goal {
    private static final double GUARD_DISTANCE = 6;
    private final DigimonEntity mob;
    private final double speed;
    private int repathIn;

    public BlindGuardGoal(DigimonEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean blind() {
        return mob.hasEffect(MobEffects.BLINDNESS) || mob.hasEffect(DCEffects.INKED);
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() == null && !mob.isAttacking() && !mob.isVehicle() && blind() && mob.lastSeenThreat() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() { repathIn = 0; }

    @Override
    public void stop() { mob.getNavigation().stop(); }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        Vec3 seen = mob.lastSeenThreat();
        if (seen == null) return;
        mob.getLookControl().setLookAt(seen.x, seen.y + mob.getEyeHeight(), seen.z, 30, 30);
        if (--repathIn > 0) return;
        repathIn = 10;
        var tactics = mob.tactics();
        if (tactics.holdsRange()) {
            double keep = Math.max(GUARD_DISTANCE, tactics.holdMin() + 1);
            Vec3 away = mob.position().subtract(seen).multiply(1, 0, 1);
            if (away.lengthSqr() < .01) away = Vec3.directionFromRotation(0, mob.getYRot() + 180);
            if (away.length() >= keep) { mob.getNavigation().stop(); return; }
            away = away.normalize();
            for (float turn : new float[]{0, 45, -45, 90, -90}) {
                Vec3 to = mob.position().add(away.yRot((float) Math.toRadians(turn)).scale(keep - away.length() + 1));
                var path = mob.getNavigation().createPath(to.x, to.y, to.z, 0);
                if (path != null && path.canReach() && mob.getNavigation().moveTo(path, speed)) return;
            }
            mob.getNavigation().stop();
        } else {
            mob.getNavigation().moveTo(seen.x, seen.y, seen.z, speed);
        }
    }
}
