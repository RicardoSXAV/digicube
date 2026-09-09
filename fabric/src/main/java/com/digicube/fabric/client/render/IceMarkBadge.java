package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import java.util.ArrayList;
import java.util.List;

/** Camera-facing status medallion, extracted for every living renderer, including vanilla mobs. */
public final class IceMarkBadge {
    public static final RenderStateDataKey<Boolean> MARKED = RenderStateDataKey.create();
    private static final RenderStateDataKey<List<Badge>> BADGES = RenderStateDataKey.create();
    private static final Identifier TEXTURE = Constants.id("textures/entity/status/ice_mark_badge.png");
    private record Badge(double x, double y, double z) {}

    private IceMarkBadge() {}

    public static void init() {
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            var badges = new ArrayList<Badge>();
            for (var state : context.levelState().entityRenderStates) {
                if (!Boolean.TRUE.equals(((FabricRenderState) state).getData(MARKED))
                        || state.isInvisible || state.distanceToCameraSq > 48 * 48) continue;
                double height = state.boundingBoxHeight + .48;
                if (state.nameTag != null && state.nameTagAttachment != null) {
                    height = Math.max(height, state.nameTagAttachment.y + .65);
                }
                badges.add(new Badge(state.x, state.y + height, state.z));
            }
            // Vanilla clears entityRenderStates before COLLECT_SUBMITS. Keep value snapshots on this frame.
            ((FabricRenderState) context.levelState()).setData(BADGES, List.copyOf(badges));
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            var badges = ((FabricRenderState) context.levelState()).getData(BADGES);
            if (badges == null) return;
            var camera = context.levelState().cameraRenderState;
            var pose = context.poseStack();
            for (var badge : badges) {
                pose.pushPose();
                pose.translate(badge.x - camera.pos.x, badge.y - camera.pos.y, badge.z - camera.pos.z);
                pose.mulPose(camera.orientation);
                // A small fixed world size; depth-tested so the badge never reveals mobs through walls.
                context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE),
                        (matrix, vertices) -> {
                            vertex(matrix, vertices, -.28F, -.28F, 0, 1);
                            vertex(matrix, vertices, .28F, -.28F, 1, 1);
                            vertex(matrix, vertices, .28F, .28F, 1, 0);
                            vertex(matrix, vertices, -.28F, .28F, 0, 0);
                        });
                pose.popPose();
            }
        });
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, float x, float y, float u, float v) {
        vertices.addVertex(pose, x, y, 0).setColor(-1).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
    }
}
