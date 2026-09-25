package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.PepperBreathEntity;
import com.digicube.fabric.client.model.NativeEffectModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;

/**
 * Draws the Pepper Breath fireball from Agumon's effect model: the cubic core, its tongues, tail and ribbons on the
 * looping {@code fireball_flight} clip while it flies, then the {@code fireball_impact} flare where it struck. The
 * core sits on the centre of the hitbox and the tail trails along the flight.
 */
public class PepperBreathRenderer extends EntityRenderer<PepperBreathEntity, NativeEffectState> {

    static final String EFFECT = "pepper_breath_fx";
    private static final Identifier TEXTURE = Constants.id("textures/entity/digimon/" + EFFECT + ".png");
    /** The effect is authored in Agumon's model pixels, so it is drawn at Agumon's scale: the core is 0.37 blocks across. */
    static final float SCALE = 1.3F / 7;
    /** The flight clip carries the core this many pixels ahead of the effect's origin. */
    static final float CORE_AHEAD = 25;

    private final NativeEffectModel model;

    public PepperBreathRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new NativeEffectModel(context.bakeLayer(NativeEffectModel.layer(EFFECT)), EFFECT);
        this.shadowRadius = 0.0F;
    }

    @Override
    public NativeEffectState createRenderState() {
        return new NativeEffectState();
    }

    @Override
    protected AABB getBoundingBoxForCulling(PepperBreathEntity entity) {
        return entity.getBoundingBox().inflate(1.0);
    }

    @Override
    public void extractRenderState(PepperBreathEntity entity, NativeEffectState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.scale = SCALE;
        // A projectile keeps yaw as atan2(x, z) and pitch positive upward; the effect transform takes a mob's angles.
        state.yaw = -entity.getYRot(partialTick);
        state.pitch = -entity.getXRot(partialTick);
        if (entity.impacting()) {
            state.clip = "fireball_impact";
            state.tick = entity.impactTick(partialTick);
        } else {
            state.clip = "fireball_flight";
            state.tick = entity.tickCount + partialTick;
        }
    }

    @Override
    public void submit(NativeEffectState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0F, state.boundingBoxHeight * 0.5F, 0.0F);
        transform(poseStack, state.yaw, state.pitch);
        collector.submitModel(model, state, poseStack, AfterWaterEffects.glow(TEXTURE),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /**
     * From the centre of the hitbox into the effect's model space: nose (-Z) along the flight, with the core on the
     * centre. Yaw and pitch in the mob convention (pitch positive downward). Shared with the parity check.
     */
    public static void transform(PoseStack poseStack, float yaw, float pitch) {
        KineticProjectileRenderer.transform(poseStack, yaw, pitch, SCALE);
        poseStack.translate(0.0F, 0.0F, CORE_AHEAD / 16);
    }
}
