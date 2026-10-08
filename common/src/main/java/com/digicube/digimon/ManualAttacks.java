package com.digicube.digimon;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Universal control: the attacks a Digimon keeps for its tamer's orders (AUTO off). The AI never picks them on its
 * own; every other attack is AUTO, the default. The set is saved in the entity's own data as a list of attack ids, so
 * it lives through recall, the Digispace and a reload, and the settings of attacks a new form keeps survive a
 * Digivolution while its new attacks start AUTO. The command wheel reads it as a mask over the sheet's slots.
 */
public final class ManualAttacks {
    private ManualAttacks() {}

    /** Entity data key of the list. Changing it is a save-data migration. */
    public static final String TAG = "ManualAttacks";
    public static final Codec<List<Identifier>> CODEC = Identifier.CODEC.listOf();

    /** The manual attack ids saved in {@code entity} (a stored partner's data); empty when it has none. */
    public static Set<Identifier> read(CompoundTag entity) {
        Tag saved = entity.get(TAG);
        if (saved == null) return new LinkedHashSet<>();
        return new LinkedHashSet<>(CODEC.parse(NbtOps.INSTANCE, saved).result().orElse(List.of()));
    }

    /** Saves {@code manual} into {@code entity}; an empty set removes the key. */
    public static void write(CompoundTag entity, Set<Identifier> manual) {
        if (manual.isEmpty()) {
            entity.remove(TAG);
            return;
        }
        entity.put(TAG, CODEC.encodeStart(NbtOps.INSTANCE, List.copyOf(manual)).getOrThrow());
    }

    /** One bit per sheet slot of {@code attacks}, set where the attack is manual. */
    public static int mask(List<DigimonAttack> attacks, Set<Identifier> manual) {
        int mask = 0;
        for (int slot = 0; slot < Math.min(attacks.size(), Integer.SIZE); slot++) {
            if (manual.contains(attacks.get(slot).id())) mask |= 1 << slot;
        }
        return mask;
    }

    /** Whether slot {@code slot} is manual in {@code mask}. */
    public static boolean manual(int mask, int slot) {
        return slot >= 0 && slot < Integer.SIZE && (mask >>> slot & 1) != 0;
    }
}
