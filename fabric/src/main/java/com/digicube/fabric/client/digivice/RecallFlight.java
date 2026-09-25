package com.digicube.fabric.client.digivice;

import com.digicube.digivice.RecallJourney;
import com.digicube.fabric.client.render.DroppedDigiviceRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The recalled device as a function of time. One continuous motion: it wakes on the ground, spins up to a hover
 * facing the player, flies a planned route around blocks into view and lands exactly on the held pose, still moving
 * a little so the hand can absorb it. Poses are rigid and in world space; the last stretch is drawn in the hand pass
 * with a view-space squeeze that makes both passes project identically, so nothing jumps where they meet.
 */
public final class RecallFlight {
    private RecallFlight() {}
    /** Blocks per second the device still has when it meets the hand. */
    static final double CATCH_SPEED = 1.3;
    /** Nearer than this to the eye the device is drawn in the hand pass, so a wall beside the player cannot cut it. */
    static final double HAND_PASS_DISTANCE = 1.5;
    /** The stretch of route, before the hand, that bends to follow a player who moved during the flight. */
    static final double SHIFT_LEG = 12;

    public record Pose(Vec3 position, Quaternionf rotation, float scale) {
        public Matrix4f matrix(Vec3 origin) {
            Vec3 p = position.subtract(origin);
            return new Matrix4f().translation((float)p.x, (float)p.y, (float)p.z).rotate(rotation).scale(scale);
        }
        /** A rigid, uniformly scaled matrix relative to {@code origin}, as a world pose. */
        public static Pose of(Vec3 origin, Matrix4f m) {
            var t = m.getTranslation(new Vector3f());
            return new Pose(origin.add(t.x, t.y, t.z), m.getUnnormalizedRotation(new Quaternionf()), m.getScale(new Vector3f()).x);
        }
    }

    /**
     * One frame's first-person camera: eye, orientation (view to world), the view bob both passes apply, the ratio of
     * the world projection's focal length to the hand pass's, and the hand's sway behind the view.
     */
    public record View(Vec3 eye, Quaternionf orientation, Matrix4f bob, float fovRatio, Quaternionf sway, int side) {
        /** Camera-relative world to bobbed view space. */
        public Matrix4f toView() { return new Matrix4f(bob).rotate(new Quaternionf(orientation).conjugate()); }
        /** The resting held device in bobbed view space, as the hand pass places it. */
        public Matrix4f heldView() {
            return new Matrix4f(bob).rotate(sway).translate(side * .56F, -.52F, -.72F).mul(heldDisplay(side));
        }
        /** The held device as a rigid world pose. */
        public Pose held() {
            var m = toView().invert().mul(heldView());
            var t = m.getTranslation(new Vector3f());
            return new Pose(eye.add(t.x, t.y, t.z), m.getUnnormalizedRotation(new Quaternionf()), m.getScale(new Vector3f()).x);
        }
        public Vec3 toWorld(Vector3f cameraSpace) {
            var p = orientation.transform(new Vector3f(cameraSpace));
            return eye.add(p.x, p.y, p.z);
        }
        public View withBob(Matrix4f bob) { return new View(eye, orientation, bob, fovRatio, sway, side); }
        /**
         * A player's own head, for a recall seen from outside (third person, or another player's): no bob, no sway and
         * one projection, so the flight is a plain world motion. Yaw and pitch in degrees, as the entity has them.
         */
        public static View body(Vec3 eye, float yRot, float xRot, int side) {
            var orientation = new Quaternionf().rotationYXZ((float)Math.PI - (float)Math.toRadians(yRot), -(float)Math.toRadians(xRot), 0);
            return new View(eye, orientation, new Matrix4f(), 1, new Quaternionf(), side);
        }
    }

    /**
     * Everything decided once per recall. The route runs through a point ahead of the eye ({@code approach}, fixed in
     * the world once planned, so the route checked is the route flown) and ends where the hand was ({@code held0});
     * only its last leg bends toward where the hand is now. The planner may still be searching: {@link #path}
     * finishes it.
     */
    public record Plan(RecallJourney journey, Vec3 source, Quaternionf rest, Vec3 start, RecallPath.Planner planner,
                       Vec3 approach, Vec3 held0) {
        public RecallPath path() { return planner.path(); }
    }

