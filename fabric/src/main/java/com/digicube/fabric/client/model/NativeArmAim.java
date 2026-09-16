package com.digicube.fabric.client.model;

import com.digicube.entity.KineticGeometry;
import com.digicube.digimon.KineticAttacks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Pitch an authored arm about a model-space axis, retaining its native shoulder pose. */
public final class NativeArmAim {
    private NativeArmAim() {}
    public static void apply(ModelPart root, KineticAttacks.Definition definition, float tick, float pitch) {
        if(definition.blendAim())pitch*=definition.attack().motion().sample(tick).aimWeight();
        if (definition.aimPath().isEmpty() || pitch == 0) return;
        ModelPart part = root;
        PoseStack stack = new PoseStack();
        part.translateAndRotate(stack);
        for (int i = 0; i < definition.aimPath().size() - 1; i++) {
            part = part.getChild(definition.aimPath().get(i));
            part.translateAndRotate(stack);
        }
        part = part.getChild(definition.aimPath().getLast());
        var axis = KineticGeometry.right(definition.motion().sample(tick));
        // Feet space -> ModelPart space is (x, -y, -z), a proper half turn about X.
        Vector3f localAxis = stack.last().pose().invert(new org.joml.Matrix4f())
                .transformDirection(new Vector3f((float) axis.x, (float) -axis.y, (float) -axis.z)).normalize();
        Quaternionf nativeRotation = new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
        Quaternionf aimed = new Quaternionf().rotationAxis((float) Math.toRadians(pitch), localAxis).mul(nativeRotation);
        Vector3f angles = aimed.getEulerAnglesZYX(new Vector3f());
        part.xRot = angles.x; part.yRot = angles.y; part.zRot = angles.z;
    }
}
