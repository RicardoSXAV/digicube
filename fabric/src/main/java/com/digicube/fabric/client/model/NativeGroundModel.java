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
public final class NativeGroundModel extends EntityModel<DigimonRenderState> implements AnimatedRiderModel {
    public record Rider(java.util.List<String> path, net.minecraft.world.phys.Vec3 point) {}
    public record Definition(Identifier species, double cullingMargin, boolean amphibious, boolean walkBlend, java.util.List<String> aimPath,
                             float attackBlendIn, float attackBlendOut, boolean gallop, boolean supportFloor, Rider rider) {
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
            String attackClip=state.attackInWater && animations.has(state.attackAnimationName+"_water")
                    ? state.attackAnimationName+"_water" : state.attackAnimationName;
            float tick=state.attackAnimation.getTimeInMillis(state.ageInTicks)/50F;
            if(state.attackDefinition!=null && state.attackDefinition.kind()==com.digicube.digimon.DigimonAttack.Kind.CONSTRICTION) {
                for(var b:com.digicube.digimon.DigimonSpeciesBootstrap.CONSTRICTION_MOTION.blends(state.constrictionFit)) animations.apply(b.clip(),tick,b.weight());
                var offset=state.constrictionOffset.scale(16/state.modelScale);
                rootPart.x+=(float)offset.x;rootPart.y-=(float)offset.y;rootPart.z-=(float)offset.z;
            } else {
                float weight=1;
                if(definition.attackBlendIn()>0)weight=Math.min(weight,tick/definition.attackBlendIn());
                if(definition.attackBlendOut()>0)weight=Math.min(weight,(animations.length(attackClip)-tick)/definition.attackBlendOut());
                weight=Math.clamp(weight,0,1);weight=weight*weight*(3-2*weight);
                applyGround(state,1-weight);
                if(definition.supportFloor()) {
                    // Blend complete poses with shortest-arc quaternions. Segmented
                    // tentacles can cross Euler's wrap boundary during a pad strike.
                    rootPart.getAllParts().forEach(ModelPart::resetPose);
                    applyGround(state,1);
                    var base=rootPart.getAllParts().stream().map(p->new float[]{p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale}).toList();
                    rootPart.getAllParts().forEach(ModelPart::resetPose);
                    animations.apply(attackClip,tick,1);
                    var all=rootPart.getAllParts();
                    for(int i=0;i<all.size();i++) {
                        var p=all.get(i);var a=base.get(i);
                        var q=new org.joml.Quaternionf().rotationZYX(a[5],a[4],a[3])
                                .slerp(new org.joml.Quaternionf().rotationZYX(p.zRot,p.yRot,p.xRot),weight);
                        var e=q.getEulerAnglesZYX(new org.joml.Vector3f());
                        p.x=a[0]+(p.x-a[0])*weight;p.y=a[1]+(p.y-a[1])*weight;p.z=a[2]+(p.z-a[2])*weight;
                        p.xRot=e.x;p.yRot=e.y;p.zRot=e.z;
                        p.xScale=a[6]+(p.xScale-a[6])*weight;p.yScale=a[7]+(p.yScale-a[7])*weight;p.zScale=a[8]+(p.zScale-a[8])*weight;
                    }
                } else animations.apply(attackClip,tick,weight);
                var rootOffset = state.kineticOffset.scale(16 / state.modelScale);
                rootPart.x += (float) rootOffset.x; rootPart.y -= (float) rootOffset.y; rootPart.z -= (float) rootOffset.z;
                var kinetic = com.digicube.digimon.KineticAttacks.get(state.attackDefinition);
                if (kinetic != null) NativeArmAim.apply(rootPart, kinetic, tick, state.attackAimPitch);
                else if(aimPart!=null && state.attackDefinition!=null && state.attackDefinition.motion()!=null) {
                    aimPart.xRot+=state.attackAimPitch*state.attackDefinition.motion().sample(tick).aimWeight()*weight*((float)Math.PI/180);
                }
            }
            supportFloor(state);
            return;
        }
        applyGround(state,1);
        if (definition.rider()!=null) {
            ModelPart back=rootPart;
            for(String name:definition.rider().path())back=back.getChild(name);
            float water=Math.clamp(state.swimAnimationAmount,0,1);
            float limit=state.isBeingRidden?8:65;
            back.xRot+=Math.clamp(state.xRot,-limit,limit)*((float)Math.PI/180)*water;
            back.zRot+=state.swimBank*(state.isBeingRidden?.25F:1)*((float)Math.PI/180)*water;
        }
        supportFloor(state);
    }

    @Override public net.minecraft.world.phys.Vec3 riderOffset(DigimonRenderState state) {
        if(definition.rider()==null)return net.minecraft.world.phys.Vec3.ZERO;
        setupAnim(state);
        var stack=new com.mojang.blaze3d.vertex.PoseStack();
        ModelPart part=rootPart;part.translateAndRotate(stack);
        for(String name:definition.rider().path()){part=part.getChild(name);part.translateAndRotate(stack);}
        var v=definition.rider().point();
        var p=stack.last().pose().transformPosition((float)v.x,(float)-v.z,(float)v.y,new org.joml.Vector3f());
        return new net.minecraft.world.phys.Vec3(p.x,1.5-p.y,-p.z).scale(state.modelScale).subtract(state.mountAnchor);
    }
    @Override public RiderPose riderPose(){return new RiderPose(0,0,0);}
    @Override public double cullingMargin(){return definition.cullingMargin();}

    private void supportFloor(DigimonRenderState state) {
        if(!definition.supportFloor() || state.swimAnimationAmount>.01F)return;
        float[] lowest={1.5F};
        rootPart.visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
            if(!path.contains("short_"))return;
            for(var polygon:cube.polygons)for(var vertex:polygon.vertices()) {
                var v=pose.pose().transformPosition(vertex.worldX(),vertex.worldY(),vertex.worldZ(),new org.joml.Vector3f());
                lowest[0]=Math.max(lowest[0],v.y);
            }
        });
        rootPart.y-=Math.max(0,lowest[0]-1.5F)*16;
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
                Rider rider=null;
                if(config.has("rider")) {
                    var r=config.getAsJsonObject("rider");var path=new java.util.ArrayList<String>();
                    r.getAsJsonArray("path").forEach(n->path.add(n.getAsString()));var p=r.getAsJsonArray("point");
                    rider=new Rider(java.util.List.copyOf(path),new net.minecraft.world.phys.Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble()));
                }
                definitions.put(species, new Definition(species, margin,GsonHelper.getAsBoolean(config,"amphibious",false),
                        GsonHelper.getAsBoolean(config,"walk_blend",true),java.util.List.copyOf(aim),
                        GsonHelper.getAsFloat(config,"attack_blend_in",0),GsonHelper.getAsFloat(config,"attack_blend_out",0),GsonHelper.getAsBoolean(config,"gallop",false),GsonHelper.getAsBoolean(config,"support_floor",false),rider));
            }
            return Map.copyOf(definitions);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load native ground model catalog", e);
        }
    }
}
