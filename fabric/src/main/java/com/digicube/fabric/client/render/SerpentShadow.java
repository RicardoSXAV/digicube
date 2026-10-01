package com.digicube.fabric.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A serpent's shadow: one soft band along its body, drawn where the body lies (its joints, {@code state.serpentLine})
 * onto the tops of the blocks under it, as vanilla draws its round shadow under a body's feet, with vanilla's own
 * texture across the band: dark under the middle of the body, fading to the sides, thinning to the tail and fading where
 * the body rises off the ground (the reared neck and head throw a faint one). Vanilla's one shadow would lie under the
 * reared head; blobs along the body read as several shadows.
 */
public final class SerpentShadow {
    private static final RenderType TYPE = RenderTypes.entityShadow(Identifier.withDefaultNamespace("textures/misc/shadow.png"));
    /** The share of vanilla's darkness the band keeps, and how much of it a block of height off the ground takes. */
    private static final float SHARE = .8F, FADE = .3F;
    /** Blocks the band spreads past the body's side (the texture's soft edge), the tail's end and the head's front. */
    private static final float SPREAD = .2F, TAIL = .5F, HEAD = .55F;
    /** Blocks under a joint the ground it falls on is looked for. */
    private static final double DEPTH = 1.5;
    /** Blocks along the body a patch of the band spans at most: a band bent tighter drew a fold. */
    private static final double PATCH = .5;

    /** How lit the block above a face is, 0 to 1: nothing below a raw brightness of 4, as vanilla's shadow. */
    public interface Light { float at(BlockPos above); }

    /** A corner of a patch on a face: its place, its place across the band (0 at one edge, 1 at the other) and its alpha. */
    public record Corner(double x, double y, double z, double u, double alpha) {}

    private SerpentShadow() {}

    /** Submits the band under {@code state}'s body, if it has one and shadows are on. */
    public static void submit(DigimonRenderState state, Level level, PoseStack poseStack, SubmitNodeCollector collector) {
        if (state.serpentLine == null || level == null || state.isInvisible || !net.minecraft.client.Minecraft.getInstance().options.entityShadows().get()) return;
        float pow = (float) (1 - state.distanceToCameraSq / 256) * SHARE;
        if (pow <= 0) return;
        Light light = above -> {
            int brightness = level.getMaxLocalRawBrightness(above);
            return brightness <= 3 ? 0 : Lightmap.getBrightness(level.dimensionType(), brightness);
        };
        var patches = new ArrayList<Corner[]>();
        band(state.serpentLine, state.serpentRadius, state.bodyRot, level, light, pow, patches);
        if (patches.isEmpty()) return;
        double ox = state.x, oy = state.y, oz = state.z;
        collector.submitCustomGeometry(poseStack, TYPE, (pose, vertices) -> {
            Matrix4f matrix = pose.pose();
            var at = new Vector3f();
            for (Corner[] patch : patches)
                for (int k = 1; k + 1 < patch.length; k++) {
                    // a fan of the patch's corners, each triangle as a quad with its last corner twice
                    Corner[] tri = {patch[0], patch[k], patch[k + 1], patch[k + 1]};
                    for (Corner c : tri) {
                        matrix.transformPosition((float) (c.x - ox), (float) (c.y - oy), (float) (c.z - oz), at);
                        vertices.addVertex(at.x, at.y, at.z, ARGB.white((float) c.alpha), (float) c.u, .5F, OverlayTexture.NO_OVERLAY, 15728880, 0, 1, 0);
                    }
                }
        });
    }

