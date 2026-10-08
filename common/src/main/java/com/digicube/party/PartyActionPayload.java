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
    /**
     * Digivolve, or grow a Baby II. {@code value} is 1 + the index of the chosen route among the species' supported routes
     * ({@link com.digicube.digimon.EvolutionRules#routes}), or 0 for the Champion the partner is bound to.
     */
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
    /**
     * A jet swimmer's rider: its mount started a pulse ({@code value}: 256 when it thrusts, plus its length in ticks),
     * which the rider's client runs; the server passes it on to everyone else who sees the mount.
     */
    public static final int JET_PULSE = 14;
    /**
     * A sea mount's rider: its mount started a barrel roll ({@code value}: 1 to the left, 0 to the right), which the
     * rider's client runs; the server passes it on to everyone else who sees the mount.
     */
    public static final int SWIM_ROLL = 15;
    /**
     * Universal control, from the command wheel: the partner casts the attack in its sheet slot {@code value} at its
     * target, or at the enemy on the owner's crosshair ({@link PartyManager#attack}).
     */
    public static final int ATTACK = 16;
    /** Universal control: the attack in sheet slot {@code value >> 1} goes on AUTO ({@code value & 1} is 1) or on manual (0). */
    public static final int AUTO_ATTACK = 17;
    /** The tamer opened {@code member}'s digivolution tree: the routes ready now stop being news. */
    public static final int NOTICE = 18;
    /**
     * The SCAN page's CONVERT: a Digitama's worth of data of the family at index {@code value} of {@link
     * com.digicube.digimon.DigimonFamilies#all} becomes a Digitama in the Digivice; {@code member} is unused.
     */
    public static final int CONVERT = 19;
    public static final UUID NO_MEMBER = new UUID(0, 0);

    /** The {@link #EVOLVE} value for {@code route} of {@code species}: 1 + its index, or 0 when it is not one of its routes. */
    public static int routeValue(net.minecraft.resources.Identifier species, net.minecraft.resources.Identifier route) {
        var routes = com.digicube.digimon.EvolutionRules.routes(species);
        for (int i = 0; i < routes.size(); i++) if (routes.get(i).target().equals(route)) return i + 1;
        return 0;
    }

    /** The route an {@link #EVOLVE} {@code value} names for {@code species}, or null for 0 or a value out of range. */
    public static net.minecraft.resources.Identifier route(net.minecraft.resources.Identifier species, int value) {
        var routes = com.digicube.digimon.EvolutionRules.routes(species);
        return value < 1 || value > routes.size() ? null : routes.get(value - 1).target();
    }

    /** The {@link #AUTO_ATTACK} value for {@code slot}. */
    public static int autoValue(int slot, boolean auto) {
        return slot << 1 | (auto ? 1 : 0);
    }
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
