package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Agumon, as a vanilla-style cube model.
 *
 * <p>The geometry mirrors {@code tools/blender/agumon_model.py} part for part:
 * same bones, same pivots, same box-UV atlas (128x64). Vanilla model space has
 * Y pointing down with the ground at y=24 and the front at -Z, so a Blender
 * cube {@code (x, y, z)} becomes {@code (x, 24 - z, y)} here.
 *
 * <p>Bones exposed for future attacks: {@code jaw} (rotate +X to open the mouth,
 * Pepper Breath) and {@code left_arm} / {@code right_arm} (Claw Attack).
 */
public class AgumonModel extends EntityModel<DigimonRenderState> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Constants.id("agumon"), "main");

    private final ModelPart head;
    private final ModelPart jaw;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;
    private final ModelPart tail;

    public AgumonModel(ModelPart root) {
        super(root);
        ModelPart body = root.getChild("body");
        this.head = body.getChild("head");
        this.jaw = this.head.getChild("jaw");
        this.leftArm = body.getChild("left_arm");
        this.rightArm = body.getChild("right_arm");
        this.leftLeg = root.getChild("left_leg");
        this.rightLeg = root.getChild("right_leg");
        this.tail = body.getChild("tail");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // Body leans forward 8 degrees; the head counter-tilts so it stays level.
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(44, 0).addBox(-5.0F, -9.0F, -4.0F, 10.0F, 9.0F, 8.0F),
                PartPose.offsetAndRotation(0.0F, 15.0F, 0.0F, rad(8.0F), 0.0F, 0.0F));

        PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-6.0F, -10.0F, -4.0F, 12.0F, 10.0F, 10.0F)
                        .texOffs(0, 20).addBox(-5.0F, -5.0F, -11.0F, 10.0F, 4.0F, 7.0F),   // snout
                PartPose.offsetAndRotation(0.0F, -9.0F, -1.0F, rad(-8.0F), 0.0F, 0.0F));

        head.addOrReplaceChild("jaw", CubeListBuilder.create()
                        .texOffs(86, 20).addBox(-5.0F, 0.0F, -7.0F, 10.0F, 2.0F, 7.0F),
                PartPose.offset(0.0F, -1.0F, -4.0F));

        // Arms hang forward with a slight outward splay; three claws under each hand.
        body.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(0, 31).addBox(0.0F, 0.0F, -1.5F, 3.0F, 6.0F, 3.0F)
                        .texOffs(12, 31).addBox(-1.0F, 6.0F, -2.0F, 5.0F, 3.0F, 4.0F)
                        .texOffs(44, 31).addBox(-1.0F, 9.0F, -0.5F, 1.0F, 3.0F, 1.0F)
                        .addBox(1.0F, 9.0F, -0.5F, 1.0F, 3.0F, 1.0F)
                        .addBox(3.0F, 9.0F, -0.5F, 1.0F, 3.0F, 1.0F),
                PartPose.offsetAndRotation(5.0F, -7.5F, -1.0F, rad(-25.0F), 0.0F, rad(-10.0F)));

        body.addOrReplaceChild("right_arm", CubeListBuilder.create().mirror()
                        .texOffs(0, 31).addBox(-3.0F, 0.0F, -1.5F, 3.0F, 6.0F, 3.0F)
                        .texOffs(12, 31).addBox(-4.0F, 6.0F, -2.0F, 5.0F, 3.0F, 4.0F)
                        .texOffs(44, 31).addBox(0.0F, 9.0F, -0.5F, 1.0F, 3.0F, 1.0F)
                        .addBox(-2.0F, 9.0F, -0.5F, 1.0F, 3.0F, 1.0F)
                        .addBox(-4.0F, 9.0F, -0.5F, 1.0F, 3.0F, 1.0F),
                PartPose.offsetAndRotation(-5.0F, -7.5F, -1.0F, rad(-25.0F), 0.0F, rad(10.0F)));

        // Legs hang off the root so the body can lean without moving the feet.
        root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                        .texOffs(80, 0).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 7.0F, 5.0F)
                        .texOffs(34, 20).addBox(-4.0F, 7.0F, -6.0F, 8.0F, 3.0F, 8.0F)
                        .texOffs(48, 31).addBox(-4.0F, 8.0F, -8.0F, 2.0F, 2.0F, 2.0F)
                        .addBox(-1.0F, 8.0F, -8.0F, 2.0F, 2.0F, 2.0F)
                        .addBox(2.0F, 8.0F, -8.0F, 2.0F, 2.0F, 2.0F),
                PartPose.offset(3.5F, 14.0F, 0.0F));

        root.addOrReplaceChild("right_leg", CubeListBuilder.create().mirror()
                        .texOffs(80, 0).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 7.0F, 5.0F)
                        .texOffs(34, 20).addBox(-4.0F, 7.0F, -6.0F, 8.0F, 3.0F, 8.0F)
                        .texOffs(48, 31).addBox(-4.0F, 8.0F, -8.0F, 2.0F, 2.0F, 2.0F)
                        .addBox(-1.0F, 8.0F, -8.0F, 2.0F, 2.0F, 2.0F)
                        .addBox(2.0F, 8.0F, -8.0F, 2.0F, 2.0F, 2.0F),
                PartPose.offset(-3.5F, 14.0F, 0.0F));

        PartDefinition tail = body.addOrReplaceChild("tail", CubeListBuilder.create()
                        .texOffs(66, 20).addBox(-2.0F, -2.0F, -1.0F, 4.0F, 4.0F, 6.0F),
                PartPose.offsetAndRotation(0.0F, -2.0F, 4.0F, rad(-25.0F), 0.0F, 0.0F));

        tail.addOrReplaceChild("tail_tip", CubeListBuilder.create()
                        .texOffs(30, 31).addBox(-1.5F, -1.5F, 0.0F, 3.0F, 3.0F, 4.0F),
                PartPose.offsetAndRotation(0.0F, 0.0F, 5.0F, rad(-20.0F), 0.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 64);
    }

    @Override
    public void setupAnim(DigimonRenderState state) {
        super.setupAnim(state);   // restores the rest pose; everything below is a delta on top of it

        this.head.yRot += state.yRot * Mth.DEG_TO_RAD;
        this.head.xRot += state.xRot * Mth.DEG_TO_RAD;

        float stride = Mth.cos(state.walkAnimationPos * 0.6662F) * state.walkAnimationSpeed;
        this.rightLeg.xRot += stride;
        this.leftLeg.xRot -= stride;
        this.leftArm.xRot += stride * 0.6F;
        this.rightArm.xRot -= stride * 0.6F;

        float idle = state.ageInTicks;
        float sway = Mth.cos(idle * 0.09F) * 0.04F;
        this.leftArm.zRot -= sway;
        this.rightArm.zRot += sway;
        this.tail.yRot += Mth.sin(idle * 0.1F) * 0.12F
                + Mth.cos(state.walkAnimationPos * 0.6662F) * 0.25F * state.walkAnimationSpeed;

        // Kept closed until the fire attack drives it; see DigimonRenderState.
        this.jaw.xRot += state.jawOpen * rad(38.0F);
    }

    private static float rad(float degrees) {
        return degrees * Mth.DEG_TO_RAD;
    }
}
