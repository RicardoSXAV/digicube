package com.digicube.digimon;

/**
 * Evolution stages, in the canonical order. Japanese names are the source of truth
 * because they are unambiguous; the English dub name is kept for display and search.
 */
public enum DigimonStage {

    BABY_I("baby_i", "Fresh", 0),
    BABY_II("baby_ii", "In-Training", 1),
    CHILD("child", "Rookie", 2),
    ADULT("adult", "Champion", 3),
    PERFECT("perfect", "Ultimate", 4),
    ULTIMATE("ultimate", "Mega", 5),
    SUPER_ULTIMATE("super_ultimate", "Ultra", 6),
    /** Off the main line: reached with a Digi-Egg rather than by levelling. */
    ARMOR("armor", "Armor", -1),
    /** Off the main line: Spirit Evolution. */
    HYBRID("hybrid", "Hybrid", -1);

    private final String id;
    private final String dubName;
    private final int tier;

    DigimonStage(String id, String dubName, int tier) {
        this.id = id;
        this.dubName = dubName;
        this.tier = tier;
    }

    /** Stable string used in JSON and NBT. Never change these without a data migration. */
    public String getId() {
        return id;
    }

    public String getDubName() {
        return dubName;
    }

    /** Position on the main evolution line, or -1 for side lines (Armor, Hybrid). */
    public int getTier() {
        return tier;
    }

    public boolean isMainLine() {
        return tier >= 0;
    }

    public static DigimonStage byId(String id) {
        for (DigimonStage stage : values()) {
            if (stage.id.equals(id)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("Unknown Digimon stage: " + id);
    }
}
