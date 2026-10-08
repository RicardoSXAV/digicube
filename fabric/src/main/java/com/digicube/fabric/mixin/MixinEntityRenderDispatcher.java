package com.digicube.fabric.mixin;

import com.digicube.fabric.client.render.BurnedVisuals;
import com.digicube.fabric.client.render.BurningFlames;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Burned body burns in its own flames ({@link BurningFlames}), drawn where vanilla draws its sheet of fire: about the
 * body's feet, right after the body, facing the camera ({@code MixinEntityRenderer} turns vanilla's off for it). A body
 * an electric shot shocked crackles there too ({@code ShockedBodies}).
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {
    @Inject(method = "submit", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void digicube$burningFlames(EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack pose,
                                        SubmitNodeCollector collector, CallbackInfo ci) {
        BurnedVisuals.Burning burning = ((FabricRenderState) state).getData(BurnedVisuals.BURNING);
        if (burning != null && !state.isInvisible) BurningFlames.submit(state, burning, pose, collector, camera.orientation);
        var shocked = ((FabricRenderState) state).getData(com.digicube.fabric.client.render.ShockedBodies.SHOCKED);
        if (shocked != null && !state.isInvisible) com.digicube.fabric.client.render.ShockedBodies.submit(state, shocked, pose, collector);
    }
}
