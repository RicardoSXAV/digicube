package com.digicube.fabric.mixin;

import com.digicube.entity.DigimonEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Retain vanilla wall clipping while framing the entire aerial mount in third person. */
@Mixin(Camera.class)
public class MixinCamera {
    @ModifyVariable(method="getMaxZoom",at=@At("HEAD"),argsOnly=true)
    private float digicube$aerialDistance(float distance) {
        var player=Minecraft.getInstance().player;
        return player!=null && player.getVehicle() instanceof DigimonEntity mount && mount.aerialMount()!=null
                ? Math.max(distance,7.5F*mount.getBody().modelScale()) : distance;
    }
}
