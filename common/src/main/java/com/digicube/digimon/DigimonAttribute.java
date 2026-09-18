package com.digicube.digimon;

/**
 * The attribute triangle. VACCINE beats VIRUS, VIRUS beats DATA, DATA beats VACCINE.
 * FREE, VARIABLE and UNKNOWN take and deal neutral damage. Since 2026-09-18 the edge is a
 * critical-hit chance ({@link CriticalHits}), not a damage multiplier.
 */
public enum DigimonAttribute {

    VACCINE("vaccine"),
    DATA("data"),
    VIRUS("virus"),
    FREE("free"),
    VARIABLE("variable"),
    UNKNOWN("unknown");

    private final String id;

    DigimonAttribute(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /** What this attribute is strong against, or null if it has no advantage. */
    public DigimonAttribute strongAgainst() {
        return switch (this) {
            case VACCINE -> VIRUS;
            case VIRUS -> DATA;
            case DATA -> VACCINE;
            default -> null;
        };
    }

    /** The triangle no longer multiplies damage; it moves the critical-hit chance, see {@link CriticalHits}. */
    public float criticalChanceAgainst(DigimonAttribute defender) {
        return CriticalHits.chance(this, defender);
    }

    public static DigimonAttribute byId(String id) {
        for (DigimonAttribute attribute : values()) {
            if (attribute.id.equals(id)) {
                return attribute;
            }
        }
        throw new IllegalArgumentException("Unknown Digimon attribute: " + id);
    }
}
