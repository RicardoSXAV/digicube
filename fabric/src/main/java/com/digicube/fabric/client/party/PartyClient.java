package com.digicube.fabric.client.party;

import com.digicube.Constants;
import com.digicube.analyzer.AnalyzerDiscoveryPayload;
import com.digicube.digimon.CombatMark;
import com.digicube.fabric.client.digivice.AnalyzerNews;
import com.digicube.fabric.client.digivice.DiscoveryToast;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.DigimonPart;
import com.digicube.fabric.client.digivice.DigiviceScreen;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.digicube.registry.DCItems;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyHealthPayload;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartyRoster;
import com.digicube.party.PartySnapshotPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Client receiver, party keys and HUD registration. State is connection-scoped and reset
 * on disconnect. One party slot is <em>selected</em>: the arrow keys move the selection
 * through the filled slots, the Digivolve key acts on the selected partner and holding the
 * command wheel key opens {@link CommandWheelScreen} for it. A deployed partner under the
 * crosshair is <em>aimed at</em>: it is outlined in blue, the wheel opens on it, and when it
 * can carry its owner the wheel offers Ride.
 */
public final class PartyClient {
    private PartySnapshotPayload snapshot = empty();
    /** Client ticks since {@link #snapshot} arrived, so a countdown can continue between server updates. */
    private int snapshotAge;
    /** The party slot the arrow keys have selected; always a filled slot while the party is not empty. */
    private int selected;
    private final PartyHud hud = new PartyHud();
    /** What the Analyzer recorded since connecting and the tamer has not opened yet. */
    private final AnalyzerNews news = new AnalyzerNews();
    /** The Digitama ready to convert that the tamer has not looked at on the SCAN page yet. */
    private final com.digicube.fabric.client.digivice.ScanNews scanNews = new com.digicube.fabric.client.digivice.ScanNews();
    /** How far the crosshair picks a partner out, in blocks. Asking for a ride needs {@link DigimonEntity#RIDE_REACH}. */
    private static final double AIM_REACH = 24;
    /** The own deployed partner under the crosshair, or null. Static for the render mixins, like the rider's soft target. */
    private static DigimonEntity aimed;
    /** Outlined in blue while the player can open the wheel on it. */
    public static DigimonEntity aimedPartner() { return aimed; }
    public static final int AIM_OUTLINE = 0xFF3D8BFF;
    private KeyMapping evolveKey;
    private KeyMapping previousKey;
    private KeyMapping nextKey;
    private KeyMapping wheelKey;
    /** The enemy on the crosshair when the wheel opened, that the selected partner would fight; what an order goes at without a target. */
    private net.minecraft.world.entity.LivingEntity sighted;
    /** The partner the last attack order went to, so a refusal the server answers with is shown on its card. */
    private java.util.UUID ordered;
    /** How long a refused order's reason stays on its partner's card, in ticks. */
    static final int NOTICE_TICKS = 40;
    /** The partner whose tree the Digivice should open on, once the server opens it: DIGIVOLVE before the choice. */
    private java.util.UUID pendingTree;
    /** The Digitama the Digivice should open on, once the server opens it: one just taken in from the hand. */
    private java.util.UUID pendingEgg;
    /** Client ticks since {@link #pendingTree} or {@link #pendingEgg} was asked for. */
    private int pendingAge;

    private static PartySnapshotPayload empty() {
        return new PartySnapshotPayload(false, 0, 0, List.of(), List.of(), "");
    }

