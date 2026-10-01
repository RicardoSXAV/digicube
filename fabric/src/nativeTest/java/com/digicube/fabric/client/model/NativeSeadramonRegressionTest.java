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
 * <li>down a bush and away the neck rises over its edge to the body on top, never curling; frame by frame over a stair, a
 *     pit and away from a ledge no joint shakes; turned slowly round on the spot no joint jumps between two frames;</li>
 * <li>round a bend the whole body lies along the path its head took, within its sway;</li>
 * <li>on land the sway keeps its place on the ground as the body slides forward through it (no slipping aside), and in
 *     the water it runs back along the body;</li>
 * <li>diving, the body follows the head down its path;</li>
 * <li>the rider's seat, near the head, stays where the clips put it whatever the body behind does;</li>
 * <li>with no weight on the trail (SerpentSpine.aim's weight) the clips' own pose is left alone;</li>
 * <li>a head turned round on the spot, a tight turn and a knock aside neither roll a link belly up, nor bend a joint past
 *     what a joint can, nor fold the body back onto itself;</li>
 * <li>up a ledge and down it no point of the body goes into the rock, and it neither folds nor stretches;</li>
 * <li>on a stair, walked down, off it, across and up, it lies along the nosings: no point in a step, no fold;</li>
 * <li>into a pit, out of it and along its edge it ramps down and up, no point in the ground, no joint past its bend;</li>
 * <li>its shadow is one band on the ground under the body, fainter under the reared neck than under the body.</li>
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
        state.spineWeight = 1; state.spineWave = wave;
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

        // No weight on the trail: the clips' own chain.
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
        double worstBend = 0, worstKnot = 99, worstTwist = 0;
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
            worstTwist = Math.max(worstTwist, shape[4]);
        }
        for (var path : List.of(tight, knocked)) {
            var shape = shape(model, root, spine, state, walked(length, spine, path, 0, 0, length + 3, flat), 0, links, scale);
            worstBend = Math.max(worstBend, shape[0]); worstKnot = Math.min(worstKnot, shape[1]); bellies += (int) shape[2]; worstLink = Math.max(worstLink, shape[3]);
            worstTwist = Math.max(worstTwist, shape[4]);
        }
        check(bellies == 0, "turned round on the spot, turning tight or knocked aside, no level link of the body lies belly up (" + bellies + ")");
        check(worstBend < 41, "no joint of the body bends further than a joint can (worst " + worstBend + " degrees)");
        check(worstKnot > .8, "the body never folds back onto itself (links far apart along it at least " + worstKnot + " blocks apart)");
        check(worstLink < .01, "every link keeps its length turned round, tight and knocked (worst " + worstLink + " blocks)");

        // Up a ledge three blocks high and down it again: the neck rears from the ground behind the head, the body drapes
        // over the edge, and no point of it goes into the rock.
        var ledge = new Ledge(12, 3);
        double inRock = 0, climbKnot = 99, climbLink = 0, underLedge = 99;
        for (double[] stop : new double[][]{{1.5, 11.55}, {3, 11.55}, {3, 13.5}, {3, 16.5}}) {
            var data2 = new SerpentSpine.State(length);
            int tick = 0;
            for (double z = 0; z <= 11.55; z += .2) data2.follow(new Vec3(.5, 0, z), 0, 0, 1, tick++, spine, ledge);
            for (double y = 0; y <= stop[0] + 1.0E-6; y += .2) data2.follow(new Vec3(.5, Math.min(y, stop[0]), 11.55), 0, 0, 1, tick++, spine, ledge);
            for (double z = 11.55; z <= stop[1] + 1.0E-6; z += .2) data2.follow(new Vec3(.5, stop[0], z), 0, 0, 1, tick++, spine, ledge);
            var shape = shape(model, root, spine, state, data2, 0, links, scale);
            inRock = Math.max(inRock, ledge.inside(chain(root, spine, scale), data2.trail().head(), 0));
            climbKnot = Math.min(climbKnot, shape[1]); climbLink = Math.max(climbLink, shape[3]); worstTwist = Math.max(worstTwist, shape[4]);
        }
        for (double[] stop : new double[][]{{1.5, 11.55}, {0, 8.5}}) {
            var data2 = new SerpentSpine.State(length);
            int tick = 0;
            for (double z = 24; z >= 11.55; z -= .2) data2.follow(new Vec3(.5, 3, z), 180, 0, 1, tick++, spine, ledge);
            for (double y = 3; y >= stop[0] - 1.0E-6; y -= .2) data2.follow(new Vec3(.5, Math.max(y, stop[0]), 11.55), 180, 0, 1, tick++, spine, ledge);
            for (double z = 11.55; z >= stop[1] - 1.0E-6; z -= .2) data2.follow(new Vec3(.5, stop[0], z), 180, 0, 1, tick++, spine, ledge);
            var shape = shape(model, root, spine, state, data2, 180, links, scale);
            inRock = Math.max(inRock, ledge.inside(chain(root, spine, scale), data2.trail().head(), 180));
            climbKnot = Math.min(climbKnot, shape[1]); climbLink = Math.max(climbLink, shape[3]); worstTwist = Math.max(worstTwist, shape[4]);
            // the head at the foot of the ledge, the body over it: nothing stands higher than the neck's rearing over the
            // ledge (a line held low under it ratcheted up without end)
            if (stop[0] == 0) underLedge = data2.trail().head().y + shape[5];
        }
        check(inRock < .05, "up a ledge and down it no point of the body goes into the rock (" + inRock + " blocks in)");
        check(climbKnot > .8, "up a ledge and down it the body never folds back onto itself (" + climbKnot + " blocks)");
        check(climbLink < .01, "every link keeps its length up a ledge and down it (worst " + climbLink + " blocks)");
        check(underLedge < 3 + 2.4, "lowered off a ledge the body lies over it, nothing higher than the neck's rearing over it (" + underLedge + " up)");

        // A climb given up: up the face half way, back down it, and backed off: the body lies on the ground, nothing of it
        // standing up where the head went and came back (laid on the head's own heights, it stood in a column).
        var givenUp = new SerpentSpine.State(length);
        int t2 = 0;
        for (double z = 0; z <= 11.55; z += .2) givenUp.follow(new Vec3(.5, 0, z), 0, 0, 1, t2++, spine, ledge);
        for (double y = .2; y <= 2.4 + 1.0E-6; y += .2) givenUp.follow(new Vec3(.5, y, 11.55), 0, 0, 1, t2++, spine, ledge);
        for (double y = 2.2; y >= -1.0E-6; y -= .2) givenUp.follow(new Vec3(.5, Math.max(0, y), 11.55), 0, 0, 1, t2++, spine, ledge);
        for (double z = 11.45; z >= 10.55; z -= .1) givenUp.follow(new Vec3(.5, 0, z), 0, 0, 1, t2++, spine, ledge);
        var gaveUp = shape(model, root, spine, state, givenUp, 0, links, scale);
        worstTwist = Math.max(worstTwist, gaveUp[4]);
        check(gaveUp[5] < 2.4 && gaveUp[1] > .8, "a climb given up leaves the body on the ground (its highest joint " + gaveUp[5]
                + " over the feet, links far apart " + gaveUp[1] + " blocks apart)");

        // A wall of logs three high and a block thick: climbed, crossed over its top and down its far side, and gone on:
        // no point of the body in the logs, no fold, no joint past what a joint can.
        var logs = new Ground((x, z) -> z == 4 ? 3 : 0);
        double inLogs = 0, logKnot = 99, logBend = 0;
        for (double to : new double[]{3.55, 4.5, 5.6, 9}) {
            var crossing = new SerpentSpine.State(length);
            int t3 = 0;
            for (double z = -10; z <= 3.55 + 1.0E-6; z += .1) crossing.follow(new Vec3(.5, 0, z), 0, 0, 1, t3++, spine, logs);
            for (double y = .2; y <= 3.1 + 1.0E-6; y += .2) crossing.follow(new Vec3(.5, Math.min(y, 3.1), 3.55), 0, 0, 1, t3++, spine, logs);
            double y = 3.1, z = 3.55;
            while (z < to - 1.0E-6 && logs.feet(.5, z) < 2.5) { z = Math.min(to, z + .2); crossing.follow(new Vec3(.5, y, z), 0, 0, 1, t3++, spine, logs); }
            while (z < to - 1.0E-6) {
                double floor = logs.feet(.5, z);
                if (y > floor + 1.0E-6) y = Math.max(floor, y - .2);
                else { z = Math.min(to, z + .25); y = Math.max(y, logs.feet(.5, z)); }
                crossing.follow(new Vec3(.5, y, z), 0, 0, 1, t3++, spine, logs);
            }
            var shape = shape(model, root, spine, state, crossing, 0, links, scale);
            inLogs = Math.max(inLogs, logs.inside(chain(root, spine, scale), crossing.trail().head(), 0));
            logKnot = Math.min(logKnot, shape[1]); logBend = Math.max(logBend, shape[0]); worstTwist = Math.max(worstTwist, shape[4]);
        }
        check(inLogs < .05 && logKnot > .8 && logBend < 41, "over a wall of logs a block thick no point of the body is in the logs, no fold, no"
                + " joint past what a joint can (" + inLogs + " in, " + logKnot + " blocks apart, " + logBend + " degrees)");

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
        check(stairKnot > .8 && stairBend < 41, "on a stair the body never folds back onto itself nor bends a joint past what a joint can ("
                + stairKnot + " blocks, " + stairBend + " degrees)");

        // A pit a block deep from z 2 to 5, walked into and out of, and a drop to the right of x 1 walked along: the body
        // ramps down into the pit and up out of it (laid on the feet's heights it hung sheer over the edge), lies along
        // the edge, and no point of it is in the ground.
        var pit = new Ground((x, z) -> z >= 2 && z <= 5 ? -1 : 0);
        var edge = new Ground((x, z) -> x >= 1 ? -1 : 0);
        double pitIn = 0, pitKnot = 99, pitBend = 0;
        for (var walk : new Object[][]{{pit, .5, -8, .5, 4}, {pit, .5, -8, .5, 8.5}, {edge, .6, -10, .6, 3}, {edge, .6, -10, .6, 5.3}}) {
            var ground = (Ground) walk[0];
            var data2 = new SerpentSpine.State(length);
            double x0 = ((Number) walk[1]).doubleValue(), z0 = ((Number) walk[2]).doubleValue(), x1 = ((Number) walk[3]).doubleValue(),
                    z1 = ((Number) walk[4]).doubleValue(), along = Math.hypot(x1 - x0, z1 - z0);
            int tick = 0;
            for (double t = 0; t <= along + 1.0E-6; t += .1) {
                double x = x0 + (x1 - x0) * t / along, z = z0 + (z1 - z0) * t / along;
                data2.follow(new Vec3(x, ground.feet(x, z), z), 0, 0, 1, tick++, spine, ground);
            }
            var shape = shape(model, root, spine, state, data2, 0, links, scale);
            pitIn = Math.max(pitIn, ground.inside(chain(root, spine, scale), data2.trail().head(), 0));
            pitKnot = Math.min(pitKnot, shape[1]);
            pitBend = Math.max(pitBend, shape[0]);
        }
        check(pitIn < .05, "into a pit, out of it and along its edge no point of the body goes into the ground (" + pitIn + " blocks in)");
        check(pitKnot > .8 && pitBend < 41, "into a pit, out of it and along its edge the body never folds nor bends a joint past what a joint can ("
                + pitKnot + " blocks, " + pitBend + " degrees)");

        // The shadow: one band of patches on the ground under the body's joints, faint under the reared neck.
        var level = new Ground((x, z) -> 0);
        var data3 = walked(length, spine, straight, 0, 0, length + 3, level);
        shape(model, root, spine, state, data3, 0, links, scale);
        check(state.serpentLine != null && state.serpentLine.length == 3 * (spine.chain().size() + 1), "the laid joints are left for the shadow");
        var patches = new ArrayList<com.digicube.fabric.client.render.SerpentShadow.Corner[]>();
        com.digicube.fabric.client.render.SerpentShadow.band(state.serpentLine, state.serpentRadius, 0, level, above -> 1, .8F, patches);
        int covered = 0;
        double underNeck = 0, underBody = 0;
        boolean onGround = true;
        for (int i = 0; i < state.serpentLine.length / 3; i++) {
            double jx = state.serpentLine[3 * i], jz = state.serpentLine[3 * i + 2], darkest = 0;
            for (var patch : patches) for (var c : patch) if (Math.hypot(c.x() - jx, c.z() - jz) < .5) darkest = Math.max(darkest, c.alpha());
            if (darkest > 0) covered++;
            if (i == 0) underNeck = darkest; else if (i == 10) underBody = darkest;
        }
        for (var patch : patches) for (var c : patch) onGround &= Math.abs(c.y()) < 1.0E-6;
        check(covered == state.serpentLine.length / 3 && onGround, "the shadow band lies on the ground under every joint (" + covered + " of "
                + state.serpentLine.length / 3 + ")");
        check(underNeck > 0 && underNeck < underBody, "the shadow is fainter under the reared neck (" + underNeck + ") than under the body (" + underBody + ")");

        // Into a pool off its bank and diving: the body follows the head off the bank and down its path, nothing of it standing
        // higher than its rearing over the bank, none under the pool's bed (turned whole about its first point by the
        // head's dive, it stood up out of the water).
        var pool = new Pool(-5);
        var diver = new SerpentSpine.State(length);
        int t4 = 0;
        for (double z = -10; z <= 2.3 + 1.0E-6; z += .1) diver.follow(new Vec3(.5, 0, z), 0, 0, 1, t4++, spine, pool);
        double dy = 0;
        float wet = 0;
        for (double z = 2.4; z <= 6 + 1.0E-6; z += .1) {
            wet = Math.min(1, wet + .08F);
            double want = z < 3 ? -1.3 : -Math.min(3.4, 1.3 + (z - 3) * Math.tan(Math.toRadians(55)));
            dy += Math.clamp(want - dy, -.25, .25);
            diver.follow(new Vec3(.5, dy, z), 0, wet, 1, t4++, spine, pool);
        }
        pose(model, state, diver, spine.swimWave(), 1, 55);
        var dove = chain(root, spine, scale);
        double overBank = -99, underBed = 0;
        for (Vec3 p : dove) {
            overBank = Math.max(overBank, diver.trail().head().y + p.y);
            underBed = Math.max(underBed, -5 - (diver.trail().head().y + p.y));
        }
        check(overBank < 2.4 && underBed <= 0, "diving off a bank into a pool the body follows the head down, its highest joint " + overBank
                + " over the bank, " + Math.max(0, underBed) + " under the pool's bed");
        double twisted = worstTwist;
        check(twisted < 12, "no link of the body twists against the one before it more than its roll can turn (worst " + twisted + " degrees)");

        // A bush three blocks every way: climbed from the east, crossed, lowered off its west face and walked away from. The
        // neck rises from the head over the bush's edge to the body still on top, never curling (a neck reared from a head
        // come down the face dipped and climbed back up behind it, and the chain curled into a ring there), never folding,
        // and no point of the body goes into the bush.
        var bush = new Ground((x, z) -> x >= 0 && x < 3 && z >= 0 && z < 3 ? 3 : 0);
        var down = new SerpentSpine.State(length);
        int t5 = 0;
        double bushCurl = 0, bushIn = 0, bushKnot = 99;
        for (double x = 9; x >= 3.45 - 1.0E-6; x -= .1) down.follow(new Vec3(x, 0, 1.5), 90, 0, 1, t5++, spine, bush);
        for (double y = .2; y <= 3 + 1.0E-6; y += .2) down.follow(new Vec3(3.45, Math.min(3, y), 1.5), 90, 0, 1, t5++, spine, bush);
        for (double x = 3.25; x >= -.5 - 1.0E-6; x -= .2) down.follow(new Vec3(x, 3, 1.5), 90, 0, 1, t5++, spine, bush);
        for (double y = 2.8; y >= -1.0E-6; y -= .2) down.follow(new Vec3(-.5, Math.max(0, y), 1.5), 90, 0, 1, t5++, spine, bush);
        for (double x = -.6; x >= -4 - 1.0E-6; x -= .1) {
            down.follow(new Vec3(x, 0, 1.5), 90, 0, 1, t5++, spine, bush);
            if (Math.abs(x * 10 - Math.round(x * 10)) > 1.0E-6 || Math.round(x * 10) % 3 != 0) continue;
            var shape = shape(model, root, spine, state, down, 90, links, scale);
            var laid = chain(root, spine, scale);
            bushCurl = Math.max(bushCurl, curl(laid));
            bushIn = Math.max(bushIn, bush.inside(laid, down.trail().head(), 90));
            bushKnot = Math.min(bushKnot, shape[1]);
        }
        check(bushCurl < 200 && bushKnot > .8 && bushIn < .05, "down a bush and away the neck rises over its edge to the body on top, never curling ("
                + bushCurl + " degrees round in nine links), never folding (" + bushKnot + " blocks), nothing in the bush (" + bushIn + " in)");

        // Frame by frame, three a tick as the feet are drawn, the head going on over level ground while its body comes after it
        // up a stair, through a pit, and down the face of a ledge the head came down: no joint shakes (its place's third
        // difference over four frames, nothing for any smooth going, past the head's own). Sampled a step from the head, the
        // line slid over every edge and the body shivered; its neck kept to the ground under it, it stuttered every step.
        var stair = new Stairs();
        double stairShake = framed(model, root, spine, state, length, scale, stair, d -> new double[]{.5, stair.feet(.5, -8 + d), -8 + d}, 24, 15, 0);
        double pitShake = framed(model, root, spine, state, length, scale, pit, d -> new double[]{.5, pit.feet(.5, -8 + d), -8 + d}, 24, 14.5, 0);
        var off = new Ground((x, z) -> z < 0 ? 3 : 0);
        double offShake = framed(model, root, spine, state, length, scale, off, d -> d < 10.45 ? new double[]{.5, 3, -10 + d}
                : d < 13.45 ? new double[]{.5, 3 - (d - 10.45), .45} : new double[]{.5, 0, .45 + (d - 13.45)}, 22, 14, 0);
        double shake = Math.max(stairShake, Math.max(pitShake, offShake));
        check(shake < .08, "frame by frame over a stair, a pit and away from a ledge no joint shakes (" + stairShake + ", " + pitShake + ", "
                + offShake + " blocks)");

        // The head turned slowly round on the spot as far as 250 degrees off its trail (a rider looking round): the neck winds
        // round after it, one way, and no joint jumps between two frames (picked afresh every frame, the way round flipped
        // at half round and the body swung across).
        var stood = walked(length, spine, straight, 0, 0, length + 3);
        double flip = 0;
        double[] before = null;
        float age = 10_000;
        for (float round = 0; round <= 250; round += 1.5F) {
            stood.follow(stood.trail().head(), round, 0, 0, age += 1 / 3F, spine, null);
            state.serpent = stood;
            state.x = stood.trail().head().x; state.y = stood.trail().head().y; state.z = stood.trail().head().z;
            state.bodyRot = round; state.xRot = 0; state.yRot = 0;
            state.spineWeight = 1; state.spineWave = spine.landWave();
            state.swimAnimationAmount = 0; state.swimMotionAmount = 0; state.swimAnimationPhase = 7;
            model.setupAnim(state);
            double[] now = state.serpentLine.clone();
            if (before != null) for (int i = 0; i < now.length / 3; i++)
                flip = Math.max(flip, Math.sqrt(Math.pow(now[3 * i] - before[3 * i], 2) + Math.pow(now[3 * i + 1] - before[3 * i + 1], 2)
                        + Math.pow(now[3 * i + 2] - before[3 * i + 2], 2)));
            before = now;
        }
        check(flip < .3, "turned slowly round on the spot as far as 250 degrees, no joint jumps between two frames (" + flip + " blocks at most)");

        Constants.LOG.info(String.format(Locale.ROOT, "Seadramon native checks passed: %d checks, links within %.4f blocks, the body within %.3f of its path"
                + " past its sway, the sway on land %.3f aside after a block of travel (swimming %.2f), a dive's tail %.1f blocks up, the seat within %.3f;"
                + " turned round, tight and knocked no link belly up, joints within %.1f degrees, links far apart %.2f blocks apart; up and down a"
                + " ledge %.3f blocks into the rock at most, %.2f blocks apart; on a stair %.3f blocks into a step at most, %.2f blocks apart, joints"
                + " within %.1f degrees; in a pit and along its edge %.3f blocks into the ground at most, %.2f blocks apart, joints within %.1f"
                + " degrees; the shadow under %d joints, %.2f dark under the neck and %.2f under the body; under a ledge nothing higher than %.2f,"
                + " a climb given up %.2f over the feet at most; over a wall of logs %.3f blocks into them, %.2f blocks apart, joints within %.1f"
                + " degrees; diving off a bank %.2f over it at most; no twist past %.1f degrees; down a bush and away %.0f degrees round in nine"
                + " links at most, %.2f blocks apart, %.3f in it; frame by frame %.3f blocks of shake at most; turned round on the spot %.2f blocks"
                + " a frame at most",
                checks, Math.max(worstLink, climbLink), Math.max(0, worstOff), slip, swimRun, rise, worstSeat, worstBend, worstKnot, inRock, climbKnot,
                inStep, stairKnot, stairBend, pitIn, pitKnot, pitBend, covered, underNeck, underBody, underLedge, gaveUp[5], inLogs, logKnot, logBend,
                overBank, twisted, bushCurl, bushKnot, bushIn, shake, flip));
    }

    /** The most the chain (joints in order) turns in any nine links running, degrees: a ring is near 360. */
    private static double curl(List<Vec3> chain) {
        double[] bends = new double[Math.max(0, chain.size() - 2)];
        for (int i = 1; i + 1 < chain.size(); i++) {
            Vec3 a = chain.get(i).subtract(chain.get(i - 1)), b = chain.get(i + 1).subtract(chain.get(i));
            if (a.lengthSqr() < 1.0E-12 || b.lengthSqr() < 1.0E-12) continue;
            bends[i - 1] = Math.toDegrees(Math.acos(Math.clamp(a.normalize().dot(b.normalize()), -1, 1)));
        }
        double most = 0;
        for (int i = 0; i + 9 <= bends.length; i++) {
            double sum = 0;
            for (int k = i; k < i + 9; k++) sum += bends[k];
            most = Math.max(most, sum);
        }
        return most;
    }

    /**
     * The worst shake of any joint as the head goes along {@code path} (feet at {@code path.apply(d)} for d from 0 to
     * {@code to}, facing {@code yaw}) at a third of a block a tick, drawn three frames a tick, from {@code from} on: a
     * joint's place's third difference over four frames (nothing for any smooth going, round a bend or speeding up), past
     * the head's own.
     */
    private static double framed(NativeGroundModel model, ModelPart root, SerpentSpine.Spine spine, DigimonRenderState state, double length,
                                 float scale, net.minecraft.world.level.BlockGetter level, DoubleFunction<double[]> path, double to, double from,
                                 float yaw) {
        var data = new SerpentSpine.State(length);
        List<double[]> frames = new ArrayList<>();
        double worst = 0;
        int frame = 0;
        for (double d = 0; d <= to + 1.0E-6; d += .1, frame++) {
            double[] at = path.apply(d);
            data.follow(new Vec3(at[0], at[1], at[2]), yaw, 0, 1, frame / 3F, spine, level);
            state.serpent = data;
            state.x = at[0]; state.y = at[1]; state.z = at[2];
            state.bodyRot = yaw; state.xRot = 0; state.yRot = 0;
            state.spineWeight = 1; state.spineWave = spine.landWave();
            state.swimAnimationAmount = 0; state.swimMotionAmount = 0; state.swimAnimationPhase = 7;
            state.groundAnimationAmount = 0; state.ageInTicks = 7;
            model.setupAnim(state);
            if (d < from) continue;
            frames.add(state.serpentLine.clone());
            int n = frames.size();
            if (n < 4) continue;
            double[] f0 = frames.get(n - 1), f1 = frames.get(n - 2), f2 = frames.get(n - 3), f3 = frames.get(n - 4);
            double own = 0;
            for (int i = 0; i < f0.length / 3; i++) {
                double v = 0;
                for (int c = 0; c < 3; c++) {
                    double third = f0[3 * i + c] - 3 * f1[3 * i + c] + 3 * f2[3 * i + c] - f3[3 * i + c];
                    v += third * third;
                }
                v = Math.sqrt(v);
                if (i == 0) own = v;
                worst = Math.max(worst, v - own);
            }
        }
        return worst;
    }

    /** Ground whose top in column (x, z) is {@code height(x, z)}, solid below that. */
    private record Ground(java.util.function.IntBinaryOperator height) implements net.minecraft.world.level.BlockGetter {
        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) { return null; }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) {
            return (pos.getY() < height.applyAsInt(pos.getX(), pos.getZ()) ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState();
        }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }

        /** Where feet at (x, z) stand: the highest ground under a box 0.9 wide round them. */
        double feet(double x, double z) {
            int top = Integer.MIN_VALUE;
            for (double dx : new double[]{-.44, .44}) for (double dz : new double[]{-.44, .44})
                top = Math.max(top, height.applyAsInt((int) Math.floor(x + dx), (int) Math.floor(z + dz)));
            return top;
        }

        /** How deep the deepest of the chain's points and link middles (the head at {@code feet} facing {@code yaw}) is in the ground. */
        double inside(List<Vec3> chain, Vec3 feet, float yaw) {
            double worst = 0, a = Math.toRadians(yaw);
            for (int i = 0; i < chain.size(); i++) for (int half = 0; half < (i + 1 < chain.size() ? 2 : 1); half++) {
                Vec3 p = half == 0 ? chain.get(i) : chain.get(i).add(chain.get(i + 1)).scale(.5);
                double x = feet.x + p.x * Math.cos(a) - p.z * Math.sin(a), z = feet.z + p.x * Math.sin(a) + p.z * Math.cos(a), y = feet.y + p.y;
                worst = Math.max(worst, height.applyAsInt((int) Math.floor(x), (int) Math.floor(z)) - y);
            }
            return worst;
        }
    }

    /** Ground at y 0 and, from z 2 on, a pool across x -4 to 4 with its bed at {@code bed} and water up to the bank's height. */
    private record Pool(int bed) implements net.minecraft.world.level.BlockGetter {
        private boolean in(net.minecraft.core.BlockPos pos) { return pos.getZ() >= 2 && Math.abs(pos.getX()) <= 4; }
        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) { return null; }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) {
            if (pos.getY() < (in(pos) ? bed : 0)) return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            return (in(pos) && pos.getY() < 0 ? net.minecraft.world.level.block.Blocks.WATER : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState();
        }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) {
            return in(pos) && pos.getY() >= bed && pos.getY() < 0 ? net.minecraft.world.level.material.Fluids.WATER.getSource(false)
                    : net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
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
     * closest two points four or more links apart come (blocks), how many level links lie belly up, the worst link off its
     * length, the sharpest twist between two links (degrees: one's back carried round the bend onto the next, against the
     * next's), and how high its highest joint stands over the head's feet.
     */
    private static double[] shape(NativeGroundModel model, ModelPart root, SerpentSpine.Spine spine, DigimonRenderState state,
                                  SerpentSpine.State data, float yaw, double[] links, float scale) {
        state.serpent = data;
        state.x = data.trail().head().x; state.y = data.trail().head().y; state.z = data.trail().head().z;
        state.bodyRot = yaw; state.xRot = 0; state.yRot = 0;
        state.spineWeight = 1; state.spineWave = spine.landWave();
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
        var backs = new ArrayList<Vec3>();
        for (int i = 0; i < spine.chain().size(); i++) {
            path.add(spine.chain().get(i));
            Vec3 line = body.get(i + 1).subtract(body.get(i)).normalize();
            backs.add(back(root, path));
            if (Math.abs(line.y) < .5 && backs.get(i).y < 0) bellies++;
        }
        double twist = 0, highest = -99;
        for (Vec3 p : body) highest = Math.max(highest, p.y);
        for (int i = 0; i + 1 < backs.size(); i++) {
            var a = body.get(i + 1).subtract(body.get(i)).normalize().toVector3f();
            var b = body.get(i + 2).subtract(body.get(i + 1)).normalize().toVector3f();
            var carried = new org.joml.Quaternionf().rotationTo(a, b).transform(backs.get(i).toVector3f());
            var next = backs.get(i + 1).toVector3f();
            carried.sub(new org.joml.Vector3f(b).mul(carried.dot(b))).normalize();
            next.sub(new org.joml.Vector3f(b).mul(next.dot(b))).normalize();
            twist = Math.max(twist, Math.toDegrees(Math.atan2(new org.joml.Vector3f(carried).cross(next).dot(b), carried.dot(next))) * 1);
            twist = Math.max(twist, -Math.toDegrees(Math.atan2(new org.joml.Vector3f(carried).cross(next).dot(b), carried.dot(next))));
        }
        return new double[]{bend, knot, bellies, off, twist, highest};
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
