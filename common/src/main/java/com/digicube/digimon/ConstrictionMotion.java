package com.digicube.digimon;

import com.google.gson.stream.JsonReader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finite-length Blender wrap lattice, shared by server collision and client pose selection. */
public final class ConstrictionMotion {
    public static final int CAPTURE_TICK = 40;
    public static final int RELEASE_TICK = 80;
    public static final int DURATION = 120;
    public static final int INTERVAL = 10;
    /** Expires with the caster's own cooldown, so a ready wrap never waits on its last victim's resistance. */
    public static final int RESISTANCE_TICKS = 160;
    /** Squeezed prey needs this long to get its breath back before it can start an attack: the caster's uncoiling. */
    public static final int WINDED_TICKS = DURATION - RELEASE_TICK;
    /** Frozen prey stays frozen for one more second after the hold releases. */
    public static final int FROZEN_TAIL_TICKS = 20;
    /** How long a freezing caster walks toward wrap reach before it freezes from where it stands. */
    public static final int CLOSE_IN_TICKS = 60;
    /** Frozen or Cold prey is approached at a hurry; the opening runs out before the prey does. */
    public static final double FROZEN_PURSUIT_SPEED = 1.3;
    public static final int PREPARE_TICKS = 40;
    public static final float ALIGN_DEGREES_PER_TICK = 20;
    public static final double ESCAPE_DISTANCE = .45;
    public static final int APPROACH_TICKS = 80;
    public static final int APPROACH_RETRY_TICKS = 40;
    /** Frozen prey cannot walk off and Cold prey barely can, so a refused stance is retried almost at once. */
    public static final int FROZEN_RETRY_TICKS = 10;
    /** The coil's line follows prey that walks, runs or lunges; only a dash or a teleport outruns it. */
    public static final double MAX_APPROACH_DRIFT = 4.0;
    public static final double MAX_TARGET_STEP = .6;
    /** A coiling body shrugs off the knockback of ordinary hits; an impulse this strong is a push and breaks the wrap. */
    public static final double BREAKING_PUSH = 1.0;
    /** Stances the planner rehearses per tick rarely differ; keep the most recent sweeps. */
    private static final int SWEPT_CACHE = 32;
    private record Variant(String clip, double[][] roots, double[][][] boxes) {}
    public record Blend(String clip, float weight) {}
    public record Fit(float radius, float pitch) {}
    /**
     * Conservative cuboid bounds along one complete cast.
     * @param boxes every part at every sample, sample-major, as native world boxes
     * @param samples the union of each sample's part boxes, in sample order
     * @param whole the union of every box
     * @param parts number of part boxes per sample
     */
    public record SweptBody(List<AABB> boxes, List<AABB> samples, AABB whole, int parts) {
        /** @return the part boxes of one sample */
        public List<AABB> sample(int index) { return boxes.subList(index * parts, (index + 1) * parts); }
    }
    private record SweptKey(float radius, float pitch, double distance, float scale, double x, double y, double z, float yaw) {}
    private final double[] radii, pitches;
    private final Variant[][] grid;
    private final double minimumHeight, maximumHeight, padding;
    private final Map<SweptKey, SweptBody> swept = new LinkedHashMap<>(SWEPT_CACHE, .75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<SweptKey, SweptBody> eldest) { return size() > SWEPT_CACHE; }
    };

    public ConstrictionMotion(Identifier id) {
        this(open(id), id.toString());
    }

    private static Reader open(Identifier id) {
        String path = "/data/" + id.getNamespace() + "/constriction_motion/" + id.getPath() + ".json";
        var stream = ConstrictionMotion.class.getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing wrap motion " + path);
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }

    /**
     * Parse one lattice document as a stream; the bundled resource and offline checks share it.
     * @param source JSON text, closed on return
     * @param label name used in error messages
     */
    public ConstrictionMotion(Reader source, String label) {
        double[] radii = null, pitches = null;
        double minimumHeight = Double.NaN, maximumHeight = Double.NaN, padding = Double.NaN;
        boolean versioned = false;
        List<List<Variant>> rows = new ArrayList<>();
        try (var reader = new JsonReader(source)) {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "format" -> {
                        if (reader.nextInt() != 1) throw new IllegalArgumentException("Invalid wrap format");
                        versioned = true;
                    }
                    case "radii" -> radii = numbers(reader);
                    case "pitches" -> pitches = numbers(reader);
                    case "minimum_height_pixels" -> minimumHeight = reader.nextDouble();
                    case "maximum_height_pixels" -> maximumHeight = reader.nextDouble();
                    case "padding_pixels" -> padding = reader.nextDouble();
                    case "grid" -> {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            var row = new ArrayList<Variant>();
                            reader.beginArray();
                            while (reader.hasNext()) row.add(variant(reader));
                            reader.endArray();
                            rows.add(row);
                        }
                        reader.endArray();
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("Cannot load wrap motion " + label, e);
        }
        if (!versioned || radii == null || pitches == null || !Double.isFinite(minimumHeight)
                || !Double.isFinite(maximumHeight) || !Double.isFinite(padding) || rows.size() != pitches.length) {
            throw new IllegalArgumentException("Invalid wrap format");
        }
        this.radii = radii; this.pitches = pitches;
        this.minimumHeight = minimumHeight; this.maximumHeight = maximumHeight; this.padding = padding;
        grid = new Variant[pitches.length][radii.length];
        for (int p = 0; p < pitches.length; p++) {
            if (rows.get(p).size() != radii.length) throw new IllegalArgumentException("Invalid wrap grid");
            for (int r = 0; r < radii.length; r++) grid[p][r] = rows.get(p).get(r);
        }
    }

    private static Variant variant(JsonReader reader) throws IOException {
        String clip = null;
        List<double[]> roots = new ArrayList<>();
        List<double[][]> boxes = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "clip" -> clip = reader.nextString();
                case "roots" -> {
                    reader.beginArray();
                    while (reader.hasNext()) roots.add(numbers(reader));
                    reader.endArray();
                }
                case "boxes" -> {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        // Each entry is [tick, env, [[minX, minY, minZ, maxX, maxY, maxZ] per part]].
                        reader.beginArray();
                        reader.skipValue();
                        reader.skipValue();
                        var parts = new ArrayList<double[]>();
                        reader.beginArray();
                        while (reader.hasNext()) parts.add(numbers(reader));
                        reader.endArray();
                        while (reader.hasNext()) reader.skipValue();
                        reader.endArray();
                        boxes.add(parts.toArray(double[][]::new));
                    }
                    reader.endArray();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (clip == null) throw new IllegalArgumentException("A wrap variant needs a clip");
        if (roots.size() != DURATION * 4 + 1) throw new IllegalArgumentException("Mismatched wrap duration");
        return new Variant(clip, roots.toArray(double[][]::new), boxes.toArray(double[][][]::new));
    }

    /**
     * Bulky prey is coiled at the lattice's largest size. The limits are generous on purpose (Ricardo,
     * 2026-09-18: refuse only prey it would look ridiculous to wrap): a Champion such as Gesomon, 2.25
     * wide and 3.7 tall, is held around its lower body; a body over about 3.2 blocks across or 4.9
     * tall at Seadramon's scale is refused.
     */
    public static final double OVERSIZE_RADIUS = 1.6, OVERSIZE_HEIGHT = 2.35;

    /**
     * Reject bodies which cannot fit inside the creature's original fixed length. Prey somewhat
     * larger than the lattice still gets its largest coil, which clears a boxy body's faces.
     */
    public Fit fit(AABB target, float scale) {
        if (!(scale>0) || !Float.isFinite(scale)) return null;
        double radius = Math.hypot(target.getXsize()/2,target.getZsize()/2)*16/scale+padding;
        double height = target.getYsize()*16/scale;
        double maxRadius = radii[radii.length-1];
        if (!Double.isFinite(radius) || !Double.isFinite(height) || height<minimumHeight || height>maximumHeight*OVERSIZE_HEIGHT
                || radius<radii[0] || radius>maxRadius*OVERSIZE_RADIUS) return null;
        return new Fit((float)Math.min(radius,maxRadius),(float)Math.clamp(height*.85,pitches[0],pitches[pitches.length-1]));
    }

    public List<Blend> blends(Fit fit) {
        var result = new ArrayList<Blend>(4);
        corners(fit,(v,w)->{ if (w>0) result.add(new Blend(v.clip,(float)w)); });
        return List.copyOf(result);
    }

    /** Authored horizontal head-root displacement, in world blocks at yaw zero. */
    public Vec3 root(Fit fit, double tick, double targetDistance, float scale) {
        double[] out = new double[4];
        corners(fit,(v,w)-> {
            double k=Math.clamp(tick*4,0,v.roots.length-1); int a=(int)k,b=Math.min(a+1,v.roots.length-1);
            for(int j=0;j<4;j++)out[j]+=w*(v.roots[a][j+1]+(k-a)*(v.roots[b][j+1]-v.roots[a][j+1]));
        });
        return new Vec3(out[0]*scale,0,out[2]*scale+targetDistance*out[3]);
    }

    /** Conservative native cuboid bounds along the complete approach, hold and withdrawal. */
    public SweptBody sweptBody(Fit fit, double distance, float scale, Vec3 feet, float yaw) {
        var key = new SweptKey(fit.radius, fit.pitch, distance, scale, feet.x, feet.y, feet.z, yaw);
        synchronized (swept) {
            var cached = swept.get(key);
            if (cached != null) return cached;
        }
        var body = sweep(fit, distance, scale, feet, yaw);
        synchronized (swept) { swept.put(key, body); }
        return body;
    }

    private SweptBody sweep(Fit fit, double distance, float scale, Vec3 feet, float yaw) {
        var result = new ArrayList<AABB>();
        var samples = new ArrayList<AABB>();
        AABB whole = null;
        int count=grid[0][0].boxes.length, parts=grid[0][0].boxes[0].length;
        for(int i=0;i<count;i++) {
            AABB sample = null;
            for(int n=0;n<parts;n++) {
                double[] box=new double[6];final int index=i,part=n;
                corners(fit,(v,w)-> { for(int j=0;j<6;j++)box[j]+=w*v.boxes[index][part][j]; });
                double shift=distance*grid[0][0].roots[i*16][4];
                Vec3 lo=null,hi=null;
                for(int corner=0;corner<8;corner++) {
                    var p=new Vec3(box[(corner&1)==0?0:3]*scale,
                            box[(corner&2)==0?1:4]*scale,box[(corner&4)==0?2:5]*scale+shift)
                            .yRot((float)Math.toRadians(-yaw)).add(feet);
                    lo=lo==null?p:new Vec3(Math.min(lo.x,p.x),Math.min(lo.y,p.y),Math.min(lo.z,p.z));
                    hi=hi==null?p:new Vec3(Math.max(hi.x,p.x),Math.max(hi.y,p.y),Math.max(hi.z,p.z));
                }
                // Leave the authored floor contacts on the supporting surface.
                var bounds = new AABB(lo.x-.06,Math.max(feet.y+.015,lo.y-.06),lo.z-.06,hi.x+.06,hi.y+.06,hi.z+.06);
                result.add(bounds);
                sample = sample == null ? bounds : sample.minmax(bounds);
            }
            samples.add(sample);
            whole = whole == null ? sample : whole.minmax(sample);
        }
        return new SweptBody(List.copyOf(result), List.copyOf(samples), whole, parts);
    }

    private interface Consumer { void accept(Variant variant,double weight); }
    private void corners(Fit fit,Consumer callback) {
        int r=lower(radii,fit.radius),p=lower(pitches,fit.pitch);
        double x=Math.clamp((fit.radius-radii[r])/(radii[r+1]-radii[r]),0,1);
        double y=Math.clamp((fit.pitch-pitches[p])/(pitches[p+1]-pitches[p]),0,1);
        callback.accept(grid[p][r],(1-x)*(1-y));callback.accept(grid[p][r+1],x*(1-y));
        callback.accept(grid[p+1][r],(1-x)*y);callback.accept(grid[p+1][r+1],x*y);
    }
    private static int lower(double[] a,double v) {int i=0;while(i+2<a.length&&a[i+1]<=v)i++;return i;}
    private static double[] numbers(JsonReader reader) throws IOException {
        var values = new ArrayList<Double>();
        reader.beginArray();
        while (reader.hasNext()) {
            double value = reader.nextDouble();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite wrap coordinate");
            values.add(value);
        }
        reader.endArray();
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }
}
