package com.digicube.fabric.mixin;
import net.minecraft.client.renderer.rendertype.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** 26.2 has no public or Fabric factory for a custom RenderType. */
@Mixin(RenderType.class)
public interface MixinEvolutionRenderType {
    @Invoker("create") static RenderType digicube$create(String name,RenderSetup setup){throw new AssertionError();}
}
