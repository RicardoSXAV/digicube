package com.digicube.entity.ai;

import com.digicube.digimon.DigimonAttack;
import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;

/** Short, grounded approaches to positions where a ready move can actually reach the victim. */
public final class DigimonCombatPosition {
    private DigimonCombatPosition() {}

    private record Candidate(Vec3 feet, DigimonAttack attack, double cost) {}

    public static Path find(DigimonEntity mob, LivingEntity target) {
        if (mob.distanceToSqr(target) > 14 * 14 || mob.getSpecies().isEmpty()) return null;
        var moves = mob.positioningAttacks(target);
        var candidates = new ArrayList<Candidate>();
        Vec3 away = mob.position().subtract(target.position()).multiply(1, 0, 1).normalize();
        if (away.lengthSqr() < .001) away = new Vec3(0, 0, 1);
        for (var attack : moves) {
            if (!mob.isAttackReady(attack)) continue;
            double near = attack.motion() != null ? attack.motion().minimumRange() + .35
                    : (mob.getBbWidth() + target.getBbWidth()) * .5 + .35;
            double far = attack.isRanged() ? Math.min(attack.range() * .65, near + 2.5)
                    : attack.motion() != null ? Math.max(near, attack.range() - .45) : near + .5;
            for (double radius : new double[]{near, far}) {
                for (int step = 0; step < 8; step++) {
                    Vec3 offset = away.yRot((float) (step * Math.PI / 4)).scale(radius);
                    Vec3 horizontal = target.position().add(offset);
                    Vec3 top = horizontal.add(0, 2.1, 0), bottom = horizontal.add(0, -2, 0);
                    HitResult ground = mob.level().clip(new ClipContext(top, bottom, ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE, mob));
                    if (ground.getType() == HitResult.Type.MISS) continue;
                    Vec3 feet = ground.getLocation();
                    if (feet.distanceToSqr(mob.position()) < .16 || Math.abs(feet.y - mob.getY()) > 2) continue;
                    var body = mob.getBoundingBox().move(feet.subtract(mob.position())).deflate(.01);
                    if (!mob.level().noCollision(mob, body) || !mob.canAttackFrom(attack, target, feet)) continue;
                    // Prefer converting an existing mark; otherwise the least expensive useful move wins.
                    double combo = target.hasEffect(DCEffects.ICE_MARK) && attack.fuel() != null ? -2 : 0;
                    double cost = feet.distanceToSqr(mob.position()) + Math.abs(feet.y - mob.getY()) + combo;
                    candidates.add(new Candidate(feet, attack, cost));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::cost));
        // Pathfinding is more expensive than the geometry rehearsal; bound it per repath.
        for (int i = 0; i < Math.min(6, candidates.size()); i++) {
            var candidate = candidates.get(i);
            Vec3 feet = candidate.feet;
            Path path = mob.getNavigation().createPath(feet.x, feet.y, feet.z, 0);
            if (path == null || !path.canReach() || path.getNodeCount() == 0) continue;
            Vec3 end = path.getEntityPosAtNode(mob, path.getNodeCount() - 1);
            // Wide mobs land off the requested block centre. Validate the real endpoint too.
            if (mob.canAttackFrom(candidate.attack, target, end)) return path;
        }
        return null;
    }
}
