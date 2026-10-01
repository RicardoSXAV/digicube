package com.digicube.digimon;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A serpent's wrap (the constriction move; Seadramon's): a strike that takes its prey on contact, then the coil its body
 * throws round the prey, sized to the prey's own box, the squeezes and the release. The server times it, holds the prey
 * and checks the coil's line against blocks; every client lays the body along the same coil ({@code SerpentSpine}), so
 * no clip carries the body: the move's clip keys only the head, jaw and fins, played on {@link #clipTime}.
 *
 * <p>The coil's loops hug the prey's box ({@link #HUG} of its half-width, plus the body's own half-thickness), stacked
 * touching, as many as the prey's height asks for and the body's length allows; the neck rises from the top loop to the
 * head, which looms over the prey facing it, and the tail leaves the bottom loop and lies on the ground. A body only
 * wraps what it can go all the way round once with its neck and tail free ({@link #fit}).
 */
public final class ConstrictionCoil {
    private ConstrictionCoil() {}

    // --- the timeline: ticks from the capture ------------------------------------------------------------------------

    /** Ticks the loops take to close round the prey after the capture. */
    public static final int COIL_TICKS = 12;
    /** The first squeeze, and the ticks between squeezes, from the capture. */
    public static final int FIRST_SQUEEZE = 14, INTERVAL = 10, SQUEEZES = 4;
    /** The prey is let go this long after its capture, and the body is back on its trail UNWIND_TICKS later. */
    public static final int RELEASE = 48, UNWIND_TICKS = 12;
    /** Ticks from the capture to the end of the move. */
    public static final int AFTER_CAPTURE = RELEASE + UNWIND_TICKS;
    /** The longest strike: past it (the prey got away from the lunge) the strike has missed. */
    public static final int STRIKE_TICKS = 14;
    /** The move's longest run: a full strike and everything after its capture. */
    public static final int DURATION = STRIKE_TICKS + AFTER_CAPTURE;

    /**
     * The move's clip: the strike's wind-up and lunge up to STRIKE_POSE, held there while the body flies at its prey,
     * then from BITE (the capture) on the capture's own clock. Its length is BITE + AFTER_CAPTURE.
     */
    public static final float STRIKE_POSE = 6, BITE = 8;

    /**
     * The capture tick of a wrap that has taken nothing yet (still striking). A capture tick may be below zero: a prey let
     * go early moves it back so that the move's clock stands at the release.
     */
    public static final int NOT_TAKEN = Integer.MIN_VALUE;

    /** Where the move's clip is {@code attackTick} ticks into the move, captured at {@code captureTick} ({@link #NOT_TAKEN}: not yet). */
    public static float clipTime(float attackTick, int captureTick) {
        return captureTick == NOT_TAKEN ? Math.min(attackTick, STRIKE_POSE) : BITE + Math.max(0, attackTick - captureTick);
    }

    // --- the hold -----------------------------------------------------------------------------------------------------

    /** Each squeeze adds this share of the prey's full health to the attack's own damage. */
    public static final float CRUSH_SHARE = .06F;
    /** Expires with the caster's own cooldown, so a ready wrap never waits on its last victim's resistance. */
    public static final int RESISTANCE_TICKS = 160;
    /** Squeezed prey needs this long to get its breath back before it can start an attack: the caster's uncoiling. */
    public static final int WINDED_TICKS = 20;
    /** Frozen prey stays frozen for one more second after the hold releases. */
    public static final int FROZEN_TAIL_TICKS = 20;
    /** A coiling body shrugs off the knockback of ordinary hits; an impulse this strong is a push and breaks the wrap. */
    public static final double BREAKING_PUSH = 1.0;
    /** How far held prey may be from the coil's middle before it has slipped out (a teleport, a shove it rode out). */
    public static final double SLIPPED = .75;

    // --- the strike ---------------------------------------------------------------------------------------------------

    /** Blocks a tick the strike flies at its prey, on land and in the water. */
    public static final double STRIKE_PACE = .7, STRIKE_PACE_WATER = .85;
    /** Degrees a tick the strike turns after prey that moves. */
    public static final float STRIKE_TURN = 30;
    /** Blocks the strike's head may be from where it stops beside the prey and still take it. */
    public static final double CONTACT = .45;
    /** Blocks above or below the striker its prey may be on land (a step and a hop); swimming, anything within reach. */
    public static final double STRIKE_STEP = 1.5;
    /** Blocks above the floor under it airborne prey may be and still be snatched down: a hop, a flutter. */
    public static final double SNATCH = 1.6;
    /** Blocks a held mob is drawn out into the open, away from a block its coil would cut. */
    public static final double DRAW_OUT = 1.2;
    /** A strike that missed, or could not go, costs this long before the next instead of the move's cooldown. */
    public static final int RETRY_TICKS = 40;
    /** How long a chilling caster walks toward strike reach before it chills its prey from where it stands. */
    public static final int CLOSE_IN_TICKS = 60;

    // --- the coil's shape ---------------------------------------------------------------------------------------------

    /** Share of the prey's half-width its loops hug (boxes are rarely filled out to their corners). */
    public static final double HUG = 1.05;
    /** The fewest loops a wrap throws: once all the way round. */
    public static final double LEAST_LOOPS = 1;
    /** The fewest loops thrown round even the smallest prey: once round and a little over, the ends overlapping. */
    public static final double SMALL_LOOPS = 1.15;
    /** A loop lies this share of the body's girth above the one under it: touching. */
    public static final double STACK = .98;
    /** Share of the prey's height the top loop reaches for, when the body is long enough. */
    public static final double COVER = .8;
    /** The head's feet stand this far beyond the loops' outer edge, and the head this far over the prey's top. */
    public static final double HEAD_OUT = .35, HEAD_OVER = .4;

    /**
     * The coil round one prey. Distances in blocks: {@code hug} the radius the loops press against, {@code height} the
     * prey's, {@code loops} how many times the body goes round, {@code top} and {@code bottom} the height above the
     * prey's feet of the top loop's line (where the neck leaves it) and the bottom one's (where the tail does).
     */
    public record Shape(double hug, double height, double loops, double top, double bottom) {}

    /**
     * The coil a serpent throws round a prey of box {@code prey}, or null when it cannot: it is no coiling serpent, or
     * the prey is too big for its body to go all the way round with its neck and tail free, or more than a block taller
     * than itself.
     */
    public static Shape fit(AABB prey, DigimonBody body) {
        return fit(Math.max(prey.getXsize(), prey.getZsize()), prey.getYsize(), body);
    }

    /** {@link #fit(AABB, DigimonBody)} for a prey {@code width} across and {@code height} tall. */
    public static Shape fit(double width, double height, DigimonBody body) {
        var coil = body.serpent() == null ? null : body.serpent().coil();
        if (coil == null || !(width > 0) || !(height > 0) || !Double.isFinite(width) || !Double.isFinite(height)) return null;
        double hug = width / 2 * HUG, r = coil.girth() / 2, circle = 2 * Math.PI * (hug + r);
        double free = body.length() - 1 - coil.neck() - coil.tail();
        if (free < circle * LEAST_LOOPS || height > body.dimensions().height() + 1) return null;
        double pitch = coil.girth() * STACK;
        double want = Math.clamp((COVER * height - r) / pitch, SMALL_LOOPS, coil.loops());
        double loops = Math.max(LEAST_LOOPS, Math.min(want, free / circle));
        // a helix: each loop a girth under the one it goes round under, the last lying over the start of the first
        double span = pitch * loops;
        // round the middle of the prey, the bottom loop never under the ground
        double bottom = Math.max(r, height / 2 - span / 2);
        return new Shape(hug, height, loops, bottom + span, bottom);
    }

    /** The widest prey a serpent's body goes round (blocks), for its checks and its sheet's readers; 0 for none. */
    public static double widest(DigimonBody body) {
        var coil = body.serpent() == null ? null : body.serpent().coil();
        if (coil == null) return 0;
        double free = body.length() - 1 - coil.neck() - coil.tail();
        return Math.max(0, (free / (2 * Math.PI * LEAST_LOOPS) - coil.girth() / 2) / HUG * 2);
    }

    /**
     * Blocks from the prey's axis to where the wrapping head's feet stand: beyond the loops' outer edge, and far enough
     * out that the head over a prey taller than it rears never puts its snout into the prey.
     */
    public static double headDistance(Shape shape, DigimonBody body) {
        var coil = body.serpent().coil();
        double loops = shape.hug() + coil.girth() / 2 + HEAD_OUT;
        double reared = body.dimensions().eyeHeight();
        return shape.height() + HEAD_OVER > reared ? Math.max(loops, shape.hug() / HUG + .9) : loops;
    }

    /**
     * The boxes along the loops' line round a prey whose feet are at {@code center} (the body's thickness through the
     * line, a step apart), for the blocks it would cut. The bottom loop rests on the floor: its boxes start just above it.
     */
    public static List<AABB> ring(Vec3 center, Shape shape, DigimonBody body) {
        var coil = body.serpent().coil();
        double r = coil.girth() / 2, radius = shape.hug() + r;
        double turn = 2 * Math.PI * shape.loops();
        int steps = Math.max(8, Mth.ceil(turn * radius / (r * 1.2)));
        var out = new ArrayList<AABB>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double u = i / (double) steps, a = turn * u;
            double y = shape.top() + (shape.bottom() - shape.top()) * u;
            double x = center.x + radius * Math.cos(a), z = center.z + radius * Math.sin(a);
            double h = r * .8;
            out.add(new AABB(x - h, center.y + Math.max(.05, y - h), z - h, x + h, center.y + y + h, z + h));
        }
        return out;
    }

    /** How hard the loops are pressing {@code since} ticks after the capture: 0, rising to 1 at each squeeze and easing off. */
    public static float squeeze(float since) {
        float press = 0;
        for (int k = 0; k < SQUEEZES; k++) {
            float t = since - (FIRST_SQUEEZE + k * INTERVAL) + SQUEEZE_RISE;
            if (t < 0 || t > SQUEEZE_RISE + SQUEEZE_EASE) continue;
            press = Math.max(press, t < SQUEEZE_RISE ? t / SQUEEZE_RISE : 1 - (t - SQUEEZE_RISE) / SQUEEZE_EASE);
        }
        return press * press * (3 - 2 * press);
    }
    /** Ticks a squeeze takes to bite (it peaks on the squeeze's own tick) and to ease off after. */
    public static final float SQUEEZE_RISE = 2, SQUEEZE_EASE = 6;

    /**
     * How far point {@code along} (0 at the head, 1 at the tip) has come from its trail onto the coil {@code since} ticks
     * after the capture: the front a little ahead of the rest, the whole body by COIL_TICKS; after the release it comes
     * off again the same way and lies back on its trail by UNWIND_TICKS. The lead is small on purpose: every point swings
     * round the prey by its own share of the loops, the tail furthest, so points that set off far apart in time end up
     * far apart round the prey, more than a link can span.
     */
    public static float onCoil(float since, float along) {
        float on = Mth.clamp((since - LEAD * along) / (COIL_TICKS - LEAD), 0, 1);
        float off = Mth.clamp((since - RELEASE - LEAD * along) / (UNWIND_TICKS - LEAD), 0, 1);
        float w = Math.min(on, 1 - off);
        return w * w * (3 - 2 * w);
    }
    /** Ticks the head's end of the body sets off ahead of the tail's, winding on and unwinding. */
    public static final float LEAD = 3;
}
