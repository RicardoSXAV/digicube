package com.digicube.fabric;

import com.digicube.entity.DigimonEntity;
import com.digicube.entity.ai.AerialInput;
import com.digicube.entity.ai.AerialInputPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Accept aerial controls only from the creature's controlling owner. */
public final class FabricAerialNetworking {
    private FabricAerialNetworking() {}
    /** Register the input payload and server-thread permission checks. */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(AerialInputPayload.TYPE,AerialInputPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AerialInputPayload.TYPE,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (payload.buttons()<0 || payload.buttons()>3) return;
            if (player.getVehicle() instanceof DigimonEntity mount && mount.getId()==payload.entityId()
                    && mount.getControllingPassenger()==player && mount.aerialMount()!=null) {
                mount.aerialRiding().accept(AerialInput.fromBits(payload.buttons()));
            }
        }));
    }
}
