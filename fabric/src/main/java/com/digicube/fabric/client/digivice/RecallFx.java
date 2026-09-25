package com.digicube.fabric.client.digivice;

import com.digicube.fabric.client.evolution.EvolutionMesh;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * The recall's light, as quads for the additive beacon shader (u in [0,1): a beam profile across the quad, bright
 * thin core and soft skirt; u in [2,3): a round soft glow; u in [4,5): a square with lit edges, the mod's pixel
 * "data"). A shooting star: a tapered two-layer streak along the flown route, a glinting four-point head, and
 * stardust shed along the way that drifts, falls and twinkles out. Every vertex carries its own colour so the streak
 * can fade along its length. Stateless: everything is a function of time, so the offline review draws the same.
 */
public final class RecallFx {
    private RecallFx() {}
    public record Quad(float[] xyz, float[] uv, int[] argb) {}

    static final int TRAIL = 34;
    /** The streak remembers this share of the flight, so a slower star keeps a long tail. */
    static final float TRAIL_SPAN = .35F, DUST_STEP = .009F, DUST_LIFE = .8F;

    /** World-pass light, in coordinates relative to the recipient's own first-person camera. */
    public static List<Quad> world(RecallFlight.Plan plan, RecallFlight.View view, RecallFlight.Pose held, float seconds) {
        return world(plan, view, held, seconds, view.eye(), view.orientation());
    }
    /**
     * World-pass light for the flight {@code view} steers, seen by a camera at {@code eye} turned by {@code camera}
     * (a third-person camera, another player's): relative to {@code eye}, turned toward it.
     */
    public static List<Quad> world(RecallFlight.Plan plan, RecallFlight.View view, RecallFlight.Pose held, float seconds,
                                   Vec3 eye, Quaternionf camera) {
        var quads = new ArrayList<Quad>();
        var journey = plan.journey();
        boolean far = !journey.nearby();
        // The wake: a column of light and a ring on the ground where the device lay.
        if (!far) {
            float pulse = (seconds - journey.departure() + .15F) / .65F;
            if (pulse > 0 && pulse < 1) {
                float grow = easeOut(pulse), alpha = (float)Math.sin(Math.PI * Math.min(1, pulse * 1.6)) * (1 - pulse);
                pillar(quads, plan.source(), eye, 1.3F + .6F * grow, 3F * grow + .5F, 0xffc94a, .75F * alpha);
                pillar(quads, plan.source(), eye, .35F + .15F * grow, 3.4F * grow + .5F, 0xfffbe8, alpha);
                billboard(quads, plan.source(), eye, camera, .25F + .5F * grow, 2, 0xffd35a, .6F * (1 - pulse));
                for (int i = 0; i < 12; i++) {
                    double angle = i * Math.PI / 6 + .4;
                    var ring = plan.source().add(Math.cos(angle) * (.12 + .75 * grow), .05 + .08 * grow, Math.sin(angle) * (.12 + .75 * grow));
                    billboard(quads, ring, eye, camera, .04F * (1 - .5F * grow), 4, i % 3 == 0 ? 0x9fe6ff : 0xffe49b, 1 - pulse);
                }
            }
        }
        // A long real flight (a lake seen from a hill) is drawn at the distant comet's size, a short hop smaller.
        float wide = wide(plan);
        float start = far ? journey.flightAt() : journey.departure() + journey.lift() * .35F;
        float step = journey.flight() * TRAIL_SPAN / TRAIL, span = TRAIL * step;
        float end = journey.arriveAt() + span;
        if (seconds < start || seconds > end + DUST_LIFE) return quads;
        // After the catch the streak runs on into the hand and is gone: its points past the landing sit at the hand.
        float fade = RecallMotion.smooth((seconds - start) / .15F) * (1 - RecallMotion.smooth((seconds - journey.arriveAt()) / (.6F * span)));
        if (fade > 0) {
            var points = new ArrayList<Vec3>();
            for (int i = 0; i < TRAIL; i++) {
                float at = seconds - i * step;
                if (at < start) break;
                points.add(RecallFlight.displayed(RecallFlight.sample(plan, view, held, Math.min(at, journey.arriveAt())), view));
            }
            // Body of the tail: soft glows along it, widest at the head, then the streak's skirt and white-hot core.
            for (int i = 0; i < points.size(); i += 2) {
                float taper = 1 - i / (float)TRAIL;
                billboard(quads, points.get(i), eye, camera, mix(.42F, .62F, wide) * taper, 2, blend(0xff8a2a, 0xffd35a, taper), .32F * taper * fade);
            }
            ribbon(quads, points, eye, mix(1.1F, 1.7F, wide), 0xffe9a8, 0xff9a36, .9F * fade);
            ribbon(quads, points, eye, mix(.32F, .5F, wide), 0xfffff6, 0xffd070, fade);
            // The head: a glow behind the device, and in the far flight a white core with a turning four-point glint.
            if (seconds < journey.arriveAt() && !points.isEmpty()) {
                var head = points.getFirst();
                var behind = head.add(head.subtract(eye).normalize().scale(.25));
                float u = (seconds - journey.flightAt()) / journey.flight();
                float bloom = far ? 1 - .5F * RecallMotion.smooth(u / .6F) : 1;
                billboard(quads, behind, eye, camera, mix(.8F, 1F, wide) * bloom, 2, 0xffc94a, .6F * fade);
                if (!far) billboard(quads, behind, eye, camera, .38F, 2, 0xfff1c0, .55F * fade);
                if (far) billboard(quads, head, eye, camera, .2F * bloom + .04F, 2, 0xfffff4, fade);
                float twinkle = .75F + .25F * (float)Math.sin(seconds * 19);
                float length = mix(.75F, 1.2F, wide) * twinkle * (far ? bloom : 1);
                glint(quads, far ? head : behind, eye, camera, seconds * 1.4F, length, .13F, 0xfffbe8, .9F * fade);
                glint(quads, far ? head : behind, eye, camera, seconds * 1.4F + (float)Math.PI / 4, length * .5F, .1F, 0xffe7a0, .6F * fade);
            }
        }
        dust(quads, plan, view, held, seconds, start, eye, camera);
        return quads;
    }

