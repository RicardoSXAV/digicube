package com.digicube.fabric.client.digivice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The reserve Digimon wandering the Digispace. Purely cosmetic and client-side: the server knows who is in reserve,
 * the herd decides where each one stands. A Digimon keeps its spot for as long as the client runs; a newcomer appears
 * where it was set down (or where the one it replaced was picked up), otherwise at a spot derived from its id.
 */
public final class DigispaceHerd {
    /** Ticks a newcomer takes to be rebuilt out of data blocks. */
    public static final int SPAWN_TICKS = 16;

    /** What the herd needs to know about a reserve member. */
    public record Entry(UUID id, boolean fast, boolean asleep) {}

    public static final class Walker {
        public final UUID id;
        public double x, y;
        /** Last tick's position, for drawing between ticks. */
        public double lastX, lastY;
        double targetX, targetY;
        int wait;
        public boolean flip;
        public int spawn;
        boolean fast;
        /** Defeated and resting: it lies still. */
        public boolean asleep;

        Walker(UUID id, double x, double y, int wait) { this.id = id; this.x = lastX = targetX = x; this.y = lastY = targetY = y; this.wait = wait; }
        public boolean moving() { return wait == 0 && !asleep; }
        void settle(double x, double y, int wait) { this.x = lastX = targetX = x; this.y = lastY = targetY = y; this.wait = wait; }
    }

    private final DigispaceWorld world;
    private final Map<UUID, Walker> walkers = new LinkedHashMap<>();
    private final Map<UUID, double[]> places = new HashMap<>();
    private boolean populated;

    public DigispaceHerd(DigispaceWorld world) { this.world = world; }

    public Collection<Walker> walkers() { return walkers.values(); }
    public Walker get(UUID id) { return walkers.get(id); }

    /** Remembers where {@code id} should appear once the server moves it to reserve. */
    public void reserve(UUID id, double x, double y) { places.put(id, new double[]{x, y}); }

    /** Brings the herd in line with the reserve. Everyone is simply there the first time; later arrivals are rebuilt out of data. */
    public void sync(List<Entry> reserve) {
        List<UUID> ids = new ArrayList<>();
        for (Entry entry : reserve) {
            ids.add(entry.id());
            Walker walker = walkers.get(entry.id());
            if (walker == null) {
                double[] spot = places.remove(entry.id());
                if (spot == null || !world.walkable(spot[0], spot[1])) spot = home(entry.id());
                walker = new Walker(entry.id(), spot[0], spot[1], 20 + walkers.size() * 13 % 90);
                if (populated) walker.spawn = SPAWN_TICKS;
                walkers.put(entry.id(), walker);
            }
            walker.fast = entry.fast();
            walker.asleep = entry.asleep();
        }
        walkers.keySet().retainAll(ids);
        populated = true;
    }

    /** A walkable spot that depends only on the id, away from the island's rim. */
    double[] home(UUID id) {
        int seed = (int) (id.getMostSignificantBits() ^ id.getLeastSignificantBits()) & 0xFFFF;
        for (int n = 0; n < 200; n++) {
            double x = 60 + DigispaceWorld.hash(seed, n) * (DigispaceWorld.WIDTH - 120), y = 30 + DigispaceWorld.hash(n, seed + 9) * (DigispaceWorld.HEIGHT - 60);
            if (world.walkable(x, y)) return new double[]{x, y};
        }
        return new double[]{DigispaceWorld.WIDTH / 2.0, DigispaceWorld.HEIGHT / 2.0};
    }

    /** Sets a walker down at a new spot (or back where it was); it stands for a moment before wandering on. */
    public void settle(Walker walker, double x, double y) { walker.settle(x, y, 40); }

    /** One tick: rest, pick a nearby reachable spot, walk to it. {@code held} is in the player's hand and does not move. */
    public void step(int tick, UUID held) {
        int index = 0;
        for (Walker w : walkers.values()) {
            int i = index++;
            w.lastX = w.x; w.lastY = w.y;
            if (w.spawn > 0) w.spawn--;
            if (w.asleep || w.id.equals(held)) continue;
            if (w.wait > 0) {
                if (--w.wait == 0) choose(w, tick, i);
                continue;
            }
            double dx = w.targetX - w.x, dy = w.targetY - w.y, distance = Math.hypot(dx, dy);
            if (distance < 0.6) { w.wait = 30 + (int) (DigispaceWorld.hash(tick, i) * 90); continue; }
            double speed = w.fast ? 0.55 : 0.35;
            w.x += dx / distance * speed; w.y += dy / distance * speed;
            if (Math.abs(dx) > 0.2) w.flip = dx < 0;
        }
    }

    private void choose(Walker w, int tick, int i) {
        for (int n = 0; n < 12; n++) {
            double angle = DigispaceWorld.hash(tick + i, n) * Math.PI * 2, reach = 16 + DigispaceWorld.hash(n, tick + i) * 46;
            double tx = w.x + Math.cos(angle) * reach, ty = w.y + Math.sin(angle) * reach * 0.6;
            boolean clear = true;
            for (int s = 1; s <= 6 && clear; s++) clear = world.walkable(w.x + (tx - w.x) * s / 6, w.y + (ty - w.y) * s / 6);
            if (clear) { w.targetX = tx; w.targetY = ty; return; }
        }
        w.wait = 20;
    }
}
