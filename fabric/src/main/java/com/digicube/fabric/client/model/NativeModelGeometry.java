package com.digicube.fabric.client.model;

import com.google.gson.stream.JsonReader;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Loads harness-authored quads, including tapered solids and single pixel sheets. */
public final class NativeModelGeometry {
    private NativeModelGeometry() {}

    /**
     * One authored face.
     * @param vertices four rows of x, y, z, u, v
     * @param normal face normal
     */
    public record Quad(float[][] vertices, float[] normal) {}
    /**
     * One authored part.
     * @param name child name
     * @param path child chain from the root, ending in this part
     * @param pose offset, rotation and optional scale, six or nine numbers
     * @param quads faces in slot order
     */
    public record Part(String name, String[] path, float[] pose, Quad[] quads) {}
    /** A parsed mesh, read once and shared by the layer, the surfaces and any part lookups. */
    public record Mesh(int textureWidth, int textureHeight, Part[] parts) {}

    private static final Map<Identifier, Mesh> MESHES = new ConcurrentHashMap<>();

    /**
     * The parsed mesh, cached for the life of the client.
     * @param resource exported mesh resource
     * @return immutable mesh data
     */
    public static Mesh mesh(Identifier resource) {
        return MESHES.computeIfAbsent(resource, NativeModelGeometry::read);
    }

    private static Mesh read(Identifier resource) {
        String path = "/assets/" + resource.getNamespace() + "/" + resource.getPath();
        var input = NativeModelGeometry.class.getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("Missing native model " + resource);
        int width = 0, height = 0;
        boolean versioned = false;
        List<Part> parts = new ArrayList<>();
        try (var reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "format" -> {
                        if (reader.nextInt() != 1) throw new IllegalArgumentException("Unsupported model " + resource);
                        versioned = true;
                    }
                    case "texture_width" -> width = reader.nextInt();
                    case "texture_height" -> height = reader.nextInt();
                    case "parts" -> {
                        reader.beginArray();
                        while (reader.hasNext()) parts.add(part(reader));
                        reader.endArray();
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("Cannot load native model " + resource, e);
        }
        if (!versioned || width <= 0 || height <= 0) throw new IllegalArgumentException("Unsupported model " + resource);
        return new Mesh(width, height, parts.toArray(Part[]::new));
    }

    private static Part part(JsonReader reader) throws IOException {
        String name = null;
        List<String> path = new ArrayList<>();
        float[] pose = null;
        List<Quad> quads = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "name" -> name = reader.nextString();
                case "path" -> {
                    reader.beginArray();
                    while (reader.hasNext()) path.add(reader.nextString());
                    reader.endArray();
                }
                case "pose" -> pose = numbers(reader);
                case "quads" -> {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        float[][] vertices = null;
                        float[] normal = null;
                        reader.beginObject();
                        while (reader.hasNext()) {
                            switch (reader.nextName()) {
                                case "vertices" -> {
                                    var rows = new ArrayList<float[]>();
                                    reader.beginArray();
                                    while (reader.hasNext()) rows.add(numbers(reader));
                                    reader.endArray();
                                    vertices = rows.toArray(float[][]::new);
                                }
                                case "normal" -> normal = numbers(reader);
                                default -> reader.skipValue();
                            }
                        }
                        reader.endObject();
                        if (vertices == null || vertices.length != 4 || normal == null || normal.length != 3) {
                            throw new IllegalArgumentException("Native faces must be quads: " + path);
                        }
                        for (float[] vertex : vertices) if (vertex.length != 5) throw new IllegalArgumentException("Invalid native vertex: " + path);
                        quads.add(new Quad(vertices, normal));
                    }
                    reader.endArray();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (name == null || pose == null || pose.length != 6 && pose.length != 9) throw new IllegalArgumentException("Invalid native pose: " + path);
        return new Part(name, path.toArray(String[]::new), pose, quads.toArray(Quad[]::new));
    }

    private static float[] numbers(JsonReader reader) throws IOException {
        var values = new ArrayList<Float>();
        reader.beginArray();
        while (reader.hasNext()) {
            float value = (float) reader.nextDouble();
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite native model coordinate");
            values.add(value);
        }
        reader.endArray();
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    /**
     * Bake the native hierarchy with one bounded cube slot per authored quad.
     * @param resource exported mesh resource
     * @return model layer ready for the usual layer registry
     */
    public static LayerDefinition createLayer(Identifier resource) {
        var data = mesh(resource);
        var mesh = new MeshDefinition();
        Map<String, PartDefinition> parts = new HashMap<>();
        parts.put("", mesh.getRoot());
        for (var part : data.parts) {
            String path = String.join("/", part.path);
            int separator = path.lastIndexOf('/');
            String parentPath = separator < 0 ? "" : path.substring(0, separator);
            var builder = CubeListBuilder.create();
            for (var quad : part.quads) {
                float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
                float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
                for (var vertex : quad.vertices) for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], vertex[axis]);
                    max[axis] = Math.max(max[axis], vertex[axis]);
                }
                builder.addBox(min[0], min[1], min[2], max[0]-min[0], max[1]-min[1], max[2]-min[2], Set.of(Direction.UP));
            }
            var pose = part.pose;
            var transform = pose.length == 9
                    ? new PartPose(pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], pose[6], pose[7], pose[8])
                    : PartPose.offsetAndRotation(pose[0], pose[1], pose[2], pose[3], pose[4], pose[5]);
            var parent = parts.get(parentPath);
            if (parent == null || parts.containsKey(path)) throw new IllegalArgumentException("Invalid model hierarchy: " + path);
            parts.put(path, parent.addOrReplaceChild(part.name, builder, transform));
        }
        return LayerDefinition.create(mesh, data.textureWidth, data.textureHeight);
    }

    /**
     * Replace each baked slot with the exact original vertices, UVs and normal.
     * A public polygon array is supplied by Minecraft; no access widening is needed.
     * @param root baked hierarchy
     * @param resource same mesh used to create the layer
     * @return the supplied root, with its native surfaces installed
     */
    public static ModelPart apply(ModelPart root, Identifier resource) {
        Map<String, Quad[]> quads = new HashMap<>();
        int expected = 0;
        for (var part : mesh(resource).parts) {
            quads.put("/" + String.join("/", part.path), part.quads);
            expected += part.quads.length;
        }
        int[] count = {0};
        int total = expected;
        root.visit(new com.mojang.blaze3d.vertex.PoseStack(), (pose, path, index, cube) -> {
            var face = quads.get(path)[index];
            var vertices = new ModelPart.Vertex[4];
            for (int i = 0; i < 4; i++) {
                var v = face.vertices[i];
                vertices[i] = new ModelPart.Vertex(v[0], v[1], v[2], v[3], v[4]);
            }
            var n = face.normal;
            if (cube.polygons.length != 1) throw new IllegalStateException("Competing native faces at " + path);
            cube.polygons[0] = new ModelPart.Polygon(vertices, new Vector3f(n[0], n[1], n[2]));
            count[0]++;
        });
        if (count[0] != total) throw new IllegalStateException("Incomplete native model " + resource);
        return root;
    }
}
