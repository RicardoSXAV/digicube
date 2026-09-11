package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

/** Renders the original Blender flame rig at the actual animated mouth. */
public final class BlueBlasterRenderer {
    private static final Identifier TEXTURE = Constants.id("textures/entity/projectile/blue_blaster.png");
    private static final Identifier HOWLING_TEXTURE = Constants.id("textures/entity/projectile/howling_blaster.png");
    private static final Identifier ICE_TEXTURE = Constants.id("textures/entity/projectile/ice_blast_fx.png");

    private BlueBlasterRenderer() {}

    public static void submit(EntityModel<BlueBlasterRenderState> model, BlueBlasterRenderState state,
                              PoseStack pose, SubmitNodeCollector collector) {
        if (state.length <= 0.05F) return;
        pose.pushPose();
        orient(pose, state.yaw, state.pitch);
        collector.submitModel(model, state, pose, RenderTypes.entityTranslucentEmissive(state.iceBlast ? ICE_TEXTURE : state.frost ? HOWLING_TEXTURE : TEXTURE),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        pose.popPose();
    }

    /**
     * Orient the authored -Z jet using living-entity yaw (positive yaw turns west).
     * Projectile yaw has the opposite sign; using it here mirrored east/west aim.
     * Length is clipped per flame tongue, never stretched into a solid beam.
     * @param pose mouth-centred transform
     * @param yaw living-entity body yaw in degrees
     * @param pitch upward jet pitch in degrees
     */
    public static void orient(PoseStack pose, float yaw, float pitch) {
        pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.scale(-1, -1, 1);
        pose.translate(0, EntityModel.MODEL_Y_OFFSET, 0);
    }
}
