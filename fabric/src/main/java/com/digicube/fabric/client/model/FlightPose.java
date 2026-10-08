package com.digicube.fabric.client.model;

import com.digicube.entity.ai.FlightPhase;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Set;

/**
 * A flyer's body off the ground ({@code flight} in the model catalog; Kabuterimon), posed from the clips the catalog
 * names by convention and the way it carries itself on the wing ({@code FlightLook}, through the render state):
 * <ul>
 *   <li>postures on the body's own clock: {@code fly_hover}, {@code fly} (cruise), {@code fly_dash}, {@code fly_dive},
 *   {@code fly_brake}, mixed by the look's shares, and {@code fly_roll} tucked in through a barrel roll</li>
 *   <li>the wings alone on the wing clock: {@code wings_beat}, {@code wings_power} (a climb, a takeoff, a boost) and
 *   {@code wings_fold} (the dive's arrow, half of it through a roll), added over whatever the body does</li>
 *   <li>{@code takeoff} on the phase's clock as it leaves the ground (the wings opening into their beat), {@code land} on
 *   the descent itself as it comes in and on time from its touchdown ({@code landing_contact}), the wings folding back</li>
 *   <li>an attack cast on the wing plays its wing form ({@code <clip>_air}) over the posture, blending in and out</li>
 * </ul>
 * Then the whole body turns about the rider's seat ({@code pivot}, model px), so the rider stays where the first-person
 * camera is while the body swings under them: pitched along its path, banked into its turns and slides, and rolled once
 * round its length in a barrel roll.
 */
final class FlightPose {
    /**
     * @param pivot the point the body turns about (model px, the rider's seat); {@code contact} the land clip's touchdown
     * tick; {@code leans} each posture's lean (degrees forward, in {@link #POSTURES}' order): not in the clips, the body is
     * turned about the pivot by the postures' mix of it, so mixing them never moves the seat
     */
    record Spec(Vector3f pivot, float contact, String wingPrefix, float[] leans) {}

    private static final String[] POSTURES = {"fly_hover", "fly", "fly_dash", "fly_dive", "fly_brake", "fly_roll"};
    private static final float WING_IN = 3, WING_OUT = 6;

    private final Spec spec;
    private final NativeAnimationSet animations;
    private final ModelPart root;
    private final Set<ModelPart> wings;
    private final java.util.Map<String, Boolean> keysWings = new java.util.HashMap<>();

    FlightPose(Spec spec, NativeAnimationSet animations, ModelPart root) {
        this.spec = spec;
        this.animations = animations;
        this.root = root;
        this.wings = animations.partsNamed(spec.wingPrefix());
        for (String clip : POSTURES) if (!animations.has(clip)) throw new IllegalArgumentException("A flyer needs the clip " + clip);
        for (String clip : new String[]{"wings_beat", "wings_power", "wings_fold", "takeoff", "land"})
            if (!animations.has(clip)) throw new IllegalArgumentException("A flyer needs the clip " + clip);
    }

    /** Whether this pose takes the body: it is off the ground, or taking off or landing. */
    static boolean applies(DigimonRenderState s) { return s.flightPhase != FlightPhase.GROUNDED; }

    /** The lean (degrees forward) the postures posed this frame give the body: their mix of the catalog's leans. */
    private float lean;

