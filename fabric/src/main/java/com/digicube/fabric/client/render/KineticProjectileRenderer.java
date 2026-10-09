package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.digimon.KineticAttacks;
import com.digicube.entity.KineticProjectileEntity;
import com.digicube.fabric.client.model.NativeEffectModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

import java.util.HashMap;
import java.util.Map;

public final class KineticProjectileRenderer extends EntityRenderer<KineticProjectileEntity, NativeEffectState> {
    private final Map<String, NativeEffectModel> models = new HashMap<>();
    private final ArcRenderer arcs = new ArcRenderer();
    /** A shocking shot's bolts: a pale pink core, a magenta strand and mint forks, as its ball's art. */
    private static final int SHOCK_CORE = 0xFFFFE8FF, SHOCK_EDGE = 0xFFE02AF0, SHOCK_FORK = 0xFF6CF2AD;
    /** A static shot's lightning (ShotStyle.STATIC): a warm white core, gold strands and pale yellow forks. */
    private static final int STATIC_CORE = 0xFFFFFBE0, STATIC_EDGE = 0xFFFFB81E, STATIC_FORK = 0xFFFFE987;
    public KineticProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
        for (var definition : KineticAttacks.all()) if (definition.projectile() != null) {
            models.put(definition.projectile(), new NativeEffectModel(context.bakeLayer(NativeEffectModel.layer(definition.projectile())), definition.projectile()));
        }
    }
    @Override public NativeEffectState createRenderState() { return new NativeEffectState(); }
    @Override protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(KineticProjectileEntity entity) {
        // an electric ball's lightning reaches the ground and the bodies near it
        return entity.getBoundingBox().inflate(5.5);
    }
    @Override public void extractRenderState(KineticProjectileEntity entity, NativeEffectState state, float partial) {
        super.extractRenderState(entity, state, partial);
        var definition = entity.definition();
        state.projectile = definition == null ? null : definition.projectile();
        if (definition == null) return;
        state.scale = definition.modelScale() * definition.projectileScale();
        state.clip=entity.impacting()?"impact":"effect";
        var model = models.get(state.projectile);
        // a looping flight (a crackling ball) plays on its age; a held one stops at its hold
        state.tick=entity.impacting()?entity.effectTick(partial)
                :model!=null&&model.loops("effect")?entity.effectTick(partial)
                :definition.projectileMotion()==null?0:definition.projectileMotion().flightTick(entity.effectTick(partial));
        bolts(entity, definition, state, partial);
        ball(entity, definition, state.ball, partial);
        state.emissive=definition.emissive();
        var velocity = entity.getDeltaMovement();
        state.yaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
        state.pitch = (float) -Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalDistance()));
    }
    /**
     * A shocking shot's bolts ({@code KineticAttacks.Proximity}): one from the ball to each body it struck, for the
     * proximity's {@code bolt_ticks}, following the body as it moves.
     */
    private static void bolts(KineticProjectileEntity entity, KineticAttacks.Definition definition, NativeEffectState state, float partial) {
        var arc = state.arc;
        arc.reset();
        var proximity = definition.proximity();
        if (proximity == null) return;
        var ball = entity.getPosition(partial);
        float youngest = Float.MAX_VALUE;
        for (var bolt : entity.bolts().entrySet()) {
            float age = entity.tickCount - bolt.getValue() + partial;
            if (age > proximity.boltTicks()) continue;
            var target = entity.level().getEntity(bolt.getKey());
            if (target == null) continue;
            var chest = target.getPosition(partial).add(0, target.getBbHeight() * .55, 0);
            arc.bolt(net.minecraft.world.phys.Vec3.ZERO, chest.subtract(ball), true);
            youngest = Math.min(youngest, age);
        }
        if (arc.count == 0) return;
        arc.age = youngest; arc.life = proximity.boltTicks(); arc.seed = entity.getId() * 31 + (int) youngest;
        arc.core = SHOCK_CORE; arc.edge = SHOCK_EDGE; arc.fork = SHOCK_FORK;
    }

    /**
     * An electric shot's own lightning about its ball ({@link ShockBall}): its clock, size and flight, the ground under
     * it, the bodies within its shock's reach (its caster, the caster's riders and the caster's own side never), its wake
     * and where it left the hands.
     */
    private static void ball(KineticProjectileEntity entity, KineticAttacks.Definition definition, ShockBall.State s, float partial) {
        var style = definition.shotStyle();
        s.drawn = style == com.digicube.entity.ShotStyle.ELECTRIC || style == com.digicube.entity.ShotStyle.STATIC;
        if (!s.drawn) return;
        // static (Petit Thunder) crackles in its star's white and gold; Mega Blaster keeps its own colours
        boolean gold = style == com.digicube.entity.ShotStyle.STATIC;
        s.core = gold ? STATIC_CORE : ShockBall.CORE; s.edge = gold ? STATIC_EDGE : ShockBall.MAGENTA;
        s.spark = ShockBall.WHITE; s.fork = gold ? STATIC_FORK : ShockBall.MINT;
        s.seed = entity.getId();
        s.time = entity.tickCount + partial;
        s.impact = entity.impacting() ? entity.effectTick(partial) : -1;
        s.burstTicks = definition.projectileMotion() == null ? 9 : definition.projectileMotion().impactTicks();
        var box = definition.projectileBoxes().isEmpty() ? null : definition.projectileBoxes().getFirst();
        s.radius = box == null ? .4F : (float) (box.x().length() * 1.2);
        var velocity = entity.getDeltaMovement();
        s.vx = (float) velocity.x; s.vy = (float) velocity.y; s.vz = (float) velocity.z;
        var level = entity.level();
        var at = entity.getPosition(partial);
        var ground = level.clip(new net.minecraft.world.level.ClipContext(at, at.add(0, -ShockBall.EARTH_REACH - 1, 0),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY, entity));
        s.groundY = ground.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? Float.NaN : (float) (ground.getLocation().y - at.y);
        s.feelerCount = 0;
        var proximity = definition.proximity();
        if (proximity != null && s.impact < 0) {
            var caster = entity.getOwner();
            for (var body : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(proximity.radius()))) {
                if (s.feelerCount * 3 >= s.feelers.length) break;
                if (!body.isAlive() || body.isSpectator() || sameSide(caster, body)) continue;
                var chest = body.getPosition(partial).add(0, Math.min(body.getBbHeight() * .55, 1.4), 0).subtract(at);
                var bounds = body.getBoundingBox();
                double x = Math.clamp(at.x, bounds.minX, bounds.maxX), y = Math.clamp(at.y, bounds.minY, bounds.maxY), z = Math.clamp(at.z, bounds.minZ, bounds.maxZ);
                if (at.distanceTo(new net.minecraft.world.phys.Vec3(x, y, z)) > proximity.radius()) continue;
                s.feelers[s.feelerCount * 3] = (float) chest.x; s.feelers[s.feelerCount * 3 + 1] = (float) chest.y; s.feelers[s.feelerCount * 3 + 2] = (float) chest.z;
                s.feelerCount++;
            }
        }
        s.wakeCount = s.impact < 0 ? entity.wake(at, s.wake) : 0;
        var muzzle = entity.firstSeen();
        s.released = muzzle != null;
        if (muzzle != null) { s.rx = (float) (muzzle.x - at.x); s.ry = (float) (muzzle.y - at.y); s.rz = (float) (muzzle.z - at.z); }
    }

    /** The caster itself, a body riding it or carrying it, its tamer, and the tamer's other partners: never shocked. */
    private static boolean sameSide(net.minecraft.world.entity.Entity caster, net.minecraft.world.entity.LivingEntity body) {
        if (caster == null) return false;
        if (body == caster || body.getVehicle() == caster || caster.getVehicle() == body) return true;
        if (!(caster instanceof net.minecraft.world.entity.OwnableEntity own) || own.getOwnerReference() == null) return false;
        var tamer = own.getOwnerReference().getUUID();
        if (body.getUUID().equals(tamer)) return true;
        return body instanceof net.minecraft.world.entity.OwnableEntity other && other.getOwnerReference() != null && tamer.equals(other.getOwnerReference().getUUID());
    }

    /** Used by the compiled parity check as well as the submitted projectile. */
    public static void transform(PoseStack stack, float yaw, float pitch, float scale) {
        stack.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        stack.mulPose(Axis.XP.rotationDegrees(-pitch));
        stack.scale(-scale, -scale, scale);
        stack.translate(0, EntityModel.MODEL_Y_OFFSET, 0);
    }
    @Override public void submit(NativeEffectState state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
        var model = models.get(state.projectile);
        if (model == null) return;
        stack.pushPose();
        transform(stack, state.yaw, state.pitch, state.scale);
        collector.submitModel(model, state, stack,
                state.emissive?AfterWaterEffects.glow(Constants.id("textures/entity/projectile/" + state.projectile + ".png"))
                        :RenderTypes.entityTranslucent(Constants.id("textures/entity/projectile/" + state.projectile + ".png")),
                state.emissive?LightCoordsUtil.FULL_BRIGHT:state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        stack.popPose();
        if (state.arc.count > 0) arcs.submit(state.arc, stack, collector);
        if (state.ball.drawn) {
            // the way from the ball to the camera: the halo's white thread is laid on that side
            double dx = camera.pos.x - state.x, dy = camera.pos.y - state.y, dz = camera.pos.z - state.z, l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l > 1.0E-4) ShockBall.submit(state.ball, stack, collector, camera.orientation, (float) (dx / l), (float) (dy / l), (float) (dz / l));
        }
        super.submit(state, stack, collector, camera);
    }
}
