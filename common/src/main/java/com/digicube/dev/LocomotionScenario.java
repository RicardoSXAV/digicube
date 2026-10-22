package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonGait;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.GaitDriver;
import com.digicube.party.PartyManager;
import com.digicube.party.PartyMember;
import com.digicube.party.PartySavedData;
import com.digicube.registry.DCEntityTypes;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code DIGICUBE_SCENARIO=locomotion_checks[:<species>]} (Agumon by default): does a body move as an animal does on its own
 * AI? Three stretches on a walled arena with a few blocks about: a wild one alone, a wild one a stand-in player stands near,
 * and a partner following a stand-in tamer who stands, walks, sprints, walks through it and circles. Each tick the
 * scenario sees the body as a client would (the position and facing packets it is sent, eased in as a client eases them,
 * and the gait the client plays from them: {@link GaitDriver}), and counts what reads as unnatural: steps sideways while it
 * walks ahead of no enemy (a crab's), legs stepping while the body hardly moves (a treadmill), small quick steps on the spot
 * (shuffles), turns on the spot too small to need the feet, the head swinging faster or jerking harder than a head does,
 * and a wander at a run. Logs {@code [locomotion] PASS|FAIL <stretch> ...} per stretch, the first few of each fault with
 * what the body was doing, and writes every tick to {@code locomotion_trace.csv} in the server's directory.
 */
public final class LocomotionScenario {
    private static final int FLOOR = 300, HALF = 22;
    private static final AABB ARENA = new AABB(-HALF - 2, FLOOR - 2, -HALF - 2, HALF + 2, FLOOR + 14, HALF + 2);

    /** A walk at a player's pace and at a sprint, blocks a tick. */
    private static final double WALK = .216, SPRINT = .28;

    private enum Stretch { WILD_ALONE, WILD_WATCHED, PARTNER }

    /** One leg of the stand-in tamer's walk: to (x, z) at {@code speed} (0 stands), for {@code ticks} ticks. */
    private record Leg(String name, int ticks, double speed, double x, double z, boolean sprint, int kind) {
        static final int GO = 0, THROUGH = 1, CIRCLE = 2, SIDE = 3;
        static Leg stand(int ticks) { return new Leg("stand", ticks, 0, 0, 0, false, GO); }
        static Leg go(int ticks, double speed, double x, double z) { return new Leg("walk", ticks, speed, x, z, speed > WALK, GO); }
    }

    private static final List<Leg> SCRIPT = List.of(
            Leg.stand(140), Leg.go(120, WALK, 12, -8), Leg.stand(220), Leg.go(140, WALK, 12, 10),
            Leg.go(140, SPRINT, -14, 10), Leg.stand(600),
            // round to its side, then up to it from there, to stand right beside it
            new Leg("aside", 120, WALK, 6, 0, false, Leg.SIDE), Leg.stand(60), new Leg("beside", 100, WALK, .9, 0, false, Leg.SIDE),
            Leg.stand(240), new Leg("through", 160, WALK, 0, 0, false, Leg.THROUGH),
            Leg.stand(240), Leg.go(170, WALK, 0, -2), new Leg("circle", 300, .12, 0, -2, false, Leg.CIRCLE), Leg.stand(500));

    private static final int WILD_TICKS = 1500;

    private static boolean started, done;
    private static DigimonSpecies species;
    private static Stretch stretch;
    private static int tick, legIndex, legTick;
    private static DigimonEntity body;
    private static ServerPlayer standIn;
    private static PartySavedData parties;
    private static ClientView view;
    private static Metrics metrics;
    private static PrintWriter trace;
    private static Vec3 legFrom, legTo;
    private static final List<String> verdicts = new ArrayList<>();
    private static int failed;

    private LocomotionScenario() {}

    static void tick(ServerLevel level, String name) {
        if (done) return;
        try {
            if (!started) { if (!setup(level, name)) return; begin(level, Stretch.WILD_ALONE); return; }
            purge(level);
            step(level);
        } catch (RuntimeException e) {
            Constants.LOG.error("[locomotion] aborted", e);
            failed++;
            finish(level);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- setup

    private static boolean setup(ServerLevel level, String name) {
        boolean loaded = true;
        for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) {
            level.setChunkForced(x, z, true);
            loaded &= level.isPositionEntityTicking(new BlockPos(x * 16 + 8, 64, z * 16 + 8));
        }
        if (!loaded) return false;
        started = true;
        species = DigimonSpeciesRegistry.getOrThrow(Constants.id(name.isEmpty() ? "agumon" : name));
        if (species.locomotion().groundGait() == null) throw new IllegalStateException(species.id() + " has no ground gait");
        com.digicube.spawn.WildSpawner.clear(level);
        build(level);
        parties = PartySavedData.get(level.getServer());
        try {
            trace = new PrintWriter(Files.newBufferedWriter(Path.of("locomotion_trace.csv"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the trace", e);
        }
        trace.println("stretch,tick,x,y,z,yaw,body,head,cx,cz,cyaw,chead,pace,amount,run,ahead,back,left,right,pivot,phase,ground,hcol,nav,goals,ox,oz,zza,xxa,speed,vx,vz,wanted,rshare,pitch");
        level.getServer().tickRateManager().requestGameToSprint(60000);
        return true;
    }

    /** A walled stone floor with a few blocks about: a lone block, a short wall, a one-block ledge and a pillar pair. */
    private static void build(ServerLevel level) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -HALF - 1; x <= HALF + 1; x++) for (int z = -HALF - 1; z <= HALF + 1; z++) {
            boolean rim = Math.abs(x) == HALF + 1 || Math.abs(z) == HALF + 1;
            level.setBlock(new BlockPos(x, FLOOR - 1, z), stone, 2);
            for (int y = FLOOR; y <= FLOOR + 12; y++) level.setBlock(new BlockPos(x, y, z), rim && y <= FLOOR + 1 ? stone : air, 2);
        }
        for (int[] b : new int[][]{{5, 4}, {-7, -9}, {9, -15}, {-12, 3}, {16, 16}}) level.setBlock(new BlockPos(b[0], FLOOR, b[1]), stone, 2);
        for (int x = -4; x <= 3; x++) level.setBlock(new BlockPos(x, FLOOR, 14), stone, 2);
        for (int x = -20; x <= -14; x++) for (int z = -20; z <= -13; z++) level.setBlock(new BlockPos(x, FLOOR, z), stone, 2);
        for (int y = FLOOR; y <= FLOOR + 2; y++) { level.setBlock(new BlockPos(14, y, -2), stone, 2); level.setBlock(new BlockPos(14, y, 1), stone, 2); }
    }

    private static void purge(ServerLevel level) {
        if (tick % 40 != 0) return;
        for (Entity e : level.getEntities((Entity) null, ARENA)) if (e != body && e != standIn && !(e instanceof ServerPlayer)) e.discard();
    }

    private static void begin(ServerLevel level, Stretch next) {
        if (body != null) {
            if (stretch == Stretch.PARTNER) PartyManager.disconnect(standIn);
            body.discard();
        }
        if (standIn != null && next != Stretch.PARTNER) { leave(level, standIn); standIn = null; }
        stretch = next;
        tick = 0;
        legIndex = 0;
        legTick = 0;
        switch (next) {
            case WILD_ALONE -> body = DigimonEntity.spawnWild(level, species, 20, new Vec3(.5, FLOOR, .5));
            case WILD_WATCHED -> {
                body = DigimonEntity.spawnWild(level, species, 20, new Vec3(.5, FLOOR, .5));
                standIn = join(level, "Watcher", new Vec3(6.5, FLOOR, .5));
            }
            case PARTNER -> {
                standIn = join(level, "LocoTamer", new Vec3(.5, FLOOR, -7.5));
                DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
                digimon.initializeAs(species, 20);
                PartyMember member = PartyManager.give(standIn, digimon);
                body = PartyManager.live(parties, member);
                if (body == null) throw new IllegalStateException("the partner did not come out");
                body.snapTo(new Vec3(2.5, FLOOR, -7.5), 0, 0);
            }
        }
        body.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(10_000);
        body.setHealth(body.getMaxHealth());
        Constants.LOG.info("[locomotion] {} starts: {} with {}, {} and {}", next.name().toLowerCase(Locale.ROOT), species.id().getPath(),
                body.getMoveControl().getClass().getSimpleName(), body.getLookControl().getClass().getSimpleName(), body.getNavigation().getClass().getSimpleName());
        view = new ClientView(body);
        metrics = new Metrics(next.name().toLowerCase(Locale.ROOT), species);
    }

    // ---------------------------------------------------------------------------------------------------------- ticking

    private static void step(ServerLevel level) {
        tick++;
        body.setHealth(body.getMaxHealth());
        if (stretch == Stretch.PARTNER) driveTamer();
        else if (standIn != null) standIn.setPos(6.5, FLOOR, .5);
        view.tick(body);
        metrics.sample(body, view, standIn, stretch == Stretch.PARTNER);
        traceRow();
        boolean over = stretch == Stretch.PARTNER ? legIndex >= SCRIPT.size() : tick >= WILD_TICKS;
        if (!over) return;
        String line = metrics.verdict();
        if (!line.startsWith("PASS")) failed++;
        verdicts.add(line);
        Constants.LOG.info("[locomotion] {}", line);
        metrics.examples().forEach(e -> Constants.LOG.info("[locomotion]   {}", e));
        switch (stretch) {
            case WILD_ALONE -> begin(level, Stretch.WILD_WATCHED);
            case WILD_WATCHED -> begin(level, Stretch.PARTNER);
            case PARTNER -> finish(level);
        }
    }

    /** Moves the stand-in tamer along {@link #SCRIPT}, as a player's client would move them (a pace, a facing, a sprint). */
    private static void driveTamer() {
        if (legIndex >= SCRIPT.size()) return;
        Leg leg = SCRIPT.get(legIndex);
        Vec3 at = standIn.position();
        if (legTick == 0) {
            legFrom = at;
            legTo = switch (leg.kind()) {
                case Leg.THROUGH -> {
                    Vec3 away = body.position().subtract(at).multiply(1, 0, 1);
                    yield body.position().add(away.lengthSqr() < 1.0E-4 ? new Vec3(5, 0, 0) : away.normalize().scale(5));
                }
                case Leg.SIDE -> {
                    // leg.x() blocks off the body's left side, as it faces now
                    double r = body.yBodyRot * Mth.DEG_TO_RAD;
                    yield body.position().add(new Vec3(Math.cos(r), 0, Math.sin(r)).scale(leg.x()));
                }
                default -> new Vec3(leg.x() + .5, FLOOR, leg.z() + .5);
            };
        }
        Vec3 next = at;
        if (leg.kind() == Leg.CIRCLE) {
            double angle = legTick * leg.speed() / 5.0;
            next = new Vec3(leg.x() + .5 + 5 * Math.sin(angle), FLOOR, leg.z() + .5 - 5 * Math.cos(angle));
        } else if (leg.speed() > 0) {
            Vec3 d = legTo.subtract(at).multiply(1, 0, 1);
            double len = d.length();
            if (len > 1.0E-3) next = at.add(d.scale(Math.min(1, leg.speed() / len)));
        }
        Vec3 moved = next.subtract(at);
        standIn.setSprinting(leg.sprint() && moved.horizontalDistanceSqr() > 1.0E-4);
        if (moved.horizontalDistanceSqr() > 1.0E-6) {
            float yaw = (float) (Mth.atan2(moved.z, moved.x) * Mth.RAD_TO_DEG) - 90;
            standIn.setYRot(yaw);
            standIn.setYHeadRot(yaw);
        }
        standIn.setPos(next.x, FLOOR, next.z);
        standIn.setOnGround(true);
        if (++legTick >= leg.ticks()) { legIndex++; legTick = 0; }
    }

    private static void traceRow() {
        var g = view.gait;
        float[] s = g.shares(1);
        Vec3 owner = standIn == null ? Vec3.ZERO : standIn.position();
        trace.printf(Locale.ROOT, "%s,%d,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%.4f,%.4f,%.2f,%.2f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%s,%.3f,%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%.3f,%.2f%n",
                stretch.name().toLowerCase(Locale.ROOT), tick, body.getX(), body.getY(), body.getZ(), body.getYRot(), body.yBodyRot, body.getYHeadRot(),
                view.x, view.z, view.yaw, view.head, view.pace, g.amount(1), g.run(1), s[0], s[1], s[2], s[3], g.pivotTurn(1), g.phase(1),
                body.onGround() ? 1 : 0, body.horizontalCollision ? 1 : 0, body.getNavigation().isDone() ? 0 : 1,
                body.runningGoals().replace(',', ';'), owner.x, owner.z, body.zza, body.xxa, body.getSpeed(),
                body.getDeltaMovement().x, body.getDeltaMovement().z, body.getMoveControl().hasWanted() ? 1 : 0, g.runShare(1), body.getXRot());
    }

    private static void finish(ServerLevel level) {
        done = true;
        if (trace != null) trace.close();
        if (standIn != null) {
            if (stretch == Stretch.PARTNER) PartyManager.disconnect(standIn);
            leave(level, standIn);
        }
        Constants.LOG.info("[scenario] {} locomotion_checks {} {} of {} stretches", failed == 0 ? "PASS" : "FAIL",
                species == null ? "?" : species.id().getPath(), verdicts.size() - failed, verdicts.size());
        level.getServer().halt(false);
    }

    // ---------------------------------------------------------------------------------------------------------- stand-ins

    /** A stand-in player with no client: in the player list (so partners find their tamer and wild ones look at them). */
    private static ServerPlayer join(ServerLevel level, String name, Vec3 at) {
        MinecraftServer server = level.getServer();
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) {}
            @Override public boolean isConnected() { return true; }
            @Override public void flushChannel() {}
        };
        new net.minecraft.server.network.ServerGamePacketListenerImpl(server, connection, player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        player.setPos(at.x, at.y, at.z);
        server.getPlayerList().getPlayers().add(player);
        server.getPlayerList().getPlayersByUUID().put(player.getUUID(), player);
        level.addNewPlayer(player);
        return player;
    }

    private static void leave(ServerLevel level, ServerPlayer player) {
        level.getServer().getPlayerList().getPlayers().remove(player);
        level.getServer().getPlayerList().getPlayersByUUID().remove(player.getUUID());
        player.discard();
    }

    // ---------------------------------------------------------------------------------------------------------- the client's view

    /**
     * The body as a client sees it: the server's position, facing and head packets (sent as the server sends them, on a
     * change of a 4096th of a block or of a 256th of a turn), eased in over three ticks from each one as a client eases
     * them; its velocity as last sent, dying away while no packet eases it; and the gait a client plays from all that.
     */
    static final class ClientView {
        double x, y, z, xo, zo;
        float yaw, yawO, head;
        double vx, vz, pace;
        int steps;
        final com.digicube.entity.ClientHead drawnHead = new com.digicube.entity.ClientHead();
        double tx, ty, tz;
        float tyaw, thead;
        byte sentYaw, sentHead;
        double sentX, sentY, sentZ, sentVx, sentVz;
        final GaitDriver gait = new GaitDriver();
        final DigimonGait walk;
        final float scale;

        ClientView(DigimonEntity body) {
            x = tx = sentX = body.getX(); y = ty = sentY = body.getY(); z = tz = sentZ = body.getZ();
            yaw = tyaw = body.getYRot(); head = thead = body.getYHeadRot();
            sentYaw = Mth.packDegrees(body.getYRot()); sentHead = Mth.packDegrees(body.getYHeadRot());
            walk = body.getLocomotion().groundGait();
            scale = body.getBody().modelScale();
        }

        void tick(DigimonEntity body) {
            // what the server sends at the end of the tick
            byte yawByte = Mth.packDegrees(body.getYRot());
            boolean moved = new Vec3(body.getX() - sentX, body.getY() - sentY, body.getZ() - sentZ).lengthSqr() >= 7.6293945E-6F;
            if (moved || yawByte != sentYaw) {
                if (moved) { sentX = body.getX(); sentY = body.getY(); sentZ = body.getZ(); }
                if (yawByte != sentYaw) sentYaw = yawByte;
                tx = sentX; ty = sentY; tz = sentZ; tyaw = Mth.unpackDegrees(sentYaw);
                steps = 3;
            }
            Vec3 v = body.getDeltaMovement();
            double dv = (v.x - sentVx) * (v.x - sentVx) + (v.z - sentVz) * (v.z - sentVz);
            if (dv > 1.0E-7 || dv > 0 && v.horizontalDistanceSqr() == 0) { sentVx = v.x; sentVz = v.z; vx = v.x; vz = v.z; }
            byte headByte = Mth.packDegrees(body.getYHeadRot());
            if (Math.abs(headByte - sentHead) >= 1) { sentHead = headByte; thead = Mth.unpackDegrees(headByte); drawnHead.sent(thead); }
            // the client's tick
            xo = x; zo = z; yawO = yaw;
            if (steps > 0) {
                double a = 1.0 / steps;
                x = Mth.lerp(a, x, tx); y = Mth.lerp(a, y, ty); z = Mth.lerp(a, z, tz);
                yaw = (float) Mth.rotLerp(a, yaw, tyaw);
                steps--;
            } else { vx *= .98; vz *= .98; }
            if (drawnHead.seen()) head = drawnHead.tick(head);
            double dx = x - xo, dz = z - zo;
            pace = Math.sqrt(dx * dx + dz * dz);
            gait.beginTick();
            gait.tick(walk, scale, new GaitDriver.Step(dx, dz, dx, dz, false, yaw, Mth.wrapDegrees(yaw - yawO),
                    body.onGround(), body.onGround(), false, false, false));
        }
    }

    // ---------------------------------------------------------------------------------------------------------- metrics

    /** What a stretch counts, and its verdict. */
    static final class Metrics {
        final String name;
        final DigimonGait walk;
        final float scale;
        final Look look;
        int ticks, moving, running, crab, back, treadmill, stopSlide, shuffle, pivotTicks, headJumps, headJerks, attacking, teleports;
        double headMax, headAccelMax, walkTurnMax, strollPace;
        int strollSamples;
        float lastVisible = Float.NaN, lastVisibleStep;
        /** A turn on the spot in progress: its sum and length. */
        float standingTurn;
        int standingTurnTicks, standingTurns, smallTurns;
        double near = Double.MAX_VALUE, far, ownerDistanceSum;
        int ownerSamples, crabRun, treadRun, shuffleRun;
        final List<String> examples = new ArrayList<>();

        Metrics(String name, DigimonSpecies species) {
            this.name = name;
            walk = species.locomotion().groundGait();
            scale = species.body().modelScale();
            look = Look.of(species.id().getPath());
        }

        void sample(DigimonEntity body, ClientView view, ServerPlayer owner, boolean partner) {
            ticks++;
            var g = view.gait;
            float[] s = g.shares(1);
            float amount = g.amount(1), pivot = g.pivotTurn(1), run = g.run(1);
            double full = walk.fullSpeed(scale);
            boolean fighting = body.isAttacking() || body.getTarget() != null;
            if (fighting) attacking++;
            if (view.pace > 3) { teleports++; lastVisible = Float.NaN; return; }
            // (the first ticks settle what the spawn left: its first facing, its first look)
            if (ticks <= 10) return;
            if (view.pace > .1 * full) { moving++; if (run > .5) running++; }
            if (view.pace > .1 * full && body.runningGoals().contains("Stroll")) { strollPace += view.pace; strollSamples++; }
            // a crab: stepping sideways while it walks no enemy (the walk's clips carry what the run leaves them)
            boolean crabbing = !fighting && view.pace > .1 * full && amount > .25 && (s[2] + s[3]) * (1 - run) > .3;
            if (crabbing) { crab++; if (crabRun++ == 0) example("crab", body, view, owner); } else crabRun = 0;
            if (!fighting && amount > .25 && s[1] * (1 - run) > .3) back++;
            float step = g.phase(1) - g.phase(0);
            // a treadmill: the legs stepping while the body hardly moves, nor turns, kept up (a stop's last step settles
            // over a couple of ticks)
            boolean tread = amount > .3 && step > .3 && view.pace < .15 * full && Math.abs(pivot) < .3;
            if (tread) {
                treadRun++;
                if (treadRun == 4) { treadmill += 4; example("treadmill", body, view, owner); } else if (treadRun > 4) treadmill++;
            } else treadRun = 0;
            // a stop that slides: the stride held while the body stands, eased back to the stance (the feet slide to it)
            if (amount > .3 && step < .05 && view.pace < .1 * full) stopSlide++;
            // a shuffle: small quick steps, kept up (a step setting off or a turn starting grows out of a small one)
            boolean shuf = amount > .04 && amount < .3 && step > .5;
            if (shuf) {
                shuffleRun++;
                if (shuffleRun == 4) { shuffle += 4; example("shuffle", body, view, owner); } else if (shuffleRun > 4) shuffle++;
            } else shuffleRun = 0;
            if (Math.abs(pivot) > .3 && amount > .2) pivotTicks++;
            // turns on the spot
            float turned = Math.abs(Mth.wrapDegrees(view.yaw - view.yawO));
            // (a turn on a path, setting off or arriving, is its way coming round: only a turn with no path counts)
            if (view.pace < .1 * full && turned > .3 && body.getNavigation().isDone()) { standingTurn += turned; standingTurnTicks++; }
            else if (standingTurnTicks > 0) {
                standingTurns++;
                // (under a degree and a half the hips take it up: no step)
                if (standingTurn >= 1.5F && standingTurn < 15) { smallTurns++; if (smallTurns <= 4) examples.add(String.format(Locale.ROOT,
                        "small turn on the spot: %.1f deg over %d ticks at %d (%s)", standingTurn, standingTurnTicks, ticks, body.runningGoals())); }
                standingTurn = 0; standingTurnTicks = 0;
            }
            if (view.pace >= .1 * full) walkTurnMax = Math.max(walkTurnMax, turned);
            // the head as the model draws it: the look's shares within their limits
            float visible = look.visible(Mth.wrapDegrees(view.head - view.yaw)) + view.yaw;
            if (!Float.isNaN(lastVisible)) {
                float v = Mth.wrapDegrees(visible - lastVisible);
                headMax = Math.max(headMax, Math.abs(v));
                if (Math.abs(v) > 11) { headJumps++; if (headJumps <= 4) example("head jump " + String.format(Locale.ROOT, "%.1f", v), body, view, owner); }
                float accel = Math.abs(v - lastVisibleStep);
                headAccelMax = Math.max(headAccelMax, accel);
                if (accel > 6) headJerks++;
                lastVisibleStep = v;
            }
            lastVisible = visible;
            if (partner && owner != null) {
                double d = body.position().distanceTo(owner.position());
                near = Math.min(near, d); far = Math.max(far, d); ownerDistanceSum += d; ownerSamples++;
            }
        }

        private void example(String what, DigimonEntity body, ClientView view, ServerPlayer owner) {
            if (examples.stream().filter(e -> e.startsWith(what.split(" ")[0])).count() >= 4) return;
            Vec3 v = body.getDeltaMovement();
            float travel = (float) (Mth.atan2(view.z - view.zo, view.x - view.xo) * Mth.RAD_TO_DEG) - 90;
            examples.add(String.format(Locale.ROOT, "%s at %d: pace %.3f, travel %.0f deg off the body, velocity %.3f, hcol %s, nav %s, goals %s%s",
                    what, ticks, view.pace, Mth.wrapDegrees(travel - view.yaw), v.horizontalDistance(), body.horizontalCollision,
                    body.getNavigation().isDone() ? "idle" : "on a path", body.runningGoals(),
                    owner == null ? "" : String.format(Locale.ROOT, ", %.1f blocks from the player", body.position().distanceTo(owner.position()))));
        }

        List<String> examples() { return examples; }

        String verdict() {
            int crabMax = moving / 100, treadMax = ticks / 200, shuffleMax = ticks / 100, jerkMax = ticks / 200;
            boolean wild = !name.equals("partner");
            double meanDistance = ownerSamples == 0 ? 0 : ownerDistanceSum / ownerSamples;
            boolean pass = crab <= crabMax && treadmill <= treadMax && shuffle <= shuffleMax && smallTurns <= 2 && headJumps == 0
                    && headJerks <= jerkMax && (!wild || running <= moving / 10) && (wild || teleports == 0 && meanDistance < 7);
            return String.format(Locale.ROOT,
                    "%s %s: %d ticks, moving %d (at a run %d, strolling at %.3f blocks a tick), crab %d (at most %d), backing %d, treadmill %d (at most %d), "
                            + "stop slides %d, shuffle %d (at most %d), turns on the spot %d (under 15 deg %d, at most 2), pivot ticks %d, "
                            + "head fastest %.1f deg/tick (jumps past 11: %d), sharpest change %.1f (jerks past 6: %d, at most %d), "
                            + "fastest turn walking %.1f deg/tick, teleports %d, fighting %d%s",
                    pass ? "PASS" : "FAIL", name, ticks, moving, running, strollSamples == 0 ? 0 : strollPace / strollSamples, crab, crabMax, back,
                    treadmill, treadMax, stopSlide, shuffle, shuffleMax, standingTurns, smallTurns, pivotTicks, headMax, headJumps, headAccelMax,
                    headJerks, jerkMax, walkTurnMax, teleports, attacking,
                    ownerSamples == 0 ? "" : String.format(Locale.ROOT, ", from the tamer %.1f to %.1f blocks (mean %.1f)", near, far, meanDistance));
        }
    }

    /** The model's head look (its catalog {@code look}): the head's share and its neck's, each within its own limit. */
    record Look(float headShare, float headYaw, List<float[]> carry) {
        float visible(float relative) {
            float sum = Mth.clamp(relative * headShare, -headYaw, headYaw);
            for (float[] c : carry) sum += Mth.clamp(relative * c[0], -c[1], c[1]);
            return sum;
        }

        static Look of(String model) {
            try (var in = LocomotionScenario.class.getResourceAsStream("/assets/digicube/models/entity/ground_models.json")) {
                if (in == null) return new Look(1, 75, List.of());
                JsonObject models = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("models");
                if (!models.has(model) || !models.getAsJsonObject(model).has("look")) return new Look(1, 75, List.of());
                JsonObject l = models.getAsJsonObject(model).getAsJsonObject("look");
                List<float[]> carry = new ArrayList<>();
                float share = 1;
                if (l.has("carry")) for (var e : l.getAsJsonArray("carry")) {
                    JsonObject c = e.getAsJsonObject();
                    carry.add(new float[]{c.get("share").getAsFloat(), c.get("yaw").getAsFloat()});
                    share -= c.get("share").getAsFloat();
                }
                return new Look(share, l.get("yaw").getAsFloat(), carry);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
