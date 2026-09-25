package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.digimon.AttackVolley;
import com.digicube.entity.VolleyMissileEntity;
import com.digicube.fabric.client.model.NativeModelGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * A volley missile is the body part that left: its own quads from the species mesh, at the species' scale and in its
 * texture, turned so its tip leads along the flight and spinning about its axis (a drill bores through the air).
 */
public final class VolleyMissileRenderer extends EntityRenderer<VolleyMissileEntity, VolleyMissileRenderer.State> {
    /** Turns a tick: a little over two turns a second. */
    private static final float SPIN = .75F;

    public static final class State extends EntityRenderState {
        ModelPart part;
        Identifier texture;
        float scale, spin, halfHeight;
        Vector3f heading = new Vector3f(0, 0, 1), tip = new Vector3f(0, 0, -1), centre = new Vector3f();
    }

    /** One posed-at-zero part per species mesh and part name, baked once. */
    private final Map<String, ModelPart> parts = new HashMap<>();

    public VolleyMissileRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override public State createRenderState() { return new State(); }

    @Override protected AABB getBoundingBoxForCulling(VolleyMissileEntity entity) { return entity.getBoundingBox().inflate(1); }

    private ModelPart part(AttackVolley volley, String name) {
        return parts.computeIfAbsent(volley.model() + "/" + name, key -> {
            Identifier mesh = Constants.id("models/entity/" + volley.model() + ".mesh.json");
            ModelPart root = NativeModelGeometry.apply(NativeModelGeometry.createLayer(mesh).bakeRoot(), mesh);
            for (var p : NativeModelGeometry.mesh(mesh).parts()) if (p.name().equals(name)) {
                ModelPart part = root;
                for (String child : p.path()) part = part.getChild(child);
                // The flight places it: its own offset, turn and scale in the body would only move it off its axis.
                part.loadPose(PartPose.ZERO);
                part.visible = true;
                return part;
            }
            throw new IllegalStateException("No part " + name + " in " + mesh);
        });
    }

    @Override
    public void extractRenderState(VolleyMissileEntity entity, State state, float partial) {
        super.extractRenderState(entity, state, partial);
        var definition = entity.definition();
        var missile = entity.missile();
        state.part = null;
        if (definition == null || missile == null) return;
        var volley = definition.volley();
        state.part = part(volley, missile.part());
        state.texture = volley.modelId().withPath(path -> "textures/entity/digimon/" + path + ".png");
        state.scale = volley.modelScale();
        state.halfHeight = entity.getBbHeight() * .5F;
        state.spin = (entity.tickCount + partial) * SPIN;
        Vec3 v = entity.getDeltaMovement();
        if (v.lengthSqr() > 1.0E-8) state.heading.set((float) v.x, (float) v.y, (float) v.z).normalize();
        state.tip.set((float) missile.tip().x, (float) missile.tip().y, (float) missile.tip().z);
        state.centre.set((float) missile.centre().x / 16, (float) missile.centre().y / 16, (float) missile.centre().z / 16);
    }

    @Override
    public void submit(State state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.part != null) {
            stack.pushPose();
            stack.translate(0, state.halfHeight, 0);
            stack.mulPose(new Quaternionf().rotationTo(state.tip, state.heading));
            stack.mulPose(new Quaternionf().rotationAxis(state.spin, state.tip.x, state.tip.y, state.tip.z));
            stack.scale(state.scale, state.scale, state.scale);
            stack.translate(-state.centre.x, -state.centre.y, -state.centre.z);
            collector.submitModelPart(state.part, stack, RenderTypes.entityCutout(state.texture), state.lightCoords, OverlayTexture.NO_OVERLAY, null);
            stack.popPose();
        }
        super.submit(state, stack, collector, camera);
    }
}
