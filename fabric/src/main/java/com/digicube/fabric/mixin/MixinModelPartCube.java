package com.digicube.fabric.mixin;

import com.digicube.fabric.client.model.NativeCube;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a native face from its installed polygon exactly as vanilla does, ahead of any renderer that rebuilds the
 * cube from its constructor box: Sodium's entity path would draw the placeholder box's top and flatten the model.
 */
@Mixin(ModelPart.Cube.class)
public abstract class MixinModelPartCube implements NativeCube {
    @Shadow @Final public ModelPart.Polygon[] polygons;
    @Unique private boolean digicube$native;

    @Override
    public void digicube$markNative() {
        digicube$native = true;
    }

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void digicube$compileNative(PoseStack.Pose pose, VertexConsumer builder, int light, int overlay, int color, CallbackInfo ci) {
        if (!digicube$native) return;
        ci.cancel();
        Matrix4f matrix = pose.pose();
        Vector3f scratch = new Vector3f();
        for (var polygon : polygons) {
            var normal = pose.transformNormal(polygon.normal(), scratch);
            float nx = normal.x(), ny = normal.y(), nz = normal.z();
            for (var vertex : polygon.vertices()) {
                var position = matrix.transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), scratch);
                builder.addVertex(position.x(), position.y(), position.z(), color, vertex.u(), vertex.v(), overlay, light, nx, ny, nz);
            }
        }
    }
}
