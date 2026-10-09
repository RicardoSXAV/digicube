package com.digicube.fabric.client.evolution;
import com.digicube.Constants;
import com.digicube.fabric.client.render.ShaderPacks;
import com.digicube.fabric.mixin.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.*;
/**
 * Opaque emissive data body with antialiased model-space squares; no bloom dependency. The shapes are drawn by the
 * mod's own fragment shaders, which a shader pack has no program for: with a pack in use ({@link ShaderPacks}) each type
 * draws as its twin instead ({@link #submit}), vanilla's emissive entity shader over a texture of its shapes
 * ({@code textures/effect/shader_pack/}), which the pack draws as a glowing entity layer.
 */
public final class EvolutionRenderType {
    /** A twin's texture: tiles side by side, each {@link #TILE} texels wide with {@link #PAD} empty ones at either side. */
    private static final int TILE=128,PAD=2;
    private static final java.util.Map<RenderType,Twin> TWINS=new java.util.IdentityHashMap<>();
    public static final RenderType GRID=create("evolution_grid",false,0);
    public static final RenderType PARTICLES=create("evolution_particles",true,2);
    public static final RenderType DATA_STREAM=create("evolution_stream",true,1);
    public static final RenderType DIGIVICE_BEACON=create("digivice_beacon",true,3);
    public static final RenderType RECALL_CHIP=create("recall_chip",false,0);
    /**
     * The type a shader pack draws: {@code tiles} glowing shapes side by side in its texture, a shape's UVs the unit
     * square at u = 0, 2, 4... as its shader reads them, their strength premultiplied into the colour (the pack drops
     * alpha under a tenth); 0 for a texture read as is (the grid repeating in model units, a plain colour).
     */
    private record Twin(RenderType type,int tiles) {}
    private static RenderType create(String name,boolean particles,int tiles) {
        var base=RenderPipelines.ENTITY_CUTOUT;
        var builder=RenderPipeline.builder().withLocation(Constants.id("pipeline/"+name))
            .withVertexShader(base.getVertexShader()).withFragmentShader(Constants.id("core/"+name));
        var twin=RenderPipeline.builder().withLocation(Constants.id("pipeline/"+name+"_shader_pack"))
            .withVertexShader(base.getVertexShader()).withFragmentShader(base.getFragmentShader());
        for(var b:java.util.List.of(builder,twin)) {
            // ALPHA_CUTOUT, unread by the shape shaders, is how a shader pack picks its entity variant (ShaderPacks)
            b.withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY").withShaderDefine("NO_CARDINAL_LIGHTING").withShaderDefine("ALPHA_CUTOUT",.1F)
                .withCull(false).withColorTargetState(base.getColorTargetState()).withDepthStencilState(base.getDepthStencilState())
                .withVertexBinding(0,base.getVertexFormatBinding(0)).withPrimitiveTopology(base.getPrimitiveTopology());
            if(particles)b.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.SRC_ALPHA,BlendFactor.ONE)))
                    .withDepthStencilState(new DepthStencilState(base.getDepthStencilState().depthTest(),false));
            for(var layout:base.getBindGroupLayouts())if(!layout.getUniforms().isEmpty()) {
                var uniforms=com.mojang.blaze3d.pipeline.BindGroupLayout.builder();
                for(var uniform:layout.getUniforms()) {
                    if(uniform.gpuFormat()==null)uniforms.withUniform(uniform.name(),uniform.type());
                    else uniforms.withUniform(uniform.name(),uniform.type(),uniform.gpuFormat());
                }
                b.withBindGroupLayout(uniforms.build());
            }
        }
        twin.withBindGroupLayout(BindGroupLayouts.SAMPLER0);
        var pipeline=MixinEvolutionPipelines.digicube$register(builder.build());
        var twinPipeline=MixinEvolutionPipelines.digicube$register(twin.build());
        // The shape pipeline too: a pack never draws it (submit hands it the twin), but logs an error for any pipeline it
        // compiles with no program of its own.
        ShaderPacks.drawAsEmissive(pipeline);
        ShaderPacks.drawAsEmissive(twinPipeline);
        // Additive glows write no depth: draw them after water and ice, or those paint over them.
        var setup=RenderSetup.builder(pipeline);
        var texture=Constants.id(name.equals("recall_chip")?"textures/effect/evolution_white.png":"textures/effect/shader_pack/"+name+".png");
        var twinSetup=RenderSetup.builder(twinPipeline).withTexture("Sampler0",texture,()->tiles==0
                ?RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR):RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        if(particles) {
            setup.setOutputTarget(com.digicube.fabric.client.render.AfterWaterEffects.TARGET);
            twinSetup.setOutputTarget(com.digicube.fabric.client.render.AfterWaterEffects.TARGET);
        }
        var type=MixinEvolutionRenderType.digicube$create(Constants.id(name).toString(),setup.createRenderSetup());
        TWINS.put(type,new Twin(MixinEvolutionRenderType.digicube$create(Constants.id(name+"_shader_pack").toString(),twinSetup.createRenderSetup()),tiles));
        return type;
    }
    /**
     * Submits {@code geometry} drawn with {@code type}, one of these. With a shader pack in use it goes to the type's
     * twin, each shape's UVs moved onto its tile of the twin's texture.
     */
    public static void submit(SubmitNodeCollector collector,PoseStack pose,RenderType type,SubmitNodeCollector.CustomGeometryRenderer geometry) {
        var twin=ShaderPacks.inUse()?TWINS.get(type):null;
        if(twin==null)collector.submitCustomGeometry(pose,type,geometry);
        else if(twin.tiles()==0)collector.submitCustomGeometry(pose,twin.type(),geometry);
        else collector.submitCustomGeometry(pose,twin.type(),(matrix,vertices)->geometry.render(matrix,new Tiles(vertices,twin.tiles())));
    }
    /** The u of a shape's {@code u} (its tile's unit square starting at u = 2 x tile) in a twin's texture of {@code tiles} tiles. */
    static float tileU(float u,int tiles) {
        int tile=Math.clamp((int)Math.floor(u/2),0,tiles-1);
        return (tile*TILE+PAD+Math.clamp(u-tile*2,0,1)*(TILE-2*PAD))/(tiles*(float)TILE);
    }
    /**
     * Writes on into {@code out}, each shape's UVs moved onto its tile ({@link #tileU}) and its colour premultiplied by
     * its alpha, whole: the glows add their light, so a faint one is a dark one, never one the pack's alpha test drops.
     */
    private record Tiles(VertexConsumer out,int tiles) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x,float y,float z){out.addVertex(x,y,z);return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){out.setColor(r*a/255,g*a/255,b*a/255,255);return this;}
        @Override public VertexConsumer setColor(int argb){return setColor(argb>>16&255,argb>>8&255,argb&255,argb>>>24);}
        @Override public VertexConsumer setUv(float u,float v){out.setUv(tileU(u,tiles),v);return this;}
        @Override public VertexConsumer setUv1(int u,int v){out.setUv1(u,v);return this;}
        @Override public VertexConsumer setUv2(int u,int v){out.setUv2(u,v);return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){out.setNormal(x,y,z);return this;}
        @Override public VertexConsumer setLineWidth(float width){out.setLineWidth(width);return this;}
    }
    private EvolutionRenderType() {}
}
