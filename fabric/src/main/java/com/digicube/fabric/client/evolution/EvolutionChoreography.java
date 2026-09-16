package com.digicube.fabric.client.evolution;

import com.digicube.digimon.EvolutionTimeline;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Analytic, event-time choreography: late observers and replays never simulate an emitter history. */
public final class EvolutionChoreography {
    private EvolutionChoreography() {}

    public record Motion(float lift, float yaw, float width, float height) {}

    public static Motion motion(float tick, float radius) {
        float rise = ease(tick, 8, 48);
        float land = ease(tick, 122, 144);
        float lift = Math.clamp(.35F + radius * .18F, .45F, 1.2F) * rise * (1 - land);
        // One complete turn, with zero angular velocity at both ends; face forward before skin completes.
        float yaw = (float) (Math.PI * 2) * ease(tick, 16, 110);
        float weight = ease(tick, 142, 147) * (1 - ease(tick, 147, 156));
        return new Motion(lift, yaw, 1 + .012F * weight, 1 - .025F * weight);
    }

    /** Conservative sphere includes rotation, lift, fragments and the outermost release particles. */
    public static float extent(float radius) { return radius * 1.65F + 3; }

    public static void transform(List<EvolutionMesh.Face> faces, Motion motion) {
        float c = (float) Math.cos(motion.yaw), s = (float) Math.sin(motion.yaw);
        for (var face : faces) {
            var v = face.vertices();
            for (int i = 0; i < 32; i += 8) {
                float x = v[i], z = v[i + 2], nx = v[i + 5] / motion.width, nz = v[i + 7] / motion.width;
                v[i] = (x * c + z * s) * motion.width;
                v[i + 1] = v[i + 1] * motion.height + motion.lift;
                v[i + 2] = (z * c - x * s) * motion.width;
                float ny = v[i + 6] / motion.height;
                float inv = 1 / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                v[i + 5] = (nx * c + nz * s) * inv;
                v[i + 6] = ny * inv;
                v[i + 7] = (nz * c - nx * s) * inv;
            }
        }
    }

    public static List<EvolutionMesh.Face> particles(EvolutionTimeline time, float tick, float radius, float height,
                                                    int color, float quality, long seed) {
        if (time.returning() || quality <= 0 || tick <= 0 || tick >= time.duration()) return List.of();
        boolean full = time.longForm();
        tick = time.bodyTick(tick);
        if (tick <= 0) return List.of();
        var out = new ArrayList<EvolutionMesh.Face>();
        float r = Math.max(.65F, Math.min(radius, 6)), size = Math.clamp(r * .025F, .025F, .075F);
        int stride = quality < .34F ? 4 : quality < .75F ? 2 : 1;
        float lift = full ? motion(tick, radius).lift : 0;
        // Finite inward flights: broad at the ground, spiralling upward into the data body.
        int gatherCount = full ? 40 : 16;
        for (int i = 0; i < gatherCount; i += stride) {
            float birth = full ? i * 2.1F : i * .7F;
            float life = full ? 32 + noise(i, seed) * 12 : 9;
            float age = (tick - birth) / life;
            if (age <= 0 || age >= 1) continue;
            float a = noise(i + 81, seed) * 6.283185F + age * (full ? 2.6F : 1.1F);
            float rr = r * (1.12F - .66F * ease(age, 0, 1));
            float y = .08F + age * height * (.65F + noise(i + 121, seed) * .3F) + lift * age;
            float envelope = ease(age, 0, .18F) * (1 - ease(age, .72F, 1));
            cube(out, rr * cos(a), y, rr * sin(a), size * (1 + noise(i + 20, seed)),
                    a + age, tint(color, .55F), envelope * .8F, i % 3 == 0);
        }
        // Sparse satellites frame the recognizable body; they never become an opaque sphere.
        int orbitCount = full ? 24 : 8;
        float orbit = full ? ease(tick, 12, 38) * (1 - ease(tick, 100, 128))
                : ease(tick, 3, 9) * (1 - ease(tick, 17, 24));
        for (int i = 0; i < orbitCount && orbit > .001F; i += stride) {
            float phase = noise(i + 221, seed);
            float a = i * 2.399963F + tick * (full ? .033F : .07F);
            float rr = r * (.72F + .2F * phase);
            float y = .15F + phase * height * .95F + lift;
            float shimmer = .75F + .25F * sin(tick * .16F + i);
            cube(out, rr * cos(a), y, rr * sin(a), size * (i % 4 == 0 ? 1.5F : .7F),
                    a * .6F, tint(color, .7F), orbit * shimmer * .65F, i % 4 == 0);
        }
        // Release only after the stronger body is readable; ground accents follow its landing.
        int releaseCount = full ? 32 : 12;
        for (int i = 0; i < releaseCount; i += stride) {
            float birth = full ? 126 + noise(i + 320, seed) * 6 : 22 + noise(i + 320, seed) * 2;
            float life = full ? 160 - birth : 32 - birth;
            float age = (tick - birth) / life;
            if (age <= 0 || age >= 1) continue;
            float a = i * 2.399963F + noise(i + 91, seed) * .4F;
            float travel = 1 - (1 - age) * (1 - age);
            float rr = r * (.48F + travel * (full ? .85F : .6F));
            float y = height * (.2F + .6F * noise(i + 420, seed)) + lift + age * .4F;
            float envelope = ease(age, 0, .12F) * (1 - ease(age, .3F, 1));
            cube(out, rr * cos(a), y, rr * sin(a), size * (1.2F - age * .6F),
                    a + age * 2, tint(color, .8F), envelope * .85F, i % 3 == 0);
        }
        if (full) {
            band(out, r, .055F, tick * .012F, .045F,
                    ease(tick, 0, 20) * (1 - ease(tick, 112, 134)) * .5F, color);
            float arrival = (tick - 142) / 14;
            if (arrival > 0 && arrival < 1)
                band(out, r * (.45F + .9F * ease(arrival, 0, 1)), .06F, 0, .04F * (1 - arrival),
                        ease(arrival, 0, .12F) * (1 - ease(arrival, .3F, 1)), tint(color, .7F));
        }
        return List.copyOf(out);
    }

