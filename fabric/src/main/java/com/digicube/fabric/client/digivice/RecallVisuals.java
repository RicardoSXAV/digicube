package com.digicube.fabric.client.digivice;

import com.digicube.digivice.DigiviceRecallPayload;
import com.digicube.digivice.Digivices;
import com.digicube.digivice.DroppedDigivice;
import com.digicube.fabric.client.evolution.EvolutionMesh;
import com.digicube.fabric.client.evolution.EvolutionRenderType;
import com.digicube.fabric.client.render.DigiviceGrip;
import com.digicube.fabric.client.render.DroppedDigiviceRenderer;
import com.digicube.registry.DCItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.digicube.registry.DCSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A recall as every nearby client sees it. Custody is already safe on the server; this plays the device's journey from
 * where it lay into the hand of the player who called it. In that player's own first-person view the last stretch is
 * drawn in the hand pass, with the chip breaking in their fingers; from any other camera (third person, or watching
 * someone else) the device flies into the hand the player model holds out, read each frame from ItemInHandLayer, and
 * that hand shows nothing until it lands. The caller's device stays out of the hotbar, the inventory and the item-name
 * banner until then. Dying, changing dimension or switching the hand's item ends it and shows the device at once.
 */
public final class RecallVisuals {
    public interface HudAccess { void digicube$replayHighlight(); }
    /** The hand's give on the catch: a damped swing along the device's arrival. */
    private static final float RECOIL_OMEGA = 17.5F, RECOIL_DECAY = .09F, RECOIL_MAX = .045F;
    /** recall_flight.ogg swells to its peak this long after it starts. */
    private static final float FLIGHT_SOUND_PEAK = 2F;
    /** Cues further out than this are played this far out in their direction. */
    private static final double CUE_REACH = 20;
    /** The chip breaking and the catch flash in a hand seen from outside, against their first-person size. */
    private static final float OUTSIDE_SCALE = .8F;
    private static final long PLANNING_SLICE = 1_500_000;
    /** Every recall in view, by the entity id of the player the device flies to. */
    private static final Map<Integer, RecallVisuals> ACTIVE = new LinkedHashMap<>();

    private final DigiviceRecallPayload active;
    private final ClientLevel level;
    /** The recall is the local player's own: the credential is theirs and the first-person view can play it. */
    private final boolean own;
    private final long start;
    private Vec3 source, plannedAt, heard;
    private float plannedTime;
    private FlightSound flightSound;
    private Quaternionf rest;
    private ItemStack modelStack;
    private int sourceLight = LightCoordsUtil.FULL_BRIGHT;
    private RecallFlight.Plan plan;
    private RecallFlight.View view;
    private RecallFlight.Pose held;
    /** The recipient's hand as ItemInHandLayer drew it this frame, or null when it was not drawn. */
    private RecallFlight.Pose drawnHand;
    private boolean firstPerson, received, inHand, woke, launched, arrived;
    private final Vector3f recoil = new Vector3f();

    private RecallVisuals(ClientLevel level, DigiviceRecallPayload packet, boolean own) {
        this.level = level; this.active = packet; this.own = own; this.start = level.getGameTime();
    }

    private static RecallVisuals mine(Minecraft client) {
        return client.player == null ? null : ACTIVE.get(client.player.getId());
    }
    public static boolean replaces(UUID entity) {
        for (var effect : ACTIVE.values()) if (effect.active.sourceEntity().equals(entity)) return true;
        return false;
    }
    /** The recalled device stays out of every item slot drawing until it is in the hand. */
    public static boolean hides(ItemStack stack) {
        if (!stack.is(DCItems.DIGIVICE)) return false;
        var effect = mine(Minecraft.getInstance());
        return effect != null && effect.own && !effect.arrived && effect.active.token().equals(Digivices.token(stack));
    }
    /**
     * ItemInHandLayer, as it submits a held item: remembers where a recalling player's hand is (the device's landing)
     * and hides what that hand holds until the device lands in it.
     * @return whether to skip drawing the held item
     */
    public static boolean thirdPersonHand(ArmedEntityRenderState state, HumanoidArm arm, PoseStack pose) {
        if (ACTIVE.isEmpty() || !(state instanceof AvatarRenderState avatar)) return false;
        var effect = ACTIVE.get(avatar.id);
        if (effect == null || effect.arm(state.mainArm) != arm) return false;
        var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        effect.drawnHand = RecallFlight.Pose.of(camera, new Matrix4f(pose.last().pose()).mul(RecallFlight.thirdPersonDisplay()));
        return !effect.arrived;
    }
    /** The local player's first-person hand pass; true when the recall drew the hand instead of vanilla. */
    public static boolean firstPersonHand(AbstractClientPlayer player, InteractionHand hand, float partial, float equip,
                                          PoseStack pose, SubmitNodeCollector collector, int light) {
        var client = Minecraft.getInstance();
        var effect = mine(client);
        return effect != null && player == client.player && effect.render(player, hand, partial, equip, pose, collector, light);
    }

