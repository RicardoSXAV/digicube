package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.BoomerangEntity;
import com.digicube.entity.IcicleEntity;
import com.digicube.fabric.client.model.NativeEffectModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Thrown weapons drawn with their native mesh: the returning bone spins about its middle as it flies, leaning from
 * upright off the hand to flat coming home, tumbles as it falls and lies still where it lands; the icicle points along
 * its flight at the size its charge gave it.
 */
public final class ThrownPropRenderer<T extends Projectile> extends EntityRenderer<T, NativeEffectState> {
    private final NativeEffectModel model;
    private final String name;
    private final boolean bone;

    private ThrownPropRenderer(EntityRendererProvider.Context context, String name, boolean bone) {
        super(context);
        this.name = name; this.bone = bone;
        model = new NativeEffectModel(context.bakeLayer(NativeEffectModel.layer(name)), name);
    }
    public static EntityRendererProvider<BoomerangEntity> bone(String name) { return context -> new ThrownPropRenderer<>(context, name, true); }
    public static EntityRendererProvider<IcicleEntity> icicle(String name) { return context -> new ThrownPropRenderer<>(context, name, false); }

    @Override public NativeEffectState createRenderState() { return new NativeEffectState(); }
    @Override protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(T entity) { return entity.getBoundingBox().inflate(3); }

    /**
     * Bone: {@code yaw} is its spin about the vertical, {@code pitch} its tilt out of the flat, {@code offset} where
     * the path puts it relative to the entity. Icicle: {@code yaw} and {@code pitch} are the flight's heading.
     */
    @Override public void extractRenderState(T entity, NativeEffectState state, float partial) {
        super.extractRenderState(entity, state, partial);
        state.projectile = name; state.clip = "effect"; state.tick = 0; state.emissive = false; state.offset = Vec3.ZERO; state.spinAxis = null;
        if (entity instanceof BoomerangEntity b) {
            var spec = b.spec();
            state.scale = spec == null ? .34F : spec.modelScale();
            state.offset = b.renderPosition(partial).subtract(entity.getPosition(partial));
            state.yaw = Mth.lerp(partial, b.spinO, b.spin);
            state.spinAxis = b.spinAxis(partial);
            state.pitch = switch (b.phase()) { case FLYING, CATCHING -> 12; case DROPPING -> 24 * Mth.sin((entity.tickCount + partial) * .6F); case GROUNDED -> 0; };
            if (b.phase() == BoomerangEntity.Phase.GROUNDED) state.offset = state.offset.add(0, .09, 0);
        } else if (entity instanceof IcicleEntity icicle) {
            var spec = icicle.spec();
            state.scale = spec == null ? .34F : spec.modelScale() * spec.mix(spec.size(), icicle.charge());
            Vec3 h = icicle.heading();
            state.yaw = (float) Math.toDegrees(Math.atan2(h.z, h.x));
            state.pitch = (float) Math.toDegrees(Math.asin(Math.clamp(h.y, -1, 1)));
            if (icicle.phase() == IcicleEntity.Phase.SHATTERED) state.projectile = null;
        }
    }

    @Override public void submit(NativeEffectState state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.projectile == null) return;
        stack.pushPose();
        stack.translate(state.offset.x, state.offset.y, state.offset.z);
        if (bone) {
            // Spinning about its middle: in the air about the axis its flight gives it (upright off the hand, flat coming
            // home), tumbling or lying flat otherwise.
            if (state.spinAxis != null) stack.mulPose(new Quaternionf().rotationTo(0, 1, 0, (float) state.spinAxis.x, (float) state.spinAxis.y, (float) state.spinAxis.z));
            stack.mulPose(Axis.YP.rotationDegrees(state.yaw));
            stack.mulPose(Axis.XP.rotationDegrees(90 + (state.spinAxis != null ? 0 : state.pitch)));
        } else {
            // The shaft (model +X, flipped to -X by the model's axes) turned onto the flight.
            stack.mulPose(new Quaternionf().rotationTo(-1, 0, 0,
                    (float) (Math.cos(Math.toRadians(state.pitch)) * Math.cos(Math.toRadians(state.yaw))),
                    (float) Math.sin(Math.toRadians(state.pitch)),
                    (float) (Math.cos(Math.toRadians(state.pitch)) * Math.sin(Math.toRadians(state.yaw)))));
        }
        stack.scale(-state.scale, -state.scale, state.scale);
        stack.translate(0, EntityModel.MODEL_Y_OFFSET, 0);
        collector.submitModel(model, state, stack, RenderTypes.entityCutout(Constants.id("textures/entity/projectile/" + name + ".png")),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        stack.popPose();
        super.submit(state, stack, collector, camera);
    }
}
