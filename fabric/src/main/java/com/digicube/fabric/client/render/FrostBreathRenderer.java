package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.FrostBreath;
import com.digicube.fabric.client.model.NativeModelGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a breath of puffs ({@link FrostBreath}) as a stream of solid glowing blocks: the boxes of its effect model
 * (Howling Blaster's approved flame), each whole and rigid at {@link #PIXEL} blocks a model pixel, so nothing is ever
 * stretched however the stream bends (a piece stretched between puffs reads as a bar across the stream).
 *
 * <p>Every puff carries its own blocks: the flame's cross-section for how far out along the flame it is (the throat's
 * narrow ones near the mouth, the broad ones partway out, the thin ones toward the end), with its core and now and then
 * a tongue or an edge sheet, and a trail of the same behind it along its own flight, as long as the gap back to the
 * next puff along that flight. A steady stream's trails meet, so the flame is one body; a swept one's point each its own
 * way and it opens into separate streaks, as gas thrown by a swinging nozzle does, never a band bent between puffs. The
 * blocks turn along their puff's flow with the art's top kept up, or lie flat on the surface the puff struck. Toward the
 * end puffs carry the flame's tips and the oldest carries all of them ahead of it. A slow wave travels along the flame
 * and each puff jostles by its own; embers leave some puffs and drift off; blocks shrink as their puffs die; the head
 * tapers, and so does the tail of a flame that has left the mouth. Each face's shade by its direction goes into its vertex
 * colour, drawn full-bright ({@link SolidGlow}).
 *
 * <p>The effect model's parts: {@code hb_flow_NN} sections in order out from the mouth (each holding {@code hb_body_NN},
 * {@code hb_core_NN} and {@code hb_tongue_NN_k}), {@code hb_tip_k}, {@code hb_edge_k} sheets and {@code hb_ember_k}.
 */
public final class FrostBreathRenderer {
    /** Blocks per model pixel: about the character's own pixel, so the stream stays slender. */
    public static final float PIXEL = .025F;
    /** Model pixels between the blocks of a puff's trail. */
    static final float STEP = 8;
    /** The longest trail a puff draws behind it (blocks): a puff far ahead of the next is a torn stream. */
    static final float MAX_TRAIL = 1.2F;
    /**
     * The lanes of a trail's blocks, in steps of {@link #LANE_X} and {@link #LANE_Y} pixels: distinct, and no two ever a
     * multiple of the art's quarter pixel apart, so no two faces of a trail's blocks can meet on one plane.
     */
    private static final int[] LANES = {0, 1, -1, 2, -2, 3};
    private static final float LANE_X = .41F, LANE_Y = .43F;
    /** Share of its life after which a puff's blocks shrink away. */
    private static final float DEATH = .75F;
    /** Blocks over which the stream's head, and the tail of a flame that has left the mouth, taper. */
    private static final float HEAD_TAPER = 1.4F, TAIL_TAPER = 1.8F;

    /** One box or sheet of the art: each quad's four vertices as x, y, z, u, v (model px about its anchor), its normal, its size. */
    public record Box(float[][][] quads, float[][] normals, float sizeX, float sizeY, float sizeZ) {
        /** The longest edge (model px). */
        public float longest() { return Math.max(sizeX, Math.max(sizeY, sizeZ)); }
    }

    /** A cross-section of the flame: how far out from the mouth it sits (px), its body, core and tongues. */
    private record Section(float z, Box body, Box core, Box[] tongues) {}

    private final Section[] sections;
    private final Box[] tips, edges, embers;
    private final Identifier texture;
    /** Where the art's tips reach (px out from the mouth), and where puffs start carrying tips and stop carrying sections. */
    private final float end, tipsFrom, sectionsTo;

