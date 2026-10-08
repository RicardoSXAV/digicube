package com.digicube.digimon;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * A blow that throws its victim ({@code "launch": {"speed": s, "lift": l}} on a pounce or a kinetic shot): in place of
 * vanilla's knockback, which pushes about half a block and lifts at most 0.4 blocks a tick and only off the ground, the
 * victim leaves along the blow at {@code speed} blocks a tick and up at {@code lift} (its own way, and any push the hit gave
 * it, are replaced; a faster rise of its own is kept), both less its knockback resistance. The motion is synced at once, so
 * a player flies as far as a mob does.
 * @param speed blocks a tick along the blow (level)
 * @param lift  blocks a tick up
 */
public record Launch(float speed, float lift) {
    public Launch {
        if (!(speed >= 0 && speed <= 4 && lift >= 0 && lift <= 3 && speed + lift > 0)) throw new IllegalArgumentException("Invalid launch");
    }

    /** The {@code launch} of a catalog entry, or null without one. */
    public static Launch parse(JsonObject entry) {
        if (!entry.has("launch")) return null;
        var launch = GsonHelper.getAsJsonObject(entry, "launch");
        return new Launch(GsonHelper.getAsFloat(launch, "speed"), GsonHelper.getAsFloat(launch, "lift", 0));
    }

    /** The launch a kinetic shot's hit throws its victim with, or null (a pounce's is its own, {@link PounceAttacks.Spec#launch}). */
    public static Launch of(DigimonAttack attack) {
        var shot = KineticAttacks.get(attack);
        return shot == null ? null : shot.launch();
    }

    /**
     * Throws {@code victim} along {@code along} (its level heading) and up, times {@code share} (a shot's falloff) and less its
     * knockback resistance. {@code before} is its motion before the blow landed: its own rise, if faster, is kept.
     */
    public void apply(LivingEntity victim, Vec3 along, double share, Vec3 before) {
        double k = share * (1 - Mth.clamp(victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1));
        if (k <= 0) return;
        Vec3 level = new Vec3(along.x, 0, along.z);
        level = level.lengthSqr() < 1.0E-8 ? Vec3.ZERO : level.normalize();
        victim.setDeltaMovement(level.x * speed * k, Math.max(before.y, lift * k), level.z * speed * k);
        // The server sends the motion this tick: a player's own client takes it (hurtMarked), a mob's watchers too.
        victim.hurtMarked = true;
        victim.needsSync = true;
    }
}
