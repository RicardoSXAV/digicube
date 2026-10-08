package com.digicube.fabric.client.digivice;

import com.digicube.fabric.client.evolution.EvolutionMesh;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact chip pixels breaking into golden data, shared by the game and review stage. A used Digitama breaks the same way,
 * from its own sprite's texels ({@link DigitamaVisuals}).
 */
public final class RecallMotion {
    public record Pixel(int x, int y, int rgb) {}
    public record Frame(List<EvolutionMesh.Face> chip, List<EvolutionMesh.Face> glow) {}
    public static final List<Pixel> PIXELS = readPixels();
    /** Seconds after which no texel is left. */
    public static final float GONE = .83F;
    private RecallMotion() {}
    public static float gripLift(float seconds) { return 1-smooth((seconds-.35F)/.55F); }

    public static Frame frame(float seconds) { return frame(PIXELS, seconds); }

    /** {@code pixels}, a sixteen-texel square sprite's, breaking into golden data {@code seconds} in. */
    public static Frame frame(List<Pixel> pixels, float seconds) {
        float t = Math.max(0, seconds);
        var chip = new ArrayList<EvolutionMesh.Face>();
        var glow = new ArrayList<EvolutionMesh.Face>();
        if (t < GONE) for (var pixel : pixels) {
            int seed = pixel.x()*31 + pixel.y()*17;
            float release = .09F + (15-pixel.y())*.012F + (seed%5)*.012F;
            float p = smooth((t-release)/.43F), vanish = smooth((t-release-.26F)/.24F);
            float x = (pixel.x()-7.5F)*.023F, y = (7.5F-pixel.y())*.023F;
            float drift = (seed%11-5)*.022F;
            x = x*(1+.8F*p) + drift*p;
            y = y*(1+.8F*p) - .09F*(float)Math.sin(Math.PI*p) + (.24F + (seed%7)*.035F)*p*p;
            float z = (seed%9-4)*.017F*p;
            float size = .0117F*(1-.38F*p)*(1-.88F*vanish);
            if (vanish >= 1) continue;
            square(chip,x,y,z,size,6,blend(pixel.rgb(),0xffdf72,smooth(p*3)),1);
            if (p > 0) {
                square(glow,x,y,z+.003F,size*1.1F,4,0xffd653,p*(1-vanish)*.55F);
                if (seed%6==0) square(glow,x,y,z+.004F,size*4,2,0xffcd45,p*(1-vanish)*.35F);
            }
        }
        return new Frame(List.copyOf(chip), List.copyOf(glow));
    }
    /** The texels of a sprite {@code width} wide (ARGB, row by row) that are drawn, as the Digivice's art counts them. */
    public static List<Pixel> pixels(int[] argb, int width) {
        var pixels = new ArrayList<Pixel>();
        for (int i = 0; i < argb.length; i++) if (argb[i] >>> 24 >= 0x60) pixels.add(new Pixel(i % width, i / width, argb[i] & 0xffffff));
        return List.copyOf(pixels);
    }
    private static List<Pixel> readPixels() {
        try (var reader=new InputStreamReader(Objects.requireNonNull(RecallMotion.class.getResourceAsStream("/assets/digicube/effects/recall_chip_pixels.json")))) {
            var data=JsonParser.parseReader(reader).getAsJsonObject();var pixels=new ArrayList<Pixel>();
            var rows=data.getAsJsonArray("rows");var palette=data.getAsJsonObject("palette");
            for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                char ch=rows.get(y).getAsString().charAt(x);
                if(ch!='.')pixels.add(new Pixel(x,y,Integer.parseInt(palette.get(String.valueOf(ch)).getAsString().substring(0,6),16)));
            }
            return List.copyOf(pixels);
        } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private static int blend(int a,int b,float p) {
        int rgb=0;for(int shift:new int[]{0,8,16})rgb|=Math.round(((a>>shift)&255)*(1-p)+((b>>shift)&255)*p)<<shift;return rgb;
    }
    public static float smooth(float p) { p=Math.clamp(p,0,1);return p*p*(3-2*p); }
    static void square(List<EvolutionMesh.Face> faces,float x,float y,float z,float r,float u,int rgb,float alpha) {
        float[] v=new float[32];
        for(int i=0;i<4;i++) {
            int a=i*8;v[a]=x+((i==1||i==2)?r:-r);v[a+1]=y+(i>=2?r:-r);v[a+2]=z;
            v[a+3]=u+((i==1||i==2)?1:0);v[a+4]=i>=2?1:0;v[a+7]=1;
        }
        faces.add(new EvolutionMesh.Face(v,(Math.clamp(Math.round(alpha*255),0,255)<<24)|rgb));
    }
}
