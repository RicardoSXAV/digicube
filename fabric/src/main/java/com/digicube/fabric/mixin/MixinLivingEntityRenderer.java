package com.digicube.fabric.mixin;

import com.digicube.fabric.client.render.InkedVisuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An inked body is drawn stained ({@link InkedVisuals}): the tint of its model, whatever the renderer made of it
 * (a wolf's or a tropical fish's own), is darkened toward the ink.
 */
@Mixin(LivingEntityRenderer.class)
public class MixinLivingEntityRenderer {
    /**
     * A rider turns with the seat when the mount's animation turns it ({@code RiderVisuals.YAW}, from the seat part's
     * heading): the body swings round with a spinning strike, the head keeps looking where the rider looks, within
     * vanilla's reach of the neck. Vanilla sets the rider's body from the mount's heading alone.
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void digicube$turnWithSeat(net.minecraft.world.entity.LivingEntity entity, net.minecraft.client.renderer.entity.state.LivingEntityRenderState state,
                                       float partialTick, CallbackInfo ci) {
        Float yaw = ((FabricRenderState) state).getData(com.digicube.fabric.client.render.RiderVisuals.YAW);
        if (yaw == null) return;
        state.bodyRot += yaw;
        state.yRot = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(state.yRot - yaw), -85, 85);
    }

    @ModifyArg(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
            index = 6)
    private int digicube$inkStain(Model<?> model, Object state, PoseStack pose, RenderType type, int light, int overlay, int tint,
                                  TextureAtlasSprite sprite, int outline, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        Float ink = state instanceof FabricRenderState extra ? extra.getData(InkedVisuals.INK) : null;
        return ink == null || ink <= 0 ? tint : InkedVisuals.tint(tint, ink);
    }
}
