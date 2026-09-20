package com.digicube.fabric.client.party;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.RiderAttack;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.TectonicWave;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.party.PartyActionPayload;
import com.digicube.registry.DCItems;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Mounted combat's direct controls. The hands rule: with an empty main hand (or the Digivice in it) the
 * attack button casts the mount's quick attack and the use button its special; with anything else in hand
 * the mouse is the rider's own, as on a horse. Two rebindable keys cast whatever is held. An attack that is
 * aimed along the ground is held to show where it will run and cast on release; a tap casts at once. Also
 * here: the soft target a swing would turn to (outlined by {@code MixinMinecraft}), the vanilla crosshair kept
 * in third person, the mounted camera (third person by default, remembered) and the camera's kick on impacts.
 * Design: {@code design/mounted-combat.md} section 5.
 */
public final class RiderControls {
    private RiderControls() {}

    /** Held this long, an aimed attack shows its ground telegraph. */
    private static final int AIM_HOLD_TICKS = 4;
    private static final Path SETTINGS = FabricLoader.getInstance().getConfigDir().resolve("digicube-client.properties");

    private static final net.minecraft.resources.Identifier CROSSHAIR = net.minecraft.resources.Identifier.withDefaultNamespace("hud/crosshair");
    private static KeyMapping quickKey, specialKey;
    private static LivingEntity softTarget;
    private static DigimonEntity aimMount;
    private static float[] aimHeights;
    private static float aimYaw;
    private static final int SLOTS = 2;
    private static final boolean[] wasDown = new boolean[SLOTS];
    private static final int[] held = new int[SLOTS], lastSend = new int[SLOTS];
    private static DigimonEntity lastMount;
    private static CameraType cameraBefore;
    private static boolean thirdPerson = true;