    public FrostBreathRenderer(String effect) {
        this.texture = Constants.id("textures/entity/digimon/" + effect + ".png");
        var mesh = NativeModelGeometry.mesh(Constants.id("models/entity/" + effect + ".mesh.json"));
        Map<String, NativeModelGeometry.Part> parts = new HashMap<>();
        for (var part : mesh.parts()) parts.put(part.name(), part);
        List<Section> found = new ArrayList<>();
        for (int i = 0; parts.containsKey(String.format("hb_flow_%02d", i)); i++) {
            String id = String.format("%02d", i);
            float[] flow = parts.get("hb_flow_" + id).pose();
            // a section's boxes about the flame's axis at the section: the flow's own small offsets kept, its place along dropped
            Box body = box(parts.get("hb_body_" + id), flow[0], flow[1], false);
            Box core = box(parts.get("hb_core_" + id), flow[0], flow[1], false);
            List<Box> tongues = new ArrayList<>();
            for (int k = 0; k < 3; k++) if (parts.containsKey("hb_tongue_" + id + "_" + k)) tongues.add(box(parts.get("hb_tongue_" + id + "_" + k), flow[0], flow[1], false));
            found.add(new Section(-flow[2], body, core, tongues.toArray(Box[]::new)));
        }
        this.sections = found.toArray(Section[]::new);
        this.tips = list(parts, "hb_tip_", true);
        this.edges = list(parts, "hb_edge_", true);
        this.embers = list(parts, "hb_ember_", false);
        Section last = sections[sections.length - 1];
        float reach = last.z();
        for (String name : parts.keySet()) if (name.startsWith("hb_tip_")) reach = Math.max(reach, -parts.get(name).pose()[2] + size(parts.get(name))[2] / 2);
        this.end = reach;
        this.sectionsTo = last.z() + last.body().sizeZ() / 2;
        this.tipsFrom = last.z() - 2;
    }

    private static Box[] list(Map<String, NativeModelGeometry.Part> parts, String prefix, boolean lateral) {
        List<Box> out = new ArrayList<>();
        for (int k = 0; parts.containsKey(prefix + k); k++) {
            float[] pose = parts.get(prefix + k).pose();
            out.add(box(parts.get(prefix + k), lateral ? pose[0] : 0, lateral ? pose[1] : 0, true));
        }
        return out.toArray(Box[]::new);
    }

