package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.fabric.client.evolution.*;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.client.model.EntityModel;
import java.nio.file.*;
import java.util.*;

/** Executes real legacy/native models without a window; exports the runtime surfaces for review. */
public final class EvolutionGeometryExperiment {
    static EntityModel<DigimonRenderState> model(String name) {
        var id=Constants.id(name);var def=NativeGroundModel.definitions().get(id);
        if(def!=null)return new NativeGroundModel(def.createLayer().bakeRoot(),def);
        if(DigimonSpeciesRegistry.getOrThrow(id).body().mount().map(m->m.flight()!=null).orElse(false))
            return new NativeFlyingMountModel(NativeModelGeometry.createLayer(id.withPath("models/entity/"+name+".mesh.json")).bakeRoot(),id);
        return switch(name) {
            case "agumon"->new AgumonModel(AgumonModel.createBodyLayer().bakeRoot());
            case "gabumon"->new GabumonModel(GabumonModel.createBodyLayer().bakeRoot());
            case "gomamon"->new GomamonModel(GomamonModel.createBodyLayer().bakeRoot());
            case "tentomon"->new TentomonModel(TentomonModel.createBodyLayer().bakeRoot());
            case "greymon"->new GreymonModel(GreymonModel.createBodyLayer().bakeRoot());
            case "garurumon"->new GarurumonModel(GarurumonModel.createBodyLayer().bakeRoot());
            default->throw new IllegalArgumentException(name);
        };
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();DigimonSpeciesBootstrap.registerBuiltIn();
        Path out=args.length==0?null:Path.of(args[0]);if(out!=null)Files.createDirectories(out);
        var surfaces=new TreeMap<String,EvolutionSurface.Surface>();
        var names=new TreeSet<>(List.of("betamon","agumon","greymon","gabumon","garurumon","gomamon","ikkakumon","tentomon","kabuterimon","seadramon","gesomon","digmon","golemon","darktyrannomon"));
        var routes=new ArrayList<String[]>();
        for(var sheet:DigimonSpeciesRegistry.all())if(sheet.stage()==DigimonStage.CHILD)for(var route:sheet.evolutions())if(EvolutionRules.supported(route)) {
            routes.add(new String[]{sheet.id().getPath(),route.target().getPath()});names.add(sheet.id().getPath());names.add(route.target().getPath());
        }
        routes.addAll(List.of(new String[]{"betamon","seadramon"},new String[]{"ikkakumon","gesomon"},new String[]{"seadramon","gesomon"},new String[]{"gesomon","digmon"},new String[]{"digmon","golemon"},new String[]{"golemon","darktyrannomon"}));
        for(String name:names) {
            var model=model(name);var state=new DigimonRenderState();state.species=Constants.id(name);state.modelScale=DigimonSpeciesRegistry.getOrThrow(state.species).body().modelScale();model.setupAnim(state);
            var surface=EvolutionSurface.capture(model.root(),state.modelScale,Constants.id("textures/entity/digimon/"+name+".png"));surfaces.put(name,surface);
            Constants.LOG.info("[evolution-geometry] {} faces={} radius={} color={}",name,surface.faces().size(),surface.radius(),Integer.toHexString(surface.color()));
        }
        for(String[] route:routes) {
            long start=System.nanoTime();var pair=EvolutionSurface.pair(surfaces.get(route[0]),surfaces.get(route[1]),3072);double ms=(System.nanoTime()-start)/1e6;
            if(out!=null)Files.writeString(out.resolve(route[0]+"_"+route[1]+".json"),new com.google.gson.Gson().toJson(pair));
            Constants.LOG.info("[evolution-geometry] PASS {} -> {} cells={} construction_ms={}",route[0],route[1],pair.source().size(),ms);
            var metrics=verify(pair);if(out!=null){Files.writeString(out.resolve(route[0]+"_"+route[1]+".metrics.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(metrics));EvolutionReviewRenderer.review(pair,out.resolve(route[0]+"_"+route[1]));}
        }
    }
    private static java.util.Map<String,Object> verify(EvolutionSurface.Pair pair) {
        long begin=System.nanoTime();var forward=EvolutionMesh.prepare(pair);var reverse=EvolutionMesh.prepare(EvolutionSurface.pair(pair.to(),pair.from(),3072));double prepare=(System.nanoTime()-begin)/1e6;
        var near=new ArrayList<Double>();var far=new ArrayList<Double>();int peakSkin=0,peakData=0,peakFar=0,peakParticles=0;long vertices=0;
        verifyChoreography(forward);
        verifyCuboids(forward);
        verifyStream(forward);
        verifyHandoff(forward,reverse);
        verifyHandoff(reverse,forward);
        for(var preset:List.of(EvolutionTimeline.LONG,EvolutionTimeline.SHORT,EvolutionTimeline.RETURN)) {
            var template=preset.returning()?reverse:forward;
            var times=new TreeSet<Float>();for(float t=0;t<=preset.duration();t++)times.add(t);
            for(float boundary:new float[]{EvolutionDataLight.impact(preset),EvolutionDataLight.end(preset),preset.shedStart(),preset.shedEnd(),preset.morphStart(),preset.morphEnd(),preset.skinEnd(),preset.duration()})for(float d:new float[]{-.75F,-.5F,-.25F,0,.25F,.5F,.75F})if(boundary+d>=0&&boundary+d<=preset.duration())times.add(boundary+d);
            for(boolean detail:new boolean[]{true,false})for(float tick:times) {
                long start=System.nanoTime();var frame=EvolutionMesh.frame(template,preset,tick,detail);(detail?near:far).add((System.nanoTime()-start)/1e6);
                if(frame.source().isEmpty()&&frame.target().isEmpty()&&frame.data().isEmpty())throw new AssertionError("empty handoff at "+tick);
                if(tick==0&&frame.source().size()!=template.cells().size())throw new AssertionError("source coverage");
                if(tick==preset.duration()&&frame.target().size()!=template.cells().size())throw new AssertionError("target coverage");
                if((tick==0||tick==preset.duration()||preset.returning()||!detail)&&!frame.particles().isEmpty())throw new AssertionError("particle lifetime/detail leak");
                if(frame.signal().size()>EvolutionDataLight.MAX_QUADS)throw new AssertionError("signal geometry budget");
                if((preset.returning()||tick==0||tick>=EvolutionDataLight.end(preset))&&!frame.signal().isEmpty())throw new AssertionError("signal lifetime");
                for(var face:frame.signal())for(int i=0;i<32;i+=8) {
                    var v=face.vertices();for(float value:v)if(!Float.isFinite(value))throw new AssertionError("signal finite");
                    float haloMargin=.32F*.45F*EvolutionDataLight.HALO_SCALE;
                    if(v[i+1]<-haloMargin||v[i+1]>Math.max(32,template.sourceHeight()+4)+haloMargin)throw new AssertionError("vertical signal bound");
                    if(Math.abs(v[i])>3||Math.abs(v[i+2])>3)throw new AssertionError("horizontal signal bound");
                }
                peakParticles=Math.max(peakParticles,frame.particles().size());
                if(detail){peakSkin=Math.max(peakSkin,frame.source().size()+frame.target().size());peakData=Math.max(peakData,frame.data().size());}else peakFar=Math.max(peakFar,frame.data().size());
                for(var pass:List.of(frame.source(),frame.target(),frame.data(),frame.particles()))for(var face:pass) {
                    for(float v:face.vertices())if(!Float.isFinite(v))throw new AssertionError("nonfinite geometry at "+tick);
                    for(int i=0;i<32;i+=8){vertices++;float[] v=face.vertices();double radius=Math.sqrt(v[i]*v[i]+v[i+1]*v[i+1]+v[i+2]*v[i+2]);if(radius>EvolutionChoreography.extent(Math.max(pair.from().radius(),pair.to().radius())))throw new AssertionError("culling extent "+radius);}
                }
            }
        }
        Collections.sort(near);Collections.sort(far);
        var result=new LinkedHashMap<String,Object>();result.put("prepare_both_directions_ms",prepare);result.put("near_cpu_p50_ms",near.get(near.size()/2));result.put("near_cpu_p95_ms",near.get(near.size()*95/100));result.put("far_cpu_p95_ms",far.get(far.size()*95/100));result.put("near_cpu_max_ms",near.getLast());result.put("peak_skin_quads",peakSkin);result.put("peak_near_data_quads",peakData);result.put("peak_far_data_quads",peakFar);result.put("peak_particle_quads",peakParticles);result.put("validated_vertices",vertices);result.put("gpu_measured",false);
        var trio=new ArrayList<Double>();var warmSingle=new ArrayList<Double>();
        for(int i=0;i<80;i++) {
            float tick=EvolutionTimeline.LONG.leadIn()+57+(i%40);long start=System.nanoTime();EvolutionMesh.frame(forward,EvolutionTimeline.LONG,tick,true);double single=(System.nanoTime()-start)/1e6;
            start=System.nanoTime();EvolutionMesh.frame(forward,EvolutionTimeline.LONG,tick,true);EvolutionMesh.frame(reverse,EvolutionTimeline.LONG,EvolutionTimeline.LONG.leadIn()*2+155-tick,true);EvolutionMesh.frame(forward,EvolutionTimeline.SHORT,12+(i%8),true);double triple=(System.nanoTime()-start)/1e6;
            if(i>=20){trio.add(triple);warmSingle.add(single);}
        }
        Collections.sort(trio);Collections.sort(warmSingle);result.put("warm_one_cpu_p95_ms",warmSingle.get(warmSingle.size()*95/100));result.put("warm_three_cpu_p95_ms",trio.get(trio.size()*95/100));
        Constants.LOG.info("[evolution-coverage] PASS {} -> {} long/short/return near/far, quarter-tick boundaries; {}",pair.from().texture(),pair.to().texture(),new com.google.gson.Gson().toJson(result));
        return result;
    }

    private static void verifyStream(EvolutionMesh.Template template) {
        for(var time:List.of(EvolutionTimeline.LONG,EvolutionTimeline.SHORT))for(float sky:new float[]{32,256,512}) {
            var previous=new HashMap<Integer,Float>();int peak=0;
            for(float tick=.25F;tick<EvolutionDataLight.end(time);tick+=.25F) {
                var packets=EvolutionDataLight.packets(time,tick,sky,template.sourceHeight(),template.pair().from().radius());
                peak=Math.max(peak,packets.size());
                float baseWidth=Math.clamp(template.pair().from().radius()*.14F,.12F,.32F);
                float pitch=(.24F+baseWidth*.45F*2.6F)*.8F;
                for(int i=0;i<packets.size();i++) {
                    var packet=packets.get(i);
                    if(Math.abs(packet.halfWidth()-baseWidth*1.3F)>1e-6||Math.abs(packet.halfHeight()-baseWidth*.45F)>1e-6)
                        throw new AssertionError("stream tile stretches or shrinks");
                    if(i>0) {
                        var before=packets.get(i-1);float expected=(packet.index()-before.index())*pitch;
                        if(Math.abs(packet.y()-before.y()-expected)>Math.max(1e-5,4*Math.ulp(packet.y())))
                            throw new AssertionError("stream spacing changes during descent");
                    }
                }
                for(var packet:packets) {
                    Float old=previous.put(packet.index(),packet.y());
                    if(old!=null&&packet.y()>=old)throw new AssertionError("data packet not descending");
                    if(packet.halfWidth()<=0||packet.halfHeight()<=0||!Float.isFinite(packet.y()))throw new AssertionError("degenerate packet");
                }
                var faces=EvolutionDataLight.frame(time,tick,sky,template.sourceHeight(),template.pair().from().radius());
                if(faces.size()!=packets.size()||faces.size()>EvolutionDataLight.MAX_QUADS)throw new AssertionError("flat tile budget");
                var camera=new org.joml.Quaternionf().rotationY(1.3F).rotateX(.4F);
                var turned=EvolutionDataLight.facing(faces,camera);
                for(int i=0;i<faces.size();i++) {
                    var v=faces.get(i).vertices();var q=turned.get(i).vertices();double y=0,turnedY=0;
                    for(int j=0;j<32;j+=8) {
                        if(v[j+2]!=0)throw new AssertionError("data tile has thickness");
                        y+=v[j+1];turnedY+=q[j+1];
                    }
                    if(Math.abs(y-turnedY)/4>Math.max(1e-5,2*Math.ulp((float)(y/4))))throw new AssertionError("camera rotation moved absorption path");
                }
            }
            if(peak==0)throw new AssertionError("stream absent");
        }
        var time=EvolutionTimeline.LONG;
        for(float tick:new float[]{1,20,40,59.75F,60}) {
            var frame=EvolutionMesh.frame(template,time,tick,true);
            if(frame.source().size()!=template.cells().size()||!frame.target().isEmpty()||!frame.data().isEmpty()||!frame.particles().isEmpty())
                throw new AssertionError("transformation begins before data absorption ends");
        }
    }
    private static void verifyHandoff(EvolutionMesh.Template template,EvolutionMesh.Template restoration) {
        var target=template.pair().to().texture().getPath().replace("textures/entity/digimon/", "").replace(".png", "");
        var targetId=Constants.id(target);var model=model(target);
        for(var time:List.of(EvolutionTimeline.LONG,EvolutionTimeline.SHORT,EvolutionTimeline.RETURN)) {
            var event=new EvolutionEvent(Constants.id("agumon"),targetId,0,time.duration(),1,false);
            for(float delay:new float[]{-.25F,0,.25F,1,5,20}) {
                float sampled=EvolutionPresentation.renderTick(event,time.duration()+delay);
                if(sampled<0)throw new AssertionError("clock deadline exposes old ordinary model before commit");
                var frame=EvolutionMesh.frame(template,time,sampled,true);
                if(frame.target().size()!=template.cells().size()||!frame.source().isEmpty())throw new AssertionError("incomplete final body during delayed commit");
            }
            var preview=new EvolutionEvent(event.source(),event.target(),0,time.duration(),1,true);
            if(EvolutionPresentation.renderTick(preview,time.duration()+16)>=0)throw new AssertionError("preview must release after restoration");
            var restore=EvolutionPresentation.presentationEvent(preview,time.duration());
            if(!restore.source().equals(preview.target())||!restore.target().equals(preview.source())||restore.duration()!=16)
                throw new AssertionError("preview needs a continuous reverse transition at expiry");
            for(float tick:new float[]{0,.25F,8,15.75F,16}) {
                var frame=EvolutionMesh.frame(restoration,EvolutionTimeline.RETURN,tick,true);
                if(frame.source().isEmpty()&&frame.target().isEmpty()&&frame.data().isEmpty())throw new AssertionError("empty preview restoration");
                if(tick==0&&frame.source().size()!=restoration.cells().size())throw new AssertionError("preview restoration must begin with complete target");
                if(tick==16&&frame.target().size()!=restoration.cells().size())throw new AssertionError("preview restoration must end with complete actual source");
            }
        }
        for(float phase:new float[]{0,17.5F,43.25F})for(float water:new float[]{0,1}) {
            var live=new DigimonRenderState();live.species=targetId;live.modelScale=DigimonSpeciesRegistry.getOrThrow(targetId).body().modelScale();
            live.ageInTicks=phase+250;live.groundAnimationPhase=phase;live.groundAnimationAmount=.35F;live.groundRunAmount=.2F;
            live.swimAnimationAmount=water;live.swimAnimationPhase=phase;live.swimMotionAmount=.4F;live.swimBank=12;live.xRot=8;
            model.setupAnim(live);var ordinary=EvolutionSurface.capture(model.root(),live.modelScale,template.pair().to().texture());
            model.setupAnim(EvolutionPresentation.endpointState(live,targetId));
            var endpoint=EvolutionSurface.capture(model.root(),live.modelScale,template.pair().to().texture());
            if(ordinary.faces().isEmpty()||!ordinary.faces().equals(endpoint.faces()))throw new AssertionError("target pose clock or visibility mismatch");
        }
    }

    private static void verifyCuboids(EvolutionMesh.Template template) {
        for(boolean detail:new boolean[]{true,false})for(float t:new float[]{.05F,.25F,.5F,.75F,.95F}) {
            var mesh=EvolutionVolume.frame(template.sourceField(),template.targetField(),t,0xff44bbcc,0,detail);
            if(mesh.isEmpty())throw new AssertionError("empty voxel morph");
            for(var face:mesh) {
                float[] v=face.vertices();int axis=-1;
                for(int a=0;a<3;a++)if(Math.abs(v[5+a])>.99F)axis=a;
                if(axis<0)throw new AssertionError("sloped voxel normal");
                for(int i=8;i<32;i+=8)if(Math.abs(v[i+axis]-v[axis])>1e-5)throw new AssertionError("nonplanar voxel face");
                float length=0;for(int a=0;a<3;a++)length+=(v[8+a]-v[a])*(v[8+a]-v[a]);
                float du=v[11]-v[3],dv=v[12]-v[4];
                if(Math.abs(Math.sqrt(du*du+dv*dv)/Math.sqrt(length)-3.5)>1e-3)throw new AssertionError("grid stretched");
            }
        }
    }
    private static void verifyChoreography(EvolutionMesh.Template template) {
        float radius=Math.max(template.pair().from().radius(),template.pair().to().radius());
        var start=EvolutionChoreography.motion(0,radius);var end=EvolutionChoreography.motion(160,radius);
        if(start.lift()!=0||end.lift()!=0||end.height()!=1||end.width()!=1||Math.abs(end.yaw()-Math.PI*2)>1e-5)throw new AssertionError("motion does not return to ordinary pose");
        for(float tick=0;tick<160;tick+=.25F) {
            var a=EvolutionChoreography.motion(tick,radius);var b=EvolutionChoreography.motion(tick+.25F,radius);
            if(Math.abs(a.lift()-b.lift())>.035F||Math.abs(a.yaw()-b.yaw())>.04F)throw new AssertionError("motion discontinuity at "+tick);
        }
        if(EvolutionChoreography.motion(144,radius).lift()!=0)throw new AssertionError("landing not complete by 7.2s");
        for(var time:List.of(EvolutionTimeline.LONG,EvolutionTimeline.SHORT)) {
            if(EvolutionDataLight.end(time)>time.shedStart())throw new AssertionError("stream must end before shedding");
            if(time.longForm()&&time.leadIn()!=60)throw new AssertionError("absorption duration");
        }
        var a=EvolutionChoreography.particles(EvolutionTimeline.LONG,EvolutionTimeline.LONG.leadIn()+63.25F,radius,template.height(),0xffcc6633,1,73);
        EvolutionChoreography.particles(EvolutionTimeline.LONG,EvolutionTimeline.LONG.leadIn()+20,radius,template.height(),0xffcc6633,1,73);
        var b=EvolutionChoreography.particles(EvolutionTimeline.LONG,EvolutionTimeline.LONG.leadIn()+63.25F,radius,template.height(),0xffcc6633,1,73);
        if(a.size()!=b.size())throw new AssertionError("late-observer count differs");
        for(int i=0;i<a.size();i++)if(a.get(i).color()!=b.get(i).color()||!Arrays.equals(a.get(i).vertices(),b.get(i).vertices()))throw new AssertionError("particles depend on frame history");
        var reduced=EvolutionChoreography.particles(EvolutionTimeline.LONG,EvolutionTimeline.LONG.leadIn()+63.25F,radius,template.height(),0xffcc6633,.5F,73);
        if(reduced.size()>=a.size())throw new AssertionError("reduced setting does not reduce particles");
    }

}
