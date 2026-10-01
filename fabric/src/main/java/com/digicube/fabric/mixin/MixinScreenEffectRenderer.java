package com.digicube.fabric.mixin;

import com.digicube.fabric.client.render.BurnedVisuals;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A Burned player sees no wall of vanilla fire over the view: {@link BurnedVisuals} draws its own low band of flames
 * along the bottom edge. Fire from anything else still shows vanilla's.
 */
@Mixin(ScreenEffectRenderer.class)
public class MixinScreenEffectRenderer {
    @Redirect(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isOnFire()Z"))
    private boolean digicube$burnedView(LocalPlayer player) {
        return player.isOnFire() && BurnedVisuals.burn(player) <= 0;
    }
}
