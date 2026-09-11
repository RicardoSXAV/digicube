package com.digicube.fabric.client.model;
import com.digicube.Constants;
import com.digicube.entity.TectonicWave;
import com.digicube.fabric.client.render.NativeEffectState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.util.GsonHelper;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Original cuboids and flat pixel sheets, including native scale and visibility curves. */
public final class NativeEffectModel extends EntityModel<NativeEffectState> {
    private final NativeAnimationSet animation;
    private final ModelPart[] spikes=new ModelPart[6];
    public static ModelLayerLocation layer(String name) {return new ModelLayerLocation(Constants.id(name),"main");}
    public static LayerDefinition createLayer(String name) {return NativeModelGeometry.createLayer(Constants.id("models/entity/"+name+".mesh.json"));}
    public NativeEffectModel(ModelPart root,String name) {
        super(NativeModelGeometry.apply(root,Constants.id("models/entity/"+name+".mesh.json")));
        animation=new NativeAnimationSet(root,Constants.id("models/entity/"+name+".animation.json"));
        if(name.equals("tectonic_fist_fx")) {
            try(var in=NativeEffectModel.class.getResourceAsStream("/assets/digicube/models/entity/"+name+".mesh.json")) {
                if(in==null)throw new IllegalStateException("Missing native effect");
                for(var el:GsonHelper.parse(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonArray("parts")) {
                    var p=el.getAsJsonObject();String n=p.get("name").getAsString();
                    if(n.startsWith("Spike ") && p.getAsJsonArray("path").size()==1)spikes[Integer.parseInt(n.substring(6,8))-1]=root.getChild(n);
                }
            }catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
    }
    @Override public void setupAnim(NativeEffectState state) {
        super.setupAnim(state);animation.hideMembranes();animation.apply("effect",state.tick,1);
        if(state.heights!=null)for(int i=0;i<6;i++) {
            if(state.heights[i]==TectonicWave.INVALID)spikes[i].visible=false;
            else spikes[i].y-=state.heights[i]*16/state.scale;
        }
    }
}
