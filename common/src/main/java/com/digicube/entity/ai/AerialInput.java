package com.digicube.entity.ai;

/**
 * Movement axes and view direction retain vanilla vehicle transport.
 * @param ascend vanilla jump is held
 * @param brake a menu has suspended movement input
 * @param boost the sprint key is held (agile flight beats the wings on full)
 * @param descend the dive key is held (agile flight sinks)
 */
public record AerialInput(boolean ascend, boolean brake, boolean boost, boolean descend) {
    /** No jump or menu override; zero movement axes naturally hover. */
    public static final AerialInput NONE = new AerialInput(false, false, false, false);
    /** The bits a payload may carry. */
    public static final int MAX_BITS = 15;
    public AerialInput(boolean ascend, boolean brake) { this(ascend, brake, false, false); }
    /** Encode this input.
     * @return validated four-bit wire representation */
    public int bits() { return (ascend ? 1 : 0) | (brake ? 2 : 0) | (boost ? 4 : 0) | (descend ? 8 : 0); }
    /** Decode a validated payload.
     * @param bits payload bits, validated by the receiver
     * @return decoded jump/menu/sprint/dive state */
    public static AerialInput fromBits(int bits) {
        return new AerialInput((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
    }
}
