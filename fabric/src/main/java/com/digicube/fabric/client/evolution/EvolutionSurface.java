package com.digicube.fabric.client.evolution;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;

/** Immutable world-sized surfaces from the same posed polygons used by the ordinary renderer. */
public final class EvolutionSurface {
    private EvolutionSurface() {}

    public record Point(float x, float y, float z, float u, float v) {
        Point mix(Point b, float t) { return new Point(x+(b.x-x)*t,y+(b.y-y)*t,z+(b.z-z)*t,u+(b.u-u)*t,v+(b.v-v)*t); }
        public Vector3f vector() { return new Vector3f(x,y,z); }
    }
    public record Tile(Point a, Point b, Point c, Point d, int color,int faceIndex) {
        public Tile(Point a,Point b,Point c,Point d,int color){this(a,b,c,d,color,-1);}
        public Point[] points() { return new Point[]{a,b,c,d}; }
        public Vector3f center() { return a.vector().add(b.vector()).add(c.vector()).add(d.vector()).mul(.25F); }
        public Vector3f normal() { return b.vector().sub(a.vector()).cross(d.vector().sub(a.vector())).normalize(); }
        public float area() { return b.vector().sub(a.vector()).cross(d.vector().sub(a.vector())).length(); }
        public float edge() { return Math.max(a.vector().distanceSquared(b.vector()),a.vector().distanceSquared(d.vector())); }
        Tile[] split() {
            if(a.vector().distanceSquared(b.vector())>=a.vector().distanceSquared(d.vector())) {
                var e=a.mix(b,.5F);var f=d.mix(c,.5F);
                return new Tile[]{new Tile(a,e,f,d,color,faceIndex),new Tile(e,b,c,f,color,faceIndex)};
            }
            var e=a.mix(d,.5F);var f=b.mix(c,.5F);
            return new Tile[]{new Tile(a,b,f,e,color,faceIndex),new Tile(e,f,c,d,color,faceIndex)};
        }
    }
    public record Surface(List<Tile> faces, Identifier texture, int color, float radius) {}
    public record Pair(List<Tile> source, List<Tile> target, Surface from, Surface to) {}

