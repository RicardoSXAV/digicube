package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Surfaces a jet of water has struck stay wet for a while on every client (a breath with {@code wet}: Hydro Pressure).
 * Each struck block face holds a 4 x 4 grid of cells, each with its own wetness: a strike wets the cells of the faces
 * about it within the water's spread (the faces of the neighbouring blocks on the same plane too, where they are open to
 * the air), and they dry over about twelve seconds, the edges of a wet patch first. A wet cell darkens its block with a
 * thin film of the jet's own colours (the shallow look); on a floor a soaked one shows a puddle's sheen; soaked walls and
 * ceilings drip. Cells never overlap on one plane (no flicker); the oldest faces give way past {@link #MAX_FACES}, so
 * however long a jet plays the cost stays bounded.
 */
public final class WetSurfaces {
    private static final Identifier TEXTURE = Constants.id("textures/effect/wet_surface.png");
    /** Cells across a face, the most faces kept, and the share of a cell's wetness it loses a tick (about 12 s from soaked). */
    private static final int GRID = 4, MAX_FACES = 1200;
    private static final float DRY = 1F / 240, EDGE_DRY = 1F / 160;
    /** Wetness above which a floor cell is a puddle, and a wall's or ceiling's drips. */
    private static final float PUDDLE = .62F, DRIP = .5F;
    /** Blocks the film stands off its face (clear of the block's own face). */
    private static final float LIFT = .0018F;

    private record Key(long pos, Direction face) {}
    private static final class Face {
        final float[] wet = new float[GRID * GRID];
        int touched;
        boolean any() { for (float w : wet) if (w > .02F) return true; return false; }
    }

    private static final Map<Key, Face> FACES = new HashMap<>();
    /** The last tick each breather's strikes were taken, so a tick's strikes wet once. */
    private static final Map<Integer, Integer> TAKEN = new HashMap<>();
    private static ClientLevel lastLevel;

    private WetSurfaces() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> { if (client.level != null && !client.isPaused()) tick(client.level); });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (FACES.isEmpty()) return;
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            var camera = context.levelState().cameraRenderState;
            var pose = context.poseStack();
            pose.pushPose();
            pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
            var entries = new ArrayList<>(FACES.entrySet());
            context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.entityTranslucent(TEXTURE), (matrix, vertices) -> {
                for (var e : entries) draw(level, e.getKey(), e.getValue(), matrix, vertices);
            });
            pose.popPose();
        });
    }

    private static void tick(ClientLevel level) {
        if (level != lastLevel) { FACES.clear(); TAKEN.clear(); lastLevel = level; }
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof DigimonEntity digimon)) continue;
            var breath = digimon.clientBreath();
            if (breath == null || !breath.spec().wet()) continue;
            if (TAKEN.getOrDefault(digimon.getId(), -1) == digimon.tickCount) continue;
            TAKEN.put(digimon.getId(), digimon.tickCount);
            for (var p : breath.puffs()) {
                if (p.hit == null || p.normal == null || p.underwater) continue;
                wet(level, p.hit, p.normal, breath.radius(p) * 1.3F, .22F);
            }
        }
        if (FACES.isEmpty()) return;
        var random = level.getRandom();
        var gone = new ArrayList<Key>();
        for (var e : FACES.entrySet()) {
            Face f = e.getValue();
            float[] w = f.wet;
            float[] next = w.clone();
            for (int i = 0; i < w.length; i++) {
                if (w[i] <= 0) continue;
                int x = i % GRID, y = i / GRID;
                // a patch dries from its edges in: a cell with a dry neighbour loses more
                boolean edge = x == 0 || y == 0 || x == GRID - 1 || y == GRID - 1 ? false
                        : w[i - 1] < .05F || w[i + 1] < .05F || w[i - GRID] < .05F || w[i + GRID] < .05F;
                next[i] = Math.max(0, w[i] - (edge ? EDGE_DRY : DRY));
            }
            System.arraycopy(next, 0, w, 0, w.length);
            if (!f.any()) { gone.add(e.getKey()); continue; }
            // soaked walls and ceilings drip
            var face = e.getKey().face();
            if (face != Direction.UP && random.nextInt(24) == 0) {
                int i = random.nextInt(w.length);
                if (w[i] > DRIP) {
                    Vec3 at = cell(e.getKey(), i % GRID + .5F, face == Direction.DOWN ? i / GRID + .5F : GRID - .5F, .02F);
                    level.addParticle(ParticleTypes.DRIPPING_WATER, at.x, at.y - .02, at.z, 0, 0, 0);
                }
            }
        }
        gone.forEach(FACES::remove);
        if (FACES.size() > MAX_FACES) {
            var oldest = new ArrayList<>(FACES.entrySet());
            oldest.sort((a, b) -> Integer.compare(a.getValue().touched, b.getValue().touched));
            for (int i = 0; i < FACES.size() - MAX_FACES && i < oldest.size(); i++) FACES.remove(oldest.get(i).getKey());
        }
    }

    /** Wets the cells within {@code radius} (blocks) of {@code hit} on the plane of the face its normal names. */
    private static void wet(ClientLevel level, Vec3 hit, Vec3 normal, float radius, float amount) {
        Direction face = Direction.getApproximateNearest(normal.x, normal.y, normal.z);
        BlockPos struck = BlockPos.containing(hit.subtract(normal.scale(.05)));
        // the plane's two in-plane axes
        Direction.Axis a1 = face.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        Direction.Axis a2 = face.getAxis() == Direction.Axis.Y ? Direction.Axis.Z : Direction.Axis.Y;
        int reach = Mth.ceil(radius) + 1;
        float cell = 1F / GRID;
        int tick = (int) level.getGameTime();
        for (int d1 = -reach; d1 <= reach; d1++) for (int d2 = -reach; d2 <= reach; d2++) {
            BlockPos pos = struck.relative(Direction.fromAxisAndDirection(a1, Direction.AxisDirection.POSITIVE), d1)
                    .relative(Direction.fromAxisAndDirection(a2, Direction.AxisDirection.POSITIVE), d2);
            // a face open to the air in front of a solid block
            if (!solid(level, pos) || solid(level, pos.relative(face)) || !level.getFluidState(pos.relative(face)).isEmpty()) continue;
            Key key = new Key(pos.asLong(), face);
            Face f = null;
            for (int i = 0; i < GRID * GRID; i++) {
                Vec3 at = cell(key, i % GRID + .5F, i / GRID + .5F, 0);
                double dist = at.distanceTo(hit);
                if (dist > radius + cell * .7F) continue;
                if (f == null) f = FACES.computeIfAbsent(key, k -> new Face());
                float share = (float) Math.clamp(1 - dist / (radius + cell), .25, 1);
                f.wet[i] = Math.min(1, f.wet[i] + amount * share);
                f.touched = tick;
            }
        }
    }

    private static boolean solid(ClientLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return !state.isAir() && state.isCollisionShapeFullBlock(level, pos);
    }

    /**
     * The world point of a cell coordinate (u, v in cells across the face, 0..GRID) on a face, {@code lift} off it. For
     * the sides v runs up the face; for the floor and the ceiling the plane's second axis.
     */
    private static Vec3 cell(Key key, float u, float v, float lift) {
        BlockPos pos = BlockPos.of(key.pos());
        Direction face = key.face();
        double s = 1.0 / GRID;
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        double nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
        // the face's plane: the block's side in the normal's direction
        double px = x + (nx > 0 ? 1 : 0), py = y + (ny > 0 ? 1 : 0), pz = z + (nz > 0 ? 1 : 0);
        return switch (face.getAxis()) {
            case Y -> new Vec3(x + u * s, py + ny * lift, z + v * s);
            case X -> new Vec3(px + nx * lift, y + v * s, z + u * s);
            case Z -> new Vec3(x + u * s, y + v * s, pz + nz * lift);
        };
    }

    private static void draw(ClientLevel level, Key key, Face f, PoseStack.Pose matrix, VertexConsumer vertices) {
        Direction face = key.face();
        BlockPos pos = BlockPos.of(key.pos());
        int light = LightCoordsUtil.getLightCoords(level, pos.relative(face));
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
        boolean floor = face == Direction.UP;
        for (int i = 0; i < f.wet.length; i++) {
            float w = f.wet[i];
            if (w <= .02F) continue;
            int u = i % GRID, v = i / GRID;
            // the film: the darker damp look, stronger the wetter; a soaked floor cell is a puddle (the sheen half)
            boolean puddle = floor && w > PUDDLE;
            float alpha = puddle ? .55F + .4F * (w - PUDDLE) / (1 - PUDDLE) : .25F + .6F * Math.min(1, w / PUDDLE);
            int color = Math.round(Mth.clamp(alpha, 0, 1) * 255) << 24 | 0xFFFFFF;
            // the cell's own square of the texture: the damp half (u 0..0.5) or the puddle half (0.5..1)
            float tu0 = (puddle ? .5F : 0) + u * .5F / GRID, tu1 = tu0 + .5F / GRID, tv0 = v * 1F / GRID, tv1 = tv0 + 1F / GRID;
            Vec3 a = cell(key, u, v, LIFT), b = cell(key, u + 1, v, LIFT), c = cell(key, u + 1, v + 1, LIFT), d = cell(key, u, v + 1, LIFT);
            // wound to face out of the block
            boolean flip = face == Direction.UP || face == Direction.WEST || face == Direction.SOUTH;
            if (flip) {
                vertex(matrix, vertices, a, tu0, tv0, color, light, nx, ny, nz); vertex(matrix, vertices, d, tu0, tv1, color, light, nx, ny, nz);
                vertex(matrix, vertices, c, tu1, tv1, color, light, nx, ny, nz); vertex(matrix, vertices, b, tu1, tv0, color, light, nx, ny, nz);
            } else {
                vertex(matrix, vertices, a, tu0, tv0, color, light, nx, ny, nz); vertex(matrix, vertices, b, tu1, tv0, color, light, nx, ny, nz);
                vertex(matrix, vertices, c, tu1, tv1, color, light, nx, ny, nz); vertex(matrix, vertices, d, tu0, tv1, color, light, nx, ny, nz);
            }
        }
    }

    private static void vertex(PoseStack.Pose matrix, VertexConsumer vertices, Vec3 p, float u, float v, int color, int light, float nx, float ny, float nz) {
        vertices.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(matrix, nx, ny, nz);
    }
}
