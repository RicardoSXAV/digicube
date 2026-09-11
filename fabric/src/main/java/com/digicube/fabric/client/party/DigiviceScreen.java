package com.digicube.fabric.client.party;

import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyHealthPayload;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartyRoster;
import com.digicube.party.PartySnapshotPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Responsive Digivice collection: select an individual, then assign one of three slots. */
public final class DigiviceScreen extends Screen {
    private final PartyClient client;
    private PartySnapshotPayload snapshot;
    private UUID selected;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private String feedback = "";
    private int feedbackTicks;
    /** Client ticks since the last snapshot; drives the rest countdown between server updates. */
    private int sinceSnapshot;
    private final List<MemberButton> memberButtons = new ArrayList<>();

    DigiviceScreen(PartyClient client) {
        super(Component.translatable("gui.digicube.party.title"));
        this.client = client;
        this.snapshot = client.snapshot();
        this.sinceSnapshot = client.snapshotAge();
    }

    void receive(PartySnapshotPayload snapshot) {
        sinceSnapshot = 0;
        boolean changed = this.snapshot.page() != snapshot.page() || !this.snapshot.collection().equals(snapshot.collection())
                || !this.snapshot.party().equals(snapshot.party());
        this.snapshot = snapshot;
        if (!snapshot.message().isEmpty()) {
            feedback = snapshot.message();
            feedbackTicks = 80;
        }
        if (changed) rebuildWidgets();
    }

    void receiveHealth(PartySnapshotPayload snapshot, PartyHealthPayload health) {
        this.snapshot = snapshot;
        for (MemberButton button : memberButtons) {
            if (button.member == null) continue;
            PartyMemberView updated = health.update(button.member);
            if (updated != button.member) button.updateMember(updated);
        }
    }

