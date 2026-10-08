package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import com.digicube.entity.ShellSpin;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;

/**
 * Client: the whirr of a shell spinning (ShellSpin; Drill Shell), on every client from the spin it sees: a grinding hum
 * that rises in pitch and loudness as the shell spins up in place, roars with the spin's speed once it is let go, and
 * sinks away as it winds down. Out of hearing, or once the body comes out, it fades.
 */
public final class SpinAudio {
    private static final double RANGE_SQUARED = 40 * 40;
    private static final Map<Integer, Whirr> LOOPS = new HashMap<>();
    private static ClientLevel level;

    private SpinAudio() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SpinAudio::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear(client));
    }

    private static void clear(Minecraft client) {
        for (var loop : LOOPS.values()) client.getSoundManager().stop(loop);
        LOOPS.clear();
        level = null;
    }

    private static void tick(Minecraft client) {
        if (client.level != level) { clear(client); level = client.level; }
        if (level == null || client.player == null || client.isPaused()) return;
        LOOPS.values().removeIf(loop -> {
            var e = loop.entity;
            boolean live = e.isAlive() && !e.isRemoved() && e.level() == level && e.distanceToSqr(client.player) <= RANGE_SQUARED && spinning(e);
            if (live) return false;
            loop.release();
            return true;
        });
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof DigimonEntity digimon) || LOOPS.containsKey(digimon.getId()) || !spinning(digimon)
                    || digimon.distanceToSqr(client.player) > RANGE_SQUARED) continue;
            var loop = new Whirr(digimon);
            client.getSoundManager().play(loop);
            LOOPS.put(digimon.getId(), loop);
        }
    }

    private static boolean spinning(DigimonEntity e) {
        int code = e.spinCode();
        if (code == 0) return false;
        var phase = ShellSpin.phase(code);
        return phase == ShellSpin.Phase.CHARGE || phase == ShellSpin.Phase.SPIN || phase == ShellSpin.Phase.WIND_DOWN;
    }

    /** The loop, following the shell; its pitch and volume ride the spin's rate. */
    private static final class Whirr extends AbstractTickableSoundInstance {
        private final DigimonEntity entity;
        private int fade = -1;

        Whirr(DigimonEntity entity) {
            super(SoundEvents.MINECART_RIDING, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.entity = entity;
            looping = true;
            delay = 0;
            volume = .01F;
            pitch = .5F;
            follow();
        }

        void release() { if (fade < 0) fade = 4; }

        private void follow() { x = entity.getX(); y = entity.getY() + .6; z = entity.getZ(); }

        @Override public boolean canStartSilent() { return true; }

        @Override public void tick() {
            if (fade == 0) { stop(); return; }
            if (fade > 0) fade--;
            if (!entity.isRemoved()) follow();
            // degrees a tick: about 6 starting up, 52 spun up, 64 at full speed
            float rate = Mth.clamp(entity.getSpinRate() / 64F, 0, 1);
            float out = fade < 0 ? 1 : fade / 4F;
            volume = (.15F + .95F * rate) * out;
            pitch = .55F + 1.1F * rate;
        }
    }
}
