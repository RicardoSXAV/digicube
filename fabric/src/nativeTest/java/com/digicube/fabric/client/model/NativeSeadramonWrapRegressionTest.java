package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.ConstrictionCoil;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Seadramon's wrap as the compiled NativeGroundModel draws it ({@link SerpentCoil} through {@link SerpentSpine}), round prey
 * from a rabbit to an enderman on a stone floor, the body having come straight in along its strike:
 * <ul>
 * <li>held, every link lies on its way round the prey (within half a block of where the coil puts it: the head's first
 *     link turns only so far off the clip's line), the body's middle never inside the prey's box, its underside never
 *     under the floor, no joint bent past what a joint can, and the loops going all the way round the prey;</li>
 * <li>winding on and coming off, no link strays far from its way nor sinks into the floor;</li>
 * <li>when the move is over the body lies exactly as it did on its trail.</li>
 * </ul>
 */
public final class NativeSeadramonWrapRegressionTest {
    private static int checks;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** Stone under y 0, air above: the arena's floor. */
    private record Floor() implements BlockGetter {
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public BlockState getBlockState(BlockPos pos) { return pos.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(); }
        public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
        public int getHeight() { return 384; }
        public int getMinY() { return -64; }
    }

    private record Pose(double[] joints, double[] targets) {}

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon"));
        var body = species.body();
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var rig = SerpentSpine.rig(root, definition.spine());
        float scale = body.modelScale();
        int n = rig.links.length + 1;
        double[] radius = new double[n];
        for (int i = 0; i < n; i++) radius[i] = rig.radius[i] * scale;
        double[][] preys = {{.4, .5}, {.4, .7}, {.6, 1.8}, {.9, 1.4}, {1.4, .9}, {.6, 2.9}};
        for (double[] prey : preys) {
            var shape = ConstrictionCoil.fit(prey[0], prey[1], body);
            check(shape != null, "a prey " + prey[0] + " wide is wrapped");
            double half = prey[0] / 2, stand = ConstrictionCoil.headDistance(shape, body);
            String what = String.format(Locale.ROOT, "%.1f x %.1f", prey[0], prey[1]);
            for (float since = ConstrictionCoil.COIL_TICKS; since < ConstrictionCoil.RELEASE; since += 7) {
                var pose = pose(model, root, rig, body, definition, shape, stand, since);
                double[] j = pose.joints();
                double miss = 0, sunk = 0, inside = 0, bend = 0;
                for (int i = 0; i < n; i++) {
                    miss = Math.max(miss, dist(j, i, pose.targets(), i));
                    sunk = Math.max(sunk, radius[i] - j[3 * i + 1]);
                    inside = Math.max(inside, depthInside(j[3 * i], j[3 * i + 1], j[3 * i + 2], half, prey[1]));
                    if (i + 1 < n) inside = Math.max(inside, depthInside((j[3 * i] + j[3 * i + 3]) / 2, (j[3 * i + 1] + j[3 * i + 4]) / 2,
                            (j[3 * i + 2] + j[3 * i + 5]) / 2, half, prey[1]));
                    if (i > 0 && i + 1 < n) bend = Math.max(bend, angle(j, i - 1, i, i + 1));
                }
                String at = what + " at " + since;
                check(miss < .5, "held round a " + at + ", every link lies on its way (" + round(miss) + ")");
                check(sunk < .06, "held round a " + at + ", the body is never under the floor (" + round(sunk) + ")");
                check(inside < .05, "held round a " + at + ", the body's middle is never inside the prey (" + round(inside) + ")");
                check(bend <= 52, "held round a " + at + ", no joint bends past what a joint can (" + round(bend) + ")");
                check(round(j, prey[1]) >= 330, "held round a " + at + ", the loops go all the way round the prey");
            }
            for (float since : new float[]{2, 5, 8, 10, ConstrictionCoil.RELEASE + 3, ConstrictionCoil.RELEASE + 7, ConstrictionCoil.RELEASE + 10}) {
                var pose = pose(model, root, rig, body, definition, shape, stand, since);
                double[] j = pose.joints();
                double miss = 0, sunk = 0;
                for (int i = 0; i < n; i++) {
                    miss = Math.max(miss, dist(j, i, pose.targets(), i));
                    sunk = Math.max(sunk, radius[i] - j[3 * i + 1]);
                }
                check(miss < .8, "winding round a " + what + " at " + since + ", no link strays far from its way (" + round(miss) + ")");
                check(sunk < .3, "winding round a " + what + " at " + since + ", no link sinks into the floor (" + round(sunk) + ")");
            }
            var over = pose(model, root, rig, body, definition, shape, stand, ConstrictionCoil.AFTER_CAPTURE);
            double off = 0;
            for (int i = 0; i < n; i++) off = Math.max(off, dist(over.joints(), i, over.targets(), i));
            check(off < 1.0E-4, "when the move is over round a " + what + " the body lies on its trail again (" + off + ")");
        }
        System.out.println("Seadramon wrap: " + checks + " checks passed");
    }

    /**
     * The model posed with its wrap round a prey at the origin, {@code since} ticks after the capture: its joints, and
     * the points the coil gives them (the trail's own pose at that clip time, wound by SerpentCoil).
     */
    private static Pose pose(NativeGroundModel model, net.minecraft.client.model.geom.ModelPart root, SerpentSpine.Rig rig,
                             com.digicube.digimon.DigimonBody body, NativeGroundModel.Definition definition, ConstrictionCoil.Shape shape,
                             double stand, float since) {
        var floor = new Floor();
        var data = new SerpentSpine.State(body.length());
        int tick = 0;
        for (double z = -14; z <= -stand + 1.0E-6; z += .1) data.follow(new Vec3(0, 0, z), 0, 0, 1, tick++, definition.spine(), floor);
        var state = new DigimonRenderState();
        state.modelScale = body.modelScale();
        state.serpent = data;
        state.x = 0; state.y = 0; state.z = -stand;
        state.bodyRot = 0;
        state.spineWeight = 1; state.spineWave = definition.spine().landWave();
        state.groundAnimationPhase = 5;
        state.ageInTicks = 1000 + 10 + since;
        state.attackAnimation.start(1000);
        state.attackAnimationName = "constriction";
        state.attackDefinition = DigimonSpeciesBootstrap.CONSTRICTION;
        var wrap = state.wrap;
        wrap.active = true; wrap.x = 0; wrap.y = 0; wrap.z = 0; wrap.since = since;
        wrap.girth = body.serpent().coil().girth(); wrap.winding = 1;
        // the trail's own pose at this clip time (no coil yet), and what the coil makes of it
        wrap.shape = null;
        model.setupAnim(state);
        double[] targets = state.serpentLine.clone();
        int n = targets.length / 3;
        double[] arcs = new double[n], radii = new double[n];
        for (int i = 0; i < n; i++) { arcs[i] = rig.arc[i] * state.modelScale; radii[i] = rig.radius[i] * state.modelScale; }
        wrap.shape = shape;
        SerpentCoil.wind(targets, arcs, radii, 57 * state.modelScale / 16, wrap);
        model.setupAnim(state);
        return new Pose(state.serpentLine.clone(), targets);
    }

    private static double dist(double[] a, int i, double[] b, int k) {
        return Math.sqrt(sq(a[3 * i] - b[3 * k]) + sq(a[3 * i + 1] - b[3 * k + 1]) + sq(a[3 * i + 2] - b[3 * k + 2]));
    }

    private static double sq(double v) { return v * v; }

    /** How deep a point is inside a prey's box at the origin (blocks), 0 when it is outside. */
    private static double depthInside(double x, double y, double z, double half, double height) {
        return Math.max(0, Math.min(Math.min(half - Math.abs(x), half - Math.abs(z)), Math.min(y, height - y)));
    }

    /** Degrees the chain turns at joint {@code b}. */
    private static double angle(double[] j, int a, int b, int c) {
        double ux = j[3 * b] - j[3 * a], uy = j[3 * b + 1] - j[3 * a + 1], uz = j[3 * b + 2] - j[3 * a + 2];
        double vx = j[3 * c] - j[3 * b], vy = j[3 * c + 1] - j[3 * b + 1], vz = j[3 * c + 2] - j[3 * b + 2];
        double dot = (ux * vx + uy * vy + uz * vz) / Math.sqrt((ux * ux + uy * uy + uz * uz) * (vx * vx + vy * vy + vz * vz));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    /** Degrees the joints at the prey's height sweep round its axis, taken the way they go (unwound). */
    private static double round(double[] j, double height) {
        double swept = 0, most = 0, last = Double.NaN;
        for (int i = 0; i < j.length / 3; i++) {
            if (j[3 * i + 1] > height + .8) { last = Double.NaN; continue; }
            double a = Math.toDegrees(Math.atan2(j[3 * i + 2], j[3 * i]));
            if (!Double.isNaN(last)) {
                double d = a - last;
                while (d > 180) d -= 360;
                while (d < -180) d += 360;
                swept += d;
                most = Math.max(most, Math.abs(swept));
            }
            last = a;
        }
        return most;
    }

    private static String round(double v) { return String.format(Locale.ROOT, "%.3f", v); }
}
