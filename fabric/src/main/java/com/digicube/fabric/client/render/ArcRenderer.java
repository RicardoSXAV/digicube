package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.digimon.AuthoredAttacks;
import com.digicube.entity.ArcDischarge;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.DigimonEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * Draws an electric discharge's bolts ({@link ArcDischarge}) as solid glowing blocks, the way Howling Blaster's flame is
 * drawn ({@link SolidGlow}): each bolt is a zigzag of square rods from where it leaves (the fin's tip, a struck body, the
 * caster's body) to the body it struck (or the ground it earthed in), a pale core with a thinner amber strand winding
 * about it and short amber forks off the main bolt, knuckled with a cube at every bend so no joint gapes. The zigzag is
 * dealt again about every two thirds of a tick, so the bolt flickers, and the rods thin away over its last two ticks.
 * Where a bolt lands a star of short rods bursts; where the main bolt leaves the fin flares. A bolt through water is
 * drawn icy white. Faces carry a shade by direction in their colour ({@link FrostBreathRenderer#shaded}).
 */
public final class ArcRenderer {
    private static final Identifier TEXTURE = Constants.id("textures/entity/digimon/electric_arc.png");
    /** Rod widths by bolt kind (blocks): the main bolt, a jump, through water, from the body, earthed. */
    private static final float[] WIDTH = {.085F, .065F, .05F, .06F, .08F};
    private static final int CORE = 0xFFFFFAD6, AMBER = 0xFFEDB224, WATER_CORE = 0xFFD2F6FF, WATER_EDGE = 0xFF7FD7F5;
    /** Blocks between the bends of a bolt, and how far a bend strays aside at most. */
    private static final float BEND = .42F, JAG = .26F;
    /** Zigzags a tick. */
    private static final float FLICKER = 1.5F;

    /** One frame's bolts, relative to the caster's drawn position. */
    public static final class State {
        public int count, seed, life;
        public float age;
        float[] from = new float[24], to = new float[24];
        int[] kind = new int[8];
        boolean[] struck = new boolean[8];
        float[] quads = new float[0];
        int quadCount;

        void clear() { count = 0; }

        void add(ArcDischarge.Kind k, Vec3 a, Vec3 b, boolean body) {
            if (count == kind.length) {
                kind = Arrays.copyOf(kind, count * 2); struck = Arrays.copyOf(struck, count * 2);
                from = Arrays.copyOf(from, count * 6); to = Arrays.copyOf(to, count * 6);
            }
            from[count * 3] = (float) a.x; from[count * 3 + 1] = (float) a.y; from[count * 3 + 2] = (float) a.z;
            to[count * 3] = (float) b.x; to[count * 3 + 1] = (float) b.y; to[count * 3 + 2] = (float) b.z;
            kind[count] = k.ordinal(); struck[count] = body; count++;
        }
    }

    /**
     * Fills {@code s} from the discharge the entity let go last (none once it has faded): every bolt's ends, the struck
     * bodies followed where they are drawn this frame, relative to the caster at {@code ox, oy, oz}.
     */
    public static void extract(DigimonEntity entity, State s, double ox, double oy, double oz, float partial) {
        s.clear();
        var arc = arcOf(entity);
        if (arc == null) return;
        var strike = entity.clientArc(arc.arc().life());
        if (strike == null) return;
        s.age = entity.clientArcAge(partial);
        s.life = arc.arc().life();
        if (s.age > s.life) return;
        s.seed = strike.seed();
        Vec3 origin = new Vec3(ox, oy, oz);
        var level = entity.level();
        for (var link : strike.links()) {
            Vec3 a;
            if (link.from() == entity.getId()) {
                a = switch (link.kind()) {
                    case MAIN, EARTH -> emitter(entity, arc, partial);
                    case BURST -> entity.getPosition(partial).add(0, entity.getBbHeight() * .45, 0);
                    default -> entity.getPosition(partial).add(0, .2, 0);
                };
            } else {
                Entity e = level.getEntity(link.from());
                if (e == null) continue;
                a = chest(e, partial);
            }
            Entity target = link.to() < 0 ? null : level.getEntity(link.to());
            Vec3 b = target != null ? chest(target, partial) : link.point();
            s.add(link.kind(), a.subtract(origin), b.subtract(origin), target != null);
        }
    }

    /** The discharging move of the entity's species, or null. */
    private static AuthoredAttacks.Definition arcOf(DigimonEntity entity) {
        for (var attack : entity.getSpecies().map(com.digicube.digimon.DigimonSpecies::attacks).orElse(java.util.List.of())) {
            var d = AuthoredAttacks.get(attack);
            if (d != null && d.discharges()) return d;
        }
        return null;
    }

    /** The fin's tip this frame: the move's mouth marker where its clip is (its hit tick once the clip has ended). */
    private static Vec3 emitter(DigimonEntity entity, AuthoredAttacks.Definition d, float partial) {
        double tick = d.attack().hitTick();
        if (entity.getAnimatingAttack() == d.attack() && entity.attackAnimationState.isStarted())
            tick = Math.min(d.attack().durationTicks(), entity.attackAnimationState.getTimeInMillis(entity.tickCount + partial) / 50F);
        return AttackGeometry.world(entity.getPosition(partial), d.motion(entity.isInWater()).sample(tick).mouth(), entity.getAttackYaw(partial));
    }

    private static Vec3 chest(Entity e, float partial) {
        Vec3 p = e.getPosition(partial);
        return p.add(0, Math.min(e.getBbHeight() * .55, 1.4), 0);
    }

    public void submit(State s, PoseStack pose, SubmitNodeCollector collector) {
        if (s.count == 0 || s.age > s.life) return;
        s.quadCount = 0;
        float fade = Mth.clamp((s.life - s.age) / 2F, 0, 1);
        int frame = (int) (s.age * FLICKER);
        for (int i = 0; i < s.count; i++) {
            int k = s.kind[i];
            float w = WIDTH[k] * (.35F + .65F * fade);
            long rng = mix(s.seed, i, frame);
            boolean water = k == ArcDischarge.Kind.WATER.ordinal();
            float ax = s.from[i * 3], ay = s.from[i * 3 + 1], az = s.from[i * 3 + 2];
            float bx = s.to[i * 3], by = s.to[i * 3 + 1], bz = s.to[i * 3 + 2];
            rng = bolt(s, ax, ay, az, bx, by, bz, w, water ? WATER_CORE : CORE, JAG, rng, k == 0 ? 2 : k == 1 ? 1 : 0, water ? WATER_EDGE : AMBER);
            // the thinner strand winding about the core
            rng = bolt(s, ax, ay, az, bx, by, bz, w * .55F, water ? WATER_EDGE : AMBER, JAG * 1.25F, rng, 0, 0);
            // a star of rods where it struck, and the fin's flare where the main bolt left, in its first ticks
            if (s.age < 3.2F) {
                if (s.struck[i] || k == ArcDischarge.Kind.EARTH.ordinal()) rng = star(s, bx, by, bz, .34F * fade + .1F, w * .8F, water ? WATER_CORE : CORE, rng, 7);
                if (k == 0 || k == ArcDischarge.Kind.EARTH.ordinal()) rng = star(s, ax, ay, az, .22F, w * .7F, CORE, rng, 5);
            }
        }
        if (s.quadCount > 0) collector.submitCustomGeometry(pose, SolidGlow.type(TEXTURE), (p, vertices) -> write(s, p, vertices));
    }

    /** A zigzag from a to b; returns the random state. Forks: that many short amber branches off its bends. */
    private long bolt(State s, float ax, float ay, float az, float bx, float by, float bz, float width, int color, float jag,
                      long rng, int forks, int forkColor) {
        float dx = bx - ax, dy = by - ay, dz = bz - az, len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-3F) return rng;
        int n = Math.max(3, (int) Math.ceil(len / BEND));
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
            if (i > 0) rod(s, px, py, pz, x, y, z, width, color);
            if (i > 0 && i < n) cube(s, x, y, z, width * 1.25F, color);
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
            rod(s, x, y, z, mx, my, mz, width * .5F, forkColor);
            rod(s, mx, my, mz, ex, ey, ez, width * .4F, forkColor);
        }
        return rng;
    }

    /** Rods out from a point in random directions: a struck body's burst, the fin's flare. */
    private long star(State s, float x, float y, float z, float reach, float width, int color, long rng, int rays) {
        for (int i = 0; i < rays; i++) {
            rng = next(rng); float a = unit(rng) * Mth.PI; rng = next(rng); float b = unit(rng) * .9F;
            rng = next(rng); float l = reach * (.6F + .4F * (unit(rng) * .5F + .5F));
            float c = Mth.cos(b);
            rod(s, x, y, z, x + Mth.cos(a) * c * l, y + Mth.sin(b) * l, z + Mth.sin(a) * c * l, width, i % 2 == 0 ? color : AMBER);
        }
        cube(s, x, y, z, width * 1.8F, color);
        return rng;
    }

    /** A square rod from p to q, its ends pushed out by half its width so bends close. */
    private void rod(State s, float px, float py, float pz, float qx, float qy, float qz, float w, int color) {
        float dx = qx - px, dy = qy - py, dz = qz - pz, len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4F) return;
        float fx = dx / len, fy = dy / len, fz = dz / len;
        float[] u = perpendicular(fx, fy, fz), v = cross(fx, fy, fz, u);
        float h = w / 2, ext = h;
        float sx = px - fx * ext, sy = py - fy * ext, sz = pz - fz * ext, ex = qx + fx * ext, ey = qy + fy * ext, ez = qz + fz * ext;
        box(s, sx, sy, sz, ex, ey, ez, u, v, h, color);
    }

    private void cube(State s, float x, float y, float z, float w, int color) {
        float h = w / 2;
        box(s, x, y - h, z, x, y + h, z, new float[]{1, 0, 0}, new float[]{0, 0, 1}, h, color);
    }

    /** A box along a to b with its cross-section spanned by u and v (half width h): six faces. */
    private void box(State s, float ax, float ay, float az, float bx, float by, float bz, float[] u, float[] v, float h, int color) {
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
        quad(s, c[0], c[1], c[2], c[3], color);
        quad(s, c[7], c[6], c[5], c[4], color);
        for (int j = 0; j < 4; j++) {
            int k = (j + 1) % 4;
            quad(s, c[j], c[4 + j], c[4 + k], c[k], color);
        }
    }

    /** A face, both ways round (the glow pipeline culls back faces, and a rod may be seen from any side). */
    private void quad(State s, float[] a, float[] b, float[] c, float[] d, int color) {
        float nx = (b[1] - a[1]) * (d[2] - a[2]) - (b[2] - a[2]) * (d[1] - a[1]);
        float ny = (b[2] - a[2]) * (d[0] - a[0]) - (b[0] - a[0]) * (d[2] - a[2]);
        float nz = (b[0] - a[0]) * (d[1] - a[1]) - (b[1] - a[1]) * (d[0] - a[0]);
        float nl = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0E-9F) return;
        nx /= nl; ny /= nl; nz /= nl;
        face(s, a, b, c, d, nx, ny, nz, color);
        face(s, d, c, b, a, -nx, -ny, -nz, color);
    }

    private void face(State s, float[] a, float[] b, float[] c, float[] d, float nx, float ny, float nz, int color) {
        int o = s.quadCount * 16;
        if (o + 16 > s.quads.length) s.quads = Arrays.copyOf(s.quads, Math.max(256, s.quads.length * 2));
        for (float[] p : new float[][]{a, b, c, d}) { s.quads[o++] = p[0]; s.quads[o++] = p[1]; s.quads[o++] = p[2]; }
        s.quads[o++] = nx; s.quads[o++] = ny; s.quads[o++] = nz;
        s.quads[o] = Float.intBitsToFloat(FrostBreathRenderer.shaded(color, Math.abs(nx), Math.abs(ny), Math.abs(nz)));
        s.quadCount++;
    }

    private static float[] perpendicular(float x, float y, float z) {
        float ax = Math.abs(x) < .9F ? 1 : 0, ay = ax == 1 ? 0 : 1;
        float[] u = cross(x, y, z, new float[]{ax, ay, 0});
        float l = Mth.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        return new float[]{u[0] / l, u[1] / l, u[2] / l};
    }

    private static float[] cross(float x, float y, float z, float[] u) {
        return new float[]{y * u[2] - z * u[1], z * u[0] - x * u[2], x * u[1] - y * u[0]};
    }

    private static long mix(int seed, int link, int frame) {
        long h = seed * 0x9E3779B97F4A7C15L + link * 0xC2B2AE3D27D4EB4FL + frame * 0x165667B19E3779F9L;
        return next(next(h));
    }

    private static long next(long x) {
        x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
        return x;
    }

    /** A number in [-1, 1) from the random state. */
    private static float unit(long x) {
        return ((x >>> 40) & 0xFFFFFF) / (float) 0x800000 - 1;
    }

    /** Writes the frame's quads: every rod face at full brightness, the texture's white texel under it. */
    private static void write(State s, PoseStack.Pose pose, VertexConsumer vertices) {
        for (int q = 0; q < s.quadCount; q++) {
            int o = q * 16;
            float nx = s.quads[o + 12], ny = s.quads[o + 13], nz = s.quads[o + 14];
            int color = Float.floatToRawIntBits(s.quads[o + 15]);
            for (int i = 0; i < 4; i++, o += 3) {
                vertices.addVertex(pose, s.quads[o], s.quads[o + 1], s.quads[o + 2]).setColor(color).setUv(.5F, .5F)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
            }
        }
    }
}
