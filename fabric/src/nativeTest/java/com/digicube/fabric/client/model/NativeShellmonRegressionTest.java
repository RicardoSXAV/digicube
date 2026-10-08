package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.BreathAttacks;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.RiderAttack;
import com.digicube.digimon.SpinAttacks;
import com.digicube.entity.ShellSpin;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shellmon as the compiled NativeGroundModel draws it:
 * <ul>
 * <li>the crawl (one hand after the other) and the heave (both hands together) are planted: through the flat of each
 *     hand's stance its wrist and its middle fingertip run back at exactly the gait's pace, level, so the hands never
 *     slide under the sheet's strides; the heave shares the walk's phase clock (its clip is a walk cycle long);</li>
 * <li>the rider sits where the sheet seats them, on the spire's flat top (its tip hidden under them), and the crawl, the
 *     heave and the swims carry them only as far as the shell's lurch and rock;</li>
 * <li>Hydro Pressure: the jet leaves where the drawn crown is, at any aim pitch, through the whole jet (the server's
 *     motion table and the drawn head agree), and the bowed crown faces ahead;</li>
 * <li>Drill Shell: withdrawn, every part of the body (crown, head, jaw, hands, fingertips) is inside the shell, behind
 *     the dark cavity plate, which shows only while it is in, and every vertex of the soft body is inside the shell's
 *     inner wall (none pokes out through the shell); the shell spins under the rider without turning them; winding down
 *     it settles onto a whole turn with its rate falling from the one it had, never jumping, reversing or speeding up;</li>
 * <li>at rest the cavity plate is hidden;</li>
 * <li>the arms never snap from one sample to the next in any clip (the swims' least of all);</li>
 * <li>the mouth opens and shuts in its own time, spells of each, and the cheeks' folds hide whenever it is shut.</li>
 * </ul>
 */
public final class NativeShellmonRegressionTest {
    private static int checks;
    private static double worstPlant, worstJet, worstWall = Double.NEGATIVE_INFINITY, worstSettle;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** A point in a part's frame (model px) in the entity's frame at yaw 0 (blocks, +z forward, feet at 0). */
    private static Vec3 point(ModelPart root, List<String> path, float scale, float x, float y, float z) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(x / 16, y / 16, z / 16, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    private static Vec3 pivot(ModelPart root, List<String> path, float scale) { return point(root, path, scale, 0, 0, 0); }

    private static ModelPart part(ModelPart root, List<String> path) {
        ModelPart part = root;
        for (String name : path) part = part.getChild(name);
        return part;
    }

    private static List<String> path(String... names) {
        var out = new ArrayList<>(List.of("root", "shell", "shell_spin"));
        out.addAll(List.of(names));
        return out;
    }

    private static final List<String> BODY = path("body"), NECK = path("body", "neck"), HEAD = path("body", "neck", "head");
    private static final List<String> CROWN = path("body", "neck", "head", "crown"), JAW = path("body", "neck", "head", "jaw");
    private static final List<String> CAVITY = path("aperture_cavity");
    /** The jet's origin in the crown's frame (px): between the tendrils. */
    private static final float[] CROWN_TOP = {0, -8, 0};

    private static List<String> wrist(char side) {
        return path("body", "shoulder_" + side, "elbow_" + side, "wrist_" + side);
    }

    private static List<String> fingertip(char side, int digit) {
        var out = new ArrayList<>(wrist(side));
        out.add("finger_" + side + "_" + digit);
        out.add("fingertip_" + side + "_" + digit);
        return out;
    }

    /**
     * The five suction pads of one hand (entity frame, blocks): the middle of each fingertip's underside, where it
     * meets the ground.
     */
    private static List<Vec3> pads(ModelPart root, char side, float scale) {
        var under = new java.util.HashMap<String, List<Vec3>>();
        root.visit(new PoseStack(), (pose, path, index, cube) -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!name.startsWith("digit_" + side + "_") || !name.endsWith("_tip_frame")) return;
            var list = under.computeIfAbsent(name, k -> new ArrayList<>());
            for (var polygon : cube.polygons) for (var v : polygon.vertices()) {
                var q = pose.pose().transformPosition(v.worldX(), v.worldY(), v.worldZ(), new org.joml.Vector3f());
                list.add(new Vec3(q.x, 1.5 - q.y, -q.z).scale(scale));
            }
        });
        var out = new ArrayList<Vec3>();
        for (int digit = 1; digit <= 5; digit++) {
            var vertices = under.get("digit_" + side + "_" + digit + "_tip_frame");
            double low = vertices.stream().mapToDouble(Vec3::y).min().orElseThrow();
            var bottom = vertices.stream().filter(v -> v.y < low + 1.5 / 16 * scale).toList();
            out.add(bottom.stream().reduce(Vec3.ZERO, Vec3::add).scale(1.0 / bottom.size()));
        }
        return out;
    }

    /**
     * One hand through the flat of its stance (from {@code from} to {@code until}, shares of the cycle after it lands):
     * its wrist and its five pads run back at the gait's pace and hold their height.
     */
    private static void planted(NativeGroundModel model, ModelPart root, DigimonRenderState state, float scale, float cycle, double pace,
                                char side, float touch, float from, float until, String label) {
        float start = (touch + from) * cycle, end = (touch + until) * cycle;
        state.groundAnimationPhase = start; model.setupAnim(state);
        Vec3 wrist = pivot(root, wrist(side), scale);
        List<Vec3> pads = pads(root, side, scale);
        double[] worst = new double[6];
        float[] at = new float[6];
        Vec3[] off = new Vec3[6];
        for (float t = start; t <= end; t += .125F) {
            state.groundAnimationPhase = t; model.setupAnim(state);
            Vec3 run = new Vec3(0, 0, -pace * (t - start));
            var now = new ArrayList<Vec3>(List.of(pivot(root, wrist(side), scale)));
            now.addAll(pads(root, side, scale));
            for (int i = 0; i < now.size(); i++) {
                Vec3 d = now.get(i).subtract((i == 0 ? wrist : pads.get(i - 1)).add(run));
                if (d.length() > worst[i]) { worst[i] = d.length(); at[i] = t; off[i] = d; }
            }
        }
        double error = java.util.Arrays.stream(worst).max().orElseThrow();
        worstPlant = Math.max(worstPlant, error);
        // three quarters of a model pixel: a slip no eye sees at any size
        if (error >= .75 * scale / 16) {
            var report = new StringBuilder();
            for (int i = 0; i < 6; i++) report.append(String.format(Locale.ROOT, " %s %.4f at %.3f %s;", i == 0 ? "wrist" : "pad " + i, worst[i], at[i], off[i]));
            check(false, label + " " + side + " hand slides:" + report);
        }
        checks++;
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("shellmon"));
        float scale = species.body().modelScale();
        var gait = species.locomotion().groundGait();
        var mount = species.body().mount().orElseThrow();
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var animation = new NativeAnimationSet(NativeModelGeometry.apply(definition.createLayer().bakeRoot(), definition.geometry()), definition.animation());
        var state = new DigimonRenderState(); state.modelScale = scale;
        float cycle = gait.cycleTicks();

        // The rider's slots: Hydro Pressure held on the quick button (and crawled with), Drill Shell held on the special one.
        var slots = mount.riderAttacks();
        check(slots.size() == 2 && slots.get(0).aim() == RiderAttack.Aim.STREAM && slots.get(0).input() == RiderAttack.Input.HOLD
                && slots.get(1).aim() == RiderAttack.Aim.SPIN && slots.get(1).input() == RiderAttack.Input.HOLD,
                "rider slots: Hydro Pressure held stream, Drill Shell held spin");
        var hydro = species.attacks().stream().filter(a -> a.id().getPath().equals("hydro_pressure")).findFirst().orElseThrow();
        var drill = species.attacks().stream().filter(a -> a.id().getPath().equals("drill_shell")).findFirst().orElseThrow();
        var jet = BreathAttacks.get(hydro);
        check(jet != null && jet.liquid() && jet.push() != null && jet.wet() && jet.douse(), "Hydro Pressure is a pushing, wetting water jet");
        check(SpinAttacks.get(drill) != null, "Drill Shell is a spin");
        var spin = definition.spin();
        check(spin != null && animation.has(spin.withdraw()) && animation.has(spin.hold()) && animation.has(spin.emerge()),
                "the spin's clips are in the model");
        for (String clip : List.of("idle", "walk", "run", "pivot_left_10", "pivot_right_10", "swim", "swim_dash", "swim_surface",
                "swim_surface_dash", "swim_idle", "hydro_pressure"))
            check(animation.has(clip), "clip " + clip);
        // The heave shares the walk's phase clock: one stride of either is one walk cycle of the phase.
        check(animation.length("run") == cycle && animation.length("walk") == cycle, "walk and run are a walk cycle long: "
                + animation.length("walk") + ", " + animation.length("run"));
        var wake = definition.swimWake();
        check(wake != null && animation.length("swim") == wake.stroke() && animation.length("swim_surface") == wake.paddle()
                && animation.length("swim_surface_dash") == wake.surgePaddle(), "swim_wake beats match the swim clips");

        // At rest the cavity plate is hidden and the spire's tip shown; ridden, the tip is hidden under the rider.
        model.setupAnim(state);
        check(!part(root, CAVITY).visible, "the cavity plate is hidden at rest");
        for (String name : List.of("shell_apex_frame", "shell_apex_tip_frame")) check(part(root, path(name)).visible, name + " shown unridden");
        state.isBeingRidden = true; model.setupAnim(state);
        for (String name : List.of("shell_apex_frame", "shell_apex_tip_frame")) check(!part(root, path(name)).visible, name + " hidden ridden");
        state.isBeingRidden = false;

        // Planted: the crawl (left hand at 0, right at half the cycle, duty .6) and the heave (both hands, duty .48).
        state.groundAnimationAmount = 1; state.ageInTicks = 0; state.groundRunAmount = 0;
        double walkPace = gait.stride() * scale / cycle;
        planted(model, root, state, scale, cycle, walkPace, 'l', 0, .04F, .44F, "crawl");
        planted(model, root, state, scale, cycle, walkPace, 'r', .5F, .04F, .44F, "crawl");
        state.groundRunAmount = 1;
        double runPace = gait.runStride() * scale / cycle;
        planted(model, root, state, scale, cycle, runPace, 'l', 0, .03F, .34F, "heave");
        planted(model, root, state, scale, cycle, runPace, 'r', .03F, .03F, .34F, "heave");
        state.groundRunAmount = 0; state.groundAnimationAmount = 0;

        // The seat: on land, through the gaits, afloat and under water.
        state.isBeingRidden = true; state.ageInTicks = 0; state.mountAnchor = mount.position(0);
        Vec3 seated = model.riderOffset(state);
        check(seated.length() < .06, "at rest the rider is drawn at the sheet's seat: " + seated);
        double crawlDrift = 0, heaveDrift = 0;
        state.groundAnimationAmount = 1;
        for (float t = 0; t < cycle; t += .25F) {
            state.groundAnimationPhase = t; state.groundRunAmount = 0;
            crawlDrift = Math.max(crawlDrift, model.riderOffset(state).length());
            state.groundRunAmount = 1;
            heaveDrift = Math.max(heaveDrift, model.riderOffset(state).length());
        }
        // limits in model px (the seat's sway grows with the model's scale)
        double px = scale / 16;
        check(crawlDrift < 7.7 * px, "crawling the rider rides the shell's lurch, no further: " + crawlDrift);
        check(heaveDrift < 19.2 * px, "heaving the rider rides the shell's yank and slide, no further: " + heaveDrift);
        state.groundAnimationAmount = 0; state.groundRunAmount = 0;
        state.swimAnimationAmount = 1; state.swimMotionAmount = 1; state.mountAnchor = mount.position(1);
        double swum = 0, paddled = 0;
        for (float t = 0; t < 40; t += .5F) {
            state.swimAnimationPhase = t; state.swimDash = t > 20 ? 1 : 0;
            swum = Math.max(swum, model.riderOffset(state).length());
        }
        state.swimSurface = 1;
        for (float t = 0; t < 36; t += .5F) {
            state.swimAnimationPhase = t; state.swimDash = t > 18 ? 1 : 0;
            paddled = Math.max(paddled, model.riderOffset(state).length());
        }
        check(swum < 9.6 * px, "under water the shell tips about the rider's seat: " + swum);
        check(paddled < 9.6 * px, "paddling at the surface the rider rides the shell's bob: " + paddled);
        state.swimSurface = 0; state.swimDash = 0; state.swimAnimationAmount = 0; state.swimMotionAmount = 0;
        state.mountAnchor = mount.position(0); state.isBeingRidden = false;

        // Hydro Pressure, standing: the drawn crown is where the server's jet leaves, at any aim, through the whole jet.
        var motion = hydro.motion();
        state.attackDefinition = hydro; state.attackAnimationName = "hydro_pressure"; state.attackAnimation.start(0);
        for (float pitch : new float[]{-45, 0, 35}) {
            for (float t = motion.activeFrom(); t <= motion.activeUntil(); t += 1) {
                state.ageInTicks = t; state.attackAimPitch = pitch;
                model.setupAnim(state);
                Vec3 drawn = point(root, CROWN, scale, CROWN_TOP[0], CROWN_TOP[1], CROWN_TOP[2]);
                Vec3 served = motion.sample(t).aimedMouth(pitch);
                double error = drawn.distanceTo(served);
                worstJet = Math.max(worstJet, error);
                check(error < .05, "the jet leaves the drawn crown at " + t + " aimed " + pitch + ": " + error);
            }
        }
        state.ageInTicks = (motion.activeFrom() + motion.activeUntil()) / 2F; state.attackAimPitch = 0;
        model.setupAnim(state);
        Vec3 crown = pivot(root, CROWN, scale), top = point(root, CROWN, scale, CROWN_TOP[0], CROWN_TOP[1], CROWN_TOP[2]);
        Vec3 facing = top.subtract(crown).normalize();
        check(facing.z > .9, "bowed, the crown faces ahead: " + facing);
        state.attackAnimation.stop(); state.attackDefinition = null; state.attackAnimationName = null; state.attackAimPitch = 0;

        // Drill Shell: withdrawn, the whole body is inside the shell, behind the cavity plate, which shows while it is in.
        state.ageInTicks = 0;
        state.spinPhase = ShellSpin.Phase.WITHDRAW; state.spinTicks = animation.length(spin.withdraw());
        model.setupAnim(state);
        double plate = pivot(root, CAVITY, scale).z;
        double deepest = Double.NEGATIVE_INFINITY;
        String outermost = "";
        var inside = new ArrayList<List<String>>(List.of(HEAD, CROWN, JAW, NECK, wrist('l'), wrist('r')));
        for (char side : new char[]{'l', 'r'}) for (int digit = 1; digit <= 5; digit++) inside.add(fingertip(side, digit));
        for (var phase : new ShellSpin.Phase[]{ShellSpin.Phase.CHARGE, ShellSpin.Phase.SPIN, ShellSpin.Phase.WIND_DOWN}) {
            state.spinPhase = phase; state.spinTicks = 4; state.spinCharge = .5F; state.spinSpeed = .8F;
            for (float age = 0; age < animation.length(spin.hold()); age += .5F) {
                state.ageInTicks = age; state.spinAngle = age * 40;
                model.setupAnim(state);
                check(part(root, CAVITY).visible, "the cavity plate shows while it is in (" + phase + ")");
                // measured in the shell's own frame, so the spin and the wobble do not move the plate away from the body
                for (var p : inside) {
                    double ahead = shellFrame(root, p, scale).z - shellFrame(root, CAVITY, scale).z;
                    if (ahead > deepest) { deepest = ahead; outermost = p.getLast(); }
                }
            }
        }
        check(deepest < -.02, "withdrawn, nothing of the body is out of the shell: " + outermost + " is " + deepest + " blocks behind the plate");
        // ...nor through the shell anywhere else: every vertex of the soft body within the shell's inner wall
        state.spinAngle = 0; state.spinLean = 0;
        for (var phase : new ShellSpin.Phase[]{ShellSpin.Phase.CHARGE, ShellSpin.Phase.EMERGE}) {
            state.spinPhase = phase; state.spinTicks = 0;
            for (float age = 0; age < animation.length(spin.hold()); age += 2) {
                state.ageInTicks = age;
                model.setupAnim(state);
                insideWall(root, phase + " at " + age);
            }
        }
        state.spinPhase = ShellSpin.Phase.WITHDRAW; state.spinTicks = animation.length(spin.withdraw());
        model.setupAnim(state);
        insideWall(root, "withdrawn");
        // The shell spins under the rider without turning them.
        state.isBeingRidden = true; state.spinPhase = ShellSpin.Phase.SPIN; state.spinSpeed = 1;
        float[] yaws = new float[3];
        for (int i = 0; i < 3; i++) {
            state.spinAngle = i * 137; model.riderOffset(state);
            yaws[i] = model.riderYaw(state);
        }
        check(Math.abs(yaws[0]) < 1 && Math.abs(yaws[1]) < 1 && Math.abs(yaws[2]) < 1, "the spin never turns the rider: " + java.util.Arrays.toString(yaws));
        double sway = 0;
        for (var phase : new ShellSpin.Phase[]{ShellSpin.Phase.CHARGE, ShellSpin.Phase.SPIN, ShellSpin.Phase.WIND_DOWN})
            for (float lean : new float[]{1.5F, 5.5F, 7})
                for (float t = 0; t < 12; t += .5F) {
                    state.spinPhase = phase; state.spinTicks = t; state.spinAngle = t * 50; state.spinLean = lean; state.spinWobble = t * 37;
                    sway = Math.max(sway, model.riderOffset(state).length());
                }
        state.spinLean = 0; state.spinWobble = 0;
        check(sway < 28.8 * px, "the rider rocks with the shell's wobble, no further: " + sway);
        settles(SpinAttacks.get(drill).windDown());
        // Out again: the emergence ends on the stance, the plate hidden as the body comes out.
        state.isBeingRidden = false; state.spinPhase = ShellSpin.Phase.EMERGE; state.spinTicks = animation.length(spin.emerge());
        model.setupAnim(state);
        check(!part(root, CAVITY).visible, "the cavity plate is hidden once the body is out");
        state.spinPhase = null; state.spinTicks = 0; state.spinAngle = 0;
        model.setupAnim(state);
        check(!part(root, CAVITY).visible, "the cavity plate stays hidden after a spin");

        // No arm snaps between samples: the swims' strokes smoothest, every other clip's within a quick swing.
        var bare = NativeModelGeometry.apply(definition.createLayer().bakeRoot(), definition.geometry());
        var clips = new NativeAnimationSet(bare, definition.animation());
        double worstSwim = 0, worstOther = 0;
        for (String clip : List.of("swim", "swim_dash", "swim_surface", "swim_surface_dash", "swim_idle", "idle", "walk", "walk_8", "walk_6",
                "walk_4", "walk_2", "run", "pivot_left_10", "pivot_left_5", "pivot_right_10", "pivot_right_5", "hydro_pressure",
                spin.withdraw(), spin.emerge())) {
            double step = armStep(bare, clips, clip);
            if (clip.startsWith("swim")) worstSwim = Math.max(worstSwim, step); else worstOther = Math.max(worstOther, step);
            check(step < (clip.startsWith("swim") ? 22 : 50), "the arms never snap in " + clip + ": " + step + " degrees in a quarter tick");
        }
        mouth(model, root, state, definition.mouth());


        System.out.println(String.format(Locale.ROOT, "Shellmon native checks passed: %d checks, hands planted within %.4f blocks, jet from the crown "
                + "within %.4f, seat %.3f, crawl drift %.3f, heave drift %.3f, swim drift %.3f, paddle drift %.3f, withdrawn %.3f blocks in, "
                + "soft body %.1f px inside the shell's wall at worst, spin sway %.3f, settle rate off by %.2f of its start at worst, arm steps "
                + "%.1f (swims) and %.1f degrees a quarter tick", checks, worstPlant, worstJet, seated.length(), crawlDrift, heaveDrift, swum, paddled,
                -deepest, -worstWall, sway, worstSettle, worstSwim, worstOther));
    }

    /** Model px of room the soft body keeps inside the shell's inner wall, and behind the cavity plate in the opening. */
    private static final float WALL_MARGIN = 1.5F, PLATE_MARGIN = .5F;

    /**
     * Every vertex of the soft body (the body part and everything it carries) is inside the shell: within the first
     * wall a level ray from the shell's axis toward it meets, or, where no wall stands that way (the aperture's
     * opening), behind the cavity plate.
     */
    private static void insideWall(ModelPart root, String label) {
        var walls = new ArrayList<float[]>();
        var body = new ArrayList<float[]>();
        float[] plate = {Float.NaN};
        root.visit(new PoseStack(), (pose, path, index, cube) -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            boolean soft = path.contains("/body/") || path.endsWith("/body");
            if (!path.contains("/shell_spin/")) return;
            boolean wall = !soft && !name.startsWith("shell_core") && !name.startsWith("shell_floor") && !name.startsWith("shell_hook")
                    && !name.startsWith("shell_apex") && !name.equals("aperture_cavity");
            for (var polygon : cube.polygons) {
                var v = polygon.vertices();
                float[] q = new float[v.length * 3];
                for (int i = 0; i < v.length; i++) {
                    var w = pose.pose().transformPosition(v[i].worldX(), v[i].worldY(), v[i].worldZ(), new org.joml.Vector3f());
                    q[i * 3] = w.x * 16; q[i * 3 + 1] = w.y * 16; q[i * 3 + 2] = w.z * 16;
                }
                if (name.equals("aperture_cavity")) plate[0] = q[2];
                if (soft) for (int i = 0; i < v.length; i++) body.add(new float[]{q[i * 3], q[i * 3 + 1], q[i * 3 + 2]});
                else if (wall) walls.add(q);
            }
        });
        check(!Float.isNaN(plate[0]) && !walls.isEmpty() && !body.isEmpty(), "the shell's walls, plate and soft body are found");
        double worst = Double.NEGATIVE_INFINITY;
        String where = "";
        for (float[] p : body) {
            double r = Math.hypot(p[0], p[2]);
            if (r < 1.0E-6) continue;
            double dx = p[0] / r, dz = p[2] / r, hit = Double.MAX_VALUE;
            for (float[] q : walls)
                for (int k = 1; k + 1 < q.length / 3; k++) hit = Math.min(hit, ray(p[1], dx, dz, q, 0, k, k + 1));
            double over = hit == Double.MAX_VALUE ? plate[0] + PLATE_MARGIN - p[2] : r - (hit - WALL_MARGIN);
            if (over > worst) { worst = over; where = String.format(Locale.ROOT, "(%.1f, %.1f, %.1f) px", p[0], p[1], p[2]); }
        }
        worstWall = Math.max(worstWall, worst);
        check(worst < 0, label + ": the soft body pokes out through the shell by " + worst + " px at " + where);
    }

    /** Distance along a level ray from (0, y, 0) the way (dx, 0, dz) to triangle (a, b, c) of a polygon, or MAX_VALUE. */
    private static double ray(double y, double dx, double dz, float[] q, int a, int b, int c) {
        double ax = q[a * 3], ay = q[a * 3 + 1], az = q[a * 3 + 2];
        double e1x = q[b * 3] - ax, e1y = q[b * 3 + 1] - ay, e1z = q[b * 3 + 2] - az;
        double e2x = q[c * 3] - ax, e2y = q[c * 3 + 1] - ay, e2z = q[c * 3 + 2] - az;
        // p = d x e2, with d = (dx, 0, dz)
        double px = -dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1.0E-9) return Double.MAX_VALUE;
        double inv = 1 / det, sx = -ax, sy = y - ay, sz = -az;
        double u = (sx * px + sy * py + sz * pz) * inv;
        if (u < 0 || u > 1) return Double.MAX_VALUE;
        double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        double v = (dx * qx + dz * qz) * inv;
        if (v < 0 || u + v > 1) return Double.MAX_VALUE;
        double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return t > 0 ? t : Double.MAX_VALUE;
    }

    /**
     * Winding down from any turn at any spinning rate, the shell settles ({@link ShellSpin#settle}) onto a whole turn
     * within its time, its rate setting out at the one it had and falling to nothing a tick at a time: no jump, no
     * reversal, at most a few per cent of speed-up.
     */
    private static void settles(float nominal) {
        for (float rate : new float[]{30, 34, 40, 47, 55, 66})
            for (float angle = 3; angle < 2000; angle += 37.3F) {
                float[] plan = ShellSpin.settle(angle, rate, nominal);
                float to = plan[0], length = plan[1];
                double turns = to / 360.0;
                check(Math.abs(turns - Math.round(turns)) < 1.0E-4, "a settle stops on a whole turn: " + to);
                check(length >= ShellSpin.SETTLE_LEAST && length <= (rate >= 40 ? nominal + ShellSpin.SETTLE_OVER : 2 * nominal),
                        "a settle takes its time: " + length + " ticks at " + rate);
                float share = rate * length / (to - angle), last = angle, lastRate = rate;
                double most = 0, least = Double.MAX_VALUE, jump = 0;
                for (int k = 1; k <= (int) length; k++) {
                    float now = angle + (to - angle) * ShellSpin.settleShare(k / length, share), r = now - last;
                    if (k == 1) worstSettle = Math.max(worstSettle, Math.abs(r - rate) / rate);
                    most = Math.max(most, r); least = Math.min(least, r); jump = Math.max(jump, Math.abs(r - lastRate));
                    last = now; lastRate = r;
                }
                check(most <= rate * 1.07 && least >= -.01 && jump <= rate * .4, String.format(Locale.ROOT,
                        "a settle from %.0f at %.0f a tick slows smoothly: rates %.1f to %.1f, steps of up to %.1f", angle, rate, least, most, jump));
            }
    }

    /** The largest turn (degrees) any arm joint makes between two quarter-tick samples of a clip. */
    private static double armStep(ModelPart bare, NativeAnimationSet clips, String clip) {
        var names = List.of("shoulder_l", "elbow_l", "wrist_l", "shoulder_r", "elbow_r", "wrist_r");
        org.joml.Quaternionf[] last = new org.joml.Quaternionf[names.size()];
        double worst = 0;
        for (float t = 0; t <= clips.length(clip); t += .25F) {
            bare.getAllParts().forEach(ModelPart::resetPose);
            clips.apply(clip, t, 1);
            for (int i = 0; i < names.size(); i++) {
                var p = clips.part(names.get(i));
                var q = new org.joml.Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot);
                if (last[i] != null) worst = Math.max(worst, Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(last[i].dot(q))))));
                last[i] = q;
            }
        }
        return worst;
    }

    /**
     * The mouth opens and shuts in its own time: over a few minutes each body spends spells shut and spells open (wide or
     * ajar), changing every so often, two bodies not in step; the cheeks' folds are hidden exactly while it is more than
     * half shut.
     */
    private static void mouth(NativeGroundModel model, ModelPart root, DigimonRenderState state, NativeGroundModel.Mouth mouth) {
        check(mouth != null, "the mouth opens and shuts by itself");
        var jaw = part(root, JAW);
        var folds = mouth.folds().stream().map(name -> part(root, path("body", "neck", "head", name))).toList();
        float rest = jaw.getInitialPose().xRot();
        var shutTimes = new java.util.HashMap<Integer, boolean[]>();
        for (int seed : new int[]{3, 11}) {
            state.seed = seed;
            int shut = 0, open = 0, changes = 0, samples = 0;
            boolean wasShut = false;
            boolean[] when = new boolean[2000];
            for (int i = 0; i < when.length; i++) {
                state.ageInTicks = i * 2;
                model.setupAnim(state);
                float share = (jaw.xRot - rest) / mouth.shut();
                boolean isShut = share > .5F;
                if (share > .85F) shut++;
                if (share < .55F) open++;
                if (samples > 0 && isShut != wasShut) changes++;
                wasShut = isShut; samples++; when[i] = isShut;
                for (var fold : folds) check(fold.visible == share < .5F, "the cheeks' folds hide as the mouth shuts (" + share + ")");
            }
            shutTimes.put(seed, when);
            check(shut > samples * .15 && open > samples * .15 && changes >= 12, String.format(Locale.ROOT,
                    "seed %d: the mouth spends %d of %d samples shut and %d open, changing %d times", seed, shut, samples, open, changes));
        }
        int together = 0;
        for (int i = 0; i < 2000; i++) if (shutTimes.get(3)[i] == shutTimes.get(11)[i]) together++;
        check(together < 2000 * .85, "two bodies' mouths are not in step: " + together + " of 2000 samples alike");
        state.seed = 0; state.ageInTicks = 0;
    }


    /** A part's pivot in the frame of the shell's own spinning part (blocks, +z the shell's front). */
    private static Vec3 shellFrame(ModelPart root, List<String> path, float scale) {
        var stack = new PoseStack();
        ModelPart part = root.getChild("root").getChild("shell").getChild("shell_spin");
        for (int i = 3; i < path.size(); i++) { part = part.getChild(path.get(i)); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(0, 0, 0, new org.joml.Vector3f());
        return new Vec3(p.x, -p.y, -p.z).scale(scale);
    }
}
