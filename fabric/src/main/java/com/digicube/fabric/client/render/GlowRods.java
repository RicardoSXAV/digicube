package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

import java.util.Arrays;

/**
 * Glowing square rods, the blocky lightning of electric moves ({@link ArcRenderer}'s discharges, {@link ShockBall}'s
 * ball): each rod is a box along its length, drawn through {@link SolidGlow} (full-bright, unlit) with a shade by face
 * direction baked into its colour ({@link FrostBreathRenderer#shaded}), every face both ways round (the pipeline culls
 * back faces, and a rod may be seen from any side). A frame's rods are gathered here and submitted as one geometry.
 * A bolt is a zigzag of rods from one point to another, knuckled with a cube at every bend so no joint gapes, with forks
 * off its bends; a star is rods out from a point. Randomness is a xorshift state the caller threads through, so a
 * frame dealt from the same seed draws the same lightning.
 */
public final class GlowRods {
    private static final Identifier TEXTURE = Constants.id("textures/entity/digimon/electric_arc.png");
    private float[] quads = new float[256];
    private int quadCount;

    public void clear() { quadCount = 0; }
    public boolean isEmpty() { return quadCount == 0; }

    /**
     * A zigzag from a to b; returns the random state. A bend every {@code bend} blocks strays aside by up to {@code jag}
     * (less on a short bolt, most mid-way); {@code forks} short branches of {@code forkColor} leave its bends.
     */
    public long bolt(float ax, float ay, float az, float bx, float by, float bz, float width, int color, float jag, float bend,
                     long rng, int forks, int forkColor) {
        float dx = bx - ax, dy = by - ay, dz = bz - az, len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-3F) return rng;
        int n = Math.max(3, (int) Math.ceil(len / bend));
        float[] u = perpendicular(dx / len, dy / len, dz / len), v = cross(dx / len, dy / len, dz / len, u);
        float amp = Math.min(jag, len * .09F);
        float px = ax, py = ay, pz = az;
        float[] bends = new float[(n + 1) * 3];
        for (int i = 0; i <= n; i++) {
            float t = (float) i / n, taper = Mth.sin(t * Mth.PI);
            rng = next(rng); float r1 = unit(rng); rng = next(rng); float r2 = unit(rng);
            float x = ax + dx * t, y = ay + dy * t, z = az + dz * t;
            if (i > 0 && i < n) {
                x += (u[0] * r1 + v[0] * r2) * amp * (.45F + .55F * taper);
                y += (u[1] * r1 + v[1] * r2) * amp * (.45F + .55F * taper);
                z += (u[2] * r1 + v[2] * r2) * amp * (.45F + .55F * taper);
            }
            bends[i * 3] = x; bends[i * 3 + 1] = y; bends[i * 3 + 2] = z;
            if (i > 0) rod(px, py, pz, x, y, z, width, color);
            if (i > 0 && i < n) cube(x, y, z, width * 1.25F, color);
            px = x; py = y; pz = z;
        }
        for (int f = 0; f < forks; f++) {
            rng = next(rng);
            int at = 1 + (int) ((rng >>> 33) % Math.max(1, n - 1));
            float x = bends[at * 3], y = bends[at * 3 + 1], z = bends[at * 3 + 2];
            rng = next(rng); float r1 = unit(rng); rng = next(rng); float r2 = unit(rng); rng = next(rng); float r3 = unit(rng);
            float fx = dx / len * .6F + u[0] * r1 + v[0] * r2, fy = dy / len * .6F + u[1] * r1 + v[1] * r2 + r3 * .3F, fz = dz / len * .6F + u[2] * r1 + v[2] * r2;
            float fl = Mth.sqrt(fx * fx + fy * fy + fz * fz), reach = .28F + .2F * (unit(next(rng)) * .5F + .5F);
            float ex = x + fx / fl * reach, ey = y + fy / fl * reach, ez = z + fz / fl * reach;
            float mx = (x + ex) / 2 + u[0] * r2 * .06F, my = (y + ey) / 2 + v[1] * r1 * .06F, mz = (z + ez) / 2 + u[2] * r2 * .06F;
            rod(x, y, z, mx, my, mz, width * .5F, forkColor);
            rod(mx, my, mz, ex, ey, ez, width * .4F, forkColor);
        }
        return rng;
    }

    /** {@code rays} rods out from a point in random directions, alternately of the two colours, and a cube at its heart. */
    public long star(float x, float y, float z, float reach, float width, int color, int other, long rng, int rays) {
        for (int i = 0; i < rays; i++) {
            rng = next(rng); float a = unit(rng) * Mth.PI; rng = next(rng); float b = unit(rng) * .9F;
            rng = next(rng); float l = reach * (.6F + .4F * (unit(rng) * .5F + .5F));
            float c = Mth.cos(b);
            rod(x, y, z, x + Mth.cos(a) * c * l, y + Mth.sin(b) * l, z + Mth.sin(a) * c * l, width, i % 2 == 0 ? color : other);
        }
        cube(x, y, z, width * 1.8F, color);
        return rng;
    }

    /** A square rod from p to q, its ends pushed out by half its width so bends close. */
    public void rod(float px, float py, float pz, float qx, float qy, float qz, float w, int color) {
        float dx = qx - px, dy = qy - py, dz = qz - pz, len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4F || w <= 0) return;
        float fx = dx / len, fy = dy / len, fz = dz / len;
        float[] u = perpendicular(fx, fy, fz), v = cross(fx, fy, fz, u);
        float h = w / 2, ext = h;
        box(px - fx * ext, py - fy * ext, pz - fz * ext, qx + fx * ext, qy + fy * ext, qz + fz * ext, u, v, h, color);
    }

    /** A cube of side {@code w} about a point. */
    public void cube(float x, float y, float z, float w, int color) {
        if (w <= 0) return;
        float h = w / 2;
        box(x, y - h, z, x, y + h, z, new float[]{1, 0, 0}, new float[]{0, 0, 1}, h, color);
    }

    /** A box along a to b with its cross-section spanned by u and v (half width h): six faces. */
    private void box(float ax, float ay, float az, float bx, float by, float bz, float[] u, float[] v, float h, int color) {
        float[][] c = new float[8][];
        int n = 0;
        for (int end = 0; end < 2; end++) {
            float ox = end == 0 ? ax : bx, oy = end == 0 ? ay : by, oz = end == 0 ? az : bz;
            for (int j = 0; j < 4; j++) {
                float su = (j == 0 || j == 3) ? -h : h, sv = (j < 2) ? -h : h;
                c[n++] = new float[]{ox + u[0] * su + v[0] * sv, oy + u[1] * su + v[1] * sv, oz + u[2] * su + v[2] * sv};
            }
        }
        // faces: the two ends, then the four sides (a, b corners in order round the section)
        quad(c[0], c[1], c[2], c[3], color);
        quad(c[7], c[6], c[5], c[4], color);
        for (int j = 0; j < 4; j++) {
            int k = (j + 1) % 4;
            quad(c[j], c[4 + j], c[4 + k], c[k], color);
        }
    }

    /** A face, both ways round. */
    private void quad(float[] a, float[] b, float[] c, float[] d, int color) {
        float nx = (b[1] - a[1]) * (d[2] - a[2]) - (b[2] - a[2]) * (d[1] - a[1]);
        float ny = (b[2] - a[2]) * (d[0] - a[0]) - (b[0] - a[0]) * (d[2] - a[2]);
        float nz = (b[0] - a[0]) * (d[1] - a[1]) - (b[1] - a[1]) * (d[0] - a[0]);
        float nl = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0E-9F) return;
        nx /= nl; ny /= nl; nz /= nl;
        face(a, b, c, d, nx, ny, nz, color);
        face(d, c, b, a, -nx, -ny, -nz, color);
    }

    private void face(float[] a, float[] b, float[] c, float[] d, float nx, float ny, float nz, int color) {
        int o = quadCount * 16;
        if (o + 16 > quads.length) quads = Arrays.copyOf(quads, Math.max(256, quads.length * 2));
        for (float[] p : new float[][]{a, b, c, d}) { quads[o++] = p[0]; quads[o++] = p[1]; quads[o++] = p[2]; }
        quads[o++] = nx; quads[o++] = ny; quads[o++] = nz;
        quads[o] = Float.intBitsToFloat(FrostBreathRenderer.shaded(color, Math.abs(nx), Math.abs(ny), Math.abs(nz)));
        quadCount++;
    }

    /** Submits the frame's rods at {@code pose} (their coordinates are relative to it). */
    public void submit(PoseStack pose, SubmitNodeCollector collector) {
        if (quadCount > 0) collector.submitCustomGeometry(pose, SolidGlow.type(TEXTURE), this::write);
    }

    /** Every rod face at full brightness, the texture's white texel under it. */
    private void write(PoseStack.Pose pose, VertexConsumer vertices) {
        for (int q = 0; q < quadCount; q++) {
            int o = q * 16;
            float nx = quads[o + 12], ny = quads[o + 13], nz = quads[o + 14];
            int color = Float.floatToRawIntBits(quads[o + 15]);
            for (int i = 0; i < 4; i++, o += 3) {
                vertices.addVertex(pose, quads[o], quads[o + 1], quads[o + 2]).setColor(color).setUv(.5F, .5F)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
            }
        }
    }

    static float[] perpendicular(float x, float y, float z) {
        float ax = Math.abs(x) < .9F ? 1 : 0, ay = ax == 1 ? 0 : 1;
        float[] u = cross(x, y, z, new float[]{ax, ay, 0});
        float l = Mth.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        return new float[]{u[0] / l, u[1] / l, u[2] / l};
    }

    static float[] cross(float x, float y, float z, float[] u) {
        return new float[]{y * u[2] - z * u[1], z * u[0] - x * u[2], x * u[1] - y * u[0]};
    }

    /** A random state from a seed and two counters (a link, a frame). */
    public static long mix(int seed, int a, int b) {
        long h = seed * 0x9E3779B97F4A7C15L + a * 0xC2B2AE3D27D4EB4FL + b * 0x165667B19E3779F9L;
        return next(next(h));
    }

    public static long next(long x) {
        x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
        return x;
    }

    /** A number in [-1, 1) from the random state. */
    public static float unit(long x) {
        return ((x >>> 40) & 0xFFFFFF) / (float) 0x800000 - 1;
    }
}
