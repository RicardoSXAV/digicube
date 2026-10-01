package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.fabric.client.model.NativeModelGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a breath of puffs ({@code FrostBreath}) with Ice Blast's own art: its effect model's ice shard (a long faceted
 * block), frosty breath sheet and snow cube, on its small texture, as solid glowing blocks ({@link SolidGlow}), each whole
 * and rigid at the art's own pixel, so nothing is ever stretched however the jet bends.
 *
 * <p>Every puff carries shards along its own flight back to the next puff behind it, each turned a little its own way
 * about the jet, so a steady jet is one faceted bar of ice and a swept one opens into separate streaks, as a hose's does.
 * Some puffs wrap a breath sheet round the jet and some shed a snow cube that drifts off and sinks. A puff that has struck
 * a surface lies on it: a flattened shard with snow heaped round it, so where the jet plays on the ground or a wall a
 * drift of snow and ice builds, the splash the jet always ended in. Blocks grow a little as their puffs age and shrink as
 * they die, the jet's head tapers, and the ice cools a little bluer toward its end. Each face's shade by its direction
 * goes into its vertex colour ({@link FrostBreathRenderer#shaded}).
 *
 * <p>The effect model's parts: {@code FX_ice_00}, {@code FX_breath_00} and {@code FX_snow_00} (every further copy of
 * each is the same block).
 */
public final class IceShardBreathRenderer implements BreathArt {
    /** Blocks per model pixel: the art's own. */
    static final float PIXEL = 1F / 16;
    /** Blocks between the shards of a puff's trail, and the longest trail a puff draws (further, the jet is torn). */
    private static final float SHARD_STEP = .42F, MAX_TRAIL = 1.2F;
    /** Share of its life after which a puff's blocks shrink away, and blocks over which the jet's head tapers. */
    private static final float DEATH = .75F, HEAD_TAPER = 1.2F;
    /** Radians a shard turns about the jet at most (its own way), and blocks it strays off the jet's line at the end. */
    private static final float ROLL = .45F, STRAY = .06F;

    private final Identifier texture;
    private final FrostBreathRenderer.Box shard, sheet, snow;

    public IceShardBreathRenderer(String effect) {
        this.texture = Constants.id("textures/entity/projectile/" + effect + ".png");
        var mesh = NativeModelGeometry.mesh(Constants.id("models/entity/" + effect + ".mesh.json"));
        Map<String, NativeModelGeometry.Part> parts = new HashMap<>();
        for (var part : mesh.parts()) parts.put(part.name(), part);
        this.shard = FrostBreathRenderer.box(required(parts, "FX_ice_00"), 0, 0, true);
        this.sheet = FrostBreathRenderer.box(required(parts, "FX_breath_00"), 0, 0, true);
        this.snow = FrostBreathRenderer.box(required(parts, "FX_snow_00"), 0, 0, true);
    }

    private static NativeModelGeometry.Part required(Map<String, NativeModelGeometry.Part> parts, String name) {
        var part = parts.get(name);
        if (part == null) throw new IllegalArgumentException("An ice breath's art needs a part " + name);
        return part;
    }

    @Override
    public void submit(FrostBreathRenderer.State s, PoseStack pose, SubmitNodeCollector collector, float ageInTicks) {
        if (s.count == 0) return;
        s.quadCount = 0;
        place(s, ageInTicks, s::put);
        if (s.quadCount == 0) return;
        collector.submitCustomGeometry(pose, SolidGlow.type(texture), s::write);
    }

    /** Lays every block of the jet for this frame's puffs and hands each to {@code sink}. */
    public void place(FrostBreathRenderer.State s, float time, FrostBreathRenderer.Sink sink) {
        int n = s.count;
        // how far along the train each puff is from the newest (a gap longer than a trail is a torn jet)
        float[] fromTail = new float[n];
        for (int i = n - 2; i >= 0; i--) {
            float d = distance(s, i, i + 1);
            fromTail[i] = fromTail[i + 1] + (d > MAX_TRAIL * 2 ? 0 : d);
        }
        float total = fromTail[0];
        float[] f = new float[9], turned = new float[9];
        for (int i = n - 1; i >= 0; i--) {
            float age = s.age[i], t = age / s.life;
            float g = (t < DEATH ? 1 : Math.max(0, 1 - (t - DEATH) / (1 - DEATH))) * (.45F + .55F * Mth.clamp((total - fromTail[i]) / HEAD_TAPER, 0, 1));
            if (g <= .02F) continue;
            frame(s, i, f);
            int own = FrostBreathRenderer.hash(s.seed[i], 0);
            float k = PIXEL * g * (.75F + .45F * Mth.clamp(t * 1.6F, 0, 1));
            // cooling toward the end of its life, a little bluer
            float cool = Mth.clamp((t - .4F) / .6F, 0, 1);
            int color = 0xFF000000 | Math.round(255 * (1 - .14F * cool)) << 16 | Math.round(255 * (1 - .06F * cool)) << 8 | 255;
            if (s.struck[i]) {
                // lying on what it struck: a flattened shard with snow heaped round it
                roll(f, own, .6F, turned);
                sink.box(shard, s.x[i], s.y[i], s.z[i], turned, k * 1.1F, color, false);
                for (int c = 0; c < 3; c++) {
                    int h = FrostBreathRenderer.hash(s.seed[i], 11 + c);
                    float way = h * 2.39996F, off = s.radius[i] * (.35F + .5F * (h % 7) / 6F);
                    float ox = Mth.cos(way) * off, oz = Mth.sin(way) * off;
                    float lift = snow.sizeY() * k * .6F;
                    sink.box(snow, s.x[i] + f[0] * ox + f[6] * oz + f[3] * lift, s.y[i] + f[1] * ox + f[7] * oz + f[4] * lift,
                            s.z[i] + f[2] * ox + f[8] * oz + f[5] * lift, f, k * (1.6F + .8F * (h % 3)), color, false);
                }
                if (own % 3 == 0) sink.box(sheet, s.x[i], s.y[i], s.z[i], f, k * 1.2F, color, true);
                continue;
            }
            // the trail: back along the puff's own flight as far as the next puff is behind it along that flight
            float trail = 0;
            if (i < n - 1) {
                float gap = (s.x[i] - s.x[i + 1]) * f[6] + (s.y[i] - s.y[i + 1]) * f[7] + (s.z[i] - s.z[i + 1]) * f[8];
                trail = Mth.clamp(gap, 0, MAX_TRAIL);
            }
            int m = Math.max(1, Mth.ceil(trail / SHARD_STEP));
            float stray = STRAY * Mth.clamp(t * 1.4F, 0, 1);
            for (int j = 0; j < m; j++) {
                float back = trail * j / m;
                int h = FrostBreathRenderer.hash(s.seed[i], j);
                roll(f, h, 1, turned);
                float sx = stray * (((h >>> 3) % 9) - 4) / 4F, sy = stray * (((h >>> 7) % 9) - 4) / 4F;
                sink.box(shard, s.x[i] - f[6] * back + f[0] * sx + f[3] * sy, s.y[i] - f[7] * back + f[1] * sx + f[4] * sy,
                        s.z[i] - f[8] * back + f[2] * sx + f[5] * sy, turned, k * (.9F + .2F * ((h >>> 11) % 3) / 2F), color, false);
            }
            // a breath sheet round the jet now and then
            if (own % 3 == 1 && t > .08F) {
                roll(f, own >>> 5, 3, turned);
                sink.box(sheet, s.x[i], s.y[i], s.z[i], turned, k * (.8F + .5F * t), color, true);
            }
            // snow drifting off, sinking and shrinking
            if (own % 4 == 2) {
                float e = age - 1.5F;
                if (e > 0 && e < 7) {
                    float way = own * 2.39996F, off = s.radius[i] + .05F + .07F * e;
                    float ex = Mth.cos(way) * off, ey = Mth.sin(way) * off * .8F - .012F * e * e;
                    sink.box(snow, s.x[i] + f[0] * ex + f[3] * ey, s.y[i] + f[1] * ex + f[4] * ey, s.z[i] + f[2] * ex + f[5] * ey, f,
                            PIXEL * 1.6F * (1 - e / 7), 0xFFFFFFFF, false);
                }
            }
        }
    }

    /** {@code f} turned about its forward axis by {@code h}'s share of {@code spread} x ROLL, into {@code out}. */
    private static void roll(float[] f, int h, float spread, float[] out) {
        float a = ROLL * spread * (((h >>> 2) % 9) - 4) / 4F, c = Mth.cos(a), sn = Mth.sin(a);
        for (int i = 0; i < 3; i++) {
            out[i] = f[i] * c + f[3 + i] * sn;
            out[3 + i] = -f[i] * sn + f[3 + i] * c;
            out[6 + i] = f[6 + i];
        }
    }

    /** Puff {@code i}'s frame: its right, up (the world's, or the face of the surface it struck) and forward. */
    private static void frame(FrostBreathRenderer.State s, int i, float[] out) {
        float fx = s.lookX[i], fy = s.lookY[i], fz = s.lookZ[i];
        if (s.struck[i]) {
            float nx = s.surfaceX[i], ny = s.surfaceY[i], nz = s.surfaceZ[i], d = fx * nx + fy * ny + fz * nz;
            float tx = fx - nx * d, ty = fy - ny * d, tz = fz - nz * d, l = Mth.sqrt(tx * tx + ty * ty + tz * tz);
            if (l > .1F && nx * nx + ny * ny + nz * nz > .5F) {
                tx /= l; ty /= l; tz /= l;
                set(out, ty * nz - tz * ny, tz * nx - tx * nz, tx * ny - ty * nx, nx, ny, nz, tx, ty, tz);
                return;
            }
        }
        float rx = -fz, rz = fx, l = Mth.sqrt(rx * rx + rz * rz);
        if (l < .2F) { rx = 1; rz = 0; } else { rx /= l; rz /= l; }
        // up = right x forward
        float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy, ul = Mth.sqrt(ux * ux + uy * uy + uz * uz);
        set(out, rx, 0, rz, ux / ul, uy / ul, uz / ul, fx, fy, fz);
    }

    private static void set(float[] o, float rx, float ry, float rz, float ux, float uy, float uz, float fx, float fy, float fz) {
        o[0] = rx; o[1] = ry; o[2] = rz; o[3] = ux; o[4] = uy; o[5] = uz; o[6] = fx; o[7] = fy; o[8] = fz;
    }

    private static float distance(FrostBreathRenderer.State s, int a, int b) {
        float dx = s.x[a] - s.x[b], dy = s.y[a] - s.y[b], dz = s.z[a] - s.z[b];
        return Mth.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
