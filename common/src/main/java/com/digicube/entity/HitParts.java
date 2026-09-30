package com.digicube.entity;

import com.digicube.digimon.DigimonBody;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Pure placement of authored hit parts; shared by both sides and by offline checks. */
public final class HitParts {
    private HitParts() {}

    /** The world box of one part for a body standing at {@code feet} with the given body yaw. */
    public static AABB place(DigimonBody.HitPart part, Vec3 feet, float bodyYaw) {
        Vec3 bottom = feet.add(part.offset().yRot(-bodyYaw * Mth.DEG_TO_RAD));
        double half = part.width() / 2.0;
        return new AABB(bottom.x - half, bottom.y, bottom.z - half, bottom.x + half, bottom.y + part.height(), bottom.z + half);
    }

    /**
     * The world box of one part of a serpent's body, which lies along the path its head took: as far behind the head
     * along the trail as the part is authored behind the feet, aside across the trail, and up as authored, or swimming
     * with its middle at the body's swimming height.
     */
    public static AABB place(DigimonBody.HitPart part, SerpentTrail trail, boolean swimming, float swimHeight) {
        double[] at = new double[3], tangent = new double[3];
        trail.sample(new double[]{Math.max(0, -part.offset().z)}, at, tangent);
        double norm = Math.sqrt(tangent[0] * tangent[0] + tangent[2] * tangent[2]);
        double leftX = norm < 1.0E-6 ? 0 : tangent[2] / norm, leftZ = norm < 1.0E-6 ? 0 : -tangent[0] / norm;
        double x = at[0] + leftX * part.offset().x, z = at[2] + leftZ * part.offset().x;
        double y = at[1] + (swimming ? swimHeight - part.height() / 2.0 : part.offset().y);
        double half = part.width() / 2.0;
        return new AABB(x - half, y, z - half, x + half, y + part.height(), z + half);
    }

    /** Every box an attack may strike: the collision box first, then any parts a Digimon carries. */
    public static List<AABB> of(LivingEntity target) {
        var boxes = new ArrayList<AABB>();
        boxes.add(target.getBoundingBox());
        if (target instanceof DigimonEntity digimon) for (DigimonPart part : digimon.parts()) boxes.add(part.getBoundingBox());
        return boxes;
    }
}