    /** Stardust shed along the route: each grain is a pure function of its birth, so nothing is stored. */
    private static void dust(List<Quad> quads, RecallFlight.Plan plan, RecallFlight.View view, RecallFlight.Pose held,
                             float seconds, float start, Vec3 eye, Quaternionf camera) {
        var journey = plan.journey();
        boolean far = !journey.nearby();
        float wide = wide(plan);
        int first = Math.max(0, (int)Math.ceil((seconds - DUST_LIFE - start) / DUST_STEP));
        int last = (int)Math.floor((Math.min(seconds, journey.arriveAt()) - start) / DUST_STEP);
        for (int k = first; k <= last; k++) {
            float born = start + k * DUST_STEP, age = seconds - born;
            if (age < 0 || age >= DUST_LIFE) continue;
            long h = hash(k * 0x9E3779B97F4A7C15L + (far ? 7 : 3));
            float r1 = unit(h), r2 = unit(h >>> 11), r3 = unit(h >>> 22), r4 = unit(h >>> 33), r5 = unit(h >>> 44);
            var origin = RecallFlight.displayed(RecallFlight.sample(plan, view, held, born), view);
            double theta = r1 * Math.PI * 2, z = r2 * 2 - 1, across = Math.sqrt(1 - z * z);
            double speed = mix(.3F, .5F, wide) + .9 * r3;
            var velocity = new Vec3(across * Math.cos(theta), z * .6 + .35, across * Math.sin(theta)).scale(speed);
            float slow = age * (1 - .45F * age);
            var p = origin.add(velocity.scale(slow)).add(0, -.55 * age * age, 0);
            float life = 1 - age / DUST_LIFE;
            float size = (.016F + .03F * r4) * (float)Math.pow(life, .7) * mix(1, 1.3F, wide);
            float alpha = life * (.55F + .45F * (float)Math.sin(age * 38 + r5 * 6));
            int rgb = r4 < .5F ? 0xffd35a : r4 < .82F ? 0xfff4d6 : 0x8fe3ff;
            billboard(quads, p, eye, camera, size, r5 < .7F ? 4 : 2, rgb, alpha);
        }
    }

