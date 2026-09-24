package com.digicube.digimon;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How a species fights when it is not swinging: the part of the combat AI a player would call
 * "smart". Every knob is a number so a balance run or an optimizer can move it without a rebuild
 * ({@code DIGICUBE_TACTICS=seadramon:hold_min=5,dodge_chance=.8;gesomon:lead_ticks=10}).
 *
 * @param holdMin        while no attack is usable, back away when the target is closer than this
 *                       (0 = never back away: a brawler closes in)
 * @param holdMax        ... and close in when it is further than this
 * @param dodgeChance    chance to sidestep an attack the target is winding up, or an inbound projectile
 * @param reactionTicks  ticks after the wind-up starts before the sidestep begins (0 = inhuman)
 * @param strafe         circle the target while holding range, so a straight shot needs a lead
 * @param leadTicks      how many ticks ahead to predict the target's position when moving on it
 * @param pressImpaired  rush a blinded, inked, Cold, frozen or held target at run speed, ignoring hold range
 * @param preferClose    when several attacks are usable at once take the shortest-ranged (a brawler's
 *                       melee over its opener); otherwise the species list order decides
 * @param chargeDistance chase at run speed while the target is further than this many blocks: how a
 *                       slow brawler answers a kiter (0 = never)
 * @param chargeSpeed    the pace of that charge, as a movement speed modifier; a fight-only burst that
 *                       leaves the species' travel gait alone (0 = its locomotion run speed)
 * @param fightSpeed     the pace of everything it does in a fight (closing in, holding its range, the approach into
 *                       a hold; a sidestep is the common factor faster), as a movement speed modifier. For a body
 *                       that travels slowly but strikes fast, like a snake: its travel pace, and so its pace under
 *                       a rider, stays the sheet's {@code base_speed} (0 = the goal's own modifier and its run speed)
 * @param gallop         circling and closing into the hold band go at the fight pace too, not at a walk: a skirmisher
 *                       that never stops moving (Centarumon). Without it they walk, as Seadramon's do
 * @param shootMoving    a shot the species also looses under a rider on the run ({@code move} in its rider attacks) is
 *                       loosed on the run by the AI too: the legs keep circling, the upper body turns to the aim
 * @param dashDodge      a sidestep is the jet burst of its charge move ({@code aim: charge}) while that move is ready
 * @param dashEngage     charge an Exposed or impaired target within this many blocks with that move, running it down
 *                       and bucking it (0 = never)
 * @param dashEscape     a brawler that has closed inside {@code holdMin} is left behind with a jet burst away
 */
public record DigimonTactics(double holdMin, double holdMax, float dodgeChance, int reactionTicks, boolean strafe,
                             int leadTicks, boolean pressImpaired, boolean preferClose, double chargeDistance, double chargeSpeed,
                             double fightSpeed, boolean gallop, boolean shootMoving, boolean dashDodge, double dashEngage,
                             boolean dashEscape) {
    /** The old behaviour: close in, never dodge, no prediction, list order. */
    public static final DigimonTactics DEFAULT = new DigimonTactics(0, 0, 0, 0, false, 0, false, false, 0, 0, 0,
            false, false, false, 0, false);

    public DigimonTactics {
        if (holdMin < 0 || holdMax < holdMin) throw new IllegalArgumentException("hold range " + holdMin + ".." + holdMax);
        if (dodgeChance < 0 || dodgeChance > 1) throw new IllegalArgumentException("dodge chance " + dodgeChance);
        if (reactionTicks < 0 || leadTicks < 0 || chargeDistance < 0 || chargeSpeed < 0 || fightSpeed < 0 || dashEngage < 0)
            throw new IllegalArgumentException("negative ticks");
    }

    public boolean holdsRange() { return holdMax > 0; }

    /** Uses the charge move's jet burst for something. */
    public boolean dashes() { return dashDodge || dashEngage > 0 || dashEscape; }

    /** The same tactics with one knob changed by name, for overrides and sweeps. */
    public DigimonTactics with(String key, String value) {
        var knobs = describe();
        if (!knobs.containsKey(key)) throw new IllegalArgumentException("unknown tactics key " + key);
        knobs.put(key, value);
        return of(knobs);
    }

    /** Tactics from knob names to values (numbers, booleans or their text); a missing knob keeps its default. */
    public static DigimonTactics of(Map<String, ?> knobs) {
        return new DigimonTactics(number(knobs, "hold_min"), number(knobs, "hold_max"), (float) number(knobs, "dodge_chance"),
                (int) number(knobs, "reaction_ticks"), flag(knobs, "strafe"), (int) number(knobs, "lead_ticks"),
                flag(knobs, "press_impaired"), flag(knobs, "prefer_close"), number(knobs, "charge_distance"),
                number(knobs, "charge_speed"), number(knobs, "fight_speed"), flag(knobs, "gallop"), flag(knobs, "shoot_moving"),
                flag(knobs, "dash_dodge"), number(knobs, "dash_engage"), flag(knobs, "dash_escape"));
    }

    private static double number(Map<String, ?> knobs, String key) {
        Object value = knobs.get(key);
        return value == null ? 0 : value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
    }

    private static boolean flag(Map<String, ?> knobs, String key) {
        Object value = knobs.get(key);
        return value != null && (value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString()));
    }

    /** Applies {@code DIGICUBE_TACTICS} overrides for this species, if any; the loader calls it once. */
    public DigimonTactics overridden(String species) {
        String spec = System.getenv("DIGICUBE_TACTICS");
        if (spec == null || spec.isBlank()) return this;
        var knobs = describe();
        boolean changed = false;
        for (String block : spec.split(";")) {
            String[] parts = block.split(":", 2);
            if (parts.length != 2 || !parts[0].trim().equalsIgnoreCase(species)) continue;
            for (String assignment : parts[1].split(",")) {
                String[] kv = assignment.split("=", 2);
                String key = kv[0].trim().toLowerCase(Locale.ROOT);
                if (!knobs.containsKey(key)) throw new IllegalArgumentException("unknown tactics key " + key);
                knobs.put(key, kv[1].trim());
                changed = true;
            }
        }
        // All at once, so a band may be moved in either order.
        return changed ? of(knobs) : this;
    }

    /** Every knob by its sheet name, in a map the caller may change. */
    public Map<String, Object> describe() {
        var knobs = new LinkedHashMap<String, Object>();
        knobs.put("hold_min", holdMin); knobs.put("hold_max", holdMax); knobs.put("dodge_chance", dodgeChance);
        knobs.put("reaction_ticks", reactionTicks); knobs.put("strafe", strafe); knobs.put("lead_ticks", leadTicks);
        knobs.put("press_impaired", pressImpaired); knobs.put("prefer_close", preferClose);
        knobs.put("charge_distance", chargeDistance); knobs.put("charge_speed", chargeSpeed); knobs.put("fight_speed", fightSpeed);
        knobs.put("gallop", gallop); knobs.put("shoot_moving", shootMoving); knobs.put("dash_dodge", dashDodge);
        knobs.put("dash_engage", dashEngage); knobs.put("dash_escape", dashEscape);
        return knobs;
    }
}