    public record Frame(Pose pose, float lambda, float progress) {}

    public static Plan plan(RecallJourney journey, RecallPath.Blocks blocks, Vec3 source, Quaternionf rest,
                            Vec3 remoteSource, boolean sameDimension, View view) {
        return plan(journey, blocks, source, rest, remoteSource, sameDimension, view, view.held());
    }
    /** {@code held} is where the device lands: the first-person hand, or the hand of a player seen from outside. */
    public static Plan plan(RecallJourney journey, RecallPath.Blocks blocks, Vec3 source, Quaternionf rest,
                            Vec3 remoteSource, boolean sameDimension, View view, Pose held) {
        blocks = RecallPath.cache(blocks);
        Vec3 start = journey.nearby() ? source.add(0, RecallPath.hover(blocks, source), 0)
                : RecallPath.entry(blocks, view.eye(), bearing(remoteSource, view.eye(), sameDimension));
        // A buried device comes straight up out of the ground before it turns toward the player.
        Vec3 open = journey.nearby() && RecallPath.buried(blocks, source) ? RecallPath.surface(blocks, source, held.position()) : null;
        if (open == null) open = start;
        var approachView = approach(blocks, view, open);
        Vec3 approach = approachView == null ? null : view.toWorld(approachView);
        return new Plan(journey, source, rest, start, new RecallPath.Planner(blocks, start, open, approach, held.position()),
                approach, held.position());
    }

