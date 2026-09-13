package com.digicube.fabric.client.model;

import com.google.gson.stream.JsonReader;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AnimationState;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Saved Blender curves in model coordinates. Data keeps animation out of Java bytecode.
 *
 * <p>Format 1: {@code paths} names each part by its child chain from the root, {@code clips}
 * hold tracks of keys. A key row is {@code [tick, x, y, z, xRot, yRot, zRot]} with an optional
 * {@code [xScale, yScale, zScale]} tail, or {@code [tick, a, b, c]} when the track names a
 * {@code channel} of {@code position}, {@code rotation} or {@code scale}. Keys interpolate
 * linearly unless the track sets {@code "interpolation":"catmullrom"}, which follows
 * Minecraft's own keyframe spline. The file is read as a stream, so a large clip set never
 * becomes a tree of boxed numbers.
 */
public final class NativeAnimationSet {
    /** Offset marker for seven or ten wide rows that drive every field of a part. */
    private static final int FULL = -1;
    private record Track(ModelPart part, float[][] keys, int offset, boolean catmullrom) {}
    private record Visibility(ModelPart part, float[] times, boolean[] shown) {}
    private record Clip(float length, boolean loop, Track[] tracks, Visibility[] visibility) {}
    private record BlendPoint(float value, String clip) {}
    private record RawTrack(String part, float[][] keys, int offset, boolean catmullrom) {}
    private record RawVisibility(String part, float[] times, boolean[] shown) {}
    private record RawClip(float length, boolean loop, List<RawTrack> tracks, List<RawVisibility> visibility) {}
    private final Map<String, Clip> clips = new HashMap<>();
    private final Map<String, BlendPoint[]> blends = new HashMap<>();
    private final ModelPart[] membranes;

    public NativeAnimationSet(ModelPart root, Identifier resource) {
        this(root, open(resource), resource.toString());
    }

    private static Reader open(Identifier resource) {
        String path = "/assets/" + resource.getNamespace() + "/" + resource.getPath();
        var input = NativeAnimationSet.class.getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("Missing native animations " + resource);
        return new InputStreamReader(input, StandardCharsets.UTF_8);
    }

    /**
     * Parse one animation document. The resource constructor and offline verification share it.
     * @param root baked hierarchy the paths are resolved against
     * @param source JSON text, closed on return
     * @param label name used in error messages
     */
    public NativeAnimationSet(ModelPart root, Reader source, String label) {
        Map<String, String[]> paths = new HashMap<>();
        List<String> membraneNames = new ArrayList<>();
        Map<String, RawClip> raw = new HashMap<>();
        Map<String, BlendPoint[]> rawBlends = new HashMap<>();
        boolean versioned = false;
        try (var reader = new JsonReader(source)) {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "format" -> {
                        if (reader.nextInt() != 1) throw new IllegalArgumentException("Unsupported animations " + label);
                        versioned = true;
                    }
                    case "paths" -> {
                        reader.beginObject();
                        while (reader.hasNext()) paths.put(reader.nextName(), strings(reader));
                        reader.endObject();
                    }
                    case "membranes" -> membraneNames.addAll(List.of(strings(reader)));
                    case "clips" -> {
                        reader.beginObject();
                        while (reader.hasNext()) raw.put(reader.nextName(), clip(reader));
                        reader.endObject();
                    }
                    case "blends" -> {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String name = reader.nextName();
                            var points = new ArrayList<BlendPoint>();
                            reader.beginArray();
                            while (reader.hasNext()) {
                                reader.beginArray();
                                points.add(new BlendPoint(finite(reader), reader.nextString()));
                                reader.endArray();
                            }
                            reader.endArray();
                            rawBlends.put(name, points.toArray(BlendPoint[]::new));
                        }
                        reader.endObject();
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("Cannot load native animations " + label, e);
        }
        if (!versioned) throw new IllegalArgumentException("Unsupported animations " + label);
        Map<String, ModelPart> parts = new HashMap<>();
        paths.forEach((name, chain) -> {
            ModelPart part = root;
            for (String child : chain) part = part.getChild(child);
            parts.put(name, part);
        });
        membranes = new ModelPart[membraneNames.size()];
        for (int i = 0; i < membranes.length; i++) membranes[i] = Objects.requireNonNull(parts.get(membraneNames.get(i)));
        raw.forEach((name, clip) -> {
            var tracks = new Track[clip.tracks.size()];
            for (int i = 0; i < tracks.length; i++) {
                var t = clip.tracks.get(i);
                tracks[i] = new Track(Objects.requireNonNull(parts.get(t.part), "Unknown native part " + t.part), t.keys, t.offset, t.catmullrom);
            }
            var visibility = new Visibility[clip.visibility.size()];
            for (int i = 0; i < visibility.length; i++) {
                var v = clip.visibility.get(i);
                visibility[i] = new Visibility(Objects.requireNonNull(parts.get(v.part), "Unknown native part " + v.part), v.times, v.shown);
            }
            clips.put(name, new Clip(clip.length, clip.loop, tracks, visibility));
        });
        rawBlends.forEach((name, points) -> {
            if (points.length < 2) throw new IllegalArgumentException("Native blend needs two points");
            for (int i = 0; i < points.length; i++) {
                if (!clips.containsKey(points[i].clip) || i > 0 && points[i].value <= points[i - 1].value) {
                    throw new IllegalArgumentException("Invalid native blend points");
                }
            }
            blends.put(name, points);
        });
    }

