package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.fabric.client.render.BlueBlasterRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;

/** Original Blender stepped cuboids and breath sheets, clipped to the shared physical jet. */
public final class IceBlastModel extends EntityModel<BlueBlasterRenderState> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Constants.id("ice_blast_fx"),"main");
    private final NativeAnimationSet animation;
    private final java.util.List<ModelPart> particles=new java.util.ArrayList<>();
    public static LayerDefinition createBodyLayer() {
        return NativeModelGeometry.createLayer(Constants.id("models/entity/ice_blast_fx.mesh.json"));
    }
    public IceBlastModel(ModelPart root) {
        super(NativeModelGeometry.apply(root,Constants.id("models/entity/ice_blast_fx.mesh.json")));
        animation=new NativeAnimationSet(root,Constants.id("models/entity/ice_blast_fx.animation.json"));
        for(var entry:java.util.Map.of("ice",56,"breath",18,"snow",28).entrySet())
            for(int i=0;i<entry.getValue();i++) particles.add(root.getChild("FX_"+entry.getKey()+"_"+String.format(java.util.Locale.ROOT,"%02d",i)));
    }
    @Override public void setupAnim(BlueBlasterRenderState state) {
        super.setupAnim(state);animation.apply("effect",state.ageInTicks,1);
        for(var part:particles) {
            float depth=-part.z/16;
            part.visible=state.length>.05F && depth>=0 && depth+.55F<=state.length;
        }
    }
}
