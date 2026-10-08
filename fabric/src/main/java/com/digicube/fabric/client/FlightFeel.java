package com.digicube.fabric.client;

import com.digicube.entity.DigimonEntity;
import com.digicube.entity.FlightLook;
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
 * Client: what flying feels like. Every agile flyer in hearing buzzes its wings (a loop that follows it, deeper and slower
 * for the big ones, louder and higher as the wings work harder). The local rider of an agile flyer ({@code
 * AerialMount.Agility}) also gets the air: a rush of wind that grows with the speed, the view widening as it picks up,
 * shuddering past the dive's top speeds and punching out as it breaks past the burst, tilting into the body's banks (a
 * share of them, eased), swinging through a barrel roll, and a whoosh as the roll begins.
 */
public final class FlightFeel {
    private FlightFeel() {}

    /** Blocks within which a flyer's wings are heard. */
    private static final double RANGE_SQUARED = 36 * 36;
    /** Share of the body's bank the view tilts by, and the most it tilts (degrees). */
    private static final float ROLL_SHARE = .3F, ROLL_MOST = 14;
    private static final Map<Integer, Wings> wings = new HashMap<>();
    private static Wind wind;
    private static ClientLevel level;
    private static float roll, previousRoll, fov, previousFov, shake;
    private static int seenRolls = -1;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(FlightFeel::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear(client));
    }

    private static void clear(Minecraft client) {
        for (var w : wings.values()) client.getSoundManager().stop(w);
        wings.clear();
        if (wind != null) client.getSoundManager().stop(wind);
        wind = null;
        level = null;
    }

    private static void tick(Minecraft client) {
        if (client.level != level) { clear(client); level = client.level; }
        previousRoll = roll;
        previousFov = fov;
        if (level == null || client.player == null || client.isPaused()) return;
        wings.values().removeIf(w -> w.isStopped());
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof DigimonEntity flyer) || flyer.aerialMount() == null || flyer.aerialMount().agility() == null || !flyer.getFlightPhase().airborne()
                    || wings.containsKey(flyer.getId()) || flyer.distanceToSqr(client.player) > RANGE_SQUARED) continue;
            var w = new Wings(flyer);
            wings.put(flyer.getId(), w);
            client.getSoundManager().play(w);
        }
        DigimonEntity mount = client.player.getVehicle() instanceof DigimonEntity d && d.aerialMount() != null
                && d.aerialMount().agility() != null ? d : null;
        float wantRoll = 0, wantFov = 0, wantShake = 0;
        if (mount != null && mount.getFlightPhase().airborne()) {
            var look = mount.flightLook();
            double cruise = mount.aerialMount().cruiseSpeed();
            float speed = look.speed(1);
            wantRoll = Mth.clamp(look.bank(1) * ROLL_SHARE, -ROLL_MOST, ROLL_MOST);
            wantFov = (float) Mth.clamp((speed - cruise * .9) / (mount.aerialMount().agility().maxSpeed() - cruise) * .24, 0, .24);
            if (look.boomAge() < 8) wantFov += .08F * (1 - look.boomAge() / 8F);
            wantShake = (float) Mth.clamp((speed - FlightLook.BOOM * .8) * 1.4, 0, .6);
            if (wind == null || wind.isStopped()) { wind = new Wind(mount); client.getSoundManager().play(wind); }
            int rolls = mount.rolls();
            if (seenRolls >= 0 && rolls != seenRolls)
                level.playLocalSound(mount.getX(), mount.getY(), mount.getZ(), SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.NEUTRAL, .8F, 1.25F, false);
            seenRolls = rolls;
        } else seenRolls = mount == null ? -1 : mount.rolls();
        roll = Mth.lerp(.18F, roll, wantRoll);
        fov = Mth.lerp(wantFov > fov ? .25F : .12F, fov, wantFov);
        shake = Mth.lerp(.3F, shake, wantShake);
    }

    /**
     * Degrees the rider's view tilts this frame (+ its left side down, as the body's bank): a share of the body's bank,
     * and through a barrel roll a swing toward it and back.
     */
    public static float cameraRoll(float partial) {
        var minecraft = Minecraft.getInstance();
        float tilt = Mth.lerp(partial, previousRoll, roll);
        if (minecraft.player != null && minecraft.player.getVehicle() instanceof DigimonEntity mount && mount.aerialMount() != null && mount.aerialMount().agility() != null) {
            float spin = mount.getSwimRoll(partial);
            if (spin != 0) tilt += 22 * Mth.sin(Mth.DEG_TO_RAD * spin * .5F) * Math.signum(spin);
        }
        return tilt;
    }

    /** The view's widening with the flight's speed (a share to add to the field of view). */
    public static float fov(float partial) { return Mth.lerp(partial, previousFov, fov); }

    /** The view's shudder at the dive's top speeds (degrees, a fine high shake). */
    public static float[] shake(float partial) {
        if (shake < .01F) return null;
        float t = (Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.tickCount) + partial;
        return new float[]{shake * Mth.sin(t * 7.3F) * .6F, shake * Mth.cos(t * 9.1F) * .5F};
    }

    /**
     * A flyer's wings: a buzz that follows it, its pitch and its loudness by how hard the wings work, hushed as they fold
     * for a dive or are swept back through a pounce (a horn's ram: the strike is heard, not the wings).
     */
    private static final class Wings extends AbstractTickableSoundInstance {
        private final DigimonEntity flyer;
        private final float depth;
        private float hush;

        Wings(DigimonEntity flyer) {
            super(SoundEvents.BEE_LOOP, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.flyer = flyer;
            this.looping = true;
            this.delay = 0;
            this.volume = 0;
            // a big body's wings beat deeper
            this.depth = (float) Mth.clamp(1.2 - flyer.getBbHeight() * .17, .45, 1);
            this.x = flyer.getX(); this.y = flyer.getY(); this.z = flyer.getZ();
        }

        @Override public void tick() {
            if (flyer.isRemoved() || !flyer.isAlive() || !flyer.getFlightPhase().airborne()) { stop(); return; }
            this.x = flyer.getX(); this.y = flyer.getY() + flyer.getBbHeight() * .6; this.z = flyer.getZ();
            var look = flyer.flightLook();
            float power = look.power(1), dive = look.dive(1);
            var attack = flyer.getAnimatingAttack();
            boolean ram = attack != null && attack.kind() == com.digicube.digimon.DigimonAttack.Kind.POUNCE && flyer.attackAnimationState.isStarted()
                    && flyer.attackAnimationState.getTimeInMillis(flyer.tickCount) / 50F < attack.durationTicks();
            hush = Mth.lerp(.45F, hush, ram ? 1 : 0);
            this.pitch = depth * (.85F + .45F * power) * (1 - .25F * dive);
            this.volume = Mth.clamp((.35F + .55F * power) * (1 - .6F * dive) * (1 - .85F * hush), 0, 1);
        }

        @Override public boolean canStartSilent() { return true; }
    }

    /** The rush of air past the local rider: louder and higher with the speed. */
    private static final class Wind extends AbstractTickableSoundInstance {
        private final DigimonEntity mount;

        Wind(DigimonEntity mount) {
            super(SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.mount = mount;
            this.looping = true;
            this.delay = 0;
            this.volume = 0;
            this.relative = true;
        }

        @Override public void tick() {
            var player = Minecraft.getInstance().player;
            if (mount.isRemoved() || player == null || player.getVehicle() != mount || !mount.getFlightPhase().airborne()) { stop(); return; }
            float speed = mount.flightLook().speed(1);
            double cruise = mount.aerialMount().cruiseSpeed();
            float share = (float) Mth.clamp((speed - cruise * .5) / (mount.aerialMount().agility().maxSpeed() - cruise * .5), 0, 1);
            this.volume = share * share * .9F;
            this.pitch = .7F + .6F * share;
        }

        @Override public boolean canStartSilent() { return true; }
    }
}
