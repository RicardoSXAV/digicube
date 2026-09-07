package com.digicube.spawn;

/** Where a spawn entry may put a wild Digimon. Ids are stable JSON values. */
public enum SpawnPlacement {

    /** On the surface, on a block that would accept an animal. */
    LAND("land"),
    /** Submerged, with at least two blocks of water. */
    WATER("water");

    private final String id;

    SpawnPlacement(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static SpawnPlacement byId(String id) {
        for (SpawnPlacement placement : values()) {
            if (placement.id.equals(id)) return placement;
        }
        throw new IllegalArgumentException("Unknown spawn placement: " + id);
    }
}
