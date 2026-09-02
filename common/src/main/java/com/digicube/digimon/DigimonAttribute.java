package com.digicube.digimon;

/**
 * The attribute triangle. VACCINE beats VIRUS, VIRUS beats DATA, DATA beats VACCINE.
 * FREE, VARIABLE and UNKNOWN take and deal neutral damage.
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

    /**
     * Damage multiplier when this attribute attacks {@code defender}.
     * Tune the numbers here rather than scattering them through combat code.
     */
    public float damageMultiplierAgainst(DigimonAttribute defender) {
        if (strongAgainst() == defender) {
            return 2.0F;
        }
        if (defender.strongAgainst() == this) {
            return 0.5F;
        }
        return 1.0F;
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
