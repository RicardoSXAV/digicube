package com.digicube.fabric.mixin;

import com.digicube.fabric.client.mode.WorldListMode;
import com.digicube.mode.DigiCubeMode;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The world list shows each world's DigiCube Mode before vanilla's third line, and the narrator reads it there
 * ({@link WorldListMode}). No event reaches a list entry's text, so the entry's line is changed where it is built.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class MixinWorldListEntry {
    @Shadow @Final private LevelSummary summary;
    @Unique private DigiCubeMode digicube$mode;

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelSummary;getInfo()Lnet/minecraft/network/chat/Component;"))
    private Component digicube$showMode(Component info) {
        digicube$mode = WorldListMode.of(summary);
        return digicube$mode.chosen() ? WorldListMode.line(digicube$mode, info) : info;
    }

    @ModifyExpressionValue(method = "getNarration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelSummary;getInfo()Lnet/minecraft/network/chat/Component;"))
    private Component digicube$narrateMode(Component info) {
        return digicube$mode != null && digicube$mode.chosen() ? WorldListMode.narration(digicube$mode, info) : info;
    }
}
