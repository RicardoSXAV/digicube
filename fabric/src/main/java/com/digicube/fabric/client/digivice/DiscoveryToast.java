package com.digicube.fabric.client.digivice;

import com.digicube.digimon.CombatMark;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * What slides in at the top right when the Analyzer records something: the new entry's sprite or emblem, what it is
 * and its name. A vanilla toast, so it queues with the advancement toasts instead of covering them.
 */
public final class DiscoveryToast implements Toast {
    private static final long SHOWN_MILLIS = 4000;

    private final Identifier species;
    private final CombatMark mark;
    private final String title, name;
    private Visibility wanted = Visibility.SHOW;
    private long shown = SHOWN_MILLIS;

    private DiscoveryToast(Identifier species, CombatMark mark, String title, String name) {
        this.species = species;
        this.mark = mark;
        this.title = title;
        this.name = name;
    }

    public static DiscoveryToast of(CombatMark mark) {
        return new DiscoveryToast(null, mark, DigiviceScreen.upper(Component.translatable("gui.digicube.discovery.mark")),
                Component.translatable(mark.translationKey()).getString());
    }

    /** Null for a species this client does not have. */
    public static DiscoveryToast of(Identifier species) {
        return DigimonSpeciesRegistry.get(species).map(known -> new DiscoveryToast(species, null,
                DigiviceScreen.upper(Component.translatable("gui.digicube.discovery.digimon")),
                Component.translatable(known.translationKey()).getString())).orElse(null);
    }

    @Override public Visibility getWantedVisibility() { return wanted; }

    @Override public void update(ToastManager manager, long visibleFor) {
        shown = Math.max(1, (long) (SHOWN_MILLIS * manager.getNotificationDisplayTimeMultiplier()));
        wanted = visibleFor >= shown ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, Font font, long visibleFor) {
        int w = width(), h = height(), edge = mark == null ? DigiTheme.EDGE : MarkEmblems.color(mark);
        DigiPanels.frame(g, 0, 0, w, h, withAlpha(DigiTheme.PANEL, 0xF0), DigiTheme.EDGE, 3);
        DigiPanels.bevel(g, 1, 1, w - 2, h - 2, 2, withAlpha(DigiTheme.EDGE_LIGHT, 0x80), DigiTheme.SHADOW);
        DigiviceKit.slot(g, 4, 4, 24, 24, edge);
        if (mark == null) DigiPanels.icon(g, species, 5, 5, 22);
        else MarkEmblems.lit(g, mark, 5, 5, 22);
        if (mark == null) DigiviceArt.pawIcon(g, 33, 4, DigiTheme.CYAN);
        else DigiviceArt.badgeIcon(g, 33, 4, DigiTheme.CYAN);
        g.text(font, title, 45, 5, DigiTheme.CYAN, false);
        String device = DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.analyzer"));
        int room = w - 33 - 5;
        // The device's name only where the entry's own leaves room for it.
        if (font.width(name) + 8 + font.width(device) <= room) g.text(font, device, w - 5 - font.width(device), 18, withAlpha(DigiTheme.MUTED, 0x90), false);
        g.text(font, font.plainSubstrByWidth(name, room), 33, 18, DigiTheme.WHITE, true);
        float left = Math.clamp(1 - (float) visibleFor / shown, 0, 1);
        g.fill(3, h - 3, 3 + Math.round((w - 6) * left), h - 2, DigiTheme.AMBER);
    }
}
