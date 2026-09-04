package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.AgumonModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Renders every {@link DigimonEntity}. The texture is picked by species; the
 * model is Agumon's until more species get their own geometry.
 */
public class DigimonRenderer extends MobRenderer<DigimonEntity, DigimonRenderState, AgumonModel> {

    /** Model pixels are authored at 16 px per block; 0.75 brings Agumon down to ~1.3 blocks. */
    private static final float SCALE = 0.75F;

    private static final Map<Identifier, Identifier> TEXTURES = Map.of(
            Constants.id("agumon"), Constants.id("textures/entity/digimon/agumon.png"));
    private static final Identifier FALLBACK_TEXTURE = TEXTURES.get(DigimonEntity.DEFAULT_SPECIES);

    public DigimonRenderer(EntityRendererProvider.Context context) {
        super(context, new AgumonModel(context.bakeLayer(AgumonModel.LAYER)), 0.4F);
    }

    @Override
    public DigimonRenderState createRenderState() {
        return new DigimonRenderState();
    }

    @Override
    public void extractRenderState(DigimonEntity entity, DigimonRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.species = entity.getSpeciesId();
        state.jawOpen = 0.0F;
    }

    @Override
    public Identifier getTextureLocation(DigimonRenderState state) {
        return TEXTURES.getOrDefault(state.species, FALLBACK_TEXTURE);
    }

    @Override
    protected void scale(DigimonRenderState state, PoseStack poseStack) {
        poseStack.scale(SCALE, SCALE, SCALE);
    }
}