    public void init() {
        KeyMapping.Category category = KeyMapping.Category.register(Constants.id("party"));
        evolveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.evolve", InputConstants.KEY_V, category));
        previousKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.party_previous", InputConstants.KEY_UP, category));
        nextKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.party_next", InputConstants.KEY_DOWN, category));
        wheelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.digicube.command_wheel", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_MIDDLE, category));
        RiderControls.init(category);
        ClientLifecycleEvents.CLIENT_STARTED.register(this::movePickBlock);
        ClientTickEvents.END_CLIENT_TICK.register(this::handleKeys);
        ClientPlayNetworking.registerGlobalReceiver(PartySnapshotPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    snapshot = payload;
                    snapshotAge = 0;
                    scanNews.update(payload.scan());
                    selected = PartyHudReadout.normalizeSelection(filled(), selected);
                    // An attack order the server turned down: its reason on the partner's card, or the wheel's caption.
                    if (ordered != null && payload.message().startsWith(REFUSED)) {
                        // a gauge still charging reads out its share, as the partner shows it here
                        boolean charging = payload.message().equals(REFUSED + "charging");
                        var partner = charging ? partner(ordered) : null;
                        var reason = !charging ? net.minecraft.network.chat.Component.translatable(payload.message())
                                : net.minecraft.network.chat.Component.translatable(payload.message(), partner == null ? "?" : CommandWheelReadout.percent(partner.shownGauge()));
                        hud.notice(ordered, reason.getString(), NOTICE_TICKS);
                        ordered = null;
                    }
                    if (payload.openScreen() && !(context.client().gui.screen() instanceof DigiviceScreen)) {
                        context.client().gui.setScreen(new DigiviceScreen(this));
                    } else if (context.client().gui.screen() instanceof DigiviceScreen screen) {
                        screen.receive(payload);
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(PartyHealthPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    snapshot = payload.apply(snapshot);
                    if (context.client().gui.screen() instanceof DigiviceScreen screen) {
                        screen.receiveHealth(snapshot, payload);
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(AnalyzerDiscoveryPayload.TYPE, (payload, context) ->
                context.client().execute(() -> discovered(context.client(), payload)));
        ClientPlayNetworking.registerGlobalReceiver(com.digicube.scan.ScanToastPayload.TYPE, (payload, context) ->
                context.client().execute(() -> com.digicube.fabric.client.digivice.ScanToast.show(context.client(), payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            snapshot = empty();
            selected = 0;
            aimed = null;
            sighted = null;
            ordered = null;
            pendingTree = null;
            pendingEgg = null;
            news.clear();
            scanNews.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            snapshotAge++;
            pendingAge++;
            hud.tick(snapshot);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.MOUNT_HEALTH, vanilla ->
                (graphics, delta) -> RiderAttacks.hud(graphics, delta, () -> vanilla.extractRenderState(graphics, delta)));
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Constants.id("party"),
                (graphics, delta) -> hud.draw(graphics, delta, snapshot, snapshotAge, selected, evolveKey));
    }

    /**
     * The middle mouse button is the command wheel's, so vanilla's pick block moves to B: that becomes its default, and
     * a pick block still sharing the wheel's key (a fresh install, or options saved before this) is rebound once.
     * A player who binds either elsewhere is left alone.
     */
    private void movePickBlock(Minecraft client) {
        KeyMapping pick = client.options.keyPickItem;
        InputConstants.Key b = InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_B);
        ((com.digicube.fabric.mixin.KeyMappingAccessor) pick).digicube$setDefaultKey(b);
        if (!pick.same(wheelKey)) return;
        pick.setKey(b);
        KeyMapping.resetMapping();
        client.options.save();
    }

    /** Arrow keys move the selection; the Digivolve key asks the server to evolve the selected resting partner. */
    private void handleKeys(Minecraft client) {
        boolean inWorld = client.player != null && client.gui.screen() == null;
        // The wheel keeps what it was opened on; any other screen, the saddle or a hidden HUD drops the aim.
        if (inWorld) aimed = client.gui.hud.isHidden() ? null : aim(client);
        else if (!(client.gui.screen() instanceof CommandWheelScreen) || aimed != null && !aimed.isAlive()) aimed = null;
        while (previousKey.consumeClick()) if (inWorld) selected = PartyHudReadout.nextSelection(filled(), selected, -1);
        while (nextKey.consumeClick()) if (inWorld) selected = PartyHudReadout.nextSelection(filled(), selected, 1);
        while (wheelKey.consumeClick()) {
            // The wheel is the Digivice's: no device in the inventory, no wheel. The server checks the same.
            if (inWorld && client.player.getInventory().contains(stack -> stack.is(DCItems.DIGIVICE))) {
                // From the saddle the wheel opens on the Digimon under the rider: its attacks are cast from there.
                if (client.player.getVehicle() instanceof DigimonEntity mount) {
                    for (PartyMemberView member : snapshot.party()) if (member.id().equals(mount.getUUID())) selected = member.slot();
                } else if (aimed != null && member(aimed) != null) {
                    // On foot it opens on the partner under the crosshair.
                    selected = member(aimed).slot();
                }
                PartyMemberView opened = member(selected);
                DigimonEntity partner = opened == null ? null : partner(opened.id());
                sighted = partner == null || client.player.isPassenger() ? null : com.digicube.party.AttackOrders.sighted(client.player, partner);
                client.gui.setScreen(new CommandWheelScreen(this));
            }
        }
        while (evolveKey.consumeClick()) {
            if (!inWorld) continue;
            snapshot.party().stream().filter(m -> m.slot() == selected && m.phase().equals("RESTING")).findFirst().ifPresent(this::digivolve);
        }
    }

    /**
     * The Digivolve key does what the wheel's DIGIVOLVE does: before the partner's first digivolution it opens its tree
     * to choose the form; once bound it digivolves into that form. A partner that cannot digivolve now is still asked
     * for, so the server's reason shows.
     */
    private void digivolve(PartyMemberView m) {
        boolean route = com.digicube.digimon.EvolutionRules.target(m.species(), m.level()).isPresent();
        boolean ready = CommandWheelReadout.modules(m, snapshotAge, route)[CommandWheelReadout.BOTTOM_RIGHT].enabled();
        if (ready && m.lineId() == null) openTree(m);
        else send(new PartyActionPayload(PartyActionPayload.EVOLVE, m.id(), 0, m.generation(), m.sequence()));
    }

    /** Opens the Digivice on {@code member}'s tree, where the tamer picks the form it digivolves into. */
    void openTree(PartyMemberView member) {
        pendingTree = member.id();
        pendingAge = 0;
        send(new PartyActionPayload(PartyActionPayload.OPEN, PartyActionPayload.NO_MEMBER, 0));
    }

    /** The tree the Digivice was asked to open on, once; null when it was opened any other way, or the ask is stale. */
    public java.util.UUID takePendingTree() {
        java.util.UUID tree = pendingAge <= PENDING_TICKS ? pendingTree : null;
        pendingTree = null;
        return tree;
    }

    /**
     * A Digitama was taken into the Digivice from the hand: the Digivice opens on the Digispace, where it comes together.
     * An open Digivice turns to it; any other screen is left alone.
     */
    public void openEgg(java.util.UUID id) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof DigiviceScreen screen) { screen.arrive(id); return; }
        if (client.player == null || client.gui.screen() != null) return;
        pendingEgg = id;
        pendingAge = 0;
        send(new PartyActionPayload(PartyActionPayload.OPEN, PartyActionPayload.NO_MEMBER, 0));
    }

    /** The Digitama the Digivice was asked to open on, once; null when it was opened any other way, or the ask is stale. */
    public java.util.UUID takePendingEgg() {
        java.util.UUID egg = pendingAge <= PENDING_TICKS ? pendingEgg : null;
        pendingEgg = null;
        return egg;
    }

    /** An open request the server turned down is forgotten: the next snapshot comes well within this. */
    private static final int PENDING_TICKS = 40;

    public AnalyzerNews news() { return news; }
    public com.digicube.fabric.client.digivice.ScanNews scanNews() { return scanNews; }

    /**
     * The Analyzer recorded something: it is news until opened, and a toast says so. Not in creative, where the
     * Analyzer shows every entry anyway; the tag waits for survival.
     */
    private void discovered(Minecraft client, AnalyzerDiscoveryPayload payload) {
        CombatMark mark = payload.combatMark();
        if (payload.mark() && mark == null) return;
        DiscoveryToast toast = mark != null ? DiscoveryToast.of(mark) : DiscoveryToast.of(payload.id());
        if (toast == null) return;
        if (mark != null) news.add(mark); else news.add(payload.id());
        if (client.player != null && !client.player.isCreative()) client.gui.toastManager().addToast(toast);
    }

    /** Server messages that turn an attack order down start with this. */
    static final String REFUSED = "gui.digicube.wheel.refused.";

    /** The enemy an attack order goes at when the partner has no target of its own, or null. */
    net.minecraft.world.entity.LivingEntity sighted() {
        return sighted != null && sighted.isAlive() && !sighted.isRemoved() ? sighted : null;
    }

    /** The partner {@code id} as this client sees it, or null while it is out of sight: its tiles' clocks come from the attacks it was seen to start. */
    DigimonEntity partner(java.util.UUID id) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;
        for (var entity : client.level.entitiesForRendering()) if (entity instanceof DigimonEntity digimon && digimon.getUUID().equals(id)) return digimon;
        return null;
    }

    /** Orders {@code member} to cast the attack in its sheet slot {@code slot}; a refusal the server answers with lands on its card. */
    void orderAttack(PartyMemberView member, int slot) {
        ordered = member.id();
        send(new PartyActionPayload(PartyActionPayload.ATTACK, member.id(), slot));
    }

    /** Puts {@code member}'s attack in {@code slot} on AUTO or on manual. The wheel shows the flip at once; the next snapshot confirms it. */
    void setAuto(PartyMemberView member, int slot, boolean auto) {
        send(new PartyActionPayload(PartyActionPayload.AUTO_ATTACK, member.id(), PartyActionPayload.autoValue(slot, auto)));
        PartyMemberView flipped = member.withManual(slot, !auto);
        snapshot = new PartySnapshotPayload(false, snapshot.page(), snapshot.total(),
                snapshot.party().stream().map(m -> m.id().equals(member.id()) ? flipped : m).toList(), snapshot.collection(), "", snapshot.known(), snapshot.marks());
    }

    /** Shows why an order was not given on {@code member}'s card for a moment; the wheel's caption shows it while the wheel is open. */
    void notice(java.util.UUID member, String text) { hud.notice(member, text, NOTICE_TICKS); }

    /** The reason still shown for {@code member}, or null. */
    String notice(java.util.UUID member) { return hud.notice(member); }

    /** The party member that is {@code entity}, or null. */
    private PartyMemberView member(DigimonEntity entity) {
        for (PartyMemberView member : snapshot.party()) if (member.id().equals(entity.getUUID())) return member;
        return null;
    }

    /** The own party Digimon on the crosshair's ray, before any block: the body or one of its hit parts. */
    private DigimonEntity aim(Minecraft client) {
        var player = client.player;
        if (player.isPassenger() || player.isSpectator()) return null;
        Vec3 from = player.getEyePosition(), look = player.getViewVector(1), to = from.add(look.scale(AIM_REACH));
        var block = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) to = block.getLocation();
        var hit = ProjectileUtil.getEntityHitResult(player, from, to, player.getBoundingBox().expandTowards(look.scale(AIM_REACH)).inflate(1),
                entity -> !entity.isSpectator() && entity.isPickable(), from.distanceToSqr(to));
        if (hit == null || !(DigimonPart.livingOf(hit.getEntity()) instanceof DigimonEntity digimon)) return null;
        return digimon.isAlive() && digimon.isOwnedBy(player) && member(digimon) != null ? digimon : null;
    }