    /** A distant source keeps its compass bearing; its elevation is compressed so a deep source reads as a direction. */
    public static Vec3 bearing(Vec3 source, Vec3 eye, boolean sameDimension) {
        if (!sameDimension) return new Vec3(0, 1, 0);
        Vec3 delta = source.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal < .001) return new Vec3(0, delta.y < 0 ? -1 : 1, 0);
        return new Vec3(delta.x / horizontal, Math.clamp(delta.y / horizontal, -.28, .5), delta.z / horizontal).normalize();
    }

    /** A point ahead of the eye, within 28 degrees of the view, on the side the device comes from. */
    static Vector3f approach(RecallPath.Blocks blocks, View view, Vec3 start) {
        Vec3 to = start.subtract(view.eye());
        double distance = to.length();
        if (distance < 2.6) return null;
        var v = new Quaternionf(view.orientation()).conjugate()
                .transform(new Vector3f((float)(to.x / distance), (float)(to.y / distance), (float)(to.z / distance)));
        var forward = new Vector3f(0, 0, -1);
        float cos = v.dot(forward), limit = (float)Math.toRadians(28);
        if (cos < Math.cos(limit)) {
            var across = new Vector3f(v).fma(-cos, forward);
            if (across.lengthSquared() < 1e-6) across.set(view.side(), .35F, 0);
            across.normalize();
            v = new Vector3f(forward).mul((float)Math.cos(limit)).add(across.mul((float)Math.sin(limit)));
        }
        float reach = (float)Math.min(2.3, distance * .55);
        var world = view.orientation().transform(new Vector3f(v));
        double open = RecallPath.free(blocks, view.eye(), new Vec3(world.x, world.y, world.z), reach + .5);
        return v.mul((float)Math.max(1.6, Math.min(reach, open - .5)));
    }

    public static Frame sample(Plan plan, View view, Pose held, float seconds) {
        var journey = plan.journey();
        if (seconds >= journey.arriveAt()) return new Frame(held, 1, 1);
        if (seconds < journey.flightAt()) {
            if (!journey.nearby()) return new Frame(new Pose(plan.start(), held.rotation(), 0), 0, 0);
            // Wakes (a short shiver), then rises with one decelerating turn while it turns its face to the player.
            float lead = (seconds - journey.departure() + .24F) / .24F;
            float shiver = lead > 0 && lead < 1 ? .07F * (float)Math.sin(Math.PI * lead) * (float)Math.sin(seconds * 75) : 0;
            float v = Math.clamp((seconds - journey.departure()) / journey.lift(), 0, 1);
            var position = plan.source().lerp(plan.start(), smoother(v));
            float spin = (float)(Math.PI * 2) * (1 - smoother(v));
            var rotation = new Quaternionf().rotationY(spin + shiver)
                    .mul(new Quaternionf(plan.rest()).slerp(facing(view, held, position), RecallMotion.smooth(v)));
            return new Frame(new Pose(position, rotation, DroppedDigiviceRenderer.SCALE), 0, 0);
        }
        float u = (seconds - journey.flightAt()) / journey.flight();
        var path = plan.path();
        double length = path.length(), approach = path.approach();
        float m0 = journey.nearby() ? 0 : 1.1F;
        float m1 = (float)Math.min(.8, CATCH_SPEED * journey.flight() / Math.max(1e-3, length));
        double s = length * hermite(u, m0, m1);
        var position = path.at(s).add(shift(plan, held, s));
        float lambda = RecallMotion.smooth(length - approach > 1e-3 ? (float)((s - approach) / (length - approach)) : u);
        // Faces the player on the way, settling into the held orientation over the last stretch, leaning into its speed.
        var rotation = facing(view, held, position).slerp(held.rotation(), RecallMotion.smooth((u - .55F) / .45F));
        double speed = length * hermiteSlope(u, m0, m1) / journey.flight();
        var ahead = path.at(s + .05).subtract(path.at(s - .05));
        var axis = new Vector3f(0, 1, 0).cross((float)ahead.x, (float)ahead.y, (float)ahead.z);
        if (axis.lengthSquared() > 1e-8) {
            float lean = (float)Math.min(.3, speed * .025) * (1 - RecallMotion.smooth((u - .6F) / .4F));
            rotation = new Quaternionf().rotationAxis(lean, axis.normalize()).mul(rotation);
        }
        float scale = journey.nearby()
                ? DroppedDigiviceRenderer.SCALE + (held.scale() - DroppedDigiviceRenderer.SCALE) * RecallMotion.smooth(u)
                : held.scale() * RecallMotion.smooth(u / .45F);
        return new Frame(new Pose(position, rotation, scale), lambda, u);
    }

    /**
     * How far the hand has moved since planning, spread over the last {@link #SHIFT_LEG} blocks (at least the last
     * leg): the rest is the route as checked. Over a long flight the player may walk a dozen blocks, and a shift
     * crammed into the final two would throw the device at the hand.
     */
    private static Vec3 shift(Plan plan, Pose held, double s) {
        double length = plan.path().length(), from = Math.min(plan.path().approach(), Math.max(0, length - SHIFT_LEG));
        if (s <= from) return Vec3.ZERO;
        float w = length - from < 1e-6 ? 1 : RecallMotion.smooth((float)((s - from) / (length - from)));
        return held.position().subtract(plan.held0()).scale(w);
    }

    /** The held orientation turned so the screen looks at the eye from where the device is. */
    private static Quaternionf facing(View view, Pose held, Vec3 position) {
        var back = view.orientation().transform(new Vector3f(0, 0, 1));
        var look = view.eye().subtract(position);
        if (look.lengthSqr() < 1e-6) return new Quaternionf(held.rotation());
        var toEye = new Vector3f((float)look.x, (float)look.y, (float)look.z).normalize();
        var axis = new Vector3f(back).cross(toEye);
        float cos = Math.clamp(back.dot(toEye), -1, 1);
        // Directly behind the camera the turn has no stable axis; it fades out there (unseen anyway).
        float weight = RecallMotion.smooth((cos + 1) / .6F);
        if (axis.lengthSquared() < 1e-10 || weight <= 0) return new Quaternionf(held.rotation());
        return new Quaternionf().rotationAxis((float)Math.acos(cos) * weight, axis.normalize()).mul(held.rotation());
    }

    /** Level pass: camera-relative model matrix. */
    public static Matrix4f worldMatrix(Frame frame, View view) {
        var toView = view.toView();
        return new Matrix4f(toView).invert().mul(squeeze(view, frame.lambda())).mul(toView).mul(frame.pose().matrix(view.eye()));
    }
    /** Hand pass: the absolute pose-stack matrix. Its projection matches {@link #worldMatrix}; at lambda 1 it is the held pose. */
    public static Matrix4f handMatrix(Frame frame, View view) {
        float k = view.fovRatio();
        return new Matrix4f().rotation(view.orientation()).scale(k, k, 1).mul(squeeze(view, frame.lambda()))
                .mul(view.toView()).mul(frame.pose().matrix(view.eye()));
    }
    /** The hand pass's view-space position, the frame the hand and its recoil live in. */
    public static Vector3f inView(Frame frame, View view) {
        return new Matrix4f().rotation(new Quaternionf(view.orientation()).conjugate()).mul(handMatrix(frame, view))
                .getTranslation(new Vector3f());
    }
    /** Where the device is drawn, in world space. */
    public static Vec3 displayed(Frame frame, View view) {
        var p = worldMatrix(frame, view).getTranslation(new Vector3f());
        return view.eye().add(p.x, p.y, p.z);
    }
    /** Blends the world projection into the hand pass's as the device comes in (lambda 0 to 1). */
    private static Matrix4f squeeze(View view, float lambda) {
        float g = 1 + lambda * (1 / view.fovRatio() - 1);
        return new Matrix4f().scaling(g, g, 1);
    }

    public static Quaternionf heldRotation(int side) {
        return new Quaternionf().rotationXYZ((float)Math.toRadians(8), (float)Math.toRadians(side * -15), (float)Math.toRadians(side * -6));
    }
    /** digivice.json's first-person display, mirrored for the left hand as vanilla does. */
    public static Matrix4f heldDisplay(int side) {
        return new Matrix4f().translation(side * -1.5F / 16, 4F / 16, -2F / 16).rotate(heldRotation(side)).scale(.52F);
    }
    /** digivice.json's third-person display (alike for both hands): the held device in the hand anchor's frame. */
    public static Matrix4f thirdPersonDisplay() {
        return new Matrix4f().translation(0, 2F / 16, 1F / 16).rotateX((float)Math.toRadians(65)).scale(.4F);
    }
    /**
     * Where a player seen from outside holds the device with the arm at rest (the item pose, no swing): the body turned
     * by {@code bodyYaw} (degrees) as LivingEntityRenderer turns it, the arm as HumanoidModel and ItemInHandLayer
     * carry it. A stand-in until the hand is drawn; the drawn hand is read from ItemInHandLayer.
     */
    public static Pose thirdPersonHeld(Vec3 feet, float bodyYaw, int side) {
        var m = new Matrix4f().rotateY((float)Math.toRadians(180 - bodyYaw)).scale(-.9375F, -.9375F, .9375F).translate(0, -1.501F, 0)
                .translate(-side * 5F / 16, 2F / 16, 0).rotateX(-(float)Math.PI / 10)
                .rotateX(-(float)Math.PI / 2).rotateY((float)Math.PI).translate(side / 16F, 2F / 16, -10F / 16)
                .mul(thirdPersonDisplay());
        return Pose.of(feet, m);
    }


    /** Cubic Hermite from 0 to 1 with start and end slopes in units of the mean speed; monotone for slopes up to 3. */
    static double hermite(float u, float m0, float m1) {
        double t = Math.clamp(u, 0, 1), t2 = t * t, t3 = t2 * t;
        return (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) + (t3 - t2) * m1;
    }
    static double hermiteSlope(float u, float m0, float m1) {
        double t = Math.clamp(u, 0, 1), t2 = t * t;
        return (3 * t2 - 4 * t + 1) * m0 + (-6 * t2 + 6 * t) + (3 * t2 - 2 * t) * m1;
    }
    static float smoother(float p) { p = Math.clamp(p, 0, 1); return p * p * p * (p * (p * 6 - 15) + 10); }
    static float easeOut(float p) { p = 1 - Math.clamp(p, 0, 1); return 1 - p * p * p; }
}
