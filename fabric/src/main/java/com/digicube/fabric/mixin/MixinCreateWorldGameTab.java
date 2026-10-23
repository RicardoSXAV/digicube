package com.digicube.fabric.mixin;

import com.digicube.fabric.client.mode.ModeSection;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create World's Game tab gains the DigiCube Mode section under its rows. No event reaches into a vanilla tab's
 * layout, so this is the one mixin the selection screen needs; everything else uses Fabric's screen events.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$GameTab")
public class MixinCreateWorldGameTab {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void digicube$addModeSection(CreateWorldScreen screen, CallbackInfo ci) {
        ModeSection.addTo((GridLayout) ((GridLayoutTab) (Object) this).getLayout(), screen);
    }
}
