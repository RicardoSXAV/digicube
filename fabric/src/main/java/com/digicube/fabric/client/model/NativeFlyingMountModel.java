package com.digicube.fabric.client.model;

import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Shared native clip and rider adapter. Adding an aerial species requires assets and data only. */
public final class NativeFlyingMountModel extends EntityModel<DigimonRenderState> implements AnimatedRiderModel {
    private final NativeAnimationSet animations;
    private final ModelPart[] riderPath;
    private final Vector3f riderPoint;
    private final RiderPose riderPose;
    private final double margin;
    private final float landingContact, approachDistance, liftTick, takeoffTicks;
    private static Identifier asset(Identifier id,String suffix) { return id.withPath("models/entity/"+id.getPath()+suffix); }
    /** Load a species' exact native quads.
     * @param id species identifier
     * @return its model layer */
    public static LayerDefinition createLayer(Identifier id) { return NativeModelGeometry.createLayer(asset(id,".mesh.json")); }
    /** Bind native clips and rider metadata.
     * @param root baked mesh root
     * @param id species identifier */
    public NativeFlyingMountModel(ModelPart root,Identifier id) {
        super(NativeModelGeometry.apply(root,asset(id,".mesh.json")));
        animations=new NativeAnimationSet(root,asset(id,".animation.json"));
        var resource=asset(id,".presentation.json");
        try (var input=getClass().getResourceAsStream("/assets/"+resource.getNamespace()+"/"+resource.getPath())) {
            if (input==null) throw new IllegalStateException("Missing flight presentation "+resource);
            var j=GsonHelper.parse(new InputStreamReader(input,StandardCharsets.UTF_8));
            var p=root;
            var chain=j.getAsJsonArray("rider_path");riderPath=new ModelPart[chain.size()];p=root;
            for (int i=0;i<chain.size();i++) { p=p.getChild(chain.get(i).getAsString());riderPath[i]=p; }
            var v=j.getAsJsonArray("rider_point");riderPoint=new Vector3f(v.get(0).getAsFloat(),v.get(1).getAsFloat(),v.get(2).getAsFloat());
            riderPose=new RiderPose(j.get("rider_leg_pitch").getAsFloat(),j.get("rider_leg_splay").getAsFloat(),0);
            margin=j.get("culling_margin").getAsDouble();
            landingContact=j.get("landing_contact_tick").getAsFloat();
            approachDistance=j.get("approach_distance").getAsFloat();
            liftTick=j.get("lift_tick").getAsFloat();
            takeoffTicks=j.get("takeoff_ticks").getAsFloat();
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read flight presentation",e); }
    }
    @Override public void setupAnim(DigimonRenderState s) {
        super.setupAnim(s);
        float t=s.flightPhaseTime;
        switch (s.flightPhase) {
            case GROUNDED -> {
                animations.apply("idle",s.ageInTicks,1-s.groundAnimationAmount);
                animations.blend("walk",s.groundAnimationAmount,s.groundAnimationPhase,s.groundAnimationAmount>0?1:0);
            }
            case TAKEOFF -> {
                animations.blend("walk",s.groundAnimationAmount,s.groundAnimationPhase,1-smooth(t/5));
                animations.apply("takeoff",t,1);
            }
            case FLYING -> animations.apply("fly",s.flightLoopTime,1);
            case APPROACH -> {
                float blend=smooth(1-s.flightGroundDistance/approachDistance);
                animations.apply("fly",s.flightLoopTime,1-blend);
                animations.apply("land",landingContact*blend,blend);
            }
            case LANDING -> animations.apply("land",landingContact+t,1);
        }
        float air=s.flightPhase.airborne()?smooth(s.flightPhase==com.digicube.entity.ai.FlightPhase.TAKEOFF?(t-liftTick)/(takeoffTicks-liftTick):1):0;
        if (s.flightPhase==com.digicube.entity.ai.FlightPhase.APPROACH) air*=smooth(s.flightGroundDistance/approachDistance);
        // This is a rigid steering transform over the native pose, so every limb stays attached.
        // Pivot around the animated seat to keep the rider and first-person clearance stable.
        if (air > 0) {
            Vector3f seat = seatPosition();
            root.zRot += s.aerialBank * Mth.DEG_TO_RAD * air;
            root.xRot += s.aerialPitch * Mth.DEG_TO_RAD * air;
            Vector3f shift = seat.sub(seatPosition()).mul(16);
            root.x += shift.x;
            root.y += shift.y;
            root.z += shift.z;
        }
    }
    @Override public Vec3 riderOffset(DigimonRenderState s) {
        setupAnim(s);
        var v=seatPosition();
        return new Vec3(v.x,1.5-v.y,-v.z).scale(s.modelScale).subtract(s.mountAnchor);
    }
    private Vector3f seatPosition() {
        var pose=new PoseStack();root.translateAndRotate(pose);
        for (var p:riderPath) p.translateAndRotate(pose);
        return pose.last().pose().transformPosition(riderPoint,new Vector3f());
    }
    @Override public RiderPose riderPose() { return riderPose; }
    @Override public double cullingMargin() { return margin; }
    private static float smooth(float v) { float x=Mth.clamp(v,0,1);return x*x*(3-2*x); }
}
