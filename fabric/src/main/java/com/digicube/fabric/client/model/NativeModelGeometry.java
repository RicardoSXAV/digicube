package com.digicube.fabric.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Loads harness-authored quads, including tapered solids and single pixel sheets. */
public final class NativeModelGeometry {
    private NativeModelGeometry() {}

    private static JsonObject read(Identifier resource) {
        String path = "/assets/" + resource.getNamespace() + "/" + resource.getPath();
        try (var input = NativeModelGeometry.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing native model " + resource);
            var data = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (data.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported model " + resource);
            return data;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load native model " + resource, e);
        }
    }

    /**
     * Bake the native hierarchy with one bounded cube slot per authored quad.
     * @param resource exported mesh resource
     * @return model layer ready for the usual layer registry
     */
    public static LayerDefinition createLayer(Identifier resource) {
        var data = read(resource);
        var mesh = new MeshDefinition();
        Map<String, PartDefinition> parts = new HashMap<>();
        parts.put("", mesh.getRoot());
        for (var element : data.getAsJsonArray("parts")) {
            var part = element.getAsJsonObject();
            String path = path(part.getAsJsonArray("path"));
            int separator = path.lastIndexOf('/');
            String parentPath = separator < 0 ? "" : path.substring(0, separator);
            var builder = CubeListBuilder.create();
            for (var quad : part.getAsJsonArray("quads")) {
                float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
                float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
                var vertices = quad.getAsJsonObject().getAsJsonArray("vertices");
                if (vertices.size() != 4) throw new IllegalArgumentException("Native faces must be quads: " + path);
                for (var vertex : vertices) for (int axis = 0; axis < 3; axis++) {
                    float value = number(vertex.getAsJsonArray(), axis);
                    min[axis] = Math.min(min[axis], value);
                    max[axis] = Math.max(max[axis], value);
                }
                builder.addBox(min[0], min[1], min[2], max[0]-min[0], max[1]-min[1], max[2]-min[2], Set.of(Direction.UP));
            }
            var pose = part.getAsJsonArray("pose");
            if (pose.size() != 6 && pose.size() != 9) throw new IllegalArgumentException("Invalid native pose: " + path);
            var transform = PartPose.offsetAndRotation(number(pose,0), number(pose,1), number(pose,2),
                    number(pose,3), number(pose,4), number(pose,5));
            if (pose.size() == 9) transform = new PartPose(number(pose,0), number(pose,1), number(pose,2),
                    number(pose,3), number(pose,4), number(pose,5), number(pose,6), number(pose,7), number(pose,8));
            var parent = parts.get(parentPath);
            if (parent == null || parts.containsKey(path)) throw new IllegalArgumentException("Invalid model hierarchy: " + path);
            parts.put(path, parent.addOrReplaceChild(part.get("name").getAsString(), builder,
                    transform));
        }
        return LayerDefinition.create(mesh, data.get("texture_width").getAsInt(), data.get("texture_height").getAsInt());
    }

    /**
     * Replace each baked slot with the exact original vertices, UVs and normal.
     * A public polygon array is supplied by Minecraft; no access widening is needed.
     * @param root baked hierarchy
     * @param resource same mesh used to create the layer
     * @return the supplied root, with its native surfaces installed
     */
    public static ModelPart apply(ModelPart root, Identifier resource) {
        Map<String, JsonArray> quads = new HashMap<>();
        for (var element : read(resource).getAsJsonArray("parts")) {
            var p = element.getAsJsonObject();
            quads.put("/" + path(p.getAsJsonArray("path")), p.getAsJsonArray("quads"));
        }
        int expected = quads.values().stream().mapToInt(JsonArray::size).sum();
        int[] count = {0};
        root.visit(new com.mojang.blaze3d.vertex.PoseStack(), (pose, path, index, cube) -> {
            var face = quads.get(path).get(index).getAsJsonObject();
            var json = face.getAsJsonArray("vertices");
            var vertices = new ModelPart.Vertex[4];
            for (int i = 0; i < 4; i++) {
                var v = json.get(i).getAsJsonArray();
                vertices[i] = new ModelPart.Vertex(number(v,0), number(v,1), number(v,2), number(v,3), number(v,4));
            }
            var n = face.getAsJsonArray("normal");
            if (cube.polygons.length != 1) throw new IllegalStateException("Competing native faces at " + path);
            cube.polygons[0] = new ModelPart.Polygon(vertices, new Vector3f(number(n,0), number(n,1), number(n,2)));
            count[0]++;
        });
        if (count[0] != expected) throw new IllegalStateException("Incomplete native model " + resource);
        return root;
    }

    private static String path(JsonArray path) {
        var names = new java.util.ArrayList<String>();
        path.forEach(n -> names.add(n.getAsString()));
        return String.join("/", names);
    }

    private static float number(JsonArray values, int index) {
        float value = values.get(index).getAsFloat();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite native model coordinate");
        return value;
    }
}
