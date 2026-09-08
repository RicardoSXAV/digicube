package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.fabric.client.model.BlueBlasterModel;
import net.minecraft.world.phys.AABB;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.AgumonModel;
import com.digicube.fabric.client.model.GabumonModel;
import com.digicube.fabric.client.model.GomamonModel;
import com.digicube.fabric.client.model.TentomonModel;
import com.digicube.fabric.client.model.GarurumonModel;
import com.digicube.fabric.client.model.AnimatedRiderModel;
import com.digicube.fabric.client.model.KoromonModel;
import com.digicube.fabric.client.model.TsunomonModel;
import com.digicube.fabric.client.model.GreymonModel;
import com.digicube.fabric.client.model.MegaFlameModel;
import com.digicube.digimon.DigimonAttack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.Map;

/**
 * Renders every {@link DigimonEntity} with its species model and texture.
 * Species without authored assets use Agumon's model and texture together.
 */
public class DigimonRenderer extends MobRenderer<DigimonEntity, DigimonRenderState, EntityModel<DigimonRenderState>> {

    private static final Map<Identifier, Identifier> TEXTURES = Map.of(
            Constants.id("agumon"), Constants.id("textures/entity/digimon/agumon.png"),
            Constants.id("gabumon"), Constants.id("textures/entity/digimon/gabumon.png"),
            Constants.id("gomamon"), Constants.id("textures/entity/digimon/gomamon.png"),
            Constants.id("tentomon"), Constants.id("textures/entity/digimon/tentomon.png"),
            Constants.id("garurumon"), Constants.id("textures/entity/digimon/garurumon.png"),
            Constants.id("koromon"), Constants.id("textures/entity/digimon/koromon.png"),
            Constants.id("tsunomon"), Constants.id("textures/entity/digimon/tsunomon.png"),
            Constants.id("greymon"), Constants.id("textures/entity/digimon/greymon.png"));
    private static final Identifier FALLBACK_TEXTURE = TEXTURES.get(DigimonEntity.DEFAULT_SPECIES);
    private final Map<Identifier, EntityModel<DigimonRenderState>> models;
    private final MegaFlameModel mouthFlame;
    private final BlueBlasterModel blueBlaster;

    public DigimonRenderer(EntityRendererProvider.Context context) {
        super(context, new AgumonModel(context.bakeLayer(AgumonModel.LAYER)), 0.4F);
        mouthFlame = new MegaFlameModel(context.bakeLayer(MegaFlameModel.LAYER));
        blueBlaster = new BlueBlasterModel(context.bakeLayer(BlueBlasterModel.LAYER));
        this.models = Map.of(
                DigimonEntity.DEFAULT_SPECIES, this.model,
                Constants.id("gabumon"), new GabumonModel(context.bakeLayer(GabumonModel.LAYER)),
                Constants.id("gomamon"), new GomamonModel(context.bakeLayer(GomamonModel.LAYER)),
                Constants.id("tentomon"), new TentomonModel(context.bakeLayer(TentomonModel.LAYER)),
                Constants.id("garurumon"), new GarurumonModel(context.bakeLayer(GarurumonModel.LAYER)),
                Constants.id("koromon"), new KoromonModel(context.bakeLayer(KoromonModel.LAYER)),
                Constants.id("tsunomon"), new TsunomonModel(context.bakeLayer(TsunomonModel.LAYER)),
                Constants.id("greymon"), new GreymonModel(context.bakeLayer(GreymonModel.LAYER)));
    }

    @Override
    public void submit(DigimonRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        this.model = this.models.getOrDefault(state.species, this.models.get(DigimonEntity.DEFAULT_SPECIES));
        super.submit(state, poseStack, collector, cameraState);
        if (state.blueBlaster.length > 0.05F && state.attackDefinition != null) {
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50.0F;
            var frame = state.attackDefinition.motion().sample(tick);
            var mouth = frame.aimedMouth(state.attackAimPitch).yRot(-state.bodyRot * Mth.DEG_TO_RAD);
            poseStack.pushPose();
            poseStack.translate(mouth.x, mouth.y, mouth.z);
            BlueBlasterRenderer.submit(blueBlaster, state.blueBlaster, poseStack, collector);
            poseStack.popPose();
        }
        if (!state.isBeingRidden && state.attackAnimation.isStarted() && state.attackDefinition != null
                && state.attackDefinition.kind() == DigimonAttack.Kind.FLAME_SHOT) {
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50.0F;
            if (tick >= 7 && tick < 22) {
                var frame = state.attackDefinition.motion().sample(tick);
                var mouth = frame.aimedMouth(state.attackAimPitch).yRot(-state.bodyRot * Mth.DEG_TO_RAD);
                var flame = state.mouthFlame;
                flame.ageInTicks = tick;
                flame.charging = true;
                flame.burst = false;
                flame.yRot = state.bodyRot;
                flame.xRot = -(frame.headPitch() + state.attackAimPitch * frame.aimWeight());
                flame.outlineColor = state.outlineColor;
                poseStack.pushPose();
                poseStack.translate(mouth.x, mouth.y, mouth.z);
                MegaFlameRenderer.submitFlame(mouthFlame, flame, poseStack, collector);
                poseStack.popPose();
            }
        }
    }

