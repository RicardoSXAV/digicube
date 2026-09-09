package com.digicube.fabric.client.model;

import com.google.gson.JsonObject;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Saved Blender curves in model coordinates. Data keeps dense subframe animation out of Java bytecode. */
public final class NativeAnimationSet {
    private record Track(ModelPart part, float[][] keys) {}
    private record Visibility(ModelPart part, float[] times, boolean[] shown) {}
    private record Clip(float length, boolean loop, Track[] tracks, Visibility[] visibility) {}
    private final Map<String, Clip> clips = new HashMap<>();
    private record BlendPoint(float value, String clip) {}
    private final Map<String, BlendPoint[]> blends = new HashMap<>();
    private final ModelPart[] membranes;

    public NativeAnimationSet(ModelPart root, Identifier resource) {
        String path = "/assets/" + resource.getNamespace() + "/" + resource.getPath();
        try (var input = NativeAnimationSet.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing native animations " + resource);
            JsonObject data = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (data.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported animations " + resource);
            Map<String, ModelPart> parts = new HashMap<>();
            data.getAsJsonObject("paths").entrySet().forEach(entry -> {
                ModelPart part = root;
                for (var name : entry.getValue().getAsJsonArray()) part = part.getChild(name.getAsString());
                parts.put(entry.getKey(), part);
            });
            membranes = new ModelPart[data.getAsJsonArray("membranes").size()];
            for (int i = 0; i < membranes.length; i++) membranes[i] = parts.get(data.getAsJsonArray("membranes").get(i).getAsString());
            for (var entry : data.getAsJsonObject("clips").entrySet()) {
                var clip = entry.getValue().getAsJsonObject();
                float length = clip.get("length").getAsFloat();
                if (!Float.isFinite(length) || length <= 0) throw new IllegalArgumentException("Invalid native duration");
                var tracks = new java.util.ArrayList<Track>();
                for (var element : clip.getAsJsonArray("tracks")) {
                    var track = element.getAsJsonObject();
                    var frames = track.getAsJsonArray("keys");
                    float[][] keys = new float[frames.size()][7];
                    if (keys.length < 2) throw new IllegalArgumentException("A native track needs two keys");
                    for (int i = 0; i < keys.length; i++) {
                        var key = frames.get(i).getAsJsonArray();
                        if (key.size() != 7) throw new IllegalArgumentException("Invalid native transform");
                        for (int j = 0; j < 7; j++) {
                            keys[i][j] = key.get(j).getAsFloat();
                            if (!Float.isFinite(keys[i][j])) throw new IllegalArgumentException("Nonfinite native curve");
                        }
                        if (i > 0 && keys[i][0] <= keys[i-1][0]) throw new IllegalArgumentException("Unordered native curve");
                    }
                    tracks.add(new Track(java.util.Objects.requireNonNull(parts.get(track.get("part").getAsString())), keys));
                }
                var visibility = new java.util.ArrayList<Visibility>();
                for (var channel : clip.getAsJsonObject("visibility").entrySet()) {
                    var keys = channel.getValue().getAsJsonArray();
                    float[] times = new float[keys.size()];
                    boolean[] shown = new boolean[keys.size()];
                    for (int i = 0; i < times.length; i++) {
                        times[i] = keys.get(i).getAsJsonArray().get(0).getAsFloat();
                        shown[i] = keys.get(i).getAsJsonArray().get(1).getAsBoolean();
                    }
                    visibility.add(new Visibility(parts.get(channel.getKey()), times, shown));
                }
                clips.put(entry.getKey(), new Clip(length, clip.get("loop").getAsBoolean(),
                        tracks.toArray(Track[]::new), visibility.toArray(Visibility[]::new)));
            }
            if (data.has("blends")) for (var entry : data.getAsJsonObject("blends").entrySet()) {
                var points = entry.getValue().getAsJsonArray();
                if (points.size() < 2) throw new IllegalArgumentException("Native blend needs two points");
                var values = new BlendPoint[points.size()];
                for (int i=0; i<values.length; i++) {
                    var point = points.get(i).getAsJsonArray();
                    values[i] = new BlendPoint(point.get(0).getAsFloat(), point.get(1).getAsString());
                    if (!Float.isFinite(values[i].value) || !clips.containsKey(values[i].clip)
                            || i>0 && values[i].value<=values[i-1].value) {
                        throw new IllegalArgumentException("Invalid native blend points");
                    }
                }
                blends.put(entry.getKey(), values);
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot load native animations " + resource, e); }
    }

    /** Model instances are shared between creatures. Reset visibility on every rendered pose. */
    public void hideMembranes() { for (ModelPart part : membranes) part.visible = false; }

    /** Interpolate authored amplitude samples, with denser points around sensitive joint bends. */
    public void blend(String name, float value, float tick, float weight) {
        if (weight <= 0) return;
        var points = blends.get(name);
        if (points == null) throw new IllegalArgumentException("Missing native blend " + name);
        int lower=0;
        while (lower+2<points.length && points[lower+1].value<=value) lower++;
        var a=points[lower];var b=points[lower+1];
        float mix=Math.clamp((value-a.value)/(b.value-a.value),0,1);
        apply(a.clip,tick,(1-mix)*weight);
        apply(b.clip,tick,mix*weight);
    }

    /** Add a weighted delta from rest. Clocks are fractional server ticks, independent of game frame rate. */
    public void apply(String name, float tick, float weight) {
        if (weight <= 0) return;
        Clip clip = clips.get(name);
        if (clip == null) throw new IllegalArgumentException("Missing native clip " + name);
        tick = Math.max(0, tick);
        tick = clip.loop ? tick % clip.length : Math.min(tick, clip.length);
        for (Track track : clip.tracks) {
            var keys = track.keys;
            int low = 0, high = keys.length - 1;
            while (low + 1 < high) {
                int middle = (low + high) >>> 1;
                if (keys[middle][0] <= tick) low = middle; else high = middle;
            }
            float blend = Math.clamp((tick - keys[low][0]) / (keys[high][0] - keys[low][0]), 0, 1);
            float[] v = new float[6];
            for (int i = 0; i < 6; i++) v[i] = (keys[low][i+1] + blend * (keys[high][i+1] - keys[low][i+1])) * weight;
            ModelPart p = track.part;
            p.x += v[0]; p.y += v[1]; p.z += v[2];
            p.xRot += v[3]; p.yRot += v[4]; p.zRot += v[5];
        }
        for (Visibility visibility : clip.visibility) {
            int index = 0;
            while (index + 1 < visibility.times.length && visibility.times[index+1] <= tick) index++;
            visibility.part.visible = visibility.shown[index];
        }
    }
}
