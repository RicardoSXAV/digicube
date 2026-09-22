package com.digicube.fabric.client.digivice;

import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.fabric.client.party.PartyClient;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyHealthPayload;
import com.digicube.party.PartySnapshotPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import static com.digicube.fabric.client.gui.DigiTheme.withAlpha;

/**
 * The Digivice. The screen is the device: a pale blue shell frames the view, its three blue keys are real controls
 * (previous tab, next tab, power), and the display between them holds the tabs. Everything is laid out on a fixed
 * {@value #WIDTH} x {@value #HEIGHT} plate of GUI units, centred, doubled on a large screen and shrunk to fit a small one.
 * <p>
 * Controls are immediate: while drawing, a tab declares what can be clicked ({@link #hit}) and scrolled
 * ({@link #wheel}); the topmost declaration under the pointer wins.
 */
public final class DigiviceScreen extends Screen {
    static final int WIDTH = 480, HEIGHT = 270;
    /** The display inside the shell, and the content area under the tab row. */
    static final int DISPLAY_X = 39, DISPLAY_Y = 27, DISPLAY_WIDTH = 398, DISPLAY_HEIGHT = 216;
    static final int CONTENT_X = DISPLAY_X + 4, CONTENT_Y = DISPLAY_Y + 21, CONTENT_WIDTH = DISPLAY_WIDTH - 8, CONTENT_HEIGHT = DISPLAY_HEIGHT - 26;
    private static final int BOOT_TICKS = 10, SWAP_TICKS = 6, KEY_TICKS = 3, EMPTY_BAYS = 3;

    private enum DeviceKey {
        PREVIOUS(20, 131, 9, "Q"), NEXT(456, 131, 9, "E"), POWER(456, 35, 7, "ESC");
        final int x, y, radius;
        final String hint;
        DeviceKey(int x, int y, int radius, String hint) { this.x = x; this.y = y; this.radius = radius; this.hint = hint; }
    }

    private record Hit(int x, int y, int w, int h, Runnable action, String press, IntConsumer wheel) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    private final PartyClient client;
    private final AnalyzerTab analyzer;
    private final DigispaceTab digispace;
    private final List<Hit> hits = new ArrayList<>();
    private PartySnapshotPayload snapshot;
    private int tab;
    private int ticks, boot, swap = SWAP_TICKS, keyTicks;
    private DeviceKey keyDown;
    private String press;
    private float partial;
    private double mouseX = -1, mouseY = -1;
    private float scale = 1;
    private int originX, originY;
    private boolean cursorHidden;

    public DigiviceScreen(PartyClient client) {
        super(Component.translatable("gui.digicube.party.title"));
        this.client = client;
        this.snapshot = client.snapshot();
        this.analyzer = new AnalyzerTab(this);
        this.digispace = new DigispaceTab(this);
    }

    // ---------- what the tabs see ----------
    PartyClient client() { return client; }
    PartySnapshotPayload snapshot() { return snapshot; }
    Font font() { return font; }
    int ticks() { return ticks; }
    /** Ticks with the fraction of the current one, for motion that should not step. */
    float time() { return ticks + partial; }
    float partial() { return partial; }
    double mouseX() { return mouseX; }
    double mouseY() { return mouseY; }
    boolean over(int x, int y, int w, int h) { return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h; }
    boolean pressed(String id) { return id.equals(press); }
    void hit(int x, int y, int w, int h, Runnable action) { hits.add(new Hit(x, y, w, h, action, null, null)); }
    /** A control that shows itself held down while the button is. */
    void hit(int x, int y, int w, int h, String press, Runnable action) { hits.add(new Hit(x, y, w, h, action, press, null)); }
    /** {@code steps}: 1 towards the player, -1 away. */
    void wheel(int x, int y, int w, int h, IntConsumer steps) { hits.add(new Hit(x, y, w, h, null, null, steps)); }
    void send(PartyActionPayload payload) { client.send(payload); }
    static String upper(Component text) { return text.getString().toUpperCase(Locale.ROOT); }

