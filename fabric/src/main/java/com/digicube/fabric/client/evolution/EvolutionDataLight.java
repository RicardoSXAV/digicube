package com.digicube.fabric.client.evolution;

import com.digicube.digimon.EvolutionTimeline;
import java.util.ArrayList;
import java.util.List;

/** A finite train of flat data tiles flowing into the body before transformation. */
public final class EvolutionDataLight {
    public static final int MAX_PACKETS = 24;
    public static final int MAX_QUADS = MAX_PACKETS;
    /** Extra transparent area for the halo; the shader preserves the approved inner rectangle. */
    public static final float HALO_SCALE = 2.6F;
    private EvolutionDataLight() {}

    public record Packet(int index, float y, float halfWidth, float halfHeight) {}
    public static float end(EvolutionTimeline time) {
        return time.returning() ? 0 : time.leadIn() > 0 ? time.leadIn() : time.shedStart();
    }
    public static float impact(EvolutionTimeline time) { return end(time) * .4F; }

    /** A rigid train: every tile shares one displacement, so gaps and sizes never compress in flight. */
    public static List<Packet> packets(EvolutionTimeline time, float tick, float skyHeight,
                                        float bodyHeight, float bodyRadius) {
        float end = end(time);
        if (tick <= 0 || tick >= end) return List.of();
        int count = time.longForm() ? MAX_PACKETS : 6;
        float intake = Math.max(.1F, bodyHeight * .55F);
        float top = Math.max(bodyHeight + 4, skyHeight);
        float baseWidth = Math.clamp(bodyRadius * .14F, .12F, .32F);
        float halfWidth = baseWidth * 1.3F, halfHeight = baseWidth * .45F;
        float pitch = (.24F + halfHeight * 2.6F) * .8F;
        float arrival = impact(time);
        float speed = ((count - 1) * pitch + halfHeight * 1.3F) / (end - arrival);
        // Ease the distant entrance into a constant downward speed near the creature.
        // This offset applies to the entire train, never separately to individual tiles.
        float entrance = Math.max(0, 1 - tick / arrival);
        float head = speed * (arrival - tick)
                + (top - intake - speed * arrival) * entrance * entrance * entrance;
        var packets = new ArrayList<Packet>(count);
        for (int i = 0; i < count; i++) {
            float y = intake + head + i * pitch;
            if (y + halfHeight * 1.3F <= intake || y >= top) continue;
            packets.add(new Packet(i, y, halfWidth, halfHeight));
        }
        return List.copyOf(packets);
    }

    public static List<EvolutionMesh.Face> frame(EvolutionTimeline time, float tick, float skyHeight,
                                                float bodyHeight, float bodyRadius) {
        var out = new ArrayList<EvolutionMesh.Face>(MAX_QUADS);
        for (var packet : packets(time, tick, skyHeight, bodyHeight, bodyRadius)) {
            float[] vertices = new float[32];
            for (int corner = 0; corner < 4; corner++) {
                int at = corner * 8;
                vertices[at] = (corner == 1 || corner == 2 ? 1 : -1) * packet.halfWidth() * HALO_SCALE;
                vertices[at + 1] = packet.y() + (corner >= 2 ? 1 : -1) * packet.halfHeight() * HALO_SCALE;
                vertices[at + 3] = corner == 1 || corner == 2 ? 1 : 0;
                vertices[at + 4] = corner >= 2 ? 1 : 0;
                vertices[at + 7] = 1;
            }
            out.add(new EvolutionMesh.Face(vertices, 0xffeffff5));
        }
        return List.copyOf(out);
    }

    /** Rotate each flat tile around its own centre. The absorption path stays vertical in world space. */
    public static List<EvolutionMesh.Face> facing(List<EvolutionMesh.Face> tiles, org.joml.Quaternionfc camera) {
        if(tiles.isEmpty())return tiles;
        var result = new ArrayList<EvolutionMesh.Face>(tiles.size());
        for(var face : tiles) {
            var v = face.vertices().clone();
            var center = new org.joml.Vector3f();
            for(int i=0;i<32;i+=8)center.add(v[i],v[i+1],v[i+2]);
            center.mul(.25F);
            for(int i=0;i<32;i+=8) {
                var point = new org.joml.Vector3f(v[i],v[i+1],v[i+2]).sub(center).rotate(camera).add(center);
                var normal = new org.joml.Vector3f(v[i+5],v[i+6],v[i+7]).rotate(camera);
                v[i]=point.x;v[i+1]=point.y;v[i+2]=point.z;
                v[i+5]=normal.x;v[i+6]=normal.y;v[i+7]=normal.z;
            }
            result.add(new EvolutionMesh.Face(v,face.color()));
        }
        return List.copyOf(result);
    }
}
