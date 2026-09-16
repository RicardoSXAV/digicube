package com.digicube.entity;

import com.digicube.digimon.EvolutionState;

/** Constructor defaults required by fixtures allocated without a live server. */
public final class EntityFixtureDefaults {
    private EntityFixtureDefaults() {}

    public static <T> T initialize(T fixture) throws ReflectiveOperationException {
        if (fixture instanceof DigimonEntity) {
            var field = DigimonEntity.class.getDeclaredField("evolution");
            field.setAccessible(true);
            field.set(fixture, new EvolutionState());
        }
        return fixture;
    }
}
