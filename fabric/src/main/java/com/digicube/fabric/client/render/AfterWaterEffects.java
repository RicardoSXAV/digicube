package com.digicube.fabric.client.render;

import com.digicube.fabric.mixin.MixinEvolutionRenderType;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Glows that write no depth, drawn after water and ice.
 *
 * <p>26.2 draws translucent entity models and custom geometry before translucent terrain. A glow that writes no depth
 * leaves the depth buffer at whatever is behind it, so water or ice behind it later passes the depth test and blends
 * over it: the glow looks sunk below the surface. Render types whose output target is {@link #TARGET} are moved by
 * {@code MixinSubmitNodeCollection} into vanilla's after-terrain phase, where translucent particles already go, so they
 * blend over the water instead; like those particles, a glow under a water surface is then hidden from above it.
 * With improved transparency the water has its own layer, and the glow is drawn into that layer, depth-tested against
 * it, so the composite keeps it in front of the water. Outside the level frame (hand, GUI) the target is the main one.
 */
public final class AfterWaterEffects {
    public static final OutputTarget TARGET = new OutputTarget("digicube_after_water",
            () -> Minecraft.getInstance().levelRenderer.translucentTarget());
    private static final Function<Identifier, RenderType> GLOW = Util.memoize(texture ->
            MixinEvolutionRenderType.digicube$create("digicube_glow", RenderSetup.builder(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE)
                    .withTexture("Sampler0", texture).useOverlay().affectsCrumbling().sortOnUpload()
                    .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE).setOutputTarget(TARGET).createRenderSetup()));

    private AfterWaterEffects() {}

    /** {@code RenderTypes.entityTranslucentEmissive(texture)}, drawn after water and ice. */
    public static RenderType glow(Identifier texture) { return GLOW.apply(texture); }

    public static boolean afterWater(RenderType type) { return type.outputTarget() == TARGET; }
}
