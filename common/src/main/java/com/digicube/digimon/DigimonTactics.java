package com.digicube.digimon;

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
 */
public record DigimonTactics(double holdMin, double holdMax, float dodgeChance, int reactionTicks, boolean strafe,
                             int leadTicks, boolean pressImpaired, boolean preferClose, double chargeDistance, double chargeSpeed) {
    /** The old behaviour: close in, never dodge, no prediction, list order. */
    public static final DigimonTactics DEFAULT = new DigimonTactics(0, 0, 0, 0, false, 0, false, false, 0, 0);

    public DigimonTactics {
        if (holdMin < 0 || holdMax < holdMin) throw new IllegalArgumentException("hold range " + holdMin + ".." + holdMax);
        if (dodgeChance < 0 || dodgeChance > 1) throw new IllegalArgumentException("dodge chance " + dodgeChance);
        if (reactionTicks < 0 || leadTicks < 0 || chargeDistance < 0 || chargeSpeed < 0) throw new IllegalArgumentException("negative ticks");
    }

    public boolean holdsRange() { return holdMax > 0; }

    /** The same tactics with one knob changed by name, for overrides and sweeps. */
    public DigimonTactics with(String key, String value) {
        return switch (key) {
            case "hold_min" -> new DigimonTactics(Double.parseDouble(value), holdMax, dodgeChance, reactionTicks, strafe, leadTicks, pressImpaired, preferClose, chargeDistance, chargeSpeed);
            case "hold_max" -> new DigimonTactics(holdMin, Double.parseDouble(value), dodgeChance, reactionTicks, strafe, leadTicks, pressImpaired, preferClose, chargeDistance, chargeSpeed);
            case "dodge_chance" -> new DigimonTactics(holdMin, holdMax, Float.parseFloat(value), reactionTicks, strafe, leadTicks, pressImpaired, preferClose, chargeDistance, chargeSpeed);
            case "reaction_ticks" -> new DigimonTactics(holdMin, holdMax, dodgeChance, Integer.parseInt(value), strafe, leadTicks, pressImpaired, preferClose, chargeDistance, chargeSpeed);
            case "strafe" -> new DigimonTactics(holdMin, holdMax, dodgeChance, reactionTicks, Boolean.parseBoolean(value), leadTicks, pressImpaired, preferClose, chargeDistance, chargeSpeed);
            case "lead_ticks" -> new DigimonTactics(holdMin, holdMax, dodgeChance, reactionTicks, strafe, Integer.parseInt(value), pressImpaired, preferClose, chargeDistance, chargeSpeed);
            case "press_impaired" -> new DigimonTactics(holdMin, holdMax, dodgeChance, reactionTicks, strafe, leadTicks, Boolean.parseBoolean(value), preferClose, chargeDistance, chargeSpeed);
            case "prefer_close" -> new DigimonTactics(holdMin, holdMax, dodgeChance, reactionTicks, strafe, leadTicks, pressImpaired, Boolean.parseBoolean(value), chargeDistance, chargeSpeed);
            case "charge_distance" -> new DigimonTactics(holdMin, holdMax, dodgeChance, reactionTicks, strafe, leadTicks, pressImpaired, preferClose, Double.parseDouble(value), chargeSpeed);
            case "charge_speed" -> new DigimonTactics(holdMin, holdMax, dodgeChance, reactionTicks, strafe, leadTicks, pressImpaired, preferClose, chargeDistance, Double.parseDouble(value));
            default -> throw new IllegalArgumentException("unknown tactics key " + key);
        };
    }

    /** Applies {@code DIGICUBE_TACTICS} overrides for this species, if any; the loader calls it once. */
    public DigimonTactics overridden(String species) {
        String spec = System.getenv("DIGICUBE_TACTICS");
        if (spec == null || spec.isBlank()) return this;
        DigimonTactics result = this;
        for (String block : spec.split(";")) {
            String[] parts = block.split(":", 2);
            if (parts.length != 2 || !parts[0].trim().equalsIgnoreCase(species)) continue;
            for (String assignment : parts[1].split(",")) {
                String[] kv = assignment.split("=", 2);
                result = result.with(kv[0].trim().toLowerCase(Locale.ROOT), kv[1].trim());
            }
        }
        return result;
    }

    public Map<String, Object> describe() {
        return Map.of("hold_min", holdMin, "hold_max", holdMax, "dodge_chance", dodgeChance, "reaction_ticks", reactionTicks,
                "strafe", strafe, "lead_ticks", leadTicks, "press_impaired", pressImpaired, "prefer_close", preferClose,
                "charge_distance", chargeDistance, "charge_speed", chargeSpeed);
    }
}
