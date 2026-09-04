package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.PepperBreathEntity;
import com.digicube.fabric.client.model.PepperBreathModel;
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

/**
 * Draws the Pepper Breath fireball: the flat-plane flame model, full bright, turned to
 * face the direction it flies. The model's own {@code setupAnim} handles the frame
 * flicker and the roll about the travel axis.
 */
public class PepperBreathRenderer extends EntityRenderer<PepperBreathEntity, PepperBreathRenderState> {

    private static final Identifier TEXTURE = Constants.id("textures/entity/projectile/pepper_breath.png");
    private static final float SCALE = 0.9F;

    private final PepperBreathModel model;

    public PepperBreathRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new PepperBreathModel(context.bakeLayer(PepperBreathModel.LAYER));
        this.shadowRadius = 0.0F;
    }

    @Override
    public PepperBreathRenderState createRenderState() {
        return new PepperBreathRenderState();
    }

    @Override
    public void extractRenderState(PepperBreathEntity entity, PepperBreathRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.yRot = entity.getYRot(partialTick);
        state.xRot = entity.getXRot(partialTick);
    }

    @Override
    public void submit(PepperBreathRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        // Centre of the hitbox, then point the model's front (-Z) along the flight direction.
        poseStack.translate(0.0F, state.boundingBoxHeight * 0.5F, 0.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.yRot + 180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.xRot));
        // Vanilla models are authored Y-down around ground level y=24: flip and lift like living entities.
        poseStack.scale(-SCALE, -SCALE, SCALE);
        poseStack.translate(0.0F, EntityModel.MODEL_Y_OFFSET, 0.0F);
        collector.submitModel(model, state, poseStack, RenderTypes.entityTranslucentEmissive(TEXTURE),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
