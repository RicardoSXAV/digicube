package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AnimationState;

/** Per-frame snapshot of a {@link DigimonEntity} that the model and renderer read from. */
public class DigimonRenderState extends LivingEntityRenderState {
    public com.digicube.digimon.DigimonAttack attackDefinition;
    public float attackAimPitch;
    public final MegaFlameRenderState mouthFlame = new MegaFlameRenderState();
    public final BlueBlasterRenderState blueBlaster = new BlueBlasterRenderState();

    public Identifier species = DigimonEntity.DEFAULT_SPECIES;
    public float modelScale = 0.75F;

    /** The tamer is riding this Digimon; its visible seat must remain aligned. */
    public boolean isBeingRidden;

    /** Smooth walk/run blend from the server's follow state, zero at walking pace. */
    public float runAnimationAmount;

    /** Clock of the attack animation in progress; copied from the entity every frame. */
    public final AnimationState attackAnimation = new AnimationState();

    /**
     * Harness animation name to play with {@link #attackAnimation}, e.g. {@code claw},
     * {@code claw_mirrored}, {@code pepper_breath}; null while idle. Generated models look
     * it up in their baked animation map.
     */
    public String attackAnimationName;
}
