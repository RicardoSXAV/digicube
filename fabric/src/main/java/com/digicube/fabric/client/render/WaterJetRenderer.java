package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.fabric.client.model.NativeModelGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a jet of water ({@code art} {@code water}: Shellmon's Hydro Pressure) from its effect model, translucent as water
 * is: one continuous square tube through the puffs from the mouth to the end of the stream, its faces painted with the
 * art's shaft (blue shadows, cyan body, pale streaks) and the paint carried by the puffs themselves, so the streaks
 * flow down the jet with the water; it narrows at the mouth and widens as the jet spreads, as wide as what it strikes,
 * and where the water runs along a surface it lies on it as a flat sheet. Foam ribbons ride the tube's faces with their
 * puffs, drops of spray fly off it and fall, the throat and its four foam fins stand at the mouth, a fresh jet's leading
 * crest and foam tip run ahead of it, and where it strikes the art's splash bursts open (its petals, foam core and
 * flung drops) and fades. Under water the jet is only a faint swirl (its bubbles are particles).
 *
 * <p>Quads are sorted back to front each frame (translucent faces drawn in a fixed order flicker where they cross), with
 * the light where the jet is.
 *
 * <p>The effect model's parts ({@code hp_} prefix): {@code hp_shaft} (the tube's paint, one face per side),
 * {@code hp_throat}, {@code hp_emission_fin_k}, {@code hp_crest}, {@code hp_tip}, {@code hp_ribbon_k} (on the top and
 * bottom), {@code hp_side_ribbon_k}, {@code hp_drop_k}, {@code hp_impact_core}, {@code hp_impact_petal_k},
 * {@code hp_impact_drop_k}; the art's stream runs along -z.
 */
public final class WaterJetRenderer implements BreathArt {
    /** Blocks an art pixel is drawn at near the mouth; the tube widens with each puff's radius past that. */
    private static final float PIXEL = .032F;
    /** The longest gap between two puffs the tube bridges (blocks): further apart the jet is torn. */
    private static final float MAX_GAP = 1.8F;
    /** The art's shaft cross-section (px) and length of its paint (px), from the model. */
    private final float shaftWidth, shaftLength;
    /** Each long face of the shaft: its UV rectangle and whether its v runs along the length. */
    private final float[][] faces = new float[4][];
    private final FrostBreathRenderer.Box throat, crest, tip, core;
    private final FrostBreathRenderer.Box[] fins, ribbons, sideRibbons, drops, petals, splashDrops;
    private final float[][] ribbonUv, sideRibbonUv;
    private final float[] ribbonLength, sideRibbonLength;
    private final Identifier texture;

