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
        return find(mob,target,false);
    }

    /** Cooling moves can prepare a stance, but attack selection still enforces readiness. */
    public static Path find(DigimonEntity mob, LivingEntity target, boolean preparing) {
        if (mob.distanceToSqr(target) > 14 * 14 || mob.getSpecies().isEmpty()) return null;
        var moves = mob.positioningAttacks(target);
        var candidates = new ArrayList<Candidate>();
        Vec3 away = mob.position().subtract(target.position()).multiply(1, 0, 1).normalize();
        if (away.lengthSqr() < .001) away = new Vec3(0, 0, 1);
        for (var attack : moves) {
            if (!preparing && !mob.isAttackReady(attack)) continue;
            var visited = new java.util.HashSet<net.minecraft.core.BlockPos>();
            double near = attack.motion() != null ? attack.motion().minimumRange() + .35
                    : (mob.getBbWidth() + target.getBbWidth()) * .5 + .35;
            double far = attack.isRanged() ? Math.min(attack.range() * .65, near + 2.5)
                    : attack.motion() != null ? Math.max(near, attack.range() - .45) : near + .5;
            for (double radius : approachRadii(attack,near,far)) {
                for (int step = 0; step < 8; step++) {
                    Vec3 offset = away.yRot((float) (step * Math.PI / 4)).scale(radius);
                    Vec3 horizontal = target.position().add(offset);
                    // Probe both elevations. A target-centred two-block scan
                    // misses the lower firing ledge, and a hard Y cap rejects
                    // perfectly reachable stairs to a higher platform.
                    for (double elevation : new double[]{target.getY(),mob.getY()}) {
                        Vec3 top = new Vec3(horizontal.x,elevation+2.1,horizontal.z);
                        Vec3 bottom = new Vec3(horizontal.x,Math.min(target.getY(),mob.getY())-2,horizontal.z);
                        HitResult ground = mob.level().clip(new ClipContext(top, bottom, ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE, mob));
                        if (ground.getType() == HitResult.Type.MISS) continue;
                        Vec3 feet = ground.getLocation();
                        if (feet.distanceToSqr(mob.position()) < .16 || !visited.add(net.minecraft.core.BlockPos.containing(feet))) continue;
                        var body = mob.getBoundingBox().move(feet.subtract(mob.position())).deflate(.01);
                        if (!mob.level().noCollision(mob, body) || !mob.canAttackFrom(attack, target, feet)) continue;
                        // Prefer converting an existing mark; otherwise the least expensive useful move wins.
                        double combo = target.hasEffect(DCEffects.ICE_MARK) && attack.fuel() != null ? -2 : 0;
                        double cost = feet.distanceToSqr(mob.position()) + Math.abs(feet.y - mob.getY()) + combo;
                        candidates.add(new Candidate(feet, attack, cost));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::cost));
        // Pathfinding is more expensive than the geometry rehearsal; bound it per repath.
        for (int i = 0; i < Math.min(6, candidates.size()); i++) {
            var candidate = candidates.get(i);
            Vec3 feet = candidate.feet;
            // Path node coordinates are the footprint corner, not a wide mob's centre.
            // Match Path.getEntityPosAtNode instead of requesting a point 1 block off for Golemon.
            double offset=(int)(mob.getBbWidth()+1)*.5;
            Path path = mob.getNavigation().createPath(feet.x-offset+.5, feet.y, feet.z-offset+.5, 0);
            if (path == null || !path.canReach() || path.getNodeCount() == 0) continue;
            Vec3 end = path.getEntityPosAtNode(mob, path.getNodeCount() - 1);
            // Wide mobs land off the requested block centre. Validate the real endpoint too.
            if (mob.level().noCollision(mob,mob.getBoundingBox().move(end.subtract(mob.position())).deflate(.01))
                    && mob.canAttackFrom(candidate.attack, target, end)) return path;
        }
        return null;
    }
    /** Slow, stationary strikers need usable stances, not a ring inside their own body or beyond the fist. */
    static double[] approachRadii(DigimonAttack attack,double near,double far) {
        if (attack.fuel()!=null) return new double[]{near,far,Math.max(far,attack.range()*.8)};
        if(attack.kind()==DigimonAttack.Kind.FIST) {
            var frame=attack.motion().sample(attack.hitTick());
            double reach=frame.hornBase().lerp(frame.hornTip(),.5).horizontalDistance();
            return new double[]{Math.max(near,reach-.25),reach,reach+.25};
        }
        if(attack.kind()==DigimonAttack.Kind.GROUND_WAVE) {
            return new double[]{com.digicube.entity.TectonicWave.base(0).horizontalDistance(),
                    com.digicube.entity.TectonicWave.base(2).horizontalDistance(),
                    com.digicube.entity.TectonicWave.base(4).horizontalDistance()};
        }
        return new double[]{near,far};
    }
}