    public static void init() {
        EvolutionRenderType.RECALL_CHIP.pipeline();
        ClientPlayNetworking.registerGlobalReceiver(DigiviceRecallPayload.TYPE, (packet, context) ->
                context.client().execute(() -> begin(context.client(), packet)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ACTIVE::clear));
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (ACTIVE.isEmpty()) return;
            var client = Minecraft.getInstance();
            float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            var camera = client.gameRenderer.mainCamera();
            var eye = camera.position();
            var orientation = new Quaternionf(camera.rotation());
            for (var effect : List.copyOf(ACTIVE.values())) {
                if (!effect.valid(client, partial)) continue;
                effect.frame(client, partial, context.levelState().cameraRenderState);
                effect.renderWorld(client, partial, eye, orientation, context.poseStack(), context.submitNodeCollector());
            }
            // Hands are read afresh every frame; one not drawn next frame falls back to its resting place.
            for (var effect : ACTIVE.values()) effect.drawnHand = null;
        });
    }

    private static void begin(Minecraft client, DigiviceRecallPayload packet) {
        var level = client.level;
        if (level == null || client.player == null) return;
        boolean own = packet.recipient() == client.player.getId() && !packet.token().equals(DigiviceRecallPayload.NO_TOKEN);
        var previous = ACTIVE.remove(packet.recipient());
        if (previous != null) previous.finish(client);
        if (!(level.getEntity(packet.recipient()) instanceof AbstractClientPlayer recipient)) return;
        var effect = new RecallVisuals(level, packet, own);
        effect.setUp(client, recipient);
        ACTIVE.put(packet.recipient(), effect);
    }
    private void setUp(Minecraft client, AbstractClientPlayer recipient) {
        source = active.source();
        float pitch = active.pitch(), yaw = active.yaw();
        modelStack = new ItemStack(DCItems.DIGIVICE);
        // Capture the visible interpolated pose before server removal, including an unfinished drop.
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (var entity : level.entitiesForRendering()) if (entity.getUUID().equals(active.sourceEntity()) && entity instanceof DroppedDigivice drop) {
            source = drop.getPosition(partial); pitch = drop.pitch(partial); yaw = drop.getYRot(); modelStack = drop.stack().copy();
            sourceLight = client.getEntityRenderDispatcher().getPackedLightCoords(drop, partial);
            break;
        }
        source = source.add(0, DroppedDigiviceRenderer.restHeight(item(client).getModelBoundingBox(), pitch), 0);
        rest = new Quaternionf().rotationY((float)Math.toRadians(yaw)).rotateX((float)Math.toRadians(pitch));
        firstPerson = firstPerson(client);
        view = firstPerson ? plainView(client) : bodyView(recipient, partial);
        held = firstPerson ? view.held() : restingHand(recipient, partial);
        replan(recipient, 0);
        // The chip breaking into data: heard in the head by its caller, from their hand by anyone watching.
        if (own) client.getSoundManager().play(SimpleSoundInstance.forUI(DCSounds.RECALL_CALL, 1F, .9F));
        else playAt(recipient.getEyePosition(), DCSounds.RECALL_CALL, .9F);
    }
    private void replan(AbstractClientPlayer recipient, float t) {
        plan = RecallFlight.plan(active.journey(), RecallPath.of(level), source, rest, active.source(), active.sameDimension(), view, held);
        plannedAt = recipient.position();
        plannedTime = t;
    }
    /** Ends early without stranding the device's appearance: it shows in its slot at once. */
    private void finish(Minecraft client) {
        if (!arrived) reveal(client, false);
        flightSound = null;
        ACTIVE.remove(active.recipient(), this);
    }
    private float seconds(float partial) { return (level.getGameTime() - start + partial) / 20F; }
    private AbstractClientPlayer recipient() {
        return level.getEntity(active.recipient()) instanceof AbstractClientPlayer player ? player : null;
    }
    private boolean firstPerson(Minecraft client) {
        return own && client.options.getCameraType().isFirstPerson() && client.getCameraEntity() == client.player;
    }
    private HumanoidArm arm(HumanoidArm mainArm) {
        return active.hand() == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
    }

    private boolean valid(Minecraft client, float partial) {
        var player = level == client.level ? recipient() : null;
        if (player == null || !player.isAlive() || player.isSpectator() || seconds(partial) >= active.journey().duration()) {
            finish(client);
            return false;
        }
        var stack = player.getItemInHand(active.hand());
        boolean device = own ? active.token().equals(Digivices.token(stack)) : stack.is(DCItems.DIGIVICE);
        if (device) {
            if (!received) { modelStack = stack.copyWithCount(1); received = true; }
        } else if (received || seconds(partial) > 2 || !(stack.is(DCItems.RECALL_CHIP) || stack.isEmpty())) {
            // The hand changed to something else: the recall's slot is no longer on show.
            finish(client);
            return false;
        }
        advance(client, player, seconds(partial));
        return true;
    }
    /** This frame's camera and landing: the first-person hand in the caller's own view, else the drawn hand. */
    private void frame(Minecraft client, float partial, CameraRenderState state) {
        var player = recipient();
        firstPerson = firstPerson(client);
        if (firstPerson) {
            view = view(client, state);
            held = view.held();
        } else {
            view = bodyView(player, partial);
            held = drawnHand != null ? drawnHand : restingHand(player, partial);
        }
    }
    private void advance(Minecraft client, AbstractClientPlayer player, float t) {
        var journey = plan.journey();
        var listener = client.gameRenderer.mainCamera().position();
        // A distant device appears relative to the player: walking during the wait moves where it comes in. Replanned
        // early enough for the search to finish in the background before it shows.
        if (!journey.nearby() && t < journey.departure() - .4F && t - plannedTime > .5F
                && player.position().distanceTo(plannedAt) > 1) replan(player, t);
        if (!woke && t >= journey.departure()) {
            woke = true;
            if (journey.nearby()) {
                var at = audible(listener, source);
                level.playLocalSound(at.x, at.y, at.z, DCSounds.RECALL_WAKE, SoundSource.PLAYERS, 1F, 1F, false);
            }
        }
        if (!launched && t >= journey.flightAt()) {
            launched = true;
            var from = plan.start();
            if (!journey.nearby()) level.playLocalSound(from.x, from.y, from.z, DCSounds.RECALL_WAKE, SoundSource.PLAYERS, 1F, 1.12F, false);
        }
        // The rush of the flight rides on the device and peaks as it meets the hand.
        if (flightSound == null && t >= Math.max(journey.departure(), journey.arriveAt() - FLIGHT_SOUND_PEAK)) {
            flightSound = new FlightSound(heard != null ? heard : plan.start());
            client.getSoundManager().play(flightSound);
        }
        if (!arrived && t >= journey.arriveAt()) {
            arrived = true;
            if (firstPerson) {
                // The hand gives along the direction the device came in, as far as its speed says.
                var hand = view.held();
                float at = journey.arriveAt(), dt = .03F;
                var a = RecallFlight.inView(RecallFlight.sample(plan, view, hand, at - dt), view);
                var b = RecallFlight.inView(RecallFlight.sample(plan, view, hand, at - .0001F), view);
                var velocity = b.sub(a).div(dt - .0001F);
                float speed = velocity.length();
                if (speed > 1e-4F) recoil.set(velocity).mul(Math.min(RECOIL_MAX, speed / RECOIL_OMEGA) / speed);
            }
            reveal(client, true);
        }
    }
    private void reveal(Minecraft client, boolean landed) {
        arrived = true;
        if (!own) {
            if (landed) playAt(held.position(), DCSounds.RECALL_CATCH, .95F);
            return;
        }
        var player = client.player;
        if (player == null) return;
        var stack = player.getItemInHand(active.hand());
        if (!active.token().equals(Digivices.token(stack))) return;
        if (landed) {
            stack.setPopTime(5);
            client.getSoundManager().play(SimpleSoundInstance.forUI(DCSounds.RECALL_CATCH, 1F, .95F));
        }
        if (active.hand() == InteractionHand.MAIN_HAND) ((HudAccess)client.gui.hud).digicube$replayHighlight();
    }
    private void playAt(Vec3 at, SoundEvent sound, float pitch) {
        level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, 1F, pitch, false);
    }

    /** Planning needs only the eye and orientation; bob and sway are applied live. */
    private RecallFlight.View plainView(Minecraft client) {
        var camera = client.gameRenderer.mainCamera();
        return new RecallFlight.View(camera.position(), new Quaternionf(camera.rotation()), new Matrix4f(), 1,
                new Quaternionf(), side(client.player));
    }
    private RecallFlight.View view(Minecraft client, CameraRenderState state) {
        var player = client.player;
        var camera = client.gameRenderer.mainCamera();
        float frame = camera.getCameraEntityPartialTicks(client.getDeltaTracker());
        float focal = (float)(1 / Math.tan(Math.toRadians(state.hudFov) / 2));
        // The hand lags the view a little (ItemInHandRenderer.submitHandsWithItems).
        var sway = new Quaternionf()
                .rotateX((float)Math.toRadians((player.getViewXRot(frame) - Mth.lerp(frame, player.xBobO, player.xBob)) * .1F))
                .rotateY((float)Math.toRadians((player.getViewYRot(frame) - Mth.lerp(frame, player.yBobO, player.yBob)) * .1F));
        return new RecallFlight.View(camera.position(), new Quaternionf(camera.rotation()), bob(state, client.options),
                state.projectionMatrix.m11() / focal, sway, side(player));
    }
    /** The recipient's own head, seen from outside. */
    private RecallFlight.View bodyView(AbstractClientPlayer player, float partial) {
        return RecallFlight.View.body(player.getEyePosition(partial), player.getViewYRot(partial), player.getViewXRot(partial), side(player));
    }
    /** Where the hand would be with the arm at rest, until the player model draws the real one. */
    private RecallFlight.Pose restingHand(AbstractClientPlayer player, float partial) {
        return RecallFlight.thirdPersonHeld(player.getPosition(partial), Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot), side(player));
    }
    /** GameRenderer's bobHurt and bobView, which both the level projection and the hand pass apply. */
    private static Matrix4f bob(CameraRenderState camera, Options options) {
        var m = new Matrix4f();
        var entity = camera.entityRenderState;
        if (entity.isLiving) {
            if (entity.isDeadOrDying) m.rotateZ((float)Math.toRadians(40 - 8000 / (Math.min(entity.deathTime, 20) + 200)));
            if (entity.hurtTime >= 0 && entity.hurtDuration > 0) {
                float hurt = entity.hurtTime / entity.hurtDuration;
                hurt = Mth.sin(hurt * hurt * hurt * hurt * (float)Math.PI);
                m.rotateY((float)Math.toRadians(-entity.hurtDir))
                        .rotateZ((float)Math.toRadians(-hurt * 14 * options.damageTiltStrength().get()))
                        .rotateY((float)Math.toRadians(entity.hurtDir));
            }
        }
        if (options.bobView().get() && entity.isPlayer) {
            float walk = entity.backwardsInterpolatedWalkDistance, amount = entity.bob;
            m.translate(Mth.sin(walk * (float)Math.PI) * amount * .5F, -Math.abs(Mth.cos(walk * (float)Math.PI) * amount), 0)
                    .rotateZ((float)Math.toRadians(Mth.sin(walk * (float)Math.PI) * amount * 3))
                    .rotateX((float)Math.toRadians(Math.abs(Mth.cos(walk * (float)Math.PI - .2F) * amount) * 5));
        }
        return m;
    }
    private int side(AbstractClientPlayer player) {
        return arm(player.getMainArm()) == HumanoidArm.RIGHT ? 1 : -1;
    }
    private ItemStackRenderState item(Minecraft client) {
        var state = new ItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(state, modelStack, ItemDisplayContext.NONE, level, client.player, client.player.getId());
        return state;
    }
    /** Lit like the ground it left, then glowing through the flight, then lit like the hand as it lands. */
    private int light(float t, int hand) {
        var journey = plan.journey();
        if (t < journey.flightAt()) return blendLight(sourceLight, LightCoordsUtil.FULL_BRIGHT, RecallMotion.smooth((t - journey.departure()) / .2F));
        float u = (t - journey.flightAt()) / journey.flight();
        return blendLight(LightCoordsUtil.FULL_BRIGHT, hand, RecallMotion.smooth((u - .7F) / .3F));
    }

    /** Where a cue at {@code p} is played: in its direction, but never so far out that it fades away (48 blocks). */
    private static Vec3 audible(Vec3 eye, Vec3 p) {
        double d = p.distanceTo(eye);
        return d <= CUE_REACH ? p : eye.add(p.subtract(eye).scale(CUE_REACH / d));
    }
    /**
     * The flight and its light, relative to the camera at {@code eye}; seen from outside, also the chip breaking and
     * the catch in the recipient's hand.
     */
    private void renderWorld(Minecraft client, float partial, Vec3 eye, Quaternionf camera, PoseStack pose, SubmitNodeCollector collector) {
        float t = seconds(partial);
        var journey = plan.journey();
        var player = recipient();
        // A route round blocks is searched a slice per frame while the chip breaks and the device rises.
        plan.planner().work(PLANNING_SLICE);
        var frame = RecallFlight.sample(plan, view, held, t);
        heard = audible(eye, RecallFlight.displayed(frame, view));
        boolean visible = t < journey.arriveAt() && frame.pose().scale() > .001F && (journey.nearby() || t >= journey.flightAt());
        // Close to the eye the hand pass takes over; latched, so the device never hops back into the world.
        if (firstPerson && visible && !inHand && t >= journey.flightAt() && (frame.progress() > .92F
                || RecallFlight.displayed(frame, view).distanceTo(view.eye()) < RecallFlight.HAND_PASS_DISTANCE)) inHand = true;
        var offset = view.eye().subtract(eye);
        if (visible && !(firstPerson && inHand)) {
            pose.pushPose();
            pose.translate((float)offset.x, (float)offset.y, (float)offset.z);
            pose.mulPose(RecallFlight.worldMatrix(frame, view));
            item(client).submit(pose, collector, light(t, client.getEntityRenderDispatcher().getPackedLightCoords(player, partial)),
                    OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        submitLight(RecallFx.world(plan, view, held, t, eye, camera), pose, collector);
        if (firstPerson || player.isInvisible()) return;
        // Seen from outside: the chip breaks into data where the hand holds it, and the hand flashes as it catches.
        var chip = RecallMotion.frame(t);
        var burst = RecallFx.burst(t - journey.arriveAt());
        if (chip.chip().isEmpty() && chip.glow().isEmpty() && burst.isEmpty()) return;
        var hand = held.position().subtract(eye);
        pose.pushPose();
        pose.translate((float)hand.x, (float)hand.y, (float)hand.z);
        pose.mulPose(camera);
        pose.scale(OUTSIDE_SCALE, OUTSIDE_SCALE, OUTSIDE_SCALE);
        submitLight(burst, pose, collector);
        pose.scale(side(player), 1, 1);
        submit(chip.chip(), pose, collector, EvolutionRenderType.RECALL_CHIP);
        submit(chip.glow(), pose, collector, EvolutionRenderType.DIGIVICE_BEACON);
        pose.popPose();
    }

    private boolean render(AbstractClientPlayer player, InteractionHand hand, float partial, float equip,
                           PoseStack pose, SubmitNodeCollector collector, int light) {
        var client = Minecraft.getInstance();
        if (!firstPerson(client) || !valid(client, partial) || !firstPerson || hand != active.hand() || view == null) return false;
        float t = seconds(partial);
        var journey = plan.journey();
        int side = side(player);
        var arm = side == 1 ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        // This pass's own bob, read back from its pose stack (inverse view, bob, sway): the landing meets vanilla's
        // held item exactly, whatever the bob replica in the level pass made of it.
        var base = new Matrix4f(pose.last().pose());
        var handView = view.withBob(new Matrix4f().rotation(new Quaternionf(view.orientation()).conjugate()).mul(base)
                .rotate(new Quaternionf(view.sway()).conjugate()));
        float since = t - journey.arriveAt();
        float swing = since > 0 ? (float)(Math.sin(RECOIL_OMEGA * since) * Math.exp(-since / RECOIL_DECAY)) : 0;
        pose.pushPose();
        pose.translate(recoil.x * swing, recoil.y * swing, recoil.z * swing);
        pose.translate(side * .56F, -.52F - equip * .6F * RecallMotion.smooth(since / journey.settle()), -.72F);
        var heldMatrix = new Matrix4f(pose.last().pose()).mul(RecallFlight.heldDisplay(side));
        if (!player.isInvisible()) {
            var renderer = client.getEntityRenderDispatcher().getPlayerRenderer(player);
            var skin = player.getSkin();
            pose.pushPose();
            float lift = RecallMotion.gripLift(t);
            pose.translate(side * -.05F * lift, .04F * lift, 0);
            DigiviceGrip.apply(pose, arm, skin.model() == PlayerModelType.SLIM);
            if (side == 1) renderer.renderRightHand(pose, collector, light, skin.body().texturePath(), player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
            else renderer.renderLeftHand(pose, collector, light, skin.body().texturePath(), player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
            pose.popPose();
        }
        pose.translate(side * -1.5F / 16, 4F / 16, -2F / 16);
        submitLight(RecallFx.burst(since), pose, collector);
        var chip = RecallMotion.frame(t);
        pose.scale(side, 1, 1);
        submit(chip.chip(), pose, collector, EvolutionRenderType.RECALL_CHIP);
        submit(chip.glow(), pose, collector, EvolutionRenderType.DIGIVICE_BEACON);
        pose.popPose();
        Matrix4f device = since >= 0 ? heldMatrix
                : inHand ? RecallFlight.handMatrix(RecallFlight.sample(plan, handView, handView.held(), t), handView) : null;
        if (device != null) {
            pose.pushPose();
            pose.mulPose(new Matrix4f(pose.last().pose()).invert().mul(device));
            item(client).submit(pose, collector, since >= 0 ? light : light(t, light), OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        return true;
    }
    private static int blendLight(int a, int b, float progress) {
        return LightCoordsUtil.smoothPack(Math.round(LightCoordsUtil.smoothBlock(a) * (1 - progress) + LightCoordsUtil.smoothBlock(b) * progress),
                Math.round(LightCoordsUtil.smoothSky(a) * (1 - progress) + LightCoordsUtil.smoothSky(b) * progress));
    }
    static void submitLight(List<RecallFx.Quad> quads, PoseStack pose, SubmitNodeCollector collector) {
        if (quads.isEmpty()) return;
        collector.submitCustomGeometry(pose, EvolutionRenderType.DIGIVICE_BEACON, (matrix, vertices) -> {
            for (var q : quads) for (int i = 0; i < 4; i++)
                vertices.addVertex(matrix, q.xyz()[i * 3], q.xyz()[i * 3 + 1], q.xyz()[i * 3 + 2]).setColor(q.argb()[i])
                        .setUv(q.uv()[i * 2], q.uv()[i * 2 + 1]).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(matrix, 0, 0, 1);
        });
    }
    /** Follows the flying device, so its rush passes by the ear; stops with the recall. */
    private final class FlightSound extends AbstractTickableSoundInstance {
        FlightSound(Vec3 at) {
            super(DCSounds.RECALL_FLIGHT, SoundSource.PLAYERS, RandomSource.create());
            x = at.x; y = at.y; z = at.z;
            volume = 1; pitch = 1;
        }
        @Override public void tick() {
            if (flightSound != this) { stop(); return; }
            if (heard != null) { x = heard.x; y = heard.y; z = heard.z; }
        }
    }
    static void submit(List<EvolutionMesh.Face> faces, PoseStack pose, SubmitNodeCollector collector, RenderType type) {
        if (faces.isEmpty()) return;
        collector.submitCustomGeometry(pose, type, (matrix, vertices) -> {
            for (var face : faces) {
                var v = face.vertices();
                for (int i = 0; i < v.length; i += 8)
                    vertices.addVertex(matrix, v[i], v[i + 1], v[i + 2]).setColor(face.color()).setUv(v[i + 3], v[i + 4])
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(matrix, 0, 0, 1);
            }
        });
    }
}
