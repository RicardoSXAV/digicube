package com.digicube.fabric.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * How a breath of puffs ({@code FrostBreath}) is drawn from its effect model, chosen by its sheet's {@code art}:
 * {@link FrostBreathRenderer} (sections of one flame) or {@link IceShardBreathRenderer} (ice shards, sheets and snow).
 */
public interface BreathArt {
    /** Draws this frame's puffs ({@code state}, relative to the entity) at {@code ageInTicks}. */
    void submit(FrostBreathRenderer.State state, PoseStack pose, SubmitNodeCollector collector, float ageInTicks);
}
