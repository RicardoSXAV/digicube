package com.digicube.fabric.client.render;
import com.digicube.Constants;
import com.digicube.entity.TectonicWaveEntity;
import com.digicube.fabric.client.model.NativeEffectModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;

public final class TectonicWaveRenderer extends EntityRenderer<TectonicWaveEntity,NativeEffectState> {
    private final NativeEffectModel model;
    public TectonicWaveRenderer(EntityRendererProvider.Context context) {
        super(context);model=new NativeEffectModel(context.bakeLayer(NativeEffectModel.layer("tectonic_fist_fx")),"tectonic_fist_fx");
    }
    @Override public NativeEffectState createRenderState(){return new NativeEffectState();}
    @Override public void extractRenderState(TectonicWaveEntity e,NativeEffectState s,float p) {
        super.extractRenderState(e,s,p);s.tick=e.animationTick(p);s.yaw=e.getYRot();s.heights=new float[6];
        for(int i=0;i<6;i++)s.heights[i]=e.height(i);
    }
    @Override protected AABB getBoundingBoxForCulling(TectonicWaveEntity e) {return e.getBoundingBox().inflate(9);}
    @Override public void submit(NativeEffectState s,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera) {
        submitEffect(model,"tectonic_fist_fx",s,pose,collector);super.submit(s,pose,collector,camera);
    }
    public static void submitEffect(NativeEffectModel model,String name,NativeEffectState s,PoseStack pose,SubmitNodeCollector collector) {
        pose.pushPose();applyWorldTransform(pose,s.yaw,s.scale);
        pose.translate(0,EntityModel.MODEL_Y_OFFSET,0);
        collector.submitModel(model,s,pose,RenderTypes.entityTranslucent(Constants.id("textures/entity/digimon/"+name+".png")),
                s.lightCoords,OverlayTexture.NO_OVERLAY,0xFFFFFFFF,null,s.outlineColor,null);
        pose.popPose();
    }
    /** Same world-facing convention as vanilla LivingEntityRenderer and the caster. */
    public static void applyWorldTransform(PoseStack pose,float yaw,float scale) {
        pose.mulPose(Axis.YP.rotationDegrees(180-yaw));pose.scale(-scale,-scale,scale);
    }
}
