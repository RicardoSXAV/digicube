package com.digicube.entity;

/**
 * Digimon-only animation events in the signed-byte range -128 through -96.
 * Minecraft 26.2's EntityEvent constants are positive. In particular, the client
 * intercepts 63 as a Sniffer sound before an entity's handleEntityEvent can run.
 */
public final class DigimonAnimationEvents {
    /** Number of attack entries supported for each species. */
    public static final int MAX_ATTACKS = 16;
    private static final int START = Byte.MIN_VALUE;
    /** Stops the current animation without entering any vanilla event handler. */
    public static final byte CANCEL = START + 2 * MAX_ATTACKS;

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
     * Decodes only Digimon animation starts, leaving vanilla events to the parent entity.
     * @param event the received signed event byte
     * @return the attack index, or -1 for cancellation and unrelated events
     */
    public static int attackIndex(byte event) {
        int offset = event - START;
        return offset >= 0 && offset < 2 * MAX_ATTACKS ? offset % MAX_ATTACKS : -1;
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
