package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Temporary hardcoded species list so there is something to test against.
 *
 * <p>ROADMAP: this whole class goes away once species are loaded from
 * {@code data/digicube/species/*.json} through a datapack reload listener.
 * Treat it as a fixture, not as the place to add hundreds of Digimon.
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

    /** Koromon's only move: a small bubble stream every two seconds. */
    public static final DigimonAttack BUBBLE_BLOW = new DigimonAttack(
            Constants.id("bubble_blow"), DigimonAttack.Kind.BUBBLES,
            1.0F, 40, 24, 10, 8.0, false);

    public static void registerBuiltIn() {
        DigimonSpeciesRegistry.register(new DigimonSpecies(
                Constants.id("koromon"),
                DigimonStage.BABY_II,
                DigimonAttribute.FREE,
                12, 2, 2, 0.25F,
                List.of(Evolution.atLevel(Constants.id("agumon"), 5)),
                List.of(BUBBLE_BLOW),
                DigimonBody.DEFAULT
        ));

        DigimonSpeciesRegistry.register(new DigimonSpecies(
                Constants.id("agumon"),
                DigimonStage.CHILD,
                DigimonAttribute.VACCINE,
                20, 6, 4, 0.30F,
                List.of(
                        // Most specific branch first: Greymon needs training, not just a level.
                        new Evolution(Constants.id("greymon"), 16, 40, -1, 20, null),
                        Evolution.atLevel(Constants.id("greymon"), 20)
                ),
                // Priority order: the fireball whenever it is off cooldown, claws in between.
                List.of(PEPPER_BREATH, CLAW),
                DigimonBody.DEFAULT
        ));

        DigimonSpeciesRegistry.register(new DigimonSpecies(
                Constants.id("greymon"),
                DigimonStage.ADULT,
                DigimonAttribute.VACCINE,
                40, 14, 10, 0.32F,
                List.of(),
                List.of(),
                // Rear crown seat (0, 2, 65) pixels, scale 1.4; +Y in Blender is backward.
                new DigimonBody(1.4F, EntityDimensions.scalable(2.5F, 5.65F).withEyeHeight(5.075F),
                        Optional.of(new DigimonBody.Mount(new Vec3(0.0, 5.6875, -0.175), 0.32F, 1.0F)))
        ));

        Constants.LOG.info("Registered {} built-in Digimon species.", DigimonSpeciesRegistry.size());
    }
}