    public static Surface capture(ModelPart root, float scale, Identifier texture) {
        return capture(root,scale,texture,readTexture(texture));
    }
    public static BufferedImage readTexture(Identifier texture) {
        BufferedImage image;
        try(var input=EvolutionSurface.class.getResourceAsStream("/assets/"+texture.getNamespace()+"/"+texture.getPath())) {
            if(input==null)throw new IllegalArgumentException("Missing evolution texture "+texture);
            image=ImageIO.read(input);
        } catch(IOException e) { throw new IllegalStateException("Evolution texture "+texture,e); }
        return image;
    }
    public static Surface capture(ModelPart root,float scale,Identifier texture,BufferedImage image) {
        var stack=new PoseStack();
        // LivingEntityRenderer: rotate 180 around Y, flip X/Y, then model origin at -1.501.
        // Heading is applied once by the caller, independent of the species' runtime size.
        stack.scale(scale,-scale,-scale);stack.translate(0,-1.501,0);
        var tiles=new ArrayList<Tile>();
        var visibility=new HashMap<String,Boolean>();
        root.visit(stack,(pose,path,index,cube)-> {
            if(!visibility.computeIfAbsent(path,key->{
                ModelPart part=root;if(!part.visible)return false;
                for(String name:key.split("/"))if(!name.isEmpty()){part=part.getChild(name);if(!part.visible)return false;}
                return !part.skipDraw;
            }))return;
            for(var polygon:cube.polygons) {
                var v=polygon.vertices();if(v.length!=4)continue;
                var p=new Point[4];
                for(int i=0;i<4;i++) {
                    var xyz=pose.pose().transformPosition(new Vector3f(v[i].worldX(),v[i].worldY(),v[i].worldZ()));
                    p[i]=new Point(xyz.x,xyz.y,xyz.z,v[i].u(),v[i].v());
                }
                var tile=new Tile(p[0],p[1],p[2],p[3],-1);
                if(tile.area()<1e-9F)continue;
                float u=0,vv=0;for(var point:p){u+=point.u*.25F;vv+=point.v*.25F;}
                int color=pixel(image,u,vv);
                // Alpha-cutout sheets are subdivided to texels before discarding transparent cells.
                boolean cutout=false;
                for(int y=0;y<5;y++)for(int x=0;x<5;x++) {
                    var q=p[0].mix(p[1],(x+.5F)/5).mix(p[3].mix(p[2],(x+.5F)/5),(y+.5F)/5);
                    if((pixel(image,q.u,q.v)>>>24)<128)cutout=true;
                }
                if(cutout) {
                    int nx=Math.max(1,Math.min(64,(int)Math.ceil(Math.hypot((p[1].u-p[0].u)*image.getWidth(),(p[1].v-p[0].v)*image.getHeight()))));
                    int ny=Math.max(1,Math.min(64,(int)Math.ceil(Math.hypot((p[3].u-p[0].u)*image.getWidth(),(p[3].v-p[0].v)*image.getHeight()))));
                    for(int y=0;y<ny;y++)for(int x=0;x<nx;x++) {
                        var a=at(p,(float)x/nx,(float)y/ny);var b=at(p,(float)(x+1)/nx,(float)y/ny);
                        var c=at(p,(float)(x+1)/nx,(float)(y+1)/ny);var d=at(p,(float)x/nx,(float)(y+1)/ny);
                        var mid=at(p,(x+.5F)/nx,(y+.5F)/ny);int rgb=pixel(image,mid.u,mid.v);
                        if((rgb>>>24)>=128)tiles.add(new Tile(a,b,c,d,rgb));
                    }
                } else tiles.add(new Tile(p[0],p[1],p[2],p[3],color));
            }
        });
        if(tiles.isEmpty())throw new IllegalArgumentException("Empty evolution surface "+texture);
        // Area-weighted visible samples, quantized into related color families. Padding contributes nothing.
        double[] weights=new double[512];double[][] sums=new double[512][3];float radius=0;
        for(var tile:tiles) {
            for(var p:tile.points())radius=Math.max(radius,p.vector().length());
            int rgb=tile.color,r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
            if(Math.max(r,Math.max(g,b))<45||Math.min(r,Math.min(g,b))>225)continue;
            int key=(r>>5)*64+(g>>5)*8+(b>>5);double w=tile.area();weights[key]+=w;
            sums[key][0]+=r*w;sums[key][1]+=g*w;sums[key][2]+=b*w;
        }
        int best=0;for(int i=1;i<512;i++)if(weights[i]>weights[best])best=i;
        int color=0xff66ccff;
        if(weights[best]>0)color=0xff000000|((int)(sums[best][0]/weights[best])<<16)|((int)(sums[best][1]/weights[best])<<8)|(int)(sums[best][2]/weights[best]);
        for(int i=0;i<tiles.size();i++){var f=tiles.get(i);tiles.set(i,new Tile(f.a,f.b,f.c,f.d,f.color,i));}
        return new Surface(List.copyOf(tiles),texture,color,radius);
    }
    public record Pose(Surface rest,Surface shown,float weight) {
        public Tile apply(Tile tile) {
            int index=tile.faceIndex;if(weight<=0||index<0||rest.faces.size()!=shown.faces.size())return tile;
            var r=rest.faces.get(index);var p=shown.faces.get(index);var ex=r.b.vector().sub(r.a.vector());var ey=r.d.vector().sub(r.a.vector());
            float xx=ex.dot(ex),yy=ey.dot(ey),xy=ex.dot(ey),det=xx*yy-xy*xy;if(det<1e-12)return tile;
            var points=tile.points();
            for(int i=0;i<4;i++) {
                var delta=points[i].vector().sub(r.a.vector());float dx=delta.dot(ex),dy=delta.dot(ey);
                var q=at(p.points(),(dx*yy-dy*xy)/det,(dy*xx-dx*xy)/det);var old=points[i];
                points[i]=new Point(old.x+(q.x-old.x)*weight,old.y+(q.y-old.y)*weight,old.z+(q.z-old.z)*weight,old.u,old.v);
            }
            return new Tile(points[0],points[1],points[2],points[3],tile.color,index);
        }
    }
    private static Point at(Point[] p,float u,float v){return p[0].mix(p[1],u).mix(p[3].mix(p[2],u),v);}
    private static int pixel(BufferedImage image,float u,float v){return image.getRGB(Math.clamp((int)(u*image.getWidth()),0,image.getWidth()-1),Math.clamp((int)(v*image.getHeight()),0,image.getHeight()-1));}

    public static Pair pair(Surface from,Surface to,int budget) {
        int count=Math.max(budget,Math.max(from.faces.size(),to.faces.size()));
        var a=tile(from.faces,count);var b=tile(to.faces,count);
        match(a,b,0,count,0,normalization(a),normalization(b));
        return new Pair(List.of(a),List.of(b),from,to);
    }
    private static Tile[] tile(List<Tile> faces,int count) {
        var queue=new PriorityQueue<Tile>(Comparator.comparingDouble(Tile::edge).reversed());queue.addAll(faces);
        while(queue.size()<count)queue.addAll(List.of(queue.remove().split()));
        return queue.toArray(Tile[]::new);
    }
    private static float[] normalization(Tile[] tiles) {
        float[] n={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        for(var tile:tiles){var p=tile.center();for(int j=0;j<3;j++){n[j]=Math.min(n[j],p.get(j));n[j+3]=Math.max(n[j+3],p.get(j));}}
        for(int j=0;j<3;j++)n[j+3]=Math.max(.01F,n[j+3]-n[j]);return n;
    }
    private static float coordinate(Tile t,int axis,float[] n){return (t.center().get(axis)-n[axis])/n[axis+3];}
    private static void match(Tile[] a,Tile[] b,int low,int high,int depth,float[] na,float[] nb) {
        if(high-low<2)return;
        // Common recursive spatial partitions keep adjacent regions together; no vertex/bone correspondence.
        int axis=depth%3;
        Arrays.sort(a,low,high,Comparator.comparingDouble(t->coordinate(t,axis,na)));
        Arrays.sort(b,low,high,Comparator.comparingDouble(t->coordinate(t,axis,nb)));
        int mid=(low+high)/2;match(a,b,low,mid,depth+1,na,nb);match(a,b,mid,high,depth+1,na,nb);
    }
}
