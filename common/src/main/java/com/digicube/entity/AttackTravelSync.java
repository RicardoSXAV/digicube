package com.digicube.entity;

import com.digicube.digimon.AttackMotion;
import com.digicube.digimon.DigimonAttack;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Client: how hard a remote Digimon chases its server position while an authored lunge carries it.
 *
 * <p>Vanilla smooths every position packet over three ticks. At walking pace that is invisible; at the
 * half block per tick of a horn charge it draws the body most of a block behind the animation, so the
 * strike lands on screen before the horn arrives. Inside the ticks where the authored root travels, the
 * body chases over two ticks instead.
 *
 * <p>The body only ever follows positions the server confirmed. Replaying the travel curve ahead of them
 * was measured and rejected: a thrust without knockback stops at its victim's box, which the client
 * cannot foresee, so a replay pokes through the victim on every hit. One step tracks exactly on a
 * perfect link but doubles the judder once packets start missing client ticks; two steps cut the lag by
 * about 45 % with the judder of vanilla. {@code AttackTravelSyncRegressionTest} holds those numbers.
 */
public final class AttackTravelSync {
    /** Vanilla's smoothing, and what every tick outside a lunge keeps. */
    public static final int VANILLA_STEPS = 3;
    public static final int LUNGE_STEPS = 2;
    private static final double MOVING = 1.0E-4;
    private static final Map<AttackMotion, Window> WINDOWS = new IdentityHashMap<>();
    private static final Window NONE = new Window(-1, -1);

    private AttackTravelSync() {}

    /**
     * Animation ticks whose position packets carry authored travel.
     * @param from first tick that shows travel
     * @param until last tick that shows travel
     */
    public record Window(int from, int until) {
        public boolean contains(int tick) { return from >= 0 && tick >= from && tick <= until; }
    }

    /** @return whether the server moves this attack's root along its travel curve */
    public static boolean drivesRoot(DigimonAttack attack) {
        if (attack == null || attack.motion() == null) return false;
        if (attack.kind() == DigimonAttack.Kind.FIST || attack.kind() == DigimonAttack.Kind.HORN_RAM || attack.kind() == DigimonAttack.Kind.FROST_BITE) return true;
        var authored = com.digicube.digimon.AuthoredAttacks.get(attack);
        return authored != null && authored.rootTravel();
    }

    /** @return the flight of a jumping strike, whose distance the server plans per cast; empty for every other attack */
    private static Window flight(DigimonAttack attack) {
        var authored = attack == null ? null : com.digicube.digimon.AuthoredAttacks.get(attack);
        return authored == null || authored.leap() == null ? NONE : new Window(authored.leap().launch() + 1, authored.leap().land() + 1);
    }

    /**
     * The server steps by {@code travel(t + 1) - travel(t)} on attack tick t, and that position reaches
     * the client for animation tick t + 1.
     */
    public static Window window(AttackMotion motion) {
        synchronized (WINDOWS) {
            return WINDOWS.computeIfAbsent(motion, m -> {
                int ticks = (m.frames().size() - 1) / m.samplesPerTick(), from = -1, until = -1;
                for (int tick = 0; tick < ticks; tick++) {
                    if (m.sample(tick + 1).travel() - m.sample(tick).travel() <= MOVING) continue;
                    if (from < 0) from = tick + 1;
                    until = tick + 1;
                }
                return from < 0 ? NONE : new Window(from, until);
            });
        }
    }

    /**
     * @param attack the attack the client is animating, or null
     * @param arrivalTick animation tick that will consume the next position packet
     * @return interpolation length for that packet
     */
    public static int steps(DigimonAttack attack, int arrivalTick) {
        return drivesRoot(attack) && window(attack.motion()).contains(arrivalTick) || flight(attack).contains(arrivalTick) ? LUNGE_STEPS : VANILLA_STEPS;
    }
}
