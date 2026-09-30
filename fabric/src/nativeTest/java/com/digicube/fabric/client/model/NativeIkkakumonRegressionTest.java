package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.*;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Native vertices, actual renderer transforms, aimed horn and swept projectile solids; and the mount as the compiled
 * NativeGroundModel draws it: every foot flat and still through its stance in all four walking directions, the rider
 * drawn centred on the mane (the sheet's seat, the first-person eye, 0.3 blocks to the right of it) on land, afloat and
 * through the stroke, tipped with the body as it dives and rolls.
 */
public final class NativeIkkakumonRegressionTest {
    static int fixtures;
    static double worstPlant;

    /** A part's pivot in the entity's frame at yaw 0 (blocks, +z forward, feet at 0), as riderOffset maps model space. */
    static Vec3 pivot(ModelPart root, List<String> path, float scale) {
        var stack = new PoseStack();
        ModelPart part = root; part.translateAndRotate(stack);
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var p = stack.last().pose().transformPosition(0, 0, 0, new org.joml.Vector3f());
        return new Vec3(p.x, 1.5 - p.y, -p.z).scale(scale);
    }

    static void check(boolean condition, String label) { fixtures++; if (!condition) throw new AssertionError(label); }

    /**
     * Each foot in each direction: from a sixth of its stance to three quarters of it (heel down, before the push-off)
     * the ankle moves with the ground under the body, the direction's own pace, and holds its height.
     */
    static void planted(NativeGroundModel model, ModelPart root, DigimonRenderState state, com.digicube.digimon.DigimonGait gait, float scale) {
        String[] directions = {"walk", "walk_back", "strafe_left", "strafe_right"};
        double[] strides = {gait.stride(), gait.backStride(), gait.sideStride(), gait.sideStride()};
        Vec3[] ground = {new Vec3(0, 0, -1), new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(1, 0, 0)};
        record Foot(String name, List<String> path, float touch, float duty) {}
        var feet = List.of(
                new Foot("left fore", List.of("ikkakumon", "body", "chest", "fore_upper_left", "fore_lower_left", "forefoot_left"), .2F, .55F),
                new Foot("right fore", List.of("ikkakumon", "body", "chest", "fore_upper_right", "fore_lower_right", "forefoot_right"), .7F, .55F),
                new Foot("left hind", List.of("ikkakumon", "body", "hind_upper_left", "hind_lower_left", "hindfoot_left"), 0F, .5F),
                new Foot("right hind", List.of("ikkakumon", "body", "hind_upper_right", "hind_lower_right", "hindfoot_right"), .5F, .5F));
        float cycle = gait.cycleTicks();
        state.isBeingRidden = false; state.swimAnimationAmount = 0; state.groundAnimationAmount = 1; state.ageInTicks = 0;
        for (int d = 0; d < 4; d++) {
            state.gaitShares = new float[4]; state.gaitShares[d] = 1;
            double pace = strides[d] * scale / cycle;
            for (var foot : feet) {
                float from = (foot.touch() + .16F * foot.duty()) * cycle, until = (foot.touch() + .76F * foot.duty()) * cycle;
                state.groundAnimationPhase = from; model.setupAnim(state);
                Vec3 start = pivot(root, foot.path(), scale);
                for (float t = from; t <= until; t += .125F) {
                    state.groundAnimationPhase = t; model.setupAnim(state);
                    Vec3 expected = start.add(ground[d].scale(pace * (t - from)));
                    double error = pivot(root, foot.path(), scale).distanceTo(expected);
                    worstPlant = Math.max(worstPlant, error);
                    check(error < .012, directions[d] + " " + foot.name() + " ankle slides at " + t + ": " + error);
                }
            }
        }
        // The galumph (the run lattice, over the forward share): the forefeet land together, then the hind pair.
        state.gaitShares = new float[]{1, 0, 0, 0}; state.groundRunAmount = 1;
        double pace = gait.runStride() * scale / cycle;
        float[] touch = {0F, .06F, .46F, .52F}, duty = {.34F, .34F, .33F, .33F};
        for (int f = 0; f < feet.size(); f++) {
            var foot = feet.get(f);
            float from = (touch[f] + .16F * duty[f]) * cycle, until = (touch[f] + .76F * duty[f]) * cycle;
            state.groundAnimationPhase = from; model.setupAnim(state);
            Vec3 start = pivot(root, foot.path(), scale);
            for (float t = from; t <= until; t += .125F) {
                state.groundAnimationPhase = t; model.setupAnim(state);
                double error = pivot(root, foot.path(), scale).distanceTo(start.add(0, 0, -pace * (t - from)));
                worstPlant = Math.max(worstPlant, error);
                check(error < .012, "run " + foot.name() + " ankle slides at " + t + ": " + error);
            }
        }
        state.groundRunAmount = 0;
    }
    static void corners(AttackBox box,List<Vec3> vertices,String label) {
        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1})
            NativeGesomonRegressionTest.near(box.center().add(box.x().scale(x)).add(box.y().scale(y)).add(box.z().scale(z)),vertices,label);
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        com.digicube.digimon.DigimonSpeciesBootstrap.registerBuiltIn();
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
        state.attackAnimation.stop();
        var species=com.digicube.digimon.DigimonSpeciesRegistry.getOrThrow(Constants.id("ikkakumon"));
        var mount=species.body().mount().orElseThrow();
        var gait=species.locomotion().groundGait();
        planted(runtime,runtimeRoot,state,gait,.5F);
        for(String clip:List.of("swim","swim_idle","swim_dash","swim_surface","swim_surface_dash","swim_leap","walk_zero"))check(animation.has(clip),"clip "+clip);
        // The water effects keep time with the clips: a swish a stroke, a splash a paddle.
        var wake=def.swimWake();
        check(wake!=null&&animation.length("swim")==wake.stroke()&&animation.length("swim_surface")==wake.paddle()
                &&animation.length("swim_surface_dash")==wake.surgePaddle(),"swim_wake beats match the swim clips");
        // The rider: drawn centred on the mane; the sheet's seat (the first-person eye) is 0.3 blocks to the right of it.
        var eye=new Vec3(.3,0,0);
        state.isBeingRidden=true;state.swimAnimationAmount=0;state.groundAnimationAmount=0;state.ageInTicks=0;state.mountAnchor=mount.position(0);
        var seat=runtime.riderOffset(state);
        check(seat.distanceTo(eye)<.06,"at rest the rider is drawn centred, 0.3 blocks left of the eye: "+seat);
        var lean=runtime.riderLean(state);
        check(lean!=null&&Math.abs(lean[0])<4&&Math.abs(lean[1])<2,"at rest the rider sits up: "+java.util.Arrays.toString(lean));
        double walked=0;
        state.groundAnimationAmount=1;
        for(int d=0;d<4;d++){state.gaitShares=new float[4];state.gaitShares[d]=1;
            for(float t=0;t<gait.cycleTicks();t+=.5F){state.groundAnimationPhase=t;walked=Math.max(walked,runtime.riderOffset(state).distanceTo(eye));}}
        state.gaitShares=new float[]{1,0,0,0};
        double galloped=0;state.groundRunAmount=1;
        for(float t=0;t<gait.cycleTicks();t+=.5F){state.groundAnimationPhase=t;galloped=Math.max(galloped,runtime.riderOffset(state).distanceTo(eye));}
        state.groundRunAmount=0;
        check(galloped<.5,"galumphing the rider rocks with the body, no further: "+galloped);
        // the rider rocks with the waddle (the seat is high over the rolling hips) and dips with a shove from the side
        check(walked<.3,"walking any way the rider rides the bob and the waddle, no further: "+walked);
        // Afloat the ridden body keeps its stroke: the seat is where the sheet's water seat puts the eye, all through it.
        state.groundAnimationAmount=0;state.swimAnimationAmount=1;state.swimMotionAmount=1;state.mountAnchor=mount.position(1);
        double swum=0;
        for(float t=0;t<28;t+=.5F){state.swimAnimationPhase=t;state.swimDash=t>14?1:0;swum=Math.max(swum,runtime.riderOffset(state).distanceTo(eye));}
        check(swum<.2,"swimming and dashing the rider stays on the stretched-out mane: "+swum);
        // Afloat at the float line it paddles head out; the seat stays where the eye is through the paddles and the surge.
        double paddled=0;state.swimSurface=1;
        for(float t=0;t<24;t+=.5F){state.swimAnimationPhase=t;state.swimDash=t>12?1:0;paddled=Math.max(paddled,runtime.riderOffset(state).distanceTo(eye));}
        check(paddled<.2,"paddling along the surface the rider stays on the mane over the eye: "+paddled);
        state.swimDash=0;state.turnBank=40;runtime.riderOffset(state);lean=runtime.riderLean(state);
        check(Math.abs(lean[1])<16,"afloat at the surface a hard turn banks the rider only a little: "+lean[1]);
        state.turnBank=0;state.swimSurface=0;
        state.swimDash=0;state.xRot=50;runtime.riderOffset(state);lean=runtime.riderLean(state);
        check(lean[0]>25&&lean[0]<50,"diving the rider leans forward with the body: "+lean[0]);
        state.xRot=0;state.swimRoll=90;runtime.riderOffset(state);lean=runtime.riderLean(state);
        check(Math.abs(Math.abs(lean[1])-90)<12,"a barrel roll carries the rider round: "+lean[1]);
        state.swimRoll=0;state.turnBank=40;runtime.riderOffset(state);lean=runtime.riderLean(state);
        check(Math.abs(lean[1])>25&&Math.abs(lean[1])<38,"a hard turn banks the body and its rider, to the catalog's limit: "+lean[1]);
        state.turnBank=0;state.swimLeap=1;state.swimAnimationAmount=1;
        check(Double.isFinite(runtime.riderOffset(state).length()),"a leap from the water keeps a seat");
        state.swimLeap=0;state.swimAnimationAmount=0;state.mountAnchor=mount.position(0);
        for(var name:List.of("heat_top","harpoon_vulcan")){
            var atk=species.attacks().stream().filter(a->a.id().getPath().equals(name)).findFirst().orElseThrow();
            state.attackDefinition=atk;state.attackAnimationName=name;state.attackAnimation.start(0);
            for(float t=0;t<=atk.durationTicks();t+=1){state.ageInTicks=t;var s=runtime.riderOffset(state);
                check(Double.isFinite(s.length())&&s.distanceTo(eye)<1,"a rider's "+name+" carries the rider with the bow, no further: "+s);}
            state.attackAnimation.stop();
        }
        state.ageInTicks=0;
        // Interrupted hidden-horn attack must restore visibility for the next creature.
        state.isBeingRidden=false;state.attackDefinition=shot.attack();state.attackAnimationName="harpoon_vulcan";state.attackAnimation.start(0);state.ageInTicks=12;runtime.setupAnim(state);
        state.attackAnimation.stop();state.groundAnimationAmount=1;state.swimAnimationAmount=0;runtime.setupAnim(state);
        ModelPart horn=runtimeRoot;for(String n:List.of("ikkakumon","body","chest","neck","head","horn"))horn=horn.getChild(n);
        if(!horn.visible)throw new AssertionError("Horn visibility leaked between shared model instances");
        Constants.LOG.info("[ikkakumon-parity] PASS {} native/renderer/solid fixtures; max error {} blocks; planted ankles within {} blocks; riding and visibility reset",
                fixtures,NativeGesomonRegressionTest.worst,worstPlant);
    }
}
