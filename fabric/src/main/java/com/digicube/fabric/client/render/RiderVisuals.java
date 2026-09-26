package com.digicube.fabric.client.render;

import com.digicube.fabric.client.model.AnimatedRiderModel.RiderPose;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/** Per-render passenger data; never stores a player or world globally. */
public final class RiderVisuals {
    /** Custom pose is present only while riding a model that supplies one. */
    public static final RenderStateDataKey<RiderPose> POSE = RenderStateDataKey.create();
    /** Degrees the seat has turned with the mount's animation (a spinning strike); the rider's body turns with it. */
    public static final RenderStateDataKey<Float> YAW = RenderStateDataKey.create();

    private RiderVisuals() {}
}
