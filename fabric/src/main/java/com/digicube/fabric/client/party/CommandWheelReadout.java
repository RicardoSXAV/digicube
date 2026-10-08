package com.digicube.fabric.client.party;

import com.digicube.digimon.Progression;
import com.digicube.entity.DigimonEntity;
import com.digicube.party.PartyActionPayload;
import com.digicube.party.PartyMemberView;

/**
 * What the command wheel offers for a partner, what the cursor points at and where everything sits. No
 * drawing and no client classes, so the rules are checked headless by {@code CommandWheelRegressionTest}.
 *
 * <p>Coordinates are GUI units from the wheel's centre: the partner panel is centred on it, the four order keys
 * stand beside the panel in the four quarters, the Digivice key under it. The centre is the screen's, moved right
 * when the party strip would otherwise sit under the left keys ({@link #centerX}).
 */
public final class CommandWheelReadout {
    private CommandWheelReadout() {}

    /** The partner panel: slot tabs, hub, name, attack tiles with their AUTO switches, caption line. */
    public static final int PANEL_WIDTH = 116, PANEL_HEIGHT = 120;
    public static final int PANEL_LEFT = -PANEL_WIDTH / 2, PANEL_TOP = -PANEL_HEIGHT / 2;
    /** Rows inside the panel, from its top edge. */
    public static final int TABS_Y = 4, HUB_Y = 15, NAME_Y = 58, TILES_Y = 70, SWITCHES_Y = 97, CAPTION_Y = 110;
    public static final int HUB = 40;
    public static final int TAB_WIDTH = 12, TAB_HEIGHT = 8, TAB_PITCH = 14;
    public static final int TILE = 24, TILE_GAP = 6, SWITCH_HEIGHT = 10;
    /** The order keys, a column on each side of the panel: their top edges, and how far the pointed one steps out. */
    public static final int KEY_WIDTH = 68, KEY_HEIGHT = 48, KEY_GAP = 8, TOP_ROW = -54, BOTTOM_ROW = 6, STEP_OUT = 2;
    /** The Digivice key under the panel: always there, and all the wheel offers while the party is empty. */
    public static final int DIGIVICE_WIDTH = 96, DIGIVICE_HEIGHT = 20, DIGIVICE_Y = 68, DIGIVICE_REACH = 4;
    /** Half the wheel's width: a key, its gap and half the panel. */
    public static final int HALF_WIDTH = KEY_WIDTH + KEY_GAP + PANEL_WIDTH / 2;
    /** Units kept free between the party strip's right edge and the left keys, and from the screen's right edge. */
    public static final int STRIP_CLEAR = 2, EDGE_CLEAR = 2;

    /** Key order, which is also the quarter order: behaviour on top, basics below. */
    public static final int TOP_LEFT = 0, TOP_RIGHT = 1, BOTTOM_LEFT = 2, BOTTOM_RIGHT = 3, NONE = -1;
    /** The keys that cast attack slot 0, 1, ... */
    public static final String[] ATTACK_KEYS = {"Q", "E"};

    public enum Order {
        STAND_STILL(PartyActionPayload.HOLD), FOLLOW(PartyActionPayload.FOLLOW), CANCEL_TARGET(PartyActionPayload.CANCEL_TARGET),
        RIDE(PartyActionPayload.RIDE), RECALL(PartyActionPayload.RECALL),
        DIGIVOLVE(PartyActionPayload.EVOLVE), REVERT(PartyActionPayload.REVERT);

        private final int action;
        Order(int action) { this.action = action; }
        /** The {@link PartyActionPayload} action that carries this order. */
        public int action() { return action; }
        /** Evolution orders are intents: the server checks them against the member's generation and sequence. */
        public boolean evolution() { return this == DIGIVOLVE || this == REVERT; }
    }

    /** Why a key is unavailable; {@link #NONE} on one that can be given. */
    public enum Reason { NONE, NO_SPACE, REST, DEFEATED, NOT_ATTACKING, BUSY, NEEDS_LEVEL, NO_ROUTE, NEEDS_ORIGIN, COOLDOWN, SOUL }

    public record Module(Order order, boolean enabled, Reason reason) {}

    /** What the cursor can point at. */
    public enum Target { NOTHING, KEY, TILE, SWITCH, DIGIVICE }

