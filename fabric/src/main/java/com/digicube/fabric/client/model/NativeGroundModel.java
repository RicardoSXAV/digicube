package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native idle and solved ground-walk assets, registered through the client catalog. */
public final class NativeGroundModel extends EntityModel<DigimonRenderState> implements AnimatedRiderModel {
    /**
     * Where the rider is attached, and how its legs are posed there ({@code pose} in the catalog; straight legs without one).
     * {@code hide} names parts the rider takes the place of (a feather standing where the seat is), drawn only unridden.
     */
    public record Rider(java.util.List<String> path, net.minecraft.world.phys.Vec3 point, RiderPose pose, java.util.List<String> hide) {
        public Rider(java.util.List<String> path, net.minecraft.world.phys.Vec3 point, RiderPose pose) { this(path, point, pose, java.util.List.of()); }
    }
    /**
     * A heavy walker's footfalls ({@code stomps} in the catalog): where each foot lands in the gait's phase, 0 to 1, and
     * where it stands then, x and forward in blocks at yaw zero (dust is raised there). Played by the client's Stomps.
     */
    public record Stomps(float[] down, float[][] feet) {}
    /** Flames a rider's jet charge shows: the parts named {@code prefix}, posed as {@code clip} has them at {@code tick}. */
    public record Flames(String clip, float tick, String prefix) {}
    /**
     * Where each hoof lands ({@code down}) and lifts ({@code up}) in the gait's phase, 0 to 1, one row per column of the
     * gallop lattice (walk to full gallop), measured from the clips; the order of {@code hooves} is the order of a row.
     */
    public record HoofTimes(java.util.List<String> hooves, float[][] down, float[][] up) {
        /** The row of the lattice column nearest to a run share. */
        public int column(float run) { return Math.round(Math.clamp(run, 0, 1) * (down.length - 1)); }
        public boolean hind(int hoof) { return hooves.get(hoof).startsWith("rear"); }
    }
    /**
     * Ordinary head look ({@code look} in the catalog): the part turned toward where the entity looks, limited in degrees.
     * A head fused to its trunk (Mojyamon's face sits on its chest) cannot turn far on its own without burying the face
     * in the fur: {@code carry} hands a share of the look to parts further down the chain (the waist twists the whole
     * upper body), each within its own limits, and the head takes what is left.
     */
    public record Look(java.util.List<String> path, float yaw, float pitch, java.util.List<Carry> carry) {
        public Look(java.util.List<String> path, float yaw, float pitch) { this(path, yaw, pitch, java.util.List.of()); }
        /** The share of the head's own turn: what the carrying parts leave. */
        public float headShare() { float s = 1; for (var c : carry) s -= c.share(); return s; }
    }
    /** A part that takes {@code share} of the look, limited to {@code yaw} and {@code pitch} degrees. */
    public record Carry(java.util.List<String> path, float share, float yaw, float pitch) {}
    /**
     * Caster-anchored clips of one effect model ({@code attack_effects}), drawn in the caster's frame while the attack
     * animation of the same name plays and on its clock: a charge in the mouth, a streak behind a claw.
     */
    public record AttackEffects(String effect, Map<String, String> clips) {}
    public record Definition(Identifier species, double cullingMargin, boolean amphibious, boolean walkBlend, java.util.List<String> aimPath,
                             float attackBlendIn, float attackBlendOut, boolean gallop, boolean supportFloor, Rider rider,
                             java.util.List<String> pitchPath, float riddenPitch, java.util.List<TextureWindow> expressions,
                             java.util.List<ClothChains.Chain> cloth, java.util.List<String> upperBody, Flames flames,
                             HoofTimes hoofBeats, Look look, AttackEffects attackEffects, java.util.List<RopeChains.Rope> ropes,
                             String carried, float swimPitch, boolean pitchAtRider, Stomps stomps, boolean bank) {
        public ModelLayerLocation layer() { return new ModelLayerLocation(species, "main"); }
        public Identifier geometry() { return species.withPath("models/entity/" + species.getPath() + ".mesh.json"); }
        public Identifier animation() { return species.withPath("models/entity/" + species.getPath() + ".animation.json"); }
        public Identifier texture() { return species.withPath("textures/entity/digimon/" + species.getPath() + ".png"); }
        public Identifier texture(String clip, float tick) {
            for (var window : expressions) if (window.clip().equals(clip) && tick >= window.from() && tick < window.until())
                return window.texture();
            return texture();
        }
        public LayerDefinition createLayer() { return NativeModelGeometry.createLayer(geometry()); }
    }

    public record TextureWindow(String clip, float from, float until, Identifier texture) {}

    private static final Map<Identifier, Definition> DEFINITIONS = readDefinitions();
    private final NativeAnimationSet animations;
    private final Definition definition;
    private final ModelPart aimPart;
    private final ModelPart rootPart;
    /** The upper body (the part named by {@code upper_body} and all it carries), or null; and its base. */
    private final java.util.Set<ModelPart> upperParts;
    private final ModelPart upperBase;
    private final java.util.Set<ModelPart> flameParts;
    private final ModelPart lookPart;
    private final ModelPart[] carryParts;
    /** Everything the upper body does not carry (legs and hips), for a thrower's layered performances; and the carried weapon. */
    private final java.util.Set<ModelPart> lowerParts;
    private final ModelPart carriedPart;
    private final ModelPart[] riderHidden;

