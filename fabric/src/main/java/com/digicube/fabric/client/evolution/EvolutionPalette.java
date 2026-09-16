package com.digicube.fabric.client.evolution;
/** Metric face coordinates keep squares square on rotated cuboids, independent of texture resolution. */
public final class EvolutionPalette {
    public static final float WHITE_U=.5F,WHITE_V=.5F;
    private EvolutionPalette() {}
    public static void grid(float[] vertices,int color) {
        float ux=vertices[8]-vertices[0],uy=vertices[9]-vertices[1],uz=vertices[10]-vertices[2];
        float length=(float)Math.sqrt(ux*ux+uy*uy+uz*uz);
        if(length<1e-7F)return;
        ux/=length;uy/=length;uz/=length;
        float nx=vertices[5],ny=vertices[6],nz=vertices[7];
        float vx=ny*uz-nz*uy,vy=nz*ux-nx*uz,vz=nx*uy-ny*ux;
        for(int i=0;i<32;i+=8) {
            vertices[i+3]=(vertices[i]*ux+vertices[i+1]*uy+vertices[i+2]*uz)*3.5F;
            vertices[i+4]=(vertices[i]*vx+vertices[i+1]*vy+vertices[i+2]*vz)*3.5F;
        }
    }
}
