package com.digicube.fabric.client.evolution;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Quaternionf;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Renderer-owned caches; deferred submissions close over immutable geometry, never a mutable model pose. */
public final class EvolutionPresentation {
    private static final Identifier WHITE=Constants.id("textures/effect/evolution_grid.png");
    public record Snapshot(EvolutionMesh.Frame frame,Identifier sourceTexture,Identifier targetTexture,float yaw) {}
    private record Key(Identifier from,Identifier to) {}
    private final Map<Identifier,EntityModel<DigimonRenderState>> models;
    private final Map<Identifier,java.awt.image.BufferedImage> images=new HashMap<>();
    private record Active(long sequence,Identifier source,EvolutionSurface.Surface pose) {}
    private final Map<DigimonEntity,Active> active=new WeakHashMap<>();
    private final Map<Identifier,EvolutionSurface.Surface> surfaces=new HashMap<>();
    private final Map<Key,CompletableFuture<EvolutionMesh.Template>> templates=new LinkedHashMap<>();
    public EvolutionPresentation(Map<Identifier,EntityModel<DigimonRenderState>> models) {
        this.models=models;
        for(var entry:models.entrySet()) {
            var sheet=DigimonSpeciesRegistry.get(entry.getKey()).orElse(null);if(sheet==null)continue;
            var s=new DigimonRenderState();s.species=entry.getKey();s.modelScale=sheet.body().modelScale();entry.getValue().setupAnim(s);
            var texture=entry.getValue() instanceof com.digicube.fabric.client.model.NativeGroundModel nativeModel?nativeModel.definition().texture():entry.getKey().withPath("textures/entity/digimon/"+entry.getKey().getPath()+".png");var image=EvolutionSurface.readTexture(texture);images.put(entry.getKey(),image);
            surfaces.put(entry.getKey(),EvolutionSurface.capture(entry.getValue().root(),s.modelScale,texture,image));
        }
        for(var source:DigimonSpeciesRegistry.all())for(var route:source.evolutions())if(EvolutionRules.rookie(source.id())&&EvolutionRules.supported(route)) {
            template(source.id(),route.target());template(route.target(),source.id());
        }
    }
    private CompletableFuture<EvolutionMesh.Template> template(Identifier from,Identifier to) {
        var key=new Key(from,to);var old=templates.get(key);if(old!=null)return old;
        if(!surfaces.containsKey(from)||!surfaces.containsKey(to))return null;
        if(templates.size()>=32)templates.remove(templates.keySet().iterator().next());
        var a=surfaces.get(from);var b=surfaces.get(to);
        var future=CompletableFuture.supplyAsync(()->EvolutionMesh.prepare(EvolutionSurface.pair(a,b,3072)));
        templates.put(key,future);return future;
    }
    public Snapshot extract(DigimonEntity entity,DigimonRenderState state,float partialTick) {
        var tracked=entity.evolutionEvent();if(tracked==null||entity.isGuiPreview())return null;
        float elapsed=entity.level().getGameTime()-tracked.start()+partialTick;
        if(renderTick(tracked,elapsed)<0)return null;
        var event=presentationEvent(tracked,elapsed);
        float tick=event==tracked?Math.min(elapsed,event.duration()):elapsed-tracked.duration();
        // A cosmetic preview returns through the shared reversion instead of snapping to its real source.
        if(tracked.preview())template(tracked.target(),tracked.source());
        var future=template(event.source(),event.target());if(future==null||!future.isDone())return null;
        var template=future.join();var timing=EvolutionTimeline.of(event.duration());
        var current=active.get(entity);if(current!=null&&event.sequence()<current.sequence)return null;
        if(current==null||current.sequence!=event.sequence()||!current.source.equals(event.source())||tick<timing.leadIn()) {
            var model=models.get(event.source());model.setupAnim(event==tracked?state:endpointState(state,event.source()));
            var captured=EvolutionSurface.capture(model.root(),template.pair().from().radius()>0?DigimonSpeciesRegistry.getOrThrow(event.source()).body().modelScale():state.modelScale,template.pair().from().texture(),images.get(event.source()));
            current=new Active(event.sequence(),event.source(),captured);active.put(entity,current);
        }
        float settle=1-EvolutionTimeline.smooth(timing.bodyTick(tick)/Math.max(1,timing.shedStart()-timing.leadIn()));
        var sourcePose=settle>0?new EvolutionSurface.Pose(template.pair().from(),current.pose,settle):null;
        EvolutionSurface.Pose targetPose=null;
        if(tick>timing.skinEnd()) {
            var targetState=endpointState(state,event.target());
            var model=models.get(event.target());model.setupAnim(targetState);
            var shown=EvolutionSurface.capture(model.root(),targetState.modelScale,template.pair().to().texture(),images.get(event.target()));
            targetPose=new EvolutionSurface.Pose(template.pair().to(),shown,EvolutionTimeline.smooth((tick-timing.skinEnd())/(timing.duration()-timing.skinEnd())));
        }
        float quality=switch(net.minecraft.client.Minecraft.getInstance().options.particles().get()) {
            case ALL -> 1F; case DECREASED -> .5F; case MINIMAL -> 0F;
        };
        if(state.distanceToCameraSq>=48*48)quality=0;
        else if(state.distanceToCameraSq>=24*24)quality=Math.min(quality,.25F);
        long seed=entity.getUUID().getLeastSignificantBits()^event.sequence();
        var frame=EvolutionMesh.frame(template,timing,tick,state.distanceToCameraSq<32*32,sourcePose,targetPose,quality,seed,beamHeight(entity));
        return new Snapshot(frame,template.pair().from().texture(),template.pair().to().texture(),state.bodyRot);
    }
    /** Hold the completed target until the server clears the event with its species commit.
     * A local clock deadline alone can fall before that packet and briefly expose the old model. */
    public static float renderTick(EvolutionEvent event,float elapsed) {
        if(elapsed<0||event.preview()&&elapsed>=event.duration()+EvolutionTimeline.RETURN.duration())return -1;
        return Math.min(elapsed,event.duration());
    }
    public static EvolutionEvent presentationEvent(EvolutionEvent event,float elapsed) {
        if(!event.preview()||elapsed<event.duration())return event;
        return new EvolutionEvent(event.target(),event.source(),event.start()+event.duration(),
                EvolutionTimeline.RETURN.duration(),event.sequence(),true);
    }
    /** Match the ordinary idle/swim/ground pose instead of jumping to phase zero at handoff. */
    public static DigimonRenderState endpointState(DigimonRenderState state,Identifier target) {
        var out=new DigimonRenderState();out.species=target;
        out.modelScale=DigimonSpeciesRegistry.getOrThrow(target).body().modelScale();
        out.ageInTicks=state.ageInTicks;out.xRot=state.xRot;out.yRot=state.yRot;
        out.walkAnimationPos=state.walkAnimationPos;out.walkAnimationSpeed=state.walkAnimationSpeed;
        out.runAnimationAmount=state.runAnimationAmount;
        out.swimAnimationAmount=state.swimAnimationAmount;out.swimAnimationPhase=state.swimAnimationPhase;
        out.swimMotionAmount=state.swimMotionAmount;out.swimBank=state.swimBank;
        out.groundAnimationPhase=state.groundAnimationPhase;out.groundAnimationAmount=state.groundAnimationAmount;
        out.groundRunAmount=state.groundRunAmount;
        return out;
    }
    public float radius(DigimonEntity entity) {
        var event=entity.evolutionEvent();if(event==null||renderTick(event,entity.level().getGameTime()-event.start())<0)return 0;
        var a=surfaces.get(event.source());var b=surfaces.get(event.target());return a==null||b==null?0:EvolutionChoreography.extent(Math.max(a.radius(),b.radius()));
    }
    public float beamHeight(DigimonEntity entity) {
        var event=entity.evolutionEvent();if(event==null)return 0;
        float tick=entity.level().getGameTime()-event.start();
        if(tick<0||tick>=EvolutionDataLight.end(EvolutionTimeline.of(event.duration())))return 0;
        return (float)Math.clamp(entity.level().getMaxY()-entity.getY(),32,512);
    }
    public static void submit(Snapshot state,PoseStack pose,SubmitNodeCollector collector,int light,net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        pose.pushPose();pose.mulPose(new Quaternionf().rotationY((float)Math.toRadians(-state.yaw)));
        submit(state.frame.source(),state.sourceTexture,pose,collector,light);
        submit(state.frame.target(),state.targetTexture,pose,collector,light);
        submit(state.frame.data(),WHITE,pose,collector,LightCoordsUtil.FULL_BRIGHT);
        submit(state.frame.particles(),EvolutionRenderType.PARTICLES,pose,collector,LightCoordsUtil.FULL_BRIGHT);
        pose.popPose();
        submit(EvolutionDataLight.facing(state.frame.signal(),camera.orientation),EvolutionRenderType.DATA_STREAM,pose,collector,LightCoordsUtil.FULL_BRIGHT);
    }
    private static void submit(List<EvolutionMesh.Face> faces,Identifier texture,PoseStack pose,SubmitNodeCollector collector,int light) {
        submit(faces,texture.equals(WHITE)?EvolutionRenderType.GRID:RenderTypes.entityCutout(texture),pose,collector,light);
    }
    private static void submit(List<EvolutionMesh.Face> faces,net.minecraft.client.renderer.rendertype.RenderType type,PoseStack pose,SubmitNodeCollector collector,int light) {
        if(faces.isEmpty())return;
        collector.submitCustomGeometry(pose,type,(matrix,vertices)-> {
            for(var face:faces) {
                var v=face.vertices();for(int i=0;i<32;i+=8)
                    vertices.addVertex(matrix,v[i],v[i+1],v[i+2]).setColor(face.color()).setUv(v[i+3],v[i+4])
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(matrix,v[i+5],v[i+6],v[i+7]);
            }
        });
    }
}
