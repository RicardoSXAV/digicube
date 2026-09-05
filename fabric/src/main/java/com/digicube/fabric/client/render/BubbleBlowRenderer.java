package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.BubbleBlowEntity;
import com.digicube.fabric.client.model.BubbleBlowModel;
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
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/** Translucent pink bubble disks, facing the camera while their stream follows flight. */
public class BubbleBlowRenderer extends EntityRenderer<BubbleBlowEntity, BubbleBlowRenderState> {
    private static final Identifier TEXTURE = Constants.id("textures/entity/projectile/bubble_blow.png");
    private static final float SCALE = 0.4F;
    private final BubbleBlowModel model;

    /**
     * Bakes the harness-authored bubble geometry and animations.
     * @param context client renderer context
     */
    public BubbleBlowRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = new BubbleBlowModel(context.bakeLayer(BubbleBlowModel.LAYER));
        shadowRadius = 0.0F;
    }

    @Override
    public BubbleBlowRenderState createRenderState() {
        return new BubbleBlowRenderState();
    }

    @Override
    public void extractRenderState(BubbleBlowEntity entity, BubbleBlowRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.yRot = entity.getYRot(partialTick);
        state.xRot = entity.getXRot(partialTick);
        state.popped = entity.isPopped();
        state.popTicks = state.popped ? entity.getPopTicks() + partialTick : 0.0F;
    }

    @Override
    public void submit(BubbleBlowRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        Vector3f direction = new Vector3f((float) (camera.pos.x - state.x),
                (float) (camera.pos.y - state.y - state.boundingBoxHeight * 0.5),
                (float) (camera.pos.z - state.z));
        direction.rotateY(-(state.yRot + 180.0F) * Mth.DEG_TO_RAD);
        direction.rotateX(-state.xRot * Mth.DEG_TO_RAD);
        direction.set(-direction.x, -direction.y, direction.z);
        if (direction.lengthSquared() > 1.0E-6F) {
            direction.normalize();
            state.billboardYaw = (float) Math.atan2(direction.x, direction.z);
            state.billboardPitch = (float) -Math.asin(Mth.clamp(direction.y, -1.0F, 1.0F));
        } else {
            state.billboardYaw = state.billboardPitch = 0.0F;
        }
        poseStack.pushPose();
        poseStack.translate(0.0F, state.boundingBoxHeight * 0.5F, 0.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.yRot + 180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.xRot));
        poseStack.scale(-SCALE, -SCALE, SCALE);
        poseStack.translate(0.0F, EntityModel.MODEL_Y_OFFSET, 0.0F);
        collector.submitModel(model, state, poseStack, RenderTypes.entityTranslucentEmissive(TEXTURE),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