    /**
     * The band's patches: for each stretch of the body, the strip across it (the joints' thickness plus SPREAD each
     * side) cut to each block column under it and laid on the top of the block there ({@code light} says how lit that
     * face is), {@code pow} dark at most, fainter the higher the body's underside is over the face. The head's front is
     * added ahead of the first joint along {@code yaw}, and the tail runs on TAIL past the last, thinning to a point.
     */
    public static void band(double[] joints, float[] radius, float yaw, BlockGetter blocks, Light light, float pow, List<Corner[]> out) {
        int n = joints.length / 3;
        if (n < 2) return;
        // the points along the body the band follows: the head's front, the joints, the tail's end
        int m = n + 2;
        double[] px = new double[m], py = new double[m], pz = new double[m], half = new double[m], bottom = new double[m];
        double fx = -Math.sin(yaw * Mth.DEG_TO_RAD), fz = Math.cos(yaw * Mth.DEG_TO_RAD);
        for (int i = 0; i < n; i++) {
            px[i + 1] = joints[3 * i]; py[i + 1] = joints[3 * i + 1]; pz[i + 1] = joints[3 * i + 2];
            half[i + 1] = radius[i] + SPREAD;
            bottom[i + 1] = py[i + 1] - radius[i];
        }
        px[0] = px[1] + fx * HEAD; py[0] = py[1]; pz[0] = pz[1] + fz * HEAD; half[0] = half[1]; bottom[0] = bottom[1];
        double ex = px[n] - px[n - 1], ez = pz[n] - pz[n - 1], e = Math.hypot(ex, ez);
        if (e < 1.0E-6) { ex = -fx; ez = -fz; e = 1; }
        px[m - 1] = px[n] + ex / e * TAIL; py[m - 1] = py[n]; pz[m - 1] = pz[n] + ez / e * TAIL; half[m - 1] = .02; bottom[m - 1] = bottom[n];
        // the level direction across the band at each point: square to the way the body runs there
        double[] nx = new double[m], nz = new double[m];
        for (int i = 0; i < m; i++) {
            int a = Math.max(0, i - 1), b = Math.min(m - 1, i + 1);
            double dx = px[b] - px[a], dz = pz[b] - pz[a], d = Math.hypot(dx, dz);
            if (d < 1.0E-6) { dx = fx; dz = fz; d = 1; }
            nx[i] = -dz / d; nz[i] = dx / d;
        }
        var pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i + 1 < m; i++) {
            double dx = px[i + 1] - px[i], dz = pz[i + 1] - pz[i], along = Math.hypot(dx, dz);
            if (along < 1.0E-6) continue;
            int pieces = Math.max(1, (int) Math.ceil(along / PATCH));
            for (int piece = 0; piece < pieces; piece++) {
                double f0 = (double) piece / pieces, f1 = (double) (piece + 1) / pieces;
                // the strip's four corners in plan, its underside's height and the band's darkness at each end
                double[][] end = new double[2][];
                for (int k = 0; k < 2; k++) {
                    double f = k == 0 ? f0 : f1;
                    double cx = px[i] + dx * f, cz = pz[i] + dz * f, h = half[i] + (half[i + 1] - half[i]) * f;
                    double ax = nx[i] + (nx[i + 1] - nx[i]) * f, az = nz[i] + (nz[i + 1] - nz[i]) * f, a = Math.hypot(ax, az);
                    if (a < 1.0E-6) { ax = nx[i]; az = nz[i]; a = 1; }
                    end[k] = new double[]{cx - ax / a * h, cz - az / a * h, cx + ax / a * h, cz + az / a * h,
                            bottom[i] + (bottom[i + 1] - bottom[i]) * f, i + f < 1 || i + f > m - 2 ? .6 : 1};
                }
                double lowest = Math.min(end[0][4], end[1][4]), highest = Math.max(end[0][4], end[1][4]);
                int x0 = Mth.floor(Math.min(Math.min(end[0][0], end[0][2]), Math.min(end[1][0], end[1][2])));
                int x1 = Mth.floor(Math.max(Math.max(end[0][0], end[0][2]), Math.max(end[1][0], end[1][2])));
                int z0 = Mth.floor(Math.min(Math.min(end[0][1], end[0][3]), Math.min(end[1][1], end[1][3])));
                int z1 = Mth.floor(Math.max(Math.max(end[0][1], end[0][3]), Math.max(end[1][1], end[1][3])));
                for (int bz = z0; bz <= z1; bz++)
                    for (int bx = x0; bx <= x1; bx++)
                        for (int by = Mth.floor(highest); by >= Mth.floor(lowest - DEPTH); by--) {
                            pos.set(bx, by, bz);
                            // a face open to the air: vanilla also draws under a block, where nothing sees it
                            if (blocks.getBlockState(pos).isCollisionShapeFullBlock(blocks, pos)) continue;
                            var below = pos.below();
                            var belowState = blocks.getBlockState(below);
                            if (belowState.getRenderShape() == RenderShape.INVISIBLE || !belowState.isCollisionShapeFullBlock(blocks, below)) continue;
                            var shape = belowState.getShape(blocks, below);
                            if (shape.isEmpty()) continue;
                            float lit = light.at(pos);
                            if (lit <= 0) continue;
                            double top = by - 1 + shape.max(Direction.Axis.Y);
                            // the strip cut to this column, each corner's darkness from its height over the face
                            var corners = new ArrayList<Corner>();
                            corners.add(corner(end[0][0], end[0][1], 0, end[0][4], end[0][5], top, pow, lit));
                            corners.add(corner(end[0][2], end[0][3], 1, end[0][4], end[0][5], top, pow, lit));
                            corners.add(corner(end[1][2], end[1][3], 1, end[1][4], end[1][5], top, pow, lit));
                            corners.add(corner(end[1][0], end[1][1], 0, end[1][4], end[1][5], top, pow, lit));
                            var cut = clip(corners, bx, bz);
                            if (cut.size() >= 3 && cut.stream().anyMatch(c -> c.alpha > .004)) out.add(cut.toArray(new Corner[0]));
                        }
            }
        }
    }

    private static Corner corner(double x, double z, double u, double underside, double end, double top, float pow, float lit) {
        double alpha = Mth.clamp((pow - Math.max(0, underside - top) * FADE) * .5 * lit * end, 0, 1);
        return new Corner(x, top, z, u, alpha);
    }

    /** The polygon cut to the column (bx, bz), its corners' across and alpha carried along its edges. */
    private static List<Corner> clip(List<Corner> polygon, int bx, int bz) {
        List<Corner> in = polygon;
        for (int edge = 0; edge < 4 && !in.isEmpty(); edge++) {
            double bound = edge == 0 ? bx : edge == 1 ? bx + 1 : edge == 2 ? bz : bz + 1;
            boolean onX = edge < 2, keepBelow = edge == 1 || edge == 3;
            var kept = new ArrayList<Corner>();
            for (int i = 0; i < in.size(); i++) {
                Corner a = in.get(i), b = in.get((i + 1) % in.size());
                double da = (onX ? a.x : a.z) - bound, db = (onX ? b.x : b.z) - bound;
                boolean insideA = keepBelow ? da <= 0 : da >= 0, insideB = keepBelow ? db <= 0 : db >= 0;
                if (insideA) kept.add(a);
                if (insideA != insideB) {
                    double f = da / (da - db);
                    kept.add(new Corner(a.x + (b.x - a.x) * f, a.y, a.z + (b.z - a.z) * f, a.u + (b.u - a.u) * f, a.alpha + (b.alpha - a.alpha) * f));
                }
            }
            in = kept;
        }
        return in;
    }
}
