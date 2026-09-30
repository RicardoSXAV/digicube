package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.digimon.IceCombo;
import com.digicube.entity.CombatMarkState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.LivingEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Camera-facing combat mark emblems in a row, extracted for every living renderer, including vanilla mobs. */
public final class CombatMarkBadges {
    /** What one entity shows this frame; null when it shows nothing. */
    public record Marks(int packed, int packed2, float coldRemainingTicks) {}
    public static final RenderStateDataKey<Marks> MARKS = RenderStateDataKey.create();
    private static final RenderStateDataKey<List<Row>> ROWS = RenderStateDataKey.create();
    private static final Identifier FREEZE = Constants.id("textures/entity/status/mark_freeze.png");
    private static final Identifier FREEZE_SPENT = Constants.id("textures/entity/status/mark_freeze_spent.png");
    private static final Identifier FREEZE_OFF = Constants.id("textures/entity/status/mark_freeze_off.png");
    private static final Identifier FREEZE_FLASH = Constants.id("textures/entity/status/mark_freeze_flash.png");
    private static final Identifier FREEZE_RESIST = Constants.id("textures/entity/status/mark_freeze_resist.png");
    private static final Identifier COLD = Constants.id("textures/entity/status/mark_cold.png");
    private static final Identifier COLD_SPENT = Constants.id("textures/entity/status/mark_cold_spent.png");
    private static final Identifier COLD_OFF = Constants.id("textures/entity/status/mark_cold_off.png");
    private static final Identifier HELD = Constants.id("textures/entity/status/mark_held.png");
    private static final Identifier INKED = Constants.id("textures/entity/status/mark_inked.png");
    private static final Identifier INKED_SPENT = Constants.id("textures/entity/status/mark_inked_spent.png");
    private static final Identifier CRACK = Constants.id("textures/entity/status/mark_crack.png");
    private static final Identifier CRACK_SPENT = Constants.id("textures/entity/status/mark_crack_spent.png");
    private static final Identifier CRACK_OFF = Constants.id("textures/entity/status/mark_crack_off.png");
    private static final Identifier EXPOSED = Constants.id("textures/entity/status/mark_exposed.png");
    private static final Identifier EXPOSED_SPENT = Constants.id("textures/entity/status/mark_exposed_spent.png");
    private static final Identifier EXPOSED_FLASH = Constants.id("textures/entity/status/mark_exposed_flash.png");
    private static final Identifier BURN = Constants.id("textures/entity/status/mark_burn.png");
    private static final Identifier BURN_SPENT = Constants.id("textures/entity/status/mark_burn_spent.png");
    /** Half an emblem's fixed world size, the gap between two, and the most one entity shows. */
    private static final float HALF = .28F, GAP = .05F;
    private static final int MAX_EMBLEMS = 3;
    /** The emblem texture is 32 px; its glyph fills rows 4..27 and a charge rises through those. */
    private static final float TEXTURE = 32, GLYPH_TOP = 4, GLYPH_ROWS = 24;
    private static final int WHITE = -1, HURT_TINT = 0xFFFF8A8A;
    private record Row(double x, double y, double z, Marks marks, boolean hurt) {}
    /** Remaining Cold arrives in steps; between two the client counts down itself, so the rim never jumps. */
    private record ColdClock(int steps, long changedAt) {}
    private static final Map<Integer, ColdClock> COLD_CLOCKS = new HashMap<>();

    private CombatMarkBadges() {}

    /** Called from render-state extraction; null when the entity carries no mark. */
    public static Marks read(LivingEntity living, float partialTick) {
        int packed = ((CombatMarkState) living).digicube$marks(), packed2 = ((CombatMarkState) living).digicube$marks2();
        int steps = CombatMarkState.coldRemainingTicks(packed) / CombatMarkState.COLD_STEP_TICKS;
        if (packed == 0 && packed2 == 0 || !living.isAlive()) {
            COLD_CLOCKS.remove(living.getId());
            return null;
        }
        long now = living.level().getGameTime();
        ColdClock clock = COLD_CLOCKS.get(living.getId());
        if (clock == null || clock.steps() != steps) COLD_CLOCKS.put(living.getId(), clock = new ColdClock(steps, now));
        float upper = steps * CombatMarkState.COLD_STEP_TICKS;
        float remaining = Math.max(Math.max(0, upper - CombatMarkState.COLD_STEP_TICKS), upper - (now - clock.changedAt() + partialTick));
        if (COLD_CLOCKS.size() > 256) COLD_CLOCKS.values().removeIf(c -> now - c.changedAt() > 400);
        return new Marks(packed, packed2, steps == 0 ? 0 : remaining);
    }

