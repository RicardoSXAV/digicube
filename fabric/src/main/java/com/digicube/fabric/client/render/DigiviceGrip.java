package com.digicube.fabric.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Quaternionf;

/** Attach the vanilla skin arm to the lower casing in the held item's own frame. */
public final class DigiviceGrip {
    private DigiviceGrip() {}

    public static void apply(PoseStack pose, HumanoidArm arm, boolean slim) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        // Match digivice.json's first-person display. The caller already carries
        // vanilla's equip, swing and view bob, shared by the hand and device.
        pose.translate(side * -1.5F / 16, 4F / 16, -2F / 16);
        pose.mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(8),
                (float) Math.toRadians(side * -15), (float) Math.toRadians(side * -6)));
        pose.translate(side * .20F, -.23F, .04F);
        pose.mulPose(Axis.XP.rotationDegrees(-25));
        pose.mulPose(Axis.ZP.rotationDegrees(side * 25));
        // The palm is near the end of the real 12-pixel arm, not its shoulder.
        pose.translate(side * (slim ? .5F : 1F) / 16, -8.5F / 16, 0);
        // AvatarRenderer restores the arm pivot and adds its +/- .1 rad roll.
        pose.mulPose(Axis.ZP.rotation(-side * .1F));
        pose.translate(side * 5F / 16, -2F / 16, 0);
    }
}
