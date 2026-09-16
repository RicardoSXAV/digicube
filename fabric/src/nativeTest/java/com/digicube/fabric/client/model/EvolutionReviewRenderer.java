package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.EvolutionTimeline;
import com.digicube.fabric.client.evolution.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** CPU depth-buffered, nearest-texel review of the actual runtime evaluator, without Minecraft UI. */
public final class EvolutionReviewRenderer {
    private record Canvas(int width, int height, boolean stage) {}
    static void review(EvolutionSurface.Pair pair,Path out)throws Exception {
        Files.createDirectories(out);var template=EvolutionMesh.prepare(pair);
        var a=texture(pair.from().texture());var b=texture(pair.to().texture());
        long nanos=0;int samples=0;
        for(var preset:List.of(EvolutionTimeline.LONG,EvolutionTimeline.SHORT,EvolutionTimeline.RETURN)) {
            var active=preset.returning()?EvolutionMesh.prepare(EvolutionSurface.pair(pair.to(),pair.from(),3072)):template;
            String name=preset.returning()?"return":preset.duration()==32?"short":"long";
            Files.createDirectories(out.resolve(name));
            // Half-tick samples demonstrate client interpolation at authored real-time speed (40 fps).
            for(int sample=0;sample<=preset.duration()*2;sample++) {
                float tick=sample*.5F;
                long start=System.nanoTime();var frame=EvolutionMesh.frame(active,preset,tick,true);nanos+=System.nanoTime()-start;samples++;
                var image=render(frame,preset.returning()?b:a,preset.returning()?a:b,Math.max(pair.from().radius(),pair.to().radius()),-.65);
                var g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.drawString(out.getFileName()+" / "+name+" / "+tick+" ticks",10,18);g.dispose();
                ImageIO.write(image,"png",out.resolve(name).resolve(String.format("%04d.png",sample)).toFile());
                if(name.equals("short") && sample%2==0) {
                    Files.createDirectories(out.resolve("short_body"));
                    var body=render(new EvolutionMesh.Frame(frame.source(),frame.target(),frame.data(),List.of(),List.of()),a,b,Math.max(pair.from().radius(),pair.to().radius()),-.65,new Canvas(480,400,false));
                    var bg=body.createGraphics();bg.setColor(java.awt.Color.WHITE);bg.drawString(out.getFileName()+" / "+name+" / "+(int)tick+" ticks",10,18);bg.dispose();
                    ImageIO.write(body,"png",out.resolve("short_body").resolve(String.format("%04d.png",(int)tick)).toFile());
                }
            }
        }
        for(int i=0;i<6;i++)for(int t:new int[]{0,10,20,30,45,55,60,100,138,160,196,204,220})ImageIO.write(render(EvolutionMesh.frame(template,EvolutionTimeline.LONG,t,true),a,b,
                Math.max(pair.from().radius(),pair.to().radius()),i*Math.PI/3),"png",out.resolve("view_"+i+"_"+t+".png").toFile());
        Constants.LOG.info("[evolution-review] {} samples={} CPU_evaluator_mean_ms={} (no GPU cost measured)",out.getFileName(),samples,nanos/1e6/samples);
    }
    private static BufferedImage texture(net.minecraft.resources.Identifier id)throws Exception {
        try(var in=EvolutionReviewRenderer.class.getResourceAsStream("/assets/"+id.getNamespace()+"/"+id.getPath())) {return ImageIO.read(in);}
    }
    private static BufferedImage render(EvolutionMesh.Frame frame,BufferedImage source,BufferedImage target,float radius,double yaw) {
        return render(frame,source,target,radius,yaw,new Canvas(720,600,true));
    }
    private static BufferedImage render(EvolutionMesh.Frame frame,BufferedImage source,BufferedImage target,float radius,double yaw,Canvas canvas) {
        int W=canvas.width,H=canvas.height;
        var image=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);int[] pixels=new int[W*H];Arrays.fill(pixels,0x101b28);
        float[] depth=new float[W*H];Arrays.fill(depth,Float.POSITIVE_INFINITY);
        float scale=Math.min(W,H)*.65F/Math.max(1,radius)/(canvas.stage?1.28F:1);double pitch=canvas.stage?-.2:.2;
        if(canvas.stage)floor(pixels,depth,scale,radius,pitch,canvas);
        draw(frame.source(),source,pixels,depth,scale,yaw,pitch,false,canvas);draw(frame.target(),target,pixels,depth,scale,yaw,pitch,false,canvas);draw(frame.data(),null,pixels,depth,scale,yaw,pitch,false,canvas);
        draw(frame.particles(),null,pixels,depth,scale,yaw,pitch,true,canvas);
        draw(EvolutionDataLight.facing(frame.signal(),new org.joml.Quaternionf().rotationY((float)-yaw).rotateX((float)-pitch)),null,pixels,depth,scale,yaw,pitch,false,canvas,true);
        image.setRGB(0,0,W,H,pixels,0,W);return image;
    }
    /** Neutral review floor only; this is not a runtime effect or a claim of terrain/lighting integration. */
    private static void floor(int[] pixels,float[] depth,float scale,float radius,double pitch,Canvas canvas) {
        int W=canvas.width,H=canvas.height;
        for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
            float wx=(x-W*.5F)/scale,wz=(y-H*.78F)/(float)(Math.sin(pitch)*scale);
            float dist=(float)Math.sqrt(wx*wx+wz*wz)/Math.max(1,radius);
            if(dist>2.3F)continue;
            int level=((int)Math.floor(wx)+(int)Math.floor(wz))%2==0?28:32;
            float edge=1-EvolutionTimeline.smooth((dist-1.8F)/.5F);
            pixels[y*W+x]=((int)(16+(level-16)*edge)<<16)|((int)(27+(level+4-27)*edge)<<8)|(int)(40+(level+10-40)*edge);
            depth[y*W+x]=(float)(wz*Math.cos(pitch));
        }
    }
    private static void draw(List<EvolutionMesh.Face> faces,BufferedImage tex,int[] pixels,float[] depth,float scale,double yaw,double pitch,boolean particles,Canvas canvas) {
        draw(faces,tex,pixels,depth,scale,yaw,pitch,particles,canvas,false);
    }
    private static void draw(List<EvolutionMesh.Face> faces,BufferedImage tex,int[] pixels,float[] depth,float scale,double yaw,double pitch,boolean particles,Canvas canvas,boolean stream) {
        int W=canvas.width,H=canvas.height;
        double cy=Math.cos(yaw),sy=Math.sin(yaw),cp=Math.cos(pitch),sp=Math.sin(pitch);
        for(var face:faces) {
            float[][] p=new float[4][5];var v=face.vertices();
            for(int i=0;i<4;i++){int j=i*8;double x=v[j]*cy+v[j+2]*sy,z=-v[j]*sy+v[j+2]*cy;
                p[i]=new float[]{(float)(W*.5+x*scale),(float)(H*.78-(v[j+1]*cp-z*sp)*scale),(float)(z*cp+v[j+1]*sp),v[j+3],v[j+4]};}
            triangle(p[0],p[1],p[2],face.color(),tex,pixels,depth,particles,stream,canvas);triangle(p[0],p[2],p[3],face.color(),tex,pixels,depth,particles,stream,canvas);
        }
    }
    private static void triangle(float[] a,float[] b,float[] c,int color,BufferedImage tex,int[] pixels,float[] depth,boolean particles,boolean stream,Canvas canvas) {
        int W=canvas.width,H=canvas.height;
        float det=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]);if(Math.abs(det)<1e-7)return;
        float uDx=((b[1]-c[1])*(a[3]-c[3])+(c[1]-a[1])*(b[3]-c[3]))/det;
        float uDy=((c[0]-b[0])*(a[3]-c[3])+(a[0]-c[0])*(b[3]-c[3]))/det;
        float vDx=((b[1]-c[1])*(a[4]-c[4])+(c[1]-a[1])*(b[4]-c[4]))/det;
        float vDy=((c[0]-b[0])*(a[4]-c[4])+(a[0]-c[0])*(b[4]-c[4]))/det;
        int xmin=Math.clamp((int)Math.floor(Math.min(a[0],Math.min(b[0],c[0]))),0,W-1),xmax=Math.clamp((int)Math.ceil(Math.max(a[0],Math.max(b[0],c[0]))),0,W-1);
        int ymin=Math.clamp((int)Math.floor(Math.min(a[1],Math.min(b[1],c[1]))),0,H-1),ymax=Math.clamp((int)Math.ceil(Math.max(a[1],Math.max(b[1],c[1]))),0,H-1);
        for(int y=ymin;y<=ymax;y++)for(int x=xmin;x<=xmax;x++) {
            float u=((b[1]-c[1])*(x+.5F-c[0])+(c[0]-b[0])*(y+.5F-c[1]))/det;
            float v=((c[1]-a[1])*(x+.5F-c[0])+(a[0]-c[0])*(y+.5F-c[1]))/det,w=1-u-v;
            if(u<0||v<0||w<0)continue;float z=u*a[2]+v*b[2]+w*c[2];int at=y*W+x;if(z>depth[at])continue;
            int rgb=color;
            if(particles) {
                float gu=u*a[3]+v*b[3]+w*c[3],gv=u*a[4]+v*b[4]+w*c[4];boolean halo=gu>1.5F;gu-=halo?2:0;
                float strength=halo?(float)Math.pow(Math.max(0,1-Math.hypot(gu*2-1,gv*2-1)),3)
                        :.055F+(Math.min(Math.min(gu,1-gu),Math.min(gv,1-gv))<.09F?.7F:0);
                float alpha=(color>>>24)/255F*strength;rgb=0;
                for(int shift:new int[]{0,8,16})rgb|=Math.min(255,((pixels[at]>>shift)&255)+Math.round(((color>>shift)&255)*alpha))<<shift;
                pixels[at]=rgb;continue;
            }
            if(tex!=null){int tx=Math.clamp((int)((u*a[3]+v*b[3]+w*c[3])*tex.getWidth()),0,tex.getWidth()-1),ty=Math.clamp((int)((u*a[4]+v*b[4]+w*c[4])*tex.getHeight()),0,tex.getHeight()-1);rgb=tex.getRGB(tx,ty);if((rgb>>>24)<128)continue;}
            if(tex==null&&!stream){int alpha=color>>>24;double threshold=(x*.754877666+y*.569840296)%1;if(alpha<128&&threshold>=alpha/127.0||alpha>=128&&alpha<255&&threshold<(alpha-128)/126.0)continue;float gu=u*a[3]+v*b[3]+w*c[3],gv=u*a[4]+v*b[4]+w*c[4];float du=Math.abs(gu-Math.round(gu)),dv=Math.abs(gv-Math.round(gv));float line=Math.min(du,dv)<.065F?1:0;rgb=0xff000000;for(int shift:new int[]{0,8,16}){float channel=(color>>shift)&255;rgb|=(int)(line>0?channel*.15F+255*.85F:channel*.74F)<<shift;}}
            if(stream) {
                float gu=u*a[3]+v*b[3]+w*c[3],gv=u*a[4]+v*b[4]+w*c[4];
                float edge=Math.max(Math.abs(gu*2-1),Math.abs(gv*2-1));
                float distance=streamDistance(gu,gv);
                float aa=Math.max(Math.abs(streamDistance(gu+uDx,gv+vDx)-distance)
                        +Math.abs(streamDistance(gu+uDy,gv+vDy)-distance),.008F);
                float outline=1-smoothstep(.035F,.035F+aa,Math.abs(distance));
                float fill=1-smoothstep(-aa,aa,distance);
                float glow=(float)Math.exp(-Math.abs(distance)*6.5)*(1-smoothstep(.9F,1,edge));
                float alpha=Math.min(1,fill*.20F+outline*.96F+glow*.45F)*(color>>>24)/255F;rgb=0;
                for(int shift:new int[]{0,8,16}) {
                    float channel=(color>>shift)&255;channel+=(255-channel)*outline;
                    rgb|=Math.min(255,((pixels[at]>>shift)&255)+Math.round(channel*alpha))<<shift;
                }
                pixels[at]=rgb;continue;
            }
            pixels[at]=rgb;depth[at]=z;
        }
    }
    private static float streamDistance(float u,float v) {
        float x=(Math.abs(u*2-1)*2-.73F)*(1.3F/.45F),y=Math.abs(v*2-1)*2-.73F;
        return (float)Math.hypot(Math.max(x,0),Math.max(y,0))+Math.min(Math.max(x,y),0);
    }
    private static float smoothstep(float low,float high,float value) {
        float t=Math.clamp((value-low)/(high-low),0,1);return t*t*(3-2*t);
    }
}
