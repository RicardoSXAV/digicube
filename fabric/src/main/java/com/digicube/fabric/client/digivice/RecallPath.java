package com.digicube.fabric.client.digivice;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The route a recalled Digivice flies, planned once on the client: a smooth curve through open air whenever one
 * exists. Gentle arcs are tried first; around walls, roofs and pillars an A* search over block cells finds the way
 * and a centripetal Catmull-Rom spline smooths it. Blocks are only crossed where no open way exists (a device buried
 * in the ground, a sealed room), and then by the least rock. Stored as a dense polyline measured by arc length.
 */
public final class RecallPath {
    public interface Blocks { boolean blocked(int x, int y, int z); }
    /** Half the flying device's width in blocks: the clearance every checked sample keeps from solid cells. */
    public static final double RADIUS = .16;
    private static final double STEP = .08, SOLID = 14, WALL = .5, END_FREE = 1.4;
    /** Routes longer than this may take the skyline over the terrain; it clears the highest block by this much. */
    private static final double SKYLINE_MIN = 24, SKYLINE_CLEARANCE = 1.5;
    private static final int SKYLINE_CEILING = 128;
    private final double[] x, y, z, s;
    private final int[] segment;
    private final double approach;
    /** True when the open arcs were all blocked and the route came from the cell search. */
    public final boolean searched;

