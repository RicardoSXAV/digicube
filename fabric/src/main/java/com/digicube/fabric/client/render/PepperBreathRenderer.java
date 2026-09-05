package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.PepperBreathEntity;
import com.digicube.fabric.client.model.PepperBreathModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Draws the Pepper Breath fireball: flat pixel planes, full bright, turned to face the
 * direction of flight. The planes are billboards: the flame sheet (head + tail profile)
 * rolls about the travel axis toward the camera and the round ball sprite turns to face
 * it, so the fireball reads as a ball with a streaky tail from every angle. The angles
 * are worked out here from the camera position and handed to the model through the
 * render state; the model's {@code setupAnim} applies them and flips the frames.
 */
public class PepperBreathRenderer extends EntityRenderer<PepperBreathEntity, PepperBreathRenderState> {

    private static final Identifier TEXTURE = Constants.id("textures/entity/projectile/pepper_breath.png");
    /** The ball is authored 16 px across, so 1.0 makes it one block wide, wider than Agumon's head. */
    private static final float SCALE = 1.0F;

    private final PepperBreathModel model;

    public PepperBreathRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new PepperBreathModel(context.bakeLayer(PepperBreathModel.LAYER));
        this.shadowRadius = 0.0F;
    }

    @Override
    public PepperBreathRenderState createRenderState() {
        return new PepperBreathRenderState();
    }

    @Override
    public void extractRenderState(PepperBreathEntity entity, PepperBreathRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.yRot = entity.getYRot(partialTick);
        state.xRot = entity.getXRot(partialTick);
    }

    @Override
    public void submit(PepperBreathRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        aimBillboards(state, camera.pos);
        poseStack.pushPose();
        // Centre of the hitbox, then point the model's front (-Z) along the flight direction.
        poseStack.translate(0.0F, state.boundingBoxHeight * 0.5F, 0.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.yRot + 180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.xRot));
        // Vanilla models are authored Y-down around ground level y=24: flip and lift like living entities.
        poseStack.scale(-SCALE, -SCALE, SCALE);
        poseStack.translate(0.0F, EntityModel.MODEL_Y_OFFSET, 0.0F);
        collector.submitModel(model, state, poseStack, RenderTypes.entityTranslucentEmissive(TEXTURE),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /**
     * Expresses the direction from the fireball to the camera in model space (after the
     * yaw/pitch above and the Y-down flip) and derives the billboard angles from it.
     */
    private static void aimBillboards(PepperBreathRenderState state, Vec3 cameraPos) {
        Vector3f toCamera = new Vector3f(
                (float) (cameraPos.x - state.x),
                (float) (cameraPos.y - (state.y + state.boundingBoxHeight * 0.5)),
                (float) (cameraPos.z - state.z));
        // Undo the flight rotation (yaw then pitch, so unwind pitch last); the flip is a half
        // turn about Z, so it negates X and Y.
        toCamera.rotateY(-(state.yRot + 180.0F) * Mth.DEG_TO_RAD);
        toCamera.rotateX(-state.xRot * Mth.DEG_TO_RAD);
        toCamera.set(-toCamera.x, -toCamera.y, toCamera.z);
        float length = toCamera.length();
        if (length < 1.0E-3F) {
            state.tailRoll = state.headPitch = state.headYaw = state.headRoll = 0.0F;
            return;
        }
        toCamera.div(length);

        // Sheet: a plane through the travel axis (Z) whose normal starts along +X; roll it
        // about Z so the normal points at the camera's sideways component.
        state.tailRoll = (float) Math.atan2(toCamera.y, toCamera.x);

        // Ball: a plane whose normal starts along +Z; ModelPart rotates Z-Y-X, so pitch about
        // X first, then yaw about Y, brings the normal to (cos p sin y, -sin p, cos p cos y).
        state.headYaw = (float) Math.atan2(toCamera.x, toCamera.z);
        state.headPitch = (float) -Math.asin(Mth.clamp(toCamera.y, -1.0F, 1.0F));

        // Then roll the sprite in its own plane so its bright side (+X in the art) points
        // along the projected flight direction (-Z in model space). Head-on there is nothing
        // to align to; keep the roll at zero rather than spinning on noise.
        Vector3f forward = new Vector3f(0.0F, 0.0F, -1.0F)
                .rotateY(-state.headYaw)
                .rotateX(-state.headPitch);
        float sideways = forward.x * forward.x + forward.y * forward.y;
        state.headRoll = sideways > 0.01F ? (float) Math.atan2(forward.y, forward.x) : 0.0F;
    }
}