    /** The catch, in the held device's frame (roughly facing the camera): flash, glint and a ring of data. */
    public static List<Quad> burst(float since) {
        var quads = new ArrayList<Quad>();
        if (since < 0 || since >= .5F) return quads;
        float p = since / .5F, out = easeOut(p), fall = (1 - p) * (1 - p);
        flat(quads, 0, 0, .12F, .07F + .16F * out, 2, 0xffe08a, .9F * fall);
        flat(quads, 0, 0, .121F, .03F + .03F * out, 2, 0xfffff6, fall);
        flatGlint(quads, .12F, since * 3, .32F * (1 - .4F * p), .03F, 0xfffbe8, fall);
        flatGlint(quads, .12F, since * 3 + (float)Math.PI / 4, .16F * (1 - .4F * p), .025F, 0xffe7a0, .7F * fall);
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8 + .2;
            float reach = (.04F + .26F * out) * (i % 2 == 0 ? 1 : .72F);
            flat(quads, (float)Math.cos(angle) * reach, (float)Math.sin(angle) * reach, .122F, .012F * (1 - .6F * p), 4,
                    i % 4 == 0 ? 0x8fe3ff : i % 3 == 0 ? 0xfff4c4 : 0xffd653, 1 - p);
        }
        return quads;
    }

    /** For the offline review, which draws one colour per face: each quad at its mean colour. */
    public static List<EvolutionMesh.Face> faces(List<Quad> quads) {
        var faces = new ArrayList<EvolutionMesh.Face>(quads.size());
        for (var q : quads) {
            float[] v = new float[32];
            int a = 0, r = 0, g = 0, b = 0;
            for (int i = 0; i < 4; i++) {
                v[i * 8] = q.xyz()[i * 3]; v[i * 8 + 1] = q.xyz()[i * 3 + 1]; v[i * 8 + 2] = q.xyz()[i * 3 + 2];
                v[i * 8 + 3] = q.uv()[i * 2]; v[i * 8 + 4] = q.uv()[i * 2 + 1]; v[i * 8 + 7] = 1;
                int c = q.argb()[i];
                a += c >>> 24; r += c >> 16 & 255; g += c >> 8 & 255; b += c & 255;
            }
            faces.add(new EvolutionMesh.Face(v, (a / 4) << 24 | (r / 4) << 16 | (g / 4) << 8 | b / 4));
        }
        return faces;
    }

    // ------------------------------------------------------------------ shapes
    /** A camera-facing strip through the points, head first, tapering to nothing at the tail. */
    private static void ribbon(List<Quad> quads, List<Vec3> points, Vec3 eye, float width, int headRgb, int tailRgb, float alpha) {
        int n = points.size();
        if (n < 2) return;
        var sides = new Vec3[n];
        Vec3 previous = new Vec3(0, 1, 0);
        for (int i = 0; i < n; i++) {
            var tangent = points.get(Math.max(0, i - 1)).subtract(points.get(Math.min(n - 1, i + 1)));
            var across = tangent.cross(points.get(i).subtract(eye));
            if (across.lengthSqr() < 1e-10) across = previous; else across = across.normalize();
            previous = across;
            float taper = (float)Math.pow(1 - i / (float)TRAIL, 1.5);
            sides[i] = across.scale(width * .5 * taper);
        }
        for (int i = 0; i + 1 < n; i++) {
            var a = points.get(i); var b = points.get(i + 1);
            if (a.distanceToSqr(b) < 1e-8) continue;
            float ta = 1 - i / (float)TRAIL, tb = 1 - (i + 1) / (float)TRAIL;
            int ca = colour(blend(tailRgb, headRgb, ta), alpha * ta * ta), cb = colour(blend(tailRgb, headRgb, tb), alpha * tb * tb);
            quads.add(quad(eye, a.subtract(sides[i]), a.add(sides[i]), b.add(sides[i + 1]), b.subtract(sides[i + 1]),
                    new float[]{0, 0, 1, 0, 1, 0, 0, 0}, ca, ca, cb, cb));
        }
    }
    /** A vertical beam turned toward the eye, fading upward (the beam profile fades as v nears 1). */
    private static void pillar(List<Quad> quads, Vec3 base, Vec3 eye, float width, float height, int rgb, float alpha) {
        var toEye = eye.subtract(base);
        var across = new Vec3(toEye.z, 0, -toEye.x);
        across = across.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : across.normalize();
        var side = across.scale(width * .5);
        var top = base.add(0, height, 0);
        int c = colour(rgb, alpha);
        quads.add(quad(eye, base.subtract(side), base.add(side), top.add(side), top.subtract(side), new float[]{0, 0, 1, 0, 1, 1, 0, 1}, c, c, c, c));
    }
    private static void billboard(List<Quad> quads, Vec3 centre, Vec3 eye, Quaternionf camera, float size, float mode, int rgb, float alpha) {
        if (alpha <= .004F || size <= 0) return;
        var right = camera.transform(new Vector3f(size, 0, 0));
        var up = camera.transform(new Vector3f(0, size, 0));
        var r = new Vec3(right.x, right.y, right.z); var u = new Vec3(up.x, up.y, up.z);
        int c = colour(rgb, alpha);
        quads.add(quad(eye, centre.subtract(r).subtract(u), centre.add(r).subtract(u), centre.add(r).add(u), centre.subtract(r).add(u),
                new float[]{mode, 0, mode + 1, 0, mode + 1, 1, mode, 1}, c, c, c, c));
    }
    /** Two thin beams crossing at the centre, turned by {@code angle} in the screen plane. */
    private static void glint(List<Quad> quads, Vec3 centre, Vec3 eye, Quaternionf camera, float angle, float length, float width, int rgb, float alpha) {
        if (alpha <= .004F) return;
        for (int k = 0; k < 2; k++) {
            double a = angle + k * Math.PI / 2;
            var along = camera.transform(new Vector3f((float)Math.cos(a) * length * .5F, (float)Math.sin(a) * length * .5F, 0));
            var across = camera.transform(new Vector3f((float)-Math.sin(a) * width * .5F, (float)Math.cos(a) * width * .5F, 0));
            var l = new Vec3(along.x, along.y, along.z); var w = new Vec3(across.x, across.y, across.z);
            int c = colour(rgb, alpha);
            // Beam profile across the width, and v kept low so the ends do not clip; the tips fade by colour.
            int tip = colour(rgb, 0);
            quads.add(quad(eye, centre.subtract(w), centre.add(w), centre.add(w).add(l), centre.subtract(w).add(l),
                    new float[]{0, 0, 1, 0, 1, 0, 0, 0}, c, c, tip, tip));
            quads.add(quad(eye, centre.subtract(w), centre.add(w), centre.add(w).subtract(l), centre.subtract(w).subtract(l),
                    new float[]{0, 0, 1, 0, 1, 0, 0, 0}, c, c, tip, tip));
        }
    }
    private static void flat(List<Quad> quads, float x, float y, float z, float r, float mode, int rgb, float alpha) {
        if (alpha <= .004F) return;
        int c = colour(rgb, alpha);
        quads.add(new Quad(new float[]{x - r, y - r, z, x + r, y - r, z, x + r, y + r, z, x - r, y + r, z},
                new float[]{mode, 0, mode + 1, 0, mode + 1, 1, mode, 1}, new int[]{c, c, c, c}));
    }
    private static void flatGlint(List<Quad> quads, float z, float angle, float length, float width, int rgb, float alpha) {
        if (alpha <= .004F) return;
        int c = colour(rgb, alpha), tip = colour(rgb, 0);
        for (int k = 0; k < 4; k++) {
            double a = angle + k * Math.PI / 2;
            float lx = (float)Math.cos(a) * length * .5F, ly = (float)Math.sin(a) * length * .5F;
            float wx = (float)-Math.sin(a) * width * .5F, wy = (float)Math.cos(a) * width * .5F;
            quads.add(new Quad(new float[]{-wx, -wy, z, wx, wy, z, wx + lx, wy + ly, z, -wx + lx, -wy + ly, z},
                    new float[]{0, 0, 1, 0, 1, 0, 0, 0}, new int[]{c, c, tip, tip}));
        }
    }
    private static Quad quad(Vec3 origin, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float[] uv, int ca, int cb, int cc, int cd) {
        float[] xyz = new float[12];
        Vec3[] corners = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            xyz[i * 3] = (float)(corners[i].x - origin.x); xyz[i * 3 + 1] = (float)(corners[i].y - origin.y); xyz[i * 3 + 2] = (float)(corners[i].z - origin.z);
        }
        return new Quad(xyz, uv, new int[]{ca, cb, cc, cd});
    }

    private static int colour(int rgb, float alpha) { return Math.clamp(Math.round(alpha * 255), 0, 255) << 24 | rgb & 0xffffff; }
    private static int blend(int a, int b, float p) {
        int rgb = 0;
        for (int shift : new int[]{0, 8, 16}) rgb |= Math.round(((a >> shift) & 255) * (1 - p) + ((b >> shift) & 255) * p) << shift;
        return rgb;
    }
    /** 0 for a hop of a dozen blocks or less, 1 for a flight of 60 or more and for a distant device. */
    private static float wide(RecallFlight.Plan plan) {
        if (!plan.journey().nearby()) return 1;
        return RecallMotion.smooth((float)(plan.source().distanceTo(plan.held0()) - 12) / 48);
    }
    private static float mix(float a, float b, float w) { return a + (b - a) * w; }
    private static float easeOut(float p) { p = 1 - Math.clamp(p, 0, 1); return 1 - p * p * p; }
    private static long hash(long x) {
        x ^= x >>> 33; x *= 0xff51afd7ed558ccdL; x ^= x >>> 33; x *= 0xc4ceb9fe1a85ec53L; x ^= x >>> 33;
        return x;
    }
    private static float unit(long h) { return (h & 0x7ff) / 2048F; }
}
