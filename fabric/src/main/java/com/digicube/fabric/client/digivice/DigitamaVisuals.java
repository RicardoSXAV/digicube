package com.digicube.fabric.client.digivice;

import com.digicube.fabric.client.evolution.EvolutionRenderType;
import com.digicube.fabric.client.render.DigiviceGrip;
import com.digicube.registry.DCItems;
import com.digicube.registry.DCSounds;
import com.digicube.scan.DigitamaUsePayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A Digitama used from the hand, as every nearby client sees it. The Digitama is already in the Digivice; this plays
 * the egg breaking into golden data where the hand held it: the recall chip's break ({@link RecallMotion}) on the egg's
 * own texels, with the chip's sound. In the tamer's own first-person view the hand holds it up while it breaks and then
 * lowers; from any other camera it breaks where the player model's hand is, which shows nothing else meanwhile. Once
 * the egg is gone the tamer's Digivice opens on the Digispace, where the Digitama comes together out of the same data
 * ({@link DigispaceTab#arrive}).
 */
public final class DigitamaVisuals {
    /** The hand lowers out of view this long after the last texel is gone. */
    private static final float LOWER = .25F;
    private static final float DURATION = RecallMotion.GONE + LOWER;
    /** The egg breaking in a hand seen from outside, against its first-person size, as the chip's. */
    private static final float OUTSIDE_SCALE = .8F;
    /** Every egg breaking in view, by the entity id of the player who used it. */
    private static final Map<Integer, DigitamaVisuals> ACTIVE = new LinkedHashMap<>();
    /** Opens the Digivice on a Digitama just taken in. */
    private static Consumer<UUID> opener = id -> {};

    private final DigitamaUsePayload use;
    private final ClientLevel level;
    /** The tamer's own use: the Digivice opens on the new Digitama once the egg is gone. */
    private final boolean own;
    private final long start;
    private final List<RecallMotion.Pixel> pixels;
    /** The tamer's hand as ItemInHandLayer drew it this frame, or null when it holds nothing drawn. */
    private RecallFlight.Pose drawnHand;
    private boolean opened;

    private DigitamaVisuals(ClientLevel level, DigitamaUsePayload use, boolean own) {
        this.level = level;
        this.use = use;
        this.own = own;
        this.start = level.getGameTime();
        DigiviceArt.Pixels sprite = DigiviceArt.pixels(DigitamaArt.texture(use.family()));
        this.pixels = sprite == null ? RecallMotion.PIXELS : RecallMotion.pixels(sprite.argb(), sprite.width());
    }

    public static void init(Consumer<UUID> open) {
        opener = open;
        ClientPlayNetworking.registerGlobalReceiver(DigitamaUsePayload.TYPE, (packet, context) ->
                context.client().execute(() -> begin(context.client(), packet)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ACTIVE::clear));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (var effect : List.copyOf(ACTIVE.values())) effect.step(client);
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (ACTIVE.isEmpty()) return;
            var client = Minecraft.getInstance();
            float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            var camera = client.gameRenderer.mainCamera();
            for (var effect : ACTIVE.values()) {
                effect.renderWorld(client, partial, camera.position(), new Quaternionf(camera.rotation()), context.poseStack(), context.submitNodeCollector());
                effect.drawnHand = null;
            }
        });
    }

    private static void begin(Minecraft client, DigitamaUsePayload packet) {
        if (client.level == null || client.player == null) return;
        if (!(client.level.getEntity(packet.recipient()) instanceof AbstractClientPlayer recipient)) return;
        boolean own = recipient == client.player && !packet.egg().equals(DigitamaUsePayload.NO_EGG);
        ACTIVE.put(packet.recipient(), new DigitamaVisuals(client.level, packet, own));
        // The egg breaking into data: heard in the head by its tamer, from their hand by anyone watching.
        if (own) client.getSoundManager().play(SimpleSoundInstance.forUI(DCSounds.RECALL_CALL, 1F, .9F));
        else {
            var at = recipient.getEyePosition();
            client.level.playLocalSound(at.x, at.y, at.z, DCSounds.RECALL_CALL, SoundSource.PLAYERS, 1F, .9F, false);
        }
    }

    private float seconds(float partial) { return (level.getGameTime() - start + partial) / 20F; }

    private AbstractClientPlayer recipient() {
        return level.getEntity(use.recipient()) instanceof AbstractClientPlayer player ? player : null;
    }

    private int side(AbstractClientPlayer player) {
        HumanoidArm arm = use.hand() == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        return arm == HumanoidArm.RIGHT ? 1 : -1;
    }

    private void step(Minecraft client) {
        var player = recipient();
        if (client.level != level || player == null || !player.isAlive() || player.isSpectator()) {
            ACTIVE.remove(use.recipient(), this);
            return;
        }
        float t = seconds(0);
        if (own && !opened && t * 20 >= DigitamaUsePayload.OPEN_TICKS) {
            opened = true;
            opener.accept(use.egg());
        }
        if (t >= DURATION) {
            ACTIVE.remove(use.recipient(), this);
            // The empty hand comes back up from below, as after any use.
            if (own) client.gameRenderer.itemInHandRenderer.itemUsed(use.hand());
        }
    }

    /** ItemInHandLayer, as it submits a held item: remembers the hand and hides a Digitama still in it while one breaks. */
    public static boolean thirdPersonHand(ArmedEntityRenderState state, HumanoidArm arm, PoseStack pose) {
        if (ACTIVE.isEmpty() || !(state instanceof AvatarRenderState avatar)) return false;
        var effect = ACTIVE.get(avatar.id);
        if (effect == null) return false;
        HumanoidArm used = effect.use.hand() == InteractionHand.MAIN_HAND ? state.mainArm : state.mainArm.getOpposite();
        if (used != arm) return false;
        var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        effect.drawnHand = RecallFlight.Pose.of(camera, new Matrix4f(pose.last().pose()).mul(RecallFlight.thirdPersonDisplay()));
        var player = effect.recipient();
        return player != null && player.getItemInHand(effect.use.hand()).is(DCItems.DIGITAMA);
    }

    /** The local player's first-person hand pass; true when the egg breaking drew that hand instead of vanilla. */
    public static boolean firstPersonHand(AbstractClientPlayer player, InteractionHand hand, float partial, PoseStack pose,
                                          SubmitNodeCollector collector, int light) {
        var client = Minecraft.getInstance();
        if (ACTIVE.isEmpty() || player != client.player) return false;
        var effect = ACTIVE.get(player.getId());
        return effect != null && effect.own && hand == effect.use.hand() && firstPerson(client)
                && effect.render(client, player, partial, pose, collector, light);
    }

    private static boolean firstPerson(Minecraft client) {
        return client.options.getCameraType().isFirstPerson() && client.getCameraEntity() == client.player;
    }

    /** The hand held up with the egg breaking in its fingers, as the chip breaks; then the hand lowers. */
    private boolean render(Minecraft client, AbstractClientPlayer player, float partial, PoseStack pose, SubmitNodeCollector collector, int light) {
        float t = seconds(partial);
        if (t >= DURATION) return false;
        int side = side(player);
        float lower = RecallMotion.smooth((t - RecallMotion.GONE) / LOWER);
        pose.pushPose();
        pose.translate(side * .56F, -.52F - .6F * lower, -.72F);
        if (!player.isInvisible()) {
            var renderer = client.getEntityRenderDispatcher().getPlayerRenderer(player);
            var skin = player.getSkin();
            pose.pushPose();
            float lift = RecallMotion.gripLift(t);
            pose.translate(side * -.05F * lift, .04F * lift, 0);
            DigiviceGrip.apply(pose, side == 1 ? HumanoidArm.RIGHT : HumanoidArm.LEFT, skin.model() == PlayerModelType.SLIM);
            if (side == 1) renderer.renderRightHand(pose, collector, light, skin.body().texturePath(), player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
            else renderer.renderLeftHand(pose, collector, light, skin.body().texturePath(), player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
            pose.popPose();
        }
        pose.translate(side * -1.5F / 16, 4F / 16, -2F / 16);
        var egg = RecallMotion.frame(pixels, t);
        pose.scale(side, 1, 1);
        RecallVisuals.submit(egg.chip(), pose, collector, EvolutionRenderType.RECALL_CHIP);
        RecallVisuals.submit(egg.glow(), pose, collector, EvolutionRenderType.DIGIVICE_BEACON);
        pose.popPose();
        return true;
    }

    /** Seen from outside (the tamer's own third person, or another player's view): the egg breaks where the hand is. */
    private void renderWorld(Minecraft client, float partial, net.minecraft.world.phys.Vec3 eye, Quaternionf camera, PoseStack pose, SubmitNodeCollector collector) {
        var player = recipient();
        if (player == null || player.isInvisible() || own && firstPerson(client)) return;
        var egg = RecallMotion.frame(pixels, seconds(partial));
        if (egg.chip().isEmpty() && egg.glow().isEmpty()) return;
        var held = drawnHand != null ? drawnHand
                : RecallFlight.thirdPersonHeld(player.getPosition(partial), Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot), side(player));
        var hand = held.position().subtract(eye);
        pose.pushPose();
        pose.translate((float) hand.x, (float) hand.y, (float) hand.z);
        pose.mulPose(camera);
        pose.scale(OUTSIDE_SCALE * side(player), OUTSIDE_SCALE, OUTSIDE_SCALE);
        RecallVisuals.submit(egg.chip(), pose, collector, EvolutionRenderType.RECALL_CHIP);
        RecallVisuals.submit(egg.glow(), pose, collector, EvolutionRenderType.DIGIVICE_BEACON);
        pose.popPose();
    }
}
