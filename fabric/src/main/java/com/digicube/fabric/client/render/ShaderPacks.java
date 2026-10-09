package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Shader packs, through Iris's public API ({@code net.irisshaders.iris.api.v0.IrisApi}) when Iris is installed. A pack
 * draws the level with programs of its own, one for each of vanilla's render pipelines; a pipeline of the mod's own has
 * none, and Iris skips drawing it ("Missing program ... in override list"), unless it is handed one
 * ({@link #drawAsEmissive}). Iris is reached by reflection, so it stays optional: without it {@link #inUse} is false and
 * nothing is handed over.
 */
public final class ShaderPacks {
    private static final Object API;
    private static final Method IN_USE, ASSIGN, ASSIGN_SHADOW;
    /** {@code IrisProgram.EMISSIVE_ENTITIES}: the pack's program for vanilla's eyes, full-bright and textured. */
    private static final Object EMISSIVE;
    /** {@code IrisShadowProgram.SHADOW_ENTITIES}: the pack's program for entities in its shadow pass. */
    private static final Object SHADOW;

    static {
        Object api = null, emissive = null, shadow = null;
        Method inUse = null, assign = null, assignShadow = null;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Class<?> program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
                Class<?> shadowProgram = Class.forName("net.irisshaders.iris.api.v0.IrisShadowProgram");
                inUse = type.getMethod("isShaderPackInUse");
                assign = type.getMethod("assignPipeline", RenderPipeline.class, program);
                assignShadow = type.getMethod("assignPipelineShadow", RenderPipeline.class, shadowProgram);
                emissive = constant(program, "EMISSIVE_ENTITIES");
                shadow = constant(shadowProgram, "SHADOW_ENTITIES");
                api = type.getMethod("getInstance").invoke(null);
            } catch (ReflectiveOperationException e) {
                Constants.LOG.warn("Iris is installed but not with the API this mod knows; its own glows stay hidden under a shader pack", e);
                api = null;
            }
        }
        API = api;
        IN_USE = inUse;
        ASSIGN = assign;
        ASSIGN_SHADOW = assignShadow;
        EMISSIVE = emissive;
        SHADOW = shadow;
    }

    private static Object constant(Class<?> type, String name) throws NoSuchFieldException {
        for (Object constant : type.getEnumConstants()) if (((Enum<?>) constant).name().equals(name)) return constant;
        throw new NoSuchFieldException(type.getSimpleName() + "." + name);
    }

    private ShaderPacks() {}

    /** Whether a shader pack draws the level now (packs can be switched in game). */
    public static boolean inUse() {
        if (API == null) return false;
        try {
            return (boolean) IN_USE.invoke(API);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Iris's isShaderPackInUse failed", e);
        }
    }

    /**
     * Has a pack draw {@code pipeline} with its program for glowing entity layers (a spider's eyes): full-bright, the
     * texture times the vertex colour, the pipeline's own blending, depth and culling kept; and in its shadow pass as an
     * entity. Iris picks the program's variant by the pipeline's vertex format or, failing that, by an {@code ALPHA_CUTOUT}
     * define: a pipeline with vanilla's entity format needs the define to get the entity variant (its alpha test drops
     * alpha under a tenth). Once per pipeline, at start.
     */
    public static void drawAsEmissive(RenderPipeline pipeline) {
        if (API == null) return;
        try {
            ASSIGN.invoke(API, pipeline, EMISSIVE);
            ASSIGN_SHADOW.invoke(API, pipeline, SHADOW);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Iris's assignPipeline failed for " + pipeline.getLocation(), e);
        }
    }
}
