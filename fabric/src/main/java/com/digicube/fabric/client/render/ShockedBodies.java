package com.digicube.fabric.client.render;

import com.digicube.entity.KineticProjectileEntity;
import com.digicube.entity.ShotStyle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.digicube.fabric.client.render.GlowRods.mix;
import static com.digicube.fabric.client.render.GlowRods.next;
import static com.digicube.fabric.client.render.GlowRods.unit;

/**
 * A body an electric shot shocked (a direct hit, or a shocking ball's spark: the bodies {@link KineticProjectileEntity}
 * syncs as its bolts) crackles with lightning for {@link #TICKS} ticks: short arcs jumping between points round its
 * outline, in the ball's colours, dealt again several times a tick and thinning away. Every client sees the shots' bolts,
 * so every client draws it ({@code MixinEntityRenderer} carries it in the body's render state,
 * {@code MixinEntityRenderDispatcher} draws it right after the body).
 */
public final class ShockedBodies {
    private ShockedBodies() {}

    /** Ticks a shocked body crackles. */
    public static final int TICKS = 12;
    /** A shocked body as drawn this frame: ticks since the shock, and a seed of its own. */
    public record Shocked(float age, int seed) {}

    public static final RenderStateDataKey<Shocked> SHOCKED = RenderStateDataKey.create();
    /** Game tick each body was last shocked, by entity id. */
    private static final Map<Integer, Long> SINCE = new HashMap<>();
    /** The (shot, body) bolts seen last tick: a new one is a new shock. */
    private static Set<Long> seen = new HashSet<>();
    private static ClientLevel lastLevel;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ShockedBodies::tick);
    }

    private static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level != lastLevel) { SINCE.clear(); seen.clear(); lastLevel = level; }
        if (level == null || minecraft.isPaused()) return;
        long now = level.getGameTime();
        SINCE.values().removeIf(at -> now - at > TICKS);
        Set<Long> bolts = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof KineticProjectileEntity shot) || shot.definition() == null || shot.definition().shotStyle() != ShotStyle.ELECTRIC) continue;
            for (int body : shot.bolts().keySet()) {
                long key = (long) shot.getId() << 32 | body & 0xFFFFFFFFL;
                bolts.add(key);
                if (!seen.contains(key)) SINCE.put(body, now);
            }
        }
        seen = bolts;
    }

    /** {@code living}'s shock as drawn this frame, or null when it is not crackling. */
    public static Shocked shocked(LivingEntity living, float partial) {
        Long at = SINCE.get(living.getId());
        if (at == null) return null;
        float age = living.level().getGameTime() - at + partial;
        return age > TICKS ? null : new Shocked(age, living.getId());
    }

    /** Draws a shocked body's crackle about its feet ({@code pose} as the dispatcher placed the body). */
    public static void submit(EntityRenderState state, Shocked shocked, PoseStack pose, SubmitNodeCollector collector) {
        float w = state.boundingBoxWidth, h = state.boundingBoxHeight;
        float fade = Mth.clamp((TICKS - shocked.age()) / 4F, 0, 1);
        int arcs = Mth.clamp(Math.round(3 + 1.5F * (w + h)), 4, 9);
        var rods = new GlowRods();
        for (int i = 0; i < arcs; i++) {
            long rng = mix(shocked.seed(), i, (int) (shocked.age() * 2.5F));
            float[] a = onOutline(w, h, rng), b;
            rng = next(next(next(next(rng))));
            // the far end a short jump away, still on the outline
            b = onOutline(w, h, rng);
            float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], l = Mth.sqrt(dx * dx + dy * dy + dz * dz), most = .5F + .25F * (w + h) * .5F;
            if (l > most) { b[0] = a[0] + dx / l * most; b[1] = a[1] + dy / l * most; b[2] = a[2] + dz / l * most; }
            rng = rods.bolt(a[0], a[1], a[2], b[0], b[1], b[2], .05F * fade, ShockBall.CORE, .14F, .16F, rng, 1, ShockBall.MINT);
            rods.bolt(a[0], a[1], a[2], b[0], b[1], b[2], .028F * fade, ShockBall.MAGENTA, .18F, .16F, rng, 0, 0);
        }
        rods.submit(pose, collector);
    }

    /** A point just outside a body of that width and height (from its feet), its sides more than its top. */
    private static float[] onOutline(float w, float h, long rng) {
        rng = next(rng); float side = unit(rng);
        rng = next(rng); float u = unit(rng);
        rng = next(rng); float y = (unit(rng) * .5F + .5F) * h;
        float half = w * .55F;
        if (side < -.5F) return new float[]{-half, y, u * half};
        if (side < 0) return new float[]{half, y, u * half};
        if (side < .5F) return new float[]{u * half, y, -half};
        return new float[]{u * half, y, half};
    }
}
