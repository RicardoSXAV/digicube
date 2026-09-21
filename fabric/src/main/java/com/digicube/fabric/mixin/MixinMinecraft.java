package com.digicube.fabric.mixin;

import com.digicube.fabric.client.party.RiderControls;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mounted combat: the use button casts the mount's special while the rider's hand is free, and the soft target is outlined. */
@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
    private void digicube$riderUse(CallbackInfo ci) {
        if (RiderControls.takesMouse()) ci.cancel();
    }

    @Inject(method="shouldEntityAppearGlowing",at=@At("HEAD"),cancellable=true)
    private void digicube$softTarget(Entity entity,CallbackInfoReturnable<Boolean> cir) {
        if (entity == RiderControls.softTarget() || entity == RiderControls.grabPrey() || entity == com.digicube.fabric.client.party.PartyClient.aimedPartner()) cir.setReturnValue(true);
    }
}
