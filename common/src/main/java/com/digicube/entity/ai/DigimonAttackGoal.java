package com.digicube.entity.ai;

import com.digicube.digimon.DigimonAttack;
import com.digicube.entity.DigimonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Fights the current target with the attacks of the species.
 *
 * <p>Each tick: face the target; if an attack is in progress, hold position; otherwise use
 * the best ready attack for its move set (including mark/fuel combos), and failing that
 * close the distance. The attack itself (timing, damage, projectile, animation event) is
 * run by {@link DigimonEntity} so it keeps going even if this goal stops mid-swing.
 */
public final class DigimonAttackGoal extends Goal {

    private final DigimonEntity mob;
    private final double speedModifier;
    private int ticksUntilPathRecalc;
    private net.minecraft.world.phys.Vec3 positionedTarget;
    private int positionedAtTick;
    private java.util.List<DigimonAttack> positionedMoves = java.util.List.of();

    public DigimonAttackGoal(DigimonEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return !mob.isVehicle() && target != null && target.isAlive() && mob.hasAttacks() && mob.canAttack(target);
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isAttacking() || canUse();
    }

    @Override
    public void start() {
        mob.setAggressive(true);
        ticksUntilPathRecalc = 0;
        positionedTarget = null;
        positionedMoves = java.util.List.of();
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (mob.isAttacking()) {
            mob.getNavigation().stop();
            return;
        }

        DigimonAttack attack = mob.chooseAttack(target);
        if (attack != null) {
            mob.getNavigation().stop();
            mob.startAttack(attack, target);
            return;
        }

        var desiredMoves = mob.positioningAttacks(target);
        if (!desiredMoves.equals(positionedMoves)) ticksUntilPathRecalc = 0;
        if (--ticksUntilPathRecalc <= 0) {
            ticksUntilPathRecalc = adjustedTickDelay(6 + mob.getRandom().nextInt(6));
            if (positionedTarget != null && !mob.getNavigation().isDone()
                    && mob.tickCount - positionedAtTick < 20
                    && desiredMoves.equals(positionedMoves) && positionedTarget.distanceToSqr(target.position()) < 1) return;
            positionedMoves = desiredMoves;
            var combatPath = DigimonCombatPosition.find(mob, target);
            if (combatPath != null && mob.getNavigation().moveTo(combatPath, speedModifier)) {
                positionedTarget = target.position();
                // Navigation can keep a blocked path alive while an enemy pins us.
                // Re-evaluate at least once a second, even if the target stays put.
                positionedAtTick = mob.tickCount;
                return;
            }
            positionedTarget = null;
            double spacing = mob.minimumAttackSpacing();
            if (spacing > 0 && mob.position().subtract(target.position()).horizontalDistanceSqr() < spacing * spacing
                    && Math.abs(mob.getY() - target.getY()) < .6) {
                var away = mob.position().subtract(target.position()).multiply(1, 0, 1).normalize();
                if (away.lengthSqr() < .01) away = net.minecraft.world.phys.Vec3.directionFromRotation(0, mob.getYRot() + 180);
                var retreat = mob.position().add(away.scale(spacing + 0.5));
                mob.getNavigation().moveTo(retreat.x, retreat.y, retreat.z, speedModifier);
            } else {
                mob.getNavigation().moveTo(target, speedModifier);
            }
        }
    }
}