    private static void cube(List<EvolutionMesh.Face> out, float x, float y, float z, float size,
                             float turn, int color, float opacity, boolean halo) {
        if (opacity < .005F) return;
        var rotation = new Quaternionf().rotationYXZ(turn, turn * .37F, .25F);
        Vector3f[] axes = {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
        for (var axis : axes) rotation.transform(axis);
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
            var n = new Vector3f(axes[axis]).mul(sign);
            quad(out, x + n.x * size, y + n.y * size, z + n.z * size,
                    axes[(axis + 1) % 3], axes[(axis + 2) % 3], size, color, opacity, false);
        }
        // Three soft crossed planes provide a view-independent local glow without a bloom dependency.
        if (halo) for (int axis = 0; axis < 3; axis++)
            quad(out, x, y, z, axes[(axis + 1) % 3], axes[(axis + 2) % 3], size * 3.5F, color, opacity * .3F, true);
    }

    private static void band(List<EvolutionMesh.Face> out, float radius, float y, float turn,
                             float width, float opacity, int color) {
        if (opacity < .005F) return;
        for (int i = 0; i < 48; i++) {
            if (i % 6 >= 4) continue;
            float a = i * 6.283185F / 48 + turn;
            quad(out, cos(a) * radius, y, sin(a) * radius, new Vector3f(-sin(a), 0, cos(a)),
                    new Vector3f(cos(a), 0, sin(a)).mul(width / Math.max(.001F, radius * .05F)),
                    radius * .05F, color, opacity, false);
        }
    }

    private static void quad(List<EvolutionMesh.Face> out, float x, float y, float z, Vector3f u,
                             Vector3f v, float size, int color, float opacity, boolean halo) {
        var n = new Vector3f(u).cross(v).normalize();
        float[] data = new float[32];
        for (int i = 0; i < 4; i++) {
            float a = i == 0 || i == 3 ? -1 : 1, b = i < 2 ? -1 : 1; int at = i * 8;
            data[at] = x + (u.x * a + v.x * b) * size;
            data[at + 1] = y + (u.y * a + v.y * b) * size;
            data[at + 2] = z + (u.z * a + v.z * b) * size;
            data[at + 3] = (a + 1) * .5F + (halo ? 2 : 0); data[at + 4] = (b + 1) * .5F;
            data[at + 5] = n.x; data[at + 6] = n.y; data[at + 7] = n.z;
        }
        out.add(new EvolutionMesh.Face(data, (color & 0xffffff) | (Math.round(Math.clamp(opacity, 0, 1) * 255) << 24)));
    }

    private static float noise(int id, long seed) {
        long v = (id + seed * 31) * 0x9E3779B97F4A7C15L;
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        return ((v ^ (v >>> 27)) & 0xffffff) / (float) 0x1000000;
    }
    private static float ease(float t, float a, float b) { return EvolutionTimeline.smooth((t - a) / (b - a)); }
    private static float sin(float v) { return (float) Math.sin(v); }
    private static float cos(float v) { return (float) Math.cos(v); }
    private static int tint(int color, float white) {
        int result = 0xff000000;
        for (int shift : new int[]{0, 8, 16}) result |= Math.round(((color >> shift) & 255) * (1 - white) + 255 * white) << shift;
        return result;
    }
}
