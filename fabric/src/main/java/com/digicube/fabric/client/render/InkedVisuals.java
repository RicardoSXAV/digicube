package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.CombatMarkState;
import com.digicube.registry.DCParticles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * What Deadly Shade's ink looks like on whatever it hit, for as long as the Inked mark lasts (the mark's own clock,
 * {@link CombatMarkState#inkRemaining}, read from the synced marks, so every client sees the same thing):
 * <ul>
 *     <li>the body is stained: drawn darker and toward the ink's violet ({@code MixinLivingEntityRenderer} multiplies
 *     the model's tint by {@link #tint}), fading back as the ink wears off;</li>
 *     <li>it drips: drops run off its upper body and fall, and lie on the ground as stains ({@link InkParticle});</li>
 *     <li>an inked player sees ink splashed over the edges of the screen, running slowly down as it wears off.</li>
 * </ul>
 * The burst of ink at the hit itself is sent by the server with the hit.
 */
public final class InkedVisuals {
    private InkedVisuals() {}

    /** How inked the entity drawn is, 0 to 1; absent when it is not. */
    public static final RenderStateDataKey<Float> INK = RenderStateDataKey.create();
    private static final Identifier SPLATTER = Constants.id("textures/gui/ink_splatter.png");
    /** The ink's colour at full strength, as a tint multiplier (dark, toward violet). */
    private static final int STAIN = 0xFF4A3C5C;
    /** Blocks from the camera within which inked bodies drip. */
    private static final double DRIP_RANGE = 40;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(InkedVisuals::tick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Constants.id("inked_view"), (graphics, delta) -> screen(graphics, delta));
    }

    /** Inked share of {@code living}: the mark's remaining share, never below a third while it lasts; 0 without the mark. */
    public static float ink(LivingEntity living) {
        int marks = ((CombatMarkState) living).digicube$marks();
        if (!CombatMarkState.has(marks, CombatMarkState.INKED) || !living.isAlive()) return 0;
        return Math.max(.34F, CombatMarkState.inkRemaining(marks));
    }

    /** A model tint darkened toward the ink by {@code ink}. */
    public static int tint(int tint, float ink) {
        return ARGB.multiply(tint, ARGB.srgbLerp(Mth.clamp(ink, 0, 1) * .85F, 0xFFFFFFFF, STAIN));
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.isPaused()) return;
        var camera = minecraft.gameRenderer.mainCamera().position();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity.distanceToSqr(camera) > DRIP_RANGE * DRIP_RANGE) continue;
            float ink = ink(living);
            if (ink <= 0) continue;
            // The camera's own body in first person drips out of sight.
            if (living == minecraft.getCameraEntity() && minecraft.options.getCameraType().isFirstPerson()) continue;
            AABB box = living.getBoundingBox();
            double area = box.getXsize() * box.getYsize() + box.getZsize() * box.getYsize();
            float rate = ink * (float) (.25 + area * .12);
            var random = living.getRandom();
            for (float n = rate; n > 0; n--) {
                if (n < 1 && random.nextFloat() > n) break;
                // From a point on the body's side or top, in its upper two thirds, just outside it.
                double u = random.nextDouble(), h = box.minY + box.getYsize() * (.33 + .67 * random.nextDouble());
                double x, z;
                switch (random.nextInt(4)) {
                    case 0 -> { x = box.minX - .04; z = Mth.lerp(u, box.minZ, box.maxZ); }
                    case 1 -> { x = box.maxX + .04; z = Mth.lerp(u, box.minZ, box.maxZ); }
                    case 2 -> { x = Mth.lerp(u, box.minX, box.maxX); z = box.minZ - .04; }
                    default -> { x = Mth.lerp(u, box.minX, box.maxX); z = box.maxZ + .04; }
                }
                var motion = living.getDeltaMovement();
                minecraft.level.addParticle(DCParticles.INK_DRIP, x, h, z, motion.x * .5, -.02, motion.z * .5);
            }
        }
    }

    /** Splats over the edges of an inked player's view, their places fixed, sliding down and fading with the ink. */
    private static void screen(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.options.getCameraType().isFirstPerson()) return;
        float ink = ink(minecraft.player);
        if (ink <= 0) return;
        float remaining = CombatMarkState.inkRemaining(((CombatMarkState) minecraft.player).digicube$marks());
        int w = g.guiWidth(), h = g.guiHeight();
        int size = Math.max(48, h / 3);
        // x, y (share of the screen), which splat, size factor
        float[][] splats = {{-.04F, -.05F, 0, 1.2F}, {.83F, -.02F, 1, 1}, {-.03F, .62F, 2, .9F}, {.86F, .55F, 3, 1.1F}, {.42F, -.12F, 1, .7F}};
        float run = (1 - remaining) * .22F;
        int alpha = (int) (235 * Mth.clamp(remaining * 1.6F, 0, 1));
        if (alpha <= 4) return;
        int colour = ARGB.color(alpha, 255, 255, 255);
        for (float[] s : splats) {
            int side = (int) (size * s[3]);
            int x = (int) (s[0] * w), y = (int) ((s[1] + run * s[3]) * h);
            int u = ((int) s[2] % 2) * 64, v = ((int) s[2] / 2) * 64;
            g.blit(RenderPipelines.GUI_TEXTURED, SPLATTER, x, y, u, v, side, side, 64, 64, 128, 128, colour);
        }
    }
}
