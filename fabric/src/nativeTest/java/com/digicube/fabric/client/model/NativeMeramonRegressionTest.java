package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.digicube.fabric.client.render.TectonicWaveRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Meramon's installed gait played through the compiled NativeGroundModel: every standing foot stays fixed to the ground.
 * The clips are in place, so the ground under them runs back at the travel speed; a planted sole point (the heel's back
 * edge while the heel strikes and the foot lands, the ball of the foot from flat through the toe-off) must run back with
 * it exactly and stay on the ground, in every direction and in the run. The idle keeps both feet where they stand.
 * The stance shares are the clips' own; the strides and cycle the species sheet's.
 *
 * <p>With an evidence path, writes every drawn quad of a set of poses (world position and atlas UV) for an offline
 * picture of what the game draws.
 */
public final class NativeMeramonRegressionTest {
    private static final float SCALE = .26F;
    /** Blocks; 0.25 model px at this scale. The installed curves are reduced within 2e-4 rad, far inside it. */
    private static final double TOLERANCE = .004;
    /** Sole points in the foot's own frame (model px): the heel's back edge and the pad's front edge, on the sole. */
    private static final float SOLE = 7, HEEL = 8, BALL = -14.5F;
    private static int checks;
    private static double worst;

    /** One gait at full amplitude: blend shares, run share, stance share and the stance shares each sole point carries. */
    private record Gait(String name, float[] shares, float run, float stance, float[] heel, float[] ball, double stride, Vec3 travel) {}

