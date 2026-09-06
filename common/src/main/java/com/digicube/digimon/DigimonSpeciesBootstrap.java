package com.digicube.digimon;

import com.digicube.Constants;
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

    public static void registerBuiltIn() {
        var species = BundledSpeciesLoader.load(Map.of(
                PEPPER_BREATH.id(), PEPPER_BREATH, CLAW.id(), CLAW, BUBBLE_BLOW.id(), BUBBLE_BLOW,
                MEGA_FLAME.id(), MEGA_FLAME, GREAT_ANTLER.id(), GREAT_ANTLER));
        species.forEach(DigimonSpeciesRegistry::register);

        Constants.LOG.info("Registered {} built-in Digimon species.", DigimonSpeciesRegistry.size());
    }
}
