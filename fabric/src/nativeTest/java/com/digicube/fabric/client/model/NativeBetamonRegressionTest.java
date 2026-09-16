package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Compiled model, real effect transform and server cuboid agreement; no window needed. */
public final class NativeBetamonRegressionTest {
    private static double worst;
    private static int checks;
    private static Map<String,List<Vec3>> points(ModelPart root,PoseStack stack) {
        Map<String,List<Vec3>> result=new HashMap<>();
        root.visit(stack,(pose,path,index,cube)->{
            String name=path.substring(path.lastIndexOf('/')+1);
            var list=result.computeIfAbsent(name,k->new ArrayList<>());
            for(var polygon:cube.polygons)for(var vertex:polygon.vertices()) {
                var p=pose.pose().transformPosition(vertex.worldX(),vertex.worldY(),vertex.worldZ(),new org.joml.Vector3f());
                list.add(new Vec3(p.x,p.y,p.z));
            }
        });
        return result;
    }
    private static PoseStack stack(float yaw,Vec3 origin) {
        var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);
        TectonicWaveRenderer.applyWorldTransform(stack,yaw,.32F);
        stack.translate(0,EntityModel.MODEL_Y_OFFSET,0);return stack;
    }
    private static void near(Vec3 expected,List<Vec3> actual,String label) {
        if(actual==null || actual.isEmpty())throw new AssertionError("Missing "+label);
        double error=Math.sqrt(actual.stream().mapToDouble(expected::distanceToSqr).min().orElseThrow());
        worst=Math.max(worst,error);checks++;
        if(error>.0025)throw new AssertionError(label+" error="+error+" expected="+expected);
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var definition=NativeGroundModel.definitions().get(Constants.id("betamon"));
        var root=definition.createLayer().bakeRoot();var model=new NativeGroundModel(root,definition);
        var animation=new NativeAnimationSet(root,definition.animation());
        var state=new DigimonRenderState();state.modelScale=.32F;
        if(args.length>0) {
            com.google.gson.JsonObject data;
            try(var reader=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))) {
                data=net.minecraft.util.GsonHelper.parse(reader);
            }
            for(var entry:data.getAsJsonArray("samples")) {
                var row=entry.getAsJsonObject();String clip=row.get("clip").getAsString();float tick=row.get("tick").getAsFloat();
                root.getAllParts().forEach(ModelPart::resetPose);animation.apply(clip,tick,1);
                for(int heading=0;heading<8;heading++)for(int height=-1;height<=1;height++) {
                    float yaw=heading*45;var origin=new Vec3(13,81+height,-19);var actual=points(root,stack(yaw,origin));
                    for(var object:row.getAsJsonArray("objects")) {
                        var o=object.getAsJsonObject();String name=o.get("name").getAsString();
                        for(var vertex:o.getAsJsonArray("points")) {
                            var p=vertex.getAsJsonArray();var expected=new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble())
                                    .yRot((float)-Math.toRadians(yaw)).add(origin);
                            near(expected,actual.get(name),clip+" "+tick+" "+name);
                        }
                    }
                }
            }
        }
        var betamon=DigimonSpeciesRegistry.getOrThrow(Constants.id("betamon"));
        for(var attack:betamon.attacks())for(boolean water:new boolean[]{false,true}) {
            var d=AuthoredAttacks.get(attack);var fxRoot=NativeEffectModel.createLayer(d.effect()).bakeRoot();
            var fxModel=new NativeEffectModel(fxRoot,d.effect());var fx=new NativeEffectState();fx.scale=.32F;
            fx.clip=water?"effect_water":"effect";
            state.attackDefinition=attack;state.attackAnimationName=attack.id().getPath();state.attackInWater=water;
            state.swimAnimationAmount=water?1:0;state.attackAnimation.start(0);
            for(float t=attack.motion().activeFrom();t<=attack.motion().activeUntil();t+=.125F) {
                state.ageInTicks=t;model.setupAnim(state);fx.tick=t;fxModel.setupAnim(fx);
                for(int heading=0;heading<8;heading++)for(int height=-1;height<=1;height++) {
                    float yaw=heading*45;var origin=new Vec3(-17,79+height,29);
                    var actual=points(attack.id().getPath().equals("headbutt")?root:fxRoot,stack(yaw,origin));
                    var boxes=d.sample(t,water);
                    for(int i=0;i<boxes.length;i++)if(boxes[i]!=null) {
                        var b=boxes[i].world(origin,yaw,0);
                        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1})
                            near(b.center().add(b.x().scale(x)).add(b.y().scale(y)).add(b.z().scale(z)),actual.get(d.parts().get(i)),attack.id()+" water="+water+" tick="+t);
                    }
                }
            }
        }
        // Exercise actual shared-model resets, fluid variants and partial gait amounts.
        for(float movement:new float[]{0,.25F,.5F,.75F,1})for(float water:new float[]{0,.25F,.5F,.75F,1}) {
            state.groundAnimationAmount=state.swimMotionAmount=movement;state.swimAnimationAmount=water;state.attackInWater=water>=.5;
            for(var attack:betamon.attacks()) {
                state.attackDefinition=attack;state.attackAnimationName=attack.id().getPath();state.attackAnimation.start(0);
                for(float t=0;t<=attack.durationTicks();t+=.25F) {
                    state.ageInTicks=state.groundAnimationPhase=state.swimAnimationPhase=t;model.setupAnim(state);
                    for(var p:root.getAllParts())if(!Float.isFinite(p.x+p.y+p.z+p.xRot+p.yRot+p.zRot))throw new AssertionError("Nonfinite pose");
                }
                state.attackAnimation.stop();model.setupAnim(state);
            }
        }
        state.attackAnimation.stop();state.swimAnimationAmount=0;
        double floorError=0;
        for(float amount:new float[]{0,.25F,.5F,.75F,1})for(float t=0;t<=60;t+=.25F) {
            state.groundAnimationAmount=amount;state.ageInTicks=t;state.groundAnimationPhase=t;
            model.setupAnim(state);
            var vertices=points(root,stack(0,Vec3.ZERO));
            for(var entry:vertices.entrySet())if(entry.getKey().contains("foot"))
                for(var point:entry.getValue())floorError=Math.max(floorError,-point.y);
        }
        if(floorError>.0025)throw new AssertionError("Partial gait penetrates floor by "+floorError);
        Constants.LOG.info("[betamon-gait] PASS partial gait floor penetration={} blocks",floorError);
        Constants.LOG.info("[betamon-parity] PASS checks={} maxError={} blocks; eight headings, three elevations, land/water attacks and transition playback",checks,worst);
    }
}
