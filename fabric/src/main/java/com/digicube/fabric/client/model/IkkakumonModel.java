package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Saved native Ikkakumon; source and exporter live in harness/blender. */
public final class IkkakumonModel extends EntityModel<DigimonRenderState> implements AnimatedRiderModel {
    private static final Identifier GEOMETRY = Constants.id("models/entity/ikkakumon.mesh.json");
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Constants.id("ikkakumon"), "main");
    private final NativeAnimationSet animations;
    private final ModelPart body;

    public IkkakumonModel(ModelPart root) {
        super(NativeModelGeometry.apply(root, GEOMETRY));
        animations = new NativeAnimationSet(root, Constants.id("models/entity/ikkakumon.animation.json"));
        body = root.getChild("body");
    }

    public static LayerDefinition createBodyLayer() { return NativeModelGeometry.createLayer(GEOMETRY); }

    @Override public void setupAnim(DigimonRenderState state) {
        super.setupAnim(state);
        float water = smooth(state.swimAnimationAmount);
        float power = Mth.clamp(state.swimMotionAmount, 0, 1);
        animations.blend("walk", state.groundAnimationAmount, state.groundAnimationPhase, 1-water);
        animations.apply("swim_idle", state.swimAnimationPhase, water*(1-power));
        animations.apply("swim", state.swimAnimationPhase, water*power);
        float pitchLimit = state.isBeingRidden ? 8 : 65;
        body.xRot += Mth.clamp(state.xRot, -pitchLimit, pitchLimit) * Mth.DEG_TO_RAD * water;
        body.zRot += state.swimBank * (state.isBeingRidden ? .25F : 1) * Mth.DEG_TO_RAD * water;
    }

    @Override public Vec3 riderOffset(DigimonRenderState state) {
        setupAnim(state);
        var pose = new PoseStack();
        root.translateAndRotate(pose);
        body.translateAndRotate(pose);
        // Same feet anchor reviewed in the native standing-rider file.
        var p = pose.last().pose().transformPosition(.52F, -.515F, .36F, new Vector3f());
        return new Vec3(p.x, 1.5F-p.y, -p.z).scale(state.modelScale).subtract(state.mountAnchor);
    }

    @Override public RiderPose riderPose() { return new RiderPose(0, 0, 0); }
    @Override public double cullingMargin() { return 1.8; }
    private static float smooth(float v) { float x=Mth.clamp(v,0,1); return x*x*(3-2*x); }
}
