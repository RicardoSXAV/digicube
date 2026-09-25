package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.digivice.*;
import com.digicube.fabric.client.digivice.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

final class RecallFlightChecks {
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
    private static final RecallPath.Blocks OPEN = (x, y, z) -> y < 0;

    /** A first-person camera the game could produce: turned, pitched, bobbing, hurt-tilted and with the hand swaying. */
    static RecallFlight.View view(Vec3 eye, float yaw, float pitch, float walk, float worldFov, int side) {
        var orientation = new Quaternionf().rotationYXZ(yaw, pitch, 0);
        var bob = new Matrix4f().translate((float)Math.sin(walk) * .03F, -Math.abs((float)Math.cos(walk)) * .06F, 0)
                .rotateZ((float)Math.sin(walk) * .03F).rotateX(Math.abs((float)Math.cos(walk - .2F)) * .05F);
        float ratio = (float)(Math.tan(Math.toRadians(70) / 2) / Math.tan(Math.toRadians(worldFov) / 2));
        var sway = new Quaternionf().rotateX(.02F * (float)Math.sin(walk * 3)).rotateY(-.03F);
        return new RecallFlight.View(eye, orientation, bob, ratio, sway, side);
    }
    /** The pose stack the hand pass hands submitArmWithItem: inverse view, bob, sway. */
    static Matrix4f handBase(RecallFlight.View view) {
        return new Matrix4f().rotation(view.orientation()).mul(view.bob()).rotate(view.sway());
    }
    static Matrix4f restingHeld(RecallFlight.View view) {
        return handBase(view).translate(view.side() * .56F, -.52F, -.72F).mul(RecallFlight.heldDisplay(view.side()));
    }
    static float[] screen(Matrix4f projectionSpace, Vector3f vertex, float focal) {
        var p = projectionSpace.transformPosition(new Vector3f(vertex));
        return new float[]{focal * p.x / -p.z, focal * p.y / -p.z};
    }

    static void run() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        for (var hand : InteractionHand.values()) for (boolean same : new boolean[]{true, false}) {
            var payload = new DigiviceRecallPayload(UUID.randomUUID(), hand, 4242, UUID.randomUUID(), Constants.id("test_dimension"),
                    new Vec3(100000.125, -62.25, -12345.75), new Vec3(-1200.125, 200, 12), same, -82.5F, 32, 192);
            DigiviceRecallPayload.STREAM_CODEC.encode(buffer, payload);
            check(payload.equals(DigiviceRecallPayload.STREAM_CODEC.decode(buffer)), "full source packet round trip");
            var watched = payload.withoutToken();
            check(watched.token().equals(DigiviceRecallPayload.NO_TOKEN) && watched.journey().equals(payload.journey())
                    && watched.recipient() == payload.recipient(), "watchers get the same journey without the credential");
        }
        buffer.release();
        String outside = outside();