    @Override
    protected void init() {
        memberButtons.clear();
        panelWidth = Math.min(440, width - 16);
        panelHeight = Math.min(292, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        addRenderableWidget(new ActionButton(left + panelWidth - 28, top + 10, 18, 18,
                Component.literal("×"), this::onClose));
        int cardWidth = (panelWidth - 32) / 3;
        for (int slot = 0; slot < PartyRoster.PARTY_SIZE; slot++) {
            int currentSlot = slot;
            PartyMemberView member = inSlot(slot);
            memberButtons.add(addRenderableWidget(new MemberButton(left + 10 + slot * (cardWidth + 6), top + 51,
                    cardWidth, 48, member, slot, () -> {
                        if (selected != null) {
                            client.send(new PartyActionPayload(PartyActionPayload.SELECT, selected, currentSlot));
                            selected = null;
                        } else if (member != null) selected = member.id();
                        rebuildWidgets();
                    })));
        }
        int rowHeight = Math.min(40, (panelHeight - 155) / 3);
        int columnWidth = (panelWidth - 26) / 2;
        for (int index = 0; index < snapshot.collection().size(); index++) {
            PartyMemberView member = snapshot.collection().get(index);
            int x = left + 10 + index % 2 * (columnWidth + 6);
            int y = top + 121 + index / 2 * rowHeight;
            memberButtons.add(addRenderableWidget(new MemberButton(x, y, columnWidth, rowHeight - 3, member, -1, () -> {
                selected = member.id().equals(selected) ? null : member.id();
                rebuildWidgets();
            })));
        }
        int footer = top + panelHeight - 25;
        ActionButton previous = addRenderableWidget(new ActionButton(left + 10, footer, 22, 16,
                Component.literal("<"), () -> page(-1)));
        previous.active = snapshot.page() > 0;
        ActionButton next = addRenderableWidget(new ActionButton(left + 85, footer, 22, 16,
                Component.literal(">"), () -> page(1)));
        next.active = (snapshot.page() + 1) * PartySnapshotPayload.PAGE_SIZE < snapshot.total();
        ActionButton recall = addRenderableWidget(new ActionButton(left + panelWidth - 82, footer, 72, 16,
                Component.translatable("gui.digicube.party.recall"), () -> {
                    if (selected != null) client.send(new PartyActionPayload(PartyActionPayload.SELECT, selected, -1));
                    selected = null;
                    rebuildWidgets();
                }));
        recall.active = selectedMember() != null && selectedMember().slot() >= 0;
        recall.setTooltip(Tooltip.create(Component.translatable("gui.digicube.party.recall_hint")));
    }

    private void page(int delta) {
        int next = Math.clamp(snapshot.page() + delta, 0, Math.max(0, (snapshot.total() - 1) / PartySnapshotPayload.PAGE_SIZE));
        if (next != snapshot.page()) {
            selected = null;
            client.send(new PartyActionPayload(PartyActionPayload.PAGE, PartyActionPayload.NO_MEMBER, next));
        }
    }

    private PartyMemberView inSlot(int slot) {
        return snapshot.party().stream().filter(member -> member.slot() == slot).findFirst().orElse(null);
    }

    private PartyMemberView selectedMember() {
        return java.util.stream.Stream.concat(snapshot.party().stream(), snapshot.collection().stream())
                .filter(member -> member.id().equals(selected)).findFirst().orElse(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xA508111C);
        graphics.fill(left + 3, top + 3, left + panelWidth + 3, top + panelHeight + 3, 0x77000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PartyGraphics.INK);
        graphics.outline(left, top, panelWidth, panelHeight, PartyGraphics.EDGE);
        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 34, PartyGraphics.PANEL);
        graphics.fill(left + 1, top + 1, left + 4, top + 33, PartyGraphics.ORANGE);
        graphics.text(font, title, left + 12, top + 9, PartyGraphics.WHITE, false);
        graphics.text(font, Component.translatable("gui.digicube.party.subtitle"), left + 12, top + 21, PartyGraphics.MUTED, false);
        graphics.text(font, Component.translatable("gui.digicube.party.team", snapshot.party().size()),
                left + 10, top + 39, PartyGraphics.TEAL, false);
        Component help = selected == null ? Component.translatable("gui.digicube.party.select_hint")
                : Component.translatable("gui.digicube.party.assign_hint");
        graphics.text(font, PartyGraphics.shortText(font, help, panelWidth - 110), left + 105, top + 39, PartyGraphics.MUTED, false);
        Component collectionLabel = feedbackTicks > 0 ? Component.translatable(feedback)
                : Component.translatable("gui.digicube.party.collection", snapshot.total());
        graphics.text(font, PartyGraphics.shortText(font, collectionLabel, panelWidth - 20), left + 10, top + 108,
                feedbackTicks > 0 ? PartyGraphics.ORANGE : PartyGraphics.MUTED, false);
        if (snapshot.total() == 0) {
            graphics.centeredText(font, Component.translatable("gui.digicube.party.empty"), left + panelWidth / 2, top + 148, PartyGraphics.WHITE);
            graphics.centeredText(font, Component.translatable("gui.digicube.party.empty_hint"), left + panelWidth / 2, top + 164, PartyGraphics.MUTED);
        }
        graphics.centeredText(font, (snapshot.page() + 1) + " / " + Math.max(1,
                (snapshot.total() + PartySnapshotPayload.PAGE_SIZE - 1) / PartySnapshotPayload.PAGE_SIZE),
                left + 58, top + panelHeight - 21, PartyGraphics.MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) page(vertical < 0 ? 1 : -1);
        return true;
    }

