package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.fabric.client.model.BlueBlasterModel;
import net.minecraft.world.phys.AABB;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.GabumonModel;
import com.digicube.fabric.client.model.GomamonModel;
import com.digicube.fabric.client.model.TentomonModel;
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
            Constants.id("gabumon"), Constants.id("textures/entity/digimon/gabumon.png"),
            Constants.id("gomamon"), Constants.id("textures/entity/digimon/gomamon.png"),
            Constants.id("ikkakumon"), Constants.id("textures/entity/digimon/ikkakumon.png"),
            Constants.id("tentomon"), Constants.id("textures/entity/digimon/tentomon.png"),
            Constants.id("koromon"), Constants.id("textures/entity/digimon/koromon.png"),
            Constants.id("tsunomon"), Constants.id("textures/entity/digimon/tsunomon.png"),
            Constants.id("greymon"), Constants.id("textures/entity/digimon/greymon.png"));
    private final Map<Identifier, EntityModel<DigimonRenderState>> models;
    /** Agumon's presentation scale, for a species drawn with Agumon's model because it has none of its own. */
    private final float fallbackScale;
    private final Map<String,com.digicube.fabric.client.model.NativeEffectModel> attackEffects=new java.util.HashMap<>();
    private final com.digicube.fabric.client.evolution.EvolutionPresentation evolution;
    private final Map<String,com.digicube.fabric.client.model.NativeEffectModel> authoredEffects=new java.util.HashMap<>();
    private final Map<DigimonEntity,com.digicube.fabric.client.model.ClothChains.State> cloth=new java.util.WeakHashMap<>();
    private final Map<DigimonEntity,com.digicube.fabric.client.model.RopeChains.State> ropes=new java.util.WeakHashMap<>();
    private final Map<DigimonEntity,com.digicube.fabric.client.model.TailChains.State> tails=new java.util.WeakHashMap<>();
    private final Map<DigimonEntity,com.digicube.fabric.client.model.SerpentSpine.State> serpents=new java.util.WeakHashMap<>();
    private final MegaFlameModel mouthFlame;
    private final BlueBlasterModel blueBlaster;
    /** A breath of puffs is drawn with its own effect model's boxes; a pounce's bite bursts its impact model at the jaws. */
    private final Map<String, BreathArt> breaths = new java.util.HashMap<>();
    private final Map<String, com.digicube.fabric.client.model.NativeEffectModel> impacts = new java.util.HashMap<>();
    /** Where each entity's last bite burst, in the world, so the burst stays where the jaws met while the body moves on. */
    private final Map<DigimonEntity, Bite> bites = new java.util.WeakHashMap<>();
    /** An electric discharge's bolts (ArcDischarge), drawn from the strike the caster last let go. */
    private final ArcRenderer arcs = new ArcRenderer();
    private record Bite(int tick, net.minecraft.world.phys.Vec3 at, float yaw, String effect) {}
    private final com.digicube.fabric.client.model.NativeEffectModel fistEffect;
    private final com.digicube.fabric.client.model.NativeEffectModel aimedWave;
    /** Ticks a discharge's bolts may live at most, and how far out of the caster's box they may reach (blocks). */
    private static final int ARC_LIFE = 12;
    private static final double ARC_REACH = 14;
    /** The wave's clip with every spike risen and the impact debris gone. */
    private static final float AIMED_WAVE_TICK = 50;

    public DigimonRenderer(EntityRendererProvider.Context context) {
        super(context, fallbackModel(context), 0.4F);
        fallbackScale = com.digicube.digimon.DigimonSpeciesRegistry.getOrThrow(DigimonEntity.DEFAULT_SPECIES).body().modelScale();
        // A move's forms share one effect model, each playing its own clip.
        for(var d:com.digicube.digimon.AuthoredAttacks.all()) if(d.effect()!=null) authoredEffects.computeIfAbsent(d.effect(),
                effect->new com.digicube.fabric.client.model.NativeEffectModel(context.bakeLayer(com.digicube.fabric.client.model.NativeEffectModel.layer(effect)),effect));
        mouthFlame = new MegaFlameModel(context.bakeLayer(MegaFlameModel.LAYER));
        blueBlaster = new BlueBlasterModel(context.bakeLayer(BlueBlasterModel.LAYER));
        for (var attack : com.digicube.digimon.BreathAttacks.attacks()) {
            var spec = com.digicube.digimon.BreathAttacks.get(attack);
            breaths.computeIfAbsent(spec.effect(), effect -> spec.art().equals("shards") ? new IceShardBreathRenderer(effect)
                    : new FrostBreathRenderer(effect, spec.pixel(), spec.cooling()));
        }
        for (var attack : com.digicube.digimon.PounceAttacks.attacks()) {
            String effect = com.digicube.digimon.PounceAttacks.get(attack).impact();
            if (!effect.isEmpty()) impacts.computeIfAbsent(effect, e -> new com.digicube.fabric.client.model.NativeEffectModel(
                    context.bakeLayer(com.digicube.fabric.client.model.NativeEffectModel.layer(e)), e));
        }
        fistEffect = new com.digicube.fabric.client.model.NativeEffectModel(context.bakeLayer(
                com.digicube.fabric.client.model.NativeEffectModel.layer("rock_punch_fx")), "rock_punch_fx");
        aimedWave = new com.digicube.fabric.client.model.NativeEffectModel(context.bakeLayer(
                com.digicube.fabric.client.model.NativeEffectModel.layer("tectonic_fist_fx")), "tectonic_fist_fx");
        this.models = new java.util.HashMap<>(Map.of(
                DigimonEntity.DEFAULT_SPECIES, this.model,
                Constants.id("gabumon"), new GabumonModel(context.bakeLayer(GabumonModel.LAYER)),
                Constants.id("gomamon"), new GomamonModel(context.bakeLayer(GomamonModel.LAYER)),
                Constants.id("tentomon"), new TentomonModel(context.bakeLayer(TentomonModel.LAYER)),
                Constants.id("koromon"), new KoromonModel(context.bakeLayer(KoromonModel.LAYER)),
                Constants.id("tsunomon"), new TsunomonModel(context.bakeLayer(TsunomonModel.LAYER)),
                Constants.id("greymon"), new GreymonModel(context.bakeLayer(GreymonModel.LAYER))));
        for (var definition : com.digicube.fabric.client.model.NativeGroundModel.definitions().values()) {
            if (!models.containsKey(definition.species())) models.put(definition.species(), new com.digicube.fabric.client.model.NativeGroundModel(
                    context.bakeLayer(definition.layer()), definition));
            if (definition.attackEffects() != null) {
                String effect = definition.attackEffects().effect();
                if (!attackEffects.containsKey(effect)) attackEffects.put(effect, new com.digicube.fabric.client.model.NativeEffectModel(
                        context.bakeLayer(com.digicube.fabric.client.model.NativeEffectModel.layer(effect)), effect));
            }
        }
        for (var species:com.digicube.digimon.DigimonSpeciesRegistry.all()) {
            if (species.body().mount().map(m->m.flight()!=null).orElse(false)) {
                var id=species.id();
                models.put(id,new com.digicube.fabric.client.model.NativeFlyingMountModel(
                        context.bakeLayer(new net.minecraft.client.model.geom.ModelLayerLocation(id,"main")),id));
            }
        }
        evolution=new com.digicube.fabric.client.evolution.EvolutionPresentation(models);
    }

    /** Agumon's native model: the default species, and the body drawn for any species without a model of its own. */
    private static EntityModel<DigimonRenderState> fallbackModel(EntityRendererProvider.Context context) {
        var definition = com.digicube.fabric.client.model.NativeGroundModel.definitions().get(DigimonEntity.DEFAULT_SPECIES);
        return new com.digicube.fabric.client.model.NativeGroundModel(context.bakeLayer(definition.layer()), definition);
    }

    /** A whip, from the arm this client runs (WhipArm): the model turns the whipping arm onto it. */
    static void whip(DigimonEntity entity, DigimonRenderState state, float partial) {
        state.whipWeight = 0;
        state.whipArm = null;
        var whip = entity.whip();
        if (whip == null || whip.weight(partial) <= 0) return;
        pose(whip, state, partial);
    }

    /** The whip's pose into the render state: its weight, arm and side, and every section's yaw and pitch. */
    public static void pose(com.digicube.entity.WhipArm whip, DigimonRenderState state, float partial) {
        state.whipWeight = whip.weight(partial);
        state.whipArm = whip.spec().arm(whip.side());
        state.whipSide = whip.side();
        int n = state.whipArm.parts().size();
        if (state.whipAngles.length != 2 * n) state.whipAngles = new float[2 * n];
        for (int i = 0; i < n; i++) {
            float[] a = whip.angles(i, partial);
            state.whipAngles[2 * i] = a[0];
            state.whipAngles[2 * i + 1] = a[1];
        }
    }

    @Override
    public void submit(DigimonRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        if(state.evolution!=null&&!state.isInvisible) {
            com.digicube.fabric.client.evolution.EvolutionPresentation.submit(state.evolution,poseStack,collector,state.lightCoords,cameraState);
            return;
        }
        this.model = this.models.getOrDefault(state.species, this.models.get(DigimonEntity.DEFAULT_SPECIES));
        super.submit(state, poseStack, collector, cameraState);
        SerpentShadow.submit(state, state.shadowLevel, poseStack, collector);
        if (!state.isInvisible && state.attackDefinition != null && state.attackDefinition.kind() == DigimonAttack.Kind.FIST
                && state.attackAnimation.isStarted()) {
            var fx=state.fistEffect;fx.tick=state.attackAnimation.getTimeInMillis(state.ageInTicks)/50F;
            fx.yaw=state.bodyRot;fx.scale=state.modelScale;fx.lightCoords=state.lightCoords;
            TectonicWaveRenderer.submitEffect(fistEffect,"rock_punch_fx",fx,poseStack,collector);
        }
        if (!state.isInvisible && state.attackEffectName != null) {
            state.attackEffect.yaw = state.bodyRot;
            TectonicWaveRenderer.submitEffect(attackEffects.get(state.attackEffectName), state.attackEffectName, state.attackEffect, poseStack, collector);
        }
        if (state.riderAim.heights != null) {
            // A ghost of the stone with the white outline a targeted enemy gets: what would rise, where, before it is cast.
            TectonicWaveRenderer.submitEffect(aimedWave,"tectonic_fist_fx",state.riderAim,poseStack,collector,0x38FFFFFF);
        }
        if (!state.isInvisible && state.attackDefinition!=null && state.attackAnimation.isStarted()) {
            var d=com.digicube.digimon.AuthoredAttacks.get(state.attackDefinition);
            if(d!=null && d.effect()!=null) TectonicWaveRenderer.submitEffect(authoredEffects.get(d.effect()),d.effect(),state.authoredEffect,poseStack,collector);
        }
        if (state.blueBlaster.length > 0.05F && state.attackDefinition != null) {
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50.0F;
            var frame = state.attackDefinition.motion().sample(tick);
            var mouth = frame.aimedMouth(state.attackAimPitch).yRot(-state.blueBlaster.yaw * Mth.DEG_TO_RAD).subtract(0, state.streamDrop, 0);
            poseStack.pushPose();
            poseStack.translate(mouth.x, mouth.y, mouth.z);
            BlueBlasterRenderer.submit(blueBlaster, state.blueBlaster, poseStack, collector);
            poseStack.popPose();
        }
        if (state.arc.count > 0) arcs.submit(state.arc, poseStack, collector);
        if (!state.isInvisible && state.breath.count > 0 && state.breathEffect != null && breaths.containsKey(state.breathEffect)) {
            breaths.get(state.breathEffect).submit(state.breath, poseStack, collector, state.ageInTicks);
        }
        if (!state.isInvisible && state.biteEffect != null && impacts.containsKey(state.biteEffect)) {
            TectonicWaveRenderer.submitEffect(impacts.get(state.biteEffect), state.biteEffect, state.bite, poseStack, collector);
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
        state.modelScale = models.containsKey(state.species) ? entity.getBody().modelScale() : fallbackScale;
        // A body of fire lights itself (ground_models.json "glow").
        if (models.get(state.species) instanceof com.digicube.fabric.client.model.NativeGroundModel glowing && glowing.definition().glow())
            state.lightCoords = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;
        state.cloth = cloth.computeIfAbsent(entity, e -> new com.digicube.fabric.client.model.ClothChains.State());
        state.ropes = ropes.computeIfAbsent(entity, e -> new com.digicube.fabric.client.model.RopeChains.State());
        state.tails = tails.computeIfAbsent(entity, e -> new com.digicube.fabric.client.model.TailChains.State());
        state.isBeingRidden = entity.isVehicle();
        state.runAnimationAmount = entity.getRunAnimationAmount(partialTick);
        state.swimAnimationAmount = entity.getSwimAnimationAmount(partialTick);
        state.swimAnimationPhase = entity.getSwimAnimationPhase(partialTick);
        whip(entity, state, partialTick);
        state.swimMotionAmount = entity.getSwimMotionAmount(partialTick);
        state.groundAnimationPhase = entity.getGroundAnimationPhase(partialTick);
        state.groundAnimationAmount = entity.getGroundAnimationAmount(partialTick);
        state.groundRunAmount = entity.getGroundRunAmount(partialTick);
        state.gaitShares = entity.getGaitShares(partialTick);
        state.pivotTurn = entity.getPivotTurn(partialTick);
        state.groundedMove = entity.groundedMove();
        state.skid = entity.getSkid(partialTick);
        state.throwCharge = entity.throwCharge();
        state.boneCarried = entity.boneCarried();
        state.riderAim.heights = com.digicube.fabric.client.party.RiderControls.aimedWave(entity);
        if (state.riderAim.heights != null && entity.getControllingPassenger() instanceof net.minecraft.world.entity.player.Player rider) {
            var aim = state.riderAim;
            // the view every frame, plus the correction for the fist the wave starts from (as the server will cast it)
            aim.tick = AIMED_WAVE_TICK;
            aim.yaw = rider.getViewYRot(partialTick) + Mth.wrapDegrees(com.digicube.fabric.client.party.RiderControls.aimedWaveYaw() - rider.getYRot());
            aim.hidden = java.util.Set.of("Ground cracks");
            aim.lightCoords = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT; aim.outlineColor = 0xFFFFFFFF;
        } else state.riderAim.heights = null;
        state.mountAnchor = entity.getMountAnchor(partialTick);
        state.flightPhase = entity.getFlightPhase();
        state.flightPhaseTime = entity.getFlightPhaseTime(partialTick);
        state.flightLoopTime = entity.getFlightLoopTime(partialTick);
        state.flightWalkAmount = entity.getFlightWalkAmount(partialTick);
        state.aerialBank=entity.getAerialBank(partialTick);
        state.aerialPitch=entity.getAerialPitch(partialTick);
        state.flightGroundDistance=entity.aerialMount()!=null && state.flightPhase==com.digicube.entity.ai.FlightPhase.APPROACH
                ? (float)entity.aerialRiding().groundDistance(3) : 3;
        state.flightLandingProgress=entity.landingProgress(partialTick);
        state.swimBank = entity.getSwimBank(partialTick);
        state.turnBank = entity.getTurnBank(partialTick);
        state.swimDash = entity.getSwimDash(partialTick);
        state.swimLeap = entity.getSwimLeap(partialTick);
        state.swimSurface = entity.getSwimSurface(partialTick);
        state.swimRoll = entity.getSwimRoll(partialTick);
        state.shadowRadius = entity.getBbWidth() * 0.5F;
        if (entity.isGuiPreview()) {
            // A screen preview: no nameplate, no shadow, and nothing of the level around it.
            state.nameTag = null;
            state.shadowRadius = 0;
        }
        state.attackAnimation.copyFrom(entity.attackAnimationState);
        state.attackAnimationName = entity.getAttackAnimationName();
        state.attackInWater = entity.isInWater();
        state.attackDefinition = entity.getAnimatingAttack();
        state.attackAimPitch = entity.getAttackAimPitch(partialTick);
        var authored=state.attackDefinition==null?null:com.digicube.digimon.AuthoredAttacks.get(state.attackDefinition);
        if(authored!=null && authored.effect()!=null) {
            var fx=state.authoredEffect;fx.tick=state.attackAnimation.getTimeInMillis(state.ageInTicks)/50F;
            fx.yaw=entity.getAttackYaw(partialTick);fx.scale=state.modelScale;
            fx.clip=state.attackInWater && authored.hasWaterVariant()?"effect_water"
                    :state.attackAnimationName!=null && state.attackAnimationName.endsWith("_mirrored") && authoredEffects.get(authored.effect()).has("effect_mirrored")?"effect_mirrored":authored.effectClip();
            // A summoned strike is drawn where it lands, not where its caster stands.
            var anchor=authored.anchored()?entity.strikeAnchor():null;
            var feet=anchor!=null?anchor:entity.position();
            fx.offset=anchor!=null?anchor.subtract(entity.getPosition(partialTick)):net.minecraft.world.phys.Vec3.ZERO;
            var frame=authored.motion(state.attackInWater).sample(fx.tick);
            fx.aimPivot=frame.head();fx.aimPitch=state.attackAimPitch*frame.aimWeight();
            fx.lightCoords=authored.emissive()?net.minecraft.util.LightCoordsUtil.FULL_BRIGHT:state.lightCoords;
            var hidden=new java.util.HashSet<String>();var boxes=authored.sample(fx.tick,state.attackInWater);
            if(!entity.attackConnected())hidden.addAll(authored.contactParts());
            for(int i=0;i<boxes.length;i++) if(boxes[i]!=null) {
                var world=com.digicube.entity.AuthoredVolumeAttack.aimed(boxes[i],state.attackDefinition,fx.tick,state.attackAimPitch)
                        .world(feet,fx.yaw,0);
                if(!com.digicube.entity.AuthoredVolumeAttack.visible(entity.level(),entity,state.attackDefinition,fx.tick,feet,fx.yaw,world)
                        || authored.grounded() && !com.digicube.entity.AuthoredVolumeAttack.supported(entity.level(),entity,world)) hidden.addAll(authored.visualParts().get(i));
            }
            fx.hidden=java.util.Set.copyOf(hidden);
        }
        state.attackEffectName = null;
        if (models.get(state.species) instanceof com.digicube.fabric.client.model.NativeGroundModel ground && ground.definition().attackEffects() != null
                && state.attackAnimation.isStarted() && state.attackAnimationName != null) {
            var effects = ground.definition().attackEffects();
            String clip = effects.clips().get(state.attackAnimationName);
            if (clip != null) {
                var fx = state.attackEffect;
                fx.clip = clip; fx.tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F;
                fx.scale = state.modelScale; fx.offset = net.minecraft.world.phys.Vec3.ZERO;
                // Fire and claw light are their own light. The facing is the body's, taken when drawn.
                fx.lightCoords = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT; fx.outlineColor = 0;
                state.attackEffectName = effects.effect();
            }
        }
        if (entity.isFlyingMovement() || (entity.canSwim() && state.swimAnimationAmount > 0.01F) || state.isBeingRidden
                || state.attackAnimation.isStarted() && state.attackDefinition != null && state.attackDefinition.locksBodyFacing()) {
            // Swimming, riding and committed attacks turn the entire creature.
            // Keep its rendered body aligned with the server's steering direction.
            state.bodyRot = state.attackAnimation.isStarted() && state.attackDefinition != null
                    && (state.attackDefinition.kind()==DigimonAttack.Kind.GROUND_WAVE || state.attackDefinition.kind()==DigimonAttack.Kind.FIST
                    || com.digicube.digimon.AuthoredAttacks.handles(state.attackDefinition)
                    || state.attackDefinition.kind()==DigimonAttack.Kind.FIREBALL && entity.attackYawFresh())
                    ? entity.getAttackYaw(partialTick) : Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
            state.yRot = 0.0F;
        }
        state.blueBlaster.length = 0;
        if (state.attackDefinition != null && state.attackDefinition.kind() == DigimonAttack.Kind.RETREAT_KICK && state.attackAnimation.isStarted()) {
            state.bodyRot = entity.getKineticRenderYaw(partialTick);
        }
        state.kineticOffset = entity.getKineticRenderOffset(partialTick).yRot(state.bodyRot * Mth.DEG_TO_RAD);
        // An attack on the run: the upper body plays it and twists toward where the rider looks, or the AI's aim (and so
        // does the AI's breath from a body that steps round, its legs turning it after the aim).
        var rider = entity.rider();
        state.attackUpperBody = state.attackAnimation.isStarted()
                && (entity.movesDuring(state.attackDefinition) || entity.breathesOnItsLegs(state.attackDefinition));
        float aim = !state.attackUpperBody ? 0 : rider != null ? Mth.rotLerp(partialTick, rider.yRotO, rider.getYRot()) : entity.getAttackYaw(partialTick);
        // A breath turns only the neck toward the aim (its own twist), a drawn shot the whole upper body.
        var breathing = com.digicube.digimon.BreathAttacks.get(state.attackDefinition);
        float twist = breathing != null ? breathing.twist() : state.attackDefinition != null && state.attackDefinition.fuel() != null
                ? DigimonEntity.STREAM_TWIST : com.digicube.entity.KineticSession.MAX_TWIST;
        state.attackTwist = state.attackUpperBody ? Math.clamp(Mth.wrapDegrees(aim - state.bodyRot), -twist, twist) : 0;
        state.riderCharge = entity.riderCharging() ? entity.riderChargeTicks() + partialTick : -1;
        int rush = entity.rushCode();
        state.rushBlow = com.digicube.entity.BullRush.blow(rush);
        state.rushStanding = com.digicube.entity.BullRush.standing(rush);
        state.rushTicks = rush == 0 ? -1 : com.digicube.entity.BullRush.ticks(rush) + (state.rushBlow ? 0 : partialTick);
        state.rushBuild = entity.rushBuildTicks();
        state.pouncePitch = entity.getPouncePitch(partialTick);
        breathAndBite(entity, state, partialTick);
        ArcRenderer.extract(entity, state.arc, state.x, state.y, state.z, partialTick);
        state.leapTick = entity.getLeapTick(partialTick);
        state.leapWeight = state.leapTick < 0 ? 0 : entity.getLeapWeight(partialTick);
        if (entity.isAlive() && state.attackAnimation.isStarted() && state.attackDefinition != null
                && state.attackDefinition.fuel() != null && !com.digicube.digimon.BreathAttacks.handles(state.attackDefinition)) {
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50.0F;
            var motion = state.attackDefinition.motion();
            if (tick >= motion.activeFrom() && tick < motion.activeUntil() + 1) {
                var frame = motion.sample(tick);
                var flame = state.blueBlaster;
                flame.ageInTicks = tick - motion.activeFrom();
                // a stream breathed on the move leaves the turned head toward the aim; a serpent's swimming head is lower
                flame.yaw = entity.riderMovesDuring(state.attackDefinition) ? entity.getAttackYaw(partialTick) : state.bodyRot;
                var serpent = entity.serpent();
                state.streamDrop = serpent != null && entity.isSwimmingMovement() ? serpent.swimHeadDrop() : 0;
                flame.pitch = -(frame.headPitch() + state.attackAimPitch * frame.aimWeight());
                flame.length = (float) entity.flameStream(state.attackDefinition, tick,
                        state.attackAimPitch, flame.yaw).length();
                flame.outlineColor = state.outlineColor;
            }
        }
        serpent(entity, state);
        state.evolution = evolution.extract(entity,state,partialTick);
    }

    /**
     * A serpent's body (a model with a spine, a sheet with a serpent): its drawn feet lay its trail, and it gets its sway (on
     * land, swimming, dashing, at rest in the water) and how much of it lies along the trail; a wrap winds it off the trail
     * and round its prey ({@link #wrap}).
     */
    private void serpent(DigimonEntity entity, DigimonRenderState state) {
        state.shadowLevel = entity.isGuiPreview() ? null : entity.level();
        var spine = models.get(state.species) instanceof com.digicube.fabric.client.model.NativeGroundModel ground ? ground.definition().spine() : null;
        if (spine == null || entity.getBody().serpent() == null) { state.serpent = null; state.spineWeight = 0; return; }
        float water = Mth.clamp(state.swimAnimationAmount, 0, 1), motion = Mth.clamp(state.swimMotionAmount, 0, 1);
        com.digicube.fabric.client.model.SerpentSpine.State data;
        if (entity.isGuiPreview()) {
            // A screen preview has no path behind it: its body lies straight back, in the sway it has at rest.
            data = new com.digicube.fabric.client.model.SerpentSpine.State(entity.getBody().length());
            data.follow(new net.minecraft.world.phys.Vec3(state.x, state.y, state.z), state.bodyRot, 0, 0, state.ageInTicks, spine, null);
        } else {
            data = serpents.computeIfAbsent(entity, e -> new com.digicube.fabric.client.model.SerpentSpine.State(e.getBody().length()));
            data.follow(new net.minecraft.world.phys.Vec3(state.x, state.y, state.z), state.bodyRot, water, motion, state.ageInTicks, spine, entity.level());
        }
        state.serpent = data;
        float swim = Mth.lerp(motion, spine.restWave(), spine.swimWave()) + Mth.clamp(state.swimDash, 0, 1) * (spine.dashWave() - spine.swimWave());
        state.spineWave = Mth.lerp(water, spine.landWave(), swim);
        state.spineWeight = 1;
        wrap(entity, state);
    }

    /**
     * A wrap under way ({@code ConstrictionCoil}): the coil the spine lays the body along (the prey's feet, the coil's shape
     * for the prey's size, the way it winds) and the ticks since the capture on the attack's own clock (negative while it
     * strikes: no coil yet); inactive for anything else.
     */
    private static void wrap(DigimonEntity entity, DigimonRenderState state) {
        var wrap = state.wrap;
        var serpent = entity.getBody().serpent();
        wrap.active = state.attackDefinition != null && state.attackAnimation.isStarted()
                && state.attackDefinition.kind() == DigimonAttack.Kind.CONSTRICTION && serpent != null && serpent.coil() != null;
        if (!wrap.active) return;
        int capture = entity.wrapCaptureTick();
        float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F;
        var size = entity.wrapSize();
        boolean taken = capture != com.digicube.digimon.ConstrictionCoil.NOT_TAKEN;
        wrap.since = taken ? tick - capture : -1;
        wrap.shape = taken ? com.digicube.digimon.ConstrictionCoil.fit(size.x(), size.y(), entity.getBody()) : null;
        var center = entity.wrapCenter();
        wrap.x = center.x; wrap.y = center.y; wrap.z = center.z;
        wrap.winding = size.z() < 0 ? -1 : 1;
        wrap.girth = serpent.coil().girth();
    }

    /** A serpent casts its shadow along its body instead (SerpentShadow). */
    @Override
    protected float getShadowRadius(DigimonRenderState state) {
        return state.serpent != null ? 0 : super.getShadowRadius(state);
    }

    /**
     * The breath's puffs as this client flies them, relative to the body; and a pounce's bite: the moment the jaws shut
     * on something, its impact bursts where the jaws are (the clip's own jaws at its snap, turned with the body and its
     * pounce), and plays out there while the body moves on.
     */
    private void breathAndBite(DigimonEntity entity, DigimonRenderState state, float partialTick) {
        var breath = entity.clientBreath();
        state.breathEffect = breath == null ? null : breath.spec().effect();
        FrostBreathRenderer.extract(breath, state.breath, state.x, state.y, state.z, partialTick);
        state.biteEffect = null;
        int since = entity.ticksSincePounceBite();
        var pounce = com.digicube.digimon.PounceAttacks.get(entity.getAnimatingAttack());
        Bite bite = bites.get(entity);
        if (pounce != null && !pounce.impact().isEmpty() && since <= 1 && (bite == null || bite.tick() != entity.tickCount - since)) {
            var frame = pounce.attack().motion().sample(pounce.snap());
            double pitch = Math.toRadians(entity.getPouncePitch(partialTick)), pivot = entity.getBbHeight() * .5;
            double y = frame.mouth().y - pivot, z = frame.mouth().z;
            var local = new net.minecraft.world.phys.Vec3(frame.mouth().x, y * Math.cos(pitch) + z * Math.sin(pitch) + pivot, -y * Math.sin(pitch) + z * Math.cos(pitch));
            bite = new Bite(entity.tickCount - since, entity.getPosition(partialTick).add(local.yRot(-state.bodyRot * Mth.DEG_TO_RAD)),
                    state.bodyRot, pounce.impact());
            bites.put(entity, bite);
        }
        if (bite == null) return;
        float age = entity.tickCount - bite.tick() + partialTick;
        if (age > 11) return;
        state.biteEffect = bite.effect();
        var fx = state.bite;
        fx.clip = "effect";
        fx.tick = age;
        fx.yaw = bite.yaw();
        fx.scale = state.modelScale;
        fx.offset = bite.at().subtract(state.x, state.y, state.z);
        fx.lightCoords = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;
        fx.outlineColor = 0;
    }

    @Override
    protected AABB getBoundingBoxForCulling(DigimonEntity entity) {
        AABB bounds = super.getBoundingBoxForCulling(entity);
        bounds=bounds.inflate(evolution.radius(entity));
        bounds=bounds.expandTowards(0,evolution.beamHeight(entity),0);
        if (models.get(entity.getSpeciesId()) instanceof com.digicube.fabric.client.model.NativeGroundModel nativeModel) {
            bounds = bounds.inflate(nativeModel.definition().cullingMargin() * entity.getBody().modelScale());
        }
        if (entity.canSwim()) bounds = bounds.inflate(entity.getBody().modelScale());
        if (entity.canFly()) bounds = bounds.inflate(2 * entity.getBody().modelScale());
        if (models.get(entity.getSpeciesId()) instanceof AnimatedRiderModel nativeModel
                && !(nativeModel instanceof com.digicube.fabric.client.model.NativeGroundModel)) {
            bounds = bounds.inflate(nativeModel.cullingMargin() * entity.getBody().modelScale());
        }
        // a discharge's bolts reach far from the caster: keep it drawn while they live
        if (entity.clientArc(ARC_LIFE) != null) bounds = bounds.inflate(ARC_REACH);
        var attack = entity.getAnimatingAttack();
        return attack != null && attack.fuel() != null
                ? bounds.inflate(attack.range()) : bounds;
    }

    @Override
    public Identifier getTextureLocation(DigimonRenderState state) {
        if (models.get(state.species) instanceof com.digicube.fabric.client.model.NativeGroundModel nativeModel) {
            return nativeModel.definition().texture(state.attackAnimation.isStarted() ? state.attackAnimationName : null,
                    state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F);
        }
        if (models.get(state.species) instanceof com.digicube.fabric.client.model.NativeFlyingMountModel) {
            return state.species.withPath("textures/entity/digimon/"+state.species.getPath()+".png");
        }
        if (!models.containsKey(state.species) && models.get(DigimonEntity.DEFAULT_SPECIES) instanceof com.digicube.fabric.client.model.NativeGroundModel fallback) {
            return fallback.definition().texture();
        }
        return TEXTURES.get(state.species);
    }

    @Override
    protected void scale(DigimonRenderState state, PoseStack poseStack) {
        poseStack.scale(state.modelScale, state.modelScale, state.modelScale);
    }

    /**
     * Rider presentation for the current frame.
     * @param offset animated local seat displacement
     * @param pose seated leg angles
     * @param yaw how far the animated seat has turned from the mount's heading, degrees
     */
    public record RiderVisual(net.minecraft.world.phys.Vec3 offset, AnimatedRiderModel.RiderPose pose, float yaw, float[] lean) {}

    /**
     * Evaluate the actual mount model at the same clock as its rendered body.
     * @param entity ridden Digimon
     * @param partialTick render interpolation
     * @return its visual attachment, or null for mounts with an already fixed seat
     */
    public RiderVisual riderVisual(DigimonEntity entity, float partialTick) {
        if(models.get(entity.getSpeciesId()) instanceof com.digicube.fabric.client.model.NativeGroundModel ground && ground.definition().rider()==null)return null;
        if (!(models.get(entity.getSpeciesId()) instanceof AnimatedRiderModel mount)) return null;
        var state = createRenderState();
        extractRenderState(entity, state, partialTick);
        var offset = mount.riderOffset(state);
        return new RiderVisual(offset, mount.riderPose(), mount.riderYaw(state), mount.riderLean(state));
    }
}
