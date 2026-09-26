package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.KineticAttacks;
import com.digicube.entity.KineticGeometry;
import com.digicube.fabric.client.render.KineticProjectileRenderer;
import com.digicube.fabric.client.render.NativeEffectState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Compares the shipped renderer vertices with native evidence and server ink solids. */
public final class NativeGesomonRegressionTest {
    static double worst;
    static Map<String,List<Vec3>> points(ModelPart root, PoseStack stack) {
        var result=new HashMap<String,List<Vec3>>();
        root.visit(stack,(pose,path,index,cube)->{
            var list=result.computeIfAbsent(path.substring(path.lastIndexOf('/')+1),k->new ArrayList<>());
            for(var polygon:cube.polygons)for(var v:polygon.vertices()) {
                var p=pose.pose().transformPosition(v.worldX(),v.worldY(),v.worldZ(),new org.joml.Vector3f());
                list.add(new Vec3(p.x,p.y,p.z));
            }
        });
        return result;
    }
    static void near(Vec3 expected,List<Vec3> actual,String label) {
        if(actual==null || actual.isEmpty())throw new AssertionError("Missing mesh "+label);
        double error=Math.sqrt(actual.stream().mapToDouble(expected::distanceToSqr).min().orElseThrow());
        worst=Math.max(worst,error);
        if(error>.008)throw new AssertionError(label+" vertex error "+error+" at "+expected);
    }
    static void check(boolean ok,String label) {
        if(!ok)throw new AssertionError(label);
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        com.digicube.digimon.DigimonSpeciesBootstrap.registerBuiltIn();
        var def=NativeGroundModel.definitions().get(Constants.id("gesomon"));
        var root=NativeModelGeometry.apply(def.createLayer().bakeRoot(),def.geometry());
        var animation=new NativeAnimationSet(root,def.animation());
        int fixtures=0;
        if(args.length>0) {
            var data=com.google.gson.JsonParser.parseReader(java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))).getAsJsonObject();
            for(var sample:data.getAsJsonArray("samples")) {
                var row=sample.getAsJsonObject();String clip=row.get("clip").getAsString();float tick=row.get("tick").getAsFloat();
                root.getAllParts().forEach(ModelPart::resetPose);animation.apply(clip,tick,1);
                for(int heading=0;heading<8;heading++)for(int elevation=-1;elevation<=1;elevation++) {
                    float yaw=heading*45;var origin=new Vec3(31,80+elevation,-22);
                    var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);
                    KineticProjectileRenderer.transform(stack,yaw,0,.5F);
                    var actual=points(root,stack);
                    for(var object:row.getAsJsonArray("objects")) {
                        var obj=object.getAsJsonObject();String name=obj.get("name").getAsString();
                        for(var point:obj.getAsJsonArray("points")) {
                            var p=point.getAsJsonArray();
                            var expected=new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble()).yRot((float)-Math.toRadians(yaw)).add(origin);
                            near(expected,actual.get(name),clip+" "+tick+" "+name);
                        }
                    }
                    fixtures++;
                }
            }
        }
        var ink=KineticAttacks.get(Constants.id("deadly_shade"));
        var bash=com.digicube.digimon.AuthoredAttacks.all().stream().filter(d->d.attack().id().equals(Constants.id("devil_bashing"))).findFirst().orElseThrow();
        for(float tick:new float[]{5,5.5F,6,7,7.5F,8,9.5F,10,10.5F,12,12.5F,13})for(int heading=0;heading<8;heading++) {
            root.getAllParts().forEach(ModelPart::resetPose);animation.apply("devil_bashing",tick,1);
            float yaw=heading*45;var origin=new Vec3(31,81,-22);var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);
            KineticProjectileRenderer.transform(stack,yaw,0,.5F);var actual=points(root,stack);
            var boxes=bash.sample(tick);
            for(int i=0;i<boxes.length;i++)if(boxes[i]!=null) {
                var box=boxes[i].world(origin,yaw,0);String part=bash.parts().get(i);part=part.substring(0,part.lastIndexOf('_'));
                for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1})
                    near(box.center().add(box.x().scale(x)).add(box.y().scale(y)).add(box.z().scale(z)),actual.get(part),"bash "+tick+" "+part);
            }
            fixtures++;
        }
        var fxRoot=NativeEffectModel.createLayer("deadly_shade_ink").bakeRoot();
        var model=new NativeEffectModel(fxRoot,"deadly_shade_ink");
        var state=new NativeEffectState();
        for(float tick:new float[]{.05F,.125F,.5F,1,3,5})for(int heading=0;heading<8;heading++)for(float pitch:new float[]{-18,0,18}) {
            state.tick=tick;model.setupAnim(state);float yaw=heading*45;
            var origin=new Vec3(-17,81,29);var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);
            KineticProjectileRenderer.transform(stack,yaw,pitch,.5F);
            var actual=points(fxRoot,stack).get("ink_burst");
            double p=Math.toRadians(pitch),y=Math.toRadians(yaw);
            var direction=new Vec3(-Math.sin(y)*Math.cos(p),-Math.sin(p),Math.cos(y)*Math.cos(p));
            for(var local:ink.projectileMotion().sample(tick)) {
                var box=KineticGeometry.flightBox(local,origin,direction);
                for(int x:new int[]{-1,1})for(int yy:new int[]{-1,1})for(int z:new int[]{-1,1})
                    near(box.center().add(box.x().scale(x)).add(box.y().scale(yy)).add(box.z().scale(z)),actual,"ink "+tick);
            }
            fixtures++;
        }
        var runtime=new NativeGroundModel(def.createLayer().bakeRoot(),def);
        var renderState=new com.digicube.fabric.client.render.DigimonRenderState();renderState.modelScale=.5F;
        for(float water:new float[]{0,.5F,1})for(float movement:new float[]{0,.5F,1})for(var move:List.of(bash.attack(),ink.attack())) {
            renderState.swimAnimationAmount=water;renderState.groundAnimationAmount=renderState.swimMotionAmount=movement;
            renderState.attackDefinition=move;renderState.attackAnimationName=move.id().getPath();renderState.attackAnimation.start(0);
            for(float tick=0;tick<=move.durationTicks();tick+=.125F) {
                renderState.ageInTicks=renderState.groundAnimationPhase=renderState.swimAnimationPhase=tick;
                runtime.setupAnim(renderState);
            }
            renderState.attackAnimation.stop();runtime.setupAnim(renderState);
        }
        // The rider sits astride the mantle's peak: drawn at rest where the sheet seats it, on land
        // and afloat (the swim pose carries the peak forward: water_seat_offset), and the dive pitch turns the body about the
        // rider, so the rider, and the first-person eye with it, stays in the saddle while the body swings under them.
        var mount=com.digicube.digimon.DigimonSpeciesRegistry.getOrThrow(Constants.id("gesomon")).body().mount().orElseThrow();
        renderState.attackAnimationName=null;renderState.attackDefinition=null;renderState.isBeingRidden=true;
        renderState.ageInTicks=0;renderState.groundAnimationAmount=0;renderState.xRot=0;
        renderState.swimAnimationAmount=renderState.swimMotionAmount=0;renderState.mountAnchor=mount.position(0);
        Vec3 seated=runtime.riderOffset(renderState);
        check(seated.length()<.06,"the rider is drawn at the sheet's seat on land: "+seated);
        renderState.swimAnimationAmount=renderState.swimMotionAmount=1;renderState.mountAnchor=mount.position(1);
        double drift=0;
        for(float t=0;t<36;t+=.5F){renderState.swimAnimationPhase=t;drift=Math.max(drift,runtime.riderOffset(renderState).length());}
        check(drift<.2,"afloat the rider rides the swim within 0.2 blocks of the sheet's water seat: "+drift);
        renderState.swimAnimationPhase=9;
        Vec3 level=runtime.riderOffset(renderState);
        Vec3 mantle=points(runtime.root(),new PoseStack()).get("mantle_tier_0").get(0);
        for(float pitch:new float[]{-60,-45,-20,20,45,60}) {
            renderState.xRot=pitch;
            near(level,List.of(runtime.riderOffset(renderState)),"diving at "+pitch+" degrees the rider stays in the saddle");
            fixtures++;
        }
        renderState.xRot=45;runtime.setupAnim(renderState);
        check(points(runtime.root(),new PoseStack()).get("mantle_tier_0").get(0).distanceTo(mantle)>.5,"the body pitches under its rider");
        renderState.isBeingRidden=false;runtime.setupAnim(renderState);
        near(mantle,List.of(points(runtime.root(),new PoseStack()).get("mantle_tier_0").get(0)),"without a rider the body stays upright (swim_pitch 0)");
        Constants.LOG.info("[gesomon-parity] PASS {} full vertex/solid fixtures, maximum error {} blocks; ground/water transition playback; seat {} blocks off at rest, {} afloat",
                fixtures,worst,String.format(Locale.ROOT,"%.3f",seated.length()),String.format(Locale.ROOT,"%.3f",drift));
    }
}
