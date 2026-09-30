package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * Seadramon's serpent body as the compiled NativeGroundModel draws it (SerpentSpine):
 * <ul>
 * <li>every link of the chain keeps its length, however the path bends and the body sways;</li>
 * <li>round a bend the whole body lies along the path its head took, within its sway;</li>
 * <li>on land the sway keeps its place on the ground as the body slides forward through it (no slipping aside), and in
 *     the water it runs back along the body;</li>
 * <li>diving, the body follows the head down its path;</li>
 * <li>the rider's seat, near the head, stays where the clips put it whatever the body behind does;</li>
 * <li>a wrap (no weight on the trail) leaves the clips' own pose alone;</li>
 * <li>a head turned round on the spot, a tight turn and a knock aside neither roll a link belly up, nor bend a joint past
 *     what a joint can, nor fold the body back onto itself;</li>
 * <li>up a ledge and down it no point of the body goes into the rock, and it neither folds nor stretches;</li>
 * <li>on a stair, walked down, off it, across and up, it lies along the nosings: no point in a step, no fold.</li>
 * </ul>
 */
public final class NativeSeadramonRegressionTest {
    private static int checks;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** A part's pivot in the entity's frame at yaw 0 (blocks: +x left, y up, +z forward, feet at 0). */
    private static Vec3 pivot(ModelPart root, List<String> path, float scale) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(0, 0, 0, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    private static List<Vec3> chain(ModelPart root, SerpentSpine.Spine spine, float scale) {
        var points = new ArrayList<Vec3>();
        var path = new ArrayList<>(spine.path());
        for (String link : spine.chain()) { path.add(link); points.add(pivot(root, path, scale)); }
        path.add(spine.tip());
        points.add(pivot(root, path, scale));
        return points;
    }

    /**
     * A trail for a head that went along {@code path} (x, z of a distance back from the head, the head at the origin
     * facing +z at the end), sampled finely as the frames draw it; y from {@code height}.
     */
    private static SerpentSpine.State walked(double length, SerpentSpine.Spine spine, DoubleFunction<double[]> path, float water, double from, double to) {
        return walked(length, spine, path, water, from, to, null);
    }

    /** {@link #walked(double, SerpentSpine.Spine, DoubleFunction, float, double, double)} over {@code level}'s blocks. */
    private static SerpentSpine.State walked(double length, SerpentSpine.Spine spine, DoubleFunction<double[]> path, float water, double from, double to,
                                             net.minecraft.world.level.BlockGetter level) {
        var data = new SerpentSpine.State(length);
        int steps = (int) Math.ceil((to - from) / .05);
        for (int i = 0; i <= steps; i++) {
            double back = to - (to - from) * i / steps;
            double[] at = path.apply(back), ahead = path.apply(back - .01);
            float yaw = (float) Math.toDegrees(Math.atan2(-(ahead[0] - at[0]), ahead[2] - at[2]));
            data.follow(new Vec3(at[0], at[1], at[2]), yaw, water, 1, i, spine, level);
        }
        return data;
    }

    private static void pose(NativeGroundModel model, DigimonRenderState state, SerpentSpine.State data, float wave, float water, float pitch) {
        state.serpent = data;
        state.x = data.trail().head().x; state.y = data.trail().head().y; state.z = data.trail().head().z;
        state.bodyRot = 0; state.xRot = pitch; state.yRot = 0;
        state.spineWeight = 1; state.spineWave = wave; state.spinePitch = pitch;
        state.swimAnimationAmount = water; state.swimMotionAmount = 1; state.swimAnimationPhase = 7;
        model.setupAnim(state);
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon"));
        float scale = species.body().modelScale();
        double length = species.body().length();
        var definition = NativeGroundModel.definitions().get(species.id());
        var spine = definition.spine();
        check(spine != null && species.body().serpent() != null, "Seadramon is a serpent: a spine in the catalog, a serpent on its sheet");
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = scale;

        // The clips' own chain, and its links' lengths.
        state.serpent = null; state.spineWeight = 0; state.swimAnimationAmount = 0; state.groundAnimationAmount = 0;
        model.setupAnim(state);
        var clip = chain(root, spine, scale);
        double[] links = new double[clip.size() - 1];
        for (int i = 0; i < links.length; i++) links[i] = clip.get(i + 1).distanceTo(clip.get(i));
        Vec3 clipSeat = model.riderOffset(state).add(state.mountAnchor);

        // Round a bend of radius 3 (a turn of 150 degrees behind the head), on land and swimming.
        double radius = 3, turn = Math.toRadians(150);
        DoubleFunction<double[]> bend = back -> {
            if (back < 1) return new double[]{0, 0, -back};
            double a = Math.min(turn, (back - 1) / radius), rest = Math.max(0, back - 1 - turn * radius);
            return new double[]{radius - radius * Math.cos(a) + rest * Math.sin(turn), 0, -1 - radius * Math.sin(a) - rest * Math.cos(turn)};
        };
        double worstLink = 0, worstOff = 0, worstSeat = 0;
        for (float water : new float[]{0, 1}) {
            float wave = water > 0 ? spine.swimWave() : spine.landWave();
            var data = walked(length, spine, bend, water, 0, length + 3);
            pose(model, state, data, wave, water, 0);
            var body = chain(root, spine, scale);
            for (int i = 0; i < links.length; i++) worstLink = Math.max(worstLink, Math.abs(body.get(i + 1).distanceTo(body.get(i)) - links[i]));
            // each point lies on the path within its sway, taking the neck's own heading near the head into account
            for (int i = 0; i < body.size(); i++) {
                Vec3 p = body.get(i);
                double nearest = Double.MAX_VALUE;
                for (double back = 0; back <= length + 3; back += .02) {
                    double[] at = bend.apply(back);
                    nearest = Math.min(nearest, Math.hypot(p.x - at[0], p.z - at[2]));
                }
                if (i > 3) worstOff = Math.max(worstOff, nearest - wave);
            }
            worstSeat = Math.max(worstSeat, model.riderOffset(state).add(state.mountAnchor).distanceTo(water > 0 ? seatSwimming(model, state) : clipSeat));
        }
        check(worstLink < .01, "every link keeps its length round a bend (worst " + worstLink + " blocks)");
        check(worstOff < .12, "round a bend the body lies along the path its head took, within its sway (worst " + worstOff + " blocks past it)");

        // On land the sway stays put on the ground as the body slides through it: where the body crosses a line across
        // the path, it crosses at the same place a block of travel later.
        DoubleFunction<double[]> straight = back -> new double[]{0, 0, -back};
        double slip = 0, swimRun = 0;
        for (float water : new float[]{0, 1}) {
            float wave = water > 0 ? spine.swimWave() : spine.landWave();
            var data = walked(length, spine, straight, water, 0, length + 3);
            pose(model, state, data, wave, water, 0);
            double before = crossing(chain(root, spine, scale), -5);
            for (int i = 1; i <= 20; i++) data.follow(new Vec3(0, 0, i * .05), 0, water, 1, 1000 + i, spine, null);
            pose(model, state, data, wave, water, 0);
            // the same line on the ground, a block further back from the head
            double after = crossing(chain(root, spine, scale), -6);
            if (water == 0) slip = Math.abs(after - before); else swimRun = Math.abs(after - before);
        }
        check(slip < .06, "on land the sway keeps its place on the ground as the body slides through it (" + slip + " blocks aside)");
        check(swimRun > .1, "swimming the sway runs back along the body (" + swimRun + " blocks aside a block later)");

        // Diving at 40 degrees, the body follows its head down: the tail is well above the head.
        DoubleFunction<double[]> dive = back -> new double[]{0, back * Math.sin(Math.toRadians(40)), -back * Math.cos(Math.toRadians(40))};
        var diving = walked(length, spine, dive, 1, 0, length + 3);
        pose(model, state, diving, spine.swimWave(), 1, 40);
        var dived = chain(root, spine, scale);
        double rise = dived.get(dived.size() - 1).y - dived.get(0).y;
        check(rise > 4, "diving, the body follows the head down its path (the tail " + rise + " blocks above the neck)");
        for (int i = 0; i < links.length; i++) worstLink = Math.max(worstLink, Math.abs(dived.get(i + 1).distanceTo(dived.get(i)) - links[i]));
        check(worstLink < .01, "every link keeps its length diving (worst " + worstLink + " blocks)");

        // A wrap: no weight on the trail, the clips' own chain.
        var data = walked(length, spine, bend, 0, 0, length + 3);
        pose(model, state, data, spine.landWave(), 0, 0);
        state.spineWeight = 0;
        model.setupAnim(state);
        var wrapped = chain(root, spine, scale);
        double moved = 0;
        for (int i = 0; i < wrapped.size(); i++) moved = Math.max(moved, wrapped.get(i).distanceTo(clip.get(i)));
        check(moved < 1.0E-4, "with no weight on its trail the body keeps the clips' pose (" + moved + ")");
        check(worstSeat < .15, "the rider's seat stays where the clips put it round a bend (" + worstSeat + " blocks off)");

        // Shapes that folded the body or rolled it belly up: a head turned round on the spot, a tight turn, a knock aside.
        double worstBend = 0, worstKnot = 99;
        int bellies = 0;
        var flat = new Ledge(0, 0);
        DoubleFunction<double[]> tight = back -> {
            if (back < 1) return new double[]{0, 0, -back};
            double a = (back - 1) / 1.2;
            return a < Math.PI ? new double[]{1.2 - 1.2 * Math.cos(a), 0, -1 - 1.2 * Math.sin(a)} : new double[]{2.4, 0, -1 + back - 1 - Math.PI * 1.2};
        };
        DoubleFunction<double[]> knocked = back -> new double[]{back < 1.5 ? 0 : back < 1.7 ? (back - 1.5) * 5 : 1, 0, -back};
        for (float turned : new float[]{0, 70, 120, 180}) {
            var shape = shape(model, root, spine, state, walked(length, spine, straight, 0, 0, length + 3, flat), turned, links, scale);
            worstBend = Math.max(worstBend, shape[0]); worstKnot = Math.min(worstKnot, shape[1]); bellies += (int) shape[2]; worstLink = Math.max(worstLink, shape[3]);
        }
        for (var path : List.of(tight, knocked)) {
            var shape = shape(model, root, spine, state, walked(length, spine, path, 0, 0, length + 3, flat), 0, links, scale);
            worstBend = Math.max(worstBend, shape[0]); worstKnot = Math.min(worstKnot, shape[1]); bellies += (int) shape[2]; worstLink = Math.max(worstLink, shape[3]);
        }
        check(bellies == 0, "turned round on the spot, turning tight or knocked aside, no level link of the body lies belly up (" + bellies + ")");
        check(worstBend < 46, "no joint of the body bends further than a joint can (worst " + worstBend + " degrees)");
        check(worstKnot > .8, "the body never folds back onto itself (links far apart along it at least " + worstKnot + " blocks apart)");
        check(worstLink < .01, "every link keeps its length turned round, tight and knocked (worst " + worstLink + " blocks)");

        // Up a ledge three blocks high and down it again: the neck rears from the ground behind the head, the body drapes
        // over the edge, and no point of it goes into the rock.
        var ledge = new Ledge(12, 3);
        double inRock = 0, climbKnot = 99, climbLink = 0;
        for (double[] stop : new double[][]{{1.5, 11.55}, {3, 11.55}, {3, 13.5}, {3, 16.5}}) {
            var data2 = new SerpentSpine.State(length);
            int tick = 0;
            for (double z = 0; z <= 11.55; z += .2) data2.follow(new Vec3(.5, 0, z), 0, 0, 1, tick++, spine, ledge);
            for (double y = 0; y <= stop[0] + 1.0E-6; y += .2) data2.follow(new Vec3(.5, Math.min(y, stop[0]), 11.55), 0, 0, 1, tick++, spine, ledge);
            for (double z = 11.55; z <= stop[1] + 1.0E-6; z += .2) data2.follow(new Vec3(.5, stop[0], z), 0, 0, 1, tick++, spine, ledge);
            var shape = shape(model, root, spine, state, data2, 0, links, scale);
            inRock = Math.max(inRock, ledge.inside(chain(root, spine, scale), data2.trail().head(), 0));
            climbKnot = Math.min(climbKnot, shape[1]); climbLink = Math.max(climbLink, shape[3]);
        }
        for (double[] stop : new double[][]{{1.5, 11.55}, {0, 8.5}}) {
            var data2 = new SerpentSpine.State(length);
            int tick = 0;
            for (double z = 24; z >= 11.55; z -= .2) data2.follow(new Vec3(.5, 3, z), 180, 0, 1, tick++, spine, ledge);
            for (double y = 3; y >= stop[0] - 1.0E-6; y -= .2) data2.follow(new Vec3(.5, Math.max(y, stop[0]), 11.55), 180, 0, 1, tick++, spine, ledge);
            for (double z = 11.55; z >= stop[1] - 1.0E-6; z -= .2) data2.follow(new Vec3(.5, stop[0], z), 180, 0, 1, tick++, spine, ledge);
            var shape = shape(model, root, spine, state, data2, 180, links, scale);
            inRock = Math.max(inRock, ledge.inside(chain(root, spine, scale), data2.trail().head(), 180));
            climbKnot = Math.min(climbKnot, shape[1]); climbLink = Math.max(climbLink, shape[3]);
        }
        check(inRock < .05, "up a ledge and down it no point of the body goes into the rock (" + inRock + " blocks in)");
        check(climbKnot > .8, "up a ledge and down it the body never folds back onto itself (" + climbKnot + " blocks)");
        check(climbLink < .01, "every link keeps its length up a ledge and down it (worst " + climbLink + " blocks)");

        // A stair, a block up every block, walked down, off it, across it and up it: the body lies along the nosings, no
        // point of its middle line in a step and no fold (laid on the feet's steps it went sheer up every riser, and pushed
        // up over the edges it looped back onto itself).
        var stairs = new Stairs();
        double inStep = 0, stairKnot = 99, stairBend = 0;
        for (double[] walk : new double[][]{{.5, 14, .5, 1.5, 180}, {.5, 14, .5, -3.5, 180}, {-6, -3, 1.5, 4.5, -45}, {.5, -8, .5, 5.5, 0}}) {
            var data2 = new SerpentSpine.State(length);
            double dx = walk[2] - walk[0], dz = walk[3] - walk[1], along = Math.hypot(dx, dz);
            int tick = 0;
            for (double t = 0; t <= along + 1.0E-6; t += .1) {
                double x = walk[0] + dx * t / along, z = walk[1] + dz * t / along;
                data2.follow(new Vec3(x, stairs.feet(x, z), z), (float) walk[4], 0, 1, tick++, spine, stairs);
            }
            var shape = shape(model, root, spine, state, data2, (float) walk[4], links, scale);
            inStep = Math.max(inStep, stairs.inside(chain(root, spine, scale), data2.trail().head(), (float) walk[4]));
            stairKnot = Math.min(stairKnot, shape[1]);
            stairBend = Math.max(stairBend, shape[0]);
        }
        check(inStep < .05, "on a stair no point of the body goes into a step (" + inStep + " blocks in)");
        check(stairKnot > .8 && stairBend < 46, "on a stair the body never folds back onto itself nor bends a joint past what a joint can ("
                + stairKnot + " blocks, " + stairBend + " degrees)");

        Constants.LOG.info(String.format(Locale.ROOT, "Seadramon native checks passed: %d checks, links within %.4f blocks, the body within %.3f of its path"
                + " past its sway, the sway on land %.3f aside after a block of travel (swimming %.2f), a dive's tail %.1f blocks up, the seat within %.3f;"
                + " turned round, tight and knocked no link belly up, joints within %.1f degrees, links far apart %.2f blocks apart; up and down a"
                + " ledge %.3f blocks into the rock at most, %.2f blocks apart; on a stair %.3f blocks into a step at most, %.2f blocks apart, joints"
                + " within %.1f degrees",
                checks, Math.max(worstLink, climbLink), Math.max(0, worstOff), slip, swimRun, rise, worstSeat, worstBend, worstKnot, inRock, climbKnot,
                inStep, stairKnot, stairBend));
    }

    /** A stair: the ground's top in column z is z, from 0 to 6 (flat before and after). */
    private record Stairs() implements net.minecraft.world.level.BlockGetter {
        static int height(int z) { return Math.clamp(z, 0, 6); }
        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) { return null; }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) {
            return (pos.getY() < height(pos.getZ()) ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState();
        }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }

