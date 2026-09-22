package com.digicube.party;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Requests contain no owner or entity NBT; the authenticated sender owns the operation. */
public record PartyActionPayload(int action, UUID member, int value, long generation, long sequence) implements CustomPacketPayload {
    public PartyActionPayload(int action,UUID member,int value){this(action,member,value,-1,-1);}
    public static final int CLOSE = 0;
    public static final int PAGE = 1;
    public static final int SELECT = 2;
    public static final int EVOLVE = 3;
    public static final int REVERT = 4;
    public static final int ORIGIN = 5;
    /** Command wheel orders. They carry no evolution intent, so generation and sequence stay unset. */
    public static final int HOLD = 6;
    public static final int FOLLOW = 7;
    public static final int CANCEL_TARGET = 8;
    /** Recall: the partner leaves the party and goes into the Digivice. */
    public static final int RECALL = 9;
    /** Opens the Digivice from the command wheel; {@code member} is unused. */
    public static final int OPEN = 10;
    /** Mounted combat: the rider casts the attack in rider slot {@code value} of the Digimon it sits on; {@code member} is unused. */
    public static final int RIDER_ATTACK = 11;
    /** The rider let go of a held attack (a stream stops); {@code value} is the rider slot. */
    public static final int RIDER_RELEASE = 12;
    /** The owner asks the deployed partner it aims at for a ride; the server checks mount, reach and ownership. */
    public static final int RIDE = 13;
    public static final UUID NO_MEMBER = new UUID(0, 0);
    public static final Type<PartyActionPayload> TYPE = new Type<>(Constants.id("party_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PartyActionPayload> STREAM_CODEC =
            StreamCodec.ofMember(PartyActionPayload::write, buffer ->
                    new PartyActionPayload(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(),buffer.readVarLong(),buffer.readVarLong()));

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(action);
        buffer.writeUUID(member);
        buffer.writeVarInt(value);buffer.writeVarLong(generation);buffer.writeVarLong(sequence);
    }

    @Override public Type<PartyActionPayload> type() { return TYPE; }
}