    public static void init() {
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            var rows = new ArrayList<Row>();
            for (var state : context.levelState().entityRenderStates) {
                Marks marks = ((FabricRenderState) state).getData(MARKS);
                if (marks == null || state.isInvisible || state.distanceToCameraSq > 48 * 48) continue;
                double height = state.boundingBoxHeight + .48;
                if (state.nameTag != null && state.nameTagAttachment != null) {
                    height = Math.max(height, state.nameTagAttachment.y + .65);
                }
                rows.add(new Row(state.x, state.y + height, state.z, marks,
                        state instanceof LivingEntityRenderState living && living.hasRedOverlay));
            }
            // Vanilla clears entityRenderStates before COLLECT_SUBMITS. Keep value snapshots on this frame.
            ((FabricRenderState) context.levelState()).setData(ROWS, List.copyOf(rows));
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            var rows = ((FabricRenderState) context.levelState()).getData(ROWS);
            if (rows == null) return;
            var camera = context.levelState().cameraRenderState;
            var pose = context.poseStack();
            for (var row : rows) {
                int packed = row.marks().packed(), packed2 = row.marks().packed2();
                float charge = CombatMarkState.coldCharge(packed);
                boolean cold = row.marks().coldRemainingTicks() > 0;
                // Build-ups first: they are the marks a tamer can still act on.
                var emblems = new ArrayList<Emblem>();
                float freeze = CombatMarkState.freezeGauge(packed2), frozen = CombatMarkState.frozenRemaining(packed2);
                boolean freezeFlash = CombatMarkState.has(packed2, CombatMarkState.FREEZE_FLASH);
                if (freeze > 0 || frozen > 0 || freezeFlash) emblems.add(Emblem.FREEZE);
                if (cold || charge > 0) emblems.add(Emblem.COLD);
                float cracked = CombatMarkState.crackedRemaining(packed), crackCharge = CombatMarkState.crackCharge(packed);
                if (cracked > 0 || crackCharge > 0) emblems.add(Emblem.CRACK);
                if (CombatMarkState.has(packed, CombatMarkState.HELD)) emblems.add(Emblem.HELD);
                if (CombatMarkState.has(packed, CombatMarkState.INKED)) emblems.add(Emblem.INKED);
                float exposed = CombatMarkState.exposedRemaining(packed2);
                if (exposed > 0) emblems.add(Emblem.EXPOSED);
                float burn = CombatMarkState.burnRemaining(packed2);
                if (burn > 0) emblems.add(Emblem.BURN);
                int count = Math.min(MAX_EMBLEMS, emblems.size());
                // A resistance is drawn small beside the row and never counts toward it.
                boolean resisting = CombatMarkState.has(packed2, CombatMarkState.FROST_RESIST) && freeze <= 0 && frozen <= 0 && !freezeFlash;
                float step = 2 * HALF + GAP;
                pose.pushPose();
                pose.translate(row.x - camera.pos.x, row.y - camera.pos.y, row.z - camera.pos.z);
                pose.mulPose(camera.orientation);
                if (resisting) small(context.submitNodeCollector(), pose, FREEZE_RESIST, count == 0 ? 0 : ((count - 1) / 2F) * step + HALF + GAP + HALF / 2);
                for (int i = 0; i < count; i++) {
                    float centre = (i - (count - 1) / 2F) * step;
                    var collector = context.submitNodeCollector();
                    switch (emblems.get(i)) {
                        case FREEZE -> {
                            // A gauge: the snowflake fills from the bottom; full, the ice closes (a white blink) and the
                            // lit rim drains as it thaws.
                            if (freezeFlash) full(collector, pose, FREEZE_FLASH, centre, WHITE);
                            else if (frozen > 0) {
                                full(collector, pose, FREEZE_SPENT, centre, WHITE);
                                wedge(collector, pose, FREEZE, centre, frozen);
                            } else {
                                full(collector, pose, FREEZE_OFF, centre, WHITE);
                                risen(collector, pose, FREEZE_SPENT, centre, freeze);
                            }
                        }
                        case HELD -> full(collector, pose, HELD, centre, row.hurt() ? HURT_TINT : WHITE);
                        case INKED -> {
                            full(collector, pose, INKED_SPENT, centre, WHITE);
                            wedge(collector, pose, INKED, centre, CombatMarkState.inkRemaining(packed));
                        }
                        case EXPOSED -> {
                            // A critical hit on it blinks the whole emblem white for a few ticks.
                            if (CombatMarkState.has(packed2, CombatMarkState.EXPOSED_FLASH)) {
                                full(collector, pose, EXPOSED_FLASH, centre, WHITE);
                            } else {
                                full(collector, pose, EXPOSED_SPENT, centre, WHITE);
                                wedge(collector, pose, EXPOSED, centre, exposed);
                            }
                        }
                        case BURN -> {
                            // A status: the rim drains as the fire burns down, and goes out with it.
                            full(collector, pose, BURN_SPENT, centre, WHITE);
                            wedge(collector, pose, BURN, centre, burn);
                        }
                        case CRACK -> {
                            // Like Cold: the stone fills charge by charge, then the rim lights and drains with Cracked.
                            if (cracked > 0) {
                                full(collector, pose, CRACK_SPENT, centre, WHITE);
                                wedge(collector, pose, CRACK, centre, cracked);
                            } else {
                                full(collector, pose, CRACK_OFF, centre, WHITE);
                                risen(collector, pose, CRACK_SPENT, centre, crackCharge);
                            }
                        }
                        case COLD -> {
                            if (cold) {
                                full(collector, pose, COLD_SPENT, centre, WHITE);
                                wedge(collector, pose, COLD, centre, row.marks().coldRemainingTicks() / IceCombo.COLD_TICKS);
                            } else {
                                full(collector, pose, COLD_OFF, centre, WHITE);
                                risen(collector, pose, COLD_SPENT, centre, charge);
                            }
                        }
                    }
                }
                pose.popPose();
            }
        });
    }

    private enum Emblem { FREEZE, COLD, CRACK, HELD, INKED, EXPOSED, BURN }

    /** A half-size emblem (a resistance) centred at {@code centre}, a little lower than the row. */
    private static void small(SubmitNodeCollector collector, PoseStack pose, Identifier texture, float centre) {
        float h = HALF / 2, drop = -HALF / 2;
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (matrix, vertices) -> {
            smallVertex(matrix, vertices, centre - h, drop - h, 0, 0, 1);
            smallVertex(matrix, vertices, centre + h, drop - h, 0, 1, 1);
            smallVertex(matrix, vertices, centre + h, drop + h, 0, 1, 0);
            smallVertex(matrix, vertices, centre - h, drop + h, 0, 0, 0);
        });
    }

    private static void smallVertex(PoseStack.Pose pose, VertexConsumer vertices, float x, float y, float z, float u, float v) {
        vertices.addVertex(pose, x, y, z).setColor(WHITE).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
    }

    /** A small fixed world size; depth-tested so an emblem never reveals mobs through walls. */
    private static void full(SubmitNodeCollector collector, PoseStack pose, Identifier texture, float centre, int color) {
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (matrix, vertices) -> {
            vertex(matrix, vertices, centre, -HALF, -HALF, 0, color);
            vertex(matrix, vertices, centre, HALF, -HALF, 0, color);
            vertex(matrix, vertices, centre, HALF, HALF, 0, color);
            vertex(matrix, vertices, centre, -HALF, HALF, 0, color);
        });
    }

    /** The lit texture over the unlit one, from the bottom up to the charge, on whole texture rows. */
    private static void risen(SubmitNodeCollector collector, PoseStack pose, Identifier texture, float centre, float charge) {
        float top = (TEXTURE - GLYPH_TOP - Math.round(GLYPH_ROWS * charge)) / TEXTURE * 2 * HALF;
        float y = HALF - top;
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (matrix, vertices) -> {
            vertex(matrix, vertices, centre, -HALF, -HALF, OVER, WHITE);
            vertex(matrix, vertices, centre, HALF, -HALF, OVER, WHITE);
            vertex(matrix, vertices, centre, HALF, y, OVER, WHITE);
            vertex(matrix, vertices, centre, -HALF, y, OVER, WHITE);
        });
    }

    /** The lit texture over the spent one as a pie: the rim drains clockwise from the top as the timer runs. */
    private static void wedge(SubmitNodeCollector collector, PoseStack pose, Identifier texture, float centre, float remaining) {
        float start = (1 - Math.min(1, Math.max(0, remaining))) * 360;
        if (start >= 360) return;
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (matrix, vertices) -> {
            // One quad per stretch of a single square edge; corners sit at 45 + 90k degrees.
            float from = start;
            while (from < 360) {
                float to = Math.min(360, (float) (Math.floor((from + 45) / 90) * 90 + 45));
                if (to <= from) to = Math.min(360, from + 90);
                float middle = (from + to) / 2;
                // Counter-clockwise as seen by the camera, like the full quad.
                vertex(matrix, vertices, centre, 0, 0, OVER, WHITE);
                rimVertex(matrix, vertices, centre, to);
                rimVertex(matrix, vertices, centre, middle);
                rimVertex(matrix, vertices, centre, from);
                from = to;
            }
        });
    }

    /** Toward the camera, clear of the base quad's depth. */
    private static final float OVER = .004F;

    /** The point of the emblem's square outline at a clockwise angle from straight up. */
    private static void rimVertex(PoseStack.Pose pose, VertexConsumer vertices, float centre, float degrees) {
        double radians = Math.toRadians(degrees);
        double dx = Math.sin(radians), dy = Math.cos(radians);
        double scale = HALF / Math.max(Math.abs(dx), Math.abs(dy));
        vertex(pose, vertices, centre, (float) (dx * scale), (float) (dy * scale), OVER, WHITE);
    }

    /** Texture coordinates follow the position, so any sub-shape shows exactly its own part of the emblem. */
    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, float centre, float x, float y, float z, int color) {
        vertices.addVertex(pose, centre + x, y, z).setColor(color).setUv((x + HALF) / (2 * HALF), 1 - (y + HALF) / (2 * HALF))
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
    }
}