    @Override public void tick() {
        if (feedbackTicks > 0) feedbackTicks--;
        sinceSnapshot++;
        if (sinceSnapshot % 20 == 0) {
            // Tooltips are built once per member; a resting one needs its countdown refreshed each second.
            for (MemberButton button : memberButtons) {
                if (button.member != null && button.member.health() <= 0 && button.member.restTicks() > 0) button.updateMember(button.member);
            }
        }
        if (minecraft.player == null || !minecraft.player.isAlive()) onClose();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void removed() {
        if (minecraft.getConnection() != null) client.send(new PartyActionPayload(PartyActionPayload.CLOSE, PartyActionPayload.NO_MEMBER, 0));
        super.removed();
    }

    private class ActionButton extends AbstractButton {
        private final Runnable action;

        ActionButton(int x, int y, int width, int height, Component label, Runnable action) {
            super(x, y, width, height, label);
            this.action = action;
        }

        @Override public void onPress(InputWithModifiers input) { action.run(); }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    isHoveredOrFocused() && active ? PartyGraphics.CARD : PartyGraphics.PANEL);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), isHoveredOrFocused() && active ? PartyGraphics.ORANGE : PartyGraphics.EDGE);
            graphics.centeredText(font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                    active ? PartyGraphics.WHITE : PartyGraphics.MUTED);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class MemberButton extends ActionButton {
        private PartyMemberView member;
        private final int partySlot;

        MemberButton(int x, int y, int width, int height, PartyMemberView member, int partySlot, Runnable action) {
            super(x, y, width, height, member == null ? Component.translatable("gui.digicube.party.slot", partySlot + 1)
                    : PartyGraphics.name(member), action);
            this.partySlot = partySlot;
            updateMember(member);
        }

        void updateMember(PartyMemberView member) {
            this.member = member;
            if (member != null) {
                Component progress = Progression.isMaxLevel(member.level())
                        ? Component.translatable("gui.digicube.party.xp_max")
                        : Component.translatable("gui.digicube.party.xp", member.xp(), Progression.xpToNext(member.level()));
                var detail = Component.empty().append(PartyGraphics.name(member)).append("\n")
                        .append(PartyGraphics.status(member, sinceSnapshot)).append(" · ")
                        .append(Component.translatable("gui.digicube.party.hp", (int) Math.ceil(member.health()), (int) Math.ceil(member.maxHealth())))
                        .append("\n").append(Component.translatable("gui.digicube.party.level_long", member.level()))
                        .append(" · ").append(progress);
                DigimonSpeciesRegistry.get(member.species()).ifPresent(species -> detail.append("\n").append(
                        Component.translatable("digicube.stage." + species.stage().getId())));
                setTooltip(Tooltip.create(detail));
                active = member.health() > 0;
            }
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            boolean chosen = member != null && member.id().equals(selected);
            int edge = chosen ? PartyGraphics.ORANGE : isHoveredOrFocused() ? PartyGraphics.TEAL : PartyGraphics.EDGE;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), chosen ? 0xFF3D3831 : PartyGraphics.CARD);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), edge);
            if (member == null) {
                graphics.centeredText(font, "+", getX() + getWidth() / 2, getY() + 10, PartyGraphics.MUTED);
                graphics.centeredText(font, Component.translatable("gui.digicube.party.slot", partySlot + 1),
                        getX() + getWidth() / 2, getY() + 28, PartyGraphics.MUTED);
                return;
            }
            int iconSize = Math.min(32, getHeight() - 2);
            int iconY = getY() + (getHeight() - iconSize) / 2 - (partySlot >= 0 ? 3 : 0);
            PartyGraphics.icon(graphics, member, getX() + 3, iconY, iconSize);
            int textX = getX() + iconSize + 5;
            int textY = getY() + (getHeight() - 18) / 2 - (partySlot >= 0 ? 2 : 0);
            String level = Component.translatable("gui.digicube.party.level", member.level()).getString();
            int levelWidth = font.width(level);
            graphics.text(font, PartyGraphics.shortText(font, PartyGraphics.name(member), getWidth() - iconSize - 14 - levelWidth),
                    textX, textY, active ? PartyGraphics.WHITE : PartyGraphics.MUTED, false);
            graphics.text(font, level, getX() + getWidth() - levelWidth - 5, textY,
                    member.slot() >= 0 ? PartyGraphics.TEAL : PartyGraphics.MUTED, false);
            Component status = partySlot >= 0 ? Component.translatable("gui.digicube.party.slot", partySlot + 1) : PartyGraphics.status(member, sinceSnapshot);
            graphics.text(font, PartyGraphics.shortText(font, status, getWidth() - iconSize - 10), textX, textY + 10,
                    member.slot() >= 0 ? PartyGraphics.TEAL : PartyGraphics.MUTED, false);
            if (partySlot >= 0) PartyGraphics.health(graphics, member, getX() + 6, getY() + getHeight() - 7, getWidth() - 12);
        }
    }
}