        /** Where feet at (x, z) stand: the highest step under a box 0.9 wide round them, as a body steps up and down. */
        double feet(double x, double z) {
            return Math.max(height((int) Math.floor(z - .44)), height((int) Math.floor(z + .44)));
        }

        /** How deep the deepest of the chain's points and link middles (the head at {@code feet} facing {@code yaw}) is in a step. */
        double inside(List<Vec3> chain, Vec3 feet, float yaw) {
            double worst = 0, a = Math.toRadians(yaw);
            for (int i = 0; i < chain.size(); i++) for (int half = 0; half < (i + 1 < chain.size() ? 2 : 1); half++) {
                Vec3 p = half == 0 ? chain.get(i) : chain.get(i).add(chain.get(i + 1)).scale(.5);
                double z = feet.z + p.x * Math.sin(a) + p.z * Math.cos(a), y = feet.y + p.y;
                worst = Math.max(worst, height((int) Math.floor(z)) - y);
            }
            return worst;
        }
    }

    /**
     * The body posed on land along {@code data}'s trail with its head facing {@code yaw}: its sharpest joint (degrees), the
     * closest two points four or more links apart come (blocks), how many level links lie belly up, and the worst link off
     * its length.
     */
    private static double[] shape(NativeGroundModel model, ModelPart root, SerpentSpine.Spine spine, DigimonRenderState state,
                                  SerpentSpine.State data, float yaw, double[] links, float scale) {
        state.serpent = data;
        state.x = data.trail().head().x; state.y = data.trail().head().y; state.z = data.trail().head().z;
        state.bodyRot = yaw; state.xRot = 0; state.yRot = 0;
        state.spineWeight = 1; state.spineWave = spine.landWave(); state.spinePitch = 0;
        state.swimAnimationAmount = 0; state.swimMotionAmount = 0; state.swimAnimationPhase = 7;
        model.setupAnim(state);
        var body = chain(root, spine, scale);
        double bend = 0, knot = 99, off = 0;
        for (int i = 0; i + 2 < body.size(); i++) {
            Vec3 a = body.get(i + 1).subtract(body.get(i)).normalize(), b = body.get(i + 2).subtract(body.get(i + 1)).normalize();
            bend = Math.max(bend, Math.toDegrees(Math.acos(Mth.clamp(a.dot(b), -1, 1))));
        }
        for (int i = 0; i < body.size(); i++) for (int j = i + 4; j < body.size(); j++) knot = Math.min(knot, body.get(i).distanceTo(body.get(j)));
        for (int i = 0; i < links.length; i++) off = Math.max(off, Math.abs(body.get(i + 1).distanceTo(body.get(i)) - links[i]));
        int bellies = 0;
        var path = new ArrayList<>(spine.path());
        for (int i = 0; i < spine.chain().size(); i++) {
            path.add(spine.chain().get(i));
            Vec3 line = body.get(i + 1).subtract(body.get(i)).normalize();
            if (Math.abs(line.y) < .5 && back(root, path).y < 0) bellies++;
        }
        return new double[]{bend, knot, bellies, off};
    }

    /** The way a part's back faces (its model up) in the entity's frame at yaw 0 (+x left, y up, +z forward). */
    private static Vec3 back(ModelPart root, List<String> path) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var up = stack.last().pose().transformDirection(0, -1, 0, new org.joml.Vector3f());
        return new Vec3(up.x, -up.y, -up.z);
    }

    /** Flat ground at y 0 and, from z {@code face} on, a ledge {@code high} blocks high (none for 0). */
    private record Ledge(int face, int high) implements net.minecraft.world.level.BlockGetter {
        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) { return null; }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) {
            boolean solid = pos.getY() < 0 || high > 0 && pos.getZ() >= face && pos.getY() < high;
            return (solid ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState();
        }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }

        /** How deep the deepest of the chain's points (entity frame, the head at {@code feet} facing {@code yaw}) is in the ledge. */
        double inside(List<Vec3> chain, Vec3 feet, float yaw) {
            double worst = 0, a = Math.toRadians(yaw);
            for (Vec3 p : chain) {
                // entity frame (+x left, +z forward) to the world
                double x = feet.x + p.x * Math.cos(a) - p.z * Math.sin(a), z = feet.z + p.x * Math.sin(a) + p.z * Math.cos(a), y = feet.y + p.y;
                if (high > 0 && z > face && y < high) worst = Math.max(worst, Math.min(z - face, high - y));
                if (y < 0) worst = Math.max(worst, -y);
            }
            return worst;
        }
    }

    /** The clips' seat swimming, straight and unbent. */
    private static Vec3 seatSwimming(NativeGroundModel model, DigimonRenderState state) {
        var keep = state.serpent;
        float weight = state.spineWeight;
        state.serpent = null; state.spineWeight = 0;
        model.setupAnim(state);
        var seat = model.riderOffset(state).add(state.mountAnchor);
        state.serpent = keep; state.spineWeight = weight;
        return seat;
    }

    /** Where the chain crosses the line z = {@code z} across a path along -z: its x there. */
    private static double crossing(List<Vec3> chain, double z) {
        for (int i = 0; i + 1 < chain.size(); i++) {
            Vec3 a = chain.get(i), b = chain.get(i + 1);
            if ((a.z - z) * (b.z - z) <= 0 && a.z != b.z) return Mth.lerp((z - a.z) / (b.z - a.z), a.x, b.x);
        }
        return Double.NaN;
    }
}
