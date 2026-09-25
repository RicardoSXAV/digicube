package com.digicube.fabric.mixin;

import com.digicube.fabric.client.render.AfterWaterEffects;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Sends {@link AfterWaterEffects} glows to the after-terrain phase, drawn once water and ice are in place. */
@Mixin(SubmitNodeCollection.class)
public abstract class MixinSubmitNodeCollection {
    @Shadow @Final public SimpleFeatureRenderPhase afterTerrain;

    @WrapOperation(method = "submitModel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/phase/TranslucentFeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V"))
    private void digicube$modelAfterWater(TranslucentFeatureRenderPhase phase, TranslucentSubmit submit, Operation<Void> original) {
        if (submit instanceof ModelFeatureRenderer.Submit<?> model && AfterWaterEffects.afterWater(model.renderType())) afterTerrain.submit(submit);
        else original.call(phase, submit);
    }

    @WrapOperation(method = "submitCustomGeometry", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"))
    private void digicube$geometryAfterWater(SimpleFeatureRenderPhase phase, SubmitNode submit, Operation<Void> original) {
        if (submit instanceof CustomFeatureRenderer.Submit custom && AfterWaterEffects.afterWater(custom.renderType())) afterTerrain.submit(submit);
        else original.call(phase, submit);
    }
}
