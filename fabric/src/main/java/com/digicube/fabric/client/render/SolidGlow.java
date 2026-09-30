package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.fabric.mixin.MixinEvolutionPipelines;
import com.digicube.fabric.mixin.MixinEvolutionRenderType;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.function.Function;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Solid glowing boxes: opaque, depth-written and culled like an entity's cutout model, but full-bright (no lightmap) and
 * unlit (no directional shading), so a face's vertex colour is its whole shade. Howling Blaster's flame bakes a shade by
 * face direction into that colour: its blocks read as blocks and still glow in the dark. Being opaque and depth-written,
 * water drawn after them does not paint over them (unlike the no-depth glows of {@link AfterWaterEffects}).
 */
public final class SolidGlow {
    private static final RenderPipeline PIPELINE = MixinEvolutionPipelines.digicube$register(build());
    private static final Function<Identifier, RenderType> TYPES = Util.memoize(texture ->
            MixinEvolutionRenderType.digicube$create("digicube_solid_glow",
                    RenderSetup.builder(PIPELINE).withTexture("Sampler0", texture).createRenderSetup()));

    private SolidGlow() {}

    /** The render type for boxes painted with {@code texture}; vertex colours carry each face's shade. */
    public static RenderType type(Identifier texture) { return TYPES.apply(texture); }

    /** The pipeline, registered with vanilla's on first touch: touched at client start, the resource reload compiles and checks it. */
    public static RenderPipeline pipeline() { return PIPELINE; }

    private static RenderPipeline build() {
        var base = RenderPipelines.ENTITY_CUTOUT_CULL;
        var builder = RenderPipeline.builder().withLocation(Constants.id("pipeline/solid_glow"))
                .withVertexShader(base.getVertexShader()).withFragmentShader(base.getFragmentShader())
                .withShaderDefine("ALPHA_CUTOUT", .1F).withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY")
                .withShaderDefine("NO_CARDINAL_LIGHTING")
                .withCull(true).withColorTargetState(base.getColorTargetState()).withDepthStencilState(base.getDepthStencilState())
                .withVertexBinding(0, base.getVertexFormatBinding(0)).withPrimitiveTopology(base.getPrimitiveTopology());
        for (var layout : base.getBindGroupLayouts()) if (!layout.getUniforms().isEmpty()) {
            var uniforms = BindGroupLayout.builder();
            for (var uniform : layout.getUniforms()) {
                if (uniform.gpuFormat() == null) uniforms.withUniform(uniform.name(), uniform.type());
                else uniforms.withUniform(uniform.name(), uniform.type(), uniform.gpuFormat());
            }
            builder.withBindGroupLayout(uniforms.build());
        }
        builder.withBindGroupLayout(BindGroupLayouts.SAMPLER0);
        return builder.build();
    }
}
