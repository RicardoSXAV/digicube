package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.MarchingFishesEntity;
import com.digicube.fabric.client.model.MarchingFishesModel;
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
import net.minecraft.util.Mth;

/** Native curved water sheets, painted fish and animated foam, lit by the surrounding world. */
public final class MarchingFishesRenderer extends EntityRenderer<MarchingFishesEntity, MarchingFishesRenderState> {
    private static final Identifier TEXTURE = Constants.id("textures/entity/projectile/marching_fishes.png");
    private final MarchingFishesModel model;

    public MarchingFishesRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = new MarchingFishesModel(context.bakeLayer(MarchingFishesModel.LAYER));
        shadowRadius = 0.0F;
    }

    @Override
    public MarchingFishesRenderState createRenderState() { return new MarchingFishesRenderState(); }

    @Override
    public void extractRenderState(MarchingFishesEntity entity, MarchingFishesRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.yRot = entity.getYRot(partialTick);
        state.xRot = entity.getXRot(partialTick);
        state.splash = entity.isSplash();
        state.splashTicks = state.splash ? entity.getSplashTicks() + partialTick : 0;
    }

    @Override
    public void submit(MarchingFishesRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0, state.boundingBoxHeight * 0.5, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.yRot + 180));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.xRot));
        poseStack.scale(-1, -1, 1);
        poseStack.translate(0, EntityModel.MODEL_Y_OFFSET, 0);
        float opacity = state.splash ? Mth.clamp((12.0F - state.splashTicks) / 6.0F, 0.0F, 1.0F)
                : Mth.clamp(0.35F + state.ageInTicks / 3.0F, 0.0F, 1.0F);
        int color = ((int) (opacity * 255) << 24) | 0xFFFFFF;
        collector.submitModel(model, state, poseStack, RenderTypes.entityTranslucent(TEXTURE),
                state.lightCoords, OverlayTexture.NO_OVERLAY, color, null, state.outlineColor, null);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
