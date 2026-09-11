package com.digicube.digimon;

import com.google.gson.JsonArray;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Finite-length Blender wrap lattice, shared by server collision and client pose selection. */
public final class ConstrictionMotion {
    public static final int CAPTURE_TICK = 40;
    public static final int RELEASE_TICK = 80;
    public static final int DURATION = 120;
    public static final int INTERVAL = 10;
    public static final int RESISTANCE_TICKS = 180;
    public static final int PREPARE_TICKS = 40;
    public static final float ALIGN_DEGREES_PER_TICK = 20;
    public static final double ESCAPE_DISTANCE = .45;
    public static final int APPROACH_TICKS = 80;
    public static final int APPROACH_RETRY_TICKS = 40;
    public static final double MAX_APPROACH_DRIFT = 2.5;
    public static final double MAX_TARGET_STEP = .25;
    private record Variant(String clip, double[][] roots, double[][][] boxes) {}
    public record Blend(String clip, float weight) {}
    public record Fit(float radius, float pitch) {}
    private final double[] radii, pitches;
    private final Variant[][] grid;
    private final double minimumHeight, maximumHeight, padding;

    public ConstrictionMotion(Identifier id) {
        String path = "/data/" + id.getNamespace() + "/constriction_motion/" + id.getPath() + ".json";
        try (var stream = ConstrictionMotion.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing wrap motion " + path);
            var data = GsonHelper.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (data.get("format").getAsInt() != 1) throw new IllegalArgumentException("Invalid wrap format");
            radii = numbers(data.getAsJsonArray("radii")); pitches = numbers(data.getAsJsonArray("pitches"));
            minimumHeight = data.get("minimum_height_pixels").getAsDouble(); padding = data.get("padding_pixels").getAsDouble();
            maximumHeight = data.get("maximum_height_pixels").getAsDouble();
            grid = new Variant[pitches.length][radii.length];
            for (int p=0;p<pitches.length;p++) for (int r=0;r<radii.length;r++) {
                var v = data.getAsJsonArray("grid").get(p).getAsJsonArray().get(r).getAsJsonObject();
                var roots = v.getAsJsonArray("roots"); var boxes = v.getAsJsonArray("boxes");
                double[][] rs = new double[roots.size()][];
                for (int i=0;i<rs.length;i++) rs[i]=numbers(roots.get(i).getAsJsonArray());
                if (rs.length != DURATION*4+1) throw new IllegalArgumentException("Mismatched wrap duration");
                double[][][] bs = new double[boxes.size()][][];
                for (int i=0;i<bs.length;i++) {
                    var row = boxes.get(i).getAsJsonArray().get(2).getAsJsonArray(); bs[i]=new double[row.size()][];
                    for (int j=0;j<row.size();j++) bs[i][j]=numbers(row.get(j).getAsJsonArray());
                }
                grid[p][r]=new Variant(v.get("clip").getAsString(),rs,bs);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load wrap motion",e); }
    }

    /** Reject bodies which cannot fit inside the creature's original fixed length. */
    public Fit fit(AABB target, float scale) {
        if (!(scale>0) || !Float.isFinite(scale)) return null;
        double radius = Math.hypot(target.getXsize()/2,target.getZsize()/2)*16/scale+padding;
        double height = target.getYsize()*16/scale;
        if (!Double.isFinite(radius) || !Double.isFinite(height) || height<minimumHeight || height>maximumHeight
                || radius<radii[0] || radius>radii[radii.length-1]) return null;
        return new Fit((float)radius,(float)Math.clamp(height*.85,pitches[0],pitches[pitches.length-1]));
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
    public List<AABB> sweptBody(Fit fit, double distance, float scale, Vec3 feet, float yaw) {
        var result = new ArrayList<AABB>();
        int count=grid[0][0].boxes.length, parts=grid[0][0].boxes[0].length;
        for(int i=0;i<count;i++) for(int n=0;n<parts;n++) {
            double[] box=new double[6];final int sample=i,part=n;
            corners(fit,(v,w)-> { for(int j=0;j<6;j++)box[j]+=w*v.boxes[sample][part][j]; });
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
            result.add(new AABB(lo.x-.06,Math.max(feet.y+.015,lo.y-.06),lo.z-.06,hi.x+.06,hi.y+.06,hi.z+.06));
        }
        return result;
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
    private static double[] numbers(JsonArray a) {
        double[] values=new double[a.size()];
        for(int i=0;i<values.length;i++) {values[i]=a.get(i).getAsDouble();if(!Double.isFinite(values[i]))throw new IllegalArgumentException("Nonfinite wrap coordinate");}
        return values;
    }
}