    /** Whether the wheel, open on {@code member}, may offer Ride: it is the partner aimed at, and it would take its owner now. */
    boolean rideable(PartyMemberView member) {
        Minecraft client = Minecraft.getInstance();
        return aimed != null && client.player != null && member.id().equals(aimed.getUUID()) && aimed.canGiveRide(client.player);
    }

    private boolean[] filled() {
        boolean[] filled = new boolean[PartyRoster.PARTY_SIZE];
        for (PartyMemberView member : snapshot.party()) {
            if (member.slot() >= 0 && member.slot() < filled.length) filled[member.slot()] = true;
        }
        return filled;
    }

    /** The party member in {@code slot}, or null for an empty slot. */
    PartyMemberView member(int slot) {
        for (PartyMemberView member : snapshot.party()) if (member.slot() == slot) return member;
        return null;
    }

    /** The filled slot {@code step} places from the selection, wrapping; the selection itself when it is the only one. */
    int neighbour(int step) { return PartyHudReadout.nextSelection(filled(), selected, step); }
    void selectNext() { selected = neighbour(1); }
    int selected() { return selected; }
    KeyMapping wheelKey() { return wheelKey; }

    public PartySnapshotPayload snapshot() { return snapshot; }
    public int snapshotAge() { return snapshotAge; }

    public void send(PartyActionPayload payload) {
        if (ClientPlayNetworking.canSend(PartyActionPayload.TYPE)) ClientPlayNetworking.send(payload);
    }
}