    private static PoseStack stack(Vec3 origin) {
        var s = new PoseStack(); s.translate(origin.x, origin.y, origin.z);
        TectonicWaveRenderer.applyWorldTransform(s, 0, SCALE);
        s.translate(0, EntityModel.MODEL_Y_OFFSET, 0); return s;
    }
    private static Vec3 point(ModelPart root, List<String> path, float x, float y, float z, Vec3 origin) {
        var s = stack(origin);
        ModelPart part = root; part.translateAndRotate(s);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(s); }
        var v = s.last().pose().transformPosition(x / 16F, y / 16F, z / 16F, new org.joml.Vector3f());
        return new Vec3(v.x, v.y, v.z);
    }
    private static void near(Vec3 expected, Vec3 actual, String label) {
        double error = expected.distanceTo(actual); worst = Math.max(worst, error); checks++;
        if (error > TOLERANCE) throw new AssertionError(label + " error=" + error + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("meramon"));
        check(species.body().modelScale() == SCALE, "exported and installed at one scale");
        check(species.attacks().isEmpty(), "no attacks yet");
        var gait = species.locomotion().groundGait();
        check(gait != null && gait.directional(), "a directional planted gait");
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var paths = new HashMap<String, List<String>>();
        for (var part : NativeModelGeometry.mesh(definition.geometry()).parts()) paths.put(part.name(), List.of(part.path()));
        var state = new DigimonRenderState(); state.modelScale = SCALE;
        Vec3 origin = new Vec3(3, 64, -7);
        float cycle = gait.cycleTicks();

        // The idle: the weight rolls, the fist comes up, the feet never move.
        state.groundAnimationAmount = 0;
        Map<String, Vec3> planted = new HashMap<>();
        for (float t = 0; t < 120; t += .5F) {
            state.ageInTicks = t; model.setupAnim(state);
            for (String side : List.of("l", "r")) for (float y : new float[]{HEEL, BALL}) {
                Vec3 p = point(root, paths.get("foot_" + side), 0, SOLE, y, origin);
                Vec3 first = planted.computeIfAbsent(side + y, k -> p);
                near(first, p, "idle foot " + side + " at " + t);
                check(Math.abs(p.y - origin.y) < TOLERANCE, "idle sole on the ground " + side + " at " + t + ": " + (p.y - origin.y));
            }
        }

        var gaits = List.of(
                new Gait("walk", new float[]{1, 0, 0, 0}, 0, .62F, new float[]{0, .56F}, new float[]{.16F, 1}, gait.stride(), new Vec3(0, 0, 1)),
                new Gait("walk_back", new float[]{0, 1, 0, 0}, 0, .64F, new float[]{.2F, 1}, new float[]{0, .68F}, gait.backStride(), new Vec3(0, 0, -1)),
                new Gait("strafe_left", new float[]{0, 0, 1, 0}, 0, .64F, new float[]{0, 1}, new float[]{0, 1}, gait.sideStride(), new Vec3(1, 0, 0)),
                new Gait("strafe_right", new float[]{0, 0, 0, 1}, 0, .64F, new float[]{0, 1}, new float[]{0, 1}, gait.sideStride(), new Vec3(-1, 0, 0)),
                new Gait("run", new float[]{1, 0, 0, 0}, 1, .36F, new float[]{0, .4F}, new float[]{.14F, 1}, gait.runStride(), new Vec3(0, 0, 1)));
        double slowest = Double.MAX_VALUE;
        for (var g : gaits) {
            // Ground covered per tick of the clip's own phase: the stride over the shared cycle.
            double speed = g.stride() * SCALE / cycle;
            state.groundAnimationAmount = 1; state.groundRunAmount = g.run(); state.gaitShares = g.shares(); state.ageInTicks = 0;
            for (String side : List.of("l", "r")) {
                float touchdown = side.equals("l") ? 0 : cycle / 2;
                for (int anchor = 0; anchor < 2; anchor++) {
                    float[] window = anchor == 0 ? g.heel() : g.ball(); float y = anchor == 0 ? HEEL : BALL;
                    float from = touchdown + (window[0] + .02F) * g.stance() * cycle, until = touchdown + (window[1] - .02F) * g.stance() * cycle;
                    state.groundAnimationPhase = from; model.setupAnim(state);
                    Vec3 start = point(root, paths.get("foot_" + side), 0, SOLE, y, origin);
                    for (float t = from; t <= until; t += .25F) {
                        state.groundAnimationPhase = t; model.setupAnim(state);
                        Vec3 p = point(root, paths.get("foot_" + side), 0, SOLE, y, origin);
                        // Planted: the point runs back with the ground, never ahead of it or sideways, and stays on it.
                        near(start.subtract(g.travel().scale(speed * (t - from))), p, g.name() + " " + side + (anchor == 0 ? " heel" : " ball") + " at " + t);
                        check(Math.abs(p.y - origin.y) < TOLERANCE, g.name() + " sole on the ground " + side + " at " + t + ": " + (p.y - origin.y));
                    }
                }
            }
            slowest = Math.min(slowest, speed);
        }
        // The sheet's paces are the clips' own: walk and run at full amplitude cover exactly their authored strides.
        check(Math.abs(gait.fullSpeed(SCALE) - gait.stride() * SCALE / cycle) < 1e-9 && gait.runSpeed(SCALE) > gait.fullSpeed(SCALE),
                "the run is faster than the walk");

        if (args.length > 0) evidence(model, root, state, Path.of(args[0]));
        System.out.printf(Locale.ROOT, "Meramon native checks passed: %d checks, worst planted-foot error %.5f blocks%n", checks, worst);
    }

    private static void evidence(NativeGroundModel model, ModelPart root, DigimonRenderState state, Path out) throws Exception {
        record Pose(String name, float age, float amount, float phase, float run, float[] shares) {}
        float[] fwd = {1, 0, 0, 0}, left = {0, 0, 1, 0}, back = {0, 1, 0, 0};
        var poses = new ArrayList<Pose>();
        for (float t : new float[]{0, 24, 44, 50, 56, 64, 78, 100}) poses.add(new Pose("idle_" + t, t, 0, 0, 0, fwd));
        for (float p : new float[]{0, 3, 6, 9, 12, 15}) poses.add(new Pose("walk_" + p, 0, 1, p, 0, fwd));
        for (float p : new float[]{0, 3, 6, 9, 12, 15}) poses.add(new Pose("run_" + p, 0, 1, p, 1, fwd));
        for (float p : new float[]{0, 4.5F, 9, 13.5F}) poses.add(new Pose("strafe_" + p, 0, 1, p, 0, left));
        for (float p : new float[]{0, 4.5F, 9, 13.5F}) poses.add(new Pose("back_" + p, 0, 1, p, 0, back));
        for (float a : new float[]{.1F, .35F, .6F}) poses.add(new Pose("start_" + a, 30, a, 3, 0, fwd));
        for (float r : new float[]{.3F, .6F}) poses.add(new Pose("jog_" + r, 0, 1, 6, r, fwd));
        var sb = new StringBuilder("{\"texture\":\"meramon\",\"poses\":[");
        boolean firstPose = true;
        for (var p : poses) {
            state.ageInTicks = p.age(); state.groundAnimationAmount = p.amount(); state.groundAnimationPhase = p.phase();
            state.groundRunAmount = p.run(); state.gaitShares = p.shares();
            model.setupAnim(state);
            if (!firstPose) sb.append(','); firstPose = false;
            sb.append("{\"name\":\"").append(p.name()).append("\",\"quads\":[");
            boolean[] first = {true};
            root.visit(stack(Vec3.ZERO), (pose, path, index, cube) -> {
                for (var polygon : cube.polygons) {
                    if (!first[0]) sb.append(','); first[0] = false;
                    sb.append('[');
                    for (int i = 0; i < polygon.vertices().length; i++) {
                        var vertex = polygon.vertices()[i];
                        var v = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new org.joml.Vector3f());
                        if (i > 0) sb.append(',');
                        sb.append(String.format(Locale.ROOT, "[%.4f,%.4f,%.4f,%.5f,%.5f]", v.x, v.y, v.z, vertex.u(), vertex.v()));
                    }
                    sb.append(']');
                }
            });
            sb.append("]}");
        }
        sb.append("]}");
        Files.writeString(out, sb.toString());
        System.out.println("Wrote " + poses.size() + " poses to " + out);
    }
}
