package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The flames a Burned body burns in ({@link BurnedVisuals}), drawn with the body in place of vanilla's sheet of fire:
 * separate tongues of fire standing on it, so the body shows between them. Each is a pixel flame of eight frames
 * ({@code textures/entity/burn_flame.png}: a spark, growing, rising, a tall lick, a lick to the other side with its tip
 * torn off, settling, shrinking, a dying wisp) standing upright and facing the camera, full-bright. A body has a number
 * of flame places by its size, fewer as the fire burns down; each plays its frames over the body's cycle of 13 to 17
 * ticks (quick to catch and to die, long in full lick, the places' phases spread through it) and then springs up
 * somewhere else: half of them on the body's top, the others round its sides from a third of its height up, their feet
 * at the edge of the body so they stand out of its silhouette. Taller bodies burn in taller flames.
 */
public final class BurningFlames {
    private BurningFlames() {}

    static final Identifier TEXTURE = Constants.id("textures/entity/burn_flame.png");
    /** The strip's frames, and each frame's size in texels. */
    static final int FRAMES = 8, FRAME_W = 8, FRAME_H = 12;
    /** How long each frame shows, in shares of a flame's cycle: quick to catch and to die, long in full lick. */
    private static final float[] HOLD = {1, 1, 2, 2, 2, 2, 1, 1};
    private static final float HOLD_TOTAL = 12;

    /** One flame this frame: its foot (blocks from the body's feet), its height (blocks), its frame, and whether it is mirrored. */
    record Flame(float x, float y, float z, float height, int frame, boolean mirrored) {}

    /**
     * The flames of a body {@code width} by {@code height} blocks, Burned by {@code burn} (its remaining share), at
     * {@code time} (ticks); {@code seed} keeps one body's flames its own.
     */
    static List<Flame> layout(float width, float height, float burn, int seed, float time) {
        int places = Mth.clamp(Math.round(3 + 2 * (width + height)), 5, 14);
        int lit = Math.max(4, Math.round(places * (.55F + .45F * burn)));
        float scale = Mth.clamp(height / 1.8F, .7F, 1.25F) * (.75F + .25F * burn);
        // one cycle for all of a body's places, their phases spread evenly through it, so some are always in full lick
        float cycle = 13 + Math.floorMod(seed, 5);
        List<Flame> out = new ArrayList<>(lit);
        for (int i = 0; i < lit; i++) {
            int own = hash(seed, i);
            float shifted = time + cycle * (i / (float) lit + (own >>> 4) % 5 * .02F);
            int round = Mth.floor(shifted / cycle);
            float played = shifted / cycle - round;
            int r = hash(own, round);
            float b = unit(r, 2), c = unit(r, 3), d = unit(r, 4);
            float tall = (.27F + .17F * d) * scale, x, y, z;
            if (i % 2 == 0) {
                // on its top (half the places: fire rises)
                x = (b - .5F) * width * .6F; z = (c - .5F) * width * .6F; y = height - .05F;
            } else {
                // round its sides
                float way = b * Mth.TWO_PI;
                x = Mth.cos(way) * width * .5F; z = Mth.sin(way) * width * .5F; y = height * (.33F + .6F * c);
            }
            out.add(new Flame(x, y, z, tall, frame(played), (r & 32) != 0));
        }
        return out;
    }

    /** The frame showing {@code played} of the way through a flame's cycle. */
    private static int frame(float played) {
        float at = played * HOLD_TOTAL;
        for (int f = 0; f < FRAMES; f++) if ((at -= HOLD[f]) < 0) return f;
        return FRAMES - 1;
    }

    /** Draws a Burned body's flames about its feet ({@code pose} as the dispatcher placed the body), facing the camera. */
    public static void submit(EntityRenderState state, BurnedVisuals.Burning burning, PoseStack pose, SubmitNodeCollector collector, Quaternionf camera) {
        List<Flame> flames = layout(state.boundingBoxWidth, state.boundingBoxHeight, burning.burn(), burning.seed(), state.ageInTicks);
        Vector3f right = Mth.rotationAroundAxis(Mth.Y_AXIS, camera, new Quaternionf()).transform(new Vector3f(1, 0, 0));
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE, false), (at, vertices) -> {
            for (Flame flame : flames) quad(at, vertices, flame, right);
        });
    }

    private static void quad(PoseStack.Pose at, VertexConsumer vertices, Flame flame, Vector3f right) {
        float half = flame.height() * FRAME_W / FRAME_H / 2, rx = right.x * half, rz = right.z * half;
        float u0 = flame.frame() / (float) FRAMES, u1 = (flame.frame() + 1) / (float) FRAMES;
        if (flame.mirrored()) { float swap = u0; u0 = u1; u1 = swap; }
        float x = flame.x(), y = flame.y(), z = flame.z(), top = y + flame.height();
        vertex(at, vertices, x - rx, y, z - rz, u0, 1);
        vertex(at, vertices, x + rx, y, z + rz, u1, 1);
        vertex(at, vertices, x + rx, top, z + rz, u1, 0);
        vertex(at, vertices, x - rx, top, z - rz, u0, 0);
    }

    private static void vertex(PoseStack.Pose at, VertexConsumer vertices, float x, float y, float z, float u, float v) {
        vertices.addVertex(at, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(at, 0, 1, 0);
    }

    private static int hash(int a, int b) {
        int h = (a ^ 0x9e3779b9) * 0x85ebca6b ^ (b + 0x632be5ab) * 0xc2b2ae35;
        h ^= h >>> 13;
        h *= 0x27d4eb2f;
        return (h ^ h >>> 15) & 0x7fffffff;
    }

    private static float unit(int h, int salt) {
        return (hash(h, salt) & 0xffff) / 65536F;
    }
}