    /** Opens the Analyzer on {@code species}, e.g. from a Digimon in the Digispace. */
    void analyze(Identifier species) { analyzer.select(species); show(0); }

    /** GUI units of the real screen for a point of the plate; the 3D preview is drawn outside the plate's transform. */
    int screenX(int plateX) { return originX + Math.round(plateX * scale); }
    int screenY(int plateY) { return originY + Math.round(plateY * scale); }
    /** Runs {@code draw} with the plate's transform set aside. */
    void unscaled(GuiGraphicsExtractor g, Runnable draw) {
        g.pose().pushMatrix();
        g.pose().identity();
        draw.run();
        g.pose().popMatrix();
    }

    // ---------- snapshots ----------
    public void receive(PartySnapshotPayload snapshot) {
        this.snapshot = snapshot;
        analyzer.refresh();
        digispace.refresh(snapshot.message());
    }

    public void receiveHealth(PartySnapshotPayload snapshot, PartyHealthPayload health) {
        this.snapshot = snapshot;
    }

    // ---------- screen ----------
    @Override protected void init() {
        scale = width >= WIDTH * 2 && height >= HEIGHT * 2 ? 2 : Math.min(1, Math.min((float) width / WIDTH, (float) height / HEIGHT));
        originX = Math.round((width - WIDTH * scale) / 2);
        originY = Math.round((height - HEIGHT * scale) / 2);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void tick() {
        ticks++; boot++; swap++;
        if (keyTicks > 0 && --keyTicks == 0) keyDown = null;
        analyzer.tick();
        digispace.tick(tab == 1);
        if (minecraft.player == null || !minecraft.player.isAlive()) onClose();
    }

    @Override public void removed() {
        hideCursor(false);
        if (minecraft.getConnection() != null) client.send(new PartyActionPayload(PartyActionPayload.CLOSE, PartyActionPayload.NO_MEMBER, 0));
        super.removed();
    }

    private void show(int index) {
        int next = Math.floorMod(index, 2);
        if (next == tab) return;
        tab = next;
        swap = 0;
        analyzer.blur();
        digispace.release();
    }

    private void pressKey(DeviceKey key) {
        keyDown = key;
        keyTicks = KEY_TICKS;
        switch (key) {
            case PREVIOUS -> show(tab - 1);
            case NEXT -> show(tab + 1);
            case POWER -> onClose();
        }
    }

    private void hideCursor(boolean hidden) {
        if (hidden == cursorHidden) return;
        cursorHidden = hidden;
        GLFW.glfwSetInputMode(minecraft.getWindow().handle(), GLFW.GLFW_CURSOR, hidden ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        partial = partialTick;
        mouseX = (mx - originX) / scale;
        mouseY = (my - originY) / scale;
        hits.clear();
        g.fill(0, 0, width, height, withAlpha(DigiTheme.VOID, 0x90));
        g.pose().pushMatrix();
        g.pose().translate(originX, originY);
        g.pose().scale(scale, scale);

        shell(g);
        for (DeviceKey key : DeviceKey.values()) key(g, key);
        backdrop(g);
        g.enableScissor(DISPLAY_X, DISPLAY_Y, DISPLAY_X + DISPLAY_WIDTH, DISPLAY_Y + DISPLAY_HEIGHT);
        tabs(g);
        if (tab == 0) analyzer.draw(g); else digispace.draw(g);
        transitions(g);
        g.disableScissor();
        g.fill(DISPLAY_X, DISPLAY_Y, DISPLAY_X + DISPLAY_WIDTH, DISPLAY_Y + 1, DigiTheme.SHADOW);
        g.fill(DISPLAY_X, DISPLAY_Y, DISPLAY_X + 1, DISPLAY_Y + DISPLAY_HEIGHT, DigiTheme.SHADOW);

        boolean glove = tab == 1 && digispace.gloveActive();
        hideCursor(glove);
        if (glove) digispace.drawGlove(g);
        g.pose().popMatrix();
    }

    /** The pale blue plastic: side lobes where the keys sit, moulded seams, a speaker grille, the antenna stub, the recess. */
    private void shell(GuiGraphicsExtractor g) {
        DigiPanels.frame(g, 1, 108, 10, 54, DigiviceArt.SHELL_MID, DigiviceArt.SHELL_LINE, 4);
        DigiPanels.frame(g, 469, 108, 10, 54, DigiviceArt.SHELL_MID, DigiviceArt.SHELL_LINE, 4);
        DigiPanels.frame(g, 5, 5, 470, 260, 0, DigiviceArt.SHELL_LINE, 13);
        DigiPanels.frame(g, 6, 6, 468, 258, DigiviceArt.SHELL, DigiviceArt.SHELL_LIGHT, 12);
        g.fill(18, 7, 462, 9, DigiviceArt.SHELL_LIGHT);
        g.fill(7, 18, 9, 252, DigiviceArt.SHELL_LIGHT);
        g.fill(18, 260, 462, 263, DigiviceArt.SHELL_DARK);
        g.fill(470, 18, 473, 252, DigiviceArt.SHELL_DARK);
        g.fill(18, 263, 462, 264, DigiviceArt.SHELL_LINE);
        for (int i = 0; i < 12; i++) {
            g.fill(461 + i, 262 - i, 462 + i, 264 - i, DigiviceArt.SHELL_DARK);
            g.fill(6 + i, 18 - i, 7 + i, 19 - i, DigiviceArt.SHELL_LIGHT);
        }
        for (int[] seam : new int[][]{{10, 70}, {10, 200}, {447, 70}, {447, 200}}) {
            g.fill(seam[0], seam[1], seam[0] + 20, seam[1] + 1, DigiviceArt.SHELL_DARK);
            g.fill(seam[0], seam[1] + 1, seam[0] + 20, seam[1] + 2, DigiviceArt.SHELL_LIGHT);
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            g.fill(12 + col * 4, 222 + row * 4, 14 + col * 4, 224 + row * 4, DigiviceArt.SHELL_DARK);
            g.fill(12 + col * 4, 224 + row * 4, 14 + col * 4, 225 + row * 4, DigiviceArt.SHELL_LIGHT);
        }
        g.fill(44, 0, 53, 7, 0xFF141A24);
        g.fill(45, 0, 47, 6, 0xFF3A4556);
        g.fill(42, 5, 55, 7, DigiviceArt.SHELL_LINE);
        DigiPanels.frame(g, DISPLAY_X - 6, DISPLAY_Y - 6, DISPLAY_WIDTH + 12, DISPLAY_HEIGHT + 12, DigiviceArt.SHELL_DARK, DigiviceArt.SHELL_LINE, 6);
        DigiPanels.frame(g, DISPLAY_X - 5, DISPLAY_Y - 5, DISPLAY_WIDTH + 10, DISPLAY_HEIGHT + 10, 0, DigiviceArt.SHELL_LIGHT, 5);
        DigiPanels.frame(g, DISPLAY_X - 4, DISPLAY_Y - 4, DISPLAY_WIDTH + 8, DISPLAY_HEIGHT + 8, DigiviceArt.RECESS, DigiviceArt.SHELL_LINE, 4);
    }

    private void key(GuiGraphicsExtractor g, DeviceKey key) {
        int r = key.radius;
        boolean down = keyDown == key, hover = Math.hypot(mouseX - key.x, mouseY - key.y) < r + 2;
        DigiviceArt.key(r, down ? DigiviceArt.KeyState.DOWN : hover ? DigiviceArt.KeyState.HOVER : DigiviceArt.KeyState.REST).draw(g, key.x - r - 2, key.y - r - 2);
        int push = down ? 1 : 0, white = withAlpha(DigiTheme.WHITE, 0xF0), cx = key.x + push, cy = key.y + push;
        switch (key) {
            case PREVIOUS -> { for (int i = 0; i < 4; i++) { g.fill(cx + 1 - i, cy - 3 + i, cx + 2 - i, cy - 2 + i, white); g.fill(cx + 1 - i, cy + 3 - i, cx + 2 - i, cy + 4 - i, white); } }
            case NEXT -> { for (int i = 0; i < 4; i++) { g.fill(cx - 2 + i, cy - 3 + i, cx - 1 + i, cy - 2 + i, white); g.fill(cx - 2 + i, cy + 3 - i, cx - 1 + i, cy + 4 - i, white); } }
            case POWER -> {
                g.fill(cx, cy - 3, cx + 1, cy + 1, white);
                g.fill(cx - 2, cy - 2, cx - 1, cy - 1, white); g.fill(cx + 2, cy - 2, cx + 3, cy - 1, white);
                g.fill(cx - 3, cy - 1, cx - 2, cy + 2, white); g.fill(cx + 3, cy - 1, cx + 4, cy + 2, white);
                g.fill(cx - 2, cy + 2, cx - 1, cy + 3, white); g.fill(cx + 2, cy + 2, cx + 3, cy + 3, white);
                g.fill(cx - 1, cy + 3, cx + 2, cy + 4, white);
            }
        }
        g.text(font, key.hint, key.x - font.width(key.hint) / 2, key.y + r + 5, withAlpha(DigiviceArt.SHELL_LINE, 0xC0), false);
        hit(key.x - r - 2, key.y - r - 2, 2 * r + 4, 2 * r + 4, () -> pressKey(key));
    }

    /** The net behind every tab: the data grid and a few squares of data drifting down it. */
    private void backdrop(GuiGraphicsExtractor g) {
        g.fill(DISPLAY_X, DISPLAY_Y, DISPLAY_X + DISPLAY_WIDTH, DISPLAY_Y + DISPLAY_HEIGHT, DigiTheme.VOID);
        DigiPanels.grid(g, DISPLAY_X, DISPLAY_Y, DISPLAY_WIDTH, DISPLAY_HEIGHT, 16, withAlpha(DigiTheme.GRID, 0x16));
        for (int i = 0; i < 26; i++) {
            double fall = 0.15 + DigispaceWorld.hash(i, 5) * 0.35;
            int x = DISPLAY_X + (int) (DigispaceWorld.hash(i, 3) * (DISPLAY_WIDTH - 3)), y = DISPLAY_Y + (int) ((DigispaceWorld.hash(i, 7) * DISPLAY_HEIGHT + time() * fall) % (DISPLAY_HEIGHT - 3));
            int size = DigispaceWorld.hash(i, 9) > 0.7 ? 3 : 2;
            int alpha = 0x18 + (int) (0x30 * (0.5 + 0.5 * Math.sin((time() + i * 20) / 18)));
            g.fill(x, y, x + size, y + size, withAlpha(DigispaceWorld.hash(i, 11) > 0.5 ? DigiTheme.DATA : DigiTheme.GRID_BRIGHT, alpha));
        }
    }

    private void tabs(GuiGraphicsExtractor g) {
        int y = DISPLAY_Y + 2, x = DISPLAY_X + 6;
        g.fill(DISPLAY_X, DISPLAY_Y + 18, DISPLAY_X + DISPLAY_WIDTH, DISPLAY_Y + 19, DigiTheme.EDGE);
        g.fill(DISPLAY_X, DISPLAY_Y + 19, DISPLAY_X + DISPLAY_WIDTH, DISPLAY_Y + 20, withAlpha(DigiTheme.CYAN, 0x30));
        String[] labels = {upper(Component.translatable("gui.digicube.digivice.analyzer")), upper(Component.translatable("gui.digicube.digivice.digispace"))};
        for (int i = 0; i < labels.length; i++) {
            int w = font.width(labels[i]) + 30, index = i;
            boolean active = tab == i;
            int color = DigiviceKit.tab(g, x, y, w, 16, active, !active && over(x, y, w, 16));
            if (i == 0) DigiviceArt.analyzerIcon(g, x + 6, y + (active ? 4 : 5), active ? DigiTheme.CYAN : color);
            else DigiviceArt.digispaceIcon(g, x + 6, y + (active ? 4 : 5), active ? DigiTheme.CYAN : color);
            g.text(font, labels[i], x + 21, y + (active ? 5 : 6), color, false);
            hit(x, y, w, 16, () -> show(index));
            x += w + 3;
        }
        for (int i = 0; i < EMPTY_BAYS; i++) { DigiviceKit.emptyBay(g, x, y, 44, 16); x += 47; }
    }

    /** Changing tab breaks the old view into blocks; powering on fills the display from the top behind a line of light. */
    private void transitions(GuiGraphicsExtractor g) {
        float swapped = (swap + partial) / SWAP_TICKS;
        if (swapped < 1) {
            int block = 10;
            for (int y = 0; y < CONTENT_HEIGHT + 4; y += block) for (int x = 0; x < CONTENT_WIDTH + 4; x += block) {
                if (DigispaceWorld.hash(x, y) <= swapped) continue;
                double tone = DigispaceWorld.hash(y, x);
                g.fill(CONTENT_X - 2 + x, CONTENT_Y - 2 + y, CONTENT_X - 2 + x + block, CONTENT_Y - 2 + y + block, tone > 0.8 ? DigiTheme.CYAN : tone > 0.6 ? DigiTheme.DATA : DigiTheme.VOID);
            }
        }
        float booted = (boot + partial) / BOOT_TICKS;
        if (booted < 1.3F) {
            int line = DISPLAY_Y + Math.round(DISPLAY_HEIGHT * Math.min(1, booted));
            g.fill(DISPLAY_X, line, DISPLAY_X + DISPLAY_WIDTH, DISPLAY_Y + DISPLAY_HEIGHT, DigiTheme.VOID);
            for (int x = 0; x < DISPLAY_WIDTH - 8; x += 10) for (int row = 0; row < 3; row++) {
                int y = line + 3 + row * 10;
                if (y < DISPLAY_Y + DISPLAY_HEIGHT - 8) DigiviceArt.rune(g, (x / 10 + row + ticks) % 8, DISPLAY_X + 4 + x, y, withAlpha(DigiTheme.GRID_BRIGHT, 0x90 - row * 0x28));
            }
            if (booted < 1) g.fill(DISPLAY_X, line - 1, DISPLAY_X + DISPLAY_WIDTH, line + 1, DigiviceArt.LCD_LIGHT);
            else g.fill(DISPLAY_X, DISPLAY_Y, DISPLAY_X + DISPLAY_WIDTH, DISPLAY_Y + DISPLAY_HEIGHT, withAlpha(DigiviceArt.LCD_LIGHT, (int) (0xC0 * (1 - (booted - 1) / 0.3F))));
        }
    }

    // ---------- input ----------
    private Hit top(double x, double y, boolean wheel) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if ((wheel ? hit.wheel != null : hit.action != null) && hit.contains(x, y)) return hit;
        }
        return null;
    }

