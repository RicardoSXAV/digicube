package com.digicube.fabric.mixin;

import com.digicube.fabric.client.digivice.RecallVisuals;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The selected item's name waits for a recalled Digivice to land, then shows as for any newly held item. */
@Mixin(Hud.class)
public class MixinHud implements RecallVisuals.HudAccess {
    @Shadow private ItemStack lastToolHighlight;

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void digicube$holdRecalledName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (RecallVisuals.hides(lastToolHighlight)) ci.cancel();
    }

    @Override public void digicube$replayHighlight() { lastToolHighlight = ItemStack.EMPTY; }
}
