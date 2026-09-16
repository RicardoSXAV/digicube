package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.*;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Native vertices, actual renderer transforms, aimed horn and swept projectile solids. */
public final class NativeIkkakumonRegressionTest {
    static int fixtures;
    static void corners(AttackBox box,List<Vec3> vertices,String label) {
        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1})
            NativeGesomonRegressionTest.near(box.center().add(box.x().scale(x)).add(box.y().scale(y)).add(box.z().scale(z)),vertices,label);
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var def=NativeGroundModel.definitions().get(Constants.id("ikkakumon"));
        var root=NativeModelGeometry.apply(def.createLayer().bakeRoot(),def.geometry());
        var animation=new NativeAnimationSet(root,def.animation());
        if(args.length>0) {
            var data=com.google.gson.JsonParser.parseReader(java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))).getAsJsonObject();
            for(var sample:data.getAsJsonArray("samples")) {
                var row=sample.getAsJsonObject();String clip=row.get("clip").getAsString();float tick=row.get("tick").getAsFloat();
                root.getAllParts().forEach(ModelPart::resetPose);animation.apply(clip,tick,1);
                for(int heading=0;heading<8;heading++)for(int elevation=-1;elevation<=1;elevation++) {
                    float yaw=heading*45;var origin=new Vec3(31,80+elevation,-22);
                    var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);KineticProjectileRenderer.transform(stack,yaw,0,.5F);
                    var actual=NativeGesomonRegressionTest.points(root,stack);
                    for(var object:row.getAsJsonArray("objects")) {
                        var obj=object.getAsJsonObject();String name=obj.get("name").getAsString();
                        for(var point:obj.getAsJsonArray("points")) {
                            var p=point.getAsJsonArray();var expected=new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble()).yRot((float)-Math.toRadians(yaw)).add(origin);
                            NativeGesomonRegressionTest.near(expected,actual.get(name),clip+" "+tick+" "+name);
                        }
                    }
                    fixtures++;
                }
            }
        }
        var shot=KineticAttacks.get(Constants.id("harpoon_vulcan"));
        var heat=AuthoredAttacks.all().stream().filter(d->d.attack().id().equals(Constants.id("heat_top"))).findFirst().orElseThrow();
        var runtimeRoot=def.createLayer().bakeRoot();var runtime=new NativeGroundModel(runtimeRoot,def);
        var state=new DigimonRenderState();state.modelScale=.5F;
        for(float tick:new float[]{6,6.125F,7,7.875F,8,8.5F,9})for(int heading=0;heading<8;heading++)for(float pitch:new float[]{-35,0,35,60}) {
            state.attackDefinition=heat.attack();state.attackAnimationName="heat_top";state.attackAnimation.start(0);state.ageInTicks=tick;state.attackAimPitch=pitch;
            runtime.setupAnim(state);float yaw=heading*45;var origin=new Vec3(31,81,-22);
            var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);KineticProjectileRenderer.transform(stack,yaw,0,.5F);
            var vertices=NativeGesomonRegressionTest.points(runtimeRoot,stack).get("horn");
            for(var box:heat.sample(tick))corners(AuthoredVolumeAttack.aimed(box,heat.attack(),tick,pitch).world(origin,yaw,0),vertices,"heat_top "+tick+" pitch "+pitch);
            fixtures++;
        }
        var fxRoot=NativeEffectModel.createLayer("harpoon_vulcan_projectile").bakeRoot();var fx=new NativeEffectModel(fxRoot,"harpoon_vulcan_projectile");var fs=new NativeEffectState();
        var parts=new ArrayList<String>();for(int i=0;i<7;i++)parts.add("fx_launched_horn");for(int i=0;i<3;i++)parts.add("fx_missile");for(int i=0;i<2;i++)parts.add("fx_missile_fins");for(int i=0;i<2;i++)parts.add("fx_missile_nose");
        for(float tick:new float[]{0,.125F,1,2.875F,3,3.125F,5,8,12})for(int heading=0;heading<8;heading++)for(float pitch:new float[]{-60,0,60}) {
            fs.tick=tick;fx.setupAnim(fs);float yaw=heading*45;var origin=new Vec3(-17,81,29);var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);KineticProjectileRenderer.transform(stack,yaw,pitch,.5F);
            var actual=NativeGesomonRegressionTest.points(fxRoot,stack);double p=Math.toRadians(pitch),y=Math.toRadians(yaw);var direction=new Vec3(-Math.sin(y)*Math.cos(p),-Math.sin(p),Math.cos(y)*Math.cos(p));
            var boxes=shot.projectileMotion().sample(tick);
            for(int i=0;i<boxes.size();i++)if(Math.abs(boxes.get(i).x().dot(boxes.get(i).y().cross(boxes.get(i).z())))>1e-10)
                corners(KineticGeometry.flightBox(boxes.get(i),origin,direction),actual.get(parts.get(i)),"harpoon "+tick+" "+parts.get(i));
            fixtures++;
        }
        // Release muzzle and complete initial horn share the aimed head pose.
        for(int heading=0;heading<8;heading++)for(float pitch:new float[]{-35,0,35,60}) {
            root.getAllParts().forEach(ModelPart::resetPose);animation.apply("harpoon_vulcan",10,1);NativeArmAim.apply(root,shot,10,pitch);
            // Release transfers the complete mesh to the projectile while the head horn is hidden.
            ModelPart launchHorn=root;for(String n:List.of("ikkakumon","body","chest","neck","head","horn"))launchHorn=launchHorn.getChild(n);
            launchHorn.yScale=1;launchHorn.visible=true;
            float yaw=heading*45;var origin=new Vec3(12,79,-8);var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);KineticProjectileRenderer.transform(stack,yaw,0,.5F);
            var vertices=NativeGesomonRegressionTest.points(root,stack).get("horn");var aim=KineticGeometry.pose(shot.motion().sample(10),origin,yaw,pitch);
            for(var box:shot.projectileBoxes())if(Math.abs(box.x().dot(box.y().cross(box.z())))>1e-10)corners(KineticGeometry.flightBox(box,aim.muzzle(),aim.direction()),vertices,"release");
            fixtures++;
        }
        state.attackAnimation.stop();state.isBeingRidden=true;state.mountAnchor=new Vec3(.52,1.5,-.7375);
        for(float water:new float[]{0,.5F,1})for(float amount:new float[]{0,.125F,.5F,1})for(float tick:new float[]{0,7.125F,16,31.875F}) {
            state.swimAnimationAmount=water;state.groundAnimationAmount=state.swimMotionAmount=amount;state.groundAnimationPhase=state.swimAnimationPhase=state.ageInTicks=tick;runtime.setupAnim(state);
            var seat=runtime.riderOffset(state);if(!Double.isFinite(seat.length())||seat.length()>1)throw new AssertionError("Invalid animated seat "+seat);fixtures++;
        }
        // Interrupted hidden-horn attack must restore visibility for the next creature.
        state.isBeingRidden=false;state.attackDefinition=shot.attack();state.attackAnimationName="harpoon_vulcan";state.attackAnimation.start(0);state.ageInTicks=12;runtime.setupAnim(state);
        state.attackAnimation.stop();state.groundAnimationAmount=1;state.swimAnimationAmount=0;runtime.setupAnim(state);
        ModelPart horn=runtimeRoot;for(String n:List.of("ikkakumon","body","chest","neck","head","horn"))horn=horn.getChild(n);
        if(!horn.visible)throw new AssertionError("Horn visibility leaked between shared model instances");
        Constants.LOG.info("[ikkakumon-parity] PASS {} native/renderer/solid fixtures; max error {} blocks; riding and visibility reset",fixtures,NativeGesomonRegressionTest.worst);
    }
}
