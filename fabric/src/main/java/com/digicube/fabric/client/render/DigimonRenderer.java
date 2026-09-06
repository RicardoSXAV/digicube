package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.AgumonModel;
import com.digicube.fabric.client.model.GabumonModel;
import com.digicube.fabric.client.model.KoromonModel;
import com.digicube.fabric.client.model.TsunomonModel;
import com.digicube.fabric.client.model.GreymonModel;
import com.digicube.fabric.client.model.MegaFlameModel;
import com.digicube.digimon.DigimonAttack;
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
            Constants.id("gabumon"), Constants.id("textures/entity/digimon/gabumon.png"),
            Constants.id("koromon"), Constants.id("textures/entity/digimon/koromon.png"),
            Constants.id("tsunomon"), Constants.id("textures/entity/digimon/tsunomon.png"),
            Constants.id("greymon"), Constants.id("textures/entity/digimon/greymon.png"));
    private static final Identifier FALLBACK_TEXTURE = TEXTURES.get(DigimonEntity.DEFAULT_SPECIES);
    private final Map<Identifier, EntityModel<DigimonRenderState>> models;
    private final MegaFlameModel mouthFlame;

    public DigimonRenderer(EntityRendererProvider.Context context) {
        super(context, new AgumonModel(context.bakeLayer(AgumonModel.LAYER)), 0.4F);
        mouthFlame = new MegaFlameModel(context.bakeLayer(MegaFlameModel.LAYER));
        this.models = Map.of(
                DigimonEntity.DEFAULT_SPECIES, this.model,
                Constants.id("gabumon"), new GabumonModel(context.bakeLayer(GabumonModel.LAYER)),
                Constants.id("koromon"), new KoromonModel(context.bakeLayer(KoromonModel.LAYER)),
                Constants.id("tsunomon"), new TsunomonModel(context.bakeLayer(TsunomonModel.LAYER)),
                Constants.id("greymon"), new GreymonModel(context.bakeLayer(GreymonModel.LAYER)));
    }

    @Override
    public void submit(DigimonRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        this.model = this.models.getOrDefault(state.species, this.models.get(DigimonEntity.DEFAULT_SPECIES));
        super.submit(state, poseStack, collector, cameraState);
        if (!state.isBeingRidden && state.attackAnimation.isStarted() && state.attackDefinition != null
                && state.attackDefinition.kind() == DigimonAttack.Kind.FLAME_SHOT) {
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50.0F;
            if (tick >= 7 && tick < 22) {
                var frame = state.attackDefinition.motion().sample(tick);
                var mouth = frame.aimedMouth(state.attackAimPitch).yRot(-state.bodyRot * Mth.DEG_TO_RAD);
                var flame = state.mouthFlame;
                flame.ageInTicks = tick;
                flame.charging = true;
                flame.burst = false;
                flame.yRot = state.bodyRot;
                flame.xRot = -(frame.headPitch() + state.attackAimPitch * frame.aimWeight());
                flame.outlineColor = state.outlineColor;
                poseStack.pushPose();
                poseStack.translate(mouth.x, mouth.y, mouth.z);
                MegaFlameRenderer.submitFlame(mouthFlame, flame, poseStack, collector);
                poseStack.popPose();
            }
        }
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
        state.isBeingRidden = entity.isVehicle();
        state.runAnimationAmount = entity.getRunAnimationAmount(partialTick);
        state.shadowRadius = entity.getBbWidth() * 0.5F;
        state.attackAnimation.copyFrom(entity.attackAnimationState);
        state.attackAnimationName = entity.getAttackAnimationName();
        state.attackDefinition = entity.getAnimatingAttack();
        state.attackAimPitch = entity.getAttackAimPitch(partialTick);
        if (state.isBeingRidden
                || state.attackAnimation.isStarted() && state.attackDefinition != null && state.attackDefinition.locksBodyFacing()) {
            // Riding and committed attacks turn the entire creature. Keep the rendered
            // body aligned with the synced yaw used by the passenger attachment.
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