    static void init(KeyMapping.Category category) {
        quickKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.rider_quick", InputConstants.KEY_R, category));
        specialKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.rider_special", InputConstants.KEY_G, category));
        thirdPerson = load();
        ClientTickEvents.END_CLIENT_TICK.register(RiderControls::tick);
        // The attack button belongs to the mount while the rider's hand is free; the use button is taken in MixinMinecraft.
        ClientPreAttackCallback.EVENT.register((minecraft, player, clicks) -> takesMouse());
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Constants.id("rider_aim"), (graphics, delta) -> reticle(graphics));
    }

    /** The rider's hand is free for the mount's attacks: empty, or holding the Digivice. */
    static boolean handsFree(LocalPlayer player) {
        return player.getMainHandItem().isEmpty() || player.getMainHandItem().is(DCItems.DIGIVICE);
    }

    /** Whether the mouse buttons cast the mount's attacks right now instead of doing what vanilla would. */
    public static boolean takesMouse() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gui.screen() == null && RiderAttacks.mount(minecraft) != null && handsFree(minecraft.player);
    }

    /** What the quick attack would turn to, or null; outlined while the rider can cast. */
    public static LivingEntity softTarget() { return softTarget; }

    /**
     * Terrain heights of the spike wave the rider is aiming from {@code mount}, or null: the mount's renderer draws
     * the spikes there as a phantom, turned to where the rider looks.
     */
    public static float[] aimedWave(DigimonEntity mount) { return mount == aimMount ? aimHeights : null; }
    /** The yaw the aimed wave would run along: the rider's view, corrected for the fist it starts from. */
    public static float aimedWaveYaw() { return aimYaw; }

    private static void tick(Minecraft minecraft) {
        DigimonEntity mount = RiderAttacks.mount(minecraft);
        camera(minecraft, mount);
        aimMount = null;
        if (mount == null || minecraft.gui.screen() != null) {
            softTarget = null;
            java.util.Arrays.fill(wasDown, false);
            java.util.Arrays.fill(held, 0);
            return;
        }
        LocalPlayer player = minecraft.player;
        List<DigimonAttack> attacks = mount.riderAttacks();
        boolean free = handsFree(player);
        softTarget = null;
        for (DigimonAttack attack : attacks) if (softTarget == null) softTarget = mount.softTarget(player, attack);

        // Slot 0 is the quick button, slot 1 the special one; what a button does comes from the attack's rider data.
        for (int slot = 0; slot < Math.min(attacks.size(), SLOTS); slot++) {
            DigimonAttack attack = attacks.get(slot);
            RiderAttack spec = mount.riderSpec(attack);
            boolean down = (slot == 0 ? quickKey : specialKey).isDown()
                    || free && (slot == 0 ? minecraft.options.keyAttack : minecraft.options.keyUse).isDown();
            if (spec == null) {
                wasDown[slot] = down;
                continue;
            }
            boolean hold = spec.input() == RiderAttack.Input.HOLD;
            if (hold && spec.aim() == RiderAttack.Aim.STREAM) {
                // Breathes for as long as the button is held; a press while the tank refills is tried again.
                if (down && mount.getAnimatingAttack() == null && (!wasDown[slot] || mount.tickCount - lastSend[slot] > 10) && cast(mount, slot)) lastSend[slot] = mount.tickCount;
                if (!down && wasDown[slot] && ClientPlayNetworking.canSend(PartyActionPayload.TYPE))
                    ClientPlayNetworking.send(new PartyActionPayload(PartyActionPayload.RIDER_RELEASE, PartyActionPayload.NO_MEMBER, slot));
            } else if (hold) {
                // Held, it shows where it will go; released, it goes.
                if (down) {
                    if (++held[slot] >= AIM_HOLD_TICKS && mount.seenCooldown(attack) <= DigimonEntity.RIDER_BUFFER_TICKS
                            && attack.kind() == DigimonAttack.Kind.GROUND_WAVE) telegraph(mount, player, held[slot]);
                } else {
                    if (wasDown[slot]) cast(mount, slot);
                    held[slot] = 0;
                }
            } else if (down && (!wasDown[slot] || mount.tickCount - lastSend[slot] > DigimonEntity.RIDER_BUFFER_TICKS) && cast(mount, slot)) {
                // A held button keeps striking: the press is repeated as each cooldown runs out.
                lastSend[slot] = mount.tickCount;
            }
            wasDown[slot] = down;
        }
    }

    /** Asks the server for rider slot {@code slot} when it is ready or close enough for the server to buffer. */
    private static boolean cast(DigimonEntity mount, int slot) {
        if (mount.seenCooldown(mount.riderAttacks().get(slot)) > DigimonEntity.RIDER_BUFFER_TICKS || !ClientPlayNetworking.canSend(PartyActionPayload.TYPE)) return false;
        ClientPlayNetworking.send(new PartyActionPayload(PartyActionPayload.RIDER_ATTACK, PartyActionPayload.NO_MEMBER, slot));
        return true;
    }

    /**
     * Where the spike wave would run if released now, from the same terrain walk the server does. The spikes that
     * would rise are drawn as a phantom of the real model by the mount's renderer; one that is blocked leaves a red
     * footprint on the ground instead.
     */
    private static void telegraph(DigimonEntity mount, LocalPlayer player, int heldTicks) {
        Vec3 feet = mount.position();
        float yaw = aimYaw = mount.riderCastYaw(player, mount.riderAttacks().stream().filter(x -> x.kind() == DigimonAttack.Kind.GROUND_WAVE).findFirst().orElseThrow());
        float[] heights = TectonicWave.ground(mount.level(), mount, feet, yaw);
        aimMount = mount;
        aimHeights = heights;
        float last = 0;
        for (int spike = 0; spike < TectonicWave.COUNT; spike++) {
            if (heights[spike] != TectonicWave.INVALID) { last = heights[spike]; continue; }
            if (heldTicks % 2 != 0) continue;
            AABB box = TectonicWave.local(spike, 48);
            DustParticleOptions dust = new DustParticleOptions(0xE03030, .55F);
            double[][] outline = {{box.minX, box.minZ}, {box.maxX, box.minZ}, {box.maxX, box.maxZ}, {box.minX, box.maxZ}};
            for (int corner = 0; corner < 4; corner++) {
                double[] a = outline[corner], b = outline[(corner + 1) % 4];
                for (int step = 0; step < 3; step++) {
                    Vec3 at = AttackGeometry.world(feet, new Vec3(Mth.lerp(step / 3.0, a[0], b[0]), last + .12, Mth.lerp(step / 3.0, a[1], b[1])), yaw);
                    mount.level().addAlwaysVisibleParticle(dust, at.x, at.y, at.z, 0, 0, 0);
                }
            }
        }
    }

    /** Third person by default from a fighting mount's saddle; the rider's own choice is kept, and put back on the ground. */
    private static void camera(Minecraft minecraft, DigimonEntity mount) {
        if (mount != lastMount) {
            if (mount != null && lastMount == null) {
                cameraBefore = minecraft.options.getCameraType();
                if (thirdPerson && cameraBefore.isFirstPerson()) minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            } else if (mount == null && cameraBefore != null) {
                minecraft.options.setCameraType(cameraBefore);
                cameraBefore = null;
            }
            lastMount = mount;
        } else if (mount != null && minecraft.options.getCameraType().isFirstPerson() == thirdPerson) {
            thirdPerson = !thirdPerson;
            save();
        }
    }

    /**
     * The rider's aim is the ordinary crosshair, untouched. Vanilla hides it in third person, which is where a
     * rider fights from, so there the same sprite is drawn in the same place. The soft target is told by its
     * outline alone.
     */
    private static void reticle(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        if (RiderAttacks.mount(minecraft) == null || minecraft.gui.hud.isHidden() || minecraft.options.getCameraType().isFirstPerson()) return;
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.CROSSHAIR, CROSSHAIR, (g.guiWidth() - 15) / 2, (g.guiHeight() - 15) / 2, 15, 15);
    }

    /**
     * The camera's answer to the mount's impacts, in degrees of yaw and pitch and as a field-of-view factor: a
     * short shudder when a swing connects, a heavier one with a brief widening when the ground slam lands.
     */
    public static float[] cameraKick(float partialTick) {
        DigimonEntity mount = RiderAttacks.mount(Minecraft.getInstance());
        if (mount == null) return null;
        float slam = mount.ticksSinceSlam() + partialTick, impact = mount.ticksSinceImpact() + partialTick;
        float shake = 0, time = 0, fov = 1;
        if (slam >= 0 && slam < 10) { shake = 1.5F * Mth.square(1 - slam / 10); time = slam; fov += .07F * Mth.square(1 - slam / 10); }
        if (impact >= 0 && impact < 5 && .6F * (1 - impact / 5) > shake) { shake = .6F * (1 - impact / 5); time = impact; }
        return shake == 0 && fov == 1 ? null : new float[] {shake * Mth.sin(time * 2.9F), shake * Mth.cos(time * 3.7F), fov};
    }

    private static boolean load() {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(SETTINGS)) {
            properties.load(in);
        } catch (java.io.IOException ignored) {
            return true;
        }
        return !"false".equals(properties.getProperty("mounted_third_person"));
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("mounted_third_person", Boolean.toString(thirdPerson));
        try (var out = Files.newOutputStream(SETTINGS)) {
            properties.store(out, "DigiCube client settings");
        } catch (java.io.IOException e) {
            Constants.LOG.warn("Could not save {}", SETTINGS, e);
        }
    }
}
