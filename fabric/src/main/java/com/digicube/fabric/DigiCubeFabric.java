package com.digicube.fabric;

import com.digicube.DigiCube;
import com.digicube.command.DigiCubeCommands;
import com.digicube.fabric.dev.FabricDevNetworking;
import com.digicube.fabric.party.FabricPartyNetworking;
import com.digicube.fabric.registry.DCCreativeTabs;
import com.digicube.fabric.starter.FabricStarterNetworking;
import com.digicube.spawn.WildSpawner;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

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
        DCCreativeTabs.init();
        FabricPartyNetworking.init();
        FabricStarterNetworking.init();
        FabricDevNetworking.init();
        FabricAerialNetworking.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DigiCubeCommands.register(dispatcher));
        // Wild Digimon: the spawner is loader-neutral, only this per-dimension tick hook is Fabric's.
        ServerTickEvents.END_LEVEL_TICK.register(WildSpawner::tick);
    }
}
