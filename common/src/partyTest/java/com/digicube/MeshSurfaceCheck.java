package com.digicube;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Z-fighting check for a native mesh: two quads that face the same way, lie on one plane and
 * overlap flicker in game, because the depth buffer cannot order them. Checked in the rest pose,
 * which is the {@code idle} clip at tick 0 (tracks and visibility; a hidden part hides its
 * children) or the bare mesh without one. Same rules and numbers as the harness tool
 * {@code ../harness/v2/tools/coplanar_poses.py}, which also samples the other clips and is what a
 * model is fixed with.
 */
final class MeshSurfaceCheck {
    private MeshSurfaceCheck() {}

    /** Model pixels between two planes that still count as one, and the smallest overlap that shows. */
    private static final double TOLERANCE = .05, PARALLEL = 1 - 1e-3, MIN_OVERLAP = .25;

    private record Quad(String part, double[][] v, double[] normal, double offset, double[] centre, double radius) {}

    /** Part pairs ("a ~ b") with same-facing coplanar overlapping quads in the rest pose. */
    static Set<String> restPairs(JsonObject mesh, JsonObject animation) {
        Map<String, double[]> delta = new HashMap<>();
        Set<String> hidden = new HashSet<>();
        JsonObject idle = animation == null || !animation.getAsJsonObject("clips").has("idle") ? null
                : animation.getAsJsonObject("clips").getAsJsonObject("idle");
        if (idle != null) {
            for (var element : idle.getAsJsonArray("tracks")) {
                JsonObject track = element.getAsJsonObject();
                JsonArray first = track.getAsJsonArray("keys").get(0).getAsJsonArray();
                int start = !track.has("channel") ? 0 : switch (track.get("channel").getAsString()) {
                    case "rotation" -> 3;
                    case "scale" -> 6;
                    default -> 0;
                };
                double[] d = delta.computeIfAbsent(track.get("part").getAsString(), k -> new double[9]);
                for (int i = 1; i < first.size(); i++) d[start + i - 1] += first.get(i).getAsDouble();
            }
            if (idle.has("visibility")) for (var entry : idle.getAsJsonObject("visibility").entrySet()) {
                boolean visible = true;
                for (var key : entry.getValue().getAsJsonArray()) {
                    if (key.getAsJsonArray().get(0).getAsDouble() <= 0) visible = key.getAsJsonArray().get(1).getAsBoolean();
                }
                if (!visible) hidden.add(entry.getKey());
            }
        }
        Map<String, JsonObject> parts = new HashMap<>();
        for (var element : mesh.getAsJsonArray("parts")) parts.put(element.getAsJsonObject().get("name").getAsString(), element.getAsJsonObject());
        Map<String, double[][]> world = new HashMap<>();
        List<Quad> quads = new ArrayList<>();
        for (var element : mesh.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            boolean shown = true;
            for (var name : part.getAsJsonArray("path")) shown &= !hidden.contains(name.getAsString());
            if (!shown) continue;
            double[][] m = world(part, parts, delta, world);
            for (var q : part.getAsJsonArray("quads")) {
                JsonArray vertices = q.getAsJsonObject().getAsJsonArray("vertices");
                double[][] v = new double[vertices.size()][];
                for (int i = 0; i < v.length; i++) {
                    JsonArray p = vertices.get(i).getAsJsonArray();
                    v[i] = apply(m, p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble());
                }
                double[] n = cross(sub(v[1], v[0]), sub(v[2], v[0]));
                double length = Math.sqrt(dot(n, n));
                if (length <= 1e-6) continue;
                n = new double[]{n[0] / length, n[1] / length, n[2] / length};
                double[] centre = new double[3];
                for (double[] p : v) for (int i = 0; i < 3; i++) centre[i] += p[i] / v.length;
                double radius = 0;
                for (double[] p : v) radius = Math.max(radius, Math.sqrt(dot(sub(p, centre), sub(p, centre))));
                quads.add(new Quad(part.get("name").getAsString(), v, n, dot(n, v[0]), centre, radius));
            }
        }
        Set<String> pairs = new TreeSet<>();
        for (int i = 0; i < quads.size(); i++) for (int j = i + 1; j < quads.size(); j++) {
            Quad a = quads.get(i), b = quads.get(j);
            if (dot(a.normal, b.normal) <= PARALLEL || Math.abs(a.offset - b.offset) >= TOLERANCE) continue;
            // Every corner of both quads on the shared plane: slightly tilted faces intersect along a line, they do not z-fight.
            if (offPlane(b.v, a) >= TOLERANCE || offPlane(a.v, b) >= TOLERANCE) continue;
            double[] between = sub(a.centre, b.centre);
            if (Math.sqrt(dot(between, between)) >= a.radius + b.radius) continue;
            double[] u = cross(a.normal, Math.abs(a.normal[0]) < .9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0});
            double ul = Math.sqrt(dot(u, u));
            u = new double[]{u[0] / ul, u[1] / ul, u[2] / ul};
            double[] w = cross(a.normal, u);
            if (area(clip(project(a.v, u, w), project(b.v, u, w))) > MIN_OVERLAP) {
                pairs.add(a.part.compareTo(b.part) <= 0 ? a.part + " ~ " + b.part : b.part + " ~ " + a.part);
            }
        }
        return pairs;
    }

    private static double offPlane(double[][] vertices, Quad plane) {
        double worst = 0;
        for (double[] p : vertices) worst = Math.max(worst, Math.abs(dot(p, plane.normal) - plane.offset));
        return worst;
    }

    private static double[][] world(JsonObject part, Map<String, JsonObject> parts, Map<String, double[]> delta, Map<String, double[][]> cache) {
        String name = part.get("name").getAsString();
        double[][] cached = cache.get(name);
        if (cached != null) return cached;
        JsonArray pose = part.getAsJsonArray("pose");
        double[] p = {0, 0, 0, 0, 0, 0, 1, 1, 1};
        for (int i = 0; i < Math.min(9, pose.size()); i++) p[i] = pose.get(i).getAsDouble();
        double[] d = delta.get(name);
        if (d != null) for (int i = 0; i < 9; i++) p[i] += d[i];
        // ModelPart order: translate, then rotate Z, Y, X, then scale.
        double cx = Math.cos(p[3]), sx = Math.sin(p[3]), cy = Math.cos(p[4]), sy = Math.sin(p[4]), cz = Math.cos(p[5]), sz = Math.sin(p[5]);
        double[][] rx = {{1, 0, 0}, {0, cx, -sx}, {0, sx, cx}}, ry = {{cy, 0, sy}, {0, 1, 0}, {-sy, 0, cy}}, rz = {{cz, -sz, 0}, {sz, cz, 0}, {0, 0, 1}};
        double[][] r = multiply(multiply(rz, ry), rx);
        double[][] local = new double[3][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) local[i][j] = r[i][j] * p[6 + j];
            local[i][3] = p[i];
        }
        JsonArray path = part.getAsJsonArray("path");
        double[][] result = local;
        if (path.size() > 1) {
            double[][] parent = world(parts.get(path.get(path.size() - 2).getAsString()), parts, delta, cache);
            result = new double[3][4];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 4; j++) {
                    for (int k = 0; k < 3; k++) result[i][j] += parent[i][k] * local[k][j];
                }
                result[i][3] += parent[i][3];
            }
        }
        cache.put(name, result);
        return result;
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) out[i][j] += a[i][k] * b[k][j];
        return out;
    }

    private static double[] apply(double[][] m, double x, double y, double z) {
        return new double[]{m[0][0] * x + m[0][1] * y + m[0][2] * z + m[0][3], m[1][0] * x + m[1][1] * y + m[1][2] * z + m[1][3],
                m[2][0] * x + m[2][1] * y + m[2][2] * z + m[2][3]};
    }

    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static List<double[]> project(double[][] v, double[] u, double[] w) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : v) out.add(new double[]{dot(p, u), dot(p, w)});
        return out;
    }

    private static double signedArea(List<double[]> poly) {
        double sum = 0;
        for (int i = 0; i < poly.size(); i++) {
            double[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum / 2;
    }

    private static double area(List<double[]> poly) { return poly.isEmpty() ? 0 : Math.abs(signedArea(poly)); }

    /** Sutherland-Hodgman against a convex clipper. */
    private static List<double[]> clip(List<double[]> subject, List<double[]> clipper) {
        if (signedArea(clipper) < 0) clipper = clipper.reversed();
        List<double[]> out = subject;
        for (int i = 0; i < clipper.size() && !out.isEmpty(); i++) {
            double[] a = clipper.get(i), b = clipper.get((i + 1) % clipper.size());
            List<double[]> in = out;
            out = new ArrayList<>();
            double[] s = in.getLast();
            for (double[] e : in) {
                if (inside(e, a, b)) {
                    if (!inside(s, a, b)) out.add(intersect(s, e, a, b));
                    out.add(e);
                } else if (inside(s, a, b)) out.add(intersect(s, e, a, b));
                s = e;
            }
        }
        return out;
    }

    private static boolean inside(double[] p, double[] a, double[] b) {
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= -1e-9;
    }

    private static double[] intersect(double[] p, double[] q, double[] a, double[] b) {
        double d1x = p[0] - q[0], d1y = p[1] - q[1], d2x = a[0] - b[0], d2y = a[1] - b[1], den = d1x * d2y - d1y * d2x;
        if (Math.abs(den) < 1e-12) return p;
        double t = ((p[0] - a[0]) * d2y - (p[1] - a[1]) * d2x) / den;
        return new double[]{p[0] - t * d1x, p[1] - t * d1y};
    }
}
