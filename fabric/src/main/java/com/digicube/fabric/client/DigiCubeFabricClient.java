package com.digicube.fabric.client;

import com.digicube.Constants;
import com.digicube.fabric.client.party.PartyClient;
import com.digicube.fabric.client.model.AgumonModel;
import com.digicube.fabric.client.model.GabumonModel;
import com.digicube.fabric.client.model.GomamonModel;
import com.digicube.fabric.client.model.GarurumonModel;
import com.digicube.fabric.client.model.KoromonModel;
import com.digicube.fabric.client.model.TsunomonModel;
import com.digicube.fabric.client.model.GreymonModel;
import com.digicube.fabric.client.model.BubbleBlowModel;
import com.digicube.fabric.client.model.MegaFlameModel;
import com.digicube.fabric.client.model.BlueBlasterModel;
import com.digicube.fabric.client.render.MegaFlameRenderer;
import com.digicube.fabric.client.render.BubbleBlowRenderer;
import com.digicube.fabric.client.model.PepperBreathModel;
import com.digicube.fabric.client.render.DigimonRenderer;
import com.digicube.fabric.client.render.PepperBreathRenderer;
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
        new PartyClient().init();
        ModelLayerRegistry.registerModelLayer(AgumonModel.LAYER, AgumonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GabumonModel.LAYER, GabumonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GomamonModel.LAYER, GomamonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GarurumonModel.LAYER, GarurumonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(KoromonModel.LAYER, KoromonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(TsunomonModel.LAYER, TsunomonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GreymonModel.LAYER, GreymonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(BubbleBlowModel.LAYER, BubbleBlowModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MegaFlameModel.LAYER, MegaFlameModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(BlueBlasterModel.LAYER, BlueBlasterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(PepperBreathModel.LAYER, PepperBreathModel::createBodyLayer);
        EntityRendererRegistry.register(DCEntityTypes.DIGIMON, DigimonRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.PEPPER_BREATH, PepperBreathRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.BUBBLE_BLOW, BubbleBlowRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.MEGA_FLAME, MegaFlameRenderer::new);

        Constants.LOG.info("DigiCube client initialised.");
    }
}
