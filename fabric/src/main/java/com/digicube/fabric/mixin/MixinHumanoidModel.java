package com.digicube.fabric.mixin;

import com.digicube.fabric.client.render.RiderVisuals;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fit vanilla player and armor legs to a wider mount, retaining upper-body item poses. */
@Mixin(HumanoidModel.class)
public class MixinHumanoidModel {
    @Shadow @Final public ModelPart leftLeg;
    @Shadow @Final public ModelPart rightLeg;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void digicube$fitRiderLegs(HumanoidRenderState state, CallbackInfo ci) {
        var pose = ((FabricRenderState) state).getData(RiderVisuals.POSE);
        if (state.isPassenger && pose != null) {
            leftLeg.xRot = rightLeg.xRot = pose.pitch();
            leftLeg.yRot = -pose.splay();
            rightLeg.yRot = pose.splay();
            leftLeg.zRot = -pose.roll();
            rightLeg.zRot = pose.roll();
        }
    }
}
