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
    /** Stage a fight between two wild Digimon in front of the player: {@link BattleTest#start}. */
    public static final String BATTLE_START = "battle_start";
    /** Remove the staged fighters. */
    public static final String BATTLE_CLEAR = "battle_clear";

    public static final String SPECIES_A_ARG = "species_a";
    public static final String SPECIES_B_ARG = "species_b";
    public static final String LEVEL_A_ARG = "level_a";
    public static final String LEVEL_B_ARG = "level_b";

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
