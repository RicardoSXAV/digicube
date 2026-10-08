package com.digicube.spawn;

/**
 * How dangerous a kind of land is ({@link SpawnRegion}): wild Digimon there spawn in its level band, the table's
 * {@code bands} added to an entry's own range. Land in no region is calm. Ids are stable JSON values.
 */
public enum SpawnDanger {

    CALM("calm"),
    WILD("wild"),
    DANGEROUS("dangerous");

    private final String id;

    SpawnDanger(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /** Translation key of the danger's name, e.g. {@code gui.digicube.scan.danger.wild}. */
    public String translationKey() {
        return "gui.digicube.scan.danger." + id;
    }

    public static SpawnDanger byId(String id) {
        for (SpawnDanger danger : values()) {
            if (danger.id.equals(id)) return danger;
        }
        throw new IllegalArgumentException("Unknown spawn danger: " + id);
    }
}
