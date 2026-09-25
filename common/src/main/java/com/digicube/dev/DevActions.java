package com.digicube.dev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The developer panel's server actions, by id. Adding one is a {@link #register} call here
 * and an action on a section of the client's panel; the payloads never change. Argument keys
 * are shared constants so both sides spell them the same way.
 */
public final class DevActions {
    /** Ask for the current readout without doing anything. */
    public static final String REFRESH = "refresh";
    /** Stage a fight between two sides of wild Digimon in front of the player: {@link BattleTest#start}. */
    public static final String BATTLE_START = "battle_start";
    /** Remove the staged fighters. */
    public static final String BATTLE_CLEAR = "battle_clear";

    /** Each side of a fight: a list written by {@link BattleRoster#write}. */
    public static final String SIDE_A_ARG = "side_a";
    public static final String SIDE_B_ARG = "side_b";

    private static final Map<String, DevAction> ACTIONS = new LinkedHashMap<>();

    static {
        register(REFRESH, (server, player, args) -> "");
        register(BATTLE_START, BattleTest::start);
        register(BATTLE_CLEAR, BattleTest::clear);
    }

    private DevActions() {}

    public static void register(String id, DevAction action) {
        ACTIONS.put(id, action);
    }

    public static Optional<DevAction> get(String id) {
        return Optional.ofNullable(ACTIONS.get(id));
    }

    public static Set<String> ids() {
        return Collections.unmodifiableSet(ACTIONS.keySet());
    }
}
