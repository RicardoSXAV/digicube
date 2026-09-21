package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.AttackGeometry;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Actual compiled renderer transforms versus native vertices and server contact corners. */
public final class NativeMochimonRegressionTest {
    private static int checks;
    private static double worst;
    private static PoseStack stack(float yaw, Vec3 origin) {
        var s = new PoseStack(); s.translate(origin.x, origin.y, origin.z);
        TectonicWaveRenderer.applyWorldTransform(s, yaw, .4F);
        s.translate(0, EntityModel.MODEL_Y_OFFSET, 0); return s;
    }
    private static Vec3 point(ModelPart root, String part, Vec3 nativePoint, float yaw, Vec3 origin) {
        var s = stack(yaw, origin); root.translateAndRotate(s);
        var r = root.getChild("root"); r.translateAndRotate(s);
        r.getChild(part).translateAndRotate(s);
        var v = s.last().pose().transformPosition((float)nativePoint.x, (float)-nativePoint.z, (float)nativePoint.y, new org.joml.Vector3f());
        return new Vec3(v.x, v.y, v.z);
    }
    private static void near(Vec3 a, Vec3 b, String label) {
        double error = a.distanceTo(b); worst = Math.max(worst, error); checks++;
        if (error > .0015) throw new AssertionError(label + " error=" + error + " expected=" + a + " actual=" + b);
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("mochimon"));
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState(); state.modelScale = .4F; state.attackAnimation.start(0);
        for (var attack : species.attacks()) for (boolean mirrored : new boolean[]{false, true}) {
            if (mirrored && !attack.alternateSides()) continue;
            state.attackDefinition = attack; state.attackAnimationName = attack.animationName(mirrored);
            for (float t = 0; t <= attack.durationTicks(); t += .125F) {
                state.ageInTicks = t; model.setupAnim(state);
                float renderedTick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F;
                for (int h = 0; h < 8; h++) for (int elevation = -1; elevation <= 1; elevation++) {
                    float yaw = h * 45; var origin = new Vec3(13, 80 + elevation, -19);
                    if (attack.kind() == DigimonAttack.Kind.BUBBLES) {
                        near(AttackGeometry.world(origin, attack.motion().sample(renderedTick).mouth(), yaw),
                                point(root, "body", new Vec3(.03125,-.9375,.21875), yaw, origin), "mouth " + t);
                    } else {
                        var b = AuthoredAttacks.get(attack).sample(renderedTick, false, mirrored)[0].world(origin, yaw, 0);
                        for (int x : new int[]{-1,1}) for (int y : new int[]{-1,1}) for (int z : new int[]{-1,1})
                            near(b.center().add(b.x().scale(x)).add(b.y().scale(y)).add(b.z().scale(z)),
                                    point(root, mirrored ? "left_arm" : "right_arm", new Vec3(.19*x,-.2+.18*y,-.76+.16*z), yaw, origin), "hand " + mirrored + " " + t);
                    }
                }
            }
        }
        check(definition.texture("bubble_blow",4.999F).equals(definition.texture()), "eyes initially open");
        check(!definition.texture("bubble_blow",5).equals(definition.texture()), "eyes close at tick 5");
        check(!definition.texture("bubble_blow",18.999F).equals(definition.texture()), "eyes remain closed through exhale");
        check(definition.texture("bubble_blow",19).equals(definition.texture()), "eyes reopen at tick 19");
        check(definition.texture("mochi_punch",10).equals(definition.texture()), "punch eyes open");
        check(DigimonSpeciesRegistry.getOrThrow(Constants.id("koromon")).attacks().getFirst() == DigimonSpeciesBootstrap.BUBBLE_BLOW, "Koromon retains shared move");
        check(DigimonSpeciesRegistry.getOrThrow(Constants.id("tsunomon")).attacks().getFirst() == DigimonSpeciesBootstrap.BUBBLE_BLOW, "Tsunomon retains shared move");
        state.attackAnimation.stop(); state.attackAnimationName = null;
        for (float amount : new float[]{0,.25F,.5F,1,0}) {
            state.groundAnimationAmount=amount; state.groundAnimationPhase=7.125F; model.setupAnim(state);
            for (var p : root.getAllParts()) check(Float.isFinite(p.x+p.y+p.z+p.xScale+p.yScale+p.zScale), "finite reset/gait");
        }
        if (args.length > 0) {
            var animation = new NativeAnimationSet(root, definition.animation());
            try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))) {
                for (var entry : net.minecraft.util.GsonHelper.parse(reader).getAsJsonArray("samples")) {
                    var row=entry.getAsJsonObject(); root.getAllParts().forEach(ModelPart::resetPose);
                    animation.apply(row.get("clip").getAsString(),row.get("tick").getAsFloat(),1);
                    for (int h=0; h<8; h++) for (int elevation=-1; elevation<=1; elevation++) {
                        var origin=new Vec3(-11,50+elevation,27); float yaw=h*45;
                        Map<String,List<Vec3>> actual=new HashMap<>();
                        root.visit(stack(yaw,origin),(pose,path,index,cube)->{
                            var vertices=actual.computeIfAbsent(path.substring(path.lastIndexOf('/')+1),k->new ArrayList<>());
                            for(var polygon:cube.polygons)for(var vertex:polygon.vertices()) {
                                var v=pose.pose().transformPosition(vertex.worldX(),vertex.worldY(),vertex.worldZ(),new org.joml.Vector3f());
                                vertices.add(new Vec3(v.x,v.y,v.z));
                            }
                        });
                        for(var object:row.getAsJsonArray("objects")) {
                            var o=object.getAsJsonObject(); var vertices=actual.get(o.get("name").getAsString());
                            for(var vertex:o.getAsJsonArray("points")) {
                                var p=vertex.getAsJsonArray(); var expected=AttackGeometry.world(origin,new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble()),yaw);
                                near(expected,vertices.stream().min(Comparator.comparingDouble(expected::distanceToSqr)).orElseThrow(),"native " + row.get("clip"));
                            }
                        }
                    }
                }
            }
        }
        Constants.LOG.info("Motimon native parity: {} checks, worst error {} blocks",checks,worst);
    }
}
