package com.digicube.digimon;

import com.digicube.Constants;

import java.util.List;

/**
 * Temporary hardcoded species list so there is something to test against.
 *
 * <p>ROADMAP: this whole class goes away once species are loaded from
 * {@code data/digicube/species/*.json} through a datapack reload listener.
 * Treat it as a fixture, not as the place to add hundreds of Digimon.
 */
public final class DigimonSpeciesBootstrap {

    private DigimonSpeciesBootstrap() {}

    public static void registerBuiltIn() {
        DigimonSpeciesRegistry.register(new DigimonSpecies(
                Constants.id("koromon"),
                DigimonStage.BABY_II,
                DigimonAttribute.FREE,
                12, 2, 2, 0.25F,
                List.of(Evolution.atLevel(Constants.id("agumon"), 5))
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
                )
        ));

        DigimonSpeciesRegistry.register(new DigimonSpecies(
                Constants.id("greymon"),
                DigimonStage.ADULT,
                DigimonAttribute.VACCINE,
                40, 14, 10, 0.32F,
                List.of()
        ));

        Constants.LOG.info("Registered {} built-in Digimon species.", DigimonSpeciesRegistry.size());
    }
}
