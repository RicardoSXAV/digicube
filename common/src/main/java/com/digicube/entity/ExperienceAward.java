package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DamageLedger;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.Progression;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a defeated wild Digimon's damage ledger into XP for the partners on it.
 *
 * <p>Runs on the server, once per defeat, from {@link DigimonEntity#hurtServer}. A
 * contributor must still be alive, owned, in this dimension and within
 * {@link Progression#CONTRIBUTION_RANGE} blocks, and must have hit within the last
 * {@link Progression#CONTRIBUTION_MEMORY_TICKS}; everyone else drops out and the
 * remaining damage is the whole pie. The arithmetic itself is {@link Progression#split}.
 */
final class ExperienceAward {

    private ExperienceAward() {}

    static void award(ServerLevel level, DigimonEntity defeated, DamageLedger ledger) {
        DigimonSpecies species = defeated.getSpecies().orElse(null);
        if (species == null || ledger.isEmpty()) return;
        List<Progression.Contributor> contributors = new ArrayList<>();
        Map<UUID, DigimonEntity> partners = new HashMap<>();
        double rangeSqr = Progression.CONTRIBUTION_RANGE * Progression.CONTRIBUTION_RANGE;
        for (DamageLedger.Entry entry : ledger.recent(level.getGameTime(), Progression.CONTRIBUTION_MEMORY_TICKS)) {
            DigimonEntity partner = EntityReference.<DigimonEntity>of(entry.attacker()).getEntity(level, DigimonEntity.class);
            if (partner == null || !partner.isAlive() || !partner.isOwned() || partner.distanceToSqr(defeated) > rangeSqr) continue;
            contributors.add(new Progression.Contributor(partner.getUUID(), partner.getLevel(), entry.damage()));
            partners.put(partner.getUUID(), partner);
        }
        Map<UUID, Integer> shares = Progression.split(species.stage(), defeated.getLevel(), contributors);
        shares.forEach((id, share) -> partners.get(id).addExperience(share));
        if (Constants.LOG.isDebugEnabled()) {
            Constants.LOG.debug("Wild {} (level {}) defeated: yield {} split {} from ledger {}", species.id(),
                    defeated.getLevel(), Progression.xpYield(species.stage(), defeated.getLevel()), shares, ledger.entries());
        }
    }
}
