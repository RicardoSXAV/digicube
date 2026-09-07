package com.digicube.fabric.mixin;

import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.render.DigimonRenderer;
import com.digicube.fabric.client.render.RiderVisuals;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric has no entity-state extraction callback; carry the animated rider offset here. */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void digicube$extractRider(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        var extra = (FabricRenderState) state;
        extra.setData(RiderVisuals.POSE, null);
        if (entity.getVehicle() instanceof DigimonEntity mount
                && Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(mount) instanceof DigimonRenderer renderer) {
            var visual = renderer.riderVisual(mount, partialTick);
            if (visual != null) {
                var offset = visual.offset().yRot(-Mth.rotLerp(partialTick, mount.yRotO, mount.getYRot()) * Mth.DEG_TO_RAD);
                state.passengerOffset = state.passengerOffset == null ? offset : state.passengerOffset.add(offset);
                extra.setData(RiderVisuals.POSE, visual.pose());
            }
        }
    }
}
