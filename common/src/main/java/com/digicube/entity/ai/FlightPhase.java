package com.digicube.entity.ai;

/** Stable synchronized ids. Approach keeps the wings beating until an actual touchdown. */
public enum FlightPhase {
    GROUNDED, TAKEOFF, FLYING, APPROACH, LANDING;

    public static final int TRANSITION_TICKS = 32;
    public static FlightPhase byId(int id) { return id >= 0 && id < values().length ? values()[id] : GROUNDED; }
    public boolean airborne() { return this == TAKEOFF || this == FLYING || this == APPROACH; }
}