        float previous = 0;
        int samples = 0, handoffs = 0;
        double worstJump = 0, worstProjection = 0;
        for (double d : new double[]{0, .1, 1, 5, 16, 32, 33, 64, 96, 200, 512, 513, 1000, 10000, 100000, 30000000}) {
            var journey = RecallJourney.plan(d, true, RANGE);
            check(journey.departure() >= previous && journey.departure() <= 4, "monotonic wait <= 4s");
            previous = journey.departure();
            check(journey.ticks() / 20F >= journey.arriveAt() && journey.ticks() / 20F - journey.arriveAt() < .051,
                    "cooldown ends as the device lands");
            for (int side : new int[]{-1, 1}) for (int bearing = 0; bearing < 8; bearing++) {
                double angle = bearing * Math.PI / 4;
                var source = new Vec3(d * Math.sin(angle), .15, d * Math.cos(angle));
                float yaw = (float)(bearing * .83 - 1.2), pitch = (float)((bearing % 3 - 1) * .35);
                var eye = new Vec3(.3, 1.62, -.2);
                var first = view(eye, yaw, pitch, 0, 70 + 15 * (bearing % 3), side);
                var rest = new Quaternionf().rotationX((float)-Math.PI / 2);
                var plan = RecallFlight.plan(journey, OPEN, source, rest, source, true, first);
                check(journey.nearby() == d <= RANGE, "flies for real within the view distance");
                if (!journey.nearby()) {
                    var delta = plan.start().subtract(eye);
                    check(Math.abs(delta.x * (source.z - eye.z) - delta.z * (source.x - eye.x)) < 1e-3 * d, "far arrival keeps its compass bearing");
                    check(Math.abs(delta.length() - 24) < 1e-6, "far arrival appears 24 blocks out in the open");
                }
                RecallFlight.Frame last = null;
                boolean inHand = false;
                int steps = (int)Math.ceil(journey.duration() * 240);
                for (int i = 0; i <= steps; i++) {
                    float t = i / 240F;
                    // The player walks and turns throughout: the device must still land in the live hand.
                    var live = view(eye.add(.8 * t, 0, -.5 * t), yaw + .4F * t, pitch - .1F * t, t * 9, 70 + 15 * (bearing % 3), side);
                    var held = live.held();
                    var frame = RecallFlight.sample(plan, live, held, t);
                    var world = RecallFlight.worldMatrix(frame, live);
                    check(world.isFinite() && frame.pose().scale() >= 0 && frame.pose().scale() <= .551F, "finite bounded flight pose");
                    if (journey.nearby() && t < journey.arriveAt()) check(frame.pose().scale() > .5F, "a nearby device never disappears");
                    if (last != null && t < journey.arriveAt() && (journey.nearby() || t > journey.flightAt() + .01F)) {
                        double jump = frame.pose().position().distanceTo(last.pose().position());
                        worstJump = Math.max(worstJump, jump);
                        check(jump < .35, "no jump along the flight (" + jump + " at " + t + "s)");
                    }
                    // Both passes must put every vertex on the same pixel, wherever the hand pass takes over.
                    if (t >= journey.flightAt() && t < journey.arriveAt()) {
                        var hand = RecallFlight.handMatrix(frame, live);
                        var viewRotation = new Matrix4f().rotation(new Quaternionf(live.orientation()).conjugate());
                        float handFocal = (float)(1 / Math.tan(Math.toRadians(70) / 2)), worldFocal = handFocal * live.fovRatio();
                        var worldSpace = live.toView().mul(world);
                        var handSpace = viewRotation.mul(hand);
                        if (worldSpace.transformPosition(new Vector3f()).z < -.2F) for (var vertex : List.of(new Vector3f(-.4F, -.4F, -.1F), new Vector3f(.4F, .45F, .1F))) {
                            var a = screen(worldSpace, vertex, worldFocal);
                            var b = screen(handSpace, vertex, handFocal);
                            if (Math.abs(a[0]) > 4 || Math.abs(a[1]) > 4) continue; // off screen
                            double error = Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
                            worstProjection = Math.max(worstProjection, error);
                            check(error < 1e-4, "world and hand passes project alike (" + error + ")");
                        }
                        if (!inHand && RecallFlight.displayed(frame, live).distanceTo(live.eye()) < 1.5) { inHand = true; handoffs++; }
                    }
                    if (t >= journey.arriveAt()) check(RecallFlight.handMatrix(frame, live).equals(restingHeld(live), 1e-4F), "lands exactly on the held pose " + t + " " + RecallFlight.handMatrix(frame, live).toString().replace('\n', ' ') + " vs " + restingHeld(live).toString().replace('\n', ' '));
                    if (i % 20 == 0) {
                        var glow = RecallFx.world(plan, live, held, t);
                        check(glow.size() <= 260, "light budget (" + glow.size() + ")");
                        for (var quad : glow) for (float value : quad.xyz()) check(Float.isFinite(value), "finite light");
                    }
                    last = frame;
                    samples++;
                }
                // The frame just before landing is the held pose to within a hair: no pop into the hand.
                var live = view(eye.add(.8 * journey.arriveAt(), 0, -.5 * journey.arriveAt()), yaw + .4F * journey.arriveAt(),
                        pitch - .1F * journey.arriveAt(), journey.arriveAt() * 9, 70 + 15 * (bearing % 3), side);
                var before = RecallFlight.handMatrix(RecallFlight.sample(plan, live, live.held(), journey.arriveAt() - .002F), live);
                var gap = before.getTranslation(new Vector3f()).distance(restingHeld(live).getTranslation(new Vector3f()));
                check(gap < .01F, "the flight ends on the held pose (" + gap + ")");
            }
        }
        check(RecallJourney.plan(100000, true, RANGE).departure() == 4, "100,000 block wait");
        check(!RecallJourney.plan(5, false, RANGE).nearby(), "cross-dimension path uses magical arrival");
        check(RecallFlight.bearing(new Vec3(0, -10000, 0), Vec3.ZERO, true).equals(new Vec3(0, -1, 0)), "vertical source direction");
        String routes = obstacles();
        for (int i = 0; i < 1200; i++) {
            var chip = RecallMotion.frame(i / 200F);
            check(chip.chip().size() + chip.glow().size() < 500, "chip budget");
            if (i / 200F > .83F) check(chip.chip().isEmpty() && chip.glow().isEmpty(), "no chip residue during long wait");
            check(RecallFx.burst(i / 200F).size() <= 30, "catch burst budget");
        }
        Constants.LOG.info("[recall-visual] PASS {} samples, {} hand-pass handoffs, worst step {} blocks, worst pass mismatch {}; {}; {}",
                samples, handoffs, String.format(Locale.ROOT, "%.3f", worstJump), String.format(Locale.ROOT, "%.1e", worstProjection), outside, routes);
    }

    /**
     * Seen from outside (the caller's third person, another player watching): a plain world flight into the hand the
     * player model holds out, while that player walks and turns.
     */
    private static String outside() {
        var right = RecallFlight.thirdPersonHeld(Vec3.ZERO, 0, 1).position();
        var left = RecallFlight.thirdPersonHeld(Vec3.ZERO, 0, -1).position();
        // Facing south (+Z) the right hand is to the west (-X), at the hip, a little ahead of the body.
        check(right.x < -.2 && right.x > -.55 && right.y > .5 && right.y < 1 && right.z > 0 && right.z < .6,
                "the resting right hand is at the body's right hip (" + right + ")");
        check(Math.abs(left.x + right.x) < 1e-5 && Math.abs(left.y - right.y) < 1e-5, "the left hand mirrors the right");
        var turned = RecallFlight.thirdPersonHeld(new Vec3(5, 64, -3), 90, 1).position().subtract(5, 64, -3);
        check(turned.distanceTo(new Vec3(-right.z, right.y, right.x)) < 1e-4, "the hand turns with the body (" + turned + ")");
        int samples = 0;
        double worstJump = 0;
        for (double d : new double[]{0, 5, 32, 200, 1000}) {
            var journey = RecallJourney.plan(d, true, RANGE);
            for (int side : new int[]{-1, 1}) for (int bearing = 0; bearing < 8; bearing++) {
                double angle = bearing * Math.PI / 4;
                var source = new Vec3(d * Math.sin(angle), .15, d * Math.cos(angle));
                float yaw = bearing * 40 - 70;
                var feet = new Vec3(.3, 0, -.2);
                var plan = RecallFlight.plan(journey, OPEN, source, new Quaternionf().rotationX((float)-Math.PI / 2), source, true,
                        RecallFlight.View.body(feet.add(0, 1.62, 0), yaw, 10, side), RecallFlight.thirdPersonHeld(feet, yaw, side));
                RecallFlight.Frame last = null;
                var watcher = new Vec3(4, 2, 5);
                for (int i = 0, steps = (int)Math.ceil(journey.duration() * 240); i <= steps; i++) {
                    float t = i / 240F;
                    var walked = feet.add(.8 * t, 0, -.5 * t);
                    var body = RecallFlight.View.body(walked.add(0, 1.62, 0), yaw + 25 * t, 10 - 5 * t, side);
                    // The drawn hand swings a little as the arm moves; the stand-in is the resting one.
                    var hand = RecallFlight.thirdPersonHeld(walked.add(0, .04 * Math.sin(t * 7), 0), yaw + 25 * t, side);
                    var frame = RecallFlight.sample(plan, body, hand, t);
                    var plain = frame.pose().matrix(body.eye());
                    float reach = 1 + plain.getTranslation(new Vector3f()).length();
                    check(RecallFlight.worldMatrix(frame, body).equals(plain, 2e-6F * reach),
                            "outside: the flight is a plain world pose");
                    check(RecallFlight.displayed(frame, body).distanceTo(frame.pose().position()) < 4e-6 * reach, "outside: drawn where it is");
                    if (last != null && t < journey.arriveAt() && (journey.nearby() || t > journey.flightAt() + .01F)) {
                        double jump = frame.pose().position().distanceTo(last.pose().position());
                        worstJump = Math.max(worstJump, jump);
                        check(jump < .35, "outside: no jump along the flight (" + jump + " at " + t + "s)");
                    }
                    if (t >= journey.arriveAt()) check(frame.pose().position().distanceTo(hand.position()) < 1e-6
                            && frame.pose().matrix(Vec3.ZERO).equals(hand.matrix(Vec3.ZERO), 1e-5F), "outside: lands in the drawn hand");
                    if (i % 20 == 0) {
                        var glow = RecallFx.world(plan, body, hand, t, watcher, new Quaternionf().rotationY(.7F));
                        check(glow.size() <= 260, "outside: light budget (" + glow.size() + ")");
                        for (var quad : glow) for (float value : quad.xyz()) check(Float.isFinite(value), "outside: finite light");
                    }
                    last = frame;
                    samples++;
                }
                float at = journey.arriveAt();
                var walked = feet.add(.8 * at, 0, -.5 * at);
                var hand = RecallFlight.thirdPersonHeld(walked.add(0, .04 * Math.sin(at * 7), 0), yaw + 25 * at, side);
                var before = RecallFlight.sample(plan, RecallFlight.View.body(walked.add(0, 1.62, 0), yaw + 25 * at, 10 - 5 * at, side),
                        hand, at - .002F);
                check(before.pose().position().distanceTo(hand.position()) < .01, "outside: the flight ends in the hand ("
                        + before.pose().position().distanceTo(hand.position()) + ")");
            }
        }
        return String.format(Locale.ROOT, "outside %d samples, worst step %.3f", samples, worstJump);
    }

    /** The largest view distance, 32 chunks: real flights up to 512 blocks. */
    static final double RANGE = 512;
    static final Vec3 TERRAIN_EYE = new Vec3(.5, 1.62, .5);
    /** Ground below {@code ground} (y 0 unless the case shapes its own) plus the case's blocks; the player stands at the origin looking toward -Z. */
    record Case(String name, Set<Long> solid, Vec3 source, boolean buried, int ground) {
        Case(String name, Set<Long> solid, Vec3 source, boolean buried) { this(name, solid, source, buried, 0); }
        RecallPath.Blocks blocks() { return (x, y, z) -> y < ground || solid.contains(BlockPos.asLong(x, y, z)); }
        RecallFlight.Plan plan() {
            return RecallFlight.plan(RecallJourney.plan(source.distanceTo(TERRAIN_EYE), true, RANGE), blocks(), source, new Quaternionf(), source, true,
                    view(TERRAIN_EYE, 0, 0, 0, 70, 1));
        }
    }
    static List<Case> terrains() {
        var cases = new ArrayList<Case>();
        cases.add(new Case("open", Set.of(), new Vec3(6.5, .1, -9.5), false));
        var wall = new HashSet<Long>();
        for (int x = -6; x <= 6; x++) for (int y = 0; y <= 4; y++) wall.add(BlockPos.asLong(x, y, -5));
        cases.add(new Case("wall", wall, new Vec3(.5, .1, -11.5), false));
        var room = new HashSet<Long>();
        for (int x = -4; x <= 4; x++) for (int y = 0; y <= 4; y++) for (int z = -16; z <= -8; z++) {
            boolean shell = x == -4 || x == 4 || y == 4 || z == -16 || z == -8;
            boolean door = z == -8 && x == 0 && y <= 1;
            if (shell && !door) room.add(BlockPos.asLong(x, y, z));
        }
        cases.add(new Case("room with a door", room, new Vec3(2.5, .1, -13.5), false));
        var pillar = new HashSet<Long>();
        for (int y = 0; y <= 9; y++) for (int x = -1; x <= 1; x++) pillar.add(BlockPos.asLong(x, y, -6));
        cases.add(new Case("pillar", pillar, new Vec3(.5, .1, -12.5), false));
        cases.add(new Case("buried", Set.of(), new Vec3(3.5, -2.8, -9.5), true));
        cases.add(new Case("ravine, device on the cliff", ravine(), new Vec3(-6.5, 21.1, -4.5), false));
        cases.add(new Case("ravine, device on the far rim", ravine(), new Vec3(7.5, 21.1, -9.5), false));
        cases.add(new Case("lake below a hill", lake(), new Vec3(.5, -29.9, -58.5), false, -31));
        // The worst case: a mountain wall 60 high and 200 wide, too tall to arc over, 150 blocks out.
        var mountain = new HashSet<Long>();
        for (int x = -100; x <= 100; x++) for (int y = 0; y <= 60; y++) for (int z = -52; z <= -50; z++) mountain.add(BlockPos.asLong(x, y, z));
        cases.add(new Case("behind a mountain wall", mountain, new Vec3(.5, .1, -150.5), false));
        // Across a valley: 180 blocks out, behind a ridge 25 blocks high and 80 wide.
        var ridge = new HashSet<Long>();
        for (int x = -40; x <= 40; x++) for (int y = 0; y <= 25; y++) for (int z = -62; z <= -58; z++) ridge.add(BlockPos.asLong(x, y, z));
        cases.add(new Case("valley behind a ridge", ridge, new Vec3(.5, .1, -180.5), false));
        cases.add(new Case("in a chest", Set.of(BlockPos.asLong(3, 0, -8)), new Vec3(3.5, .55, -7.5), true));
        // A barrel on its side in a wall, its lid toward the player: roofed, walled left and right, open both ways.
        var shelf = new HashSet<Long>();
        for (int x = -3; x <= 3; x++) for (int y = 0; y <= 4; y++) shelf.add(BlockPos.asLong(x, y, -9));
        cases.add(new Case("barrel in a wall", shelf, new Vec3(.5, 1.55, -8.5), true));
        return cases;
    }
    /** The first point of the route outside the block the device started in. */
    static Vec3 exit(Case c, RecallFlight.Plan plan) {
        var path = plan.path();
        for (int i = 0; i < path.samples(); i++) if (RecallPath.clear(c.blocks(), path.sample(i))) return path.sample(i);
        throw new AssertionError(c.name() + ": never leaves the block");
    }

    /** Terrain the route must find its way through, in the open where it can. */
    private static String obstacles() {
        var report = new StringBuilder("routes:");
        var eye = TERRAIN_EYE;
        var looking = view(eye, 0, 0, 0, 70, 1); // yaw 0 looks toward -Z
        for (var c : terrains()) {
            var blocks = c.blocks();
            long began = System.nanoTime();
            var plan = c.plan();
            var path = plan.path(); // the whole search, not only the arcs
            double millis = (System.nanoTime() - began) / 1e6;
            check(plan.journey().nearby(), c.name() + ": flies for real from where it lies");
            check(path.sample(0).distanceTo(plan.start()) < 1e-6 && path.at(path.length()).distanceTo(plan.held0()) < 1e-6,
                    c.name() + ": route runs from the hover to the hand");
            double inside = 0;
            boolean out = false;
            for (int i = 1; i < path.samples(); i++) {
                var p = path.sample(i);
                if (path.approach() > 0 && p.distanceTo(plan.held0()) < 1.4) break;
                boolean open = RecallPath.clear(blocks, p);
                if (!open) {
                    check(c.buried() && !out, c.name() + ": the route crosses a block at " + p);
                    inside += p.distanceTo(path.sample(i - 1));
                } else out = true;
            }
            if (c.buried()) {
                check(plan.start().equals(c.source()), "buried: no lift inside the ground");
                check(inside < 4, "buried: digs out by the short way (" + inside + " blocks)");
            } else check(plan.start().y - c.source().y > .9, c.name() + ": rises before it flies");
            if (c.name().equals("in a chest")) {
                var exit = exit(c, plan);
                check(inside < 1 && exit.y > 1 && Math.abs(exit.x - 3.5) < .3 && Math.abs(exit.z + 7.5) < .3,
                        "chest: rises straight out through the lid (" + exit + ", " + inside + " inside)");
            }
            if (c.name().equals("barrel in a wall")) {
                var exit = exit(c, plan);
                check(inside < 1 && exit.z > -8 && exit.y < 2.1, "barrel: leaves through its open face toward the player (" + exit + ")");
            }
            check(millis < 250, c.name() + ": plans quickly (" + millis + " ms)");
            report.append(String.format(Locale.ROOT, " %s %.1f blocks%s %.1f ms;", c.name(), path.length(), path.searched ? " searched" : "", millis));
        }
        // A player in a cave: the distant device leaves the cave's open side, not the rock.
        var cave = new HashSet<Long>();
        for (int x = -8; x <= 8; x++) for (int y = -3; y <= 6; y++) for (int z = -8; z <= 8; z++) {
            boolean hollow = Math.abs(x) <= 2 && y >= 0 && y <= 3 && Math.abs(z) <= 2 || x >= 0 && x <= 8 && y >= 0 && y <= 2 && Math.abs(z) <= 1;
            if (!hollow) cave.add(BlockPos.asLong(x, y, z));
        }
        RecallPath.Blocks caveBlocks = (x, y, z) -> cave.contains(BlockPos.asLong(x, y, z));
        var far = RecallJourney.plan(5000, true, RANGE);
        var plan = RecallFlight.plan(far, caveBlocks, Vec3.ZERO, new Quaternionf(), new Vec3(-5000, 0, 20), true, looking);
        check(RecallPath.clear(caveBlocks, plan.start()), "cave: the distant device appears in the open");
        check(plan.start().x > 3, "cave: it comes in along the tunnel (" + plan.start() + ")");
        report.append(String.format(Locale.ROOT, " cave entry %.1f blocks out", plan.start().distanceTo(eye)));
        // A player at the bottom of a ravine and a distant device to the west, behind the cliff: it comes down out of
        // the open sky, and its whole route stays in the open (the screenshot of 24 September).
        var ravine = new Case("ravine", ravine(), Vec3.ZERO, false);
        var dive = RecallFlight.plan(far, ravine.blocks(), Vec3.ZERO, new Quaternionf(), new Vec3(-5000, 5, 0), true, looking);
        check(RecallPath.clear(ravine.blocks(), dive.start()), "ravine: the distant device appears in the open");
        var route = dive.path();
        for (int i = 0; i < route.samples(); i++) {
            var p = route.sample(i);
            if (route.approach() > 0 && p.distanceTo(dive.held0()) < 1.4) break;
            check(RecallPath.clear(ravine.blocks(), p), "ravine: the distant device flies in the open, not through the cliff (" + p + ")");
        }
        report.append(String.format(Locale.ROOT, " ravine entry at %.0f degrees up", Math.toDegrees(Math.asin((dive.start().y - eye.y) / dive.start().distanceTo(eye)))));
        return report.toString();
    }

    /**
     * The player on a hilltop above a lake (the screenshot of 24 September): the slope falls 30 blocks from 20 to 40
     * blocks out, the lake bed is flat beyond. Water does not block the device, so only the ground is solid.
     */
    static Set<Long> lake() {
        var ground = new HashSet<Long>();
        for (int x = -30; x <= 30; x++) for (int z = -80; z <= 10; z++) {
            int surface = z > -20 ? 0 : z > -40 ? -(-20 - z) * 3 / 2 : -30;
            for (int y = -31; y < surface; y++) ground.add(BlockPos.asLong(x, y, z));
        }
        return ground;
    }

    /** A ravine two blocks wide running along z, its floor at y 0 and its walls 21 blocks high. */
    static Set<Long> ravine() {
        var walls = new HashSet<Long>();
        for (int x = -12; x <= 14; x++) for (int y = 0; y <= 20; y++) for (int z = -30; z <= 30; z++)
            if (x <= -2 || x >= 3) walls.add(BlockPos.asLong(x, y, z));
        return walls;
    }
}