    public NativeGroundModel(ModelPart root, Definition definition) {
        super(NativeModelGeometry.apply(root, definition.geometry()));
        this.definition = definition;
        this.rootPart=root;
        animations = new NativeAnimationSet(root, definition.animation());
        ModelPart aim=root;
        for(String name:definition.aimPath()) aim=aim.getChild(name);
        aimPart=definition.aimPath().isEmpty()?null:aim;
        ModelPart upper=null;
        if(definition.upperBody()!=null){upper=root;for(String name:definition.upperBody())upper=upper.getChild(name);}
        upperBase=upper;
        if(upper==null)upperParts=null;
        else{upperParts=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());upperParts.addAll(upper.getAllParts());}
        flameParts=definition.flames()==null?null:animations.partsNamed(definition.flames().prefix());
        ModelPart look=null;
        if(definition.look()!=null){look=root;for(String name:definition.look().path())look=look.getChild(name);}
        lookPart=look;
        carryParts=new ModelPart[definition.look()==null?0:definition.look().carry().size()];
        for(int i=0;i<carryParts.length;i++){ModelPart c=root;for(String name:definition.look().carry().get(i).path())c=c.getChild(name);carryParts[i]=c;}
        if(upperParts==null)lowerParts=null;
        else{lowerParts=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());lowerParts.addAll(root.getAllParts());lowerParts.removeAll(upperParts);}
        carriedPart=definition.carried()==null?null:animations.part(definition.carried());
        riderHidden=definition.rider()==null?new ModelPart[0]:definition.rider().hide().stream().map(animations::part).toArray(ModelPart[]::new);
    }

    public static Map<Identifier, Definition> definitions() { return DEFINITIONS; }
    public Definition definition() { return definition; }

    @Override
    public void setupAnim(DigimonRenderState state) {
        super.setupAnim(state);
        pose(state);
        whip(state);
        ClothChains.apply(rootPart, state, definition.cloth(), state.cloth);
        RopeChains.apply(rootPart, state, definition.ropes(), state.ropes);
    }

    /** The authored pose for this frame: idle, gait, swim and the attack in progress. Cloth hangs from it afterwards. */
    private void pose(DigimonRenderState state) {
        animations.hideMembranes();
        if (carriedPart != null) carriedPart.visible = state.boneCarried;
        for (ModelPart part : riderHidden) part.visible = !state.isBeingRidden;
        if (state.attackAnimationName != null && state.attackAnimation.isStarted() && upperParts != null
                && com.digicube.digimon.ThrownAttacks.handles(state.attackDefinition)) {
            thrownPerformance(state);
            return;
        }
        // Also under a rider: mounted combat casts from the saddle, and a mount that does not fight never starts one.
        if (state.attackAnimationName != null && state.attackAnimation.isStarted()) {
            String attackClip=state.attackInWater && animations.has(state.attackAnimationName+"_water")
                    ? state.attackAnimationName+"_water" : state.attackAnimationName;
            float tick=state.attackAnimation.getTimeInMillis(state.ageInTicks)/50F;
            if(state.attackDefinition!=null && state.attackDefinition.kind()==com.digicube.digimon.DigimonAttack.Kind.CONSTRICTION) {
                for(var b:com.digicube.digimon.DigimonSpeciesBootstrap.CONSTRICTION_MOTION.blends(state.constrictionFit)) animations.apply(b.clip(),tick,b.weight());
                var offset=state.constrictionOffset.scale(16/state.modelScale);
                rootPart.x+=(float)offset.x;rootPart.y-=(float)offset.y;rootPart.z-=(float)offset.z;
            } else {
                float weight=1;
                if(definition.attackBlendIn()>0)weight=Math.min(weight,tick/definition.attackBlendIn());
                if(definition.attackBlendOut()>0)weight=Math.min(weight,(animations.length(attackClip)-tick)/definition.attackBlendOut());
                weight=Math.clamp(weight,0,1);weight=weight*weight*(3-2*weight);
                if(state.attackUpperBody && upperParts!=null) {
                    // A rider's attack on the run: the legs keep the gait, the upper body plays the attack and turns to the aim.
                    float blend=Math.clamp(Math.min(tick/UPPER_BLEND,(animations.length(attackClip)-tick)/UPPER_BLEND),0,1);
                    blend=blend*blend*(3-2*blend);
                    applyGround(state,1);
                    fromRest(upperParts,1-blend);
                    animations.apply(attackClip,tick,blend,upperParts);
                    var kinetic = com.digicube.digimon.KineticAttacks.get(state.attackDefinition);
                    if (kinetic != null) NativeArmAim.apply(rootPart, kinetic, tick, state.attackAimPitch*blend);
                    upperBase.yRot+=state.attackTwist*blend*((float)Math.PI/180);
                    bank(state);
                    supportFloor(state);
                    return;
                }
                applyGround(state,1-weight);
                if(definition.supportFloor()) {
                    // Blend complete poses with shortest-arc quaternions. Segmented
                    // tentacles can cross Euler's wrap boundary during a pad strike.
                    rootPart.getAllParts().forEach(ModelPart::resetPose);
                    applyGround(state,1);
                    var base=rootPart.getAllParts().stream().map(p->new float[]{p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale}).toList();
                    rootPart.getAllParts().forEach(ModelPart::resetPose);
                    animations.apply(attackClip,tick,1);
                    var all=rootPart.getAllParts();
                    for(int i=0;i<all.size();i++) {
                        var p=all.get(i);var a=base.get(i);
                        var q=new org.joml.Quaternionf().rotationZYX(a[5],a[4],a[3])
                                .slerp(new org.joml.Quaternionf().rotationZYX(p.zRot,p.yRot,p.xRot),weight);
                        var e=q.getEulerAnglesZYX(new org.joml.Vector3f());
                        p.x=a[0]+(p.x-a[0])*weight;p.y=a[1]+(p.y-a[1])*weight;p.z=a[2]+(p.z-a[2])*weight;
                        p.xRot=e.x;p.yRot=e.y;p.zRot=e.z;
                        p.xScale=a[6]+(p.xScale-a[6])*weight;p.yScale=a[7]+(p.yScale-a[7])*weight;p.zScale=a[8]+(p.zScale-a[8])*weight;
                    }
                } else animations.apply(attackClip,tick,weight);
                var rootOffset = state.kineticOffset.scale(16 / state.modelScale);
                rootPart.x += (float) rootOffset.x; rootPart.y -= (float) rootOffset.y; rootPart.z -= (float) rootOffset.z;
                var kinetic = com.digicube.digimon.KineticAttacks.get(state.attackDefinition);
                if (kinetic != null) NativeArmAim.apply(rootPart, kinetic, tick, state.attackAimPitch);
                else if(aimPart!=null && state.attackDefinition!=null && state.attackDefinition.motion()!=null) {
                    aimPart.xRot+=state.attackAimPitch*state.attackDefinition.motion().sample(tick).aimWeight()*weight*((float)Math.PI/180);
                }
                look(state,1-weight);
                divePitch(state,1-weight);
            }
            supportFloor(state);
            return;
        }
        applyGround(state,1);
        look(state,1);
        if (state.riderCharge >= 0 && flameParts != null) {
            // The jets burn through a rider's charge, flickering on the flame clip's own pose.
            var f=definition.flames();
            animations.apply(f.clip(), f.tick()+(float)Math.sin(state.riderCharge*2.3F)*.6F, 1, flameParts);
            for(var part:flameParts)part.visible=true;
        }
        bank(state);
        divePitch(state,1);
        supportFloor(state);
    }

    /**
     * A swimmer's dive and climb, {@code keep} of it (an attack takes the body level as it blends in, as its hits are):
     * the pitch part (the rider's by default, the neck base of a serpent) turns by the body's pitch within its limit
     * ({@code ridden_pitch} under a rider, {@code swim_pitch} without; 0 keeps the body upright) and banks into the turn.
     * With {@code pitch_at_rider} it turns about the rider's seat instead of its own pivot: the rider stays where the
     * saddle is (and where the first-person camera is) and the body swings under them.
     */
    private void divePitch(DigimonRenderState state,float keep) {
        // A rider's whip levels the body out as well, so the arm can reach down ahead (WhipArm.root turns the same way).
        keep*=1-Math.clamp(state.whipWeight,0,1);
        if(definition.pitchPath()==null || keep<=0)return;
        float limit=state.isBeingRidden?definition.riddenPitch():definition.swimPitch();
        if(limit<=0)return;
        ModelPart back=rootPart;
        for(String name:definition.pitchPath())back=back.getChild(name);
        float water=Math.clamp(state.swimAnimationAmount,0,1)*keep;
        float pitch=Math.clamp(state.xRot,-limit,limit)*((float)Math.PI/180)*water;
        float roll=state.swimBank*(state.isBeingRidden?.25F:1)*((float)Math.PI/180)*water;
        if(!definition.pitchAtRider() || definition.rider()==null) {
            back.xRot+=pitch;
            back.zRot+=roll;
            return;
        }
        // The seat in the pitch part's parent frame, in pixels: the rider's path runs on from the pitch part.
        var stack=new com.mojang.blaze3d.vertex.PoseStack();
        ModelPart part=back;part.translateAndRotate(stack);
        var path=definition.rider().path();
        for(int i=definition.pitchPath().size();i<path.size();i++){part=part.getChild(path.get(i));part.translateAndRotate(stack);}
        var v=definition.rider().point();
        var seat=stack.last().pose().transformPosition((float)v.x,(float)-v.z,(float)v.y,new org.joml.Vector3f()).mul(16);
        var turn=new org.joml.Matrix3f().rotationZ(roll).rotateX(pitch);
        var from=new org.joml.Vector3f(back.x,back.y,back.z).sub(seat);
        turn.transform(from).add(seat);
        back.setPos(from.x,from.y,from.z);
        var angles=new org.joml.Matrix3f(turn).mul(new org.joml.Matrix3f().rotationZYX(back.zRot,back.yRot,back.xRot)).getEulerAnglesZYX(new org.joml.Vector3f());
        back.setRotation(angles.x,angles.y,angles.z);
    }

    /**
     * A thrower's performance (throw, catch, pickup, the icicle's form, hold and release): the upper body plays it over
     * the gait, the hips and legs take its own footwork only as far as the body stands still, so a throw on the walk
     * keeps its steps and one from a standstill shifts its weight. A clip name that is a blend (the icicle's hold and
     * release, the bone's held wind-up and its release) mixes its light and heavy performances by the synced charge.
     * Clips that continue a performance (a hold, the release after it) do not blend in again, nor do the form and the
     * hold blend out. In the air the legs keep the leap.
     */
    private void thrownPerformance(DigimonRenderState state) {
        String clip = state.attackAnimationName;
        float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F;
        boolean continues = com.digicube.digimon.ThrownAttacks.continues(clip);
        boolean goesOn = com.digicube.digimon.ThrownAttacks.goesOn(clip);
        float weight = 1, length = com.digicube.digimon.ThrownAttacks.length(clip);
        if (definition.attackBlendIn() > 0 && !continues) weight = Math.min(weight, tick / definition.attackBlendIn());
        if (definition.attackBlendOut() > 0 && !goesOn) weight = Math.min(weight, (length - tick) / definition.attackBlendOut());
        weight = Math.clamp(weight, 0, 1); weight = weight * weight * (3 - 2 * weight);
        float amount = Math.clamp(state.groundAnimationAmount, 0, 1);
        applyGround(state, 1);
        // In the air the legs keep the jump (a throw from a leap): the performance has the upper body only.
        float lower = weight * (1 - amount) * (1 - leap(state));
        fromRest(upperParts, 1 - weight);
        fromRest(lowerParts, 1 - lower);
        if (animations.blendOnly(clip)) {
            animations.blend(clip, state.throwCharge, tick, weight, upperParts);
            animations.blend(clip, state.throwCharge, tick, lower, lowerParts);
        } else {
            animations.apply(clip, tick, weight, upperParts);
            animations.apply(clip, tick, lower, lowerParts);
        }
        look(state, 1 - weight);
        supportFloor(state);
    }

    /**
     * Turns the look part toward where the entity looks (vanilla's head yaw and pitch), within the catalog's limits; the
     * parts that carry it take their shares first.
     */
    private void look(DigimonRenderState state, float amount) {
        if(lookPart==null||amount<=0)return;
        // A head with the rider sitting on it carries them and holds still (the clips still move it).
        if(state.isBeingRidden&&ridesLook())return;
        var l=definition.look();
        float toRad=(float)Math.PI/180;
        for(int i=0;i<carryParts.length;i++) {
            var c=l.carry().get(i);
            carryParts[i].yRot+=Math.clamp(state.yRot*c.share(),-c.yaw(),c.yaw())*amount*toRad;
            carryParts[i].xRot+=Math.clamp(state.xRot*c.share(),-c.pitch(),c.pitch())*amount*toRad;
        }
        float share=l.headShare();
        lookPart.yRot+=Math.clamp(state.yRot*share,-l.yaw(),l.yaw())*amount*toRad;
        lookPart.xRot+=Math.clamp(state.xRot*share,-l.pitch(),l.pitch())*amount*toRad;
    }

    /** The rider is attached to the look part, a part that carries its look, or something they carry. */
    private boolean ridesLook() {
        var rider=definition.rider();var look=definition.look();
        if(rider==null||look==null)return false;
        if(starts(rider.path(),look.path()))return true;
        for(var c:look.carry())if(starts(rider.path(),c.path()))return true;
        return false;
    }
    private static boolean starts(java.util.List<String> path,java.util.List<String> prefix) {
        return path.size()>=prefix.size()&&path.subList(0,prefix.size()).equals(prefix);
    }

    /** Ticks at either end of a rider's moving attack over which the upper body blends from and back to the gait. */
    private static final float UPPER_BLEND = 4;

    /** Pulls {@code parts} back toward their rest pose, keeping {@code keep} of what has been applied to them. */
    private static void fromRest(java.util.Set<ModelPart> parts, float keep) {
        for (ModelPart p : parts) {
            var rest = p.getInitialPose();
            p.x = rest.x() + (p.x - rest.x()) * keep; p.y = rest.y() + (p.y - rest.y()) * keep; p.z = rest.z() + (p.z - rest.z()) * keep;
            p.xRot = rest.xRot() + (p.xRot - rest.xRot()) * keep; p.yRot = rest.yRot() + (p.yRot - rest.yRot()) * keep;
            p.zRot = rest.zRot() + (p.zRot - rest.zRot()) * keep;
        }
    }

    /**
     * A whip (WhipArm, a rider's or the AI's): each section of the whipping arm is turned onto the direction its yaw and
     * pitch give it, over whatever the clips made of the arm, by the whip's weight. A section keeps the roll it has at
     * rest relative to its direction's own frame ({@link #whipFrame}: across is the way a turn in yaw moves it), so the arm
     * never twists or spins however it swings; the pad lies along the last direction with its palm to the way the lash
     * sweeps. The angles are the body's (yaw only), so a diving body keeps its whip where the aim is.
     */
    private void whip(DigimonRenderState state) {
        var arm = state.whipArm;
        int n = arm == null ? 0 : arm.parts().size();
        if (state.whipWeight <= 0 || arm == null || state.whipAngles.length < 2 * n) return;
        var rolls = whipRolls.computeIfAbsent(arm, this::restRolls);
        if (rolls.length < n) return;
        ModelPart part = rootPart;
        var parent = rotation(part);
        for (String name : arm.parentPath()) {
            if (!part.hasChild(name)) return;
            part = part.getChild(name);
            parent.mul(rotation(part));
        }
        float weight = Math.clamp(state.whipWeight, 0, 1);
        for (int i = 0; i < n; i++) {
            if (!part.hasChild(arm.parts().get(i))) return;
            part = part.getChild(arm.parts().get(i));
            var frame = whipFrame(state.whipAngles[2 * i], state.whipAngles[2 * i + 1]);
            org.joml.Matrix3f world;
            if (i < n - 1) world = frame.mul(rolls[i]);
            else {
                // The pad along its arm (claws down its local -y), the palm (local -z) facing the way the lash sweeps.
                var along = frame.getColumn(2, new org.joml.Vector3f());
                var sweep = frame.getColumn(0, new org.joml.Vector3f()).mul(state.whipSide);
                var y = along.negate();
                var z = sweep.negate();
                world = new org.joml.Matrix3f(new org.joml.Vector3f(y).cross(z), y, z);
            }
            var local = new org.joml.Matrix3f(parent).transpose().mul(world);
            var turned = new org.joml.Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot)
                    .slerp(new org.joml.Quaternionf().setFromNormalized(local), weight);
            var angles = turned.getEulerAnglesZYX(new org.joml.Vector3f());
            part.setRotation(angles.x, angles.y, angles.z);
            parent = new org.joml.Matrix3f(parent).mul(rotation(part));
        }
    }

    /** Each whip arm's sections' rest rolls relative to their directions' frames, measured once. */
    private final java.util.Map<com.digicube.digimon.WhipAttacks.Arm, org.joml.Matrix3f[]> whipRolls = new java.util.IdentityHashMap<>();

    private org.joml.Matrix3f[] restRolls(com.digicube.digimon.WhipAttacks.Arm arm) {
        ModelPart part = rootPart;
        var rest = restRotation(part);
        for (String name : arm.parentPath()) {
            if (!part.hasChild(name)) return new org.joml.Matrix3f[0];
            part = part.getChild(name);
            rest.mul(restRotation(part));
        }
        var rolls = new org.joml.Matrix3f[arm.parts().size()];
        for (int i = 0; i < rolls.length; i++) {
            if (!part.hasChild(arm.parts().get(i))) return new org.joml.Matrix3f[0];
            part = part.getChild(arm.parts().get(i));
            rest.mul(restRotation(part));
            // the section's own rest direction, as the whip's yaw and pitch (body frame: x left, y up, z forward)
            var a = rest.transform(new org.joml.Vector3f(0, -1, 0));
            float yaw = (float) Math.toDegrees(Math.atan2(-a.x, -a.z)), pitch = (float) Math.toDegrees(Math.asin(Math.clamp(a.y, -1, 1)));
            rolls[i] = whipFrame(yaw, pitch).transpose().mul(rest);
        }
        return rolls;
    }

    private static org.joml.Matrix3f restRotation(ModelPart part) {
        var p = part.getInitialPose();
        return new org.joml.Matrix3f().rotationZYX(p.zRot(), p.yRot(), p.xRot());
    }

    /**
     * The frame of a whip direction, in model axes: columns across (the way a growing yaw moves it, always level),
     * the third axis, and along it. Continuous in yaw and pitch, so nothing built on it can flip.
     */
    private static org.joml.Matrix3f whipFrame(float yawDegrees, float pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
        // body frame (x left, y up, z forward), as Minecraft's directionFromRotation; model axes turn y and z over
        var along = new org.joml.Vector3f((float) (-Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) -(Math.cos(yaw) * Math.cos(pitch)));
        var across = new org.joml.Vector3f((float) -Math.cos(yaw), 0, (float) Math.sin(yaw));
        var third = new org.joml.Vector3f(along).cross(across);
        return new org.joml.Matrix3f(across, third, along);
    }

    private static org.joml.Matrix3f rotation(ModelPart part) {
        return new org.joml.Matrix3f().rotationZYX(part.zRot, part.yRot, part.xRot);
    }

    /** A galloper (or a body with {@code bank} in the catalog, a running dinosaur) under a rider leans into its turns, more the faster it runs. */
    private void bank(DigimonRenderState state) {
        if (!(definition.gallop() || definition.bank()) || !state.isBeingRidden) return;
        rootPart.zRot += Math.clamp(state.aerialBank * .45F * Math.clamp(state.groundRunAmount * 1.5F, 0, 1), -9, 9) * ((float) Math.PI / 180);
    }

    @Override public net.minecraft.world.phys.Vec3 riderOffset(DigimonRenderState state) {
        if(definition.rider()==null)return net.minecraft.world.phys.Vec3.ZERO;
        setupAnim(state);
        var stack=new com.mojang.blaze3d.vertex.PoseStack();
        ModelPart part=rootPart;part.translateAndRotate(stack);
        for(String name:definition.rider().path()){part=part.getChild(name);part.translateAndRotate(stack);}
        var v=definition.rider().point();
        var p=stack.last().pose().transformPosition((float)v.x,(float)-v.z,(float)v.y,new org.joml.Vector3f());
        return new net.minecraft.world.phys.Vec3(p.x,1.5-p.y,-p.z).scale(state.modelScale).subtract(state.mountAnchor);
    }
    /**
     * The seat part's heading in the model, degrees (0 at rest): a strike that spins the body (DarkTyrannomon's Iron Tail,
     * a half turn) turns its rider with it, where the rider would otherwise keep facing the mount's own heading while the
     * body wheeled under them. Read right after {@link #riderOffset}, on the pose it set up.
     */
    @Override public float riderYaw(DigimonRenderState state) {
        if(definition.rider()==null)return 0;
        var stack=new com.mojang.blaze3d.vertex.PoseStack();
        ModelPart part=rootPart;part.translateAndRotate(stack);
        for(String name:definition.rider().path()){part=part.getChild(name);part.translateAndRotate(stack);}
        var forward=stack.last().pose().transformDirection(0,0,-1,new org.joml.Vector3f());
        if(forward.x*forward.x+forward.z*forward.z<1.0E-6F)return 0;
        return (float)Math.toDegrees(Math.atan2(-forward.x,-forward.z));
    }
    @Override public RiderPose riderPose(){return definition.rider()==null?new RiderPose(0,0,0):definition.rider().pose();}
    @Override public double cullingMargin(){return definition.cullingMargin();}

    private void supportFloor(DigimonRenderState state) {
        if(!definition.supportFloor() || state.swimAnimationAmount>.01F)return;
        float[] lowest={1.5F};
        rootPart.visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
            if(!path.contains("short_"))return;
            for(var polygon:cube.polygons)for(var vertex:polygon.vertices()) {
                var v=pose.pose().transformPosition(vertex.worldX(),vertex.worldY(),vertex.worldZ(),new org.joml.Vector3f());
                lowest[0]=Math.max(lowest[0],v.y);
            }
        });
        rootPart.y-=Math.max(0,lowest[0]-1.5F)*16;
    }

    /** How much of the pose a leap has: its {@code jump} clip takes over from the gait (DigimonEntity.tickLeapPose). */
    private float leap(DigimonRenderState state) {
        return state.leapWeight > 0 && animations.has("jump") ? Math.clamp(state.leapWeight, 0, 1) : 0;
    }

    private static final String[] DIRECTIONS={"walk","walk_back","strafe_left","strafe_right"};
    private void applyGround(DigimonRenderState state,float weight) {
        if(weight<=0)return;
        if(definition.supportFloor()) { mixGround(state, weight); return; }
        float amount = Math.clamp(state.groundAnimationAmount, 0, 1);
        float water=definition.amphibious()?Math.clamp(state.swimAnimationAmount,0,1):0;
        // A leap takes over from the gait: takeoff, flight and landing (DigimonEntity.tickLeapPose), and hands back.
        float leap = leap(state);
        if (definition.gallop()) {
            float gait = weight * (1 - leap);
            if (amount == 0) animations.apply("idle", state.ageInTicks, gait);
            else {
                float column = Math.clamp(state.groundRunAmount, 0, 1) * 8;
                int low = (int) column, high = Math.min(8, low + 1);
                animations.blend("gait_" + low, amount, state.groundAnimationPhase, gait * (1 - (column - low)));
                if (high != low) animations.blend("gait_" + high, amount, state.groundAnimationPhase, gait * (column - low));
            }
            if (leap > 0) animations.apply("jump", state.leapTick, weight * leap);
            return;
        }
        if (leap > 0) animations.apply("jump", state.leapTick, weight * leap * (1 - water));
        weight *= 1 - leap;
        animations.apply("idle", state.ageInTicks, (1 - amount)*(1-water)*weight);
        // The lattice excludes the idle-at-zero contribution. Its amplitude zero
        // is rest, so the independent idle clock never doubles body or tail motion.
        // A species with a run clip mixes it in as the pace passes the walk's authored speed; both share the gait phase.
        float run=animations.has("run")?Math.clamp(state.groundRunAmount,0,1):0;
        if(definition.walkBlend() && animations.blendNames().contains("walk_back")) {
            // Directional gait: planted clips per direction on one phase, mixed by the share of the movement each one carries.
            // A run clip takes over the forward share as the pace passes the walk's.
            for(int i=0;i<DIRECTIONS.length;i++) animations.blend(DIRECTIONS[i], amount, state.groundAnimationPhase, state.gaitShares[i]*(1-water)*weight*(i==0?1-run:1));
            if(run>0 && animations.blendNames().contains("run")) animations.blend("run", amount, state.groundAnimationPhase, state.gaitShares[0]*run*(1-water)*weight);
        } else if(definition.walkBlend()) {
            animations.blend("walk", amount, state.groundAnimationPhase, (1-run)*(1-water)*weight);
            if(run>0) animations.blend("run", amount, state.groundAnimationPhase, run*(1-water)*weight);
        } else {
            animations.apply("walk",state.groundAnimationPhase,(1-run)*amount*(1-water)*weight);
            if(run>0) animations.apply("run",state.groundAnimationPhase,run*amount*(1-water)*weight);
        }
        if(definition.amphibious()) {
            float power=Math.clamp(state.swimMotionAmount,0,1);
            animations.apply("swim_idle",state.ageInTicks,water*(1-power)*weight);
            animations.apply("swim",state.swimAnimationPhase,water*power*weight);
        }
    }

    /** Clips a tentacle body mixes on the ground and afloat, in {@link #mixGround}'s order. */
    private static final String[] GROUND_LAYERS = {"idle", "walk", "swim_idle", "swim", "jump"};
    private java.util.List<ModelPart> mixParts;
    private float[] mixPosition, mixScale;
    private org.joml.Quaternionf[] mixTurn;

    /**
     * A tentacle body (support_floor, Gesomon) mixes its ground and water clips by the shortest turn between whole poses
     * ({@code weight} of the mix over the rest pose), never by adding up Euler angles scaled by the clips' weights. An arm
     * section's angles in a clip may be any of the triples that make its turn (whole turns round, the other branch past a
     * straight pitch); scaled by a partial weight while the body sets off, slows or stops, they make a turn nobody
     * authored and the arms flicker in all directions.
     */
    private void mixGround(DigimonRenderState state, float weight) {
        float amount = Math.clamp(state.groundAnimationAmount, 0, 1);
        float water = definition.amphibious() ? Math.clamp(state.swimAnimationAmount, 0, 1) : 0;
        float power = Math.clamp(state.swimMotionAmount, 0, 1);
        float leap = leap(state), keep = 1 - leap;
        float[] weights = {(1 - amount) * (1 - water) * keep, amount * (1 - water) * keep, water * (1 - power) * keep, water * power * keep, leap * (1 - water)};
        float[] ticks = {state.ageInTicks, state.groundAnimationPhase, state.ageInTicks, state.swimAnimationPhase, state.leapTick};
        if (mixParts == null) {
            mixParts = rootPart.getAllParts();
            mixPosition = new float[mixParts.size() * 3];
            mixScale = new float[mixParts.size() * 3];
            mixTurn = new org.joml.Quaternionf[mixParts.size()];
            for (int i = 0; i < mixTurn.length; i++) mixTurn[i] = new org.joml.Quaternionf();
        }
        var turn = new org.joml.Quaternionf();
        float total = 0;
        for (int layer = 0; layer < GROUND_LAYERS.length; layer++) {
            float w = weights[layer];
            if (w <= 1.0E-4F || !animations.has(GROUND_LAYERS[layer])) continue;
            mixParts.forEach(ModelPart::resetPose);
            animations.apply(GROUND_LAYERS[layer], ticks[layer], 1);
            total += w;
            float f = w / total;
            for (int i = 0; i < mixTurn.length; i++) {
                var p = mixParts.get(i);
                turn.rotationZYX(p.zRot, p.yRot, p.xRot);
                if (f >= 1) mixTurn[i].set(turn); else mixTurn[i].slerp(turn, f);
                blend(mixPosition, i, p.x, p.y, p.z, f);
                blend(mixScale, i, p.xScale, p.yScale, p.zScale, f);
            }
        }
        mixParts.forEach(ModelPart::resetPose);
        if (total <= 0) return;
        var angles = new org.joml.Vector3f();
        for (int i = 0; i < mixTurn.length; i++) {
            var p = mixParts.get(i);
            var rest = p.getInitialPose();
            turn.set(mixTurn[i]);
            if (weight < 1) turn.set(new org.joml.Quaternionf().rotationZYX(rest.zRot(), rest.yRot(), rest.xRot()).slerp(mixTurn[i], weight));
            turn.getEulerAnglesZYX(angles);
            p.setRotation(angles.x, angles.y, angles.z);
            p.x = rest.x() + (mixPosition[3 * i] - rest.x()) * weight;
            p.y = rest.y() + (mixPosition[3 * i + 1] - rest.y()) * weight;
            p.z = rest.z() + (mixPosition[3 * i + 2] - rest.z()) * weight;
            p.xScale = rest.xScale() + (mixScale[3 * i] - rest.xScale()) * weight;
            p.yScale = rest.yScale() + (mixScale[3 * i + 1] - rest.yScale()) * weight;
            p.zScale = rest.zScale() + (mixScale[3 * i + 2] - rest.zScale()) * weight;
        }
    }

    private static void blend(float[] into, int i, float x, float y, float z, float f) {
        if (f >= 1) { into[3 * i] = x; into[3 * i + 1] = y; into[3 * i + 2] = z; return; }
        into[3 * i] += (x - into[3 * i]) * f; into[3 * i + 1] += (y - into[3 * i + 1]) * f; into[3 * i + 2] += (z - into[3 * i + 2]) * f;
    }

    private static float[][] table(com.google.gson.JsonObject data, String key, int width) {
        var rows=data.getAsJsonArray(key);var table=new float[rows.size()][];
        for(int i=0;i<table.length;i++){var row=rows.get(i).getAsJsonArray();table[i]=new float[width];
            if(row.size()!=width)throw new IllegalArgumentException("Invalid hoof beat row");
            for(int j=0;j<width;j++){table[i][j]=row.get(j).getAsFloat();
                if(!(table[i][j]>=0&&table[i][j]<=1))throw new IllegalArgumentException("Invalid hoof beat phase");}}
        if(table.length==0)throw new IllegalArgumentException("Empty hoof beat table");
        return table;
    }

    private static Look look(com.google.gson.JsonObject config) {
        if(!config.has("look"))return null;
        var l=config.getAsJsonObject("look");var path=new java.util.ArrayList<String>();
        l.getAsJsonArray("path").forEach(n->path.add(n.getAsString()));
        float yaw=l.get("yaw").getAsFloat(),pitch=l.get("pitch").getAsFloat();
        if(path.isEmpty()||!(yaw>=0&&yaw<=90)||!(pitch>=0&&pitch<=90))throw new IllegalArgumentException("Invalid head look");
        var carry=new java.util.ArrayList<Carry>();
        if(l.has("carry"))for(var item:l.getAsJsonArray("carry")){
            var c=item.getAsJsonObject();var names=new java.util.ArrayList<String>();
            c.getAsJsonArray("path").forEach(n->names.add(n.getAsString()));
            var entry=new Carry(java.util.List.copyOf(names),c.get("share").getAsFloat(),c.get("yaw").getAsFloat(),c.get("pitch").getAsFloat());
            if(names.isEmpty()||!(entry.share()>0&&entry.share()<1)||!(entry.yaw()>=0&&entry.yaw()<=90)||!(entry.pitch()>=0&&entry.pitch()<=90))
                throw new IllegalArgumentException("Invalid look carry");
            carry.add(entry);
        }
        var look=new Look(java.util.List.copyOf(path),yaw,pitch,java.util.List.copyOf(carry));
        if(!(look.headShare()>0))throw new IllegalArgumentException("Invalid head look shares");
        return look;
    }

    private static Stomps stomps(com.google.gson.JsonObject config) {
        if(!config.has("stomps"))return null;
        var s=config.getAsJsonObject("stomps");
        var down=s.getAsJsonArray("down");var feet=s.getAsJsonArray("feet");
        if(down.isEmpty()||down.size()!=feet.size())throw new IllegalArgumentException("Invalid stomps");
        float[] phases=new float[down.size()];float[][] at=new float[feet.size()][];
        for(int i=0;i<phases.length;i++){
            phases[i]=down.get(i).getAsFloat();
            var f=feet.get(i).getAsJsonArray();at[i]=new float[]{f.get(0).getAsFloat(),f.get(1).getAsFloat()};
            if(!(phases[i]>=0&&phases[i]<1)||f.size()!=2)throw new IllegalArgumentException("Invalid stomp");
        }
        return new Stomps(phases,at);
    }

    private static AttackEffects attackEffects(com.google.gson.JsonObject config) {
        if(!config.has("attack_effects"))return null;
        var e=config.getAsJsonObject("attack_effects");var clips=new LinkedHashMap<String,String>();
        e.getAsJsonObject("clips").entrySet().forEach(c->clips.put(c.getKey(),c.getValue().getAsString()));
        if(clips.isEmpty())throw new IllegalArgumentException("Attack effects without clips");
        return new AttackEffects(e.get("effect").getAsString(),Map.copyOf(clips));
    }

    private static Map<Identifier, Definition> readDefinitions() {
        try (var input = NativeGroundModel.class.getResourceAsStream("/assets/digicube/models/entity/ground_models.json")) {
            if (input == null) throw new IllegalStateException("Missing native ground model catalog");
            var data = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (data.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported ground model catalog");
            Map<Identifier, Definition> definitions = new LinkedHashMap<>();
            for (var entry : data.getAsJsonObject("models").entrySet()) {
                var species = Constants.id(entry.getKey());
                double margin = entry.getValue().getAsJsonObject().get("culling_margin").getAsDouble();
                if (!Double.isFinite(margin) || margin < 0) throw new IllegalArgumentException("Invalid native culling margin");
                var config=entry.getValue().getAsJsonObject();
                var aim=new java.util.ArrayList<String>();
                if(config.has("aim_path"))config.getAsJsonArray("aim_path").forEach(n->aim.add(n.getAsString()));
                Rider rider=null;
                if(config.has("rider")) {
                    var r=config.getAsJsonObject("rider");var path=new java.util.ArrayList<String>();
                    r.getAsJsonArray("path").forEach(n->path.add(n.getAsString()));var p=r.getAsJsonArray("point");
                    var legs=r.has("pose")?r.getAsJsonArray("pose"):null;
                    var hide=new java.util.ArrayList<String>();
                    if(r.has("hide"))r.getAsJsonArray("hide").forEach(n->hide.add(n.getAsString()));
                    rider=new Rider(java.util.List.copyOf(path),new net.minecraft.world.phys.Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble()),
                            legs==null?new RiderPose(0,0,0):new RiderPose(legs.get(0).getAsFloat(),legs.get(1).getAsFloat(),legs.get(2).getAsFloat(),
                                    legs.size()>3?legs.get(3).getAsFloat():0),java.util.List.copyOf(hide));
                }
                java.util.List<String> pitch=rider==null?null:rider.path();
                if(config.has("pitch_path")) {
                    var names=new java.util.ArrayList<String>();config.getAsJsonArray("pitch_path").forEach(n->names.add(n.getAsString()));pitch=java.util.List.copyOf(names);
                }
                var expressions = new java.util.ArrayList<TextureWindow>();
                if (config.has("expressions")) for (var item : config.getAsJsonArray("expressions")) {
                    var e = item.getAsJsonObject();
                    float from = e.get("from").getAsFloat(), until = e.get("until").getAsFloat();
                    if (!Float.isFinite(from) || !Float.isFinite(until) || from < 0 || until <= from)
                        throw new IllegalArgumentException("Invalid expression window " + species);
                    expressions.add(new TextureWindow(e.get("clip").getAsString(), from, until,
                            Constants.id("textures/entity/digimon/" + e.get("texture").getAsString() + ".png")));
                }
                java.util.List<String> upper=null;
                if(config.has("upper_body")){var names=new java.util.ArrayList<String>();config.getAsJsonArray("upper_body").forEach(n->names.add(n.getAsString()));upper=java.util.List.copyOf(names);}
                Flames flames=null;
                if(config.has("charge_flames")){var f=config.getAsJsonObject("charge_flames");
                    flames=new Flames(f.get("clip").getAsString(),f.get("tick").getAsFloat(),f.get("prefix").getAsString());}
                HoofTimes hoofBeats=null;
                if(config.has("hoof_beats")){var h=config.getAsJsonObject("hoof_beats");var names=new java.util.ArrayList<String>();
                    h.getAsJsonArray("hooves").forEach(n->names.add(n.getAsString()));
                    hoofBeats=new HoofTimes(java.util.List.copyOf(names),table(h,"down",names.size()),table(h,"up",names.size()));
                    if(hoofBeats.down().length!=hoofBeats.up().length)throw new IllegalArgumentException("Invalid hoof beats "+species);}
                definitions.put(species, new Definition(species, margin,GsonHelper.getAsBoolean(config,"amphibious",false),
                        GsonHelper.getAsBoolean(config,"walk_blend",true),java.util.List.copyOf(aim),
                        GsonHelper.getAsFloat(config,"attack_blend_in",0),GsonHelper.getAsFloat(config,"attack_blend_out",0),GsonHelper.getAsBoolean(config,"gallop",false),GsonHelper.getAsBoolean(config,"support_floor",false),rider,
                        pitch,GsonHelper.getAsFloat(config,"ridden_pitch",8),java.util.List.copyOf(expressions),ClothChains.read(config),upper,flames,hoofBeats,
                        look(config),attackEffects(config),RopeChains.read(config),
                        // A thrower's returning weapon, drawn where it is carried while the Digimon holds it.
                        GsonHelper.getAsString(config,"carried",null),
                        // The dive pitch without a rider, and whether it turns the body about the rider's seat.
                        GsonHelper.getAsFloat(config,"swim_pitch",65),GsonHelper.getAsBoolean(config,"pitch_at_rider",false),stomps(config),
                        GsonHelper.getAsBoolean(config,"bank",false)));
            }
            return Map.copyOf(definitions);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load native ground model catalog", e);
        }
    }
}
