package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

/** Per-frame snapshot of a {@link DigimonEntity} that the model and renderer read from. */
public class DigimonRenderState extends LivingEntityRenderState {

    public Identifier species = DigimonEntity.DEFAULT_SPECIES;

    /** 0 = mouth closed, 1 = fully open. Driven by attacks once they exist. */
    public float jawOpen;
}