    /** The thing under the cursor: a key by quarter, a tile or a switch by attack slot. */
    public record Pick(Target target, int index) {
        public static final Pick NOTHING = new Pick(Target.NOTHING, NONE);
        public boolean is(Target target, int index) { return this.target == target && this.index == index; }
    }

    /** Why an attack order is not sent; {@link #NONE} when it goes to the server, which checks it again. */
    public enum Refusal { NONE, NO_TARGET, COOLING, AWAY, CHARGING }

    /**
     * The four keys for {@code member}, indexed by quarter.
     * @param age   client ticks since the snapshot that carried {@code member}
     * @param route whether the species has a supported growth or Champion route at this level
     */
    public static Module[] modules(PartyMemberView member, int age, boolean route) {
        return modules(member, age, route, false);
    }

    /**
     * @param rideable the wheel was opened on this partner by aiming at it, and it would carry its owner now. Ride
     *                 then takes the place of Call off, which has nothing to call off on a partner at peace.
     */
    public static Module[] modules(PartyMemberView member, int age, boolean route, boolean rideable) {
        boolean alive = member.health() > 0;
        boolean field = alive && member.deployed();
        Reason away = !alive ? (member.restTicks() > 0 ? Reason.REST : Reason.DEFEATED) : Reason.NO_SPACE;
        boolean steady = "RESTING".equals(member.phase()) || "EVOLVED".equals(member.phase());

        Module[] modules = new Module[4];
        modules[TOP_LEFT] = new Module(member.holding() ? Order.FOLLOW : Order.STAND_STILL, field, field ? Reason.NONE : away);
        modules[TOP_RIGHT] = rideable && field && !member.attacking() ? new Module(Order.RIDE, true, Reason.NONE)
                : new Module(Order.CANCEL_TARGET, field && member.attacking(), !field ? away : member.attacking() ? Reason.NONE : Reason.NOT_ATTACKING);
        // Recall takes the partner out of the party and into the Digivice; it comes back out from the Digispace.
        modules[BOTTOM_LEFT] = new Module(Order.RECALL, field && steady, !field ? away : steady ? Reason.NONE : Reason.BUSY);
        modules[BOTTOM_RIGHT] = evolution(member, age, route, field, away);
        return modules;
    }

    private static Module evolution(PartyMemberView member, int age, boolean route, boolean field, Reason away) {
        // A Champion brought out in creative with no Rookie behind it has nothing to return to.
        if (field && "EVOLVED".equals(member.phase()))
            return new Module(Order.REVERT, !member.origin().isEmpty(), member.origin().isEmpty() ? Reason.NEEDS_ORIGIN : Reason.NONE);
        Reason reason;
        // A Baby II's growth needs its level and a route, never DigiSoul.
        boolean growth = PartyHudReadout.grows(member);
        if (!field) reason = away;
        else if (!"RESTING".equals(member.phase())) reason = Reason.BUSY;
        else if (growth ? member.level() < Progression.GROWTH_LEVEL : PartyHudReadout.soulLocked(member)) reason = Reason.NEEDS_LEVEL;
        else if (!route) reason = Reason.NO_ROUTE;
        else if (member.originRequired()) reason = Reason.NEEDS_ORIGIN;
        else if (!growth && PartyHudReadout.cooldown(member, age) > 0) reason = Reason.COOLDOWN;
        else if (!growth && PartyHudReadout.soul(member, age) < Progression.DIGISOUL_MINIMUM) reason = Reason.SOUL;
        else reason = Reason.NONE;
        return new Module(Order.DIGIVOLVE, reason == Reason.NONE, reason);
    }

    /**
     * Whether an attack order for {@code member} goes to the server. It needs the partner out, a target (its own, or
     * {@code sighted}: an enemy on the crosshair when the wheel opened) and the move ready within
     * {@link DigimonEntity#ORDER_GRACE_TICKS}; {@code readyIn} is negative when the client cannot tell.
     */
    public static Refusal attackRefusal(PartyMemberView member, boolean sighted, int readyIn) {
        return attackRefusal(member, sighted, readyIn, -1);
    }

