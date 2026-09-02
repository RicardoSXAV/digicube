package com.digicube.digimon;

import net.minecraft.resources.ResourceLocation;

/**
 * One possible digivolution from a species into another.
 *
 * <p>A species owns a list of these. At evolution time we walk the list and take
 * the first entry whose conditions are all satisfied, so ORDER MATTERS: put the
 * most specific / rarest branches first.
 *
 * @param target      species this evolves into, e.g. {@code digicube:greymon}
 * @param minLevel    minimum level of the partner Digimon
 * @param minBond     minimum bond with the tamer, 0-100
 * @param maxWeight   maximum weight in "g"; -1 disables the check
 * @param minTraining minimum accumulated training points; 0 disables the check
 * @param requiredItem item the tamer must be holding, or null for none
 */
public record Evolution(
        ResourceLocation target,
        int minLevel,
        int minBond,
        int maxWeight,
        int minTraining,
        ResourceLocation requiredItem
) {

    /** A plain level-gated evolution with no extra conditions. */
    public static Evolution atLevel(ResourceLocation target, int minLevel) {
        return new Evolution(target, minLevel, 0, -1, 0, null);
    }

    public boolean hasItemRequirement() {
        return requiredItem != null;
    }

    public boolean hasWeightRequirement() {
        return maxWeight >= 0;
    }
}
