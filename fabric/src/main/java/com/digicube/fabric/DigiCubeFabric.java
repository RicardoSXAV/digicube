package com.digicube.fabric;

import com.digicube.DigiCube;
import com.digicube.command.DigiCubeCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * Fabric entry point (both client and dedicated server).
 *
 * <p>Keep this thin. Its job is to hand control to {@link DigiCube#init()} and to
 * register the handful of things that only exist on Fabric.
 */
public class DigiCubeFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        DigiCube.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DigiCubeCommands.register(dispatcher));
    }
}
