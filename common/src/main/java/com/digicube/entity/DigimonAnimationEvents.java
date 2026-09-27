package com.digicube.entity;

/**
 * Digimon-only animation events in the signed-byte range -128 through -45: attack starts, the cues, then the starts
 * of a move's later forms. Minecraft 26.2's EntityEvent constants are positive. In particular, the client
 * intercepts 63 as a Sniffer sound before an entity's handleEntityEvent can run.
 */
public final class DigimonAnimationEvents {
    /** Number of attack entries supported for each species. */
    public static final int MAX_ATTACKS = 16;
    /** Forms one move may have (AuthoredAttacks.Forms), the first included. */
    public static final int MAX_FORMS = 4;
    private static final int START = Byte.MIN_VALUE;
    /** Stops the current animation without entering any vanilla event handler. */
    public static final byte CANCEL = START + 2 * MAX_ATTACKS;
    /** Mounted combat feedback for the rider's client: a swing connected; a ground slam landed. */
    public static final byte IMPACT = CANCEL + 1, SLAM = CANCEL + 2;
    /** An authored volume landed: effect cells that only a hit shows may now be drawn. */
    public static final byte CONTACT = CANCEL + 3;
    /** Starts of a move's second and later forms: MAX_FORMS - 1 codes for each attack index. */
    private static final int FORM_START = CONTACT + 1;

    private DigimonAnimationEvents() {}

    /**
     * Encodes a normal or mirrored animation start.
     * @param index position in the species attack list
     * @param mirrored whether to play the other-hand animation
     * @return the signed byte sent through Minecraft's entity-event packet
     */
    public static byte start(int index, boolean mirrored) {
        if (index < 0 || index >= MAX_ATTACKS) throw new IllegalArgumentException("Invalid attack index: " + index);
        return (byte) (START + index + (mirrored ? MAX_ATTACKS : 0));
    }

    /**
     * Encodes the start of one form of a move; form 0 is the move itself, and only it may be mirrored.
     * @param index position of the move in the species attack list
     * @param form the form's position among the move's forms
     */
    public static byte start(int index, boolean mirrored, int form) {
        if (form == 0) return start(index, mirrored);
        if (index < 0 || index >= MAX_ATTACKS || form < 0 || form >= MAX_FORMS || mirrored)
            throw new IllegalArgumentException("Invalid form start: " + index + "/" + form);
        return (byte) (FORM_START + index * (MAX_FORMS - 1) + form - 1);
    }

    /**
     * Decodes only Digimon animation starts, leaving vanilla events to the parent entity.
     * @param event the received signed event byte
     * @return the attack index, or -1 for cancellation and unrelated events
     */
    public static int attackIndex(byte event) {
        int offset = event - START;
        if (offset >= 0 && offset < 2 * MAX_ATTACKS) return offset % MAX_ATTACKS;
        int form = event - FORM_START;
        return form >= 0 && form < MAX_ATTACKS * (MAX_FORMS - 1) ? form / (MAX_FORMS - 1) : -1;
    }

    /** The form a start plays: 0 for an ordinary start, 1 and up for a move's later forms. */
    public static int form(byte event) {
        int form = event - FORM_START;
        return form >= 0 && form < MAX_ATTACKS * (MAX_FORMS - 1) ? form % (MAX_FORMS - 1) + 1 : 0;
    }

    /**
     * Reads the side of a Digimon animation start.
     * @param event the received signed event byte
     * @return whether this is a mirrored start
     */
    public static boolean mirrored(byte event) {
        return event >= START + MAX_ATTACKS && event < CANCEL;
    }
}
