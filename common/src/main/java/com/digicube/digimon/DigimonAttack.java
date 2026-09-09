package com.digicube.digimon;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * One move a species can use in combat -- the shared, immutable definition.
 *
 * <p>Species order is the fallback priority for ready, reachable moves. A frost bite
 * paired with a frost stream instead plans a combo from target status, fuel and range.
 *
 * <p>Timing is in ticks (20 per second). The animation on the client is looked up by
 * {@code id().getPath()} (plus {@code _mirrored} for the alternate side), so the harness
 * animation must carry the same name as the attack id.
 *
 * @param id             unique id, e.g. {@code digicube:pepper_breath}
 * @param kind           how the hit is delivered
 * @param power          multiplier on the entity's attack-damage attribute
 * @param cooldownTicks  ticks from the start of one use until the next one may start
 * @param durationTicks  length of the animation; the entity holds still for this long
 * @param hitTick        tick within the animation when the damage lands / projectile leaves
 * @param range          blocks; melee uses the vanilla reach test instead
 * @param alternateSides whether consecutive uses mirror the animation (left claw, right claw)
 * @param motion         optional Blender-exported origin, contact and movement profile
 * @param fuel           fuel timing for a sustained attack, otherwise null
 * @param knockback      extra impulse for horn contact; zero also suppresses vanilla hurt knockback
 */
public record DigimonAttack(
        Identifier id,
        Kind kind,
        float power,
        int cooldownTicks,
        int durationTicks,
        int hitTick,
        double range,
        boolean alternateSides,
        AttackMotion motion,
        AttackFuel fuel,
        double knockback
) {

    public DigimonAttack {
        Objects.requireNonNull(id, "attack id");
        Objects.requireNonNull(kind, "attack kind");
        if (hitTick < 0 || hitTick >= durationTicks) {
            throw new IllegalArgumentException(id + ": hitTick must lie inside the animation");
        }
        if ((fuel == null ? cooldownTicks < durationTicks : cooldownTicks != 0)
                || !Float.isFinite(power) || power <= 0 || !Double.isFinite(range) || range < 0
                || !Double.isFinite(knockback) || knockback < 0) {
            throw new IllegalArgumentException(id + ": invalid power, cooldown or range");
        }
        if ((kind == Kind.FLAME_SHOT || kind == Kind.HORN_RAM || kind == Kind.FLAME_STREAM || kind == Kind.WATER_WAVE
                || kind == Kind.FROST_BITE || kind == Kind.FROST_STREAM)
                && (motion == null || motion.frames().size() != durationTicks * motion.samplesPerTick() + 1)) {
            throw new IllegalArgumentException(id + ": missing or mismatched Blender motion");
        }
        if ((kind == Kind.FLAME_STREAM || kind == Kind.FROST_STREAM) != (fuel != null)
                || fuel != null && (motion.activeFrom() != hitTick
                || motion.activeUntil() - motion.activeFrom() + 1 != fuel.capacityTicks())) {
            throw new IllegalArgumentException(id + ": fuel must match the sustained motion interval");
        }
    }

    /** Authored one-shot moves retain their original impulse. */
    public DigimonAttack(Identifier id, Kind kind, float power, int cooldownTicks, int durationTicks,
                         int hitTick, double range, boolean alternateSides, AttackMotion motion) {
        this(id, kind, power, cooldownTicks, durationTicks, hitTick, range, alternateSides, motion,
                null, kind == Kind.HORN_RAM ? 1.1 : 0.0);
    }

    /** Existing moves without authored contact trajectories. */
    public DigimonAttack(Identifier id, Kind kind, float power, int cooldownTicks, int durationTicks,
                         int hitTick, double range, boolean alternateSides) {
        this(id, kind, power, cooldownTicks, durationTicks, hitTick, range, alternateSides, null);
    }

    /** How an attack reaches its target. */
    public enum Kind {
        /** Damage applied directly when the target is within melee reach on the hit tick. */
        MELEE,
        /** A {@link com.digicube.entity.PepperBreathEntity} fireball launched on the hit tick. */
        FIREBALL,
        /** A non-burning bubble volley launched on the hit tick. */
        BUBBLES,
        /** A large animated flame shot with an impact burst. */
        FLAME_SHOT,
        /** Collision-safe forward movement and swept contact along the authored horn. */
        HORN_RAM,
        /** Continuous non-burning flame, paid for with a per-entity fuel reserve. */
        FLAME_STREAM,
        /** A broad homing wave carrying fish, with a single low-damage knockback impact. */
        WATER_WAVE,
        /** Swept fang contact and a collision-safe lunge that applies an ice mark. */
        FROST_BITE,
        /** Fueled ice flames which convert a mark after sustained contact. */
        FROST_STREAM
    }

    /** Harness animation name for this attack, e.g. {@code claw} or {@code claw_mirrored}. */
    public String animationName(boolean mirrored) {
        return mirrored ? id.getPath() + "_mirrored" : id.getPath();
    }

    public boolean isRanged() {
        return kind == Kind.FIREBALL || kind == Kind.BUBBLES || kind == Kind.FLAME_SHOT
                || kind == Kind.FLAME_STREAM || kind == Kind.FROST_STREAM || kind == Kind.WATER_WAVE;
    }

    /** Whole-body attacks hold a common visual and physical facing. */
    public boolean locksBodyFacing() {
        return kind == Kind.BUBBLES || motion != null;
    }
}
