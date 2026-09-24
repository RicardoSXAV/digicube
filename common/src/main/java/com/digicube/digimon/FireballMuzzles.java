package com.digicube.digimon;

import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a FIREBALL move charges and leaves: the snout of the animated head, sampled from the harness export as
 * {@code attack_motion/<attack>_muzzle.json} (mouth and head points in blocks at the caster's feet, per sub-tick).
 * Kept apart from {@link DigimonAttack#motion()} on purpose: an attack with a motion also takes the motion attacks'
 * positioning, aim and wind-up rules, and a fireball keeps its own. A move without a table uses the fixed snout.
 */
public final class FireballMuzzles {
    private static final Map<Identifier, Optional<AttackMotion>> TABLES = new ConcurrentHashMap<>();

    private FireballMuzzles() {}

    public static Optional<AttackMotion> get(DigimonAttack attack) {
        return TABLES.computeIfAbsent(attack.id(), id -> {
            var muzzle = id.withPath(id.getPath() + "_muzzle");
            if (FireballMuzzles.class.getResource("/data/" + muzzle.getNamespace() + "/attack_motion/" + muzzle.getPath() + ".json") == null)
                return Optional.empty();
            return Optional.of(AttackMotion.load(muzzle));
        });
    }
}
