package com.digicube.fabric.client.digivice;

import com.digicube.Constants;
import com.digicube.digivice.DigiviceLocatorPayload;
import com.digicube.digivice.DigiviceRemovedPayload;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.client.resources.WaypointStyle;
import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;

public final class DigiviceLocatorRegressionTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var dimension = Level.OVERWORLD.identifier();
        var mine = new DigiviceLocatorPayload.Marker(UUID.randomUUID(), new Vec3(1000,64,20),125,true);
        var other = new DigiviceLocatorPayload.Marker(UUID.randomUUID(), new Vec3(10,64,20),-1,false);
        var snapshot = new DigiviceLocatorPayload(dimension,List.of(mine,other));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            DigiviceLocatorPayload.STREAM_CODEC.encode(buffer,snapshot);
            check(snapshot.equals(DigiviceLocatorPayload.STREAM_CODEC.decode(buffer)), "snapshot codec preserves owner flag, position and beam time");
            var removed = new DigiviceRemovedPayload(mine.entity());
            DigiviceRemovedPayload.STREAM_CODEC.encode(buffer,removed);
            check(removed.equals(DigiviceRemovedPayload.STREAM_CODEC.decode(buffer)), "immediate removal packet round trip");
            buffer.clear(); buffer.writeIdentifier(dimension); buffer.writeVarInt(65);
            try { DigiviceLocatorPayload.STREAM_CODEC.decode(buffer); throw new AssertionError("unbounded packet accepted"); }
            catch (IllegalArgumentException expected) { check(true,"snapshot count bounded"); }
        } finally { buffer.release(); }
        var signals = new DigiviceSignals(); signals.accept(snapshot);
        check(signals.owned(dimension).equals(mine), "only the owner marker is selected for the HUD");
        check(signals.markers(Level.NETHER.identifier()).isEmpty(), "other dimensions never show stale signals");
        var manager = new ClientWaypointManager();
        var waypoint = new DigiviceWaypoint(); waypoint.update(manager, signals.owned(dimension));
        check(manager.hasWaypoints(), "own drop activates vanilla locator bar");
        waypoint.update(manager, signals.owned(dimension));
        signals.remove(mine.entity()); waypoint.update(manager, signals.owned(dimension));
        check(!manager.hasWaypoints() && signals.markers(dimension).equals(List.of(other)), "pickup removes HUD and beam immediately, without a tick or snapshot refresh");
        var playerDot = TrackedWaypoint.empty(UUID.randomUUID()); manager.trackWaypoint(playerDot);
        signals.accept(snapshot); waypoint.update(manager,signals.owned(dimension));
        waypoint.update(manager,null);
        check(manager.hasWaypoints(), "Digivice withdrawal preserves other player dots");
        manager.untrackWaypoint(playerDot);
        check(!manager.hasWaypoints(), "updates do not leave duplicate direction markers");
        signals.accept(snapshot); waypoint.update(manager,signals.owned(dimension));
        var next = new ClientWaypointManager(); waypoint.update(next,null);
        check(!manager.hasWaypoints() && !next.hasWaypoints(), "connection or dimension change removes previous waypoint");
        check(DigiviceWaypoint.create(mine).icon().style.identifier().equals(Constants.id("digivice")), "uses the distinct Digivice style");
        try (var reader = new InputStreamReader(Objects.requireNonNull(DigiviceLocatorRegressionTest.class.getResourceAsStream("/assets/digicube/waypoint_style/digivice.json")))) {
            var style = WaypointStyle.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseReader(reader)).getOrThrow();
            check(style.sprite(1).equals(Constants.id("hud/locator_bar_dot/digivice")) && style.sprite(5000).equals(style.sprite(1)), "vanilla style codec resolves the identifiable sprite near and far");
        }
        var icon = ImageIO.read(Objects.requireNonNull(DigiviceLocatorRegressionTest.class.getResourceAsStream("/assets/digicube/textures/gui/sprites/hud/locator_bar_dot/digivice.png")));
        check(icon.getWidth()==9 && icon.getHeight()==9 && (icon.getRGB(0,0)>>>24)==0, "native 9px locator icon has a transparent silhouette");
        Constants.LOG.info("[digivice-locator] RESULT {} checks passed",checks);
    }
    private static void check(boolean condition,String description) {
        if (!condition) throw new AssertionError(description);
        checks++; Constants.LOG.info("[digivice-locator] PASS {}",description);
    }
}
