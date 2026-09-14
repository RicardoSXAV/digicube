package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.digimon.KineticAttacks;
import com.digicube.entity.KineticProjectileEntity;
import com.digicube.fabric.client.model.NativeEffectModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

import java.util.HashMap;
import java.util.Map;

public final class KineticProjectileRenderer extends EntityRenderer<KineticProjectileEntity, NativeEffectState> {
    private final Map<String, NativeEffectModel> models = new HashMap<>();
    public KineticProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
        for (var definition : KineticAttacks.all()) if (definition.projectile() != null) {
            models.put(definition.projectile(), new NativeEffectModel(context.bakeLayer(NativeEffectModel.layer(definition.projectile())), definition.projectile()));
        }
    }
    @Override public NativeEffectState createRenderState() { return new NativeEffectState(); }
    @Override public void extractRenderState(KineticProjectileEntity entity, NativeEffectState state, float partial) {
        super.extractRenderState(entity, state, partial);
        var definition = entity.definition();
        state.projectile = definition == null ? null : definition.projectile();
        if (definition == null) return;
        state.scale = definition.modelScale();
        var velocity = entity.getDeltaMovement();
        state.yaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
        state.pitch = (float) -Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalDistance()));
    }
    /** Used by the compiled parity check as well as the submitted projectile. */
    public static void transform(PoseStack stack, float yaw, float pitch, float scale) {
        stack.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        stack.mulPose(Axis.XP.rotationDegrees(-pitch));
        stack.scale(-scale, -scale, scale);
        stack.translate(0, EntityModel.MODEL_Y_OFFSET, 0);
    }
    @Override public void submit(NativeEffectState state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
        var model = models.get(state.projectile);
        if (model == null) return;
        stack.pushPose();
        transform(stack, state.yaw, state.pitch, state.scale);
        collector.submitModel(model, state, stack,
                RenderTypes.entityTranslucentEmissive(Constants.id("textures/entity/projectile/" + state.projectile + ".png")),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        stack.popPose();
        super.submit(state, stack, collector, camera);
    }
}
