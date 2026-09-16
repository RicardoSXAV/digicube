package com.digicube.fabric.client.model;
import com.digicube.Constants;
import com.digicube.entity.TectonicWave;
import com.digicube.fabric.client.render.NativeEffectState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;

/** Original cuboids and flat pixel sheets, including native scale and visibility curves. */
public final class NativeEffectModel extends EntityModel<NativeEffectState> {
    private final NativeAnimationSet animation;
    private final ModelPart rootPart;
    private final java.util.Map<String,ModelPart> cells=new java.util.HashMap<>();
    private final ModelPart[] spikes=new ModelPart[6];
    public static ModelLayerLocation layer(String name) {return new ModelLayerLocation(Constants.id(name),"main");}
    public static LayerDefinition createLayer(String name) {return NativeModelGeometry.createLayer(Constants.id("models/entity/"+name+".mesh.json"));}
    public NativeEffectModel(ModelPart root,String name) {
        super(NativeModelGeometry.apply(root,Constants.id("models/entity/"+name+".mesh.json")));
        rootPart=root;
        animation=new NativeAnimationSet(root,Constants.id("models/entity/"+name+".animation.json"));
        for(var p:NativeModelGeometry.mesh(Constants.id("models/entity/"+name+".mesh.json")).parts()) {
            ModelPart cell=root;for(String child:p.path())cell=cell.getChild(child);cells.put(p.name(),cell);
        }
        if(name.equals("tectonic_fist_fx")) {
            for(var part:NativeModelGeometry.mesh(Constants.id("models/entity/"+name+".mesh.json")).parts()) {
                String n=part.name();
                if(n.startsWith("Spike ") && part.path().length==1)spikes[Integer.parseInt(n.substring(6,8))-1]=root.getChild(n);
            }
        }
    }
    @Override public void setupAnim(NativeEffectState state) {
        super.setupAnim(state);cells.values().forEach(p->p.visible=true);
        animation.hideMembranes();animation.apply(state.clip,state.tick,1);
        if(state.aimPitch!=0) {
            double a=Math.toRadians(state.aimPitch), c=Math.cos(a), s=Math.sin(a);
            double y=24-state.aimPivot.y*16/state.scale, z=-state.aimPivot.z*16/state.scale;
            rootPart.y+=(float)(y-y*c+z*s);rootPart.z+=(float)(z-y*s-z*c);
            rootPart.xRot+=(float)a;
        }
        for(String name:state.hidden) { var cell=cells.get(name);if(cell!=null)cell.visible=false; }
        if(state.heights!=null)for(int i=0;i<6;i++) {
            if(state.heights[i]==TectonicWave.INVALID)spikes[i].visible=false;
            else spikes[i].y-=state.heights[i]*16/state.scale;
        }
    }
}
