package com.digicube.fabric.mixin;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Include the custom pipeline in vanilla resource-reload shader validation. */
@Mixin(RenderPipelines.class)
public interface MixinEvolutionPipelines {
    @Invoker("register") static RenderPipeline digicube$register(RenderPipeline pipeline){throw new AssertionError();}
}