    private static float[] size(NativeModelGeometry.Part part) {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var quad : part.quads()) for (float[] v : quad.vertices()) for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], v[a]); max[a] = Math.max(max[a], v[a]); }
        return new float[]{max[0] - min[0], max[1] - min[1], max[2] - min[2]};
    }

    /** A part's quads about an anchor: its pivot's offset within the section (or {@code ox, oy}), and its own turn when {@code turned}. */
    private static Box box(NativeModelGeometry.Part part, float ox, float oy, boolean turned) {
        float[] pose = part.pose();
        float px = turned ? 0 : pose[0], py = turned ? 0 : pose[1], pz = turned ? 0 : pose[2];
        var quads = part.quads();
        float[][][] out = new float[quads.length][4][];
        float[][] normals = new float[quads.length][];
        for (int q = 0; q < quads.length; q++) {
            for (int i = 0; i < 4; i++) {
                float[] v = quads[q].vertices()[i];
                float[] r = turned ? turn(v, pose) : v;
                out[q][i] = new float[]{r[0] + ox + px, r[1] + oy + py, r[2] + pz, v[3], v[4]};
            }
            normals[q] = turned ? turn(quads[q].normal(), pose) : quads[q].normal().clone();
        }
        float[] s = size(part);
        return new Box(out, normals, s[0], s[1], s[2]);
    }

    /** A model part's own rotation (x, then y, then z, as a model part turns) applied to a point. */
    private static float[] turn(float[] v, float[] pose) {
        float x = v[0], y = v[1], z = v[2];
        float c = Mth.cos(pose[3]), s = Mth.sin(pose[3]);
        float y1 = y * c - z * s, z1 = y * s + z * c;
        c = Mth.cos(pose[4]); s = Mth.sin(pose[4]);
        float x2 = x * c + z1 * s, z2 = -x * s + z1 * c;
        c = Mth.cos(pose[5]); s = Mth.sin(pose[5]);
        return new float[]{x2 * c - y1 * s, x2 * s + y1 * c, z2};
    }

    /**
     * Receives each box of the flame. {@code frame} holds the box's right, up and forward axes (nine numbers, valid only
     * during the call); a model pixel (x, y, z) of the box lands at the anchor minus {@code (right x + up y + forward z) * scale}.
     */
    public interface Sink {
        void box(Box box, float x, float y, float z, float[] frame, float scale, int color, boolean twoSided);
    }

    /** What one breath looks like this frame: its puffs, oldest first, relative to the entity (blocks). */
    public static final class State {
        public int count;
        public float[] x = new float[48], y = new float[48], z = new float[48], age = new float[48], radius = new float[48];
        public float[] lookX = new float[48], lookY = new float[48], lookZ = new float[48];
        public float[] surfaceX = new float[48], surfaceY = new float[48], surfaceZ = new float[48];
        public int[] seed = new int[48];
        public boolean[] struck = new boolean[48];
        public int life = 16;
        public float drag = .9F;
        /** Quads built at submit: four vertices of x, y, z, u, v, then the normal and the colour's bits (24 numbers each). */
        private float[] quads = new float[0];
        private int quadCount;

        public void clear() { count = 0; }

        private void grow() {
            int n = x.length * 2;
            x = Arrays.copyOf(x, n); y = Arrays.copyOf(y, n); z = Arrays.copyOf(z, n); age = Arrays.copyOf(age, n);
            radius = Arrays.copyOf(radius, n); lookX = Arrays.copyOf(lookX, n); lookY = Arrays.copyOf(lookY, n); lookZ = Arrays.copyOf(lookZ, n);
            surfaceX = Arrays.copyOf(surfaceX, n); surfaceY = Arrays.copyOf(surfaceY, n); surfaceZ = Arrays.copyOf(surfaceZ, n);
            seed = Arrays.copyOf(seed, n); struck = Arrays.copyOf(struck, n);
        }

        /** Adds a puff at (px, py, pz) of radius r and age a, its flame pointing along look, lying on surface once it has struck. */
        public void add(double px, double py, double pz, float r, float a, int s, boolean hit, float lx, float ly, float lz, float sx, float sy, float sz) {
            if (count == x.length) grow();
            x[count] = (float) px; y[count] = (float) py; z[count] = (float) pz; radius[count] = r; age[count] = a; seed[count] = s; struck[count] = hit;
            lookX[count] = lx; lookY[count] = ly; lookZ[count] = lz; surfaceX[count] = sx; surfaceY[count] = sy; surfaceZ[count] = sz;
            count++;
        }

        private void put(Box box, float bx, float by, float bz, float[] f, float scale, int color, boolean twoSided) {
            int needed = (quadCount + box.quads().length * (twoSided ? 2 : 1)) * 24;
            if (needed > quads.length) quads = Arrays.copyOf(quads, Math.max(needed, quads.length * 2));
            for (int q = 0; q < box.quads().length; q++) {
                float[] n = box.normals()[q];
                float nx = -(f[0] * n[0] + f[3] * n[1] + f[6] * n[2]), ny = -(f[1] * n[0] + f[4] * n[1] + f[7] * n[2]), nz = -(f[2] * n[0] + f[5] * n[1] + f[8] * n[2]);
                for (int side = 0; side < (twoSided ? 2 : 1); side++) {
                    int o = quadCount++ * 24;
                    for (int i = 0; i < 4; i++) {
                        float[] v = box.quads()[q][side == 0 ? i : 3 - i];
                        quads[o++] = bx - (f[0] * v[0] + f[3] * v[1] + f[6] * v[2]) * scale;
                        quads[o++] = by - (f[1] * v[0] + f[4] * v[1] + f[7] * v[2]) * scale;
                        quads[o++] = bz - (f[2] * v[0] + f[5] * v[1] + f[8] * v[2]) * scale;
                        quads[o++] = v[3];
                        quads[o++] = v[4];
                    }
                    float sign = side == 0 ? 1 : -1;
                    quads[o++] = nx * sign; quads[o++] = ny * sign; quads[o++] = nz * sign;
                    quads[o] = Float.intBitsToFloat(shaded(color, nx * sign, ny * sign, nz * sign));
                }
            }
        }

        private void write(PoseStack.Pose pose, VertexConsumer vertices) {
            for (int q = 0; q < quadCount; q++) {
                int o = q * 24;
                float nx = quads[o + 20], ny = quads[o + 21], nz = quads[o + 22];
                int color = Float.floatToRawIntBits(quads[o + 23]);
                for (int i = 0; i < 4; i++, o += 5) {
                    vertices.addVertex(pose, quads[o], quads[o + 1], quads[o + 2]).setColor(color).setUv(quads[o + 3], quads[o + 4])
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
                }
            }
        }
    }

    /**
     * A face's colour: up faces full, sides a little darker (north and south lighter than east and west), under faces
     * darker still, as the art is lit, so the flame's blocks stay readable while it glows.
     */
    static int shaded(int color, float nx, float ny, float nz) {
        float b = .84F + .16F * ny + .04F * (Math.abs(nz) - Math.abs(nx));
        int r = Math.min(255, Math.round((color >> 16 & 255) * b)), g = Math.min(255, Math.round((color >> 8 & 255) * b)), bl = Math.min(255, Math.round((color & 255) * b));
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** Fills {@code state} from the breath this client flies, interpolated to {@code partial}, relative to {@code origin}. */
    public static void extract(FrostBreath breath, State state, double ox, double oy, double oz, float partial) {
        state.clear();
        if (breath == null) return;
        state.life = breath.spec().life();
        state.drag = breath.spec().drag();
        for (var p : breath.puffs()) {
            double px = Mth.lerp(partial, p.px, p.x), py = Mth.lerp(partial, p.py, p.y), pz = Mth.lerp(partial, p.pz, p.z);
            state.add(px - ox, py - oy, pz - oz, breath.radius(p, partial), p.age + partial, p.seed, p.struck,
                    p.lookX, p.lookY, p.lookZ, p.surfaceX, p.surfaceY, p.surfaceZ);
        }
    }

    public void submit(State s, PoseStack pose, SubmitNodeCollector collector, float ageInTicks) {
        if (s.count == 0) return;
        s.quadCount = 0;
        place(s, ageInTicks, s::put);
        if (s.quadCount == 0) return;
        collector.submitCustomGeometry(pose, SolidGlow.type(texture), s::write);
    }

    /** Lays every box of the flame for this frame's puffs and hands each to {@code sink}. */
    public void place(State s, float time, Sink sink) {
        int n = s.count;
        if (n == 0) return;
        // how far along the train each puff is from the newest (a gap longer than a trail is a torn stream)
        float[] fromTail = new float[n];
        for (int i = n - 2; i >= 0; i--) {
            float d = distance(s, i, i + 1);
            fromTail[i] = fromTail[i + 1] + (d > MAX_TRAIL * 2 ? 0 : d);
        }
        float total = fromTail[0];
        // a train still leaving the mouth has no tail to thin
        boolean attached = s.age[n - 1] < 1.05F;
        var puff = new Puff(s, time, sink, fallbackRight(s), total, attached);
        for (int i = n - 1; i >= 0; i--) {
            float[] f = puff.frame(i);
            // the trail: back along the puff's own flight as far as the next puff is behind it along that flight
            float trail = 0;
            if (i < n - 1) {
                float gap = (s.x[i] - s.x[i + 1]) * f[6] + (s.y[i] - s.y[i + 1]) * f[7] + (s.z[i] - s.z[i + 1]) * f[8];
                trail = Mth.clamp(gap, 0, MAX_TRAIL);
            }
            if (!puff.begin(i, fromTail[i])) continue;
            int m = Math.min(LANES.length, Math.max(1, Mth.ceil(trail / (STEP * PIXEL))));
            for (int j = 0; j < m; j++) {
                float u = j / (float) m, back = trail * u;
                float age = i < n - 1 ? Mth.lerp(u, s.age[i], s.age[i + 1]) : s.age[i];
                puff.lay(j, s.x[i] - f[6] * back, s.y[i] - f[7] * back, s.z[i] - f[8] * back, age);
            }
        }
        // the head of the stream: every tip, ahead of its oldest puff
        float t = s.age[0] / s.life, g = t < DEATH ? 1 : Math.max(0, 1 - (t - DEATH) / (1 - DEATH));
        if (g > .02F && tips.length > 0) {
            float[] f = puff.frame(0);
            for (int k = 0; k < tips.length; k++) {
                float ahead = tips[k].sizeZ() * (.35F + .65F * ((s.seed[0] + k * 3) % 4) / 3F) * PIXEL * g;
                sink.box(tips[k], s.x[0] + f[6] * ahead, s.y[0] + f[7] * ahead, s.z[0] + f[8] * ahead, f,
                        PIXEL * g * (.8F + .2F * Mth.sin(time * 1.7F + k)), 0xFFFFFFFF, false);
            }
        }
    }

    private static float distance(State s, int a, int b) {
        float dx = s.x[a] - s.x[b], dy = s.y[a] - s.y[b], dz = s.z[a] - s.z[b];
        return Mth.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The right to use where a flame points straight up or down: the first puff from the mouth that does not. */
    private static float[] fallbackRight(State s) {
        for (int i = s.count - 1; i >= 0; i--) {
            float rx = -s.lookZ[i], rz = s.lookX[i], l = Mth.sqrt(rx * rx + rz * rz);
            if (l > .2F) return new float[]{rx / l, 0, rz / l};
        }
        return new float[]{1, 0, 0};
    }

    /** A deterministic spread of a puff's seed and a block's place in its trail, for each block's own look. */
    static int hash(int a, int b) {
        int x = (a ^ 0x9e3779b9) * 0x85ebca6b ^ (b + 0x632be5ab) * 0xc2b2ae35;
        x ^= x >>> 13;
        x *= 0x27d4eb2f;
        return (x ^ x >>> 15) & 0x7fffffff;
    }

    /** Lays the blocks of the puffs (sections, cores, tongues, sheets, tips and embers) for one frame. */
    private final class Puff {
        private final State s;
        private final float time, total;
        private final boolean attached;
        private final Sink sink;
        private final float[] right, frame = new float[9];
        private final float grow, reach;

        Puff(State s, float time, Sink sink, float[] right, float total, boolean attached) {
            this.s = s; this.time = time; this.sink = sink; this.right = right; this.total = total; this.attached = attached;
            this.grow = 1 - (float) Math.pow(s.drag, s.life);
            this.reach = sections[0].body().sizeZ() / 2;
        }

        /** How far out along the art a puff of this age sits in a steady breath (px): its own flight, scaled to the art. */
        float along(float age) {
            return end * (1 - (float) Math.pow(s.drag, Math.max(0, age))) / grow;
        }

        /** Puff {@code i}'s frame: its right, up (the world's, or the face of the surface it struck) and forward. */
        float[] frame(int i) {
            float fx = s.lookX[i], fy = s.lookY[i], fz = s.lookZ[i];
            if (s.struck[i]) {
                float nx = s.surfaceX[i], ny = s.surfaceY[i], nz = s.surfaceZ[i], d = fx * nx + fy * ny + fz * nz;
                float tx = fx - nx * d, ty = fy - ny * d, tz = fz - nz * d, l = Mth.sqrt(tx * tx + ty * ty + tz * tz);
                if (l > .1F && nx * nx + ny * ny + nz * nz > .5F) {
                    tx /= l; ty /= l; tz /= l;
                    set(ty * nz - tz * ny, tz * nx - tx * nz, tx * ny - ty * nx, nx, ny, nz, tx, ty, tz);
                    return frame;
                }
            }
            float rx = -fz, ry = 0, rz = fx, l = Mth.sqrt(rx * rx + rz * rz);
            if (l < .2F) { rx = right[0]; ry = right[1]; rz = right[2]; } else { rx /= l; rz /= l; }
            // up = right x forward
            float ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx, ul = Mth.sqrt(ux * ux + uy * uy + uz * uz);
            set(rx, ry, rz, ux / ul, uy / ul, uz / ul, fx, fy, fz);
            return frame;
        }

        private void set(float rx, float ry, float rz, float ux, float uy, float uz, float fx, float fy, float fz) {
            frame[0] = rx; frame[1] = ry; frame[2] = rz; frame[3] = ux; frame[4] = uy; frame[5] = uz; frame[6] = fx; frame[7] = fy; frame[8] = fz;
        }

        /** The puff being laid, and what its whole trail shares: its size, sway, colour and look. */
        private int i, own, color;
        private float k, ox, oy, tail;

        /**
         * Starts puff {@code i}, {@code fromTail} blocks along the train from its newest: its size (shrinking as it dies, at
         * the stream's head, and at the tail of a flame that has left the mouth), a slow wave travelling along the flame and
         * its own jostle, all shared by its trail. False when it has died away.
         */
        boolean begin(int i, float fromTail) {
            this.i = i;
            float age = s.age[i], t = age / s.life, out = along(age);
            float head = Mth.clamp((total - fromTail) / HEAD_TAPER, 0, 1);
            tail = attached ? 1 : Mth.clamp(fromTail / TAIL_TAPER, 0, 1);
            float g = (t < DEATH ? 1 : Math.max(0, 1 - (t - DEATH) / (1 - DEATH))) * (.45F + .55F * head) * (.3F + .7F * tail)
                    * (s.struck[i] ? 1.15F : 1);
            if (g <= .02F) return false;
            own = hash(s.seed[i], 0);
            k = PIXEL * g * (1 + .05F * Mth.sin(time * 1.9F + own));
            // (in the blocks' own pixels)
            float wave = 1.1F * Math.min(1, out / 90), jostle = .3F + 1.6F * out / end;
            ox = wave * Mth.sin(out * .07F - time * .55F) + jostle * Mth.sin(own * 2.39F + age * .83F);
            oy = wave * .8F * Mth.sin(out * .05F - time * .41F + 1.3F) + jostle * .7F * Mth.sin(own * 1.71F + age * .67F);
            // cooling toward the end of its life, a little deeper blue
            float cool = Mth.clamp((t - .45F) / .55F, 0, 1);
            color = 0xFF000000 | Math.round(255 * (1 - .12F * cool)) << 16 | Math.round(255 * (1 - .08F * cool)) << 8 | 255;
            return true;
        }

        /** Block {@code j} of the puff's trail at (x, y, z), of the age the flame has there. The frame is the puff's, already set. */
        void lay(int j, float x, float y, float z, float age) {
            int sd = hash(s.seed[i], j);
            float out = along(age);
            float[] f = frame;
            // each block of a trail keeps its own lane beside the others, never a multiple of the art's quarter pixel
            // apart, so overlapping blocks of one trail never share a face's plane (they would flicker)
            float lx = ox + LANE_X * LANES[j], ly = oy + LANE_Y * LANES[(j * 5 + 1) % LANES.length];
            // the newest blocks start at the mouth instead of straddling it, and a loose tail's last ones start at its end
            float shift = Math.max(Math.max(0, 1 - age), 1 - tail) * reach * PIXEL;
            float bx = x + f[6] * shift - (f[0] * lx + f[3] * ly) * k;
            float by = y + f[7] * shift - (f[1] * lx + f[4] * ly) * k;
            float bz = z + f[8] * shift - (f[2] * lx + f[5] * ly) * k;
            if (out < sectionsTo) {
                // the section for how far out it is, sometimes a neighbour's
                Section section = sections[Mth.clamp(Math.round((out - sections[0].z()) / step() + ((sd % 5) - 2) * .22F), 0, sections.length - 1)];
                sink.box(section.body(), bx, by, bz, f, k, color, false);
                if (sd % 5 != 4) sink.box(section.core(), bx, by, bz, f, k, color, false);
                if (section.tongues().length > 0 && sd % 6 == 1) sink.box(section.tongues()[(sd >>> 2) % section.tongues().length], bx, by, bz, f, k, color, false);
                if (edges.length > 0 && out > 55 && sd % 17 == 7 && (Mth.floor(time / 2) + sd) % 3 != 0)
                    sink.box(edges[(sd >>> 4) % edges.length], bx, by, bz, f, k, color, true);
            }
            if (tips.length > 0 && out >= tipsFrom && sd % 3 == 0) sink.box(tips[(sd >>> 2) % tips.length], bx, by, bz, f, k, color, false);
            // an ember leaves some puffs and drifts off, falling a little and shrinking
            if (j == 0 && embers.length > 0 && own % 6 == 1) {
                float e = age - (3 + own % 5);
                if (e > 0 && e < 8) {
                    float way = own * 2.39996F, off = s.radius[i] + .06F + .08F * e;
                    float ex = -Mth.cos(way) * off, ey = -Mth.sin(way) * off * .8F - .015F * e * e;
                    sink.box(embers[own % embers.length], bx + f[0] * ex + f[3] * ey, by + f[1] * ex + f[4] * ey, bz + f[2] * ex + f[5] * ey, f,
                            PIXEL * (1 - e / 8) * 1.2F, 0xFFFFFFFF, false);
                }
            }
        }

        /** Pixels between the art's sections. */
        private float step() {
            return sections.length > 1 ? (sections[sections.length - 1].z() - sections[0].z()) / (sections.length - 1) : 1;
        }
    }
}
