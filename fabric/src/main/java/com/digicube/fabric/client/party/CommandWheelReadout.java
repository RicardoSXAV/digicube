package com.digicube.fabric.client.party;

import com.digicube.digimon.Progression;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;

/**
 * What the command wheel offers for a partner and which order the cursor points at. No
 * drawing and no client classes, so the rules are checked headless by
 * {@code CommandWheelRegressionTest}; the layout numbers live here for the same reason.
 * Design: {@code design/command-wheel.md}.
 */
public final class CommandWheelReadout {
    private CommandWheelReadout() {}

    public static final int MODULE_WIDTH = 118;
    public static final int MODULE_HEIGHT = 32;
    /** Horizontal space between the two columns, and the band between the rows that holds the hub. */
    public static final int COLUMN_GAP = 16;
    public static final int ROW_GAP = 64;
    public static final int HUB = 38;
    /** Cursor distance from the screen centre, in GUI units, under which nothing is selected. */
    public static final int DEAD_ZONE = 14;
    /** How far the selected module steps away from the hub. */
    public static final int STEP_OUT = 3;

    /** Sector order, which is also the module order: behaviour on top, basics below. */
    public static final int TOP_LEFT = 0, TOP_RIGHT = 1, BOTTOM_LEFT = 2, BOTTOM_RIGHT = 3, NONE = -1;

    public enum Order {
        STAND_STILL(PartyActionPayload.HOLD), FOLLOW(PartyActionPayload.FOLLOW), CANCEL_TARGET(PartyActionPayload.CANCEL_TARGET),
        RIDE(PartyActionPayload.RIDE), RECALL(PartyActionPayload.STOW), SEND_OUT(PartyActionPayload.SEND_OUT),
        DIGIVOLVE(PartyActionPayload.EVOLVE), REVERT(PartyActionPayload.REVERT);

        private final int action;
        Order(int action) { this.action = action; }
        /** The {@link PartyActionPayload} action that carries this order. */
        public int action() { return action; }
        /** Evolution orders are intents: the server checks them against the member's generation and sequence. */
        public boolean evolution() { return this == DIGIVOLVE || this == REVERT; }
    }

    /** Why a module is unavailable; {@link #NONE} on one that can be given. */
    public enum Reason { NONE, IN_DIGIVICE, REST, DEFEATED, NOT_ATTACKING, BUSY, NEEDS_LEVEL, NO_ROUTE, NEEDS_ORIGIN, COOLDOWN, SOUL }

    public record Module(Order order, boolean enabled, Reason reason) {}

    /**
     * The four modules for {@code member}, indexed by sector.
     * @param age   client ticks since the snapshot that carried {@code member}
     * @param route whether the species has a supported Champion route at this level
     */
    public static Module[] modules(PartyMemberView member, int age, boolean route) {
        return modules(member, age, route, false);
    }

    /**
     * @param rideable the wheel was opened on this partner by aiming at it, and it would carry its owner now. Ride
     *                 then takes the place of Cancel target, which has nothing to cancel on a partner at peace.
     */
    public static Module[] modules(PartyMemberView member, int age, boolean route, boolean rideable) {
        boolean alive = member.health() > 0;
        boolean field = alive && member.deployed();
        Reason away = !alive ? (member.restTicks() > 0 ? Reason.REST : Reason.DEFEATED) : Reason.IN_DIGIVICE;
        boolean steady = "RESTING".equals(member.phase()) || "EVOLVED".equals(member.phase());

        Module[] modules = new Module[4];
        modules[TOP_LEFT] = new Module(member.holding() ? Order.FOLLOW : Order.STAND_STILL, field, field ? Reason.NONE : away);
        modules[TOP_RIGHT] = rideable && field && !member.attacking() ? new Module(Order.RIDE, true, Reason.NONE)
                : new Module(Order.CANCEL_TARGET, field && member.attacking(), !field ? away : member.attacking() ? Reason.NONE : Reason.NOT_ATTACKING);
        if (member.deployed()) {
            modules[BOTTOM_LEFT] = new Module(Order.RECALL, field && steady, !alive ? away : steady ? Reason.NONE : Reason.BUSY);
        } else {
            modules[BOTTOM_LEFT] = new Module(Order.SEND_OUT, alive, alive ? Reason.NONE : away);
        }
        modules[BOTTOM_RIGHT] = evolution(member, age, route, field, away);
        return modules;
    }

    private static Module evolution(PartyMemberView member, int age, boolean route, boolean field, Reason away) {
        if (field && "EVOLVED".equals(member.phase())) return new Module(Order.REVERT, true, Reason.NONE);
        Reason reason;
        if (!field) reason = away;
        else if (!"RESTING".equals(member.phase())) reason = Reason.BUSY;
        else if (PartyHudReadout.soulLocked(member)) reason = Reason.NEEDS_LEVEL;
        else if (!route) reason = Reason.NO_ROUTE;
        else if (member.originRequired()) reason = Reason.NEEDS_ORIGIN;
        else if (PartyHudReadout.cooldown(member, age) > 0) reason = Reason.COOLDOWN;
        else if (PartyHudReadout.soul(member, age) < Progression.DIGISOUL_MINIMUM) reason = Reason.SOUL;
        else reason = Reason.NONE;
        return new Module(Order.DIGIVOLVE, reason == Reason.NONE, reason);
    }

    /**
     * The sector the cursor points at: the whole quarter of the screen counts, not just the
     * module in it. Inside the dead zone around the centre nothing is selected, so letting
     * go without moving cancels.
     * @param dx cursor offset from the screen centre, in GUI units, right positive
     * @param dy cursor offset from the screen centre, in GUI units, down positive
     */
    public static int sector(double dx, double dy) {
        if (dx * dx + dy * dy < (double) DEAD_ZONE * DEAD_ZONE) return NONE;
        return (dy < 0 ? TOP_LEFT : BOTTOM_LEFT) + (dx < 0 ? 0 : 1);
    }

    /** DigiSoul as a whole percentage, for the charging readout. */
    public static int soulPercent(int soul) {
        return Math.clamp(soul * 100 / Progression.DIGISOUL_CAPACITY, 0, 100);
    }

    /** Left edge of a module, given the screen centre. */
    public static int moduleX(int sector, int centerX) {
        return (sector & 1) == 0 ? centerX - COLUMN_GAP / 2 - MODULE_WIDTH : centerX + COLUMN_GAP / 2;
    }

    /** Top edge of a module, given the screen centre. */
    public static int moduleY(int sector, int centerY) {
        return sector < BOTTOM_LEFT ? centerY - ROW_GAP / 2 - MODULE_HEIGHT : centerY + ROW_GAP / 2;
    }
}
