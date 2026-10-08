package com.digicube.fabric.mixin;

import com.digicube.fabric.client.digivice.DigitamaVisuals;
import com.digicube.fabric.client.digivice.RecallVisuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player seen from outside receives a recalled Digivice in the hand drawn here, or breaks a used Digitama in it: read
 * the hand's pose, and hide what it holds until the device lands or while the egg breaks.
 */
@Mixin(ItemInHandLayer.class)
public class MixinItemInHandLayer {
    @Unique private ArmedEntityRenderState digicube$state;
    @Unique private HumanoidArm digicube$arm;

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void digicube$rememberArm(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm,
                                      PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        digicube$state = state;
        digicube$arm = arm;
    }

    @Redirect(method = "submitArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void digicube$recallHand(ItemStackRenderState item, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline) {
        boolean hidden = RecallVisuals.thirdPersonHand(digicube$state, digicube$arm, pose);
        hidden |= DigitamaVisuals.thirdPersonHand(digicube$state, digicube$arm, pose);
        if (!hidden) item.submit(pose, collector, light, overlay, outline);
    }
}
