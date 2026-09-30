package com.digicube.fabric.client.dev;

import com.digicube.Constants;
import com.digicube.dev.BattleRoster;
import com.digicube.dev.BattleTest;
import com.digicube.dev.DevActionPayload;
import com.digicube.dev.DevStatePayload;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.platform.Services;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client side of the developer panel, alive only in a development environment. The panel has
 * no key: it opens from the gear on the command wheel ({@link DevGear}) and closes with Esc.
 * This object is what survives between openings: the declared tabs, which tab and sections
 * were open, the Battle Testing picks, and the fight readout the server sends.
 */
public final class DevClient {
    /** A fight readout older than this was abandoned by the server (it syncs every few ticks). */
    private static final int STALE_TICKS = 60;

    private static DevClient instance;

    /** The panel's client, or null outside a development environment. */
    public static DevClient get() { return instance; }

    final DevTabs tabs = new DevTabs();
    List<DevSearch.Entry> index = List.of();

    // what the panel remembers between openings
    int tab, firstTab;
    final Map<DevTabs.Tab, Integer> scroll = new HashMap<>();
    final Set<DevTabs.Section> collapsed = new HashSet<>();

    // Battle Testing sides; the species list is static, so they survive worlds too
    final List<Fighter> sideA = new ArrayList<>(), sideB = new ArrayList<>();
    DevTabs.Section battleSection, analyzerSection;
    /** The section whose action is out with the server; its reply lands there. */
    private DevTabs.Section asked;

    /** One kind on a Battle Testing side: how many of which Digimon at which level; no species yet while being picked. */
    static final class Fighter {
        Identifier species;
        int level = Progression.CHAMPION_LEVEL, count = 1;

        Fighter(Identifier species) { this.species = species; }

        BattleRoster.Entry entry() { return new BattleRoster.Entry(species.toString(), level, count); }
    }

    /** The running fight as {@link BattleTest} describes it, or null. */
    private CompoundTag battle;
    private int battleAge;

    public void init() {
        if (!Services.PLATFORM.isDevelopmentEnvironment()) return;
        instance = this;
        DevCatalog.declare(this);
        index = DevSearch.index(tabs);
        List<DigimonSpecies> fighters = DigimonSpeciesRegistry.all().stream().filter(DigimonSpecies::canFight).toList();
        sideA.add(new Fighter(pick(fighters, "agumon", 0)));
        sideB.add(new Fighter(pick(fighters, "gabumon", 1)));

        ClientPlayNetworking.registerGlobalReceiver(DevStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    battle = payload.state().getCompound(BattleTest.BATTLE).orElse(null);
                    battleAge = 0;
                    if (!payload.reply().isEmpty()) {
                        DevTabs.Section to = asked != null ? asked : battleSection;
                        asked = null;
                        if (to != null) to.status(payload.reply().toUpperCase(Locale.ROOT));
                    }
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> battle = null);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (battle != null && ++battleAge > STALE_TICKS) battle = null;
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Constants.id("dev_battle"), (graphics, delta) -> {
            Minecraft client = Minecraft.getInstance();
            if (battle == null || client.player == null || client.gui.hud.isHidden() || client.gui.screen() instanceof DevPanelScreen) return;
            BattleReadout.draw(graphics, client.font, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScale(), battle, battleAge);
        });
    }

    private static Identifier pick(List<DigimonSpecies> fighters, String preferred, int fallback) {
        return DigimonSpeciesRegistry.get(Constants.id(preferred)).filter(DigimonSpecies::canFight).map(DigimonSpecies::id)
                .orElse(fighters.size() > fallback ? fighters.get(fallback).id() : null);
    }

    boolean fighting() { return battle != null; }

    /** Both sides have a Digimon in every row. */
    boolean ready() {
        return !sideA.isEmpty() && !sideB.isEmpty() && sideA.stream().allMatch(f -> f.species != null) && sideB.stream().allMatch(f -> f.species != null);
    }

    static ListTag write(List<Fighter> side) {
        return BattleRoster.write(side.stream().map(Fighter::entry).toList());
    }

    void send(String action, CompoundTag args) {
        if (ClientPlayNetworking.canSend(DevActionPayload.TYPE)) ClientPlayNetworking.send(new DevActionPayload(action, args));
    }

    /** {@link #send}, with the reply shown on {@code from}. */
    void send(String action, CompoundTag args, DevTabs.Section from) {
        asked = from;
        send(action, args);
    }
}