    private void point(double x, double y) { mouseX = (x - originX) / scale; mouseY = (y - originY) / scale; }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return false;
        point(event.x(), event.y());
        analyzer.blur();
        Hit hit = top(mouseX, mouseY, false);
        if (hit != null) { press = hit.press; hit.action.run(); }
        else if (tab == 1) digispace.mouseDown(mouseX, mouseY);
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        point(event.x(), event.y());
        if (tab == 1) digispace.mouseMove(mouseX, mouseY);
        return true;
    }

    @Override public void mouseMoved(double x, double y) {
        point(x, y);
        if (tab == 1) digispace.mouseMove(mouseX, mouseY);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        point(event.x(), event.y());
        press = null;
        if (tab == 1) digispace.mouseUp(mouseX, mouseY);
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        point(x, y);
        Hit hit = top(mouseX, mouseY, true);
        if (hit != null && vertical != 0) hit.wheel.accept(vertical > 0 ? 1 : -1);
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (tab == 0 && analyzer.keyPressed(event.key())) return true;
        if (tab == 1 && digispace.keyPressed(event.key())) return true;
        if (event.key() == InputConstants.KEY_Q) { pressKey(DeviceKey.PREVIOUS); return true; }
        if (event.key() == InputConstants.KEY_E) { pressKey(DeviceKey.NEXT); return true; }
        return super.keyPressed(event);
    }

    @Override public boolean charTyped(CharacterEvent event) {
        return tab == 0 && analyzer.charTyped(event) || super.charTyped(event);
    }
}
