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
        var fxRoot=NativeEffectModel.createLayer("deadly_shade_ink").bakeRoot();
        var model=new NativeEffectModel(fxRoot,"deadly_shade_ink");
        var state=new NativeEffectState();
        for(float tick:new float[]{.05F,.125F,.5F,1,3,5})for(int heading=0;heading<8;heading++)for(float pitch:new float[]{-18,0,18}) {
            state.tick=tick;model.setupAnim(state);float yaw=heading*45;
            var origin=new Vec3(-17,81,29);var stack=new PoseStack();stack.translate(origin.x,origin.y,origin.z);
            // Drawn at the attack's model scale times its projectile scale, as the renderer draws it.
            KineticProjectileRenderer.transform(stack,yaw,pitch,ink.modelScale()*ink.projectileScale());
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
        for(float water:new float[]{0,.5F,1})for(float movement:new float[]{0,.5F,1})for(var move:List.of(ink.attack())) {
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
        // The whip (Devil Bashing, a rider's or the AI's): the arm the client draws is the arm the server strikes with, joint
        // by joint, through a long wind-up (the coil at a full charge) and a lash, with either arm. Held, the pad keeps its
        // face: it used to roll a full turn every 11 ticks with the twirl, the hand spinning in the rider's view.
        var whipSpec=com.digicube.digimon.WhipAttacks.get(Constants.id("devil_bashing"));
        double whipWorst=0,padTurn=0;
        for(int side:new int[]{1,-1}) {
            var whip=new com.digicube.entity.WhipArm(whipSpec);
            whip.wind(side);
            org.joml.Matrix3f lastPad=null;
            for(int t=0;t<50;t++) {
                if(t==34)whip.release();
                whip.tick(t<34?-side*20:35-(t-34)*6,t<34?5:25);
                renderState.isBeingRidden=true;renderState.xRot=0;renderState.ageInTicks=0;renderState.groundAnimationAmount=0;
                renderState.swimAnimationAmount=renderState.swimMotionAmount=0;renderState.mountAnchor=mount.position(0);
                com.digicube.fabric.client.render.DigimonRenderer.pose(whip,renderState,1);renderState.whipWeight=1;
                runtime.setupAnim(renderState);
                var drawn=whipJoints(runtime.root(),renderState.whipArm,renderState.modelScale);
                var struck=whip.joints(mount.position(0),0,0,0,1);
                for(int i=0;i<struck.length;i++){
                    double e=drawn.get(i).distanceTo(struck[i]);
                    whipWorst=Math.max(whipWorst,e);
                    check(e<.12,"whip joint "+i+" of side "+side+" at tick "+t+" is drawn "+e+" blocks from where it strikes");
                }
                var pad=partTurn(runtime.root(),renderState.whipArm);
                if(t>=12&&t<34&&lastPad!=null){
                    double turn=Math.toDegrees(between(new org.joml.Quaternionf().setFromNormalized(lastPad),new org.joml.Quaternionf().setFromNormalized(pad)));
                    padTurn=Math.max(padTurn,turn);
                    check(turn<10,"held at tick "+t+" the pad of side "+side+" turns "+turn+" degrees in a tick");
                }
                lastPad=pad;
                fixtures++;
            }
        }
        renderState.whipWeight=0;renderState.whipArm=null;
        // Setting off, slowing and stopping mix the walk with the rest of the ground pose by the shortest turn, part by part:
        // a part's turn from the idle pose plus its turn on to the walk's is the whole turn between them. Added up as Euler
        // angles the long arms turned any which way at half a walk and flickered.
        renderState.isBeingRidden=false;renderState.swimAnimationAmount=renderState.swimMotionAmount=0;renderState.ageInTicks=0;
        double mixWorst=0;
        for(float phase:new float[]{3,11.5F,19,27.5F,34})for(float amount:new float[]{.25F,.5F,.75F}) {
            renderState.groundAnimationPhase=phase;
            var idle=turns(runtime,renderState,0);var walk=turns(runtime,renderState,1);var mixed=turns(runtime,renderState,amount);
            for(int i=0;i<mixed.size();i++){
                double off=between(idle.get(i),mixed.get(i))+between(mixed.get(i),walk.get(i))-between(idle.get(i),walk.get(i));
                mixWorst=Math.max(mixWorst,Math.toDegrees(off));
                check(Math.toDegrees(off)<1,"at "+amount+" of the walk, phase "+phase+", part "+i+" turns "+Math.toDegrees(off)+" degrees off the shortest turn");
            }
            fixtures++;
        }
        renderState.groundAnimationAmount=0;
        Constants.LOG.info("[gesomon-parity] PASS {} full vertex/solid fixtures, maximum error {} blocks; ground/water transition playback; seat {} blocks off at rest, {} afloat; whip drawn within {} blocks of where it strikes, held pad turns at most {} degrees a tick; walk mix within {} degrees of the shortest turn",
                fixtures,worst,String.format(Locale.ROOT,"%.3f",seated.length()),String.format(Locale.ROOT,"%.3f",drift),String.format(Locale.ROOT,"%.3f",whipWorst),
                String.format(Locale.ROOT,"%.1f",padTurn),String.format(Locale.ROOT,"%.2f",mixWorst));
    }

    /** The angle of the turn from one orientation to another, radians (the short way round). */
    static double between(org.joml.Quaternionf a, org.joml.Quaternionf b) {
        double w=Math.abs(a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w)/(Math.sqrt(a.lengthSquared()*b.lengthSquared()));
        return 2*Math.acos(Math.min(1,w));
    }

    /** Every part's local turn, in the model's part order, with this much of the walk. */
    static List<org.joml.Quaternionf> turns(NativeGroundModel model, com.digicube.fabric.client.render.DigimonRenderState state, float amount) {
        state.groundAnimationAmount=amount;model.setupAnim(state);
        var out=new ArrayList<org.joml.Quaternionf>();
        for(var p:model.root().getAllParts())out.add(new org.joml.Quaternionf().rotationZYX(p.zRot,p.yRot,p.xRot));
        return out;
    }

    /** The whip arm's pad's rotation in the model. */
    static org.joml.Matrix3f partTurn(ModelPart root, com.digicube.digimon.WhipAttacks.Arm arm) {
        var stack=new PoseStack();ModelPart part=root;part.translateAndRotate(stack);
        for(String name:arm.parentPath()){part=part.getChild(name);part.translateAndRotate(stack);}
        for(String name:arm.parts()){part=part.getChild(name);part.translateAndRotate(stack);}
        return new org.joml.Matrix3f(stack.last().normal());
    }

    /** The drawn whip arm, root to pad tip, in blocks with the feet at the origin and the body facing +z. */
    static List<Vec3> whipJoints(ModelPart root, com.digicube.digimon.WhipAttacks.Arm arm, float scale) {
        var stack=new PoseStack();ModelPart part=root;part.translateAndRotate(stack);
        for(String name:arm.parentPath()){part=part.getChild(name);part.translateAndRotate(stack);}
        var out=new ArrayList<Vec3>();
        for(String name:arm.parts()){
            part=part.getChild(name);part.translateAndRotate(stack);
            var p=stack.last().pose().transformPosition(0,0,0,new org.joml.Vector3f());
            out.add(new Vec3(p.x,1.5-p.y,-p.z).scale(scale));
        }
        var tip=stack.last().pose().transformPosition(0,-arm.pad()/scale,0,new org.joml.Vector3f());
        out.add(new Vec3(tip.x,1.5-tip.y,-tip.z).scale(scale));
        return out;
    }
}
