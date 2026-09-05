package com.digicube.digimon;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * One move a species can use in combat -- the shared, immutable definition.
 *
 * <p>A species lists its attacks in <b>priority order</b>: in a fight the entity walks the
 * list and uses the first attack that is off cooldown and in range, so put the big move
 * first and the basic one last (Agumon: Pepper Breath, then Claw).
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
 */
public record DigimonAttack(
        Identifier id,
        Kind kind,
        float power,
        int cooldownTicks,
        int durationTicks,
        int hitTick,
        double range,
        boolean alternateSides
) {

    public DigimonAttack {
        Objects.requireNonNull(id, "attack id");
        Objects.requireNonNull(kind, "attack kind");
        if (hitTick < 0 || hitTick >= durationTicks) {
            throw new IllegalArgumentException(id + ": hitTick must lie inside the animation");
        }
    }

    /** How an attack reaches its target. */
    public enum Kind {
        /** Damage applied directly when the target is within melee reach on the hit tick. */
        MELEE,
        /** A {@link com.digicube.entity.PepperBreathEntity} fireball launched on the hit tick. */
        FIREBALL,
        /** A non-burning bubble volley launched on the hit tick. */
        BUBBLES
    }

    /** Harness animation name for this attack, e.g. {@code claw} or {@code claw_mirrored}. */
    public String animationName(boolean mirrored) {
        return mirrored ? id.getPath() + "_mirrored" : id.getPath();
    }

    public boolean isRanged() {
        return kind != Kind.MELEE;
    }
}
