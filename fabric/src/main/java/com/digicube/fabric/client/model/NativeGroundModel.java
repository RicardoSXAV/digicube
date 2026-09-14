package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native idle and solved ground-walk assets, registered through the client catalog. */
public final class NativeGroundModel extends EntityModel<DigimonRenderState> {
    public record Definition(Identifier species, double cullingMargin, boolean amphibious, boolean walkBlend, java.util.List<String> aimPath,
                             float attackBlendIn, float attackBlendOut, boolean gallop) {
        public ModelLayerLocation layer() { return new ModelLayerLocation(species, "main"); }
        public Identifier geometry() { return species.withPath("models/entity/" + species.getPath() + ".mesh.json"); }
        public Identifier animation() { return species.withPath("models/entity/" + species.getPath() + ".animation.json"); }
        public Identifier texture() { return species.withPath("textures/entity/digimon/" + species.getPath() + ".png"); }
        public LayerDefinition createLayer() { return NativeModelGeometry.createLayer(geometry()); }
    }

    private static final Map<Identifier, Definition> DEFINITIONS = readDefinitions();
    private final NativeAnimationSet animations;
    private final Definition definition;
    private final ModelPart aimPart;
    private final ModelPart rootPart;

    public NativeGroundModel(ModelPart root, Definition definition) {
        super(NativeModelGeometry.apply(root, definition.geometry()));
        this.definition = definition;
        this.rootPart=root;
        animations = new NativeAnimationSet(root, definition.animation());
        ModelPart aim=root;
        for(String name:definition.aimPath()) aim=aim.getChild(name);
        aimPart=definition.aimPath().isEmpty()?null:aim;
    }

    public static Map<Identifier, Definition> definitions() { return DEFINITIONS; }
    public Definition definition() { return definition; }

    @Override
    public void setupAnim(DigimonRenderState state) {
        super.setupAnim(state);
        animations.hideMembranes();
        if (!state.isBeingRidden && state.attackAnimationName != null && state.attackAnimation.isStarted()) {
            float tick=state.attackAnimation.getTimeInMillis(state.ageInTicks)/50F;
            if(state.attackDefinition!=null && state.attackDefinition.kind()==com.digicube.digimon.DigimonAttack.Kind.CONSTRICTION) {
                for(var b:com.digicube.digimon.DigimonSpeciesBootstrap.CONSTRICTION_MOTION.blends(state.constrictionFit)) animations.apply(b.clip(),tick,b.weight());
                var offset=state.constrictionOffset.scale(16/state.modelScale);
                rootPart.x+=(float)offset.x;rootPart.y-=(float)offset.y;rootPart.z-=(float)offset.z;
            } else {
                float weight=1;
                if(definition.attackBlendIn()>0)weight=Math.min(weight,tick/definition.attackBlendIn());
                if(definition.attackBlendOut()>0)weight=Math.min(weight,(animations.length(state.attackAnimationName)-tick)/definition.attackBlendOut());
                weight=Math.clamp(weight,0,1);weight=weight*weight*(3-2*weight);
                applyGround(state,1-weight);
                animations.apply(state.attackAnimationName,tick,weight);
                var rootOffset = state.kineticOffset.scale(16 / state.modelScale);
                rootPart.x += (float) rootOffset.x; rootPart.y -= (float) rootOffset.y; rootPart.z -= (float) rootOffset.z;
                var kinetic = com.digicube.digimon.KineticAttacks.get(state.attackDefinition);
                if (kinetic != null) NativeArmAim.apply(rootPart, kinetic, tick, state.attackAimPitch);
                else if(aimPart!=null && state.attackDefinition!=null && state.attackDefinition.motion()!=null) {
                    aimPart.xRot+=state.attackAimPitch*state.attackDefinition.motion().sample(tick).aimWeight()*weight*((float)Math.PI/180);
                }
            }
            return;
        }
        applyGround(state,1);
    }

    private void applyGround(DigimonRenderState state,float weight) {
        if(weight<=0)return;
        float amount = Math.clamp(state.groundAnimationAmount, 0, 1);
        float water=definition.amphibious()?Math.clamp(state.swimAnimationAmount,0,1):0;
        if (definition.gallop()) {
            if (amount == 0) animations.apply("idle", state.ageInTicks, weight);
            else {
                float column = Math.clamp(state.groundRunAmount, 0, 1) * 8;
                int low = (int) column, high = Math.min(8, low + 1);
                animations.blend("gait_" + low, amount, state.groundAnimationPhase, weight * (1 - (column - low)));
                if (high != low) animations.blend("gait_" + high, amount, state.groundAnimationPhase, weight * (column - low));
            }
            return;
        }
        animations.apply("idle", state.ageInTicks, (1 - amount)*(1-water)*weight);
        // The lattice excludes the idle-at-zero contribution. Its amplitude zero
        // is rest, so the independent idle clock never doubles body or tail motion.
        if(definition.walkBlend()) animations.blend("walk", amount, state.groundAnimationPhase, (1-water)*weight);
        else animations.apply("walk",state.groundAnimationPhase,amount*(1-water)*weight);
        if(definition.amphibious()) {
            float power=Math.clamp(state.swimMotionAmount,0,1);
            animations.apply("swim_idle",state.ageInTicks,water*(1-power)*weight);
            animations.apply("swim",state.swimAnimationPhase,water*power*weight);
        }
    }

    private static Map<Identifier, Definition> readDefinitions() {
        try (var input = NativeGroundModel.class.getResourceAsStream("/assets/digicube/models/entity/ground_models.json")) {
            if (input == null) throw new IllegalStateException("Missing native ground model catalog");
            var data = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (data.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported ground model catalog");
            Map<Identifier, Definition> definitions = new LinkedHashMap<>();
            for (var entry : data.getAsJsonObject("models").entrySet()) {
                var species = Constants.id(entry.getKey());
                double margin = entry.getValue().getAsJsonObject().get("culling_margin").getAsDouble();
                if (!Double.isFinite(margin) || margin < 0) throw new IllegalArgumentException("Invalid native culling margin");
                var config=entry.getValue().getAsJsonObject();
                var aim=new java.util.ArrayList<String>();
                if(config.has("aim_path"))config.getAsJsonArray("aim_path").forEach(n->aim.add(n.getAsString()));
                definitions.put(species, new Definition(species, margin,GsonHelper.getAsBoolean(config,"amphibious",false),
                        GsonHelper.getAsBoolean(config,"walk_blend",true),java.util.List.copyOf(aim),
                        GsonHelper.getAsFloat(config,"attack_blend_in",0),GsonHelper.getAsFloat(config,"attack_blend_out",0),GsonHelper.getAsBoolean(config,"gallop",false)));
            }
            return Map.copyOf(definitions);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load native ground model catalog", e);
        }
    }
}