    /**
     * As {@link #attackRefusal(PartyMemberView, boolean, int)}, for a move behind a gauge: {@code charging} is the share of
     * it filled (0 to 1), or negative for a move without one or when the client cannot tell. A gauge not yet full refuses
     * the order ({@link Refusal#CHARGING}, read out with its {@link #percent}).
     */
    public static Refusal attackRefusal(PartyMemberView member, boolean sighted, int readyIn, float charging) {
        if (!member.deployed() || member.health() <= 0) return Refusal.AWAY;
        if (!member.attacking() && !sighted) return Refusal.NO_TARGET;
        if (charging >= 0 && charging < 1) return Refusal.CHARGING;
        if (readyIn > DigimonEntity.ORDER_GRACE_TICKS) return Refusal.COOLING;
        return Refusal.NONE;
    }

    /** A gauge's share as a whole percentage for the charging readout, never 100 before it is full. */
    public static int percent(float share) {
        return share >= 1 ? 100 : Math.clamp((int) Math.floor(share * 100), 0, 99);
    }

    /**
     * What the cursor points at, {@code dx}, {@code dy} from the wheel's centre. The Digivice key wins over its quarter;
     * inside the panel only the {@code tiles} attack tiles and, unless the partner is ridden, their switches are
     * targets, and the rest of it points at nothing; outside it the whole quarter of the screen is its key.
     */
    public static Pick pick(double dx, double dy, int tiles, boolean switches) {
        if (overDigivice(dx, dy)) return new Pick(Target.DIGIVICE, 0);
        for (int slot = 0; slot < tiles; slot++) {
            if (inside(dx, dy, tileX(slot, tiles), PANEL_TOP + TILES_Y, TILE, TILE)) return new Pick(Target.TILE, slot);
            if (switches && inside(dx, dy, tileX(slot, tiles), PANEL_TOP + SWITCHES_Y, TILE, SWITCH_HEIGHT)) return new Pick(Target.SWITCH, slot);
        }
        if (inside(dx, dy, PANEL_LEFT, PANEL_TOP, PANEL_WIDTH, PANEL_HEIGHT)) return Pick.NOTHING;
        return new Pick(Target.KEY, (dy < 0 ? TOP_LEFT : BOTTOM_LEFT) + (dx < 0 ? 0 : 1));
    }

    /** Whether the cursor is on the Digivice key, with a little reach around it. */
    public static boolean overDigivice(double dx, double dy) {
        return Math.abs(dx) <= DIGIVICE_WIDTH / 2.0 + DIGIVICE_REACH && dy >= DIGIVICE_Y - DIGIVICE_REACH
                && dy <= DIGIVICE_Y + DIGIVICE_HEIGHT + DIGIVICE_REACH;
    }

    private static boolean inside(double dx, double dy, int x, int y, int width, int height) {
        return dx >= x && dx < x + width && dy >= y && dy < y + height;
    }

    /** Left edge of the attack tile in {@code slot} of a centred row of {@code tiles}; its switch sits under it. */
    public static int tileX(int slot, int tiles) {
        int row = tiles * TILE + Math.max(0, tiles - 1) * TILE_GAP;
        return -row / 2 + slot * (TILE + TILE_GAP);
    }

    /** Left edge of a key. */
    public static int keyX(int key) {
        return (key & 1) == 0 ? PANEL_LEFT - KEY_GAP - KEY_WIDTH : -PANEL_LEFT + KEY_GAP;
    }

    /** Top edge of a key. */
    public static int keyY(int key) {
        return key < BOTTOM_LEFT ? TOP_ROW : BOTTOM_ROW;
    }

    /**
     * The wheel's centre column on a screen {@code screenWidth} wide: the middle, moved right as far as the party strip
     * (ending at {@code stripRight}) needs to stay clear of the left keys, but never so far the right keys leave the screen.
     */
    public static int centerX(int screenWidth, int stripRight) {
        int middle = screenWidth / 2;
        int clear = stripRight + STRIP_CLEAR + HALF_WIDTH + STEP_OUT;
        int furthest = Math.max(middle, screenWidth - EDGE_CLEAR - HALF_WIDTH - STEP_OUT);
        return Math.min(Math.max(middle, clear), furthest);
    }

    /** DigiSoul as a whole percentage, for the charging readout. */
    public static int soulPercent(int soul) {
        return Math.clamp(soul * 100 / Progression.DIGISOUL_CAPACITY, 0, 100);
    }
}
