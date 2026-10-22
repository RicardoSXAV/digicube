package com.digicube.entity.ai;

import com.digicube.entity.DigimonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * A stroll at the species' own walking pace ({@code locomotion.stroll_pace}; without one, vanilla's). A wild body wanders
 * where it likes, as vanilla's water-avoiding stroll does. A partner potters about near its tamer once the tamer has stood
 * still a while ({@link #TAMER_STILL}): to a spot between {@link #ROAM_NEAR} and {@link #ROAM_FAR} blocks from them, never as
 * far as it would set off to follow from, so a tamer who stops has a partner that lives on round them instead of standing
 * stock-still or wandering out of reach. Swimming, it leaves the water to the swimming goal.
 */
public final class DigimonStrollGoal extends WaterAvoidingRandomStrollGoal {
    /** Ticks a tamer has to stand still before their partner potters about. */
    static final int TAMER_STILL = 60;
    /** Blocks a tamer may drift from where they stand and still count as standing. */
    private static final double TAMER_STANDS = .05;
    /** Blocks from the tamer a partner's stroll ends at, at least and at most. */
    static final double ROAM_NEAR = 2, ROAM_FAR = 5.5;
    /** Blocks off its tamer a partner's stroll keeps as it goes (it never brushes past them). */
    private static final double PASS_BY = 1.4;
    /** Ticks between a partner's strolls on average (a wild body keeps vanilla's 120). */
    private static final int PARTNER_INTERVAL = 90;

    private final DigimonEntity body;
    /** Where the tamer stood when last seen, and the tick they last moved from it. */
    private Vec3 tamerAt;
    private int tamerMovedAt;

    public DigimonStrollGoal(DigimonEntity body) {
        super(body, 1.0);
        this.body = body;
    }

    @Override
    public boolean canUse() {
        if (body.canSwim() && body.isInWater()) return false;
        LivingEntity tamer = body.getOwner();
        if (tamer == null) return super.canUse();
        // (goals are asked every other tick: the stillness is counted in ticks, not in asks)
        Vec3 at = tamer.position();
        if (tamerAt == null || at.distanceToSqr(tamerAt) > TAMER_STANDS * TAMER_STANDS) { tamerAt = at; tamerMovedAt = body.tickCount; }
        if (body.tickCount - tamerMovedAt < TAMER_STILL) return false;
        setInterval(PARTNER_INTERVAL);
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (body.canSwim() && body.isInWater()) return false;
        LivingEntity tamer = body.getOwner();
        // a tamer setting off again takes their partner along (the follow goal) instead of the stroll
        if (tamer != null && tamerAt != null && tamer.position().distanceToSqr(tamerAt) > 1) return false;
        return super.canContinueToUse();
    }

    @Override
    protected Vec3 getPosition() {
        LivingEntity tamer = body.getOwner();
        if (tamer == null) return super.getPosition();
        double far = Math.min(ROAM_FAR, body.getLocomotion().followStartDistance() - 1);
        var random = body.getRandom();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2, radius = Mth.lerp(random.nextDouble(), ROAM_NEAR, far);
            Vec3 spot = tamer.position().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            Vec3 found = LandRandomPos.getPosTowards(body, 4, 3, spot);
            if (found == null) continue;
            double fromTamer = found.subtract(tamer.position()).horizontalDistance();
            if (fromTamer >= ROAM_NEAR * .75 && fromTamer <= far + 1 && clearOf(tamer.position(), body.position(), found)) return found;
        }
        return null;
    }

    /**
     * Whether the straight way from {@code from} to {@code to} keeps {@link #PASS_BY} blocks off the tamer at {@code tamer}
     * (or, starting nearer, never brings it nearer than it starts).
     */
    private static boolean clearOf(Vec3 tamer, Vec3 from, Vec3 to) {
        Vec3 way = to.subtract(from).multiply(1, 0, 1), off = tamer.subtract(from).multiply(1, 0, 1);
        double t = way.lengthSqr() < 1.0E-6 ? 0 : Mth.clamp(off.dot(way) / way.lengthSqr(), 0, 1);
        double keep = Math.min(PASS_BY, off.horizontalDistance() - .05);
        return off.subtract(way.scale(t)).horizontalDistanceSqr() >= keep * keep;
    }

    @Override
    public void start() {
        body.getNavigation().moveTo(wantedX, wantedY, wantedZ, body.strollModifier());
    }
}
