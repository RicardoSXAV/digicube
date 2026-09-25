package com.digicube.fabric.client.render;

import com.digicube.digivice.DroppedDigivice;
import com.digicube.fabric.client.digivice.RecallVisuals;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;

public final class DroppedDigiviceRenderer extends EntityRenderer<DroppedDigivice, DroppedDigiviceRenderer.State> {
    public static final float SCALE = .55F;
    private final ItemModelResolver resolver;
    public static final class State extends EntityRenderState {
        public final ItemStackRenderState item = new ItemStackRenderState();
        public float pitch, yaw;
        public boolean recalled;
    }
    public DroppedDigiviceRenderer(EntityRendererProvider.Context context) {
        super(context); resolver = context.getItemModelResolver(); shadowRadius = .25F;
    }
    @Override public State createRenderState() { return new State(); }
    @Override protected int getBlockLightLevel(DroppedDigivice entity, net.minecraft.core.BlockPos pos) {
        int glow = Math.round(10 * DroppedDigivice.beaconStrength(entity.beaconAt(), entity.level().getGameTime()));
        return Math.max(glow, super.getBlockLightLevel(entity, pos));
    }
    @Override public void extractRenderState(DroppedDigivice entity, State state, float partial) {
        super.extractRenderState(entity, state, partial);
        resolver.updateForNonLiving(state.item, entity.stack(), ItemDisplayContext.NONE, entity);
        state.pitch = entity.pitch(partial); state.yaw = entity.getYRot();
        state.recalled = RecallVisuals.replaces(entity.getUUID());
    }
    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.recalled) return;
        pose.pushPose();
        // Rest the rotated model's lowest point on the collision surface, including during its final settle.
        pose.translate(0, restHeight(state.item.getModelBoundingBox(), state.pitch), 0);
        pose.mulPose(Axis.YP.rotationDegrees(state.yaw)); pose.mulPose(Axis.XP.rotationDegrees(state.pitch));
        pose.scale(SCALE, SCALE, SCALE);
        state.item.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }
    public static double restHeight(net.minecraft.world.phys.AABB bounds, float pitch) {
        double angle = Math.toRadians(pitch), lowest = Double.POSITIVE_INFINITY;
        for (double y : new double[]{bounds.minY, bounds.maxY}) for (double z : new double[]{bounds.minZ, bounds.maxZ})
            lowest = Math.min(lowest, (y * Math.cos(angle) - z * Math.sin(angle)) * SCALE);
        return .002 - lowest;
    }
}
