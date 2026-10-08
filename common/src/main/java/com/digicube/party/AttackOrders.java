package com.digicube.party;

import com.digicube.entity.DigimonEntity;
import com.digicube.entity.DigimonPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Universal control: who an attack order from the command wheel goes at. The partner's own target first; without one
 * the enemy on its owner's crosshair. Both sides ask: the server when the order arrives, the wheel to say who it would
 * go at before it is given.
 */
public final class AttackOrders {
    private AttackOrders() {}

    /** How far the owner's crosshair picks the enemy an order goes at, in blocks. */
    public static final double SIGHT_REACH = 24;

    /**
     * The enemy on {@code player}'s crosshair, in plain view within {@link #SIGHT_REACH} blocks, that {@code partner}
     * would fight; null when there is none. A long body's hit parts count as the body. Another player only counts where
     * the server lets players hurt each other, and an armour stand never does.
     */
    public static LivingEntity sighted(Player player, DigimonEntity partner) {
        Vec3 from = player.getEyePosition(), look = player.getViewVector(1), to = from.add(look.scale(SIGHT_REACH));
        var block = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) to = block.getLocation();
        var hit = ProjectileUtil.getEntityHitResult(player, from, to, player.getBoundingBox().expandTowards(look.scale(SIGHT_REACH)).inflate(1),
                entity -> !entity.isSpectator() && entity.isPickable() && DigimonPart.livingOf(entity) != partner, from.distanceToSqr(to));
        if (hit == null) return null;
        LivingEntity living = DigimonPart.livingOf(hit.getEntity());
        if (living == null || !living.isAlive() || living instanceof ArmorStand || living instanceof Player other && !player.canHarmPlayer(other)) return null;
        return partner.wantsToAttack(living, player) ? living : null;
    }
}
