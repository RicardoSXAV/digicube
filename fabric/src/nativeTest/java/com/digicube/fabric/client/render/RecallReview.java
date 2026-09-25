package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.fabric.client.digivice.RecallMotion;
import com.digicube.fabric.client.digivice.RecallFlight;
import com.digicube.fabric.client.digivice.RecallFx;
import com.digicube.digivice.RecallJourney;
import net.minecraft.world.phys.Vec3;
import com.digicube.fabric.client.evolution.EvolutionMesh;
import com.digicube.fabric.client.model.EvolutionReviewRenderer;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.resources.model.cuboid.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import javax.imageio.ImageIO;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;

/** Shared runtime timing/geometry plus the actual approved Minecraft cuboid model on the evolution stage. */
public final class RecallReview {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var chip=ImageIO.read(Objects.requireNonNull(RecallReview.class.getResourceAsStream("/assets/digicube/textures/item/recall_chip.png")));
        if(chip.getWidth()!=16 || chip.getHeight()!=16)throw new AssertionError("native item pixel dimensions");
        for(var pixel:RecallMotion.PIXELS) if((chip.getRGB(pixel.x(),pixel.y())&0xffffff)!=pixel.rgb())throw new AssertionError("dissolving pixel differs from sprite");
        RecallFlightChecks.run();
        if(args.length==0)return;
        Path out=Path.of(args[0]);Files.createDirectories(out.resolve("frames"));
        var model=new ArrayList<EvolutionMesh.Face>();
        try(var reader=new InputStreamReader(Objects.requireNonNull(RecallReview.class.getResourceAsStream("/assets/digicube/models/item/digivice.json")))) {
            var geometry=(UnbakedCuboidGeometry)CuboidModel.fromStream(reader).geometry();
            for(var e:geometry.elements())for(var entry:e.faces().entrySet()) {
                float[] v=new float[32];var face=entry.getValue();
                for(int i=0;i<4;i++) {
                    Vector3f p=FaceInfo.fromFacing(entry.getKey()).getVertexInfo(i).select(e.from(),e.to()).div(16);
                    if(e.rotation()!=null) {p.sub(e.rotation().origin());e.rotation().transform().transformPosition(p);p.add(e.rotation().origin());}
                    p.sub(.5F,.5F,.5F);v[i*8]=p.x;v[i*8+1]=p.y;v[i*8+2]=p.z;
                    v[i*8+3]=CuboidFace.getU(face.uvs(),face.rotation(),i);v[i*8+4]=CuboidFace.getV(face.uvs(),face.rotation(),i);
                }
                model.add(new EvolutionMesh.Face(v,0xffffffff));
            }
        }
        var texture=ImageIO.read(Objects.requireNonNull(RecallReview.class.getResourceAsStream("/assets/digicube/textures/item/digivice_3d.png")));
        var skin=ImageIO.read(Objects.requireNonNull(RecallReview.class.getResourceAsStream("/assets/minecraft/textures/entity/player/wide/steve.png")));
        var armModel=net.minecraft.client.model.geom.builders.LayerDefinition.create(
                net.minecraft.client.model.player.PlayerModel.createMesh(net.minecraft.client.model.geom.builders.CubeDeformation.NONE,false),64,64).bakeRoot().getChild("right_arm");
        armModel.zRot=.1F;
        var armPose=new com.mojang.blaze3d.vertex.PoseStack();DigiviceGrip.apply(armPose,net.minecraft.world.entity.HumanoidArm.RIGHT,false);
        var arm=new ArrayList<EvolutionMesh.Face>();
        armModel.visit(armPose,(matrix,path,index,cube) -> {
            for(var polygon:cube.polygons) {
                float[] v=new float[32];int k=0;
                for(var vertex:polygon.vertices()) {
                    var p=matrix.pose().transformPosition(vertex.worldX(),vertex.worldY(),vertex.worldZ(),new Vector3f());
                    v[k]=p.x;v[k+1]=p.y;v[k+2]=p.z;v[k+3]=vertex.u();v[k+4]=vertex.v();k+=8;
                }
                arm.add(new EvolutionMesh.Face(v,0xffffffff));
            }
        });
        renderJourneys(out,model,texture,arm,skin);
        renderOutside(out,model,texture,skin);
        renderRoutes(out);
    }
    /**
     * The recall watched from outside (another player, or the caller's third person): a camera beside the caller, the
     * player model with its arm held out as the item pose holds it, the device flying into that hand.
     */
    private static void renderOutside(Path out,List<EvolutionMesh.Face> model,java.awt.image.BufferedImage texture,
                                      java.awt.image.BufferedImage skin) throws Exception {
        var root=net.minecraft.client.model.geom.builders.LayerDefinition.create(
                net.minecraft.client.model.player.PlayerModel.createMesh(net.minecraft.client.model.geom.builders.CubeDeformation.NONE,false),64,64).bakeRoot();
        root.getChild("right_arm").xRot=-(float)Math.PI/10;
        Vec3 feet=Vec3.ZERO,eye=new Vec3(3.2,1.6,3.6);
        float yaw=15;
        var camera=new Quaternionf().rotationY((float)Math.atan2(eye.x,eye.z));
        var toCamera=new Matrix4f().rotation(new Quaternionf(camera).conjugate()).translate((float)-eye.x,(float)-eye.y,(float)-eye.z);
        var bodyPose=new com.mojang.blaze3d.vertex.PoseStack();
        bodyPose.last().pose().mul(new Matrix4f().rotateY((float)Math.toRadians(180-yaw)).scale(-.9375F,-.9375F,.9375F).translate(0,-1.501F,0));
        var body=new ArrayList<EvolutionMesh.Face>();
        root.visit(bodyPose,(matrix,path,index,cube) -> {
            for(var polygon:cube.polygons) {
                float[] v=new float[32];int k=0;
                for(var vertex:polygon.vertices()) {
                    var p=matrix.pose().transformPosition(vertex.worldX(),vertex.worldY(),vertex.worldZ(),new Vector3f());
                    v[k]=p.x;v[k+1]=p.y;v[k+2]=p.z;v[k+3]=vertex.u();v[k+4]=vertex.v();k+=8;
                }
                body.add(new EvolutionMesh.Face(v,0xffffffff));
            }
        });
        var bodyInCamera=transform(body,toCamera);
        for(boolean near:new boolean[]{true,false}) {
            Vec3 source=near?new Vec3(-3,.15,-6):new Vec3(-60000,.15,-80000);
            var journey=RecallJourney.plan(source.distanceTo(feet),true,192);
            var view=RecallFlight.View.body(feet.add(0,1.62,0),yaw,0,1);
            var held=RecallFlight.thirdPersonHeld(feet,yaw,1);
            var plan=RecallFlight.plan(journey,(x,y,z)->y<0,source,new Quaternionf().rotationX((float)-Math.PI/2),source,true,view,held);
            Path frames=out.resolve(near?"outside_near":"outside_far");Files.createDirectories(frames);
            int count=(int)Math.ceil((journey.duration()+1.15)*40);
            for(int i=0;i<count;i++) {
                float seconds=i/40F,t=Math.clamp(seconds-.45F,0,journey.duration()),since=t-journey.arriveAt();
                var frame=RecallFlight.sample(plan,view,held,t);
                boolean visible=t<journey.arriveAt() && frame.pose().scale()>.001F && (journey.nearby() || t>=journey.flightAt());
                var world=new ArrayList<EvolutionReviewRenderer.PropLayer>();
                world.add(new EvolutionReviewRenderer.PropLayer(bodyInCamera,skin));
                if(since>=0)world.add(new EvolutionReviewRenderer.PropLayer(transform(model,new Matrix4f(toCamera).mul(held.matrix(Vec3.ZERO))),texture));
                else if(visible)world.add(new EvolutionReviewRenderer.PropLayer(transform(model,new Matrix4f(toCamera).mul(frame.pose().matrix(Vec3.ZERO))),texture));
                var light=transform(RecallFx.faces(RecallFx.world(plan,view,held,t,eye,camera)),new Matrix4f().rotation(new Quaternionf(camera).conjugate()));
                var hand=held.position().subtract(eye);
                var stage=new Matrix4f(toCamera).translate((float)held.position().x,(float)held.position().y,(float)held.position().z).rotate(camera).scale(.8F);
                var chip=RecallMotion.frame(t);
                var chipGlow=new ArrayList<EvolutionMesh.Face>(transform(chip.glow(),stage));
                chipGlow.addAll(transform(RecallFx.faces(RecallFx.burst(since)),stage));
                var image=EvolutionReviewRenderer.renderFirstPersonProps(world,light,List.of(),transform(chip.chip(),stage),chipGlow,70,70);
                var g=image.createGraphics();g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,17));g.setColor(new java.awt.Color(220,231,237));
                g.drawString("WATCHED FROM OUTSIDE / "+(near?"NEARBY / 7 BLOCKS":"DISTANT / 100,000 BLOCKS")+String.format(java.util.Locale.ROOT," / hand %.1f blocks away",hand.length()),20,28);
                g.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,14));
                g.drawString(seconds<.45F?"Right-click to recall":t<journey.flightAt()?"Chip breaks into data in the hand":t<journey.arriveAt()?"Flight into the held-out hand":"Caught",20,54);
                g.dispose();
                ImageIO.write(image,"png",frames.resolve(String.format("%04d.png",i)).toFile());
            }
        }
    }
    private static void renderJourneys(Path out,List<EvolutionMesh.Face> model,java.awt.image.BufferedImage texture,
                                       List<EvolutionMesh.Face> arm,java.awt.image.BufferedImage skin) throws Exception {
        float worldFov=80,handFov=70;
        float ratio=(float)(Math.tan(Math.toRadians(handFov)/2)/Math.tan(Math.toRadians(worldFov)/2));
        for(int scenario=0;scenario<4;scenario++) {
            boolean near=scenario<2;
            int direction=scenario%2==0?1:-1;
            Vec3 receiver=new Vec3(0,1.6,0);
            Vec3 source=new Vec3(direction*(near?3:60000),.15,near?-4:-80000);
            var plan0=RecallJourney.plan(source.distanceTo(new Vec3(0,0,0)),true,192);
            // The review camera looks down -Z with no bob or sway, so the hand pass is plain view space.
            var view=new RecallFlight.View(receiver,new Quaternionf(),new Matrix4f(),ratio,new Quaternionf(),1);
            var rest=new Quaternionf().rotationX((float)-Math.PI/2);
            var plan=RecallFlight.plan(plan0,(x,y,z)->y<0,source,rest,source,true,view);
            var held=view.held();
            String name=(near?"near_":"far_")+(direction==1?"right":"left");
            Path frames=out.resolve(name);Files.createDirectories(frames);
            int count=(int)Math.ceil((plan0.duration()+1.15)*40);
            boolean inHand=false;
            for(int i=0;i<count;i++) {
                float seconds=i/40F,t=Math.clamp(seconds-.45F,0,plan0.duration());
                var frame=RecallFlight.sample(plan,view,held,t);
                float since=t-plan0.arriveAt();
                float swing=since>0?(float)(Math.sin(17.5F*since)*Math.exp(-since/.09F))*.03F:0;
                var hand=new Matrix4f().translation(.56F,-.52F-swing,-.72F+swing);
                boolean visible=t<plan0.arriveAt() && frame.pose().scale()>.001F && (plan0.nearby() || t>=plan0.flightAt());
                if(visible && t>=plan0.flightAt() && (frame.progress()>.92F || RecallFlight.displayed(frame,view).distanceTo(receiver)<1.5))inHand=true;
                var liftedHand=new Matrix4f(hand).translate(-.05F*RecallMotion.gripLift(t),.04F*RecallMotion.gripLift(t),0);
                var pixelStage=new Matrix4f(hand).translate(-1.5F/16,4F/16,-2F/16);
                var chip=RecallMotion.frame(t);
                var chipGlow=new ArrayList<EvolutionMesh.Face>(transform(chip.glow(),pixelStage));
                chipGlow.addAll(transform(RecallFx.faces(RecallFx.burst(since)),pixelStage));
                var held2=new ArrayList<EvolutionReviewRenderer.PropLayer>();
                held2.add(new EvolutionReviewRenderer.PropLayer(transform(arm,liftedHand),skin));
                var world=new ArrayList<EvolutionReviewRenderer.PropLayer>();
                if(since>=0)held2.add(new EvolutionReviewRenderer.PropLayer(transform(model,new Matrix4f(hand).mul(RecallFlight.heldDisplay(1))),texture));
                else if(visible && inHand)held2.add(new EvolutionReviewRenderer.PropLayer(transform(model,RecallFlight.handMatrix(frame,view)),texture));
                else if(visible)world.add(new EvolutionReviewRenderer.PropLayer(transform(model,RecallFlight.worldMatrix(frame,view)),texture));
                var image=EvolutionReviewRenderer.renderFirstPersonProps(world,RecallFx.faces(RecallFx.world(plan,view,held,t)),held2,
                        transform(chip.chip(),pixelStage),chipGlow,worldFov,handFov);
                var g=image.createGraphics();g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,17));g.setColor(new java.awt.Color(220,231,237));
                g.drawString((near?"NEARBY / 5 BLOCKS":"DISTANT / 100,000 BLOCKS")+" / "+(direction==1?"RIGHT":"LEFT"),20,28);
                g.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,14));
                String phase=seconds<.45F?"Right-click to recall":t<plan0.departure()?(near?"Chip breaks into data; the device wakes":String.format(java.util.Locale.ROOT,"Calling the distant Digivice... %.1f / 4.0 seconds",t)):
                        t<plan0.flightAt()?"The device spins up off the ground":t<plan0.arriveAt()?(inHand?"Into the hand pass":"Flight from the source direction"):"Caught: the hand gives and settles";
                g.drawString(phase,20,54);g.setColor(new java.awt.Color(141,164,181));g.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,12));
                g.drawString("Evolution preview renderer / actual model + runtime trajectory / approximate lighting",20,578);g.dispose();
                ImageIO.write(image,"png",frames.resolve(String.format("%04d.png",i)).toFile());
            }
        }
        Files.writeString(out.resolve("visual_checks.txt"),"PASS: see [recall-visual] in the log. Review is external, not a Minecraft screenshot.\n");
    }
    /** Top and side views of each obstacle route, each framed to fit it: blocks grey, route gold, device red, eye blue. */
    private static void renderRoutes(Path out) throws Exception {
        var cases=RecallFlightChecks.terrains();
        int w=21*16,h=30*16;
        var image=new java.awt.image.BufferedImage(w*2+30,(h+40)*cases.size(),java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x101b28));g.fillRect(0,0,image.getWidth(),image.getHeight());
        for(int n=0;n<cases.size();n++) {
            var c=cases.get(n);var plan=c.plan();var path=plan.path();var blocks=c.blocks();
            int top=n*(h+40)+30;
            g.setColor(new java.awt.Color(220,231,237));g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,15));
            g.drawString(c.name()+(path.searched?" (searched)":"")+"   top view (player at the top)        side view (player at the left)",10,top-10);
            // The frame: everything the route, the device and the eye touch, plus a margin.
            double x0=-10,x1=10,y0=-4,y1=12,z0=-18,z1=2;
            for(int i=0;i<=path.samples();i++){var p=i==path.samples()?c.source():path.sample(i);
                x0=Math.min(x0,p.x-3);x1=Math.max(x1,p.x+3);y0=Math.min(y0,p.y-3);y1=Math.max(y1,p.y+3);z0=Math.min(z0,p.z-3);z1=Math.max(z1,p.z+3);}
            int cell=(int)Math.max(2,Math.min(16,Math.min(Math.min(w/(x1-x0),h/(z1-z0)),Math.min(w/(z1-z0),h/(y1-y0)))));
            int nx=(int)Math.ceil(x1-x0),ny=(int)Math.ceil(y1-y0),nz=(int)Math.ceil(z1-z0);
            for(int view=0;view<2;view++) {
                int left=view*(w+30);
                g.setColor(new java.awt.Color(0x17283a));g.fillRect(left,top,w,h);
                if(view==0)for(int a=0;a<nx;a++)for(int b=0;b<nz;b++){
                    int x=(int)Math.floor(x0)+a,z=(int)Math.ceil(z1)-1-b;boolean solid=false;
                    for(int y=Math.max(c.ground(),(int)Math.floor(y0));y<=(int)Math.ceil(y1)&&!solid;y++)solid=blocks.blocked(x,y,z);
                    if(solid&&(a+1)*cell<=w&&(b+1)*cell<=h){g.setColor(new java.awt.Color(0x55606b));g.fillRect(left+a*cell,top+b*cell,cell-1,cell-1);}
                }
                else for(int a=0;a<nz;a++)for(int b=0;b<ny;b++){
                    int z=(int)Math.ceil(z1)-1-a,y=(int)Math.ceil(y1)-1-b;boolean solid=false;
                    for(int x=(int)Math.floor(x0);x<=(int)Math.ceil(x1)&&!solid;x++)solid=blocks.blocked(x,y,z);
                    if(solid&&(a+1)*cell<=w&&(b+1)*cell<=h){g.setColor(new java.awt.Color(0x55606b));g.fillRect(left+a*cell,top+b*cell,cell-1,cell-1);}
                }
                double fx0=Math.floor(x0),fz1=Math.ceil(z1),fy1=Math.ceil(y1);int facing=view;
                java.util.function.Function<Vec3,int[]> at=p->facing==0?new int[]{left+(int)((p.x-fx0)*cell),top+(int)((fz1-p.z)*cell)}
                        :new int[]{left+(int)((fz1-p.z)*cell),top+(int)((fy1-p.y)*cell)};
                g.setColor(new java.awt.Color(0xffcc46));g.setStroke(new java.awt.BasicStroke(2.2F));
                int[] last=null;
                for(int i=0;i<path.samples();i+=2) {
                    int[] q=at.apply(path.sample(i));
                    if(last!=null)g.drawLine(last[0],last[1],q[0],q[1]);
                    last=q;
                }
                for(var mark:new Object[][]{{c.source(),new java.awt.Color(0xff5050)},{RecallFlightChecks.TERRAIN_EYE,new java.awt.Color(0x5aa0ff)}}) {
                    int[] q=at.apply((Vec3)mark[0]);g.setColor((java.awt.Color)mark[1]);
                    g.fillOval(q[0]-5,q[1]-5,10,10);
                }
            }
        }
        g.dispose();
        ImageIO.write(image,"png",out.resolve("routes.png").toFile());
    }
    private static List<EvolutionMesh.Face> transform(List<EvolutionMesh.Face> faces,Matrix4f matrix) {
        var result=new ArrayList<EvolutionMesh.Face>();
        for(var f:faces) {float[] v=f.vertices().clone();for(int i=0;i<v.length;i+=8) {
            var p=matrix.transformPosition(new Vector3f(v[i],v[i+1],v[i+2]));v[i]=p.x;v[i+1]=p.y;v[i+2]=p.z;
        }result.add(new EvolutionMesh.Face(v,f.color()));}return result;
    }
}
