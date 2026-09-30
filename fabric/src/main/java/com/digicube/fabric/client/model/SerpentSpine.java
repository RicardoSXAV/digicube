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
 * ({@code rest_wave}, {@code rest_pace}). Near the head ({@code neck} blocks) the body keeps the head's own heading and
 * pitch, so the head turns and dives before the body follows; out of the water the neck lies over the ground behind the
 * head, whatever face the head is climbing or lowering itself down. No point sinks into the ground under it, and the sway
 * gives way where it would push the body into a wall. Every link is then laid from the head toward its point, keeping its
 * length, bending no more than a joint can and turned up over an edge it would cut, with its back kept up.
 */
public final class SerpentSpine {
    /**
     * The chain: {@code path} leads from the root to the part its first link hangs in, {@code chain} is the links, each
     * the next one's parent, and {@code tip} the part at the end of the last. Distances in blocks, the rest pace in
     * blocks a tick.
     */
    public record Spine(List<String> path, List<String> chain, String tip, float neck, float wavelength, float landWave,
                        float swimWave, float dashWave, float restWave, float slip, float restPace) {}

    /** Per-entity state, kept by the renderer for as long as the entity is drawn: the drawn body's own trail. */
    public static final class State {
        final SerpentTrail trail;
        BlockGetter level;
        private float age = Float.NaN;

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
    /** Blocks a point may be lifted onto the ground under it: a step, never a wall. */
    private static final double LIFT_REACH = 1.1;
    /** Blocks along the body over which its sway grows from nothing (the neck holds still), and where it starts. */
    private static final float SWAY_FROM = 1, SWAY_OVER = 2.5F;

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
     * Lays the chain along the drawn body's trail, {@code state.spineWeight} of the way from the clips' pose. Run after the
     * pose, before anything hangs from the body.
     */
    public static void apply(ModelPart root, DigimonRenderState state, Spine spine, Rig rig) {
        var data = state.serpent;
        if (spine == null || rig == null || data == null || data.trail.head() == null || state.spineWeight <= 0) return;
        float s = state.modelScale;
        int n = rig.links.length + 1;
        // The chain as the clips pose it, in model px (y down, the front -z), and each link's turn.
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
        // In blocks, the body's frame: left, up, forward.
        double[] left = new double[n], up = new double[n], forward = new double[n];
        var origin = new Vector3f();
        for (int i = 0; i < n; i++) {
            matrices[i].getTranslation(origin);
            left[i] = s * origin.x / 16;
            up[i] = s * (24 - origin.y) / 16;
            forward[i] = -s * origin.z / 16;
        }
        // How far behind the head along the ground each point lies, and its sway: the wave keeps its place on the ground
        // (the trail's clock), and each link keeps its length across it.
        double[] behind = new double[n], sway = new double[n];
        behind[0] = Math.max(0, -forward[0]);
        double clock = data.trail.clock(), wave = state.spineWave;
        for (int i = 1; i < n; i++) {
            double chord = Math.hypot(forward[i] - forward[i - 1], left[i] - left[i - 1]);
            double estimate = behind[i - 1] + Math.abs(forward[i] - forward[i - 1]);
            sway[i] = wave * envelope(rig.arc[i] * s, rig.arc[n - 1] * s)
                    * Math.sin(2 * Math.PI * (clock - estimate) / spine.wavelength());
            double side = left[i] + sway[i] - left[i - 1] - sway[i - 1];
            double step = Math.sqrt(Math.max(0, chord * chord - side * side));
            behind[i] = behind[i - 1] + (forward[i] <= forward[i - 1] ? step : -step);
        }
        double[] distances = new double[n];
        for (int i = 0; i < n; i++) distances[i] = Math.max(0, behind[i]);
        // Out of the water the neck lies over the ground behind the head (the trail read level there): whatever face its
        // head is going up or down, the neck rears from, or lies on, the ground it has behind it.
        float water = Math.clamp(state.swimAnimationAmount, 0, 1);
        double level = spine.neck() * (1 - water);
        double[] trailAt = new double[3 * n], tangent = new double[3 * n];
        data.trail.sample(distances, trailAt, tangent, level);
        // The head's own frame (its heading and its dive), and where its feet are drawn.
        double yaw = state.bodyRot * Mth.DEG_TO_RAD, pitch = state.spinePitch * Mth.DEG_TO_RAD;
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), lx = Math.cos(yaw), lz = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        Vec3 head = data.trail.head();
        double hx = state.x, hy = state.y, hz = state.z;
        // Which way the neck unwinds from the head's heading to the trail's (the head turned left of it, or right).
        double[] near = new double[3], nearTangent = new double[3];
        data.trail.sample(new double[]{NECK_READ}, near, nearTangent, level);
        double turned = Mth.wrapDegrees(state.bodyRot - Math.toDegrees(Math.atan2(-(head.x - near[0]), head.z - near[2])));
        // The line the body lies along: its trail's heights, out of the water relaxed over the ground under the body there.
        double[] line = new double[n];
        for (int i = 0; i < n; i++) line[i] = trailAt[3 * i + 1];
        double[] world = new double[3 * n];
        if (data.level != null && water < 1) {
            for (int i = 0; i < n; i++)
                place(i, left[i] + sway[i], up, up, line, forward, distances, trailAt, tangent, spine, fx, fz, lx, lz, cp, sp, head, turned, water, world);
            drape(line, world, tangent, rig.radius, s, data.level);
        }
        // Each point's height over that line. Out of the water the clips' reared neck is stretched or eased to span from the
        // head down to the ground under each point (it rears higher from the foot of a ledge the head is climbing, and lies
        // over the top of one it is lowering itself off); past the neck the body lies as the clips lay it (eased there, its
        // height jumped wherever the ground rose over the head's, and it kinked over every stair).
        double rest = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) rest = Math.min(rest, up[i]);
        double[] over = new double[n];
        for (int i = 0; i < n; i++) {
            double reared = up[0] - rest, stretch = reared < REARED ? 1
                    : Math.clamp((head.y + up[0] - line[i] - rest) / reared, 0, STRETCH_MOST);
            stretch += (1 - stretch) * Mth.smoothstep(Math.clamp(distances[i] / spine.neck(), 0, 1));
            over[i] = up[i] + (rest + (up[i] - rest) * stretch - up[i]) * (1 - water);
        }
        for (int i = 0; i < n; i++) {
            place(i, left[i] + sway[i], up, over, line, forward, distances, trailAt, tangent, spine, fx, fz, lx, lz, cp, sp, head, turned, water, world);
            if (data.level == null) continue;
            double x = world[3 * i], y = world[3 * i + 1], z = world[3 * i + 2];
            // The sway gives way where it would push the body into a wall beside the way its head went.
            if (sway[i] != 0 && SerpentTrail.solid(data.level, x, y, z)) {
                place(i, left[i], up, over, line, forward, distances, trailAt, tangent, spine, fx, fz, lx, lz, cp, sp, head, turned, water, world);
                x = world[3 * i]; y = world[3 * i + 1]; z = world[3 * i + 2];
            }
            // No point sinks into the ground under it: a step it lies across lifts it.
            world[3 * i + 1] = y + SerpentTrail.lift(data.level, x, y, z, rig.radius[i] * s, LIFT_REACH);
        }
        // Model px of each point, through the drawn heading (the model is drawn at the drawn feet).
        var targets = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            double rx = world[3 * i] - hx, ry = world[3 * i + 1] - hy, rz = world[3 * i + 2] - hz;
            double l = rx * lx + rz * lz, f = rx * fx + rz * fz;
            targets[i] = new Vector3f((float) (l * 16 / s), (float) (24 - ry * 16 / s), (float) (-f * 16 / s));
        }
        // Whether the middle line of the body (a point in model px) goes through a block.
        var blocks = data.level;
        Rock rock = blocks == null ? null : p -> {
            double l = p.x * s / 16, f = -p.z * s / 16;
            return SerpentTrail.solid(blocks, hx + l * lx + f * fx, hy + (24 - p.y) * s / 16, hz + l * lz + f * fz);
        };
        aim(rig, parent, targets, rock, Math.clamp(state.spineWeight, 0, 1));
    }

    /** Whether a point of the body's middle line (model px) lies in a block. */
    private interface Rock { boolean at(Vector3f point); }

    /** Blocks behind the head the trail's heading is read at, to tell which way a turned head's neck unwinds. */
    private static final double NECK_READ = 1.5;
    /** Blocks over its trail the ground a body lies on may be (a step), and under it that ground is looked for. */
    private static final double DRAPE_ABOVE = 1.1, DRAPE_BELOW = 2.5;
    /** Passes of the rope over the ground. */
    private static final int DRAPE_PASSES = 6;

    /**
     * The line the body lies along out of the water ({@code line}, its trail's heights to start with: the feet's, stepping
     * sheer up and down every block): relaxed like a rope over the ground under the body ({@code at}, a thickness across
     * it), and never under that ground. Over a step it bridges from edge to edge, up or down a stair it lies along the
     * nosings, and down a face it still hangs as the trail does; the head's own height stays. Laid on the feet's steps the
     * body went sheer up each riser, and aimed at them it folded round the edges.
     */
    private static void drape(double[] line, double[] at, double[] tangent, float[] radius, float scale, BlockGetter level) {
        int n = line.length;
        double[] ground = new double[n];
        for (int i = 0; i < n; i++) {
            double tx = tangent[3 * i], tz = tangent[3 * i + 2], norm = Math.sqrt(tx * tx + tz * tz), r = radius[i] * scale;
            double ax = norm > 1.0E-3 ? tz / norm * r : 0, az = norm > 1.0E-3 ? -tx / norm * r : 0;
            double g = Double.NaN;
            for (int k = -1; k <= 1; k++) {
                double under = groundUnder(level, at[3 * i] + ax * k, line[i], at[3 * i + 2] + az * k);
                if (!Double.isNaN(under) && (Double.isNaN(g) || under > g)) g = under;
            }
            ground[i] = g;
        }
        for (int pass = 0; pass < DRAPE_PASSES; pass++)
            for (int i = 1; i < n; i++) {
                double mean = i + 1 < n ? (line[i - 1] + line[i + 1]) / 2 : line[i - 1];
                line[i] = Double.isNaN(ground[i]) ? mean : Math.max(ground[i], mean);
            }
    }

    /**
     * The top of the ground under (x, z), looked for from DRAPE_ABOVE over {@code y} down to DRAPE_BELOW under it; NaN when
     * there is none there, or the place is in a wall that goes higher (that is no ground to lie on).
     */
    private static double groundUnder(BlockGetter level, double x, double y, double z) {
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int top = Mth.floor(y + DRAPE_ABOVE), bottom = Mth.floor(y - DRAPE_BELOW);
        for (int by = top; by >= bottom; by--) {
            pos.set(Mth.floor(x), by, Mth.floor(z));
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            return by == top ? Double.NaN : by + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return Double.NaN;
    }
    /**
     * Blocks the clips must rear the head over the body's resting height for the neck to be stretched to the ground, and
     * the most it is stretched (the neck's links keep their lengths: past that it only draws straighter).
     */
    private static final double REARED = .2, STRETCH_MOST = 3;

    /**
     * One point of the body in the world: on the trail, or near the head in the head's own frame, blended by the neck. The
     * blend swings each point round the head's feet from where the head's frame puts it to where the trail does (its
     * distance from the feet eased between the two), so a head turned off its trail bends the neck round in an arc; a
     * straight blend drew the neck in through the head and folded it. {@code turned} (degrees the head faces left of its
     * trail) picks the way round when the two lie opposite. Out of the water every point's height is its height over the
     * trail where it lies ({@code over}), the neck's too: a neck reared from where its feet stood went into the ledge its
     * head was lowering itself off, and hung in the air behind a head climbing one. Swimming, the neck dives with the head.
     */
    private static void place(int i, double left, double[] up, double[] over, double[] line, double[] forward, double[] behind,
                              double[] trailAt, double[] tangent, Spine spine, double fx, double fz, double lx, double lz, double cp,
                              double sp, Vec3 head, double turned, float water, double[] out) {
        // along the trail: its horizontal heading there, the point aside and up from the line it lies along
        double tx = tangent[3 * i], tz = tangent[3 * i + 2], norm = Math.sqrt(tx * tx + tz * tz);
        if (norm < 1.0E-6) { tx = fx; tz = fz; norm = 1; }
        tx /= norm; tz /= norm;
        double onX = trailAt[3 * i] + tz * left, onY = line[i] + over[i], onZ = trailAt[3 * i + 2] - tx * left;
        // in the head's frame: the clip's own place, turned by the dive about the chain's first point
        double u = up[i] - up[0], f = forward[i];
        double pu = u * cp - f * sp, pf = u * sp + f * cp;
        double inX = head.x + lx * left + fx * pf, inY = onY + (head.y + up[0] + pu - onY) * water, inZ = head.z + lz * left + fz * pf;
        double w = Mth.smoothstep(Math.clamp(behind[i] / spine.neck(), 0, 1));
        out[3 * i + 1] = inY + (onY - inY) * w;
        double ix = inX - head.x, iz = inZ - head.z, ox = onX - head.x, oz = onZ - head.z;
        double ri = Math.hypot(ix, iz), ro = Math.hypot(ox, oz);
        if (ri < 1.0E-3 || ro < 1.0E-3 || w <= 0 || w >= 1) {
            out[3 * i] = inX + (onX - inX) * w;
            out[3 * i + 2] = inZ + (onZ - inZ) * w;
            return;
        }
        double from = Math.atan2(iz, ix), swing = Mth.wrapDegrees(Math.toDegrees(Math.atan2(oz, ox) - from));
        // The trail's frame lies turned from the head's by minus the head's turn: past a right angle, go that way round.
        if (Math.abs(swing) > 90 && Math.signum(swing) == Math.signum(turned)) swing -= Math.signum(swing) * 360;
        double a = from + Math.toRadians(swing) * w, r = ri + (ro - ri) * w;
        out[3 * i] = head.x + r * Math.cos(a);
        out[3 * i + 2] = head.z + r * Math.sin(a);
    }

    /** The sway's share along the body: none on the neck, growing to the tail. */
    private static double envelope(double along, double length) {
        return Mth.smoothstep(Math.clamp((along - SWAY_FROM) / SWAY_OVER, 0, 1)) * (.6 + .4 * along / length);
    }

    /** Degrees the first link may turn off the clips' own line (the head rides on it), and that any joint bends at most. */
    private static final float FIRST_TURN = 30, MOST_BEND = 45;
    /** Degrees a joint may bend past its bend in the clips, where the clips bend it further than MOST_BEND. */
    private static final float BEND_SLACK = 10;
    /**
     * How near straight up or down (the dot of its line with up) a link's back stops being set by the ground's up and is
     * carried on from the link before it alone (a body up or down a face), and over how much it hands over.
     */
    private static final float STEEP = .95F, STEEP_BAND = .3F;

    /**
     * Puts the first link's origin on the first point, then lays every link from where the one before it ends toward its
     * next point (the tip for the last), keeping its length and bending no more than a joint can: a point the trail puts
     * behind a fold is left for the body to reach round to, where aiming at it folded the body back on itself. Each link's
     * back is carried on from the link before it the shortest way, and on anything short of a face it is turned back up
     * (rolled about its line only as far as the clips roll it, a barrel roll): turning each link the shortest way alone
     * let a body that came round rolled over, belly up. {@code weight} of the way from the clips' pose.
     */
    private static void aim(Rig rig, Matrix4f parent, Vector3f[] targets, Rock rock, float weight) {
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
            if (rock != null) lines[i] = clear(lines[i], joint, length[i], rock);
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
                back.rotateAxis(signedAngle(back, want, line) * share, line.x, line.y, line.z);
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

    /** Degrees a link turns up at most, a step at a time, to clear what it would cut into. */
    private static final float CLEAR_MOST = 40, CLEAR_STEP = 10;

    /**
     * A link along {@code line} from {@code joint}, turned up over whatever its middle line would go through (its end or
     * its middle in a block): the edge of a ledge the trail turns round. Aimed straight at its point round the edge, the
     * body cut through the corner of the rock; turned up, it goes over the edge. Left as it is when nothing within
     * CLEAR_MOST clears it: turned further, the body shot up over a stair and looped back down onto it.
     */
    private static Vector3f clear(Vector3f line, Vector3f joint, float length, Rock rock) {
        if (!cuts(line, joint, length, rock)) return line;
        var axis = new Vector3f(line).cross(0, -1, 0);
        if (axis.lengthSquared() < 1.0E-6F) return line;
        axis.normalize();
        for (float turn = CLEAR_STEP; turn <= CLEAR_MOST; turn += CLEAR_STEP) {
            var tried = new Vector3f(line).rotateAxis(turn * Mth.DEG_TO_RAD, axis.x, axis.y, axis.z);
            if (!cuts(tried, joint, length, rock)) return tried;
        }
        return line;
    }

    private static boolean cuts(Vector3f line, Vector3f joint, float length, Rock rock) {
        return rock.at(new Vector3f(line).mul(length).add(joint)) || rock.at(new Vector3f(line).mul(length / 2).add(joint));
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