    public WaterJetRenderer(String effect) {
        this.texture = Constants.id("textures/entity/digimon/" + effect + ".png");
        var mesh = NativeModelGeometry.mesh(Constants.id("models/entity/" + effect + ".mesh.json"));
        Map<String, NativeModelGeometry.Part> parts = new HashMap<>();
        for (var part : mesh.parts()) parts.put(part.name(), part);
        var shaft = parts.get("hp_shaft");
        if (shaft == null) throw new IllegalStateException("No water shaft in " + effect);
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var q : shaft.quads()) for (float[] v : q.vertices()) for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], v[a]); max[a] = Math.max(max[a], v[a]); }
        shaftWidth = max[0] - min[0];
        shaftLength = max[2] - min[2];
        // the four long faces: +y (top), -y (under), +x, -x; each face's rectangle in the atlas, and which way its length runs
        int k = 0;
        for (var q : shaft.quads()) {
            float[] n = q.normal();
            if (Math.abs(n[2]) > .5F) continue;
            faces[k++] = rect(q);
            if (k == 4) break;
        }
        throat = FrostBreathRenderer.box(parts.get("hp_throat"), 0, 0, false);
        crest = FrostBreathRenderer.box(parts.get("hp_crest"), 0, 0, false);
        tip = FrostBreathRenderer.box(parts.get("hp_tip"), 0, 0, false);
        core = FrostBreathRenderer.box(parts.get("hp_impact_core"), 0, 0, true);
        fins = list(parts, "hp_emission_fin_", true);
        drops = list(parts, "hp_drop_", true);
        petals = list(parts, "hp_impact_petal_", true);
        splashDrops = list(parts, "hp_impact_drop_", false);
        ribbons = list(parts, "hp_ribbon_", true);
        sideRibbons = list(parts, "hp_side_ribbon_", true);
        ribbonUv = new float[ribbons.length][]; ribbonLength = new float[ribbons.length];
        for (int i = 0; i < ribbons.length; i++) { ribbonUv[i] = rect(parts.get("hp_ribbon_" + i).quads()[0]); ribbonLength[i] = ribbons[i].sizeZ(); }
        sideRibbonUv = new float[sideRibbons.length][]; sideRibbonLength = new float[sideRibbons.length];
        for (int i = 0; i < sideRibbons.length; i++) { sideRibbonUv[i] = rect(parts.get("hp_side_ribbon_" + i).quads()[0]); sideRibbonLength[i] = sideRibbons[i].sizeZ(); }
    }

    /** A quad's UV rectangle {u0, v0, u1, v1, lengthAlongV (1 or 0)}: which UV axis runs with the art's z. */
    private static float[] rect(NativeModelGeometry.Quad q) {
        float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (float[] v : q.vertices()) { u0 = Math.min(u0, v[3]); u1 = Math.max(u1, v[3]); v0 = Math.min(v0, v[4]); v1 = Math.max(v1, v[4]); }
        // the axis along which the face's z varies with v
        float[] a = q.vertices()[0], b = q.vertices()[1], c = q.vertices()[2];
        float dzv = Math.abs((b[2] - a[2]) * (b[4] - a[4])) + Math.abs((c[2] - b[2]) * (c[4] - b[4]));
        float dzu = Math.abs((b[2] - a[2]) * (b[3] - a[3])) + Math.abs((c[2] - b[2]) * (c[3] - b[3]));
        return new float[]{u0, v0, u1, v1, dzv >= dzu ? 1 : 0};
    }

    private static FrostBreathRenderer.Box[] list(Map<String, NativeModelGeometry.Part> parts, String prefix, boolean turned) {
        List<FrostBreathRenderer.Box> out = new ArrayList<>();
        for (int k = 0; parts.containsKey(prefix + k); k++) {
            var part = parts.get(prefix + k);
            out.add(FrostBreathRenderer.box(part, turned ? 0 : 0, 0, turned));
        }
        return out.toArray(FrostBreathRenderer.Box[]::new);
    }

    // ------------------------------------------------------------------------------------------------ quads
    /** Quads of this frame: four vertices of x, y, z, u, v, then the normal and the colour (alpha in its top byte). */
    private static final class Quads {
        float[] data = new float[0];
        int count;
        void add(float[] p0, float[] p1, float[] p2, float[] p3, float[] uv, float nx, float ny, float nz, int color) {
            int need = (count + 1) * 24;
            if (need > data.length) data = Arrays.copyOf(data, Math.max(need, data.length * 2));
            int o = count * 24;
            float[][] p = {p0, p1, p2, p3};
            for (int i = 0; i < 4; i++) {
                data[o++] = p[i][0]; data[o++] = p[i][1]; data[o++] = p[i][2]; data[o++] = uv[2 * i]; data[o++] = uv[2 * i + 1];
            }
            data[o++] = nx; data[o++] = ny; data[o++] = nz; data[o] = Float.intBitsToFloat(color);
            count++;
        }
    }

    private final Quads quads = new Quads();
    private int light = 0xF000F0;

    /** The light the jet is drawn with: the world's where it is (set by the renderer before submitting). */
    public void light(int packed) { this.light = packed; }

    @Override
    public void submit(FrostBreathRenderer.State s, PoseStack pose, SubmitNodeCollector collector, float ageInTicks) {
        if (s.count == 0) return;
        quads.count = 0;
        build(s, ageInTicks);
        if (quads.count == 0) return;
        collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(texture), this::write);
    }

    private void write(PoseStack.Pose pose, VertexConsumer vertices) {
        // back to front in view space
        int n = quads.count;
        Integer[] order = new Integer[n];
        float[] depth = new float[n];
        var m = pose.pose();
        Vector3f c = new Vector3f();
        for (int q = 0; q < n; q++) {
            int o = q * 24;
            float x = (quads.data[o] + quads.data[o + 5] + quads.data[o + 10] + quads.data[o + 15]) * .25F;
            float y = (quads.data[o + 1] + quads.data[o + 6] + quads.data[o + 11] + quads.data[o + 16]) * .25F;
            float z = (quads.data[o + 2] + quads.data[o + 7] + quads.data[o + 12] + quads.data[o + 17]) * .25F;
            m.transformPosition(x, y, z, c);
            depth[q] = c.lengthSquared();
            order[q] = q;
        }
        Arrays.sort(order, (a, b) -> Float.compare(depth[b], depth[a]));
        for (int k = 0; k < n; k++) {
            int o = order[k] * 24;
            float nx = quads.data[o + 20], ny = quads.data[o + 21], nz = quads.data[o + 22];
            int color = Float.floatToRawIntBits(quads.data[o + 23]);
            for (int i = 0; i < 4; i++, o += 5) {
                vertices.addVertex(pose, quads.data[o], quads.data[o + 1], quads.data[o + 2]).setColor(color).setUv(quads.data[o + 3], quads.data[o + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the jet
    /** Per-puff frames of the tube: tangent, right and up (blocks), half-width, half-height, paint coordinate, alpha. */
    private float[] tx = new float[64], ty = new float[64], tz = new float[64], rx = new float[64], ry = new float[64], rz = new float[64],
            ux = new float[64], uy = new float[64], uz = new float[64], hw = new float[64], hh = new float[64], paint = new float[64], alpha = new float[64];

    private void ensure(int n) {
        if (tx.length >= n) return;
        int m = Math.max(n, tx.length * 2);
        tx = Arrays.copyOf(tx, m); ty = Arrays.copyOf(ty, m); tz = Arrays.copyOf(tz, m); rx = Arrays.copyOf(rx, m); ry = Arrays.copyOf(ry, m);
        rz = Arrays.copyOf(rz, m); ux = Arrays.copyOf(ux, m); uy = Arrays.copyOf(uy, m); uz = Arrays.copyOf(uz, m); hw = Arrays.copyOf(hw, m);
        hh = Arrays.copyOf(hh, m); paint = Arrays.copyOf(paint, m); alpha = Arrays.copyOf(alpha, m);
    }

    private void build(FrostBreathRenderer.State s, float time) {
        int n = s.count;
        ensure(n);
        // paint carried by the puffs: each puff's coordinate along the art's shaft (px), the gap between two puffs shed
        // one after the other being their flight a puff's worth apart
        float spacing = s.speed / Math.max(1, s.perTick) / PIXEL;
        for (int i = 0; i < n; i++) {
            float r = s.radius[i];
            float life = s.life, age = s.age[i];
            float fade = Mth.clamp((life - age) / 5F, 0, 1) * Mth.clamp(age / .6F + .35F, 0, 1);
            if (s.under[i]) fade *= .22F;
            alpha[i] = fade;
            paint[i] = -s.seed[i] * spacing;
            if (s.struck[i]) { hw[i] = r * 1.25F; hh[i] = .035F; }
            else { hw[i] = Math.max(shaftWidth * PIXEL * .5F, r * .8F); hh[i] = hw[i]; }
        }
        frames(s);
        // the tube, newest to oldest, bridging gaps no longer than MAX_GAP
        for (int i = n - 1; i > 0; i--) {
            int j = i - 1;
            float d = dist(s, i, j);
            if (d > MAX_GAP || alpha[i] <= .01F && alpha[j] <= .01F) continue;
            tube(s, i, j);
        }
        // the mouth: the throat and its fins, the newest puff's
        int newest = n - 1;
        if (s.age[newest] < 1.6F && !s.under[newest]) emission(s, newest, time);
        // the fresh jet's front: crest and foam tip ahead of the oldest puff, while it still flies
        if (!s.struck[0] && !s.under[0] && s.age[0] < s.life * .7F && n > 2 && dist(s, 0, 1) < MAX_GAP) front(s, 0, time);
        for (int i = 0; i < n; i++) {
            if (s.under[i]) continue;
            int seed = s.seed[i];
            if (!s.struck[i] && s.age[i] > 1.2F) {
                if (ribbons.length > 0 && seed % 4 == 0) ribbon(s, i, seed);
                if (sideRibbons.length > 0 && seed % 5 == 2) sideRibbon(s, i, seed);
                if (drops.length > 0 && seed % 3 == 1 && s.age[i] > 2.5F) drop(s, i, seed);
            }
            if (s.struck[i] && s.struckAt[i] >= 0) {
                float since = s.age[i] - s.struckAt[i];
                if (since < 5 && seed % 4 == 0) splash(s, i, since, seed);
            }
        }
    }

    private static float dist(FrostBreathRenderer.State s, int a, int b) {
        float dx = s.x[a] - s.x[b], dy = s.y[a] - s.y[b], dz = s.z[a] - s.z[b];
        return Mth.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The tube's frame at every puff: its tangent along the train (toward the older puffs, the way the water goes), and
     * right and up carried from one puff to the next without twisting; a puff running along a surface has the
     * surface's face for its up.
     */
    private void frames(FrostBreathRenderer.State s) {
        int n = s.count;
        for (int i = 0; i < n; i++) {
            float ax, ay, az;
            int a = Math.min(n - 1, i + 1), b = Math.max(0, i - 1);
            if (a == b) { ax = s.lookX[i]; ay = s.lookY[i]; az = s.lookZ[i]; }
            else { ax = s.x[b] - s.x[a]; ay = s.y[b] - s.y[a]; az = s.z[b] - s.z[a]; }
            float l = Mth.sqrt(ax * ax + ay * ay + az * az);
            if (l < 1.0E-4F) { ax = s.lookX[i]; ay = s.lookY[i]; az = s.lookZ[i]; l = Mth.sqrt(ax * ax + ay * ay + az * az); }
            if (l < 1.0E-4F) { ax = 0; ay = 0; az = 1; l = 1; }
            tx[i] = ax / l; ty[i] = ay / l; tz[i] = az / l;
        }
        // the first frame from the world's up, then each carried on to the next (the previous up made square to the tangent)
        float upx = 0, upy = 1, upz = 0;
        for (int i = n - 1; i >= 0; i--) {
            float px = upx, py = upy, pz = upz;
            if (s.struck[i] && s.surfaceX[i] * s.surfaceX[i] + s.surfaceY[i] * s.surfaceY[i] + s.surfaceZ[i] * s.surfaceZ[i] > .5F) {
                px = s.surfaceX[i]; py = s.surfaceY[i]; pz = s.surfaceZ[i];
            }
            float d = px * tx[i] + py * ty[i] + pz * tz[i];
            px -= tx[i] * d; py -= ty[i] * d; pz -= tz[i] * d;
            float l = Mth.sqrt(px * px + py * py + pz * pz);
            if (l < 1.0E-3F) {
                // the tangent runs along the old up: take any square direction
                px = -tz[i]; py = 0; pz = tx[i]; l = Mth.sqrt(px * px + pz * pz);
                if (l < 1.0E-3F) { px = 1; py = 0; pz = 0; l = 1; }
            }
            ux[i] = px / l; uy[i] = py / l; uz[i] = pz / l;
            // right = up x tangent
            rx[i] = uy[i] * tz[i] - uz[i] * ty[i]; ry[i] = uz[i] * tx[i] - ux[i] * tz[i]; rz[i] = ux[i] * ty[i] - uy[i] * tx[i];
            upx = ux[i]; upy = uy[i]; upz = uz[i];
        }
    }

    /**
     * One stretch of the tube between puffs a (the younger, nearer the mouth) and b, split where its paint wraps round
     * the art's shaft. The younger puff's paint is always the lower (puffs are shed in order).
     */
    private void tube(FrostBreathRenderer.State s, int a, int b) {
        float pa = paint[a], pb = paint[b];
        if (pb - pa < 1.0E-4F) { piece(s, a, b, 0, 1); return; }
        // split at multiples of the shaft's length so every piece maps inside the art
        float cut = (float) (Math.floor(pa / shaftLength) + 1) * shaftLength;
        float from = 0;
        for (int guard = 0; guard < 64; guard++) {
            float to = cut >= pb ? 1 : (cut - pa) / (pb - pa);
            piece(s, a, b, from, to);
            if (to >= 1) break;
            from = to;
            cut += shaftLength;
        }
    }

    /** The four faces of the tube from share {@code f0} to {@code f1} of the way from puff a to puff b. */
    private void piece(FrostBreathRenderer.State s, int a, int b, float f0, float f1) {
        if (f1 - f0 < 1.0E-4F) return;
        float[] c0 = mix(s, a, b, f0), c1 = mix(s, a, b, f1);
        float p0 = Mth.lerp(f0, paint[a], paint[b]), p1 = Mth.lerp(f1, paint[a], paint[b]);
        float base = (float) Math.floor(Math.min(p0, p1) / shaftLength) * shaftLength;
        float v0 = (p0 - base) / shaftLength, v1 = (p1 - base) / shaftLength;
        float al0 = Mth.lerp(f0, alpha[a], alpha[b]), al1 = Mth.lerp(f1, alpha[a], alpha[b]);
        int color = color((al0 + al1) * .5F, 255);
        // corners of each section: centre +- right * half-width +- up * half-height
        float[][] s0 = corners(c0), s1 = corners(c1);
        // faces: top (+up), right (+right), under (-up), left (-right): corner pairs (0-1), (1-2), (2-3), (3-0)
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}};
        int[] art = {0, 2, 1, 3};
        for (int e = 0; e < 4; e++) {
            float[] r = faces[art[e]];
            if (r == null) continue;
            float[] uv = uv(r, v0, v1);
            float[] q0 = s0[edges[e][0]], q1 = s0[edges[e][1]], q2 = s1[edges[e][1]], q3 = s1[edges[e][0]];
            float[] n = normal(q0, q1, q2);
            quads.add(q0, q1, q2, q3, uv, n[0], n[1], n[2], color);
        }
    }

    /** UVs for the four corners of a face piece (across 0..1 at the start, then the end), v0..v1 along the shaft. */
    private static float[] uv(float[] r, float v0, float v1) {
        float u0 = r[0], u1 = r[2];
        if (r[4] > .5F) {
            float a = Mth.lerp(v0, r[1], r[3]), b = Mth.lerp(v1, r[1], r[3]);
            return new float[]{u0, a, u1, a, u1, b, u0, b};
        }
        float a = Mth.lerp(v0, r[0], r[2]), b = Mth.lerp(v1, r[0], r[2]);
        return new float[]{a, r[1], a, r[3], b, r[3], b, r[1]};
    }

    /** A section's centre, frame and half sizes {x, y, z, rx, ry, rz, ux, uy, uz, hw, hh} at share f from a to b. */
    private float[] mix(FrostBreathRenderer.State s, int a, int b, float f) {
        return new float[]{Mth.lerp(f, s.x[a], s.x[b]), Mth.lerp(f, s.y[a], s.y[b]), Mth.lerp(f, s.z[a], s.z[b]),
                Mth.lerp(f, rx[a], rx[b]), Mth.lerp(f, ry[a], ry[b]), Mth.lerp(f, rz[a], rz[b]),
                Mth.lerp(f, ux[a], ux[b]), Mth.lerp(f, uy[a], uy[b]), Mth.lerp(f, uz[a], uz[b]),
                Mth.lerp(f, hw[a], hw[b]), Mth.lerp(f, hh[a], hh[b])};
    }

    /** The four corners of a section: up-left, up-right, down-right, down-left. */
    private static float[][] corners(float[] c) {
        float[][] out = new float[4][];
        float[][] sign = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
        for (int k = 0; k < 4; k++) {
            float sr = sign[k][0] * c[9], su = sign[k][1] * c[10];
            out[k] = new float[]{c[0] + c[3] * sr + c[6] * su, c[1] + c[4] * sr + c[7] * su, c[2] + c[5] * sr + c[8] * su};
        }
        return out;
    }

    private static float[] normal(float[] a, float[] b, float[] c) {
        float ex = b[0] - a[0], ey = b[1] - a[1], ez = b[2] - a[2], fx = c[0] - a[0], fy = c[1] - a[1], fz = c[2] - a[2];
        float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx, l = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        return l < 1.0E-6F ? new float[]{0, 1, 0} : new float[]{nx / l, ny / l, nz / l};
    }

    private static int color(float alpha, int bright) {
        int a = Mth.clamp(Math.round(alpha * 255), 0, 255);
        return a << 24 | bright << 16 | bright << 8 | bright;
    }

    // ------------------------------------------------------------------------------------------------ boxes of the art
    /** A frame of 9 numbers (right, up, forward) for a box laid along puff i's tangent; the art's -z runs forward. */
    private float[] frameAt(int i) {
        return new float[]{rx[i], ry[i], rz[i], ux[i], uy[i], uz[i], tx[i], ty[i], tz[i]};
    }

    /**
     * Lays an art box: its model pixel (x, y, z) lands at the anchor plus right x + up y - forward z (the art's stream
     * runs along -z, the way the water goes), all times {@code scale}.
     */
    private void box(FrostBreathRenderer.Box box, float ax, float ay, float az, float[] f, float scale, float alphaShare, boolean twoSided) {
        int color = color(alphaShare, 255);
        for (int q = 0; q < box.quads().length; q++) {
            float[][] v = box.quads()[q];
            float[][] p = new float[4][];
            float[] uv = new float[8];
            for (int i = 0; i < 4; i++) {
                float x = v[i][0], y = -v[i][1], z = -v[i][2];
                p[i] = new float[]{ax + (f[0] * x + f[3] * y + f[6] * z) * scale, ay + (f[1] * x + f[4] * y + f[7] * z) * scale,
                        az + (f[2] * x + f[5] * y + f[8] * z) * scale};
                uv[2 * i] = v[i][3]; uv[2 * i + 1] = v[i][4];
            }
            float[] n = normal(p[0], p[1], p[2]);
            quads.add(p[0], p[1], p[2], p[3], uv, n[0], n[1], n[2], color);
            if (twoSided) quads.add(p[3], p[2], p[1], p[0], new float[]{uv[6], uv[7], uv[4], uv[5], uv[2], uv[3], uv[0], uv[1]}, -n[0], -n[1], -n[2], color);
        }
    }

    /** The throat and its foam fins at the mouth, the fins turning slowly round it and flickering. */
    private void emission(FrostBreathRenderer.State s, int i, float time) {
        float[] f = frameAt(i);
        float k = PIXEL * (.9F + .1F * Mth.sin(time * 2.7F));
        // the throat's art sits 10 px ahead of the emission point
        box(throat, s.x[i], s.y[i], s.z[i], f, k, 1, false);
        float turn = time * .35F;
        float c = Mth.cos(turn), sn = Mth.sin(turn);
        float[] g = {f[0] * c + f[3] * sn, f[1] * c + f[4] * sn, f[2] * c + f[5] * sn, -f[0] * sn + f[3] * c, -f[1] * sn + f[4] * c, -f[2] * sn + f[5] * c, f[6], f[7], f[8]};
        for (int k2 = 0; k2 < fins.length; k2++) {
            float flick = .8F + .3F * Mth.sin(time * 3.1F + k2 * 1.7F);
            box(fins[k2], s.x[i], s.y[i], s.z[i], g, PIXEL * flick, 1, true);
        }
    }

    /** The fresh jet's leading crest and its foam tip, ahead of the oldest puff. */
    private void front(FrostBreathRenderer.State s, int i, float time) {
        float[] f = frameAt(i);
        float k = Math.max(PIXEL, hw[i] * 2 / 10);
        // the crest's art stands 84..106 px out and the tip at 109: put the crest's back on the puff
        float back = 84 * k;
        float ax = s.x[i] - f[6] * back, ay = s.y[i] - f[7] * back, az = s.z[i] - f[8] * back;
        box(crest, ax, ay, az, f, k, alpha[i], false);
        box(tip, ax, ay, az, f, k * (.9F + .15F * Mth.sin(time * 4)), 1, false);
    }

    /** A foam ribbon on the tube's top or under face, riding its puff. */
    private void ribbon(FrostBreathRenderer.State s, int i, int seed) {
        int k = (seed >>> 2) % ribbons.length;
        float[] f = frameAt(i);
        float scale = hw[i] * 2 / shaftWidth;
        // the art's ribbon lies at its own z; centre it on the puff
        var r = ribbons[k];
        float mid = centreZ(r);
        float ax = s.x[i] + f[6] * mid * scale, ay = s.y[i] + f[7] * mid * scale, az = s.z[i] + f[8] * mid * scale;
        box(r, ax, ay, az, f, scale, alpha[i], true);
    }

    private void sideRibbon(FrostBreathRenderer.State s, int i, int seed) {
        int k = (seed >>> 3) % sideRibbons.length;
        float[] f = frameAt(i);
        float scale = hw[i] * 2 / shaftWidth;
        var r = sideRibbons[k];
        float mid = centreZ(r);
        float ax = s.x[i] + f[6] * mid * scale, ay = s.y[i] + f[7] * mid * scale, az = s.z[i] + f[8] * mid * scale;
        box(r, ax, ay, az, f, scale, alpha[i], true);
    }

    private static float centreZ(FrostBreathRenderer.Box b) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float[][] q : b.quads()) for (float[] v : q) { lo = Math.min(lo, v[2]); hi = Math.max(hi, v[2]); }
        return (lo + hi) * .5F;
    }

    /** A drop of spray torn off the jet: it leaves the skin and falls, shrinking. */
    private void drop(FrostBreathRenderer.State s, int i, int seed) {
        var d = drops[(seed >>> 1) % drops.length];
        float age = s.age[i] - 2.5F;
        float way = seed * 2.39996F;
        float out = hw[i] + .05F + .06F * age;
        float c = Mth.cos(way), sn = Mth.sin(way);
        float[] f = frameAt(i);
        float ax = s.x[i] + (f[0] * c + f[3] * sn) * out, ay = s.y[i] + (f[1] * c + f[4] * sn) * out - .012F * age * age, az = s.z[i] + (f[2] * c + f[5] * sn) * out;
        float shrink = Mth.clamp(1 - age / 9F, 0, 1);
        if (shrink <= .05F) return;
        box(d, ax, ay, az, f, PIXEL * 1.2F * shrink, 1, false);
    }

    /** The splash where a puff struck: petals burst open out of the surface, a foam core and flung drops; it fades. */
    private void splash(FrostBreathRenderer.State s, int i, float since, int seed) {
        float nx = s.surfaceX[i], ny = s.surfaceY[i], nz = s.surfaceZ[i];
        if (nx * nx + ny * ny + nz * nz < .5F) return;
        // the burst's frame: the art's petals stand up its -y, out of the surface along the face's normal, and fan round
        // its forward, the way the water was running along the surface (turned a little by the puff's own seed)
        float fx = s.lookX[i], fy = s.lookY[i], fz = s.lookZ[i];
        float d = fx * nx + fy * ny + fz * nz;
        fx -= nx * d; fy -= ny * d; fz -= nz * d;
        float fl = Mth.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1.0E-3F) { fx = Math.abs(ny) > .9F ? 1 : 0; fy = Math.abs(ny) > .9F ? 0 : 1; fz = 0; d = fx * nx + fy * ny; fx -= nx * d; fy -= ny * d; fz -= nz * d; fl = Mth.sqrt(fx * fx + fy * fy + fz * fz); }
        fx /= fl; fy /= fl; fz /= fl;
        float turn = (seed % 7) * .45F;
        float c = Mth.cos(turn), sn = Mth.sin(turn);
        // right = up x forward, then the pair turned about the normal
        float rxv = ny * fz - nz * fy, ryv = nz * fx - nx * fz, rzv = nx * fy - ny * fx;
        float[] f = {rxv * c + fx * sn, ryv * c + fy * sn, rzv * c + fz * sn, nx, ny, nz, -rxv * sn + fx * c, -ryv * sn + fy * c, -rzv * sn + fz * c};
        float grow = Mth.clamp(since / 1.5F, 0, 1), fade = Mth.clamp(1 - (since - 2) / 4, 0, 1);
        float scale = PIXEL * (.6F + .5F * grow) * Mth.clamp(s.radius[i] / .3F, .6F, 1.6F);
        float hx = s.x[i] + nx * .02F, hy = s.y[i] + ny * .02F, hz = s.z[i] + nz * .02F;
        if (since < 3) box(core, hx, hy, hz, f, scale * (1 - since / 3), 1, false);
        for (int k = 0; k < petals.length; k++) if ((seed + k) % 2 == 0) box(petals[k], hx, hy, hz, f, scale, fade * .9F, true);
        for (int k = 0; k < splashDrops.length; k++) {
            if ((seed + k) % 3 != 0) continue;
            float lift = since * .045F - since * since * .01F;
            box(splashDrops[k], hx + nx * lift, hy + ny * lift, hz + nz * lift, f, scale * (1 + since * .25F), fade, false);
        }
    }
}
