package com.digicube.entity;

import com.digicube.digimon.WhipAttacks;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One long arm used as a whip ({@link WhipAttacks}), by a rider or the AI, run the same way on the server (which hits
 * with it) and on every client (which draws it). The arm's root turns on a spring toward a target, and each section
 * after it follows the root's own turn a little later ({@code lag} ticks a section), so the arm curls behind a turn and
 * the wave runs out to the pad and cracks there when the root stops.
 * <ul>
 *     <li>{@link Stage#WIND}: while the button is held the root is drawn back behind its own side and up, and the arm
 *     coils there, further back and tighter toward the pad the longer it is held: the momentum being gathered
 *     ({@code charge}, full after {@code chargeTicks}).</li>
 *     <li>{@link Stage#LASH}: let go, the coil springs open and the root swings at the aim on a stiff, springy turn,
 *     and keeps swinging after it as the aim moves; everything the swept arm touches is struck once.</li>
 *     <li>{@link Stage#RECOVER}: it falls back to its rest and gives the arm back to the clips.</li>
 * </ul>
 * Every section is described by its yaw and pitch alone, so the drawn arm cannot roll or spin: the model turns each
 * section onto its direction keeping its roll to that direction's own frame, and the pad's palm faces the way it
 * sweeps (NativeGroundModel.whip).
 * Angles are the body's: yaw relative to the body's facing (positive to its right, as Minecraft's yaw turns), pitch as
 * Minecraft's (positive down).
 */
public final class WhipArm {
    public enum Stage { IDLE, WIND, LASH, RECOVER }
    public enum Event { NONE, LASH, END }

    /** Ticks of the root's turns kept, for the sections' lag. */
    private static final int HISTORY = 40;
    /** A wind-up held this long lets go by itself. */
    public static final int MAX_WIND_TICKS = 20 * 6;

    private final WhipAttacks.Spec spec;
    private Stage stage = Stage.IDLE;
    private int side = 1, stageTicks, heldTicks;
    private boolean releaseAsked;
    private float charge, weight, previousWeight, curl, previousCurl;
    private float yaw, pitch, yawSpeed, pitchSpeed;
    private final float[] yaws = new float[HISTORY], pitches = new float[HISTORY];
    private int newest;

    public WhipArm(WhipAttacks.Spec spec) { this.spec = spec; }

    public WhipAttacks.Spec spec() { return spec; }
    public Stage stage() { return stage; }
    /** +1 the left arm, -1 the right. */
    public int side() { return side; }
    public float charge() { return charge; }
    /** Ticks the wind-up has been held. */
    public int heldTicks() { return heldTicks; }
    /** Ticks into the current stage. */
    public int stageTicks() { return stageTicks; }
    public boolean busy() { return stage != Stage.IDLE; }
    /** How much of the arm's pose the whip has (the clips have the rest), eased over a frame. */
    public float weight(float partial) { return Mth.lerp(partial, previousWeight, weight); }

    /** The button went down: {@code side}'s arm is drawn back. */
    public void wind(int side) {
        if (stage == Stage.IDLE || side != this.side) {
            yaw = -side * spec.rest()[0];
            pitch = spec.rest()[1];
            yawSpeed = pitchSpeed = 0;
            for (int i = 0; i < HISTORY; i++) { yaws[i] = yaw; pitches[i] = pitch; }
            curl = previousCurl = 0;
        }
        this.side = side;
        stage = Stage.WIND;
        stageTicks = heldTicks = 0;
        releaseAsked = false;
        charge = 0;
    }

    /** The button came up: the lash goes as soon as the arm is back far enough ({@code min_wind_ticks}). */
    public void release() { if (stage == Stage.WIND) releaseAsked = true; }

    /** The lash goes now, with this charge (a client following the server's lash). */
    public void lashNow(float charge) {
        if (stage == Stage.LASH) return;
        if (stage == Stage.IDLE) wind(side);
        this.charge = charge;
        stage = Stage.LASH;
        stageTicks = 0;
    }

    /** It stops being a whip (the rider left, the AI gave it up): the arm falls back. */
    public void cancel() {
        if (stage == Stage.WIND || stage == Stage.LASH) { stage = Stage.RECOVER; stageTicks = 0; }
    }

    /** One tick, with the aim (the rider's crosshair, the AI's prey) relative to the body. */
    public Event tick(float aimYaw, float aimPitch) {
        previousWeight = weight;
        previousCurl = curl;
        if (stage == Stage.IDLE) { weight = 0; curl = 0; return Event.NONE; }
        Event event = Event.NONE;
        stageTicks++;
        if (stage == Stage.WIND) {
            heldTicks++;
            charge = Math.min(1, heldTicks / (float) spec.chargeTicks());
            if (releaseAsked && heldTicks >= spec.minWindTicks() || heldTicks >= MAX_WIND_TICKS) { stage = Stage.LASH; stageTicks = 0; event = Event.LASH; }
        } else if (stage == Stage.LASH && stageTicks > spec.lashTicks()) { stage = Stage.RECOVER; stageTicks = 0; }
        else if (stage == Stage.RECOVER && stageTicks > spec.recoverTicks()) { stage = Stage.IDLE; event = Event.END; }

        float targetYaw, targetPitch;
        float[] spring;
        switch (stage) {
            case WIND -> {
                // Behind its own side and up, drawn further back and higher as the momentum builds, leaning a little
                // after the aim; a slow flex keeps it alive without turning the pad.
                float[] from = spec.wind(), to = spec.windCharged();
                targetYaw = -side * Mth.lerp(charge, from[0], to[0]) + aimYaw * .2F + 4 * charge * Mth.sin(heldTicks * .33F);
                targetPitch = Mth.lerp(charge, from[1], to[1]);
                spring = spec.windSpring();
            }
            case LASH -> {
                targetYaw = Mth.clamp(aimYaw, -175, 175);
                targetPitch = Mth.clamp(aimPitch, -70, 70);
                spring = spec.lashSpring();
            }
            default -> {
                targetYaw = -side * spec.rest()[0];
                targetPitch = spec.rest()[1];
                spring = spec.recoverSpring();
            }
        }
        // Yaw is not wrapped: the arm reaches the far side through the front, never round the back of the body.
        yawSpeed += spring[0] * (targetYaw - yaw) - spring[1] * yawSpeed;
        pitchSpeed += spring[0] * (targetPitch - pitch) - spring[1] * pitchSpeed;
        float speed = Mth.sqrt(yawSpeed * yawSpeed + pitchSpeed * pitchSpeed);
        if (speed > spec.maxTurn()) { yawSpeed *= spec.maxTurn() / speed; pitchSpeed *= spec.maxTurn() / speed; }
        yaw = Mth.clamp(yaw + yawSpeed, -200, 200);
        pitch = Mth.clamp(pitch + pitchSpeed, -85, 85);
        newest = (newest + 1) % HISTORY;
        yaws[newest] = yaw;
        pitches[newest] = pitch;
        // The coil tightens with the charge and springs open in the first ticks of the lash.
        curl = stage == Stage.WIND ? curl + (charge - curl) * .3F : curl * (stage == Stage.LASH ? .45F : .6F);
        weight = switch (stage) {
            case WIND -> Math.min(1, weight + .34F);
            case LASH -> 1;
            case RECOVER -> 1 - Mth.clamp(stageTicks / (float) spec.recoverTicks(), 0, 1);
            default -> 0;
        };
        return event;
    }

    private float sample(float[] history, float delay) {
        delay = Mth.clamp(delay, 0, HISTORY - 2);
        int i = (int) delay;
        float f = delay - i;
        float a = history[Math.floorMod(newest - i, HISTORY)], b = history[Math.floorMod(newest - i - 1, HISTORY)];
        return a + (b - a) * f;
    }

    /**
     * Section {@code i}'s (the pad is the last) yaw and pitch at {@code partial} of the tick, relative to the body: the
     * root's turn {@code i * lag} ticks ago, coiled back and down toward the pad by the coil.
     */
    public float[] angles(int i, float partial) {
        float delay = i * spec.lag() + (1 - partial);
        float along = (float) Math.pow(i / (float) spec.arm(side).sections().length, 1.3);
        float coil = Mth.lerp(partial, previousCurl, curl) * along;
        return new float[]{sample(yaws, delay) - side * spec.curl()[0] * coil, Mth.clamp(sample(pitches, delay) + spec.curl()[1] * coil, -88, 88)};
    }

    /** Section {@code i}'s direction in the body's frame (x left, y up, z forward). */
    public Vec3 direction(int i, float partial) {
        float[] a = angles(i, partial);
        return Vec3.directionFromRotation(a[1], a[0]);
    }

    /** Where the arm's root is in the world (see {@link #joints}). */
    public Vec3 root(Vec3 seat, float bodyYaw, float bodyPitch, float water) {
        var arm = spec.arm(side);
        Vec3 base = arm.base().lerp(arm.waterBase(), water);
        // The body levels out while it whips, as it does for any attack (NativeGroundModel.divePitch).
        float dive = Mth.clamp(bodyPitch, -spec.waterPitch(), spec.waterPitch()) * water * (1 - weight) * Mth.DEG_TO_RAD;
        // Nose down (positive pitch) turns the body's forward axis down about the seat, as the model is turned.
        double c = Math.cos(dive), s = Math.sin(dive);
        base = new Vec3(base.x, base.y * c - base.z * s, base.y * s + base.z * c);
        return seat.add(base.yRot(-bodyYaw * Mth.DEG_TO_RAD));
    }

    /**
     * Where the arm is, in the world: its root (from the rider's seat, turned by the body's dive pitch afloat), each
     * section's end, and last the pad's tip.
     *
     * @param seat      the rider's seat in the world
     * @param bodyYaw   the body's facing
     * @param bodyPitch the body's pitch (a swimmer dives with its rider's view)
     * @param water     how far into its swimming pose the body is, 0 to 1
     */
    public Vec3[] joints(Vec3 seat, float bodyYaw, float bodyPitch, float water, float partial) {
        var arm = spec.arm(side);
        var points = new Vec3[arm.sections().length + 2];
        points[0] = root(seat, bodyYaw, bodyPitch, water);
        for (int i = 0; i <= arm.sections().length; i++) {
            float[] a = angles(i, partial);
            Vec3 d = Vec3.directionFromRotation(a[1], bodyYaw + a[0]);
            points[i + 1] = points[i].add(d.scale(i < arm.sections().length ? arm.sections()[i] : arm.pad()));
        }
        return points;
    }
}
