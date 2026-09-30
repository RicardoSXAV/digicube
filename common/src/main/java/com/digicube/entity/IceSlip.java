package com.digicube.entity;

import net.minecraft.util.Mth;

/**
 * Where a sure-footed body's legs take it on ice, read back from its moves, and how far it skids ahead of them
 * ({@code locomotion.ice_grip}; {@link DigimonEntity#footing}). On ice its pace comes round to its legs' drive by
 * {@link DigimonEntity#FIRM_LOSS} times its grip a tick, so one move and the one before give the drive: running on ahead
 * of a slow start (the paws slipping back), gone as it lets go (the body sliding on), along the body through a drift.
 * The client plays its gait on the legs and its skid pose on the skid; {@code garurumon_checks} reads the same from the
 * server's moves.
 */
public final class IceSlip {
    /** Most blocks a tick the legs are read to drive at (a read past it is a knock or a wall, not a stride). */
    private static final double LEGS_MOST = 1.2;
    /** Blocks a tick of the body's pace its legs do not carry from which it skids, and at which its skid is whole. */
    private static final double SKID_FROM = .02, SKID_FULL = .12;

    private IceSlip() {}

    /**
     * The legs' drive, blocks a tick along the world's axes: this tick's move {@code dx, dz} with last tick's, on ground
     * of {@code grip} for a species whose ice grip is {@code iceGrip}. On firm ground it is the move itself.
     */
    public static double[] legs(double dx, double dz, double lastX, double lastZ, double grip, double iceGrip) {
        if (grip >= 1 || iceGrip >= 1) return new double[]{dx, dz};
        double rate = DigimonEntity.FIRM_LOSS * grip, slippery = Math.clamp((1 - grip) / (1 - iceGrip), 0, 1);
        double driveX = (dx - (1 - rate) * lastX) / rate, driveZ = (dz - (1 - rate) * lastZ) / rate;
        double drive = Math.sqrt(driveX * driveX + driveZ * driveZ);
        if (drive > LEGS_MOST) { driveX *= LEGS_MOST / drive; driveZ *= LEGS_MOST / drive; }
        return new double[]{dx + (driveX - dx) * slippery, dz + (driveZ - dz) * slippery};
    }

    /** How far into its skid a body moving {@code dx, dz} is on legs driving {@code legsX, legsZ}: 0 to 1, eased. */
    public static float skid(double dx, double dz, double legsX, double legsZ) {
        double pace = Math.sqrt(dx * dx + dz * dz), carried = pace < 1.0E-6 ? 0 : Math.max(0, (legsX * dx + legsZ * dz) / pace);
        float slide = (float) Mth.clamp((pace - carried - SKID_FROM) / (SKID_FULL - SKID_FROM), 0, 1);
        return slide * slide * (3 - 2 * slide);
    }
}
