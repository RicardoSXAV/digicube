package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Garurumon as the compiled NativeGroundModel draws it:
 * <ul>
 * <li>every gait is planted: while a paw stands on the ground (its toe line, which also holds as the paw rolls onto its
 *     toes) it runs back at exactly the pace its lattice was authored for, forwards (the walk's columns and the gallop),
 *     backwards and aside, so no foot slides under the sheet's strides; turning on the spot, the body goes round its
 *     centre and every standing paw stays put on the ground, the paws of a pair never touching;</li>
 * <li>every paw is heard once a stride where it lands (PawFalls follows the toes through the blend): the walk's four
 *     beats, the trot's diagonal pairs, the gallop's hind pair then fore pair, backwards and aside, and turning on the spot
 *     one paw at a time (the forepaw on the side the turn swings to, the other, then the hind paws); on grass its own step
 *     takes the place of the ground's, on stone the ground's plays alone, and quicker landings are each softer;</li>
 * <li>skidding on ice, every paw stands flat on the ground, the forepaws braced out ahead and the hind paws under the
 *     belly;</li>
 * <li>the tail holds the clip's line at rest with a little sag, swings out of a turn, lifts as the body falls from a
 *     leap, rides a hillside of one-block steps up and down close to its line on flat ground, keeps every link whole,
 *     and moves the same whatever the frame rate;</li>
 * <li>the rider sits where the sheet seats them;</li>
 * <li>the Freeze Fang canines are hidden until the bite shows them, and follow the head;</li>
 * <li>a pounce's pitch tips the whole body about its back, jaws leading;</li>
 * <li>Howling Blaster's flame is a stream of blocks, the effect model's own boxes and art on the breath's puffs, each
 *     whole at the flame's pixel, broadest partway out like the approved flame, its top up, no two sharing a face's
 *     plane; one body when steady, and whipped, every block still riding its own puff's flight; thinning at the tail
 *     once it leaves the mouth, and flat on a wall a puff struck.</li>
 * </ul>
 */
public final class NativeGarurumonRegressionTest {
    private static int checks;
    private static double worstPlant;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** A part's pivot in the entity's frame at yaw 0 (blocks, +z forward, feet at 0), as riderOffset maps model space. */
    private static Vec3 pivot(ModelPart root, List<String> path, float scale) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(0, 0, 0, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    /** The toe line under a paw (paw frame y 11 px down, 22 px ahead): what stays put while the paw stands and rolls onto its toes. */
    private static Vec3 toes(ModelPart root, List<String> path, float scale) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(0, 11 / 16F, -22 / 16F, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    /** The gap across the body between two paws' meshes (the left one's inner edge to the right one's), blocks. */
    private static double gap(ModelPart root, String left, String right, float scale) {
        float[] inner = {Float.MAX_VALUE, -Float.MAX_VALUE};
        root.visit(new PoseStack(), (pose, path, index, cube) -> {
            boolean l = path.contains(left), r = path.contains(right);
            if (!l && !r) return;
            for (var polygon : cube.polygons) for (var vertex : polygon.vertices()) {
                float x = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new org.joml.Vector3f()).x;
                if (l) inner[0] = Math.min(inner[0], x); else inner[1] = Math.max(inner[1], x);
            }
        });
        return (inner[0] - inner[1]) * scale;
    }

    private static ModelPart part(ModelPart root, List<String> path) {
        ModelPart part = root;
        for (String name : path) part = part.getChild(name);
        return part;
    }

    private static final List<List<String>> PAWS = List.of(
            List.of("root", "pelvis", "torso", "chest", "forelimb_l", "fore_elbow_l", "forepaw_l"),
            List.of("root", "pelvis", "torso", "chest", "forelimb_r", "fore_elbow_r", "forepaw_r"),
            List.of("root", "pelvis", "hindlimb_l", "hind_knee_l", "hind_hock_l", "hindpaw_l"),
            List.of("root", "pelvis", "hindlimb_r", "hind_knee_r", "hind_hock_r", "hindpaw_r"));

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("garurumon"));
        float scale = species.body().modelScale();
        var gait = species.locomotion().groundGait();
        var mount = species.body().mount().orElseThrow();
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = scale;
        float cycle = gait.cycleTicks();

        // Planted: sampled over a whole cycle, a paw at its lowest (standing) moves back at the pace of its lattice.
        // Direction shares: forwards (walk columns and the gallop), backwards, left, right; each with its stride.
        record Lattice(String name, float[] shares, float amount, float run, double pace, Vec3 travel) {}
        var lattices = new java.util.ArrayList<Lattice>();
        // the walk's own columns (between two, the blend of their different footfall timings is not a column of its own)
        for (float amount : new float[]{.4F, .6F, 1})
            lattices.add(new Lattice("walk " + amount, new float[]{1, 0, 0, 0}, amount, 0, gait.stride() * amount * scale / cycle, new Vec3(0, 0, 1)));
        lattices.add(new Lattice("gallop", new float[]{1, 0, 0, 0}, 1, 1, gait.runStride() * scale / cycle, new Vec3(0, 0, 1)));
        lattices.add(new Lattice("back", new float[]{0, 1, 0, 0}, 1, 0, gait.backStride() * scale / cycle, new Vec3(0, 0, -1)));
        lattices.add(new Lattice("left", new float[]{0, 0, 1, 0}, 1, 0, gait.sideStride() * scale / cycle, new Vec3(1, 0, 0)));
        lattices.add(new Lattice("right", new float[]{0, 0, 0, 1}, 1, 0, gait.sideStride() * scale / cycle, new Vec3(-1, 0, 0)));
        for (var lattice : lattices) {
            state.gaitShares = lattice.shares(); state.groundAnimationAmount = lattice.amount(); state.groundRunAmount = lattice.run();
            state.ageInTicks = 0;
            int steps = 112;
            double dt = cycle / steps;
            for (var paw : PAWS) {
                var track = new Vec3[steps + 1];
                double lowest = Double.MAX_VALUE;
                for (int i = 0; i <= steps; i++) {
                    state.groundAnimationPhase = (float) (i * dt); model.setupAnim(state);
                    track[i] = toes(root, paw, scale);
                    lowest = Math.min(lowest, track[i].y);
                }
                // the stance: a run of samples with the toes on the ground, less its first and last two (the paw still
                // settling onto the ground from its swing, or about to leave it)
                boolean[] low = new boolean[steps + 1];
                for (int i = 0; i <= steps; i++) low[i] = track[i].y <= lowest + .004;
                boolean[] stance = new boolean[steps + 1];
                for (int i = 0; i <= steps; ) {
                    if (!low[i]) { i++; continue; }
                    int end = i;
                    while (end + 1 <= steps && low[end + 1]) end++;
                    for (int j = i + 2; j <= end - 2; j++) stance[j] = true;
                    i = end + 1;
                }
                int standing = 0;
                for (int i = 1; i <= steps; i++) {
                    if (!stance[i] || !stance[i - 1]) continue;
                    standing++;
                    // standing still on the ground while the body travels: in the body's frame the paw runs back at its pace
                    Vec3 moved = track[i].subtract(track[i - 1]);
                    Vec3 expected = lattice.travel().scale(-lattice.pace() * dt);
                    double error = moved.subtract(expected).horizontalDistance();
                    worstPlant = Math.max(worstPlant, error);
                    check(error < .004, lattice.name() + " " + paw.getLast() + " slides at phase " + i * dt + ": " + error);
                }
                check(standing >= 8, lattice.name() + " " + paw.getLast() + " never stands: " + standing);
            }
        }

        // Turning on the spot (pivot_left / pivot_right): the body goes round its centre on the phase the entity pays the turn
        // with (DigimonGait.pivotTravel), and every standing paw stays put on the ground, its toe line read back in the world
        // as the body turns over it; a pair's two paws never touch (their meshes, across the body).
        double worstPivot = 0, pivotGap = Double.MAX_VALUE;
        for (float amount : new float[]{.5F, 1}) for (int way : new int[]{1, -1}) {
            state.gaitShares = new float[]{1, 0, 0, 0}; state.groundAnimationAmount = amount; state.groundRunAmount = 0; state.pivotTurn = way;
            float degrees = 4;
            // degrees the body turns a tick of the clip (the yaw rises turning right)
            double perPhase = way * degrees / gait.advance(gait.pivotTravel(degrees, 0), amount, scale, 0);
            int steps = 112;
            double dt = cycle / steps;
            var world = new Vec3[PAWS.size()][steps + 1];
            double[] lowest = new double[PAWS.size()];
            Arrays.fill(lowest, Double.MAX_VALUE);
            for (int i = 0; i <= steps; i++) {
                state.groundAnimationPhase = (float) (i * dt); model.setupAnim(state);
                double yaw = Math.toRadians(perPhase * i * dt), cos = Math.cos(yaw), sin = Math.sin(yaw);
                for (int f = 0; f < PAWS.size(); f++) {
                    Vec3 t = toes(root, PAWS.get(f), scale);
                    world[f][i] = new Vec3(t.x * cos - t.z * sin, t.y, t.x * sin + t.z * cos);
                    lowest[f] = Math.min(lowest[f], t.y);
                }
                pivotGap = Math.min(pivotGap, Math.min(gap(root, "forepaw_l", "forepaw_r", scale), gap(root, "hindpaw_l", "hindpaw_r", scale)));
            }
            for (int f = 0; f < PAWS.size(); f++) {
                int standing = 0;
                for (int i = 3; i <= steps - 2; i++) {
                    boolean down = true;
                    for (int j = i - 2; j <= i + 1; j++) down &= world[f][j].y <= lowest[f] + .004;
                    if (!down) continue;
                    standing++;
                    double moved = world[f][i].subtract(world[f][i - 1]).horizontalDistance();
                    worstPivot = Math.max(worstPivot, moved);
                    check(moved < .004, "turning " + (way > 0 ? "right" : "left") + " at " + amount + ", " + PAWS.get(f).getLast()
                            + " slides at phase " + i * dt + ": " + moved);
                }
                check(standing >= 40, "turning on the spot, " + PAWS.get(f).getLast() + " stands between its steps: " + standing);
            }
            state.pivotTurn = 0;
        }
        check(pivotGap > 3 / 16F * scale, "turning on the spot, a pair's paws keep apart: " + pivotGap * 16 / scale + " px");
        // A turn on the spot gathers and brakes over as long as the gait's stride takes to grow (SteadyBodyControl.ease, the
        // entity's amplitude easing): the pivot keeps its cadence all through, never fluttering its paws while its stride
        // is still small.
        float pivotCadence = 0;
        {
            float rate = gait.pivotTurnRate(scale), speed = 0, facing = 0, amount = 0;
            for (int tick = 0; tick < 60; tick++) {
                speed = com.digicube.entity.ai.SteadyBodyControl.ease(speed, 150 - facing, rate);
                facing += speed;
                double travel = gait.pivotTravel(speed, 0);
                amount = net.minecraft.util.Mth.approach(amount, (float) Math.min(1, travel / gait.fullSpeed(scale)), com.digicube.entity.DigimonEntity.AMPLITUDE_EASE);
                if (travel > 1.0E-6) pivotCadence = Math.max(pivotCadence, gait.advance(travel, amount, scale, 0));
            }
            check(Math.abs(facing - 150) < .01F, "a turn on the spot ends where it was going: " + facing);
            check(pivotCadence <= gait.pivotCadence() + .02F, "a turn on the spot keeps its pivot's cadence while it gathers: " + pivotCadence);
        }

        // Paws heard where they land (PawFalls): each paw once a stride, in the gait's own order.
        // Turning on the spot, one paw at a time: the forepaw on the side the shoulders swing to, the other forepaw closing
        // up to it, then the hind paw on the side the hips swing to and the other.
        record Heard(String name, float amount, float run, float[] shares, String order, float pivot) {
            Heard(String name, float amount, float run, float[] shares, String order) { this(name, amount, run, shares, order, 0); }
        }
        String[] pawNames = {"FL", "FR", "HL", "HR"};
        for (var heard : List.of(new Heard("walk", .2F, 0, new float[]{1, 0, 0, 0}, "FL HR FR HL"), new Heard("trot", 1, 0, new float[]{1, 0, 0, 0}, null),
                new Heard("gallop", 1, 1, new float[]{1, 0, 0, 0}, "HL HR FR FL"), new Heard("back", 1, 0, new float[]{0, 1, 0, 0}, null),
                new Heard("aside", 1, 0, new float[]{0, 0, 1, 0}, null), new Heard("turning right", 1, 0, new float[]{1, 0, 0, 0}, "FR FL HL HR", 1),
                new Heard("turning left", .5F, 0, new float[]{1, 0, 0, 0}, "FL FR HR HL", -1))) {
            float[][] toes = new float[4][3];
            boolean[] down = new boolean[4];
            model.toes(0, heard.amount(), heard.run(), heard.shares(), heard.pivot(), 0, toes);
            for (int f = 0; f < 4; f++) down[f] = toes[f][1] >= model.toeRest()[f][1] - .6F / 16;
            int[] landed = new int[4];
            List<String> order = new java.util.ArrayList<>();
            for (float phase = .25F; phase <= 2 * cycle + 1.0E-3F; phase += .25F) {
                model.toes(phase, heard.amount(), heard.run(), heard.shares(), heard.pivot(), 0, toes);
                for (int f = 0; f < 4; f++) {
                    boolean on = toes[f][1] >= model.toeRest()[f][1] - .6F / 16;
                    if (on && !down[f]) { landed[f]++; order.add(pawNames[f]); }
                    down[f] = on;
                }
            }
            check(Arrays.equals(landed, new int[]{2, 2, 2, 2}), heard.name() + ": every paw lands once a stride: " + Arrays.toString(landed));
            if (heard.order() != null) {
                // the order from the first beat of the pattern on
                String all = String.join(" ", order), twice = heard.order() + " " + heard.order();
                check((all + " " + all).contains(heard.order()) && twice.contains(String.join(" ", order.subList(0, 4))),
                        heard.name() + ": the paws land " + heard.order() + ": " + all);
            }
        }

        // Its own step on grassy ground, in place of the ground's; the ground's alone on stone and snow; and softer each the
        // quicker the landings come (PawFalls.balance: whole up to six beats a second).
        var paws = model.definition().paws();
        check(paws.padsOn(net.minecraft.sounds.SoundEvents.GRASS_STEP) && paws.replacesStep(net.minecraft.sounds.SoundEvents.GRASS_STEP)
                && paws.padsOn(net.minecraft.sounds.SoundEvents.MOSS_STEP), "on grass and moss the paws' own step replaces the ground's");
        check(!paws.padsOn(net.minecraft.sounds.SoundEvents.STONE_STEP) && !paws.replacesStep(net.minecraft.sounds.SoundEvents.STONE_STEP)
                && !paws.padsOn(net.minecraft.sounds.SoundEvents.SNOW_STEP), "on stone and snow the ground's own step plays alone");
        float sprintShare = com.digicube.fabric.client.render.PawFalls.balance(9);
        check(com.digicube.fabric.client.render.PawFalls.balance(4) == 1 && com.digicube.fabric.client.render.PawFalls.balance(6) == 1
                && Math.abs(sprintShare - .816F) < .01F && com.digicube.fabric.client.render.PawFalls.balance(12) < sprintShare,
                "each step keeps its weight up to six beats a second and softens past it: " + sprintShare + " at nine");

        // The skid (braking on ice): every paw flat on the ground, the forepaws braced out ahead, the hind paws under the belly.
        float[][] braced = new float[4][3];
        model.toes(0, 0, 0, new float[]{1, 0, 0, 0}, 0, 1, braced);
        float braceFore = Float.MAX_VALUE, braceHind = Float.MAX_VALUE;
        for (int f = 0; f < 4; f++) {
            float[] rest = model.toeRest()[f];
            check(Math.abs(braced[f][1] - rest[1]) < .3F / 16, "skidding, " + pawNames[f] + " stands on the ground: " + (braced[f][1] - rest[1]) * 16 + " px");
            float ahead = (rest[2] - braced[f][2]) * 16;
            if (f < 2) braceFore = Math.min(braceFore, ahead); else braceHind = Math.min(braceHind, ahead);
        }
        check(braceFore > 20 && braceHind > 15, "skidding, the forepaws brace " + braceFore + " px ahead and the hind paws come " + braceHind + " px under");

        // The tail (TailChains): held on the clip's line at rest with a little sag, out of a turn to the outside, up as the
        // body falls, over steps close to its line on flat ground, whole links, the same at any frame rate.
        var tailLinks = List.of("tail_segment_0", "tail_segment_1", "tail_segment_2", "tail_tuft");
        var tailFrame = List.of("root", "pelvis", "tail");
        float[] restLength = new float[3];
        for (int k = 0; k < 3; k++) {
            var a = part(root, tailFrame).getChild(tailLinks.get(k)).getInitialPose();
            var b = part(root, tailFrame).getChild(tailLinks.get(k + 1)).getInitialPose();
            restLength[k] = (float) Math.sqrt(Math.pow(b.x() - a.x(), 2) + Math.pow(b.y() - a.y(), 2) + Math.pow(b.z() - a.z(), 2));
        }
        record TailRun(float[] tip, float worstLength, boolean finite, float[][] tips) {}
        // One run: `ticks` ticks of a scripted body (speed in blocks a tick, turn in degrees a tick, rise per tick, and 1 for
        // a move from the ground to the ground), drawn at `frames` frames a tick; the tip (the tuft's pivot) in the tail
        // frame's px at the end, and at the end of every tick.
        java.util.function.Function<float[][], TailRun> run = script -> {
            var s = new DigimonRenderState();
            s.modelScale = scale; s.tails = new TailChains.State(); s.isBeingRidden = true;
            double x = 0, y = 0, z = 0; float yaw = 0, phase = 0;
            int frames = (int) script[0][0];
            float worst = 0;
            boolean finite = true;
            float[] tip = new float[3];
            float[][] tips = new float[script.length][];
            for (int tick = 1; tick < script.length; tick++) {
                float speed = script[tick][0], turn = script[tick][1], rise = script[tick][2];
                s.groundedMove = script[tick].length > 3 && script[tick][3] > 0;
                for (int f = 1; f <= frames; f++) {
                    float t = (float) f / frames;
                    s.ageInTicks = tick - 1 + t;
                    float yawNow = yaw + turn * t;
                    s.bodyRot = yawNow;
                    s.x = x - Math.sin(Math.toRadians(yawNow)) * speed * t; s.z = z + Math.cos(Math.toRadians(yawNow)) * speed * t; s.y = y + rise * t;
                    s.groundAnimationAmount = speed > .05F ? 1 : 0; s.groundRunAmount = speed > .3F ? 1 : 0;
                    s.groundAnimationPhase = phase + gait.advance(speed, 1, scale, s.groundRunAmount) * t;
                    model.setupAnim(s);
                    var frame = part(root, tailFrame);
                    for (int k = 0; k < 3; k++) {
                        var a = frame.getChild(tailLinks.get(k)); var b = frame.getChild(tailLinks.get(k + 1));
                        float length = (float) Math.sqrt(Math.pow(b.x - a.x, 2) + Math.pow(b.y - a.y, 2) + Math.pow(b.z - a.z, 2));
                        worst = Math.max(worst, Math.abs(length - restLength[k]));
                        finite &= Float.isFinite(length);
                    }
                    var tuft = frame.getChild("tail_tuft");
                    tip = new float[]{tuft.x, tuft.y, tuft.z};
                }
                tips[tick] = tip;
                x -= Math.sin(Math.toRadians(yaw + turn)) * speed; z += Math.cos(Math.toRadians(yaw + turn)) * speed; y += rise;
                yaw += turn;
                phase += gait.advance(speed, 1, scale, speed > .3F ? 1 : 0);
            }
            return new TailRun(tip, worst, finite, tips);
        };
        var restTip = part(root, tailFrame).getChild("tail_tuft").getInitialPose();
        // standing a while
        float[][] stand = new float[81][3]; stand[0][0] = 3;
        var standing = run.apply(stand);
        double sag = Math.toDegrees(Math.atan2(standing.tip()[1] - restTip.y(), standing.tip()[2]));
        check(standing.finite() && sag > 1 && sag < 15, "standing, the tail hangs on the clip's line with a little sag: " + sag + " degrees");
        // a right turn at the gallop: the tip swings to the body's left (+x), the outside
        float[][] turning = new float[61][3]; turning[0][0] = 3;
        for (int t = 1; t <= 60; t++) { turning[t][0] = .75F; turning[t][1] = t > 40 ? 8 : 0; }
        var turned = run.apply(turning);
        check(turned.tip()[0] > 4, "a right turn at the gallop swings the tail's tip out to the left: " + turned.tip()[0] + " px");
        // the fall from a leap: the tip lifts over the clip's line (y down in the model)
        float[][] leaping = new float[41][3]; leaping[0][0] = 3;
        double vy = .75;
        for (int t = 1; t <= 40; t++) {
            leaping[t][0] = .6F;
            if (t > 20) { leaping[t][2] = (float) vy; vy = (vy - .08) * .98; }
        }
        var fell = run.apply(leaping);
        check(fell.tip()[1] < restTip.y() - 3, "falling from a leap, the tail lifts: tip at " + fell.tip()[1] + " px against " + restTip.y());
        // A hillside of one-block steps at a trot, up and down, the body set on each step in a tick: once on the hill the tip
        // wobbles little about its line (a little under the flat-ground one going up, over it coming down), and nowhere
        // strays far from where it is on flat ground (felt raw, every step swung the tail like a leap).
        java.util.function.BiFunction<Float, Boolean, float[][]> steps = (rise, grounded) -> {
            float[][] script = new float[81][4]; script[0][0] = 3;
            for (int t = 1; t <= 80; t++) {
                script[t][0] = .3F; script[t][3] = grounded ? 1 : 0;
                if (t > 10 && (int) (t * .3F) > (int) ((t - 1) * .3F)) script[t][2] = rise;
            }
            return script;
        };
        var flatTrot = run.apply(steps.apply(0F, true));
        // [the most the tip strays from flat ground, the most it wobbles about its mean line from tick 30 on], px
        java.util.function.Function<float[][], float[]> overSteps = script -> {
            var r = run.apply(script);
            float most = 0, wobble = 0;
            double[] mean = new double[3];
            for (int t = 10; t <= 80; t++) {
                double d = 0;
                for (int k = 0; k < 3; k++) { double off = r.tips()[t][k] - flatTrot.tips()[t][k]; d += off * off; if (t >= 30) mean[k] += off / 51; }
                most = Math.max(most, (float) Math.sqrt(d));
            }
            for (int t = 30; t <= 80; t++) {
                double d = 0;
                for (int k = 0; k < 3; k++) d += Math.pow(r.tips()[t][k] - flatTrot.tips()[t][k] - mean[k], 2);
                wobble = Math.max(wobble, (float) Math.sqrt(d));
            }
            return new float[]{most, wobble};
        };
        float[] up = overSteps.apply(steps.apply(1F, true)), down = overSteps.apply(steps.apply(-1F, true)), raw = overSteps.apply(steps.apply(1F, false));
        float stepStray = Math.max(up[0], down[0]), stepWobble = Math.max(up[1], down[1]);
        check(stepWobble < 5 && stepStray < 18 && raw[1] > 5 * stepWobble,
                "over one-block steps the tail's tip wobbles " + stepWobble + " px and strays " + stepStray + " px (felt raw " + raw[1] + " and " + raw[0] + ")");
        // the same turn at one frame a tick and at six: the tail ends where it would at any frame rate (a frame a tick
        // sees the pose's path through the tick as a straight line, a couple of px at the tip, about two degrees)
        turning[0][0] = 1;
        var coarse = run.apply(turning);
        turning[0][0] = 6;
        var fine = run.apply(turning);
        double apart = Math.sqrt(Math.pow(coarse.tip()[0] - fine.tip()[0], 2) + Math.pow(coarse.tip()[1] - fine.tip()[1], 2) + Math.pow(coarse.tip()[2] - fine.tip()[2], 2));
        check(apart < 4, "the tail moves the same at one frame a tick and at six: tips " + apart + " px apart");
        float worstLink = Math.max(Math.max(standing.worstLength(), turned.worstLength()), Math.max(fell.worstLength(), fine.worstLength()));
        check(turned.finite() && fell.finite() && worstLink < .05F, "every link of the tail keeps its length: " + worstLink + " px off");

        // The seat.
        state.gaitShares = new float[]{1, 0, 0, 0};
        state.isBeingRidden = true; state.groundAnimationAmount = 0; state.groundRunAmount = 0; state.ageInTicks = 0;
        state.mountAnchor = mount.position(0);
        Vec3 seated = model.riderOffset(state);
        check(seated.length() < .06, "at rest the rider is drawn at the sheet's seat: " + seated);
        state.isBeingRidden = false;

        // The canines: hidden at rest, shown through the bite and carried by the head.
        var fang = List.of("root", "pelvis", "torso", "chest", "neck", "head", "ff_upper", "ff_upper_l_shaft");
        model.setupAnim(state);
        check(!part(root, fang).visible, "the canines are hidden until the bite shows them");
        var bite = species.attacks().stream().filter(a -> a.id().getPath().equals("freeze_fang")).findFirst().orElseThrow();
        state.attackDefinition = bite; state.attackAnimationName = "freeze_fang"; state.attackAnimation.start(0);
        state.ageInTicks = 5; model.setupAnim(state);
        check(part(root, fang).visible, "the canines show through the bite");
        Vec3 head = pivot(root, List.of("root", "pelvis", "torso", "chest", "neck", "head"), scale), tooth = pivot(root, fang, scale);
        check(tooth.distanceTo(head) < 1.2 && tooth.z > head.z, "the canines sit ahead of the head: " + tooth + " vs " + head);

        // A pounce's pitch: the whole body tips about its back, the jaws down when it dives.
        var jaw = List.of("root", "pelvis", "torso", "chest", "neck", "head", "jaw");
        var back = List.of("root", "pelvis", "torso");
        Vec3 jawsLevel = pivot(root, jaw, scale), backLevel = pivot(root, back, scale);
        state.pouncePitch = -30; model.setupAnim(state);
        Vec3 jawsDiving = pivot(root, jaw, scale), backDiving = pivot(root, back, scale);
        check(jawsDiving.y < jawsLevel.y - .3, "diving, the jaws lead down: " + jawsLevel.y + " -> " + jawsDiving.y);
        check(backDiving.distanceTo(backLevel) < .3, "the body tips about its back: the back moved " + backDiving.distanceTo(backLevel));
        state.pouncePitch = 0; state.attackDefinition = null; state.attackAnimation.stop();

        // Howling Blaster's flame as the breath renderer lays it: the effect model's own boxes and art, rigid at the flame's
        // pixel on the puffs, shaped like the approved flame, its top kept up.
        var flame = new com.digicube.fabric.client.render.FrostBreathRenderer("howling_blaster_fx");
        var spec = com.digicube.digimon.BreathAttacks.get(species.attacks().stream().filter(com.digicube.digimon.BreathAttacks::handles).findFirst().orElseThrow());
        java.awt.image.BufferedImage art;
        try (var in = NativeGarurumonRegressionTest.class.getResourceAsStream("/assets/digicube/textures/entity/digimon/howling_blaster_fx.png")) {
            art = javax.imageio.ImageIO.read(in);
        } catch (java.io.IOException e) { throw new IllegalStateException("cannot read the flame's art", e); }
        float pixel = com.digicube.fabric.client.render.FrostBreathRenderer.PIXEL;

        var steady = lay(flame, breathe(spec, t -> new Vec3(0, 0, 1), 24, 0));
        double far = 0, maxRadius = 0;
        for (int i = 0; i < steady.puffs().count; i++) { far = Math.max(far, steady.puffs().z[i]); maxRadius = Math.max(maxRadius, steady.puffs().radius[i]); }
        int faces = 0, lit = 0;
        double stray = 0, reach = 0;
        double[] halfWidth = new double[10];
        for (var box : steady.boxes()) {
            check(rigid(box, pixel), "every box of a steady flame is whole and at the flame's pixel: scale " + box.scale());
            check(box.frame()[4] > .95F, "a steady flame keeps its art's top up: up " + box.frame()[4]);
            for (int q = 0; q < box.box().quads().length; q++) {
                faces++;
                float u = 0, v = 0;
                for (float[] vertex : box.box().quads()[q]) {
                    float[] w = box.at(vertex);
                    double near = Double.MAX_VALUE;
                    for (int i = 0; i < steady.puffs().count; i++) {
                        near = Math.min(near, Math.sqrt(Math.pow(w[0] - steady.puffs().x[i], 2) + Math.pow(w[1] - steady.puffs().y[i], 2) + Math.pow(w[2] - steady.puffs().z[i], 2)));
                    }
                    stray = Math.max(stray, near);
                    reach = Math.max(reach, w[2]);
                    int bin = (int) Math.floor(w[2] / far * 10);
                    // the flame's own outline: embers drifting off it are left out
                    if (bin >= 0 && bin < 10 && box.box().longest() >= 6) halfWidth[bin] = Math.max(halfWidth[bin], Math.abs(w[0]));
                    u += vertex[3]; v += vertex[4];
                }
                int texel = art.getRGB(Math.min(art.getWidth() - 1, (int) (u / 4 * art.getWidth())), Math.min(art.getHeight() - 1, (int) (v / 4 * art.getHeight())));
                if ((texel >>> 24) > 16) lit++;
            }
        }
        int widest = 0;
        for (int b = 1; b < 10; b++) if (halfWidth[b] > halfWidth[widest]) widest = b;
        check(steady.boxes().size() > 40, "the flame is drawn: " + steady.boxes().size() + " boxes");
        check(lit >= faces * .95, "its faces sample the flame's art, not empty texels: " + lit + " of " + faces);
        check(stray < maxRadius * 2 + .8, "no box is drawn away from the puffs: " + stray + " blocks");
        check(reach > far * .85, "the flame runs the length of the breath: " + reach + " of " + far);
        check(widest >= 3 && widest <= 6, "like the approved flame it is broadest partway out: tenth " + widest + " " + Arrays.toString(halfWidth));
        check(halfWidth[0] < halfWidth[widest] * .6 && halfWidth[9] < halfWidth[widest] * .7,
                "narrow at the mouth and thin at its tips: " + Arrays.toString(halfWidth));
        check(halfWidth[widest] > maxRadius * .8 && halfWidth[widest] < maxRadius * 1.8,
                "as wide as the breath strikes: " + halfWidth[widest] + " for a radius of " + maxRadius);
        // Steady, the puffs' trails meet: along the aim the flame's body blocks leave no gap from the mouth to its last
        // section (past it the tips stand apart, as in the art).
        List<double[]> spans = new java.util.ArrayList<>();
        for (var box : steady.boxes()) {
            if (box.box().sizeZ() > 20 || box.box().sizeX() < 7) continue;
            double from = Double.MAX_VALUE, to = -Double.MAX_VALUE;
            for (var quad : box.box().quads()) for (float[] vertex : quad) { float[] w = box.at(vertex); from = Math.min(from, w[2]); to = Math.max(to, w[2]); }
            spans.add(new double[]{from, to});
        }
        spans.sort(java.util.Comparator.comparingDouble(span -> span[0]));
        double covered = spans.get(0)[1], widestGap = 0;
        for (var span : spans) { widestGap = Math.max(widestGap, span[0] - covered); covered = Math.max(covered, span[1]); }
        check(widestGap < .05, "a steady stream is one body of blocks: a gap of " + widestGap + " blocks along it");

        // Whipped back and forth at the breath's full turn, the flame stays a stream of whole blocks: none is stretched
        // along the curve, whatever the gap between puffs.
        var whipped = lay(flame, breathe(spec, t -> {
            double phase = (t % 12) / 12.0, yaw = Math.toRadians(phase < .5 ? -66 + phase * 264 : 66 - (phase - .5) * 264);
            return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        }, 24, 0));
        float longest = 0, artLongest = 0;
        for (var box : whipped.boxes()) {
            check(rigid(box, pixel), "every box of a whipped flame is whole and at the flame's pixel: scale " + box.scale());
            longest = Math.max(longest, box.box().longest() * box.scale());
        }
        for (var box : steady.boxes()) artLongest = Math.max(artLongest, box.box().longest() * pixel);
        check(longest <= artLongest * 1.25F, "no box grows longer than the art's longest: " + longest + " of " + artLongest);
        check(whipped.boxes().size() > whipped.puffs().count, "every puff of a whipped stream still carries its blocks: " + whipped.boxes().size() + " boxes on " + whipped.puffs().count + " puffs");
        // ... and every block rides its own puff's flight (behind it along its way, or a tip ahead of the oldest), never
        // bridging the gap between two puffs that went different ways.
        double astray = 0;
        for (var box : whipped.boxes()) {
            if (box.box().longest() < 6) continue;
            double nearest = Double.MAX_VALUE;
            var puffs = whipped.puffs();
            for (int i = 0; i < puffs.count; i++) {
                double dx = box.x() - puffs.x[i], dy = box.y() - puffs.y[i], dz = box.z() - puffs.z[i];
                double along = dx * puffs.lookX[i] + dy * puffs.lookY[i] + dz * puffs.lookZ[i];
                if (along < -1.35 || along > 1.35) continue;
                double ax = dx - puffs.lookX[i] * along, ay = dy - puffs.lookY[i] * along, az = dz - puffs.lookZ[i] * along;
                nearest = Math.min(nearest, Math.sqrt(ax * ax + ay * ay + az * az));
            }
            astray = Math.max(astray, nearest);
        }
        check(astray < .25, "every block of a whipped stream rides its own puff's flight: one strays " + astray + " blocks off");

        // Overlapping boxes never share a face's plane (they would flicker), steady or whipped.
        int steadyFlicker = coplanar(steady.boxes()), whippedFlicker = coplanar(whipped.boxes());
        check(steadyFlicker == 0 && whippedFlicker == 0, "no two boxes share a face's plane where they overlap: " + steadyFlicker + " steady, " + whippedFlicker + " whipped");

        // Let go, the flame leaves the mouth thinning at its tail.
        var released = lay(flame, breathe(spec, t -> new Vec3(0, 0, 1), 24, 3));
        double tail = Double.MAX_VALUE, tailSize = 0, broadest = 0;
        for (var box : released.boxes()) {
            if (box.box().sizeX() < 7 || box.twoSided()) continue;
            double size = box.box().sizeX() * box.scale();
            broadest = Math.max(broadest, size);
            if (box.z() < tail) { tail = box.z(); tailSize = size; }
        }
        check(tailSize < broadest * .5, "a flame that has left the mouth thins at its tail: " + tailSize + " of " + broadest);

        // A puff that struck a wall lays its slabs flat on the wall, their tops facing out of it.
        var wall = breathe(spec, t -> new Vec3(0, 0, 1), 24, 0);
        var puff = wall.puffs().get(wall.puffs().size() * 3 / 4);
        puff.struck = true;
        puff.surfaceX = 0; puff.surfaceY = 0; puff.surfaceZ = -1;
        puff.lookX = 0; puff.lookY = 1; puff.lookZ = 0;
        boolean flat = false;
        // (drawn at the start of the tick, where the puff was; a slab's wobble moves it a little off it)
        for (var box : lay(flame, wall).boxes()) flat |= Math.abs(box.z() - puff.pz) < .3 && box.frame()[5] < -.99F;
        check(flat, "a puff on a wall lays its flame flat on it");

        System.out.printf(Locale.ROOT, "Garurumon native checks passed: %d checks, standing paws within %.4f blocks a sample of their pace, turning on the spot within %.4f of their place (pairs %.1f px apart, at most %.2f times the walk's cadence), tail sag %.1f degrees, out %.1f px in a turn, up %.1f px falling, wobbling %.1f px over steps (%.1f felt raw), skidding forepaws %.0f px ahead, seat at %.3f blocks, flame %d boxes (%d of %d faces on its art) over %.1f blocks, broadest %.2f in its tenth %d, no gap wider than %.3f, whipped %d boxes all whole, none further than %.2f off its puff%n",
                checks, worstPlant, worstPivot, pivotGap * 16 / scale, pivotCadence, sag, turned.tip()[0], restTip.y() - fell.tip()[1], stepWobble, raw[1], braceFore, seated.length(), steady.boxes().size(), lit, faces, reach, halfWidth[widest], widest, widestGap, whipped.boxes().size(), astray);
    }

    /** One box the flame renderer laid: the art's box, where, its axes (right, up, forward) and its scale. */
    private record Laid(com.digicube.fabric.client.render.FrostBreathRenderer.Box box, float x, float y, float z, float[] frame, float scale, boolean twoSided) {
        float[] at(float[] v) {
            return new float[]{x - (frame[0] * v[0] + frame[3] * v[1] + frame[6] * v[2]) * scale,
                    y - (frame[1] * v[0] + frame[4] * v[1] + frame[7] * v[2]) * scale,
                    z - (frame[2] * v[0] + frame[5] * v[1] + frame[8] * v[2]) * scale};
        }
    }

    private record Flame(com.digicube.fabric.client.render.FrostBreathRenderer.State puffs, List<Laid> boxes) {}

    /** A breath shed from a mouth 1.5 blocks up for {@code ticks} along {@code aim(tick)}, then flown {@code after} ticks more. */
    private static com.digicube.entity.FrostBreath breathe(com.digicube.digimon.BreathAttacks.Spec spec, java.util.function.IntFunction<Vec3> aim, int ticks, int after) {
        var breath = new com.digicube.entity.FrostBreath(spec);
        for (int t = 0; t < ticks + after; t++) {
            if (t < ticks) breath.emit(new Vec3(0, 1.5, 0), aim.apply(t), Vec3.ZERO, net.minecraft.util.RandomSource.create(t));
            else breath.breakTrain();
            breath.step(null);
        }
        return breath;
    }

    private static Flame lay(com.digicube.fabric.client.render.FrostBreathRenderer renderer, com.digicube.entity.FrostBreath breath) {
        var puffs = new com.digicube.fabric.client.render.FrostBreathRenderer.State();
        com.digicube.fabric.client.render.FrostBreathRenderer.extract(breath, puffs, 0, 0, 0, 0);
        List<Laid> boxes = new java.util.ArrayList<>();
        renderer.place(puffs, 40, (box, x, y, z, frame, scale, color, twoSided) -> boxes.add(new Laid(box, x, y, z, frame.clone(), scale, twoSided)));
        return new Flame(puffs, boxes);
    }

    /**
     * Faces closer than this (blocks) share a plane for the depth buffer: 26.2 draws the level into a 32-bit float depth
     * with reverse Z, which keeps faces apart down to about a millionth of a block at twenty blocks.
     */
    private static final float PLANE = 1.0E-5F;

    /** Pairs of faces of different boxes facing the same way on one plane that overlap. */
    private static int coplanar(List<Laid> boxes) {
        List<float[][]> faces = new java.util.ArrayList<>();
        List<Integer> owner = new java.util.ArrayList<>();
        for (int b = 0; b < boxes.size(); b++) {
            var box = boxes.get(b);
            if (box.twoSided()) continue;
            for (int q = 0; q < box.box().quads().length; q++) {
                float[][] quad = new float[5][];
                for (int i = 0; i < 4; i++) quad[i] = box.at(box.box().quads()[q][i]);
                float[] n = box.box().normals()[q], f = box.frame();
                quad[4] = new float[]{-(f[0] * n[0] + f[3] * n[1] + f[6] * n[2]), -(f[1] * n[0] + f[4] * n[1] + f[7] * n[2]), -(f[2] * n[0] + f[5] * n[1] + f[8] * n[2])};
                faces.add(quad);
                owner.add(b);
            }
        }
        int pairs = 0;
        for (int a = 0; a < faces.size(); a++) for (int c = a + 1; c < faces.size(); c++) {
            if (owner.get(a).equals(owner.get(c))) continue;
            float[][] fa = faces.get(a), fc = faces.get(c);
            float[] n = fa[4];
            if (n[0] * fc[4][0] + n[1] * fc[4][1] + n[2] * fc[4][2] < .999F) continue;
            float gap = n[0] * (fc[0][0] - fa[0][0]) + n[1] * (fc[0][1] - fa[0][1]) + n[2] * (fc[0][2] - fa[0][2]);
            if (Math.abs(gap) > PLANE) continue;
            // overlap in the plane: along the face's two edge directions
            float[] e1 = sub(fa[1], fa[0]), e2 = sub(fa[3], fa[0]);
            if (overlaps(fa, fc, e1) && overlaps(fa, fc, e2)) pairs++;
        }
        return pairs;
    }

    private static float[] sub(float[] a, float[] b) { return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    /** Whether two quads' spans along {@code axis} overlap by more than a hair. */
    private static boolean overlaps(float[][] a, float[][] b, float[] axis) {
        float l = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (l < 1.0E-6F) return false;
        float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE, minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float pa = (a[i][0] * axis[0] + a[i][1] * axis[1] + a[i][2] * axis[2]) / l, pb = (b[i][0] * axis[0] + b[i][1] * axis[1] + b[i][2] * axis[2]) / l;
            minA = Math.min(minA, pa); maxA = Math.max(maxA, pa); minB = Math.min(minB, pb); maxB = Math.max(maxB, pb);
        }
        return Math.min(maxA, maxB) - Math.max(minA, minB) > 1.0E-3F;
    }

    /** A box drawn whole: an orthonormal frame and one scale no larger than the flame's pixel with its flicker. */
    private static boolean rigid(Laid box, float pixel) {
        float[] f = box.frame();
        for (int a = 0; a < 3; a++) {
            float l = f[a * 3] * f[a * 3] + f[a * 3 + 1] * f[a * 3 + 1] + f[a * 3 + 2] * f[a * 3 + 2];
            if (Math.abs(l - 1) > 1.0E-3F) return false;
            for (int b = a + 1; b < 3; b++) if (Math.abs(f[a * 3] * f[b * 3] + f[a * 3 + 1] * f[b * 3 + 1] + f[a * 3 + 2] * f[b * 3 + 2]) > 1.0E-3F) return false;
        }
        return box.scale() > 0 && box.scale() <= pixel * 1.25F;
    }
}
