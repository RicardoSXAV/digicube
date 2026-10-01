package com.digicube.fabric.client;

import com.digicube.Constants;
import com.digicube.fabric.client.dev.DevClient;
import com.digicube.fabric.client.party.PartyClient;
import com.digicube.fabric.client.starter.StarterClient;
import com.digicube.fabric.client.model.GabumonModel;
import com.digicube.fabric.client.model.GomamonModel;
import com.digicube.fabric.client.model.TentomonModel;
import com.digicube.fabric.client.model.KoromonModel;
import com.digicube.fabric.client.model.TsunomonModel;
import com.digicube.fabric.client.model.GreymonModel;
import com.digicube.fabric.client.model.BubbleBlowModel;
import com.digicube.fabric.client.model.MegaFlameModel;
import com.digicube.fabric.client.model.MarchingFishesModel;
import com.digicube.fabric.client.render.MarchingFishesRenderer;
import com.digicube.fabric.client.model.BlueBlasterModel;
import com.digicube.fabric.client.render.MegaFlameRenderer;
import com.digicube.fabric.client.render.BubbleBlowRenderer;
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
        com.digicube.fabric.client.evolution.EvolutionRenderType.GRID.pipeline();
        com.digicube.fabric.client.render.SolidGlow.pipeline();
        new com.digicube.fabric.client.evolution.EvolutionAudio().init();
        new com.digicube.fabric.client.render.BreathAudio().init();
        new PartyClient().init();
        new StarterClient().init();
        new com.digicube.fabric.client.digivice.DigiviceLocator().init();
        com.digicube.fabric.client.digivice.RecallVisuals.init();
        com.digicube.fabric.client.digivice.DigiviceCursor.init();
        new DevClient().init();
        new AerialMountClient().init();
        com.digicube.fabric.client.render.PixelPlaneParticle.register();
        com.digicube.fabric.client.render.InkParticle.register();
        com.digicube.fabric.client.render.InkedVisuals.init();
        com.digicube.fabric.client.render.BurnParticle.register();
        com.digicube.fabric.client.render.BurnedVisuals.init();
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
        com.digicube.fabric.client.render.CombatMarkBadges.init();
        com.digicube.fabric.client.render.HoofBeats.init();
        com.digicube.fabric.client.render.Stomps.init();
        com.digicube.fabric.client.render.PawFalls.init();
        com.digicube.fabric.client.render.SwimWake.init();
        ModelLayerRegistry.registerModelLayer(GabumonModel.LAYER, GabumonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GomamonModel.LAYER, GomamonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(TentomonModel.LAYER, TentomonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(KoromonModel.LAYER, KoromonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(TsunomonModel.LAYER, TsunomonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(GreymonModel.LAYER, GreymonModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(BubbleBlowModel.LAYER, BubbleBlowModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MegaFlameModel.LAYER, MegaFlameModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MarchingFishesModel.LAYER, MarchingFishesModel::createBodyLayer);
        var casterEffects = new java.util.LinkedHashSet<>(java.util.List.of("rock_punch_fx", "tectonic_fist_fx"));
        for (var definition : com.digicube.fabric.client.model.NativeGroundModel.definitions().values())
            if (definition.attackEffects() != null) casterEffects.add(definition.attackEffects().effect());
        for (String effect : casterEffects) {
            ModelLayerRegistry.registerModelLayer(com.digicube.fabric.client.model.NativeEffectModel.layer(effect),
                    () -> com.digicube.fabric.client.model.NativeEffectModel.createLayer(effect));
        }
        // A move's forms share one effect model: one layer each.
        var authoredEffects = new java.util.LinkedHashSet<String>();
        for(var definition:com.digicube.digimon.AuthoredAttacks.all()) if(definition.effect()!=null) authoredEffects.add(definition.effect());
        // A pounce's impact is an effect model too (a breath's flame is laid from its mesh's boxes, no layer).
        for (var attack : com.digicube.digimon.PounceAttacks.attacks()) {
            String impact = com.digicube.digimon.PounceAttacks.get(attack).impact();
            if (!impact.isEmpty()) authoredEffects.add(impact);
        }
        authoredEffects.removeAll(casterEffects);
        for (String effect : authoredEffects) {
            ModelLayerRegistry.registerModelLayer(com.digicube.fabric.client.model.NativeEffectModel.layer(effect),
                    () -> com.digicube.fabric.client.model.NativeEffectModel.createLayer(effect));
        }
        ModelLayerRegistry.registerModelLayer(BlueBlasterModel.LAYER, BlueBlasterModel::createBodyLayer);
        EntityRendererRegistry.register(DCEntityTypes.DIGIMON, DigimonRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.DROPPED_DIGIVICE, com.digicube.fabric.client.render.DroppedDigiviceRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.PEPPER_BREATH, PepperBreathRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.BUBBLE_BLOW, BubbleBlowRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.MEGA_FLAME, MegaFlameRenderer::new);
        for (var definition : com.digicube.digimon.KineticAttacks.all()) if (definition.projectile() != null) {
            String name = definition.projectile();
            ModelLayerRegistry.registerModelLayer(com.digicube.fabric.client.model.NativeEffectModel.layer(name),
                    () -> com.digicube.fabric.client.model.NativeEffectModel.createLayer(name));
        }
        EntityRendererRegistry.register(DCEntityTypes.KINETIC_PROJECTILE, com.digicube.fabric.client.render.KineticProjectileRenderer::new);
        // Thrown weapons: one mesh each (every species shares the entity types; today only Mojyamon throws).
        var boneMesh = com.digicube.digimon.ThrownAttacks.returning().stream().findFirst().map(com.digicube.digimon.ThrownAttacks.Returning::projectile).orElse(null);
        var icicleMesh = com.digicube.digimon.ThrownAttacks.charged().stream().findFirst().map(com.digicube.digimon.ThrownAttacks.Charged::projectile).orElse(null);
        for (String name : new String[]{boneMesh, icicleMesh}) if (name != null)
            ModelLayerRegistry.registerModelLayer(com.digicube.fabric.client.model.NativeEffectModel.layer(name),
                    () -> com.digicube.fabric.client.model.NativeEffectModel.createLayer(name));
        if (boneMesh != null) EntityRendererRegistry.register(DCEntityTypes.BOOMERANG, com.digicube.fabric.client.render.ThrownPropRenderer.bone(boneMesh));
        if (icicleMesh != null) EntityRendererRegistry.register(DCEntityTypes.ICICLE, com.digicube.fabric.client.render.ThrownPropRenderer.icicle(icicleMesh));
        EntityRendererRegistry.register(DCEntityTypes.MARCHING_FISHES, MarchingFishesRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.VOLLEY_MISSILE, com.digicube.fabric.client.render.VolleyMissileRenderer::new);
        EntityRendererRegistry.register(DCEntityTypes.TECTONIC_WAVE, com.digicube.fabric.client.render.TectonicWaveRenderer::new);

        Constants.LOG.info("DigiCube client initialised.");
    }
}
