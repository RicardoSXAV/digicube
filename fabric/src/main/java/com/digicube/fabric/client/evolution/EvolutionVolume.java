package com.digicube.fabric.client.evolution;

import java.util.*;

/** Connected cuboid morph with greedy coplanar face merging, including open cutout sheets. */
public final class EvolutionVolume {
    private static final int N=32,S=N+1;
    public record Field(float[] distance,float[] bounds) {}
    private EvolutionVolume() {}
    private static int at(int x,int y,int z){return (z*S+y)*S+x;}
    public static Field field(EvolutionSurface.Surface surface) {
        float[] bounds={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        for(var tile:surface.faces())for(var p:tile.points())for(int a=0;a<3;a++){float v=p.vector().get(a);bounds[a]=Math.min(bounds[a],v);bounds[a+3]=Math.max(bounds[a+3],v);}
        for(int a=0;a<3;a++){float pad=Math.max(.02F,(bounds[a+3]-bounds[a])/N*1.5F);bounds[a]-=pad;bounds[a+3]+=pad;}
        var triangles=new ArrayList<float[][]>();
        for(var tile:surface.faces()) {
            float[][] v=new float[4][3];var p=tile.points();
            for(int i=0;i<4;i++)for(int a=0;a<3;a++)v[i][a]=(p[i].vector().get(a)-bounds[a])/(bounds[a+3]-bounds[a])*N;
            triangles.add(new float[][]{v[0],v[1],v[2]});triangles.add(new float[][]{v[0],v[2],v[3]});
        }
        boolean[] inside=new boolean[S*S*S];
        // Scanline parity fills solids without relying on normals; thin/open sheets get a conservative shell.
        for(int z=1;z<N;z++)for(int y=1;y<N;y++) {
            var crossings=new ArrayList<Float>();
            for(var triangle:triangles) {
                var a=triangle[0];var b=triangle[1];var c=triangle[2];float yy=y+.0013F,zz=z+.0027F;
                float det=(b[2]-c[2])*(a[1]-c[1])+(c[1]-b[1])*(a[2]-c[2]);if(Math.abs(det)<1e-6)continue;
                float u=((b[2]-c[2])*(yy-c[1])+(c[1]-b[1])*(zz-c[2]))/det;
                float v=((c[2]-a[2])*(yy-c[1])+(a[1]-c[1])*(zz-c[2]))/det;
                if(u>=0&&v>=0&&u+v<=1)crossings.add(u*a[0]+v*b[0]+(1-u-v)*c[0]);
            }
            crossings.sort(Float::compare);
            // Union winding intervals per closed cube works with overlapping exported solids.
            for(int i=0;i+1<crossings.size();i+=2)for(int x=Math.max(1,(int)Math.ceil(crossings.get(i)));x<=Math.min(N-1,(int)Math.floor(crossings.get(i+1)));x++)inside[at(x,y,z)]=true;
        }
        for(var tri:triangles) {
            var a=tri[0];var b=tri[1];var c=tri[2];int steps=2+(int)Math.ceil(Math.max(length(a,b),Math.max(length(a,c),length(b,c)))*1.5);
            for(int u=0;u<=steps;u++)for(int v=0;v<=steps-u;v++) {
                float q=(float)u/steps,r=(float)v/steps;int[] p=new int[3];
                for(int i=0;i<3;i++)p[i]=Math.clamp(Math.round(a[i]+(b[i]-a[i])*q+(c[i]-a[i])*r),1,N-1);
                inside[at(p[0],p[1],p[2])]=true;
            }
        }
        float[] distance=new float[inside.length];Arrays.fill(distance,1000);
        var queue=new ArrayDeque<Integer>();
        for(int z=0;z<S;z++)for(int y=0;y<S;y++)for(int x=0;x<S;x++) {
            int i=at(x,y,z);
            if(x>0&&inside[i]!=inside[i-1]||x<N&&inside[i]!=inside[i+1]||y>0&&inside[i]!=inside[i-S]||y<N&&inside[i]!=inside[i+S]||z>0&&inside[i]!=inside[i-S*S]||z<N&&inside[i]!=inside[i+S*S]){distance[i]=.5F;queue.add(i);}
        }
        while(!queue.isEmpty()) {
            int i=queue.remove(),x=i%S,y=i/S%S,z=i/(S*S);
            for(int d:new int[]{-1,1,-S,S,-S*S,S*S}) {
                if(d==-1&&x==0||d==1&&x==N||d==-S&&y==0||d==S&&y==N||d==-S*S&&z==0||d==S*S&&z==N)continue;
                int next=i+d;if(distance[next]>distance[i]+1){distance[next]=distance[i]+1;queue.add(next);}
            }
        }
        for(int i=0;i<distance.length;i++)if(inside[i])distance[i]=-distance[i];
        return new Field(distance,bounds);
    }
    private static float length(float[] a,float[] b){float d=0;for(int i=0;i<3;i++)d+=(a[i]-b[i])*(a[i]-b[i]);return (float)Math.sqrt(d);}
    public static List<EvolutionMesh.Face> frame(Field a,Field b,float t,int color,float lift,boolean detailed) {
        int N=detailed?32:16,S=N+1,step=32/N;
        float[] values=new float[S*S*S],bounds=new float[6];
        for(int z=0;z<S;z++)for(int y=0;y<S;y++)for(int x=0;x<S;x++){int i=(z*S+y)*S+x,j=at(x*step,y*step,z*step);values[i]=a.distance[j]*(1-t)+b.distance[j]*t;}
        for(int i=0;i<6;i++)bounds[i]=a.bounds[i]*(1-t)+b.bounds[i]*t;
        if(t>.02F&&t<.98F)connect(values,S,1.1F*(float)Math.pow(Math.sin(Math.PI*t),.25));
        return cuboids(values,S,bounds,lift,color);
    }
    /** Emit only the boundary of the filled lattice, merging coplanar rectangles instead of drawing cubes. */
    private static List<EvolutionMesh.Face> cuboids(float[] values,int size,float[] bounds,float lift,int color) {
        var faces=new ArrayList<EvolutionMesh.Face>();
        int[] mask=new int[size*size],strides={1,size,size*size};
        float[] pitch={(bounds[3]-bounds[0])/(size-1),(bounds[4]-bounds[1])/(size-1),(bounds[5]-bounds[2])/(size-1)};
        for(int axis=0;axis<3;axis++) {
            int u=(axis+1)%3,v=(axis+2)%3;
            for(int slice=-1;slice<size;slice++) {
                for(int j=0;j<size;j++)for(int i=0;i<size;i++) {
                    int at=slice*strides[axis]+i*strides[u]+j*strides[v];
                    boolean behind=slice>=0&&values[at]<0,ahead=slice<size-1&&values[at+strides[axis]]<0;
                    mask[j*size+i]=behind==ahead?0:behind?1:-1;
                }
                for(int j=0;j<size;j++)for(int i=0;i<size;) {
                    int sign=mask[j*size+i];if(sign==0){i++;continue;}
                    int width=1,height=1;
                    while(i+width<size&&mask[j*size+i+width]==sign)width++;
                    outer:while(j+height<size) {
                        for(int k=0;k<width;k++)if(mask[(j+height)*size+i+k]!=sign)break outer;
                        height++;
                    }
                    float[] vertices=new float[32];
                    for(int corner=0;corner<4;corner++) {
                        int order=sign>0?corner:3-corner,at=corner*8;
                        vertices[at+axis]=bounds[axis]+(slice+.5F)*pitch[axis];
                        vertices[at+u]=bounds[u]+(i-.5F+(order==1||order==2?width:0))*pitch[u];
                        vertices[at+v]=bounds[v]+(j-.5F+(order>=2?height:0))*pitch[v];
                        vertices[at+1]+=lift;vertices[at+5+axis]=sign;
                    }
                    EvolutionPalette.grid(vertices,color);
                    float light=axis==1?(sign>0?1F:.76F):axis==0?.88F:.94F;
                    int shaded=0xff000000;
                    for(int shift:new int[]{0,8,16})shaded|=Math.round(((color>>shift)&255)*light)<<shift;
                    faces.add(new EvolutionMesh.Face(vertices,shaded));
                    for(int y=0;y<height;y++)Arrays.fill(mask,(j+y)*size+i,(j+y)*size+i+width,0);
                    i+=width;
                }
            }
        }
        return faces;
    }
    /** Preserve a continuous data body when unrelated appendages otherwise become isolated islands. */
    private static void connect(float[] values,int size,float radius) {
        int plane=size*size;boolean[] visited=new boolean[values.length];int[] queue=new int[values.length];
        var components=new ArrayList<int[]>();int[] offsets={-1,1,-size,size,-plane,plane};
        for(int seed=0;seed<values.length;seed++)if(values[seed]<0&&!visited[seed]) {
            int read=0,write=1;queue[0]=seed;visited[seed]=true;
            while(read<write) {
                int at=queue[read++],x=at%size,y=at/size%size,z=at/plane;
                for(int d:offsets) {
                    if(d==-1&&x==0||d==1&&x==size-1||d==-size&&y==0||d==size&&y==size-1||d==-plane&&z==0||d==plane&&z==size-1)continue;
                    int next=at+d;if(!visited[next]&&values[next]<0){visited[next]=true;queue[write++]=next;}
                }
            }
            components.add(Arrays.copyOf(queue,write));
        }
        if(components.size()<2)return;components.sort(Comparator.comparingInt((int[] c)->c.length).reversed());var core=components.getFirst();
        for(int i=1;i<components.size();i++) {
            var part=components.get(i);int from=part[part.length/2],to=core[0];float best=Float.MAX_VALUE;
            int ax=from%size,ay=from/size%size,az=from/plane;
            for(int index:core){float dx=index%size-ax,dy=index/size%size-ay,dz=index/plane-az,d=dx*dx+dy*dy+dz*dz;if(d<best){best=d;to=index;}}
            float bx=to%size,by=to/size%size,bz=to/plane,dx=bx-ax,dy=by-ay,dz=bz-az,len=dx*dx+dy*dy+dz*dz;
            for(int z=Math.max(1,(int)Math.min(az,bz)-2);z<=Math.min(size-2,(int)Math.max(az,bz)+2);z++)
                for(int y=Math.max(1,(int)Math.min(ay,by)-2);y<=Math.min(size-2,(int)Math.max(ay,by)+2);y++)
                    for(int x=Math.max(1,(int)Math.min(ax,bx)-2);x<=Math.min(size-2,(int)Math.max(ax,bx)+2);x++) {
                        float u=Math.clamp(((x-ax)*dx+(y-ay)*dy+(z-az)*dz)/Math.max(.001F,len),0,1);
                        float xx=x-ax-u*dx,yy=y-ay-u*dy,zz=z-az-u*dz;int index=(z*size+y)*size+x;
                        values[index]=Math.min(values[index],(float)Math.sqrt(xx*xx+yy*yy+zz*zz)-radius);
                    }
        }
    }
}