    /** The whole pose for this frame; {@code attackBlendIn}/{@code Out} are the catalog's attack blends (ticks). */
    void pose(DigimonRenderState s, float attackBlendIn, float attackBlendOut) {
        float t = s.flightPhaseTime, age = s.ageInTicks, wing = s.wingClock;
        String attack = attackClip(s);
        float attackTick = attack == null ? 0 : s.attackAnimation.getTimeInMillis(s.ageInTicks) / 50F;
        float aw = 0;
        if (attack != null) {
            aw = 1;
            if (attackBlendIn > 0) aw = Math.min(aw, attackTick / attackBlendIn);
            if (attackBlendOut > 0) aw = Math.min(aw, (animations.length(attack) - attackTick) / attackBlendOut);
            aw = smooth(aw);
        }
        float keep = 1 - aw;
        // the wings: how much of the beat they carry (folded at rest on the ground)
        float wingWeight = switch (s.flightPhase) {
            case TAKEOFF -> smooth((t - 1) / WING_IN);
            case LANDING -> 1 - smooth(t / WING_OUT);
            default -> 1;
        };
        float roll = Math.abs(s.swimRoll) / 360F, tuck = roll > 0 ? Mth.sin(roll * Mth.PI) : 0;
        lean = 0;
        switch (s.flightPhase) {
            case TAKEOFF -> animations.apply("takeoff", t, keep);
            case LANDING -> animations.apply("land", spec.contact() + t, keep);
            case APPROACH -> {
                float landed = smooth(s.flightLandingProgress);
                postures(s, age, keep * (1 - landed), tuck);
                animations.apply("land", spec.contact() * s.flightLandingProgress, keep * landed);
            }
            default -> postures(s, age, keep, tuck);
        }
        if (attack != null) {
            animations.apply(attack, attackTick, aw);
            // a wing form that does not key the wings leaves them beating
            if (!keysWings.computeIfAbsent(attack, c -> animations.keyed(c).stream().anyMatch(wings::contains))) wingLayer(s, wing, wingWeight * aw, tuck);
        }
        wingLayer(s, wing, wingWeight * keep, tuck);
        // the body turns about the seat: along its path, into its turns, round in a roll; an attack owns the body (a
        // pounce's own line pitches it instead)
        float air = s.flightPhase == FlightPhase.TAKEOFF ? smooth((t - 2) / 6) : s.flightPhase == FlightPhase.LANDING ? 0
                : s.flightPhase == FlightPhase.APPROACH ? 1 - smooth(s.flightLandingProgress) : 1;
        float pitch = (s.flightPitch * keep - s.pouncePitch * aw) * air + lean, bank = s.flightBank * keep * air, spin = s.swimRoll;
        if (pitch != 0 || bank != 0 || spin != 0) turnAboutSeat(pitch, bank + spin);
    }

    /** The attack clip this frame: the wing form when it was cast on the wing and the model has one; null for none. */
    private String attackClip(DigimonRenderState s) {
        if (s.attackAnimationName == null || !s.attackAnimation.isStarted()) return null;
        String clip = s.attackAnimationName;
        if (s.attackAir && animations.has(clip + "_air")) clip = clip + "_air";
        return animations.has(clip) ? clip : null;
    }

    /** The postures mixed by the flight's shares, on the body's own clock. */
    private void postures(DigimonRenderState s, float age, float weight, float tuck) {
        if (weight <= 0) return;
        float dive = Math.clamp(s.flightDive, 0, 1), rest = 1 - dive;
        float brake = Math.clamp(s.flightBrake, 0, 1) * rest; rest -= brake;
        float dash = Math.clamp(s.flightDash, 0, 1) * rest; rest -= dash;
        float cruise = Math.clamp(s.flightCruise, 0, 1) * rest; rest -= cruise;
        float hover = rest;
        float body = weight * (1 - tuck);
        float[] shares = {hover * body, cruise * body, dash * body, dive * body, brake * body, weight * tuck};
        for (int i = 0; i < POSTURES.length; i++) {
            if (shares[i] <= 0) continue;
            animations.apply(POSTURES[i], age, shares[i]);
            lean += shares[i] * spec.leans()[i];
        }
    }

    /** The four wings on the wing clock: beating, beating hard, or folded back into the dive's arrow. */
    private void wingLayer(DigimonRenderState s, float clock, float weight, float tuck) {
        if (weight <= 0) return;
        float fold = Math.clamp(Math.max(s.flightDive, tuck * .6F), 0, 1);
        float power = Math.clamp(s.flightPower, 0, 1) * (1 - fold);
        animations.apply("wings_fold", clock, weight * fold, wings);
        animations.apply("wings_power", clock, weight * power, wings);
        animations.apply("wings_beat", clock, weight * (1 - fold - power), wings);
    }

    /** Turn the whole body about the seat: pitch (degrees, nose down) then roll (degrees, the left side down). */
    private void turnAboutSeat(float pitchDegrees, float rollDegrees) {
        var p = spec.pivot();
        var turn = new Matrix4f().translate(p.x, p.y, p.z)
                .rotateZ(rollDegrees * Mth.DEG_TO_RAD).rotateX(pitchDegrees * Mth.DEG_TO_RAD)
                .translate(-p.x, -p.y, -p.z);
        var current = new Matrix4f().translate(root.x, root.y, root.z).rotate(new Quaternionf().rotationZYX(root.zRot, root.yRot, root.xRot));
        var result = turn.mul(current);
        var position = result.getTranslation(new Vector3f());
        var angles = result.getUnnormalizedRotation(new Quaternionf()).getEulerAnglesZYX(new Vector3f());
        root.setPos(position.x, position.y, position.z);
        root.setRotation(angles.x, angles.y, angles.z);
    }

    private static float smooth(float v) { float x = Math.clamp(v, 0, 1); return x * x * (3 - 2 * x); }
}
