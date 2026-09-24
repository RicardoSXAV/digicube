package com.digicube.fabric.client.render;

import com.digicube.fabric.client.digivice.DigiviceBeacon;
import com.digicube.fabric.client.evolution.EvolutionMesh;
import com.digicube.fabric.client.model.EvolutionReviewRenderer;
import com.google.gson.*;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.resources.model.cuboid.*;
import org.joml.Vector3f;
import java.util.*;
import java.nio.file.*;
import java.io.InputStreamReader;
import javax.imageio.ImageIO;

/** Uses the real 26.2 cuboid parser/face UV mapping, approved atlas and shared beacon evaluator. */
public final class DigiviceDropReview {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var model = new ArrayList<EvolutionMesh.Face>();
        try(var reader = new InputStreamReader(Objects.requireNonNull(DigiviceDropReview.class.getResourceAsStream("/assets/digicube/models/item/digivice.json")))) {
            var geometry = (UnbakedCuboidGeometry)CuboidModel.fromStream(reader).geometry();
            for(var e : geometry.elements()) for(var entry : e.faces().entrySet()) {
                float[] v=new float[32];var face=entry.getValue();
                for(int i=0;i<4;i++) {
                    Vector3f p=FaceInfo.fromFacing(entry.getKey()).getVertexInfo(i).select(e.from(),e.to()).div(16);
                    if(e.rotation()!=null) {
                        p.sub(e.rotation().origin()); e.rotation().transform().transformPosition(p); p.add(e.rotation().origin());
                    }
                    p.sub(.5F,.5F,.5F);
                    v[i*8]=p.x;v[i*8+1]=p.y;v[i*8+2]=p.z;
                    v[i*8+3]=CuboidFace.getU(face.uvs(),face.rotation(),i);v[i*8+4]=CuboidFace.getV(face.uvs(),face.rotation(),i);
                }
                model.add(new EvolutionMesh.Face(v,0xffffffff));
            }
        }
        if(model.size()!=722)throw new AssertionError("Approved model face count changed");
        Path out=Path.of(args[0]);Files.createDirectories(out.resolve("frames"));
        var texture=ImageIO.read(Objects.requireNonNull(DigiviceDropReview.class.getResourceAsStream("/assets/digicube/textures/item/digivice_3d.png")));
        var trajectory=JsonParser.parseString(Files.readString(out.resolve("trajectory.json"))).getAsJsonArray();
        long nanos=0;int maxFaces=0;
        for(int i=0;i<240;i++) {
            float seconds=i/40F,tick=seconds*20;
            int sample=Math.min((int)tick,trajectory.size()-1), next=Math.min(sample+1,trajectory.size()-1);
            var a=trajectory.get(sample).getAsJsonArray();var b=trajectory.get(next).getAsJsonArray();float f=tick-(int)tick;
            float y=a.get(1).getAsFloat()*(1-f)+b.get(1).getAsFloat()*f;
            float pitch=a.get(2).getAsFloat()*(1-f)+b.get(2).getAsFloat()*f;
            float strength=a.get(3).getAsFloat()*(1-f)+b.get(3).getAsFloat()*f;
            var posed=pose(model,y,pitch);
            long start=System.nanoTime();var beam=DigiviceBeacon.frame(seconds,3);nanos+=System.nanoTime()-start;maxFaces=Math.max(maxFaces,beam.size());
            // Use server-recorded delay/fade; no beam while the device is falling.
            var lifted=new ArrayList<EvolutionMesh.Face>();for(var face:beam) {var v=face.vertices().clone();for(int k=1;k<v.length;k+=8)v[k]+=y;lifted.add(new EvolutionMesh.Face(v,(face.color()&0xffffff)|(Math.round((face.color()>>>24)*strength)<<24)));}
            float zoom=Math.clamp((seconds-1.8F)/1.7F,0,1);zoom=zoom*zoom*(3-2*zoom);
            var image=EvolutionReviewRenderer.renderProp(posed,texture,lifted,1.9F-.9F*zoom,Math.PI-.5,-.65,false);
            var g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.drawString("DIGIVICE / drop + gold locator / "+String.format(java.util.Locale.ROOT,"%.2fs",seconds),18,24);g.dispose();
            ImageIO.write(image,"png",out.resolve("frames").resolve(String.format("%04d.png",i)).toFile());
        }
        var settled=EvolutionReviewRenderer.renderProp(pose(model,0,-90),texture,DigiviceBeacon.frame(4,3),.8F,Math.PI-.5,-.8,false);
        ImageIO.write(settled,"png",out.resolve("night.png").toFile());
        for(float distance:new float[]{128,384}) {
            var image=EvolutionReviewRenderer.renderProp(pose(model,0,-90),texture,DigiviceBeacon.frame(4,distance),110,-.5,-.1,false);
            ImageIO.write(image,"png",out.resolve("beam_"+(int)distance+".png").toFile());
        }
        for(float distance:new float[]{0,24,48,128,448,512}) for(int i=0;i<80;i++) {
            var faces=DigiviceBeacon.frame(i*.07F,distance);
            if(faces.size()>28)throw new AssertionError("Beacon budget");
            for(var face:faces)for(float v:face.vertices())if(!Float.isFinite(v))throw new AssertionError("Nonfinite geometry");
            if(distance==512 && faces.stream().anyMatch(face -> face.color()>>>24 != 0))throw new AssertionError("Distance fade");
        }
        String report="PASS: 722 approved model faces; beacon max="+maxFaces+" quads / "+(maxFaces*4)+" vertices; CPU generation mean="+(nanos/240/1e6)+" ms. GPU cost and Minecraft appearance not measured.";
        Files.writeString(out.resolve("visual_checks.txt"),report);System.out.println(report);
    }
    private static List<EvolutionMesh.Face> pose(List<EvolutionMesh.Face> model,float y,float pitch) {
        var posed=new ArrayList<EvolutionMesh.Face>();float angle=(float)Math.toRadians(pitch),low=Float.POSITIVE_INFINITY;
        for(var face:model) {var v=face.vertices().clone();for(int i=0;i<v.length;i+=8){var p=new Vector3f(v[i],v[i+1],v[i+2]).rotateX(angle).mul(.55F);v[i]=p.x;v[i+1]=p.y;v[i+2]=p.z;low=Math.min(low,p.y);}posed.add(new EvolutionMesh.Face(v,face.color()));}
        for(var face:posed)for(int i=1;i<face.vertices().length;i+=8)face.vertices()[i]+=y+.002F-low;
        return posed;
    }
}
