package com.digicube.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Whom a wild Digimon fights once a tamer's side has hurt it: that tamer's partners first. A blow from the tamer's own
 * hand sends it at the nearest partner of theirs out in the world, not at the tamer, and when the partner it fights falls
 * it turns to the next one. The tamer is attacked only after striking it with their own hand, and only while no partner
 * of theirs is left to fight. The grudge lapses when nobody of that side is in reach, or {@link #MEMORY_TICKS} after the
 * side's last blow. Server only and never saved: a reload calms the wild Digimon down.
 */
final class WildGrudge {

    /** A grudge outlives its side's last blow by this long: thirty seconds. */
    static final int MEMORY_TICKS = 600;
    /** How often a wild Digimon with a grudge checks its target against it. */
    static final int CHECK_TICKS = 5;

    /** The tamer whose side the grudge is against, or null for none. */
    private UUID tamer;
    /** The tamer struck with their own hand: with no partner left, the tamer is fair game. */
    private boolean struckByTamer;
    private long lastBlow;

    /** Notes a blow at game time {@code now}: from a tamer's own hand, or from one of a tamer's partners. Anyone else is ignored. */
    void hurtBy(Entity attacker, long now) {
        UUID side = null;
        boolean own = false;
        if (attacker instanceof Player player && !player.isSpectator()) {
            side = player.getUUID();
            own = true;
        } else if (attacker instanceof DigimonEntity partner && partner.isOwned()) {
            side = partner.getOwnerReference().getUUID();
        }
        if (side == null) return;
        if (!side.equals(tamer)) {
            tamer = side;
            struckByTamer = false;
        }
        struckByTamer |= own;
        lastBlow = now;
    }

    boolean active() {
        return tamer != null;
    }

    /** Whether {@code entity} is the tamer of the grudge or one of their partners. */
    boolean against(Entity entity) {
        if (tamer == null || entity == null) return false;
        if (entity instanceof DigimonEntity digimon) return digimon.isOwned() && tamer.equals(digimon.getOwnerReference().getUUID());
        return tamer.equals(entity.getUUID());
    }

    void forget() {
        tamer = null;
        struckByTamer = false;
    }

    /**
     * The target {@code wild} should hold: the partner it fights while that one can still be fought, else the nearest
     * partner of the tamer in reach, else the tamer when they struck it; null when the grudge is over. A new target is
     * only chosen within {@link #MEMORY_TICKS} of the side's last blow.
     */
    LivingEntity pick(DigimonEntity wild, ServerLevel level) {
        if (tamer == null) return null;
        double reach = wild.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (wild.getTarget() instanceof DigimonEntity current && fair(wild, current, reach)) return current;
        if (level.getGameTime() - lastBlow > MEMORY_TICKS) return null;
        DigimonEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (DigimonEntity partner : level.getEntitiesOfClass(DigimonEntity.class, wild.getBoundingBox().inflate(reach),
                partner -> fair(wild, partner, reach))) {
            double distance = wild.distanceToSqr(partner);
            if (distance < best) {
                best = distance;
                nearest = partner;
            }
        }
        if (nearest != null) return nearest;
        if (!struckByTamer) return null;
        Player player = level.getPlayerByUUID(tamer);
        return player != null && player.isAlive() && wild.canAttack(player) && wild.distanceToSqr(player) <= reach * reach ? player : null;
    }

    private boolean fair(DigimonEntity wild, DigimonEntity partner, double reach) {
        return partner != wild && partner.isAlive() && against(partner) && wild.canAttack(partner)
                && wild.distanceToSqr(partner) <= reach * reach;
    }
}
