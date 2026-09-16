package com.digicube.fabric.client.evolution;
import com.digicube.Constants;
import com.digicube.fabric.mixin.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
/** Opaque emissive data body with antialiased model-space squares; no bloom dependency. */
public final class EvolutionRenderType {
    public static final RenderType GRID=create("evolution_grid",false);
    public static final RenderType PARTICLES=create("evolution_particles",true);
    public static final RenderType DATA_STREAM=create("evolution_stream",true);
    private static RenderType create(String name,boolean particles) {
        var base=RenderPipelines.ENTITY_CUTOUT;
        var builder=RenderPipeline.builder().withLocation(Constants.id("pipeline/"+name))
            .withVertexShader(base.getVertexShader()).withFragmentShader(Constants.id("core/"+name))
            .withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY").withShaderDefine("NO_CARDINAL_LIGHTING")
            .withCull(false).withColorTargetState(base.getColorTargetState()).withDepthStencilState(base.getDepthStencilState())
            .withVertexBinding(0,base.getVertexFormatBinding(0)).withPrimitiveTopology(base.getPrimitiveTopology());
        if(particles)builder.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.SRC_ALPHA,BlendFactor.ONE)))
                .withDepthStencilState(new DepthStencilState(base.getDepthStencilState().depthTest(),false));
        for(var layout:base.getBindGroupLayouts())if(!layout.getUniforms().isEmpty()) {
            var uniforms=com.mojang.blaze3d.pipeline.BindGroupLayout.builder();
            for(var uniform:layout.getUniforms()) {
                if(uniform.gpuFormat()==null)uniforms.withUniform(uniform.name(),uniform.type());
                else uniforms.withUniform(uniform.name(),uniform.type(),uniform.gpuFormat());
            }
            builder.withBindGroupLayout(uniforms.build());
        }
        var pipeline=MixinEvolutionPipelines.digicube$register(builder.build());
        return MixinEvolutionRenderType.digicube$create(Constants.id(name).toString(),RenderSetup.builder(pipeline).createRenderSetup());
    }
    private EvolutionRenderType() {}
}
