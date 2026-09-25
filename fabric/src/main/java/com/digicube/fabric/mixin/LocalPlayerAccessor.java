package com.digicube.fabric.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How long the eyes have been under water, which is how far vanilla lets the player see there (full at 600 ticks). */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("waterVisionTime")
    int digicube$waterVisionTime();

    @Accessor("waterVisionTime")
    void digicube$setWaterVisionTime(int ticks);
}
