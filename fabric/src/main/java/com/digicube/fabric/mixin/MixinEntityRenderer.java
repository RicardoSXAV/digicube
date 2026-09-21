package com.digicube.fabric.mixin;

import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.render.DigimonRenderer;
import com.digicube.fabric.client.render.RiderVisuals;
import com.digicube.fabric.client.render.CombatMarkBadges;
import net.minecraft.world.entity.LivingEntity;
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

/** Fabric has no entity-state extraction callback; carry rider and living status data here. */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void digicube$extractRider(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        var extra = (FabricRenderState) state;
        extra.setData(CombatMarkBadges.MARKS, !Minecraft.getInstance().gui.hud.isHidden()
                && entity instanceof LivingEntity living ? CombatMarkBadges.read(living, partialTick) : null);
        // The partner under the crosshair is outlined in blue, where a soft target keeps the white of vanilla.
        if (entity == com.digicube.fabric.client.party.PartyClient.aimedPartner()) state.outlineColor = com.digicube.fabric.client.party.PartyClient.AIM_OUTLINE;
        // So is the prey a press of the hold would take, in the colour of its tile.
        if (entity == com.digicube.fabric.client.party.RiderControls.grabPrey()) state.outlineColor = com.digicube.fabric.client.party.RiderControls.GRAB_OUTLINE;
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
