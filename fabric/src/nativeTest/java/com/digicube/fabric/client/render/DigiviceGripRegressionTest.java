package com.digicube.fabric.client.render;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.io.InputStreamReader;
import java.nio.file.*;

/** Actual vanilla wide/slim arms must meet the held casing, on either side. */
public final class DigiviceGripRegressionTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        JsonObject item;
        try (var in = DigiviceGripRegressionTest.class.getResourceAsStream("/assets/digicube/models/item/digivice.json")) {
            item = JsonParser.parseReader(new InputStreamReader(java.util.Objects.requireNonNull(in))).getAsJsonObject();
        }
        var evidence = new JsonArray();
        for (boolean slim : new boolean[]{false, true}) for (var side : HumanoidArm.values()) {
            int sign = side == HumanoidArm.RIGHT ? 1 : -1;
            var root = LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64).bakeRoot();
            var arm = root.getChild(side == HumanoidArm.RIGHT ? "right_arm" : "left_arm");
            arm.resetPose(); arm.zRot = sign * .1F; // AvatarRenderer.renderHand's real pose.
            System.out.println("Arm " + side + " slim=" + slim + " pivot=" + arm.x + "," + arm.y + "," + arm.z);
            var pose = new PoseStack();
            DigiviceGrip.apply(pose, side, slim);
            var display = item.getAsJsonObject("display").getAsJsonObject(side == HumanoidArm.RIGHT
                    ? "firstperson_righthand" : "firstperson_lefthand");
            var rotation = display.getAsJsonArray("rotation");
            var translation = display.getAsJsonArray("translation");
            var scale = display.getAsJsonArray("scale");
            var device = new Matrix4f().translation(sign * translation.get(0).getAsFloat()/16,
                    translation.get(1).getAsFloat()/16, translation.get(2).getAsFloat()/16)
                    .rotate(new Quaternionf().rotationXYZ((float)Math.toRadians(rotation.get(0).getAsFloat()),
                            (float)Math.toRadians(sign * rotation.get(1).getAsFloat()),
                            (float)Math.toRadians(sign * rotation.get(2).getAsFloat())))
                    .scale(scale.get(0).getAsFloat(), scale.get(1).getAsFloat(), scale.get(2).getAsFloat());
            // Lower outer casing, authored x=23, z=6, front y=-11 pixels.
            var contact = device.transformPosition(sign * 23F/64, (6F-28)/64, 11F/64, new Vector3f());
            var polygons = new JsonArray();
            boolean[] contactInsidePalm = {false};
            arm.visit(pose, (matrix, path, index, cube) -> {
                if (path.isEmpty()) {
                    var local = new Matrix4f(matrix.pose()).invert().transformPosition(contact, new Vector3f()).mul(16);
                    contactInsidePalm[0] |= local.x >= cube.minX && local.x <= cube.maxX
                            && local.y >= cube.maxY - 3 && local.y <= cube.maxY
                            && local.z >= cube.minZ && local.z <= cube.maxZ;
                }
                for (var polygon : cube.polygons) {
                    var points = new JsonArray();
                    for (var vertex : polygon.vertices()) {
                        var point = matrix.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
                        var v = new JsonArray(); v.add(point.x); v.add(point.y); v.add(point.z); v.add(vertex.u()); v.add(vertex.v()); points.add(v);
                    }
                    polygons.add(points);
                }
            });
            if (!contactInsidePalm[0]) throw new AssertionError("Casing misses vanilla palm: " + side + " slim=" + slim);
            var entry = new JsonObject(); entry.addProperty("slim", slim); entry.addProperty("side", side.name());
            entry.add("polygons", polygons); evidence.add(entry);
        }
        if (args.length > 0) Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        System.out.println("PASS: Digivice casing meets the real wide/slim palms in both hands (4 fixtures)");
    }
}
