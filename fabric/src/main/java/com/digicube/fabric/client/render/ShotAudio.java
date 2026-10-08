package com.digicube.fabric.client.render;

import com.digicube.entity.KineticProjectileEntity;
import com.digicube.entity.ShotStyle;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Client: what an electric ball sounds like as it flies (a kinetic shot with {@code "shot_style": "electric"}; its
 * report, its shocks and its burst are the server's, {@link ShotStyle}): a hum that follows it, fading in as it leaves
 * the hands and cut as it bursts, and a crackle where it earths a bolt in the ground under it, on the same ticks its
 * lightning shows one ({@link ShockBall#earths}). Every client plays its own, from the balls it flies.
 */
public final class ShotAudio {
    /** Blocks within which a ball is heard. */
    private static final double RANGE_SQUARED = 40 * 40;
    private final Map<Integer, Hum> hums = new HashMap<>();
    private ClientLevel level;

    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear(client));
    }

    private void clear(Minecraft client) {
        for (var hum : hums.values()) client.getSoundManager().stop(hum);
        hums.clear();
        level = null;
    }

    private void tick(Minecraft client) {
        if (client.level != level) { clear(client); level = client.level; }
        if (level == null || client.player == null || client.isPaused()) return;
        hums.values().removeIf(Hum::isStopped);
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof KineticProjectileEntity ball) || ball.definition() == null || ball.definition().shotStyle() != ShotStyle.ELECTRIC
                    || ball.impacting() || ball.distanceToSqr(client.player) > RANGE_SQUARED) continue;
            if (!hums.containsKey(ball.getId())) {
                var hum = new Hum(ball);
                hums.put(ball.getId(), hum);
                client.getSoundManager().play(hum);
            }
            if (!ShockBall.earths(ball.getId(), ball.tickCount)) continue;
            var at = ball.position();
            var ground = level.clip(new ClipContext(at, at.add(0, -ShockBall.EARTH_REACH, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, ball));
            if (ground.getType() == HitResult.Type.MISS) continue;
            var spot = ground.getLocation();
            level.playLocalSound(spot.x, spot.y, spot.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.NEUTRAL, .35F, 1.9F + ball.getRandom().nextFloat() * .2F, false);
        }
    }

    /** The ball's hum, following it. */
    private static final class Hum extends AbstractTickableSoundInstance {
        private final KineticProjectileEntity ball;
        private int age;

        Hum(KineticProjectileEntity ball) {
            super(SoundEvents.BEACON_AMBIENT, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.ball = ball;
            this.looping = true;
            this.delay = 0;
            this.volume = 0;
            this.pitch = 1.7F;
            this.x = ball.getX(); this.y = ball.getY(); this.z = ball.getZ();
        }

        @Override public void tick() {
            if (ball.isRemoved() || ball.impacting()) { stop(); return; }
            age++;
            this.x = ball.getX(); this.y = ball.getY(); this.z = ball.getZ();
            this.volume = Mth.clamp(age / 4F, 0, 1) * .9F;
            this.pitch = 1.65F + .1F * Mth.sin(age * .9F);
        }

        @Override public boolean canStartSilent() { return true; }
    }
}
