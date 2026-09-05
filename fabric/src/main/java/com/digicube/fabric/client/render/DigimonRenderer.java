package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.AgumonModel;
import com.digicube.fabric.client.model.KoromonModel;
import com.digicube.fabric.client.model.GreymonModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.Map;

/**
 * Renders every {@link DigimonEntity} with its species model and texture.
 * Species without authored assets use Agumon's model and texture together.
 */
public class DigimonRenderer extends MobRenderer<DigimonEntity, DigimonRenderState, EntityModel<DigimonRenderState>> {

    private static final Map<Identifier, Identifier> TEXTURES = Map.of(
            Constants.id("agumon"), Constants.id("textures/entity/digimon/agumon.png"),
            Constants.id("koromon"), Constants.id("textures/entity/digimon/koromon.png"),
            Constants.id("greymon"), Constants.id("textures/entity/digimon/greymon.png"));
    private static final Identifier FALLBACK_TEXTURE = TEXTURES.get(DigimonEntity.DEFAULT_SPECIES);
    private final Map<Identifier, EntityModel<DigimonRenderState>> models;

    public DigimonRenderer(EntityRendererProvider.Context context) {
        super(context, new AgumonModel(context.bakeLayer(AgumonModel.LAYER)), 0.4F);
        this.models = Map.of(
                DigimonEntity.DEFAULT_SPECIES, this.model,
                Constants.id("koromon"), new KoromonModel(context.bakeLayer(KoromonModel.LAYER)),
                Constants.id("greymon"), new GreymonModel(context.bakeLayer(GreymonModel.LAYER)));
    }

    @Override
    public void submit(DigimonRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        this.model = this.models.getOrDefault(state.species, this.models.get(DigimonEntity.DEFAULT_SPECIES));
        super.submit(state, poseStack, collector, cameraState);
    }

    @Override
    public DigimonRenderState createRenderState() {
        return new DigimonRenderState();
    }

    @Override
    public void extractRenderState(DigimonEntity entity, DigimonRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.species = entity.getSpeciesId();
        state.modelScale = entity.getBody().modelScale();
        state.shadowRadius = entity.getBbWidth() * 0.5F;
        state.attackAnimation.copyFrom(entity.attackAnimationState);
        state.attackAnimationName = entity.getAttackAnimationName();
        if (state.attackAnimation.isStarted() && "bubble_blow".equals(state.attackAnimationName)) {
            // Bubble Blow turns the entire creature. Vanilla body rotation can lag
            // behind its synced yaw by up to the head/body limit while standing still.
            state.bodyRot = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
            state.yRot = 0.0F;
        }
    }

    @Override
    public Identifier getTextureLocation(DigimonRenderState state) {
        return TEXTURES.getOrDefault(state.species, FALLBACK_TEXTURE);
    }

    @Override
    protected void scale(DigimonRenderState state, PoseStack poseStack) {
        poseStack.scale(state.modelScale, state.modelScale, state.modelScale);
    }
}