    private RecallPath(double[] x, double[] y, double[] z, double[] s, int[] segment, double approach, boolean searched) {
        this.x = x; this.y = y; this.z = z; this.s = s; this.segment = segment; this.approach = approach; this.searched = searched;
    }
    public double length() { return s[s.length - 1]; }
    /** Arc length at the approach point, 0 when the route has none. */
    public double approach() { return approach; }
    public Vec3 at(double distance) {
        double d = Math.clamp(distance, 0, length());
        int lo = 0, hi = s.length - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (s[mid] <= d) lo = mid; else hi = mid; }
        double span = s[hi] - s[lo], f = span < 1e-9 ? 0 : (d - s[lo]) / span;
        return new Vec3(x[lo] + (x[hi] - x[lo]) * f, y[lo] + (y[hi] - y[lo]) * f, z[lo] + (z[hi] - z[lo]) * f);
    }
    public int samples() { return s.length; }
    public Vec3 sample(int i) { return new Vec3(x[i], y[i], z[i]); }

    public static Blocks of(BlockGetter level) {
        var pos = new BlockPos.MutableBlockPos();
        return (bx, by, bz) -> {
            pos.set(bx, by, bz);
            return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        };
    }
    /** One planning pass asks each cell at most once. */
    public static Blocks cache(Blocks blocks) {
        var known = new Long2BooleanOpenHashMap();
        return (bx, by, bz) -> {
            long key = BlockPos.asLong(bx, by, bz);
            if (known.containsKey(key)) return known.get(key);
            boolean blocked = blocks.blocked(bx, by, bz);
            known.put(key, blocked);
            return blocked;
        };
    }
    public static boolean clear(Blocks blocks, Vec3 p) {
        int x0 = floor(p.x - RADIUS), x1 = floor(p.x + RADIUS), y0 = floor(p.y - RADIUS), y1 = floor(p.y + RADIUS);
        int z0 = floor(p.z - RADIUS), z1 = floor(p.z + RADIUS);
        for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++)
            if (blocks.blocked(bx, by, bz)) return false;
        return true;
    }
    public static boolean lineClear(Blocks blocks, Vec3 a, Vec3 b) {
        double length = a.distanceTo(b);
        int n = Math.max(1, (int)Math.ceil(length / .1));
        for (int i = 0; i <= n; i++) if (!clear(blocks, a.lerp(b, i / (double)n))) return false;
        return true;
    }
    /** Open distance along a ray, up to {@code limit}, keeping the device's clearance. */
    public static double free(Blocks blocks, Vec3 from, Vec3 direction, double limit) {
        var dir = direction.normalize();
        for (double d = .1; d <= limit; d += .1) if (!clear(blocks, from.add(dir.scale(d)))) return d - .1;
        return limit;
    }
    public static boolean buried(Blocks blocks, Vec3 source) {
        return blocks.blocked(floor(source.x), floor(source.y + .12), floor(source.z));
    }
    /** How high the device can rise above its resting centre: 1.05 in the open, less under a low roof, 0 when buried. */
    public static double hover(Blocks blocks, Vec3 source) {
        if (buried(blocks, source)) return 0;
        for (double h = .3; h <= 1.2; h += .05) if (!clear(blocks, source.add(0, h, 0))) return Math.max(0, h - .12);
        return 1.05;
    }
    /**
     * The way out of the block the device is in. Out of a container (a chest, a barrel on its side, a shulker box in a
     * wall) that is through an open face beside it: the top first, else the side nearest {@code toward}. Buried in the
     * ground it rises straight out: the first open point above it, or null deeper than ten blocks.
     */
    public static Vec3 surface(Blocks blocks, Vec3 source, Vec3 toward) {
        int x = floor(source.x), y = floor(source.y + .12), z = floor(source.z);
        var centre = new Vec3(x + .5, y + .5, z + .5);
        Vec3 best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (var face : net.minecraft.core.Direction.values()) {
            if (face == net.minecraft.core.Direction.DOWN) continue;
            var out = centre.add(face.getStepX() * .85, face.getStepY() * .85, face.getStepZ() * .85);
            if (blocks.blocked(x + face.getStepX(), y + face.getStepY(), z + face.getStepZ()) || !clear(blocks, out)) continue;
            if (face == net.minecraft.core.Direction.UP) return out;
            double d = out.distanceToSqr(toward);
            if (d < nearest) { nearest = d; best = out; }
        }
        return best != null ? best : surface(blocks, source);
    }
    /** A buried device rises straight out: the first open point above it, or null deeper than ten blocks. */
    public static Vec3 surface(Blocks blocks, Vec3 source) {
        for (double h = .5; h <= 10; h += .25) {
            var p = source.add(0, h, 0);
            if (clear(blocks, p) && clear(blocks, p.add(0, .3, 0))) return p.add(0, .3, 0);
        }
        return null;
    }
    /**
     * Where a distant device appears: 24 blocks out along a line of open air from the eye, as near its bearing as the
     * terrain allows. In a ravine or a pit that is often steeply up, into the open sky, never out of a cliff face.
     * Only when no direction is open that far (a cave) does it come from the most open side, short of its wall.
     */
    public static Vec3 entry(Blocks blocks, Vec3 eye, Vec3 bearing) {
        var dir = bearing.normalize();
        double yaw = Math.atan2(dir.z, dir.x), pitch = Math.asin(Math.clamp(dir.y, -1, 1));
        record Candidate(Vec3 dir, double angle) {}
        var candidates = new ArrayList<Candidate>();
        double[] turns = {0, 20, -20, 40, -40, 60, -60, 90, -90, 120, -120, 150, -150, 180};
        double[] lifts = {Math.toDegrees(pitch), 10, 25, 40, 55, 70, 85};
        for (double turn : turns) for (double lift : lifts) {
            double a = yaw + Math.toRadians(turn), e = Math.toRadians(lift);
            var candidate = new Vec3(Math.cos(a) * Math.cos(e), Math.sin(e), Math.sin(a) * Math.cos(e));
            candidates.add(new Candidate(candidate, Math.acos(Math.clamp(candidate.dot(dir), -1, 1))));
        }
        candidates.sort(java.util.Comparator.comparingDouble(Candidate::angle));
        Vec3 best = dir; double bestFree = -1;
        for (var c : candidates) {
            double open = free(blocks, eye, c.dir(), 24);
            if (open >= 24) return eye.add(c.dir().scale(24));
            if (open > bestFree + .5) { bestFree = open; best = c.dir(); }
        }
        return eye.add(best.scale(Math.max(1.2, bestFree - .6)));
    }

    public static RecallPath plan(Blocks blocks, Vec3 start, Vec3 approach, Vec3 end) { return new Planner(blocks, start, start, approach, end).path(); }

    /**
     * A route from {@code from} (the hover point or a distant entry) by way of {@code start} (where a buried device
     * breaks the surface; the same point otherwise) to the optional approach point in front of the eye, then the hand.
     * Open arcs are checked at once; the cell searches run in slices of frame time ({@link #work}) so a hard route
     * never stalls a frame, and {@link #path} finishes whatever is left when the flight needs it.
     */
    public static final class Planner {
        private final Blocks blocks;
        private final List<Vec3> head;
        private final Vec3 start, approach, end, goal;
        private RecallPath result, fallback;
        private Search search;
        private boolean digging;

        public Planner(Blocks blocks, Vec3 from, Vec3 start, Vec3 approach, Vec3 end) {
            this.blocks = blocks; this.start = start; this.approach = approach; this.end = end;
            head = from.distanceToSqr(start) > 1e-8 ? List.of(from) : List.of();
            goal = approach != null ? approach : end;
            var tail = approach != null ? List.of(approach, end) : List.of(end);
            double length = start.distanceTo(goal);
            var dir = length < 1e-6 ? new Vec3(0, 0, 1) : goal.subtract(start).scale(1 / length);
            var up = new Vec3(0, 1, 0);
            var sideways = Math.abs(dir.y) > .95 ? new Vec3(1, 0, 0) : dir.cross(up).normalize();
            // Most natural first: a soft rise, a higher arc, around either side, straight, then over the top.
            List<Vec3> arcs = List.of(up.scale(.12 * length + .35), up.scale(Math.min(7, .3 * length + .8)),
                    sideways.scale(.3 * length).add(0, .4, 0), sideways.scale(-.3 * length).add(0, .4, 0),
                    Vec3.ZERO, up.scale(Math.min(12, .55 * length + 1.5)));
            for (var offset : arcs) {
                var points = new ArrayList<Vec3>(head);
                points.add(start);
                if (offset != Vec3.ZERO && length > .8) points.add(start.lerp(goal, .5).add(offset));
                points.addAll(tail);
                var path = build(points, approach != null ? points.size() - 2 : -1, false);
                if (fallback == null) fallback = path;
                if (path.blockedSegments(blocks, end).isEmpty()) { result = path; return; }
            }
            // A long way over hills or a mountain: the arc of a thrown star over the terrain in between.
            if (length > SKYLINE_MIN) {
                var over = skyline(blocks, start, goal);
                if (over != null) {
                    var points = new ArrayList<Vec3>(head);
                    points.addAll(over);
                    if (approach != null) points.add(end);
                    var path = build(points, approach != null ? points.size() - 2 : -1, false);
                    if (path.blockedSegments(blocks, end).isEmpty()) { result = path; return; }
                }
            }
            // Open air only first, over a wide area: over a ridge, out of a ravine, round a house to its door.
            search = new Search(blocks, start, goal, false);
        }
        public boolean done() { return result != null; }
        /** Spend up to {@code nanos} on the search. */
        public void work(long nanos) {
            long deadline = System.nanoTime() + nanos;
            while (result == null && System.nanoTime() < deadline) advance(deadline);
        }
        public RecallPath path() {
            while (result == null) advance(Long.MAX_VALUE);
            return result;
        }
        private void advance(long deadline) {
            if (!search.run(deadline)) return;
            var raw = search.cells();
            if (raw != null) { result = smooth(raw); return; }
            // Sealed in or buried deep: through the fewest blocks, nearby.
            if (!digging) { digging = true; search = new Search(blocks, start, goal, true); return; }
            result = fallback;
        }
        private RecallPath smooth(List<Vec3> raw) {
            var keep = pull(blocks, raw);
            int offset = head.size();
            boolean hasApproach = approach != null;
            for (int round = 0; round < 64; round++) {
                var points = new ArrayList<Vec3>(head);
                for (int index : keep) points.add(raw.get(index));
                if (hasApproach) points.add(end);
                var path = build(points, hasApproach ? offset + keep.size() - 1 : -1, true);
                boolean refined = false;
                // Bring back the raw cells under a curve that cut a corner, then smooth again.
                var bad = path.blockedSegments(blocks, end);
                for (int k = bad.size() - 1; k >= 0; k--) {
                    int segment = bad.get(k) - offset;
                    if (segment < 0 || segment + 1 >= keep.size()) continue;
                    int a = keep.get(segment), b = keep.get(segment + 1);
                    if (b - a > 1) { keep.add(segment + 1, (a + b) / 2); refined = true; }
                }
                if (!refined) return path;
            }
            var points = new ArrayList<Vec3>(head);
            points.addAll(raw);
            if (hasApproach) points.add(end);
            return build(points, hasApproach ? offset + raw.size() - 1 : -1, true);
        }
    }

    /**
     * Waypoints from {@code from} to {@code to} over everything solid between them: each column under the straight
     * line is raised to clear its highest block (up to {@link #SKYLINE_CEILING} above the line), and the upper hull of
     * those heights is the route, straight up out of a valley and down onto the player in one arc. Null when nothing
     * is in the way (the plain arcs cover that) or a column is solid past the ceiling.
     */
    static List<Vec3> skyline(Blocks blocks, Vec3 from, Vec3 to) {
        double length = from.distanceTo(to);
        int n = Math.max(2, (int)Math.ceil(length));
        double[] t = new double[n + 1], h = new double[n + 1];
        boolean raised = false;
        for (int i = 0; i <= n; i++) {
            var p = from.lerp(to, i / (double)n);
            t[i] = length * i / n; h[i] = p.y;
            if (i == 0 || i == n) continue;
            int x = floor(p.x), z = floor(p.z), low = floor(p.y) - 2, high = floor(p.y) + SKYLINE_CEILING;
            if (blocks.blocked(x, high, z)) return null;
            for (int y = high - 1; y >= low; y--) if (blocks.blocked(x, y, z)) {
                double clear = y + 1 + SKYLINE_CLEARANCE;
                if (clear > h[i]) { h[i] = clear; raised = true; }
                break;
            }
        }
        if (!raised) return null;
        // Upper hull (monotone chain): keep only clockwise turns.
        int[] hull = new int[n + 1];
        int size = 0;
        for (int i = 0; i <= n; i++) {
            while (size >= 2) {
                int a = hull[size - 2], b = hull[size - 1];
                if ((t[b] - t[a]) * (h[i] - h[a]) - (h[b] - h[a]) * (t[i] - t[a]) >= 0) size--; else break;
            }
            hull[size++] = i;
        }
        var points = new ArrayList<Vec3>(size);
        for (int k = 0; k < size; k++) {
            var p = from.lerp(to, hull[k] / (double)n);
            points.add(new Vec3(p.x, h[hull[k]], p.z));
        }
        return points;
    }

    /** Greedy string pulling: from each kept cell, the farthest later cell in plain sight. */
    private static List<Integer> pull(Blocks blocks, List<Vec3> raw) {
        var keep = new ArrayList<Integer>();
        keep.add(0);
        int i = 0, last = raw.size() - 1;
        while (i < last) {
            int next = i + 1;
            if (clear(blocks, raw.get(i))) for (int j = i + 2; j <= Math.min(last, i + 28); j++) {
                if (!lineClear(blocks, raw.get(i), raw.get(j))) break;
                next = j;
            }
            keep.add(next);
            i = next;
        }
        return keep;
    }

    /**
     * Resumable cell A* over sparse maps. The open search never enters a solid cell and ranges wide (a detour of
     * dozens of blocks beats one block of rock); the digging search may cross solid cells at a high price, near the
     * straight line. Cells beside a wall cost a little more, which keeps the smoothed curve off the wall.
     */
    private static final class Search {
        private final Blocks blocks;
        private final boolean dig;
        private final int gx, gy, gz, minX, maxX, minY, maxY, minZ, maxZ, limit;
        private final long goalKey;
        private final Vec3 start, goal;
        private final Long2FloatOpenHashMap g = new Long2FloatOpenHashMap();
        private final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        private final LongOpenHashSet closed = new LongOpenHashSet();
        private final Long2BooleanOpenHashMap wall = new Long2BooleanOpenHashMap();
        private final Heap heap = new Heap();
        private int expansions;
        private boolean finished, found;

        Search(Blocks blocks, Vec3 start, Vec3 goal, boolean dig) {
            this.blocks = blocks; this.dig = dig; this.start = start; this.goal = goal;
            int sx = floor(start.x), sy = floor(start.y), sz = floor(start.z);
            gx = floor(goal.x); gy = floor(goal.y); gz = floor(goal.z);
            int across = dig ? 7 : 24, below = dig ? 4 : 10, above = dig ? 10 : 40;
            minX = Math.min(sx, gx) - across; maxX = Math.max(sx, gx) + across;
            minY = Math.min(sy, gy) - below; maxY = Math.max(sy, gy) + above;
            minZ = Math.min(sz, gz) - across; maxZ = Math.max(sz, gz) + across;
            limit = dig ? 80_000 : 250_000;
            goalKey = BlockPos.asLong(gx, gy, gz);
            g.defaultReturnValue(Float.POSITIVE_INFINITY);
            long startKey = BlockPos.asLong(sx, sy, sz);
            if (!dig && (blocks.blocked(sx, sy, sz) || blocks.blocked(gx, gy, gz))) { finished = true; return; }
            g.put(startKey, 0);
            parent.put(startKey, Long.MIN_VALUE);
            heap.push(startKey, (float)Math.sqrt(sq(sx - gx) + sq(sy - gy) + sq(sz - gz)));
        }
        /** Works until done or the deadline; true when finished (found or given up). */
        boolean run(long deadline) {
            while (!finished) {
                if ((expansions & 127) == 0 && System.nanoTime() > deadline) return false;
                if (heap.size == 0 || ++expansions > limit) { finished = true; break; }
                long current = heap.pop();
                if (!closed.add(current)) continue;
                if (current == goalKey) { finished = found = true; break; }
                int cx = BlockPos.getX(current), cy = BlockPos.getY(current), cz = BlockPos.getZ(current);
                float base = g.get(current);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int nx = cx + dx, ny = cy + dy, nz = cz + dz;
                    if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) continue;
                    long next = BlockPos.asLong(nx, ny, nz);
                    if (closed.contains(next)) continue;
                    boolean solid = blocks.blocked(nx, ny, nz);
                    if (solid && !dig) continue;
                    // A diagonal step through open air may not clip the corner of a block beside it.
                    if (!solid && (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0) > 1
                            && (dx != 0 && blocks.blocked(cx + dx, cy, cz) || dy != 0 && blocks.blocked(cx, cy + dy, cz)
                            || dz != 0 && blocks.blocked(cx, cy, cz + dz))) continue;
                    double cost = solid ? SOLID : 1 + (besideWall(next, nx, ny, nz) ? WALL : 0);
                    float tentative = (float)(base + Math.sqrt(dx * dx + dy * dy + dz * dz) * cost);
                    if (tentative >= g.get(next)) continue;
                    g.put(next, tentative);
                    parent.put(next, current);
                    heap.push(next, tentative + (float)Math.sqrt(sq(nx - gx) + sq(ny - gy) + sq(nz - gz)));
                }
            }
            return true;
        }
        /** The cell centres of the route found, from the exact start to the exact goal, or null. */
        List<Vec3> cells() {
            if (!found) return null;
            var cells = new ArrayList<Vec3>();
            for (long at = goalKey; at != Long.MIN_VALUE; at = parent.get(at))
                cells.add(new Vec3(BlockPos.getX(at) + .5, BlockPos.getY(at) + .5, BlockPos.getZ(at) + .5));
            java.util.Collections.reverse(cells);
            cells.set(0, start);
            if (cells.size() == 1) cells.add(goal); else cells.set(cells.size() - 1, goal);
            return cells;
        }
        private boolean besideWall(long key, int x, int y, int z) {
            if (wall.containsKey(key)) return wall.get(key);
            boolean near = blocks.blocked(x + 1, y, z) || blocks.blocked(x - 1, y, z) || blocks.blocked(x, y + 1, z)
                    || blocks.blocked(x, y - 1, z) || blocks.blocked(x, y, z + 1) || blocks.blocked(x, y, z - 1);
            wall.put(key, near);
            return near;
        }
    }

    /** Binary min-heap of (cell, priority) with lazy deletion. */
    private static final class Heap {
        long[] nodes = new long[256];
        float[] keys = new float[256];
        int size;
        void push(long node, float key) {
            if (size == nodes.length) { nodes = Arrays.copyOf(nodes, size * 2); keys = Arrays.copyOf(keys, size * 2); }
            int i = size++;
            while (i > 0) {
                int up = (i - 1) >>> 1;
                if (keys[up] <= key) break;
                nodes[i] = nodes[up]; keys[i] = keys[up]; i = up;
            }
            nodes[i] = node; keys[i] = key;
        }
        long pop() {
            long top = nodes[0];
            long node = nodes[--size]; float key = keys[size];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= size) break;
                if (child + 1 < size && keys[child + 1] < keys[child]) child++;
                if (keys[child] >= key) break;
                nodes[i] = nodes[child]; keys[i] = keys[child]; i = child;
            }
            nodes[i] = node; keys[i] = key;
            return top;
        }
    }

    /** Waypoint segments whose curve touches a block, checked up to the approach and away from the hand. */
    List<Integer> blockedSegments(Blocks blocks, Vec3 end) {
        var bad = new ArrayList<Integer>();
        boolean out = false;
        for (int i = 0; i < s.length; i++) {
            if (approach > 0 && s[i] > approach + 1e-6) break;
            var p = sample(i);
            if (p.distanceTo(end) < END_FREE) continue;
            boolean open = clear(blocks, p);
            // A buried start digs out first; only the curve after it must stay in the open.
            if (!out) { if (open) out = true; else continue; }
            if (!open && (bad.isEmpty() || bad.getLast() != segment[i])) bad.add(segment[i]);
        }
        return bad;
    }

    private static RecallPath build(List<Vec3> waypoints, int approachIndex, boolean searched) {
        var points = new ArrayList<Vec3>();
        int approachAt = -1;
        for (int i = 0; i < waypoints.size(); i++) {
            var p = waypoints.get(i);
            if (points.isEmpty() || points.getLast().distanceToSqr(p) > 1e-8) points.add(p);
            if (i == approachIndex) approachAt = points.size() - 1;
        }
        if (points.size() == 1) points.add(points.getFirst());
        var xs = new ArrayList<double[]>();
        var segments = new ArrayList<Integer>();
        int approachSample = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            var p1 = points.get(i); var p2 = points.get(i + 1);
            var p0 = i > 0 ? points.get(i - 1) : p1.scale(2).subtract(p2);
            var p3 = i + 2 < points.size() ? points.get(i + 2) : p2.scale(2).subtract(p1);
            int count = Math.max(2, (int)Math.ceil(p1.distanceTo(p2) / STEP));
            for (int j = i == 0 ? 0 : 1; j <= count; j++) {
                var p = catmullRom(p0, p1, p2, p3, j / (double)count);
                xs.add(new double[]{p.x, p.y, p.z});
                segments.add(i);
            }
            if (i + 1 == approachAt) approachSample = xs.size() - 1;
        }
        int size = xs.size();
        double[] x = new double[size], y = new double[size], z = new double[size], s = new double[size];
        int[] segment = new int[size];
        for (int i = 0; i < size; i++) {
            x[i] = xs.get(i)[0]; y[i] = xs.get(i)[1]; z[i] = xs.get(i)[2]; segment[i] = segments.get(i);
            if (i > 0) s[i] = s[i - 1] + Math.sqrt(sq(x[i] - x[i - 1]) + sq(y[i] - y[i - 1]) + sq(z[i] - z[i - 1]));
        }
        return new RecallPath(x, y, z, s, segment, approachAt > 0 ? s[approachSample] : 0, searched);
    }
    /** Centripetal Catmull-Rom (Barry-Goldman): no cusps or loops where waypoints bunch up. */
    private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
        double t0 = 0, t1 = t0 + knot(p0, p1), t2 = t1 + knot(p1, p2), t3 = t2 + knot(p2, p3);
        double t = t1 + (t2 - t1) * u;
        var a1 = mix(p0, p1, t0, t1, t); var a2 = mix(p1, p2, t1, t2, t); var a3 = mix(p2, p3, t2, t3, t);
        var b1 = mix(a1, a2, t0, t2, t); var b2 = mix(a2, a3, t1, t3, t);
        return mix(b1, b2, t1, t2, t);
    }
    private static Vec3 mix(Vec3 a, Vec3 b, double ta, double tb, double t) { return a.lerp(b, (t - ta) / (tb - ta)); }
    private static double knot(Vec3 a, Vec3 b) { return Math.max(1e-4, Math.sqrt(a.distanceTo(b))); }
    private static int floor(double v) { return (int)Math.floor(v); }
    private static double sq(double v) { return v * v; }

}
