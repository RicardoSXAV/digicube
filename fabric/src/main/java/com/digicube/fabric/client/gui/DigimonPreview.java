package com.digicube.fabric.client.gui;

import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One Digimon drawn inside a screen. The entity is client-side only, never enters the
 * level and is never ticked; the screen bumps its tick count so the idle animation plays.
 * Facing is applied to the render state, not the entity, so nothing about the entity
 * changes between frames. The transform follows vanilla's inventory preview, the
 * reference for the angle math.
 */
public final class DigimonPreview {
    /** Previews never enter a level, so nothing assigns them an entity id; renderers still read one. */
    private static int nextPreviewId = -1000;

    private final Minecraft minecraft;
    private final DigimonEntity entity;

    private DigimonPreview(Minecraft minecraft, DigimonEntity entity) {
        this.minecraft = minecraft;
        this.entity = entity;
    }

    /** @return the preview, or null when there is no level to create an entity in */
    public static DigimonPreview create(Minecraft minecraft, Identifier species) {
        if (minecraft.level == null) return null;
        DigimonEntity entity = DCEntityTypes.DIGIMON.create(minecraft.level, EntitySpawnReason.LOAD);
        if (entity == null) return null;
        entity.setId(nextPreviewId--);
        entity.setSpecies(species);
        if (minecraft.player != null) entity.setPos(minecraft.player.position());
        entity.setNoGravity(true);
        entity.setSilent(true);
        entity.markGuiPreview();
        return new DigimonPreview(minecraft, entity);
    }

    /** Advances the idle animation clock. Call once per screen tick. */
    public void tick() {
        entity.tickCount++;
    }

    /** GUI units per block that fill {@link DigiTheme#PREVIEW_FILL} of the viewport with the larger body side. */
    public int scale(int viewportHeight) {
        float largest = Math.max(entity.getBbHeight(), entity.getBbWidth());
        return Math.max(1, (int) Math.floor(viewportHeight * DigiTheme.PREVIEW_FILL / Math.max(0.1F, largest)));
    }

    /**
     * Draws the Digimon clipped to the viewport.
     * @param footY     screen row the feet stand on
     * @param bodyYaw   turntable angle in degrees, 0 facing the player
     * @param headYaw   head turn relative to the body, degrees
     * @param headPitch head pitch, degrees, positive looking down
     */
    public void draw(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int footY,
                     float bodyYaw, float headYaw, float headPitch, float partialTick) {
        EntityRenderer<? super DigimonEntity, ?> renderer = minecraft.getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, partialTick);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        state.shadowRadius = 0;
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = 180.0F + bodyYaw;
            living.yRot = headYaw;
            living.xRot = headPitch;
            living.boundingBoxWidth /= living.scale;
            living.boundingBoxHeight /= living.scale;
            living.scale = 1.0F;
        }
        int scale = scale(y2 - y1);
        float centerY = (y1 + y2) / 2.0F;
        Vector3f translation = new Vector3f(0.0F, (footY - centerY) / scale, 0.0F);
        Quaternionf camera = new Quaternionf().rotateX(-headPitch * Mth.DEG_TO_RAD);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(camera);
        graphics.enableScissor(x1, y1, x2, y2);
        graphics.entity(state, scale, translation, rotation, camera, x1, y1, x2, y2);
        graphics.disableScissor();
    }
}
