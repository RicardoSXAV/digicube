package com.digicube.fabric.client.model;

import com.digicube.entity.SerpentTrail;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.google.gson.JsonObject;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A serpent's body on a native model ({@code spine} in {@code ground_models.json}): the chain of parts that makes it lies
 * along the path its head took (SerpentTrail, followed every frame by the drawn feet), so a turn bends the whole body
 * through it, a dive draws it down after the head, and it goes only where its head went. The clips still give each
 * point of the chain its height (a neck reared up, a body level in the water) and its spacing; the path gives where
 * along the ground it lies; a wave gives it its sway: laid on the ground, where the body slides through it without
 * slipping aside, as a snake slithers ({@code land_wave}); in the water it runs back along the body faster than the body
 * swims ({@code slip}), more strongly the faster it goes ({@code swim_wave}, {@code dash_wave}), and slowly at rest
 * ({@code rest_wave}, {@code rest_pace}). Near the head ({@code neck} blocks) the body keeps the head's own heading, so the
 * head turns before the body follows.
 * <p>
 * The body is first laid as a fine level line along the trail (a point every tenth of a block of the way the head went,
 * each keeping its place on the ground as the head goes on, {@link SerpentTrail#line}: wherever the head went straight up
 * or down, no distance at all), settled on what lies under it there, the ground (and where the head stood, its edge) or
 * in the water the path the head swam, like a stiff rope ({@link #rope}): as low as the ground lets it, never sharper
 * than a bend of ROPE_RADIUS, so it ramps up a step and over an edge instead of going sheer up a riser or folding round a
 * corner, and bridges a dip; a climb the head gave up stands nothing up. The clips' chain lies over that line, but along
 * the neck over the head's own feet (the neck keeps the clips' pose on the head, easing onto the line by its end), never
 * under the ground, and every dip the heights make is bridged ({@link #bridge}: a head come down a face, the body still on
 * top, reared its neck and dipped it before the body climbed back up behind it, and the chain curled there). Each point
 * of the line eases into a new place over FILTER ticks, so a sudden change of what it lies on is never a jump; the sway
 * gives way, smoothly, where it would push the body into a wall. The joints are then laid on a path that sets out along
 * the clips' own way out of the head and turns toward the line no tighter than the body bends ({@link #pursue}): it rounds
 * a turn of the line too tight for the body and swings wide round a hairpin, where links laid straight onto the line
 * curled and flipped from one way round to the other. Each link keeps its back up, never twisted past ROLL_STEP against
 * the link before it.
 */
public final class SerpentSpine {
    /**
     * The chain: {@code path} leads from the root to the part its first link hangs in, {@code chain} is the links, each
     * the next one's parent, and {@code tip} the part at the end of the last. Distances in blocks, the rest pace in
     * blocks a tick.
     */
    public record Spine(List<String> path, List<String> chain, String tip, float neck, float wavelength, float landWave,
                        float swimWave, float dashWave, float restWave, float slip, float restPace) {}

    /**
     * Per-entity state, kept by the renderer for as long as the entity is drawn: the drawn body's own trail, where each
     * step of its line was laid last frame, and which way round the neck last unwound.
     */
    public static final class State {
        final SerpentTrail trail;
        BlockGetter level;
        private float age = Float.NaN;
        /** Each step of the line (by its step, in its slot) as laid last frame, and when; the trail's lays then. */
        private final long[] steps = new long[SLOTS];
        private final double[] laidX = new double[SLOTS], laidY = new double[SLOTS], laidZ = new double[SLOTS];
        private float laidAt = Float.NaN;
        private int laidOf = -1;
        /** Degrees the head was turned off its trail last frame (NaN before). */
        private double turned = Double.NaN;

        public State(double length) { trail = new SerpentTrail(length); }

        /** The drawn body's trail. */
        public SerpentTrail trail() { return trail; }

        /**
         * Follows the drawn feet (every frame, before the pose): the trail takes the head's new place, and the wave's clock
         * runs on by the head's travel (faster swimming, by the slip) and, in the water, slowly by time at rest.
         */
        public void follow(Vec3 feet, float yaw, float water, float motion, float ageInTicks, Spine spine, BlockGetter level) {
            this.level = level;
            trail.follow(feet, yaw, Mth.lerp(water, 1, 1 / spine.slip()), level, LAY_LIFT);
            if (!Float.isNaN(age) && ageInTicks > age) trail.tick((ageInTicks - age) * spine.restPace() * water * (1 - motion));
            age = ageInTicks;
        }

        /**
         * Eases each point of the line ({@code x, y, z}, by its step on the trail's way) from where it was laid last frame
         * toward where it is laid now, over FILTER ticks on the clock the trail was last followed by, but for those within
         * FILTER_FROM of the head (eased in over FILTER_OVER): a step keeps its place on the ground as the body goes on, so
         * only a change of what it lies on is eased (an edge read the other way, a lift let go, a turn of the neck), never
         * the body's going. Laid again in the same frame it lies as it did; not drawn for FILTER_MOST ticks, it starts afresh.
         */
        void ease(SerpentTrail.Line line, double[] x, double[] y, double[] z) {
            boolean fresh = trail.lays() != laidOf || Float.isNaN(laidAt) || Float.isNaN(age) || age < laidAt || age - laidAt > FILTER_MOST;
            double dt = fresh ? 0 : age - laidAt;
            laidOf = trail.lays();
            laidAt = age;
            double keep = Math.exp(-dt / FILTER);
            for (int j = 1; j < x.length; j++) {
                long step = line.k[j];
                int slot = (int) Math.floorMod(step, (long) SLOTS);
                if (!fresh && steps[slot] == step) {
                    double held = keep * Math.clamp((line.t[j] - FILTER_FROM) / FILTER_OVER, 0, 1);
                    x[j] += (laidX[slot] - x[j]) * held;
                    y[j] += (laidY[slot] - y[j]) * held;
                    z[j] += (laidZ[slot] - z[j]) * held;
                }
                steps[slot] = step;
                laidX[slot] = x[j];
                laidY[slot] = y[j];
                laidZ[slot] = z[j];
            }
        }

        /**
         * Degrees the head faces left of the way its trail runs up to it, read off the level line NECK_READ behind the head
         * (off the trail itself, a face the head came straight down was read: the place right over the head, and no way at
         * all), followed round from last frame's (past half round it keeps to the side it was on: a hair either way of half
         * round swung the neck round the other way, the body with it), up to TURN_MOST either way.
         */
        double turned(float bodyRot, Vec3 head, SerpentTrail.Line line) {
            int near = 1;
            while (near + 1 < line.x.length && line.t[near] < NECK_READ) near++;
            double now = Mth.wrapDegrees(bodyRot - Math.toDegrees(Math.atan2(-(head.x - line.x[near]), head.z - line.z[near])));
            if (!Double.isNaN(turned)) {
                double round = turned + Mth.wrapDegrees(now - turned);
                if (Math.abs(round) <= TURN_MOST) now = round;
            }
            turned = now;
            return now;
        }
    }

    /** The chain's parts found once per model, with each link's offset to the next and its thickness. */
    public static final class Rig {
        final ModelPart[] path;
        final ModelPart[] links;
        final ModelPart tip;
        final Vector3f[] toNext;
        final float[] radius;
        /** Blocks along the chain from the first link, at the model's authored scale 1 (times the model scale when used). */
        final float[] arc;

        Rig(ModelPart[] path, ModelPart[] links, ModelPart tip, Vector3f[] toNext, float[] radius, float[] arc) {
            this.path = path; this.links = links; this.tip = tip; this.toNext = toNext; this.radius = radius; this.arc = arc;
        }
    }

    /** Blocks above the feet a fresh trail is laid clear of blocks at. */
    private static final double LAY_LIFT = .3;
    /** Blocks along the body over which its sway grows from nothing (the neck holds still), and where it starts. */
    private static final float SWAY_FROM = 1, SWAY_OVER = 2.5F;
    /** Blocks between the points of the line the body is laid along, and blocks of it laid past the chain's length. */
    private static final double FINE = .1, LINE_EXTRA = 1.5;
    /** Blocks over a point's feet the water is felt for: the body swims there, along the head's own path. */
    private static final double SWIM_FEEL = .5;
    /** Blocks over its trail the ground a body lies on may be (a step), and under it that ground is looked for. */
    private static final double GROUND_ABOVE = 1.1, GROUND_BELOW = 2.5;
    /** The sharpest bend the line takes over the ground, as a circle's radius in blocks, and the passes that settle it. */
    private static final double ROPE_RADIUS = 1.25;
    private static final int ROPE_PASSES = 48;
    /** The most the bend eases on a slope (a bend measured along the ground reads tighter down a face than it is). */
    private static final double SLOPE_EASE = 8;
    /** Blocks round a point of the line within which the ground bounds how far the rope may lift it. */
    private static final double ROPE_SPAN = 2.5;
    /**
     * Blocks behind the head the rope takes no hold of a point within, and over which its hold grows from nothing: a new
     * point comes in by the head every step (held from the second point on, each newcomer took the hold off the one before
     * it at once, and the body stuttered every step).
     */
    private static final double HOLD_FROM = .15, HOLD_OVER = .3;
    /**
     * Ticks over which a point of the line eases into a new place (not drawn for longer than the most, it starts afresh),
     * and blocks behind the head it is left alone within and eased in over.
     */
    private static final double FILTER = 2, FILTER_MOST = 5, FILTER_FROM = 1, FILTER_OVER = 2;
    /** Steps of the line kept from frame to frame (more than the longest line). */
    private static final int SLOTS = 512;
    /** Degrees off its trail the head's turn is followed round to, the side kept, before it is read the short way again. */
    private static final double TURN_MOST = 270;
    /**
     * The tightest bend of the path the joints are laid on (a circle's radius, blocks), the tighter one it may take by the
     * head, eased out over the first NECK_BEND_OVER blocks (a neck bends up a face right behind a head come down it), how
     * far on along the line it steers for, and its step.
     */
    private static final double PATH_BEND = .72, NECK_BEND = .4, NECK_BEND_OVER = 1.5, PATH_LEAD = .85, PATH_STEP = .05;
    /** Shares of its sway a point tries where the full sway is in a wall. */
    private static final double[] GIVE_TRIES = {1, .5, 0};
    /** Points over which the sway's give-way spreads and blurs, so the body never kinks at a wall's end. */
    private static final int GIVE_SPAN = 5, GIVE_BLUR = 3;
    /** Blocks behind the head the trail's heading is read at, to tell which way a turned head's neck unwinds. */
    private static final double NECK_READ = 1.5;

    private SerpentSpine() {}

    public static Spine read(JsonObject config) {
        if (!config.has("spine")) return null;
        var s = config.getAsJsonObject("spine");
        var path = new ArrayList<String>();
        s.getAsJsonArray("path").forEach(n -> path.add(n.getAsString()));
        var chain = new ArrayList<String>();
        s.getAsJsonArray("chain").forEach(n -> chain.add(n.getAsString()));
        var spine = new Spine(List.copyOf(path), List.copyOf(chain), GsonHelper.getAsString(s, "tip"), GsonHelper.getAsFloat(s, "neck"),
                GsonHelper.getAsFloat(s, "wavelength"), GsonHelper.getAsFloat(s, "land_wave"), GsonHelper.getAsFloat(s, "swim_wave"),
                GsonHelper.getAsFloat(s, "dash_wave"), GsonHelper.getAsFloat(s, "rest_wave"), GsonHelper.getAsFloat(s, "slip"),
                GsonHelper.getAsFloat(s, "rest_pace"));
        if (chain.size() < 2 || !(spine.neck() > 0 && spine.wavelength() > 0 && spine.slip() > 0 && spine.slip() <= 1 && spine.landWave() >= 0
                && spine.swimWave() >= 0 && spine.dashWave() >= 0 && spine.restWave() >= 0 && spine.restPace() >= 0))
            throw new IllegalArgumentException("Invalid spine");
        return spine;
    }

    /** Finds the chain's parts and measures it at rest. */
    public static Rig rig(ModelPart root, Spine spine) {
        if (spine == null) return null;
        var path = new ModelPart[spine.path().size()];
        ModelPart part = root;
        for (int i = 0; i < path.length; i++) path[i] = part = part.getChild(spine.path().get(i));
        var links = new ModelPart[spine.chain().size()];
        for (int i = 0; i < links.length; i++) links[i] = part = part.getChild(spine.chain().get(i));
        ModelPart tip = part.getChild(spine.tip());
        var toNext = new Vector3f[links.length];
        var radius = new float[links.length + 1];
        var arc = new float[links.length + 1];
        for (int i = 0; i < links.length; i++) {
            var next = i + 1 < links.length ? links[i + 1].getInitialPose() : tip.getInitialPose();
            toNext[i] = new Vector3f(next.x(), next.y(), next.z());
            arc[i + 1] = arc[i] + toNext[i].length() / 16;
            // the link's thickness: half its own faces' widest span across it (its children are its own)
            float[] half = {0};
            links[i].visit(new com.mojang.blaze3d.vertex.PoseStack(), (pose, name, index, cube) -> {
                if (name.isEmpty()) half[0] = Math.max(half[0], Math.max(cube.maxX - cube.minX, cube.maxY - cube.minY) / 2);
            });
            radius[i] = half[0] / 16;
        }
        radius[links.length] = radius[links.length - 1];
        return new Rig(path, links, tip, toNext, radius, arc);
    }

    /**
     * Lays the chain along the drawn body's trail, {@code state.spineWeight} of the way from the clips' pose, and leaves
     * where its joints came to lie in {@code state.serpentLine}. Run after the pose, before anything hangs from the body.
     */
    public static void apply(ModelPart root, DigimonRenderState state, Spine spine, Rig rig) {
        var data = state.serpent;
        state.serpentLine = null;
        if (spine == null || rig == null || data == null || data.trail.head() == null || state.spineWeight <= 0) return;
        float s = state.modelScale;
        int n = rig.links.length + 1;
        // The chain as the clips pose it, in model px (y down, the front -z).
        var parent = new Matrix4f();
        transform(root, parent);
        for (ModelPart p : rig.path) transform(p, parent);
        var matrices = new Matrix4f[n];
        var at = new Matrix4f(parent);
        for (int i = 0; i < rig.links.length; i++) {
            transform(rig.links[i], at);
            matrices[i] = new Matrix4f(at);
        }
        matrices[n - 1] = new Matrix4f(at).translate(rig.toNext[n - 2]);
        // In blocks, the body's frame: left, up, forward, and each point's level distance back along the chain.
        double[] left = new double[n], up = new double[n], forward = new double[n], behind = new double[n];
        var origin = new Vector3f();
        for (int i = 0; i < n; i++) {
            matrices[i].getTranslation(origin);
            left[i] = s * origin.x / 16;
            up[i] = s * (24 - origin.y) / 16;
            forward[i] = -s * origin.z / 16;
            behind[i] = i == 0 ? 0 : behind[i - 1] + Math.hypot(forward[i] - forward[i - 1], left[i] - left[i - 1]);
        }
        double arcTotal = rig.arc[n - 1] * s;
        // The head's own heading, and where its feet are drawn.
        double yaw = state.bodyRot * Mth.DEG_TO_RAD;
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), lx = Math.cos(yaw), lz = Math.sin(yaw);
        Vec3 head = data.trail.head();
        double hx = state.x, hy = state.y, hz = state.z;
        // The level line along the trail: the way the head went over the ground from its feet back (SerpentTrail.line); which
        // way the neck unwinds from the head's heading to the trail's (the head turned left of it, or right); and at each
        // point of the line the clips' chain there, the sway and the neck's blend.
        int count = (int) Math.ceil((arcTotal + LINE_EXTRA) / FINE) + 1;
        var line = data.trail.line(FINE, count);
        double turned = data.turned(state.bodyRot, head, line);
        double clock = data.trail.clock(), wave = state.spineWave;
        double[] cl = new double[count], cu = new double[count], cf = new double[count], radius = new double[count];
        double[] sway = new double[count], w = new double[count];
        for (int j = 0; j < count; j++) {
            double t = line.t[j];
            clip(behind, left, up, forward, rig.radius, s, t, j, cl, cu, cf, radius);
            w[j] = Mth.smoothstep(Math.clamp(t / spine.neck(), 0, 1));
            sway[j] = wave * envelope(t, arcTotal) * Math.sin(2 * Math.PI * (clock - t) / spine.wavelength());
        }
        // The sway gives way where it would push the body's side into a wall, spread along the body so it never kinks.
        double[] give = new double[count];
        java.util.Arrays.fill(give, 1);
        var point = new double[2];
        if (data.level != null) {
            boolean any = false;
            for (int j = 0; j < count; j++) {
                if (Math.abs(sway[j]) < 1.0E-4) continue;
                double edge = Math.copySign(radius[j], sway[j]), y = line.y[j] + radius[j];
                for (double f : GIVE_TRIES) {
                    give[j] = f;
                    if (f == 0) break;
                    placeXZ(line.x[j], line.z[j], line.tx[j], line.tz[j], cl[j] + sway[j] * f + edge, cf[j], w[j], fx, fz, lx, lz, head, turned, point);
                    if (!SerpentTrail.solid(data.level, point[0], y, point[1])) break;
                }
                any |= give[j] < 1;
            }
            if (any) spread(give);
        }
        // Where each point lies in plan, and what it lies on there: the ground under it (the highest of three columns across
        // its thickness, and where the head stood, its feet's height: walking along an edge with its box on it, the body
        // a little off it read the ground under the edge and flipped between hanging off it and lying on it), or in the
        // water the path the head swam, no lower than the bed (it may settle down to that). The line's first point is the
        // head's own feet.
        double[] x = new double[count], z = new double[count], ground = new double[count], lowest = new double[count];
        boolean[] swims = new boolean[count];
        var cell = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int j = 0; j < count; j++) {
            placeXZ(line.x[j], line.z[j], line.tx[j], line.tz[j], cl[j] + sway[j] * give[j], cf[j], w[j], fx, fz, lx, lz, head, turned, point);
            x[j] = point[0]; z[j] = point[1];
            if (data.level == null) { ground[j] = lowest[j] = line.y[j]; continue; }
            double ax = line.tz[j] * radius[j], az = -line.tx[j] * radius[j], g = Double.NaN;
            boolean wall = false;
            for (int k = -1; k <= 1; k++) {
                double under = groundUnder(data.level, x[j] + ax * k, line.y[j], z[j] + az * k);
                if (under == Double.POSITIVE_INFINITY) wall = true;
                else if (!Double.isNaN(under) && (Double.isNaN(g) || under > g)) g = under;
            }
            if (!wall && state.boundingBoxWidth > 0 && line.y[j] > g && stood(data.level, line.x[j], line.y[j], line.z[j], state.boundingBoxWidth / 2))
                g = line.y[j];
            swims[j] = data.level.getFluidState(cell.set(x[j], line.y[j] + SWIM_FEEL, z[j])).is(net.minecraft.tags.FluidTags.WATER);
            if (swims[j]) {
                lowest[j] = wall || Double.isNaN(g) ? Double.NEGATIVE_INFINITY : g;
                ground[j] = Math.max(line.y[j], lowest[j]);
            } else ground[j] = lowest[j] = wall ? Double.NaN : g;
        }
        ground[0] = lowest[0] = head.y;
        fill(ground, line.y);
        fill(lowest, ground);
        double[] h = ground.clone();
        rope(h, lowest, swims, ground, line.t);
        // Each point's height: the clips' chain over a base that is the head's own feet at the head, easing onto what the
        // line lies on over the neck (the neck keeps the clips' pose on the head, whatever is under the place it swings
        // to; it stepped at every edge the place slid over), never under what it lies on (near the head, the ground
        // under the way the head went: it keeps its place as the head goes on), every dip bridged.
        double[] y = new double[count];
        for (int j = 0; j < count; j++) y[j] = cu[j] + head.y + (h[j] - head.y) * w[j];
        for (int j = 1; j < count; j++) {
            double under = swims[j] ? lowest[j] : h[j];
            if (!swims[j] && data.level != null) {
                // (no ground under the way there, over the water or off an edge it lowered itself from, none from it)
                double way = groundUnder(data.level, line.x[j], line.y[j], line.z[j]);
                if (!Double.isFinite(way)) way = under;
                under = Double.isFinite(under) ? way + (under - way) * w[j] : way;
            }
            if (Double.isFinite(under)) y[j] = Math.max(y[j], under + radius[j]);
        }
        bridge(y, line.t, swims);
        data.ease(line, x, y, z);
        // The joints, laid on a path from the head that bends no tighter than the body: it sets out along the clips' own way
        // out of the head (the head rides on the first link).
        var world = new double[3 * n];
        double cx = left[1] - left[0], cy = up[1] - up[0], cz = forward[1] - forward[0];
        double[][] path = pursue(x, y, z, new double[]{cx * lx + cz * fx, cy, cx * lz + cz * fz}, arcTotal + 1);
        lay(path[0], path[1], path[2], rig, s, new double[]{cx * lx + cz * fx, cy, cx * lz + cz * fz}, world);
        // A wrap winds the body off its trail and round its prey.
        if (state.wrap.active && state.wrap.shape != null) {
            double[] arcs = new double[n], radii = new double[n];
            for (int i = 0; i < n; i++) { arcs[i] = rig.arc[i] * s; radii[i] = rig.radius[i] * s; }
            SerpentCoil.wind(world, arcs, radii, up[0], state.wrap);
        }
        // Model px of each joint, through the drawn heading (the model is drawn at the drawn feet).
        var targets = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            double rx = world[3 * i] - hx, ry = world[3 * i + 1] - hy, rz = world[3 * i + 2] - hz;
            double l = rx * lx + rz * lz, f = rx * fx + rz * fz;
            targets[i] = new Vector3f((float) (l * 16 / s), (float) (24 - ry * 16 / s), (float) (-f * 16 / s));
        }
        aim(rig, parent, targets, Math.clamp(state.spineWeight, 0, 1));
        // Where the joints came to lie in the world, for the shadow.
        var joints = new double[3 * n];
        var radii = new float[n];
        var laid = new Matrix4f(parent);
        for (int i = 0; i < n; i++) {
            if (i < rig.links.length) transform(rig.links[i], laid);
            else laid.translate(rig.toNext[n - 2]);
            laid.getTranslation(origin);
            double l = s * origin.x / 16, upB = s * (24 - origin.y) / 16, f = -s * origin.z / 16;
            joints[3 * i] = hx + l * lx + f * fx; joints[3 * i + 1] = hy + upB; joints[3 * i + 2] = hz + l * lz + f * fz;
            radii[i] = rig.radius[i] * s;
        }
        state.serpentLine = joints;
        state.serpentRadius = radii;
    }

    private static double square(double v) { return v * v; }

    /**
     * The clips' chain {@code t} blocks (level) behind its first point: its place aside and up, how far forward it lies,
     * and its thickness there, read between the joints (on straight back past the last).
     */
    private static void clip(double[] behind, double[] left, double[] up, double[] forward, float[] radius, float s, double t, int j,
                             double[] cl, double[] cu, double[] cf, double[] r) {
        int n = behind.length;
        if (t >= behind[n - 1]) {
            cl[j] = left[n - 1]; cu[j] = up[n - 1]; cf[j] = forward[n - 1] - (t - behind[n - 1]); r[j] = radius[n - 1] * s;
            return;
        }
        int i = 0;
        while (i + 2 < n && behind[i + 1] <= t) i++;
        double f = Math.clamp((t - behind[i]) / Math.max(1.0E-9, behind[i + 1] - behind[i]), 0, 1);
        cl[j] = left[i] + (left[i + 1] - left[i]) * f;
        cu[j] = up[i] + (up[i + 1] - up[i]) * f;
        cf[j] = forward[i] + (forward[i + 1] - forward[i]) * f;
        r[j] = (radius[i] + (radius[i + 1] - radius[i]) * f) * s;
    }

    /**
     * Where a point of the line lies in plan: on the trail, {@code lateral} blocks aside of it, or near the head in the
     * head's own frame, blended by the neck ({@code w}). The blend swings the point round the head's feet from where the
     * head's frame puts it to where the trail does (its distance from the feet eased between the two), so a head turned
     * off its trail bends the neck round in an arc; a straight blend drew the neck in through the head and folded it.
     * {@code turned} (degrees the head faces left of its trail) picks the way round: every point swings the way nearest
     * the head's own unwinding (minus its turn), all of them alike (each point picking its own nearest way, some went
     * round one side and some the other, and the neck zigzagged).
     */
    private static void placeXZ(double lineX, double lineZ, double tx, double tz, double lateral, double f, double w, double fx, double fz,
                                double lx, double lz, Vec3 head, double turned, double[] out) {
        double onX = lineX + tz * lateral, onZ = lineZ - tx * lateral;
        double inX = head.x + lx * lateral + fx * f, inZ = head.z + lz * lateral + fz * f;
        double ix = inX - head.x, iz = inZ - head.z, ox = onX - head.x, oz = onZ - head.z;
        double ri = Math.hypot(ix, iz), ro = Math.hypot(ox, oz);
        if (ri < 1.0E-3 || ro < 1.0E-3 || w <= 0 || w >= 1) {
            out[0] = inX + (onX - inX) * w;
            out[1] = inZ + (onZ - inZ) * w;
            return;
        }
        double from = Math.atan2(iz, ix), swing = Mth.wrapDegrees(Mth.wrapDegrees(Math.toDegrees(Math.atan2(oz, ox) - from)) + turned) - turned;
        double a = from + Math.toRadians(swing) * w, r = ri + (ro - ri) * w;
        out[0] = head.x + r * Math.cos(a);
        out[1] = head.z + r * Math.sin(a);
    }

    /** Spreads the sway's give-way along the line: the least within GIVE_SPAN points, blurred over GIVE_BLUR. */
    private static void spread(double[] give) {
        int n = give.length;
        double[] low = new double[n];
        for (int j = 0; j < n; j++) {
            double least = 1;
            for (int i = Math.max(0, j - GIVE_SPAN); i <= Math.min(n - 1, j + GIVE_SPAN); i++) least = Math.min(least, give[i]);
            low[j] = least;
        }
        for (int j = 0; j < n; j++) {
            double sum = 0;
            int m = 0;
            for (int i = Math.max(0, j - GIVE_BLUR); i <= Math.min(n - 1, j + GIVE_BLUR); i++) { sum += low[i]; m++; }
            give[j] = sum / m;
        }
    }

    /**
     * The top of the ground under (x, z), looked for from GROUND_ABOVE over {@code y} down to GROUND_BELOW under it: NaN
     * when there is none there, positive infinity when the place is in a wall that goes higher (no ground to lie on).
     */
    private static double groundUnder(BlockGetter level, double x, double y, double z) {
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int top = Mth.floor(y + GROUND_ABOVE), bottom = Mth.floor(y - GROUND_BELOW);
        for (int by = top; by >= bottom; by--) {
            pos.set(Mth.floor(x), by, Mth.floor(z));
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            return by == top ? Double.POSITIVE_INFINITY : by + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return Double.NaN;
    }

    /** Fills the unknown (NaN) points of the ground line from the known ones either side, or from the trail with none. */
    private static void fill(double[] ground, double[] fallback) {
        int n = ground.length, known = -1;
        for (int j = 0; j < n; j++) {
            if (Double.isNaN(ground[j])) continue;
            if (known < j - 1) {
                double from = known < 0 ? ground[j] : ground[known];
                for (int i = known + 1; i < j; i++) ground[i] = known < 0 ? from : from + (ground[j] - from) * (i - known) / (double) (j - known);
            }
            known = j;
        }
        for (int j = known + 1; j < n; j++) ground[j] = known < 0 ? fallback[j] : ground[known];
    }

    /**
     * Settles the line like a stiff rope: every point stays on or over {@code lowest} (the ground under it; in the water
     * the bed, the line starting on the swim's path), and the line bends no tighter than ROPE_RADIUS along it, so a step
     * it comes to is ramped from before it and an edge it goes over is rounded, a dip bridged: a dip's middle rises to the
     * bend, and a point standing up off it (an edge) has its lower neighbour lifted away from it, as far as the higher one,
     * then both alike (lifted both alike, the higher side rippled on along the ground without end); on the ground points
     * only ever rise, and in the water ({@code swims}) one standing up off the bend settles down to it first, as far as the
     * bed. The rope takes hold of each point by its distance behind the head ({@code t}: none within HOLD_FROM, growing over
     * HOLD_OVER): the head's feet bind nothing (pinned low under a ledge the line had no way to meet its bend, and
     * ratcheted up without end), and a point coming in by the head takes no hold off the one before it. No point is lifted
     * over the ground within ROPE_SPAN of it: a lift only rounds an edge or bridges a dip that is there.
     */
    private static void rope(double[] h, double[] lowest, boolean[] swims, double[] ground, double[] t) {
        int n = h.length;
        if (n < 4) return;
        double[] hold = new double[n];
        for (int j = 1; j < n; j++) hold[j] = Mth.smoothstep(Math.clamp((t[j] - HOLD_FROM) / HOLD_OVER, 0, 1));
        // the most a point may be lifted to: the highest ground within ROPE_SPAN of it (a point by the head counted as it is held)
        double[] most = new double[n];
        int span = (int) Math.ceil(ROPE_SPAN / FINE);
        for (int j = 1; j < n; j++) {
            double top = h[j];
            for (int i = Math.max(1, j - span); i <= Math.min(n - 1, j + span); i++) top = Math.max(top, ground[i] - (1 - hold[i]) * HOLD_LIFT);
            most[j] = top;
        }
        for (int pass = 0; pass < ROPE_PASSES; pass++) {
            boolean back = (pass & 1) == 1;
            for (int k = 1; k < n - 1; k++) {
                int i = back ? n - 1 - k : k;
                if (hold[i] <= 0) continue;
                double a = t[i] - t[i - 1], b = t[i + 1] - t[i];
                double slope = (h[i + 1] - h[i - 1]) / (a + b), grade = 1 + slope * slope;
                double tolerance = a * b / ROPE_RADIUS / 2 * Math.min(SLOPE_EASE, grade * Math.sqrt(grade));
                double mid = h[i - 1] + (h[i + 1] - h[i - 1]) * a / (a + b);
                if (h[i] < mid - tolerance) { h[i] += hold[i] * (Math.min(most[i], mid - tolerance) - h[i]); continue; }
                if (h[i] <= mid + tolerance) continue;
                if (swims[i] && h[i] > lowest[i] + 1.0E-9) {
                    // in the water, over the bed: it settles down to the bend first
                    h[i] += hold[i] * (Math.max(lowest[i], mid + tolerance) - h[i]);
                    if (h[i] <= mid + tolerance) continue;
                }
                double need = 2 * (h[i] - mid - tolerance) * hold[i];
                int lo = h[i - 1] <= h[i + 1] ? i - 1 : i + 1, hi = 2 * i - lo;
                double toLo = Math.min(need, h[hi] - h[lo]);
                double rest = need - toLo + raise(h, most, lo, toLo);
                if (rest > 0) raise(h, most, hi, rest / 2 + raise(h, most, lo, rest / 2));
            }
        }
    }

    /** Blocks below the ground a point the rope does not hold yet counts for, in the bound on lifts round it. */
    private static final double HOLD_LIFT = 64;

    /**
     * Bridges every dip of the heights ({@code y} at {@code t} behind the head) no tighter than ROPE_RADIUS, exactly: the
     * lowest heights over them that bend up nowhere sharper (heights less t squared over twice the radius are concave
     * there: the upper concave hull of the points so lowered, raised back). Swimming points neither bear it nor are raised.
     */
    private static void bridge(double[] y, double[] t, boolean[] swims) {
        int n = y.length;
        int[] hull = new int[n];
        int top = 0;
        double twice = 2 * ROPE_RADIUS;
        for (int j = 0; j < n; j++) {
            if (j > 0 && j < n - 1 && swims[j]) continue;
            double fj = y[j] - t[j] * t[j] / twice;
            while (top >= 2) {
                int a = hull[top - 2], b = hull[top - 1];
                double fa = y[a] - t[a] * t[a] / twice, fb = y[b] - t[b] * t[b] / twice;
                // b under the chord from a to j: not on the upper hull
                if ((fb - fa) * (t[j] - t[a]) <= (fj - fa) * (t[b] - t[a])) top--;
                else break;
            }
            hull[top++] = j;
        }
        for (int e = 0; e + 1 < top; e++) {
            int a = hull[e], b = hull[e + 1];
            double fa = y[a] - t[a] * t[a] / twice, fb = y[b] - t[b] * t[b] / twice;
            for (int j = a + 1; j < b; j++) {
                if (swims[j]) continue;
                double lid = fa + (fb - fa) * (t[j] - t[a]) / Math.max(1.0E-9, t[b] - t[a]) + t[j] * t[j] / twice;
                if (y[j] < lid) y[j] = lid;
            }
        }
    }

    /** Whether the head stood at (x, y, z): its box, {@code half} a block either way, on ground as high as its feet. */
    private static boolean stood(BlockGetter level, double x, double y, double z, double half) {
        double under = Double.NEGATIVE_INFINITY;
        for (int cx = -1; cx <= 1; cx += 2) for (int cz = -1; cz <= 1; cz += 2) {
            double u = groundUnder(level, x + cx * (half - 1.0E-3), y, z + cz * (half - 1.0E-3));
            if (Double.isFinite(u)) under = Math.max(under, u);
        }
        return Math.abs(under - y) < STOOD;
    }

    /** Blocks the feet may be off the ground under the head's box and still have stood on it. */
    private static final double STOOD = .02;

    /**
     * The way the body goes from the head: a path PATH_STEP a step that sets out along {@code first} (the clips' own way
     * out of the head) and steers for the line PATH_LEAD blocks on from the nearest place on it it has come to (never back
     * along it), turning no tighter than PATH_BEND (by the head NECK_BEND, eased out over NECK_BEND_OVER). It rounds a
     * turn of the line too tight for the body and swings wide round a hairpin, where links laid straight onto the line
     * curled round its corners, and flipped round one way and the other from frame to frame. Steering for the line no
     * further on than PATH_LEAD, it rounds an edge the line goes over without cutting into the block (steering a block on,
     * it cut a quarter of a block into a wall of logs it went over; lifted wherever it went under the line, it shook).
     */
    private static double[][] pursue(double[] x, double[] y, double[] z, double[] first, double reach) {
        int count = x.length;
        double[] along = along(x, y, z);
        int steps = (int) Math.ceil(reach / PATH_STEP) + 1;
        double[] px = new double[steps], py = new double[steps], pz = new double[steps];
        px[0] = x[0];
        py[0] = y[0];
        pz[0] = z[0];
        double norm = Math.sqrt(first[0] * first[0] + first[1] * first[1] + first[2] * first[2]);
        var way = norm > 1.0E-9 ? new Vector3f((float) (first[0] / norm), (float) (first[1] / norm), (float) (first[2] / norm)) : new Vector3f(0, 0, 1);
        double near = 0;
        double[] point = new double[3];
        for (int i = 1; i < steps; i++) {
            near = Math.max(near + PATH_STEP / 4, nearest(x, y, z, along, near, px[i - 1], py[i - 1], pz[i - 1]));
            at(x, y, z, along, near + PATH_LEAD, point);
            var want = new Vector3f((float) (point[0] - px[i - 1]), (float) (point[1] - py[i - 1]), (float) (point[2] - pz[i - 1]));
            double gone = (i - 1) * PATH_STEP, bend = gone < NECK_BEND_OVER ? NECK_BEND + (PATH_BEND - NECK_BEND) * gone / NECK_BEND_OVER : PATH_BEND;
            if (want.lengthSquared() > 1.0E-12F) way = within(want.normalize(), way, (float) (PATH_STEP / bend)).normalize();
            px[i] = px[i - 1] + way.x * PATH_STEP;
            py[i] = py[i - 1] + way.y * PATH_STEP;
            pz[i] = pz[i - 1] + way.z * PATH_STEP;
        }
        return new double[][]{px, py, pz};
    }

    /** The nearest place to ({@code px, py, pz}) on the line, no nearer the head than {@code from} and a little on, exactly. */
    private static double nearest(double[] x, double[] y, double[] z, double[] along, double from, double px, double py, double pz) {
        int count = x.length;
        double best = Double.MAX_VALUE, bestAt = from, limit = from + 4 * PATH_STEP;
        int k = 0;
        while (k + 1 < count && along[k + 1] <= from) k++;
        for (; k + 1 < count && along[k] <= limit; k++) {
            double span = along[k + 1] - along[k];
            if (span < 1.0E-9) continue;
            double bx = x[k + 1] - x[k], by = y[k + 1] - y[k], bz = z[k + 1] - z[k];
            double lo = Math.max(0, (from - along[k]) / span), hi = Math.min(1, (limit - along[k]) / span);
            if (lo > hi) continue;
            double u = Math.clamp(((px - x[k]) * bx + (py - y[k]) * by + (pz - z[k]) * bz) / (span * span), lo, hi);
            double qx = x[k] + bx * u - px, qy = y[k] + by * u - py, qz = z[k] + bz * u - pz, d = qx * qx + qy * qy + qz * qz;
            if (d < best) { best = d; bestAt = along[k] + u * span; }
        }
        return bestAt;
    }

    /**
     * Lays the joints on the path ({@code x, y, z} from the head): each where the path crosses a link's length from the
     * joint before it, past half a link on from where that one lay on it (solved exactly on each segment, looked for up
     * to three links on; none, the place a link on), the link turned toward it no further than a joint bends (the first
     * no further than FIRST_TURN off {@code first}, the clips' own way out of the head), so every link keeps its length
     * and lies along the path, or rejoins it a link on.
     */
    private static void lay(double[] x, double[] y, double[] z, Rig rig, float scale, double[] first, double[] world) {
        int count = x.length, n = rig.links.length + 1;
        double[] along = along(x, y, z);
        world[0] = x[0];
        world[1] = y[0];
        world[2] = z[0];
        double at = 0;
        double[] point = new double[3];
        double norm = Math.sqrt(first[0] * first[0] + first[1] * first[1] + first[2] * first[2]);
        var was = norm > 1.0E-9 ? new Vector3f((float) (first[0] / norm), (float) (first[1] / norm), (float) (first[2] / norm)) : new Vector3f(0, 0, 1);
        for (int i = 1; i < n; i++) {
            double length = (rig.arc[i] - rig.arc[i - 1]) * scale;
            double jx = world[3 * i - 3], jy = world[3 * i - 2], jz = world[3 * i - 1];
            double from = at + length / 2, to = at + 3 * length, found = Double.NaN;
            at(x, y, z, along, from, point);
            if (square(point[0] - jx) + square(point[1] - jy) + square(point[2] - jz) >= length * length) found = from;
            else {
                int k = 0;
                while (k + 1 < count && along[k + 1] <= from) k++;
                for (; k + 1 < count && along[k] <= to && Double.isNaN(found); k++) {
                    double span = along[k + 1] - along[k];
                    if (span < 1.0E-9) continue;
                    double bx = x[k + 1] - x[k], by = y[k + 1] - y[k], bz = z[k + 1] - z[k];
                    double ox = x[k] - jx, oy = y[k] - jy, oz = z[k] - jz;
                    double qa = bx * bx + by * by + bz * bz, qb = 2 * (ox * bx + oy * by + oz * bz), qc = ox * ox + oy * oy + oz * oz - length * length;
                    double disc = qb * qb - 4 * qa * qc;
                    if (disc < 0) continue;
                    double root = Math.sqrt(disc), u0 = Math.max(0, (from - along[k]) / span);
                    for (double u : new double[]{(-qb - root) / (2 * qa), (-qb + root) / (2 * qa)})
                        if (u >= u0 - 1.0E-9 && u <= 1 + 1.0E-9) { found = along[k] + Math.clamp(u, 0, 1) * span; break; }
                }
                if (!Double.isNaN(found) && found > to) found = Double.NaN;
            }
            double reach = Double.isNaN(found) ? at + length : found;
            at(x, y, z, along, reach, point);
            var toward = new Vector3f((float) (point[0] - jx), (float) (point[1] - jy), (float) (point[2] - jz));
            var way = toward.lengthSquared() > 1.0E-12F ? within(toward.normalize(), was, (i == 1 ? FIRST_TURN : MOST_BEND) * Mth.DEG_TO_RAD).normalize()
                    : new Vector3f(was);
            world[3 * i] = jx + way.x * length;
            world[3 * i + 1] = jy + way.y * length;
            world[3 * i + 2] = jz + way.z * length;
            was = way;
            at = reach;
        }
    }

    /** Blocks along a polyline to each of its points. */
    private static double[] along(double[] x, double[] y, double[] z) {
        double[] along = new double[x.length];
        for (int k = 1; k < x.length; k++) along[k] = along[k - 1] + Math.sqrt(square(x[k] - x[k - 1]) + square(y[k] - y[k - 1]) + square(z[k] - z[k - 1]));
        return along;
    }

    /** The place {@code at} blocks along a polyline (run on straight past its end). */
    private static void at(double[] x, double[] y, double[] z, double[] along, double at, double[] out) {
        int n = x.length;
        if (at <= 0 || n == 1) { out[0] = x[0]; out[1] = y[0]; out[2] = z[0]; return; }
        if (at >= along[n - 1]) {
            double dx = x[n - 1] - x[n - 2], dy = y[n - 1] - y[n - 2], dz = z[n - 1] - z[n - 2], d = Math.max(1.0E-9, Math.sqrt(dx * dx + dy * dy + dz * dz));
            double over = at - along[n - 1];
            out[0] = x[n - 1] + dx / d * over; out[1] = y[n - 1] + dy / d * over; out[2] = z[n - 1] + dz / d * over;
            return;
        }
        int lo = 0, hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (along[mid] <= at) lo = mid; else hi = mid;
        }
        double f = (at - along[lo]) / Math.max(1.0E-9, along[hi] - along[lo]);
        out[0] = x[lo] + (x[hi] - x[lo]) * f; out[1] = y[lo] + (y[hi] - y[lo]) * f; out[2] = z[lo] + (z[hi] - z[lo]) * f;
    }

    /** Raises a point of the line by {@code amount}, no higher than {@code most} lets it: what it could not take. */
    private static double raise(double[] h, double[] most, int i, double amount) {
        double taken = Math.clamp(amount, 0, Math.max(0, most[i] - h[i]));
        h[i] += taken;
        return amount - taken;
    }

    /** The sway's share along the body: none on the neck, growing to the tail. */
    private static double envelope(double along, double length) {
        return Mth.smoothstep(Math.clamp((along - SWAY_FROM) / SWAY_OVER, 0, 1)) * (.6 + .4 * Math.min(1, along / length));
    }

    /** Degrees the first link may turn off the clips' own line (the head rides on it), and that any joint bends at most. */
    private static final float FIRST_TURN = 30, MOST_BEND = 40;
    /** Degrees a joint may bend past its bend in the clips, where the clips bend it further than MOST_BEND. */
    private static final float BEND_SLACK = 10;
    /**
     * How near straight up or down (the dot of its line with up) a link's back stops being set by the ground's up and is
     * carried on from the link before it alone (a body up or down a face), and over how much it hands over.
     */
    private static final float STEEP = .95F, STEEP_BAND = .3F;
    /**
     * Degrees a link's back may turn about its line toward up past the link before it's: a body owing a roll after a face
     * or a tight bend pays it back over several links (all at one link, it twisted there, its plates turned askew).
     */
    private static final float ROLL_STEP = 8;

    /**
     * Puts the first link's origin on the first point, then lays every link from where the one before it ends toward its
     * next point (the tip for the last), keeping its length and bending no more than a joint can: a point the line puts
     * behind a fold is left for the body to reach round to, where aiming at it folded the body back on itself. Each link's
     * back is carried on from the link before it the shortest way, and on anything short of a face it is turned back up
     * (rolled about its line only as far as the clips roll it, a barrel roll): turning each link the shortest way alone
     * let a body that came round rolled over, belly up. {@code weight} of the way from the clips' pose.
     */
    private static void aim(Rig rig, Matrix4f parent, Vector3f[] targets, float weight) {
        int links = rig.links.length;
        var first = rig.links[0];
        var local = new Matrix4f(parent).invert().transformPosition(new Vector3f(targets[0]));
        first.x += (local.x - first.x) * weight;
        first.y += (local.y - first.y) * weight;
        first.z += (local.z - first.z) * weight;
        var parentTurn = parent.get3x3(new Matrix3f());
        // The clips' own turn of every link in the model, its line to the next, its back and its length.
        var clipTurn = new Matrix3f[links];
        var clipLine = new Vector3f[links];
        var clipBack = new Vector3f[links];
        var length = new float[links];
        var turn = new Matrix3f(parentTurn);
        for (int i = 0; i < links; i++) {
            var part = rig.links[i];
            turn = new Matrix3f(turn).mul(new Matrix3f().set(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot)));
            clipTurn[i] = turn;
            var step = new Vector3f(rig.toNext[i]).mul(part.xScale, part.yScale, part.zScale);
            length[i] = step.length();
            clipLine[i] = turn.transform(step).normalize();
            clipBack[i] = square(turn.transform(new Vector3f(0, -1, 0)), clipLine[i]);
        }
        // The joints.
        var lines = new Vector3f[links];
        var joint = parent.transformPosition(new Vector3f(first.x, first.y, first.z));
        for (int i = 0; i < links; i++) {
            var want = new Vector3f(targets[i + 1]).sub(joint);
            var from = i == 0 ? clipLine[0] : lines[i - 1];
            var line = want.lengthSquared() > 1.0E-6F ? want.normalize() : new Vector3f(from);
            float bend = i == 0 ? FIRST_TURN : Math.max(MOST_BEND, degrees(clipLine[i - 1], clipLine[i]) + BEND_SLACK);
            lines[i] = within(line, from, bend * Mth.DEG_TO_RAD);
            joint.add(new Vector3f(lines[i]).mul(length[i]));
        }
        // Each link's turn: its line and its back, from the clips' turn of it.
        var above = parentTurn;
        var angles = new Vector3f();
        Vector3f back = null;
        float roll = 0;
        for (int i = 0; i < links; i++) {
            var part = rig.links[i];
            var line = lines[i];
            // carried on from the link before (the first from the clips' own back), the shortest way onto its line
            back = i == 0 ? new Quaternionf().rotationTo(clipLine[0], line).transform(new Vector3f(clipBack[0]))
                    : new Quaternionf().rotationTo(lines[i - 1], line).transform(back);
            back = square(back, line);
            // the clips' roll about the link's line, off its back up (kept from the link before where the clips stand it on end)
            var clipLevel = level(clipLine[i]);
            if (clipLevel != null) roll = signedAngle(clipLevel, clipBack[i], clipLine[i]);
            var level = level(line);
            if (level != null) {
                var want = level.rotateAxis(roll, line.x, line.y, line.z);
                float share = (float) Mth.smoothstep(Math.clamp((STEEP - Math.abs(line.y)) / STEEP_BAND, 0, 1));
                float most = ROLL_STEP * Mth.DEG_TO_RAD * share;
                back.rotateAxis(Math.clamp(signedAngle(back, want, line), -most, most), line.x, line.y, line.z);
            }
            var world = new Matrix3f(line, back, new Vector3f(line).cross(back))
                    .mul(new Matrix3f(clipLine[i], clipBack[i], new Vector3f(clipLine[i]).cross(clipBack[i])).transpose())
                    .mul(clipTurn[i]);
            var aimed = new Quaternionf().setFromNormalized(new Matrix3f(above).transpose().mul(world));
            if (weight < 1) aimed = new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot).slerp(aimed, weight);
            aimed.getEulerAnglesZYX(angles);
            part.setRotation(angles.x, angles.y, angles.z);
            above = new Matrix3f(above).mul(new Matrix3f().set(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot)));
        }
    }

    /** Degrees between two unit vectors. */
    private static float degrees(Vector3f a, Vector3f b) {
        return (float) Math.toDegrees(Math.acos(Mth.clamp(a.dot(b), -1, 1)));
    }

    /** {@code line} (unit) turned toward {@code from} (unit) until it lies at most {@code most} radians off it. */
    private static Vector3f within(Vector3f line, Vector3f from, float most) {
        if (Math.acos(Mth.clamp(line.dot(from), -1, 1)) <= most) return line;
        var axis = new Vector3f(from).cross(line);
        if (axis.lengthSquared() < 1.0E-8F) {
            // straight back on itself: it folds about the up, along the ground
            axis.set(0, -1, 0);
            if (Math.abs(axis.dot(from)) > .9F) axis.set(1, 0, 0);
            axis.sub(new Vector3f(from).mul(axis.dot(from)));
        }
        axis.normalize();
        return new Vector3f(from).rotateAxis(most, axis.x, axis.y, axis.z);
    }

    /** {@code v} made square to the unit {@code axis}, unit length (any square direction when it lies along it). */
    private static Vector3f square(Vector3f v, Vector3f axis) {
        var out = new Vector3f(v).sub(new Vector3f(axis).mul(v.dot(axis)));
        if (out.lengthSquared() < 1.0E-10F) {
            out = Math.abs(axis.y) < .9F ? new Vector3f(0, -1, 0) : new Vector3f(1, 0, 0);
            out.sub(new Vector3f(axis).mul(out.dot(axis)));
        }
        return out.normalize();
    }

    /** The up (model y is down) square to a link along {@code line}, or null when it stands near on end. */
    private static Vector3f level(Vector3f line) {
        if (Math.abs(line.y) > .999F) return null;
        return new Vector3f(0, -1, 0).sub(new Vector3f(line).mul(-line.y)).normalize();
    }

    /** Radians from {@code from} to {@code to} about {@code axis} (all unit, the first two square to the axis). */
    private static float signedAngle(Vector3f from, Vector3f to, Vector3f axis) {
        return (float) Math.atan2(new Vector3f(from).cross(to).dot(axis), from.dot(to));
    }

    private static void transform(ModelPart part, Matrix4f into) {
        into.translate(part.x, part.y, part.z).rotateZYX(part.zRot, part.yRot, part.xRot).scale(part.xScale, part.yScale, part.zScale);
    }
}
