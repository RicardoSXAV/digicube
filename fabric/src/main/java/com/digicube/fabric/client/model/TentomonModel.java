package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.entity.ai.FlightPhase;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;

/** Approved native Tentomon. Reproduce with harness/blender/export_tentomon_release.py. */
public final class TentomonModel extends EntityModel<DigimonRenderState> {
    private static final Identifier GEOMETRY = Constants.id("models/entity/tentomon.mesh.json");
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Constants.id("tentomon"), "main");
    private final NativeAnimationSet animations;

    public TentomonModel(ModelPart root) {
        super(NativeModelGeometry.apply(root, GEOMETRY));
        animations = new NativeAnimationSet(root, Constants.id("models/entity/tentomon.animation.json"));
    }
    public static LayerDefinition createBodyLayer() { return NativeModelGeometry.createLayer(GEOMETRY); }

    @Override public void setupAnim(DigimonRenderState state) {
        super.setupAnim(state);
        animations.hideMembranes();
        float t = state.flightPhaseTime;
        switch (state.flightPhase) {
            case GROUNDED -> walk(state.groundAnimationPhase, state.flightWalkAmount, 1);
            case TAKEOFF -> {
                // Let the previous step settle during the opening anticipation.
                walk(state.groundAnimationPhase, state.flightWalkAmount, 1-smooth(t/5));
                animations.apply("takeoff", t, 1);
            }
            case FLYING, APPROACH -> animations.apply("fly", state.flightLoopTime, 1);
            case LANDING -> {
                float blend = smooth(t/3);
                animations.apply("fly", state.flightLoopTime, 1-blend);
                animations.apply("land", t, blend);
            }
        }
    }

    private void walk(float tick, float amount, float weight) {
        float index = Math.clamp(amount, 0, 1) * 8;
        int lower = Math.min(7, (int) index);
        float blend = index-lower;
        animations.apply("walk_"+lower, tick, (1-blend)*weight);
        animations.apply("walk_"+(lower+1), tick, blend*weight);
    }
    private static float smooth(float value) {
        float x = Math.clamp(value, 0, 1);
        return x*x*(3-2*x);
    }
}
