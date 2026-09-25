package com.digicube.digivice;

import com.digicube.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/**
 * Cosmetic acknowledgement after custody has already reached the owner, sent to the owner and to the players who can
 * see them ({@code recipient} is the owner's entity id; watchers get no credential, {@link #token} is nil for them).
 * {@code flyRange} is the owner's view distance in blocks when it was sent, so the server's cooldown and every client's
 * flight share one journey. {@code sameDimension} false means the device comes from nowhere in sight: another
 * dimension, or a lost device summoned back.
 */
public record DigiviceRecallPayload(UUID token, InteractionHand hand, int recipient, UUID sourceEntity, Identifier dimension,
                                   Vec3 source, Vec3 receiver, boolean sameDimension, float pitch, float yaw,
                                   float flyRange) implements CustomPacketPayload {
    public static final UUID NO_TOKEN = new UUID(0, 0);
    public static final Type<DigiviceRecallPayload> TYPE = new Type<>(Constants.id("digivice_recall"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DigiviceRecallPayload> STREAM_CODEC = StreamCodec.of(
            (b,p) -> {
                b.writeUUID(p.token()); b.writeEnum(p.hand()); b.writeVarInt(p.recipient()); b.writeUUID(p.sourceEntity()); b.writeIdentifier(p.dimension());
                writePoint(b,p.source()); writePoint(b,p.receiver()); b.writeBoolean(p.sameDimension()); b.writeFloat(p.pitch()); b.writeFloat(p.yaw());
                b.writeFloat(p.flyRange());
            },
            b -> new DigiviceRecallPayload(b.readUUID(), b.readEnum(InteractionHand.class), b.readVarInt(), b.readUUID(), b.readIdentifier(),
                    readPoint(b),readPoint(b),b.readBoolean(),b.readFloat(),b.readFloat(),b.readFloat()));
    private static void writePoint(RegistryFriendlyByteBuf b, Vec3 p) { b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z); }
    private static Vec3 readPoint(RegistryFriendlyByteBuf b) { return new Vec3(b.readDouble(),b.readDouble(),b.readDouble()); }
    public RecallJourney journey() { return RecallJourney.plan(source.distanceTo(receiver),sameDimension,flyRange); }
    /** The copy other players get: the same journey, no credential. */
    public DigiviceRecallPayload withoutToken() {
        return new DigiviceRecallPayload(NO_TOKEN, hand, recipient, sourceEntity, dimension, source, receiver, sameDimension, pitch, yaw, flyRange);
    }
    @Override public Type<DigiviceRecallPayload> type() { return TYPE; }
}