    @Override
    public DigimonRenderState createRenderState() {
        return new DigimonRenderState();
    }

    @Override
    public void extractRenderState(DigimonEntity entity, DigimonRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        // Vanilla decided whether a nameplate shows (distance, F1, invisibility); wild ones add their level.
        if (state.nameTag != null && !entity.isOwned()) {
            state.nameTag = Component.translatable("digimon.digicube.wild_nameplate", entity.getLevel(), state.nameTag);
        }
        state.species = entity.getSpeciesId();
        state.modelScale = entity.getBody().modelScale();
        state.isBeingRidden = entity.isVehicle();
        state.runAnimationAmount = entity.getRunAnimationAmount(partialTick);
        state.swimAnimationAmount = entity.getSwimAnimationAmount(partialTick);
        state.swimAnimationPhase = entity.getSwimAnimationPhase(partialTick);
        state.swimMotionAmount = entity.getSwimMotionAmount(partialTick);
        state.groundAnimationPhase = entity.getGroundAnimationPhase(partialTick);
        state.flightPhase = entity.getFlightPhase();
        state.flightPhaseTime = entity.getFlightPhaseTime(partialTick);
        state.flightLoopTime = entity.getFlightLoopTime(partialTick);
        state.flightWalkAmount = entity.getFlightWalkAmount(partialTick);
        state.swimBank = entity.getSwimBank(partialTick);
        state.shadowRadius = entity.getBbWidth() * 0.5F;
        if (entity.isGuiPreview()) {
            // A screen preview: no nameplate, no shadow, and nothing of the level around it.
            state.nameTag = null;
            state.shadowRadius = 0;
        }
        state.attackAnimation.copyFrom(entity.attackAnimationState);
        state.attackAnimationName = entity.getAttackAnimationName();
        state.attackDefinition = entity.getAnimatingAttack();
        state.attackAimPitch = entity.getAttackAimPitch(partialTick);
        if (entity.isFlyingMovement() || (entity.canSwim() && state.swimAnimationAmount > 0.01F) || state.isBeingRidden
                || state.attackAnimation.isStarted() && state.attackDefinition != null && state.attackDefinition.locksBodyFacing()) {
            // Swimming, riding and committed attacks turn the entire creature.
            // Keep its rendered body aligned with the server's steering direction.
            state.bodyRot = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
            state.yRot = 0.0F;
        }
        state.blueBlaster.length = 0;
        if (entity.isAlive() && !state.isBeingRidden && state.attackAnimation.isStarted() && state.attackDefinition != null
                && state.attackDefinition.kind() == DigimonAttack.Kind.FLAME_STREAM) {
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50.0F;
            var motion = state.attackDefinition.motion();
            if (tick >= motion.activeFrom() && tick < motion.activeUntil() + 1) {
                var frame = motion.sample(tick);
                var flame = state.blueBlaster;
                flame.ageInTicks = tick - motion.activeFrom();
                flame.yaw = state.bodyRot;
                flame.pitch = -(frame.headPitch() + state.attackAimPitch * frame.aimWeight());
                flame.length = (float) entity.flameStream(state.attackDefinition, tick,
                        state.attackAimPitch, state.bodyRot).length();
                flame.outlineColor = state.outlineColor;
            }
        }
    }

    @Override
    protected AABB getBoundingBoxForCulling(DigimonEntity entity) {
        AABB bounds = super.getBoundingBoxForCulling(entity);
        if (entity.canSwim()) bounds = bounds.inflate(entity.getBody().modelScale());
        if (entity.canFly()) bounds = bounds.inflate(2 * entity.getBody().modelScale());
        if (models.get(entity.getSpeciesId()) instanceof AnimatedRiderModel nativeModel) {
            bounds = bounds.inflate(nativeModel.cullingMargin() * entity.getBody().modelScale());
        }
        var attack = entity.getAnimatingAttack();
        return attack != null && attack.kind() == DigimonAttack.Kind.FLAME_STREAM
                ? bounds.inflate(attack.range()) : bounds;
    }

    @Override
    public Identifier getTextureLocation(DigimonRenderState state) {
        return TEXTURES.getOrDefault(state.species, FALLBACK_TEXTURE);
    }

    @Override
    protected void scale(DigimonRenderState state, PoseStack poseStack) {
        poseStack.scale(state.modelScale, state.modelScale, state.modelScale);
    }

    /**
     * Rider presentation for the current frame.
     * @param offset animated local seat displacement
     * @param pose seated leg angles
     */
    public record RiderVisual(net.minecraft.world.phys.Vec3 offset, AnimatedRiderModel.RiderPose pose) {}

    /**
     * Evaluate the actual mount model at the same clock as its rendered body.
     * @param entity ridden Digimon
     * @param partialTick render interpolation
     * @return its visual attachment, or null for mounts with an already fixed seat
     */
    public RiderVisual riderVisual(DigimonEntity entity, float partialTick) {
        if (!(models.get(entity.getSpeciesId()) instanceof AnimatedRiderModel mount)) return null;
        var state = createRenderState();
        extractRenderState(entity, state, partialTick);
        return new RiderVisual(mount.riderOffset(state), mount.riderPose());
    }
}
