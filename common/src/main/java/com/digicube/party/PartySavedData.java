package com.digicube.party;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One registry per world save, shared by all dimensions; no mutable process-global state. */
public final class PartySavedData extends SavedData {
    public static final Codec<PartySavedData> CODEC = PartyRoster.CODEC.fieldOf("members").codec()
            .xmap(PartySavedData::new, PartySavedData::roster);
    public static final SavedDataType<PartySavedData> TYPE = new SavedDataType<>(
            Constants.id("parties"), PartySavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final PartyRoster roster;
    final Map<UUID, DigimonEntity> live = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();

    public PartySavedData() { this(new PartyRoster()); }
    private PartySavedData(PartyRoster roster) { this.roster = roster; }
    public PartyRoster roster() { return roster; }
    public Session session(UUID player) { return sessions.computeIfAbsent(player, ignored -> new Session()); }
    public void forgetSession(UUID player) { sessions.remove(player); }

    public static PartySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Connection-local UI state; deliberately excluded from disk persistence. */
    public static final class Session {
        public final PartySyncState sync = new PartySyncState();
        public boolean open;
        public int page;
        public int lastActionTick = Integer.MIN_VALUE / 2;
    }
}
