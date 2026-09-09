package com.digicube.entity.ai;

/**
 * Movement axes and view direction retain vanilla vehicle transport.
 * @param ascend vanilla jump is held
 * @param brake a menu has suspended movement input
 */
public record AerialInput(boolean ascend, boolean brake) {
    /** No jump or menu override; zero movement axes naturally hover. */
    public static final AerialInput NONE = new AerialInput(false, false);
    /** Encode this input.
     * @return validated two-bit wire representation */
    public int bits() { return (ascend ? 1 : 0) | (brake ? 2 : 0); }
    /** Decode a validated payload.
     * @param bits payload bits, validated by the receiver
     * @return decoded jump/menu state */
    public static AerialInput fromBits(int bits) {
        return new AerialInput((bits & 1) != 0, (bits & 2) != 0);
    }
}
