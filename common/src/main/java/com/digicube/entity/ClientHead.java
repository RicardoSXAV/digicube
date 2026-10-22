package com.digicube.entity;

import com.digicube.entity.ai.SteadyBodyControl;
import net.minecraft.util.Mth;

/**
 * Client: a head drawn after the head the server sends, as a head turns: it gathers into its turn and brakes to a stop
 * ({@link SteadyBodyControl#ease}). The server sends the head in 256ths of a turn and vanilla's client eased each one in
 * over three ticks, so a head braking within a tick on the server stopped dead on the client, a little short of where it
 * was going. Fed every client tick, whatever arrived.
 */
public final class ClientHead {
    /** Degrees a tick the drawn head turns at most, and the ticks it gathers that rate over. */
    static final float RATE = 11, GATHER = 5;
    private float target, speed;
    private boolean seen;

    /** The server's head arrived (a packet). */
    public void sent(float yaw) {
        target = yaw;
        seen = true;
    }

    /** Whether a head has arrived since the body was first seen. */
    public boolean seen() { return seen; }

    /** This tick's head, from last tick's {@code head}. */
    public float tick(float head) {
        speed = SteadyBodyControl.ease(speed, Mth.wrapDegrees(target - head), RATE, GATHER);
        return head + speed;
    }
}
