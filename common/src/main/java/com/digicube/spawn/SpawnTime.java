package com.digicube.spawn;

/** When a spawn entry is active. Ids are stable JSON values. */
public enum SpawnTime {

    ANY("any"),
    DAY("day"),
    NIGHT("night");

    private final String id;

    SpawnTime(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /**
     * @param bright the level reports daylight
     * @param dark   the level reports night
     * @return whether the entry is active; at dusk neither day nor night entries are
     */
    public boolean matches(boolean bright, boolean dark) {
        return switch (this) {
            case ANY -> true;
            case DAY -> bright;
            case NIGHT -> dark;
        };
    }

    public static SpawnTime byId(String id) {
        for (SpawnTime time : values()) {
            if (time.id.equals(id)) return time;
        }
        throw new IllegalArgumentException("Unknown spawn time: " + id);
    }
}
