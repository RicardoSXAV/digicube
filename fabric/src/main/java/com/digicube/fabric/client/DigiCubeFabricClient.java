package com.digicube.fabric.client;

import com.digicube.Constants;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client-only entry point. Renderers, key bindings, screens and model
 * layers are registered here -- never from {@link com.digicube.fabric.DigiCubeFabric},
 * which also runs on dedicated servers where client classes do not exist.
 */
public class DigiCubeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Constants.LOG.info("DigiCube client initialised.");
    }
}
