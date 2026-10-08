package com.digicube.fabric.mixin;

import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.FlightFeel;
import com.digicube.fabric.client.party.RiderControls;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Retain vanilla wall clipping while framing the entire mount in third person: an aerial mount by its model,
 * a fighting mount by its body. The camera also answers a fighting mount's impacts (shudder, brief widening), and
 * on an agile flyer the flight itself (FlightFeel): it tilts into the body's banks and swings through a roll, widens
 * with the speed and shudders at the dive's top speeds.
 */
@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private int matrixPropertiesDirty;
    @Shadow @Final private static Vector3fc FORWARDS;
    @Shadow @Final private static Vector3fc UP;
    @Shadow @Final private static Vector3fc LEFT;
    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @ModifyVariable(method="getMaxZoom",at=@At("HEAD"),argsOnly=true)
    private float digicube$mountDistance(float distance) {
        var player=Minecraft.getInstance().player;
        if (player==null || !(player.getVehicle() instanceof DigimonEntity mount)) return distance;
        // A flyer frames its whole spread: its sheet may set the distance, else one by its model's scale.
        if (mount.aerialMount()!=null) return Math.max(distance,Math.max(7.5F*mount.getBody().modelScale(),mount.getBody().mount().map(m->m.cameraDistance()).orElse(0F)));
        // A sheet may set its own: close enough that a big mount still fills the screen.
        float own=mount.getBody().mount().map(m->m.cameraDistance()).orElse(0F);
        if (own>0) return Math.max(distance,own);
        return mount.riderAttacks().isEmpty() ? distance : Math.max(distance,3F+Math.max(mount.getBbWidth(),mount.getBbHeight())*1.6F);
    }

    @Inject(method="alignWithEntity",at=@At("TAIL"))
    private void digicube$impactShake(float partialTick,CallbackInfo ci) {
        float[] kick=RiderControls.cameraKick(partialTick);
        if (kick!=null && (kick[0]!=0 || kick[1]!=0)) setRotation(yRot+kick[0],xRot+kick[1]);
        float[] shake=FlightFeel.shake(partialTick);
        if (shake!=null) setRotation(yRot+shake[0],xRot+shake[1]);
        float roll=FlightFeel.cameraRoll(partialTick);
        if (Math.abs(roll)>1.0E-3F) {
            // A roll about the view's own axis: what the camera frames stays where it is, the horizon tilts.
            rotation.rotateZ(roll*((float)Math.PI/180F));
            FORWARDS.rotate(rotation,forwards);
            UP.rotate(rotation,up);
            LEFT.rotate(rotation,left);
            matrixPropertiesDirty|=3;
        }
    }

    @Inject(method="calculateFov",at=@At("RETURN"),cancellable=true)
    private void digicube$impactFov(float partialTick,CallbackInfoReturnable<Float> cir) {
        float[] kick=RiderControls.cameraKick(partialTick);
        float flight=FlightFeel.fov(partialTick);
        float factor=(kick!=null ? kick[2] : 1)*(1+flight);
        if (factor!=1) cir.setReturnValue(cir.getReturnValue()*factor);
    }
}
