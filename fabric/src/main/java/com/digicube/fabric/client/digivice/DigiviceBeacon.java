package com.digicube.fabric.client.digivice;

import com.digicube.fabric.client.evolution.EvolutionMesh;
import java.util.ArrayList;
import java.util.List;

/** Shared runtime/review geometry, in blocks. Three soft crossed ribbons form a warm, quiet locator. */
public final class DigiviceBeacon {
    public static final float HEIGHT = 128;
    private DigiviceBeacon() {}
    public static List<EvolutionMesh.Face> frame(float seconds, float distance) {
        var faces = new ArrayList<EvolutionMesh.Face>(32);
        float visibility = 1 - smooth((distance - 448) / 64);
        float pulse = .93F + .07F * (float) Math.sin(seconds * 1.6);
        // A minimum apparent width keeps the signal readable at distance without growing the near beam.
        float width = Math.max(.34F, distance * .009F);
        for (int i = 0; i < 3; i++) {
            double angle = i * Math.PI / 3;
            float x = (float) Math.cos(angle) * width, z = (float) Math.sin(angle) * width;
            quad(faces, new float[]{-x,.12F,-z, x,.12F,z, x,HEIGHT,z, -x,HEIGHT,-z}, 0, color(.62F * pulse * visibility));
        }
        float r = .65F;
        quad(faces, new float[]{-r,.014F,-r, -r,.014F,r, r,.014F,r, r,.014F,-r}, 2, color(.40F * visibility));
        // Sparse square sparks rise gently around the device, never a screen full of particles.
        if (distance < 48) for (int i = 0; i < 12; i++) {
            float phase = (seconds * .22F + i * .618034F) % 1;
            double angle = i * 2.39996 + seconds * .12;
            float x = (float) Math.cos(angle) * (.13F + .18F * phase), z = (float) Math.sin(angle) * (.13F + .18F * phase);
            float y = .15F + phase * 1.5F, size = .018F + .014F * (i % 3) / 2;
            int color = color((float) Math.sin(phase * Math.PI) * .6F * visibility * (1 - smooth((distance - 24) / 24)));
            quad(faces, new float[]{x-size,y-size,z, x+size,y-size,z, x+size,y+size,z, x-size,y+size,z}, 4, color);
            quad(faces, new float[]{x,y-size,z-size, x,y-size,z+size, x,y+size,z+size, x,y+size,z-size}, 4, color);
        }
        return List.copyOf(faces);
    }
    private static float smooth(float x) { x = Math.clamp(x, 0, 1); return x*x*(3-2*x); }
    private static int color(float alpha) { return (Math.clamp(Math.round(alpha * 255), 0, 255) << 24) | 0xffd653; }
    private static void quad(List<EvolutionMesh.Face> out, float[] p, float u, int color) {
        float[] vertices = new float[32];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(p, i*3, vertices, i*8, 3);
            vertices[i*8+3] = u + (i == 1 || i == 2 ? 1 : 0); vertices[i*8+4] = i >= 2 ? 1 : 0;
            vertices[i*8+6] = 1;
        }
        out.add(new EvolutionMesh.Face(vertices, color));
    }
    /** Shader-matched transfer function for offline review. No invented bloom in the preview. */
    public static float radiance(float u, float v) {
        if (u >= 4) { float d = Math.min(Math.min(u-4, 5-u), Math.min(v, 1-v)); return .25F + .65F*(1-smooth((d-.06F)/.05F)); }
        if (u >= 2) { float r = (float) Math.hypot((u-2)*2-1, v*2-1); return (float) Math.pow(Math.max(0, 1-r), 2); }
        float x = Math.abs(u*2-1);
        float glow = (float) Math.exp(-x*x*8) * .15F;
        float core = (float) Math.exp(-x*x*240) * .75F;
        return (core + glow) * (1-smooth((v-.78F)/.22F));
    }
}
