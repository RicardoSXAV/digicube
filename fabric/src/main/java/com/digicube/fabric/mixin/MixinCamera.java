package com.digicube.fabric.mixin;

import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.party.RiderControls;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Retain vanilla wall clipping while framing the entire mount in third person: an aerial mount by its model,
 * a fighting mount by its body. The camera also answers a fighting mount's impacts (shudder, brief widening).
 */
@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @ModifyVariable(method="getMaxZoom",at=@At("HEAD"),argsOnly=true)
    private float digicube$mountDistance(float distance) {
        var player=Minecraft.getInstance().player;
        if (player==null || !(player.getVehicle() instanceof DigimonEntity mount)) return distance;
        if (mount.aerialMount()!=null) return Math.max(distance,7.5F*mount.getBody().modelScale());
        return mount.riderAttacks().isEmpty() ? distance : Math.max(distance,3F+Math.max(mount.getBbWidth(),mount.getBbHeight())*1.6F);
    }

    @Inject(method="alignWithEntity",at=@At("TAIL"))
    private void digicube$impactShake(float partialTick,CallbackInfo ci) {
        float[] kick=RiderControls.cameraKick(partialTick);
        if (kick!=null && (kick[0]!=0 || kick[1]!=0)) setRotation(yRot+kick[0],xRot+kick[1]);
    }

    @Inject(method="calculateFov",at=@At("RETURN"),cancellable=true)
    private void digicube$impactFov(float partialTick,CallbackInfoReturnable<Float> cir) {
        float[] kick=RiderControls.cameraKick(partialTick);
        if (kick!=null && kick[2]!=1) cir.setReturnValue(cir.getReturnValue()*kick[2]);
    }
}
