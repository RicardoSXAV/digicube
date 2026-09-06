package com.digicube.fabric.party;

import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyHealthPayload;
import com.digicube.party.PartyManager;
import com.digicube.party.PartyMember;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartySavedData;
import com.digicube.party.PartySnapshotPayload;
import com.digicube.registry.DCItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/** Fabric event/transport adapter. Gameplay and save data stay in common. */
public final class FabricPartyNetworking {
    private FabricPartyNetworking() {}

    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(PartySnapshotPayload.TYPE, PartySnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PartyHealthPayload.TYPE, PartyHealthPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PartyActionPayload.TYPE, PartyActionPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PartyActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> handle(context.player(), payload)));
        UseItemCallback.EVENT.register((player, level, hand) -> use(player, hand));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> use(player, hand));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> use(player, hand));
        ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, existing) -> PartyManager.allowLoad(entity, level));
        ServerEntityEvents.ENTITY_LOAD.register(PartyManager::loaded);
        ServerEntityEvents.ENTITY_UNLOAD.register(PartyManager::unloaded);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player, false, ""));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PartyManager.disconnect(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            PartyManager.tick(server);
            PartySavedData data = PartySavedData.get(server);
            boolean periodic = server.getTickCount() % 20 == 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PartySavedData.Session session = data.session(player.getUUID());
                if (periodic || session.sync.needsSnapshot()) send(player, false, "");
                else if (ServerPlayNetworking.canSend(player, PartyHealthPayload.TYPE)) {
                    PartyHealthPayload health = session.sync.takeHealthChanges();
                    if (health != null) ServerPlayNetworking.send(player, health);
                }
            }
        });
    }

    private static InteractionResult use(Player player, InteractionHand hand) {
        if (!player.getItemInHand(hand).is(DCItems.DIGIVICE) || player.isSpectator()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) {
            PartySavedData data = PartySavedData.get(serverPlayer.level().getServer());
            data.session(player.getUUID()).open = true;
            send(serverPlayer, true, "");
        }
        return InteractionResult.SUCCESS;
    }

    private static void handle(ServerPlayer player, PartyActionPayload payload) {
        PartySavedData data = PartySavedData.get(player.level().getServer());
        PartySavedData.Session session = data.session(player.getUUID());
        if (payload.action() == PartyActionPayload.CLOSE) {
            session.open = false;
            return;
        }
        if (!session.open || !player.isAlive() || player.isSpectator()
                || !player.getInventory().contains(stack -> stack.is(DCItems.DIGIVICE))) return;
        int tick = player.level().getServer().getTickCount();
        if (tick - session.lastActionTick < 2) {
            send(player, false, "gui.digicube.party.wait");
            return;
        }
        session.lastActionTick = tick;
        String message = "";
        if (payload.action() == PartyActionPayload.PAGE) session.page = Math.max(0, payload.value());
        else if (payload.action() == PartyActionPayload.SELECT) message = PartyManager.select(player, payload.member(), payload.value());
        else return;
        send(player, false, message);
    }

    private static void send(ServerPlayer player, boolean open, String message) {
        if (!ServerPlayNetworking.canSend(player, PartySnapshotPayload.TYPE)) return;
        PartySavedData data = PartySavedData.get(player.level().getServer());
        PartySavedData.Session session = data.session(player.getUUID());
        List<PartyMember> owned = data.roster().owned(player.getUUID());
        session.page = Math.min(session.page, Math.max(0, (owned.size() - 1) / PartySnapshotPayload.PAGE_SIZE));
        int first = session.page * PartySnapshotPayload.PAGE_SIZE;
        List<PartyMemberView> page = session.open ? owned.subList(first,
                Math.min(owned.size(), first + PartySnapshotPayload.PAGE_SIZE)).stream()
                .map(member -> PartyMemberView.of(data, member)).toList() : List.of();
        PartySnapshotPayload snapshot = new PartySnapshotPayload(open, session.page, owned.size(),
                data.roster().party(player.getUUID()).stream().map(member -> PartyMemberView.of(data, member)).toList(),
                page, message);
        if (session.sync.updateSnapshot(snapshot)) ServerPlayNetworking.send(player, snapshot);
    }
}
