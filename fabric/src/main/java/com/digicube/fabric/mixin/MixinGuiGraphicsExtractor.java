package com.digicube.fabric.mixin;

import com.digicube.fabric.client.digivice.RecallVisuals;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A recalled Digivice in flight is not yet in any slot: hotbar, inventory screens and their decorations skip it. */
@Mixin(GuiGraphicsExtractor.class)
public class MixinGuiGraphicsExtractor {
    @Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V",
            at = @At("HEAD"), cancellable = true)
    private void digicube$hideRecalledItem(LivingEntity owner, Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        if (RecallVisuals.hides(stack)) ci.cancel();
    }

    @Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
            at = @At("HEAD"), cancellable = true)
    private void digicube$hideRecalledDecorations(Font font, ItemStack stack, int x, int y, String count, CallbackInfo ci) {
        if (RecallVisuals.hides(stack)) ci.cancel();
    }
}
