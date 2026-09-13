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

    /** Every box an attack may strike: the collision box first, then any parts a Digimon carries. */
    public static List<AABB> of(LivingEntity target) {
        var boxes = new ArrayList<AABB>();
        boxes.add(target.getBoundingBox());
        if (target instanceof DigimonEntity digimon) for (DigimonPart part : digimon.parts()) boxes.add(part.getBoundingBox());
        return boxes;
    }
}
