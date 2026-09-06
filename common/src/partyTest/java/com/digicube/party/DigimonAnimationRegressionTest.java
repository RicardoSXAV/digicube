package com.digicube.party;

import com.digicube.Constants;
import com.digicube.entity.DigimonAnimationEvents;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityEvent;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/** Guards against vanilla event collisions and checks Minecraft's actual signed-byte packet codec. */
final class DigimonAnimationRegressionTest {
    private DigimonAnimationRegressionTest() {}

    static void run() {
        Set<Byte> events = new HashSet<>();
        check(events.add(DigimonAnimationEvents.CANCEL), "cancellation has a unique event id");
        for (int index = 0; index < DigimonAnimationEvents.MAX_ATTACKS; index++) {
            for (boolean mirrored : new boolean[]{false, true}) {
                byte event = DigimonAnimationEvents.start(index, mirrored);
                check(events.add(event), "every attack and side has its own event id");
                check(DigimonAnimationEvents.attackIndex(event) == index, "attack index round-trip");
                check(DigimonAnimationEvents.mirrored(event) == mirrored, "normal and mirrored attacks remain distinct");
            }
        }
        check(DigimonAnimationEvents.CANCEL != EntityEvent.SNIFFER_DIGGING_SOUND,
                "cancelling an attack must never enter the client's Sniffer cast");
        try {
            for (var field : EntityEvent.class.getFields()) {
                if (field.getType() == byte.class && Modifier.isStatic(field.getModifiers())) {
                    check(!events.contains(field.getByte(null)), "custom animation overlaps vanilla event " + field.getName());
                }
            }
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Cannot inspect Minecraft's event constants", exception);
        }

        for (int value = Byte.MIN_VALUE; value <= Byte.MAX_VALUE; value++) {
            byte event = (byte) value;
            boolean start = events.contains(event) && event != DigimonAnimationEvents.CANCEL;
            check((DigimonAnimationEvents.attackIndex(event) >= 0) == start,
                    "unrelated entity events must not trigger a Digimon animation");
            if (!start) check(!DigimonAnimationEvents.mirrored(event), "unrelated event cannot be a mirrored attack");
        }
        for (int index : new int[]{-1, DigimonAnimationEvents.MAX_ATTACKS, Integer.MAX_VALUE}) {
            boolean rejected = false;
            try { DigimonAnimationEvents.start(index, false); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "out-of-range attack index rejected before byte conversion");
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (byte event : events) {
                buffer.clear();
                // The real packet writes the entity id as an int followed by a signed byte.
                buffer.writeInt(123);
                buffer.writeByte(event);
                ClientboundEntityEventPacket packet = ClientboundEntityEventPacket.STREAM_CODEC.decode(buffer);
                check(packet.getEventId() == event, "vanilla packet preserves negative event ids on decode");
                buffer.clear();
                ClientboundEntityEventPacket.STREAM_CODEC.encode(buffer, packet);
                check(buffer.readableBytes() == 5 && buffer.readInt() == 123 && buffer.readByte() == event,
                        "vanilla packet preserves entity identity and signed event id on encode");
            }
        } finally {
            buffer.release();
        }
        Constants.LOG.info("Combat animation checks passed: 33 distinct events, no vanilla collisions, both sides and real packet codec.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
