package com.digicube.fabric.mixin;

import com.digicube.fabric.client.render.DigiviceGrip;
import com.digicube.registry.DCItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ordinary held items omit the arm; the Digivice has a visible one-handed grip. */
@Mixin(ItemInHandRenderer.class)
public class MixinItemInHandRenderer {
    @Inject(method = "renderItem", at = @At("HEAD"))
    private void digicube$holdDevice(LivingEntity entity, ItemStack item, ItemDisplayContext context,
                                    PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (!item.is(DCItems.DIGIVICE)
                || (context != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                    && context != ItemDisplayContext.FIRST_PERSON_LEFT_HAND)) return;
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || entity != player || player.isInvisible() || player.isSpectator()) return;
        var arm = context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        var renderer = minecraft.getEntityRenderDispatcher().getPlayerRenderer(player);
        var skin = player.getSkin();
        pose.pushPose();
        DigiviceGrip.apply(pose, arm, skin.model() == PlayerModelType.SLIM);
        if (arm == HumanoidArm.RIGHT) {
            renderer.renderRightHand(pose, collector, light, skin.body().texturePath(),
                    player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
        } else {
            renderer.renderLeftHand(pose, collector, light, skin.body().texturePath(),
                    player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
        }
        pose.popPose();
    }
}
