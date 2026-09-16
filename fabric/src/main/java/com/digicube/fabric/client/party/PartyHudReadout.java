package com.digicube.fabric.client.party;

import com.digicube.digimon.Progression;
import com.digicube.party.PartyMemberView;

/**
 * What a party card says, derived from a {@link PartyMemberView} and the age of the
 * snapshot it came from. No drawing and no client classes, so the rules are checked
 * headless by {@code PartyHudRegressionTest}; the layout numbers live here for the same
 * reason. Design: {@code design/party-hud-strip.md} section 12.
 */
public final class PartyHudReadout {
    private PartyHudReadout() {}

    public static final int CARD_WIDTH = 110;
    public static final int CARD_HEIGHT = 42;
    public static final int STUB_HEIGHT = 14;
    public static final int GAP = 4;
    public static final int HEADER_HEIGHT = 12;
    public static final int SOUL_SEGMENTS = 8;
    /** Remaining DigiSoul ticks under which the evolved timer turns amber, then red. */
    public static final int SOUL_WARN_TICKS = 600;
    public static final int SOUL_CRITICAL_TICKS = 200;
    /** A local countdown never shows less than this before the next snapshot: it holds rather than flashing zero. */
    static final int HOLD_TICKS = 20;

    /** The status row, in priority order: the first that applies wins. */
    public enum Status { REST, DEFEATED, EVOLVING, REVERTING, SOUL, WAITING, COOLDOWN, READY, STAGE }

    /**
     * @param age   client ticks since the snapshot that carried {@code member}
     * @param route whether the species has a supported Champion route at this level
     */
    public static Status status(PartyMemberView member, int age, boolean route) {
        if (member.health() <= 0) return member.restTicks() > 0 ? Status.REST : Status.DEFEATED;
        switch (member.phase()) {
            case "EVOLVING": return Status.EVOLVING;
            case "REVERTING": return Status.REVERTING;
            case "EVOLVED": return Status.SOUL;
            default: break;
        }
        if (!member.deployed()) return Status.WAITING;
        if (cooldown(member, age) > 0) return Status.COOLDOWN;
        if (ready(member, age, route)) return Status.READY;
        return Status.STAGE;
    }

    /** Live DigiSoul: drains one unit per tick while evolved, otherwise the snapshot value. */
    public static int soul(PartyMemberView member, int age) {
        int soul = "EVOLVED".equals(member.phase()) ? member.soul() - age : member.soul();
        return Math.clamp(soul, 0, Progression.DIGISOUL_CAPACITY);
    }

    /** Rest still owed, ticked locally and held at one second before a snapshot is due. */
    public static int restTicks(PartyMemberView member, int age) {
        return member.restTicks() <= 0 ? 0 : Math.max(HOLD_TICKS, member.restTicks() - age);
    }

    /** Re-evolution cooldown, ticked locally and held at one tick until the server clears it. */
    public static int cooldown(PartyMemberView member, int age) {
        return member.cooldown() <= 0 ? 0 : Math.max(1, member.cooldown() - age);
    }

    /** Below the Champion level the DigiSoul well is drawn empty and dim: the resource exists but is locked. */
    public static boolean soulLocked(PartyMemberView member) {
        return member.level() < Progression.CHAMPION_LEVEL;
    }

    /** A Digivolution can be requested right now: the READY code and the amber brackets. */
    public static boolean ready(PartyMemberView member, int age, boolean route) {
        return route && !soulLocked(member) && member.health() > 0 && member.deployed()
                && "RESTING".equals(member.phase()) && cooldown(member, age) == 0 && !member.originRequired()
                && soul(member, age) >= Progression.DIGISOUL_MINIMUM;
    }

    public static float healthFraction(PartyMemberView member) {
        return member.maxHealth() <= 0 ? 0 : Math.clamp(member.health() / member.maxHealth(), 0.0F, 1.0F);
    }

    /** Progress to the next level; full at the cap. */
    public static float xpFraction(PartyMemberView member) {
        int needed = Progression.xpToNext(member.level());
        return needed <= 0 ? 1.0F : Math.clamp(member.xp() / (float) needed, 0.0F, 1.0F);
    }

    /** How many of the {@link #SOUL_SEGMENTS} are lit, fractional for the topmost one. */
    public static float soulSegments(int soul) {
        return soul / (float) Progression.DIGISOUL_CAPACITY * SOUL_SEGMENTS;
    }

    /** Segments that make up the activation minimum; the divider above them marks it. */
    public static int minimumSegments() {
        return Math.round(soulSegments(Progression.DIGISOUL_MINIMUM));
    }

    /**
     * The slot the selection lands on after moving {@code step} places (±1) through the
     * filled slots, wrapping at either end and skipping empty ones. With nothing filled
     * the selection stays where it is.
     */
    public static int nextSelection(boolean[] filled, int current, int step) {
        int count = filled.length;
        if (count == 0) return current;
        int direction = step < 0 ? -1 : 1;
        int slot = normalizeSelection(filled, current);
        for (int i = 0; i < count; i++) {
            slot = Math.floorMod(slot + direction, count);
            if (filled[slot]) return slot;
        }
        return current;
    }

    /** {@code current} if that slot is filled, otherwise the first filled slot at or after it, wrapping; {@code current} when none is filled. */
    public static int normalizeSelection(boolean[] filled, int current) {
        int count = filled.length;
        if (count == 0) return current;
        int start = Math.clamp(current, 0, count - 1);
        for (int i = 0; i < count; i++) {
            int slot = (start + i) % count;
            if (filled[slot]) return slot;
        }
        return current;
    }

    /** Height of the whole strip in units: header, cards, stubs and the gaps between them. */
    public static int stackHeight(int filled, int empty, boolean header) {
        int slots = filled + empty;
        return (header ? HEADER_HEIGHT : 0) + filled * CARD_HEIGHT + empty * STUB_HEIGHT + Math.max(0, slots - 1) * GAP;
    }
}
