package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Shared attack mechanics and startup loading of the bundled JSON species catalog.
 * New species belong in the catalog and species JSON files, not in Java.
 */
public final class DigimonSpeciesBootstrap {

    private DigimonSpeciesBootstrap() {}

    /** Agumon's signature fireball: slow, hard-hitting, used whenever it is ready. */
    public static final DigimonAttack PEPPER_BREATH = new DigimonAttack(
            Constants.id("pepper_breath"), DigimonAttack.Kind.FIREBALL,
            1.5F, 100, 24, 12, 10.0, false);

    /** Agumon's basic swipe: quick, alternates hands. */
    public static final DigimonAttack CLAW = new DigimonAttack(
            Constants.id("claw"), DigimonAttack.Kind.MELEE,
            0.7F, 20, 10, 4, 0.0, true);

    /** Shared baby-stage move: a small bubble stream every two seconds. */
    public static final DigimonAttack BUBBLE_BLOW = new DigimonAttack(
            Constants.id("bubble_blow"), DigimonAttack.Kind.BUBBLES,
            1.0F, 40, 24, 10, 8.0, false);

    /** Greymon's priority flame shot: a two-second performance, eight-second cooldown. */
    public static final DigimonAttack MEGA_FLAME = new DigimonAttack(
            Constants.id("mega_flame"), DigimonAttack.Kind.FLAME_SHOT,
            2.4F, 160, 40, 16, 16.0, false, AttackMotion.load(Constants.id("mega_flame")));

    /** A committed horn drive with shorter recovery between uses. */
    public static final DigimonAttack GREAT_ANTLER = new DigimonAttack(
            Constants.id("great_antler"), DigimonAttack.Kind.HORN_RAM,
            1.15F, 50, 36, 11, 6.2, false, AttackMotion.load(Constants.id("great_antler")));

    /** Four seconds of continuous blue flame, then six seconds to refill an empty tank. */
    public static final DigimonAttack BLUE_BLASTER = new DigimonAttack(
            Constants.id("blue_blaster"), DigimonAttack.Kind.FLAME_STREAM,
            0.4F, 0, 100, 10, 8.0, false, AttackMotion.load(Constants.id("blue_blaster")),
            new AttackFuel(80, 120, 10), 0.0);

    /** A quick horn thrust, with no vanilla hurt impulse or extra knockback. */
    public static final DigimonAttack HORN_ATTACK = new DigimonAttack(
            Constants.id("horn_attack"), DigimonAttack.Kind.HORN_RAM,
            0.7F, 26, 22, 7, 2.3, false, AttackMotion.load(Constants.id("horn_attack")), null, 0.0);

    /** Gomamon gathers a homing school into a wave: modest damage, substantial displacement. */
    public static final DigimonAttack MARCHING_FISHES = new DigimonAttack(
            Constants.id("marching_fishes"), DigimonAttack.Kind.WATER_WAVE,
            0.55F, 90, 32, 14, 11.0, false, AttackMotion.load(Constants.id("marching_fishes")), null, 1.35);

    /** A quick alternating paw rake, with enough recovery for a clean planted pose. */
    public static final DigimonAttack CLAW_ATTACK = new DigimonAttack(
            Constants.id("claw_attack"), DigimonAttack.Kind.MELEE,
            0.65F, 22, 16, 6, 0.0, true);

    /** The shared moves species sheets may reference, by id. */
    public static Map<Identifier, DigimonAttack> attacks() {
        return Map.of(
                PEPPER_BREATH.id(), PEPPER_BREATH, CLAW.id(), CLAW, BUBBLE_BLOW.id(), BUBBLE_BLOW,
                MEGA_FLAME.id(), MEGA_FLAME, GREAT_ANTLER.id(), GREAT_ANTLER,
                BLUE_BLASTER.id(), BLUE_BLASTER, HORN_ATTACK.id(), HORN_ATTACK,
                MARCHING_FISHES.id(), MARCHING_FISHES, CLAW_ATTACK.id(), CLAW_ATTACK);
    }

    public static void registerBuiltIn() {
        var species = BundledSpeciesLoader.load(attacks());
        species.forEach(DigimonSpeciesRegistry::register);

        Constants.LOG.info("Registered {} built-in Digimon species.", DigimonSpeciesRegistry.size());
    }

    /** One species as its bundled sheet defines it, ignoring any runtime tuning. */
    public static DigimonSpecies bundled(Identifier id) {
        return BundledSpeciesLoader.loadOne(id, attacks());
    }
}