    private static String[] strings(JsonReader reader) throws IOException {
        var values = new ArrayList<String>();
        reader.beginArray();
        while (reader.hasNext()) values.add(reader.nextString());
        reader.endArray();
        return values.toArray(String[]::new);
    }

    private static float finite(JsonReader reader) throws IOException {
        float value = (float) reader.nextDouble();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite native curve");
        return value;
    }

    private static RawClip clip(JsonReader reader) throws IOException {
        float length = Float.NaN;
        boolean loop = false;
        var tracks = new ArrayList<RawTrack>();
        var visibility = new ArrayList<RawVisibility>();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "length" -> length = (float) reader.nextDouble();
                case "loop" -> loop = reader.nextBoolean();
                case "tracks" -> {
                    reader.beginArray();
                    while (reader.hasNext()) tracks.add(track(reader));
                    reader.endArray();
                }
                case "visibility" -> {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String part = reader.nextName();
                        var times = new ArrayList<Float>();
                        var shown = new ArrayList<Boolean>();
                        reader.beginArray();
                        while (reader.hasNext()) {
                            reader.beginArray();
                            times.add(finite(reader));
                            shown.add(reader.nextBoolean());
                            reader.endArray();
                        }
                        reader.endArray();
                        float[] t = new float[times.size()];
                        boolean[] s = new boolean[t.length];
                        for (int i = 0; i < t.length; i++) { t[i] = times.get(i); s[i] = shown.get(i); }
                        visibility.add(new RawVisibility(part, t, s));
                    }
                    reader.endObject();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (!Float.isFinite(length) || length <= 0) throw new IllegalArgumentException("Invalid native duration");
        return new RawClip(length, loop, tracks, visibility);
    }

    private static RawTrack track(JsonReader reader) throws IOException {
        String part = null;
        int offset = FULL;
        boolean catmullrom = false;
        List<float[]> rows = new ArrayList<>();
        int channels = 0;
        float[] buffer = new float[10];
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "part" -> part = reader.nextString();
                case "channel" -> offset = switch (reader.nextString()) {
                    case "position" -> 0;
                    case "rotation" -> 3;
                    case "scale" -> 6;
                    default -> throw new IllegalArgumentException("Invalid native channel");
                };
                case "interpolation" -> catmullrom = switch (reader.nextString()) {
                    case "linear" -> false;
                    case "catmullrom" -> true;
                    default -> throw new IllegalArgumentException("Invalid native interpolation");
                };
                case "keys" -> {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.beginArray();
                        int width = 0;
                        while (reader.hasNext()) {
                            if (width == buffer.length) throw new IllegalArgumentException("Invalid native transform width");
                            buffer[width++] = finite(reader);
                        }
                        reader.endArray();
                        if (rows.isEmpty()) channels = width;
                        else if (width != channels) throw new IllegalArgumentException("Invalid native transform");
                        float[] row = new float[width];
                        System.arraycopy(buffer, 0, row, 0, width);
                        if (!rows.isEmpty() && row[0] <= rows.getLast()[0]) throw new IllegalArgumentException("Unordered native curve");
                        rows.add(row);
                    }
                    reader.endArray();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (part == null) throw new IllegalArgumentException("A native track needs a part");
        if (rows.size() < 2) throw new IllegalArgumentException("A native track needs two keys");
        if (offset == FULL ? channels != 7 && channels != 10 : channels != 4) {
            throw new IllegalArgumentException("Invalid native transform width");
        }
        return new RawTrack(part, rows.toArray(float[][]::new), offset, catmullrom);
    }

