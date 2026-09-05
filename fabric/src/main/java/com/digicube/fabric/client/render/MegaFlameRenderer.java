package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.MegaFlameEntity;
import com.digicube.fabric.client.model.MegaFlameModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

/** Emissive stepped flame core, fluttering sheets and a ten-tick breakup. */
public class MegaFlameRenderer extends EntityRenderer<MegaFlameEntity, MegaFlameRenderState> {
    private static final Identifier TEXTURE = Constants.id("textures/entity/projectile/mega_flame.png");
    private static final float SCALE = 1.35F;
    private final MegaFlameModel model;

    public MegaFlameRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = new MegaFlameModel(context.bakeLayer(MegaFlameModel.LAYER));
        shadowRadius = 0.0F;
    }

    @Override
    public MegaFlameRenderState createRenderState() {
        return new MegaFlameRenderState();
    }

    @Override
    public void extractRenderState(MegaFlameEntity entity, MegaFlameRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.yRot = entity.getYRot(partialTick);
        state.xRot = entity.getXRot(partialTick);
        state.burst = entity.isBurst();
        state.charging = false;
        state.burstTicks = state.burst ? entity.getBurstTicks() + partialTick : 0.0F;
    }

    @Override
    public void submit(MegaFlameRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0, state.boundingBoxHeight * 0.5, 0);
        submitFlame(model, state, poseStack, collector);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /** Draw at the caller's origin, shared by the mouth flare and the flying flame. */
    public static void submitFlame(MegaFlameModel model, MegaFlameRenderState state,
                                   PoseStack poseStack, SubmitNodeCollector collector) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(state.yRot + 180));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.xRot));
        poseStack.scale(-SCALE, -SCALE, SCALE);
        poseStack.translate(0, EntityModel.MODEL_Y_OFFSET, 0);
        collector.submitModel(model, state, poseStack, RenderTypes.entityTranslucentEmissive(TEXTURE),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        poseStack.popPose();
    }
}
