package com.digicube.fabric.client;

import com.digicube.Constants;
import com.digicube.fabric.client.model.AgumonModel;
import com.digicube.fabric.client.render.DigimonRenderer;
import com.digicube.registry.DCEntityTypes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

/**
 * Fabric client-only entry point. Renderers, key bindings, screens and model
 * layers are registered here -- never from {@link com.digicube.fabric.DigiCubeFabric},
 * which also runs on dedicated servers where client classes do not exist.
 */
public class DigiCubeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ModelLayerRegistry.registerModelLayer(AgumonModel.LAYER, AgumonModel::createBodyLayer);
        EntityRendererRegistry.register(DCEntityTypes.DIGIMON, DigimonRenderer::new);

        Constants.LOG.info("DigiCube client initialised.");
    }
}
