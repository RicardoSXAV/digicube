package com.digicube.mixin;

import com.digicube.Constants;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Proof that the mixin pipeline works. Delete once you have a real mixin.
 *
 * <p>A mixin edits a vanilla class at load time. Reach for one only when there is
 * no event or API that does the job -- they break on every Minecraft update and
 * conflict with other mods.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(at = @At("TAIL"), method = "<init>")
    private void digicube$onClientInit(CallbackInfo info) {
        Constants.LOG.info("DigiCube mixins are applying.");
    }
}
