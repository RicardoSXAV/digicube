package com.digicube;

import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.platform.Services;
import com.digicube.registry.DCItems;

/**
 * Shared entry point. Both loader modules call {@link #init()} from their own
 * entry point, so anything that is not loader-specific belongs here.
 *
 * <p>Nothing in this package may import Fabric or NeoForge classes. If you need
 * something from a loader, add a method to
 * {@link com.digicube.platform.services.IPlatformHelper} instead.
 */
public final class DigiCube {

    private DigiCube() {}

    public static void init() {
        Constants.LOG.info("Starting {} on {} ({} environment).",
                Constants.MOD_NAME,
                Services.PLATFORM.getPlatformName(),
                Services.PLATFORM.getEnvironmentName());

        DCItems.init();
        DigimonSpeciesBootstrap.registerBuiltIn();

        Constants.LOG.info("{} ready with {} species.", Constants.MOD_NAME, DigimonSpeciesRegistry.size());
    }
}