    /** Model instances are shared between creatures. Reset visibility on every rendered pose. */
    public void hideMembranes() { for (ModelPart part : membranes) part.visible = false; }

    /** @return whether a clip of this name was saved */
    public boolean has(String name) { return clips.containsKey(name); }

    /** @return every saved clip name */
    public java.util.Set<String> clipNames() { return java.util.Collections.unmodifiableSet(clips.keySet()); }

    /** @return every saved blend name */
    public java.util.Set<String> blendNames() { return java.util.Collections.unmodifiableSet(blends.keySet()); }

    /** @return a clip's authored length in ticks */
    public float length(String name) {
        Clip clip = clips.get(name);
        if (clip == null) throw new IllegalArgumentException("Missing native clip " + name);
        return clip.length;
    }

    /** Play a clip on an entity's animation clock, exactly as a baked keyframe animation would. */
    public void applyStarted(String name, AnimationState state, float ageInTicks) {
        if (state.isStarted()) apply(name, state.getTimeInMillis(ageInTicks) / 50.0F, 1.0F);
    }

    /** Drive a looping clip from the limb swing clock, as a baked keyframe animation's applyWalk. */
    public void applyWalk(String name, float walkPos, float walkSpeed, float timeMultiplier, float scaleMultiplier) {
        apply(name, (long) (walkPos * 50.0F * timeMultiplier) / 50.0F, Math.min(walkSpeed * scaleMultiplier, 1.0F));
    }

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
            float[] a = keys[low], b = keys[high];
            float blend = Math.clamp((tick - a[0]) / (b[0] - a[0]), 0, 1);
            ModelPart p = track.part;
            if (track.catmullrom) {
                float[] before = keys[Math.max(0, low - 1)], after = keys[Math.min(keys.length - 1, high + 1)];
                add(p, track.offset, Mth.catmullrom(blend, before[1], a[1], b[1], after[1]) * weight,
                        Mth.catmullrom(blend, before[2], a[2], b[2], after[2]) * weight,
                        Mth.catmullrom(blend, before[3], a[3], b[3], after[3]) * weight);
            } else if (track.offset == FULL) {
                p.x += (a[1] + blend * (b[1] - a[1])) * weight;
                p.y += (a[2] + blend * (b[2] - a[2])) * weight;
                p.z += (a[3] + blend * (b[3] - a[3])) * weight;
                p.xRot += (a[4] + blend * (b[4] - a[4])) * weight;
                p.yRot += (a[5] + blend * (b[5] - a[5])) * weight;
                p.zRot += (a[6] + blend * (b[6] - a[6])) * weight;
                if (a.length == 10) {
                    p.xScale += (a[7] + blend * (b[7] - a[7])) * weight;
                    p.yScale += (a[8] + blend * (b[8] - a[8])) * weight;
                    p.zScale += (a[9] + blend * (b[9] - a[9])) * weight;
                }
            } else {
                add(p, track.offset, (a[1] + blend * (b[1] - a[1])) * weight,
                        (a[2] + blend * (b[2] - a[2])) * weight, (a[3] + blend * (b[3] - a[3])) * weight);
            }
        }
        for (Visibility visibility : clip.visibility) {
            int index = 0;
            while (index + 1 < visibility.times.length && visibility.times[index+1] <= tick) index++;
            visibility.part.visible = visibility.shown[index];
        }
    }

    private static void add(ModelPart p, int offset, float x, float y, float z) {
        switch (offset) {
            case 0 -> { p.x += x; p.y += y; p.z += z; }
            case 3 -> { p.xRot += x; p.yRot += y; p.zRot += z; }
            default -> { p.xScale += x; p.yScale += y; p.zScale += z; }
        }
    }
}
