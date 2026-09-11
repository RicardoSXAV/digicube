package com.digicube.fabric.client;

import com.digicube.Constants;
import com.digicube.fabric.client.dev.DevClient;
import com.digicube.fabric.client.party.PartyClient;
import com.digicube.fabric.client.starter.StarterClient;
import com.digicube.fabric.client.model.AgumonModel;
import com.digicube.fabric.client.model.GabumonModel;
import com.digicube.fabric.client.model.GomamonModel;
import com.digicube.fabric.client.model.IkkakumonModel;
import com.digicube.fabric.client.model.TentomonModel;
import com.digicube.fabric.client.model.GarurumonModel;
import com.digicube.fabric.client.model.KoromonModel;
import com.digicube.fabric.client.model.TsunomonModel;
import com.digicube.fabric.client.model.GreymonModel;
import com.digicube.fabric.client.model.BubbleBlowModel;
import com.digicube.fabric.client.model.MegaFlameModel;
import com.digicube.fabric.client.model.MarchingFishesModel;
import com.digicube.fabric.client.render.MarchingFishesRenderer;
import com.digicube.fabric.client.model.BlueBlasterModel;
import com.digicube.fabric.client.model.HowlingBlasterModel;
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
        new StarterClient().init();
        new DevClient().init();
        new AerialMountClient().init();
        for (var definition : com.digicube.fabric.client.model.NativeGroundModel.definitions().values()) {
            ModelLayerRegistry.registerModelLayer(definition.layer(), definition::createLayer);
        }
        for (var species : com.digicube.digimon.DigimonSpeciesRegistry.all()) {
            if (species.body().mount().map(m -> m.flight()!=null).orElse(false)) {
                var id=species.id();
                ModelLayerRegistry.registerModelLayer(new net.minecraft.client.model.geom.ModelLayerLocation(id,"main"),
                        () -> com.digicube.fabric.client.model.NativeFlyingMountModel.createLayer(id));
            }
        }
        com.digicube.fabric.client.render.IceMarkBadge.init();
        ModelLayerRegistry.registerModelLayer(AgumonModel.LAYER, AgumonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GabumonModel.LAYER, GabumonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GomamonModel.LAYER, GomamonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(IkkakumonModel.LAYER, IkkakumonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(TentomonModel.LAYER, TentomonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GarurumonModel.LAYER, GarurumonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(KoromonModel.LAYER, KoromonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(TsunomonModel.LAYER, TsunomonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GreymonModel.LAYER, GreymonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(BubbleBlowModel.LAYER, BubbleBlowModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MegaFlameModel.LAYER, MegaFlameModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MarchingFishesModel.LAYER, MarchingFishesModel::createBodyLayer);
        for (String effect : new String[]{"rock_punch_fx", "tectonic_fist_fx"}) {
            ModelLayerRegistry.registerModelLayer(com.digicube.fabric.client.model.NativeEffectModel.layer(effect),
                    () -> com.digicube.fabric.client.model.NativeEffectModel.createLayer(effect));
        }
        ModelLayerRegistry.registerModelLayer(BlueBlasterModel.LAYER, BlueBlasterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HowlingBlasterModel.LAYER, HowlingBlasterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(com.digicube.fabric.client.model.IceBlastModel.LAYER,
                com.digicube.fabric.client.model.IceBlastModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(PepperBreathModel.LAYER, PepperBreathModel::createBodyLayer);
        EntityRendererRegistry.register(DCEntityTypes.DIGIMON, DigimonRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.PEPPER_BREATH, PepperBreathRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.BUBBLE_BLOW, BubbleBlowRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.MEGA_FLAME, MegaFlameRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.MARCHING_FISHES, MarchingFishesRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.TECTONIC_WAVE, com.digicube.fabric.client.render.TectonicWaveRenderer::new);

        Constants.LOG.info("DigiCube client initialised.");
    }
}
