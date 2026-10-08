package com.digicube.fabric.client.digivice;

import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.scan.ScanToastPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * What slides in at the top right when a Digitama changes: data from a defeat (a long fight adds up in one toast), the
 * first sighting of a family, data lost to a full bar, a bar ready to convert, a Digitama hatched. The family's egg is
 * drawn as it fills. A vanilla toast, so it queues with the others instead of covering them.
 */
public final class ScanToast implements Toast {
    private static final long SHOWN_MILLIS = 4000;

    private final ScanToastPayload.Kind kind;
    private final Identifier family;
    private int amount, data;
    private Visibility wanted = Visibility.SHOW;
    private long since = -1, shown = SHOWN_MILLIS, visible;

    private ScanToast(ScanToastPayload payload) {
        this.kind = payload.kind();
        this.family = payload.family();
        this.amount = payload.amount();
        this.data = payload.data();
    }

    /** Shows {@code payload}; data from a family whose data toast is still up adds to that one and keeps it up. */
    public static void show(Minecraft client, ScanToastPayload payload) {
        if (DigimonSpeciesRegistry.get(payload.family()).isEmpty()) return;
        ToastManager manager = client.gui.toastManager();
        if (payload.kind() == ScanToastPayload.Kind.DATA) {
            ScanToast open = manager.getToast(ScanToast.class, payload.family());
            if (open != null && open.kind == ScanToastPayload.Kind.DATA && open.wanted == Visibility.SHOW) {
                open.amount += payload.amount();
                open.data = payload.data();
                open.since = -1;
                return;
            }
        }
        manager.addToast(new ScanToast(payload));
    }

    @Override public Object getToken() { return kind == ScanToastPayload.Kind.DATA ? family : NO_TOKEN; }

    @Override public Visibility getWantedVisibility() { return wanted; }

    @Override public void update(ToastManager manager, long visibleFor) {
        if (since < 0) since = visibleFor;
        shown = Math.max(1, (long) (SHOWN_MILLIS * manager.getNotificationDisplayTimeMultiplier()));
        visible = visibleFor - since;
        wanted = visible >= shown ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, Font font, long visibleFor) {
        int w = width(), h = height();
        boolean amber = kind == ScanToastPayload.Kind.READY || kind == ScanToastPayload.Kind.LOST;
        float fill = Math.min(1, data / (float) Progression.DIGITAMA_DATA);
        DigiPanels.frame(g, 0, 0, w, h, withAlpha(DigiTheme.PANEL, 0xF0), amber ? DigiTheme.AMBER : DigiTheme.EDGE, 3);
        DigiPanels.bevel(g, 1, 1, w - 2, h - 2, 2, withAlpha(DigiTheme.EDGE_LIGHT, 0x80), DigiTheme.SHADOW);
        DigiviceKit.slot(g, 4, 4, 24, 24, amber ? DigiTheme.AMBER : DigiTheme.EDGE);
        float breath = 0.5F + 0.5F * (float) Math.sin(visibleFor / 1000.0 * Math.PI * 2 / 1.5);
        DigitamaArt.draw(g, family, 8, 8, 1, kind == ScanToastPayload.Kind.HATCHED ? 1 : fill,
                kind == ScanToastPayload.Kind.READY ? breath : 0, 0, 0, 0, (int) (visibleFor / 50));
        String caption = switch (kind) {
            case DATA -> Component.translatable("gui.digicube.scan.toast.data", Math.max(1, amount)).getString();
            case SIGHTED -> Component.translatable("gui.digicube.scan.toast.sighted", amount * 100 / Progression.DIGITAMA_DATA).getString();
            case LOST -> Component.translatable("gui.digicube.scan.toast.lost").getString();
            case READY -> Component.translatable("gui.digicube.scan.toast.ready").getString();
            case HATCHED -> Component.translatable("gui.digicube.scan.toast.hatched").getString();
        };
        g.text(font, DigiviceScreen.upper(Component.literal(caption)), 33, 5, amber ? DigiTheme.AMBER : DigiTheme.CYAN, false);
        String familyName = Component.translatable(DigimonSpeciesRegistry.getOrThrow(family).translationKey()).getString();
        String name = kind == ScanToastPayload.Kind.HATCHED ? familyName.toUpperCase(java.util.Locale.ROOT) + "  L" + Progression.MIN_LEVEL
                : DigiviceScreen.upper(Component.translatable("gui.digicube.scan.digitama", familyName));
        String side = kind == ScanToastPayload.Kind.HATCHED ? DigiviceScreen.upper(Component.translatable("gui.digicube.digivice.digispace"))
                : data * 100 / Progression.DIGITAMA_DATA + "%";
        int room = w - 33 - 5 - font.width(side) - 4;
        g.text(font, font.plainSubstrByWidth(name, room), 33, 18, DigiTheme.WHITE, true);
        g.text(font, side, w - 5 - font.width(side), 18, kind == ScanToastPayload.Kind.HATCHED ? withAlpha(DigiTheme.MUTED, 0x90) : DigiTheme.WHITE, false);
        if (kind == ScanToastPayload.Kind.HATCHED) {
            float left = Math.clamp(1 - (float) visible / shown, 0, 1);
            g.fill(3, h - 3, 3 + Math.round((w - 6) * left), h - 2, DigiTheme.AMBER);
        } else {
            // the bar toward the next Digitama, amber once it holds one
            g.fill(33, h - 4, w - 4, h - 3, withAlpha(DigiTheme.EDGE_DIM, 0xC0));
            g.fill(33, h - 4, 33 + Math.round((w - 37) * fill), h - 3, data >= Progression.DIGITAMA_DATA ? DigiTheme.AMBER : DigiTheme.CYAN);
        }
    }
}
