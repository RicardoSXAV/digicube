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
        int covered=0,emitted=0;
        for(var attack:betamon.attacks())for(boolean water:new boolean[]{false,true}) {
            var d=AuthoredAttacks.get(attack);var fxRoot=NativeEffectModel.createLayer(d.effect()).bakeRoot();
            var fxModel=new NativeEffectModel(fxRoot,d.effect());var fx=new NativeEffectState();fx.scale=.32F;
            fx.clip=water?"effect_water":"effect";
            state.attackDefinition=attack;state.attackAnimationName=attack.id().getPath();state.attackInWater=water;
            state.swimAnimationAmount=water?1:0;state.attackAnimation.start(0);
            float from=d.discharges()?attack.hitTick()-1:(float)d.hitWindows().getFirst()[0];
            float until=d.discharges()?attack.hitTick()+1:(float)d.hitWindows().getLast()[1];
            for(float t=from;t<=until;t+=.125F) {
                state.ageInTicks=t;model.setupAnim(state);fx.tick=t;fxModel.setupAnim(fx);
                for(int heading=0;heading<8;heading++)for(int height=-1;height<=1;height++) {
                    float yaw=heading*45;var origin=new Vec3(-17,79+height,29);
                    var actual=points(root,stack(yaw,origin));
                    if(d.discharges()) {
                        // The bolt leaves where the fin is drawn: the server's emitter on the fin's front top edge.
                        var marker=com.digicube.entity.AttackGeometry.world(origin,d.motion(water).sample(t).mouth(),yaw);
                        var fin=new ArrayList<>(actual.get("dorsal_fin"));
                        fin.sort(Comparator.comparingDouble(marker::distanceToSqr));
                        Vec3 edge=null;
                        for(var p:fin)if(fin.getFirst().distanceTo(p)>.03){edge=p;break;}
                        var mid=fin.getFirst().add(edge).scale(.5);
                        // The clip plays on whole milliseconds and the fin shivers fast through the charge: a few tenths of
                        // a pixel between the drawn fin and the table sampled at the exact tick (a wrong frame is pixels off).
                        double error=marker.distanceTo(mid);checks++;emitted++;
                        if(error>.008)throw new AssertionError(attack.id()+" water="+water+" tick="+t+" emitter off the fin's edge by "+error);
                        continue;
                    }
                    // The struck volume covers the drawn brow: the forehead's foremost corners lie inside the box.
                    for(var box:d.sample(t,water))if(box!=null) {
                        var b=box.world(origin,yaw,0);var axisZ=b.z().normalize();
                        var ahead=b.center().subtract(origin).dot(axisZ)<0?axisZ.scale(-1):axisZ;
                        var brow=new ArrayList<>(actual.get("forehead_frame"));
                        brow.sort(Comparator.comparingDouble(p->-p.dot(ahead)));
                        for(var p:brow.subList(0,8)) {
                            var r=p.subtract(b.center());
                            for(var axis:new Vec3[]{b.x(),b.y(),b.z()}) {
                                double excess=Math.abs(r.dot(axis.normalize()))-axis.length();
                                worst=Math.max(worst,Math.max(0,excess));checks++;
                                if(excess>.0025)throw new AssertionError(attack.id()+" water="+water+" tick="+t+" brow outside the struck volume by "+excess);
                            }
                        }
                        covered++;
                    }
                }
            }
        }
        if(covered==0||emitted==0)throw new AssertionError("No headbutt volume or discharge emitter was checked");
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
        // A partial amount adds the idle's look round (the body turned on planted feet) to a planted walk column: the
        // sum of two planted poses is not quite planted, so a foot may dip up to half a model pixel while the gait
        // starts or stops. A wrong column or frame sinks it by pixels.
        if(floorError>.01)throw new AssertionError("Partial gait penetrates floor by "+floorError);
        Constants.LOG.info("[betamon-gait] PASS partial gait floor penetration={} blocks",floorError);
        Constants.LOG.info("[betamon-parity] PASS checks={} maxError={} blocks; eight headings, three elevations, land/water attacks and transition playback",checks,worst);
    }
}
