package com.digicube.fabric.client.evolution;

import com.digicube.digimon.EvolutionTimeline;
import org.joml.*;
import java.lang.Math;
import java.util.*;

/** Shared CPU evaluator used both by deferred Minecraft submissions and external review exports. */
public final class EvolutionMesh {
    private EvolutionMesh() {}
    public record Face(float[] vertices, int color) {}
    public record Frame(List<Face> source, List<Face> target, List<Face> data, List<Face> particles,List<Face> signal) {}
    public record Cell(EvolutionSurface.Tile source,EvolutionSurface.Tile target,float order) {}
    public record Template(EvolutionSurface.Pair pair,List<Cell> cells,EvolutionVolume.Field sourceField,EvolutionVolume.Field targetField,float height,float sourceHeight) {}

    public static Template prepare(EvolutionSurface.Pair pair) {
        var cells=new ArrayList<Cell>();int i=0;
        for(var a:pair.source()) {
            var b=pair.target().get(i++);var ac=a.center();
            // A height sweep with broad, deterministic patches preserves recognizable skin islands.
            float order=Math.clamp(.5F-ac.y/(2*Math.max(.2F,pair.from().radius()))+.12F*(float)Math.sin(ac.x*4+ac.z*3),.02F,.98F);
            cells.add(new Cell(a,b,order));
        }
        float height=.5F;
        for(var surface:List.of(pair.from(),pair.to()))for(var tile:surface.faces())for(var point:tile.points())height=Math.max(height,point.y());
        float sourceHeight=.15F;for(var tile:pair.from().faces())for(var point:tile.points())sourceHeight=Math.max(sourceHeight,point.y());
        return new Template(pair,List.copyOf(cells),EvolutionVolume.field(pair.from()),EvolutionVolume.field(pair.to()),height,sourceHeight);
    }
    public static Frame frame(Template template,EvolutionTimeline time,float tick,boolean decoration) {
        return frame(template,time,tick,decoration,null,null);
    }
    public static Frame frame(Template template,EvolutionTimeline time,float tick,boolean decoration,EvolutionSurface.Pose sourcePose,EvolutionSurface.Pose targetPose) {
        return frame(template,time,tick,decoration,sourcePose,targetPose,decoration?1:0,0);
    }
    public static Frame frame(Template template,EvolutionTimeline time,float tick,boolean decoration,EvolutionSurface.Pose sourcePose,EvolutionSurface.Pose targetPose,float particleQuality,long seed) {
        return frame(template,time,tick,decoration,sourcePose,targetPose,particleQuality,seed,32);
    }
    public static Frame frame(Template template,EvolutionTimeline time,float tick,boolean decoration,EvolutionSurface.Pose sourcePose,EvolutionSurface.Pose targetPose,float particleQuality,long seed,float skyHeight) {
        var source=new ArrayList<Face>();var target=new ArrayList<Face>();var data=new ArrayList<Face>();
        float shed=time.shed(tick),morph=time.morph(tick),skin=time.skin(tick);
        float radius=Math.max(template.pair.from().radius(),template.pair.to().radius());
        float lift=(time.duration()==32?.035F:0)*Math.min(radius,4)*EvolutionTimeline.smooth(tick/Math.max(1,time.shedEnd()))
                *(1-EvolutionTimeline.smooth((tick-time.morphEnd())/(time.skinEnd()-time.morphEnd())));
        int color=mix(template.pair.from().color(),template.pair.to().color(),morph*.7F);
        color=saturate(color);
        if(tick>time.morphStart() && tick<time.morphEnd()) {
            float bridge=Math.max(1,(time.morphEnd()-time.morphStart())*.15F);
            float entrance=EvolutionTimeline.smooth((tick-time.morphStart())/bridge),exit=EvolutionTimeline.smooth((time.morphEnd()-tick)/bridge);
            float volumeWeight=Math.min(entrance,exit);
            for(var face:EvolutionVolume.frame(template.sourceField,template.targetField,morph,color,lift,decoration))data.add(coverage(face,volumeWeight,false));
            if(volumeWeight<1)for(var cell:template.cells) {
                var tile=entrance<exit?cell.source:cell.target;var grid=face(tile,0,lift,0,1,color);EvolutionPalette.grid(grid.vertices,color);
                data.add(coverage(grid,volumeWeight,true));
            }
            return finish(source,target,data,template,time,tick,decoration,particleQuality,seed,color,skyHeight);
        }
        int index=0;
        for(var cell:template.cells) {
            int id=index++;
            if(shed<cell.order && tick<time.morphStart()) {
                source.add(face(sourcePose==null?cell.source:sourcePose.apply(cell.source),0,lift,0,1,-1));continue;
            }
            float arrivalOrder=Math.clamp(cell.order*.8F+.1F,.02F,.98F);
            if(skin>=arrivalOrder) {target.add(face(targetPose==null?cell.target:targetPose.apply(cell.target),0,lift,0,1,-1));continue;}
            if(decoration && id%Math.max(1,(template.cells.size()+127)/128)==0 && !time.returning()) {
                float age=(shed-cell.order)*2.5F;
                if(age>=0 && age<1 && tick<time.morphStart()) {
                    var n=cell.source.normal();float travel=age*Math.min(radius,.9F);
                    source.add(face(cell.source,n.x*travel,lift+n.y*travel,n.z*travel,1-age,-1));
                }
            }
            // The connected volume owns the entire moving interval. Endpoint grids use exact installed surfaces.
            var tile=morph<.5F?cell.source:cell.target;
            var grid=face(tile,0,lift,0,1,color);EvolutionPalette.grid(grid.vertices,color);data.add(grid);
        }
        return finish(source,target,data,template,time,tick,decoration,particleQuality,seed,color,skyHeight);
    }
    private static Frame finish(List<Face> source,List<Face> target,List<Face> data,Template template,EvolutionTimeline time,float tick,boolean decoration,float particleQuality,long seed,int color,float skyHeight) {
        float radius=Math.max(template.pair.from().radius(),template.pair.to().radius());
        if(time.longForm()) {
            var motion=EvolutionChoreography.motion(time.bodyTick(tick),radius);
            EvolutionChoreography.transform(source,motion);EvolutionChoreography.transform(target,motion);EvolutionChoreography.transform(data,motion);
        } else if(decoration)rings(data,time,tick,radius,color);
        return new Frame(List.copyOf(source),List.copyOf(target),List.copyOf(data),
                EvolutionChoreography.particles(time,tick,radius,template.height,color,particleQuality,seed),
                EvolutionDataLight.frame(time,tick,skyHeight,template.sourceHeight,template.pair.from().radius()));
    }
    /** Complementary screen-space coverage hides the topology remesh seam without a flash or empty frame. */
    private static Face coverage(Face face,float weight,boolean inverse) {
        if(!inverse&&weight>=1)return face;
        int alpha=inverse?128+Math.round(weight*126):Math.round(weight*127);
        return new Face(face.vertices,(face.color&0xffffff)|(alpha<<24));
    }
    private static Face face(EvolutionSurface.Tile tile,float x,float y,float z,float size,int color) {
        var center=tile.center();var n=tile.normal();float[] result=new float[32];int i=0;
        for(var p:tile.points()) {
            result[i++]=center.x+(p.x()-center.x)*size+x;result[i++]=center.y+(p.y()-center.y)*size+y;result[i++]=center.z+(p.z()-center.z)*size+z;
            result[i++]=color==-1?p.u():EvolutionPalette.WHITE_U;result[i++]=color==-1?p.v():EvolutionPalette.WHITE_V;
            result[i++]=n.x;result[i++]=n.y;result[i++]=n.z;
        }
        return new Face(result,color);
    }
    private static void rings(List<Face> out,EvolutionTimeline time,float tick,float radius,int color) {
        float progress=Math.clamp(tick/time.duration(),0,1),envelope=(float)Math.sin(Math.PI*progress);
        int count=time.returning()?1:time.duration()==32?1:2;
        if(envelope<.001F)return;
        float r=Math.min(radius,5)*(time.returning()?.85F-progress*.35F:.7F+progress*.12F),thickness=.025F*envelope;
        for(int ring=0;ring<count;ring++)for(int i=0;i<40;i++) {
            if(i%5==4)continue;
            double a=i*Math.PI*2/40+progress*(time.returning()?-1:1)+ring*.5,b=a+.11;
            float h=.05F+ring*radius*.28F+envelope*ring*.12F;
            var p=new EvolutionSurface.Point[4];
            for(int v=0;v<4;v++){double angle=(v==0||v==3)?a:b;float rr=r+((v<2)?thickness:-thickness);p[v]=new EvolutionSurface.Point((float)Math.cos(angle)*rr,h,(float)Math.sin(angle)*rr,0,0);}
            out.add(face(new EvolutionSurface.Tile(p[0],p[1],p[2],p[3],color),0,0,0,1,mix(color,0xffffffff,.5F)));
        }
    }
    private static int saturate(int c) {
        float[] h=java.awt.Color.RGBtoHSB((c>>16)&255,(c>>8)&255,c&255,null);
        return java.awt.Color.HSBtoRGB(h[0],Math.max(.6F,h[1]),Math.max(.65F,h[2]));
    }
    private static int mix(int a,int b,float t) {
        int color=0xff000000;for(int shift:new int[]{0,8,16})color|=((int)(((a>>shift)&255)*(1-t)+((b>>shift)&255)*t))<<shift;return color;
    }
}
