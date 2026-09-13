package com.digicube.mixin;

import com.digicube.entity.DigimonEntity;
import com.digicube.entity.DigimonPart;
import com.digicube.entity.PartedLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Vanilla surfaces the Ender Dragon's sub-hitboxes to every entity query by class check; this does the
 * same for Digimon parts. Only Digimon that registered themselves are consulted, so the cost is a
 * handful of box tests per query, never a second spatial lookup.
 */
@Mixin(Level.class)
public abstract class MixinLevel implements PartedLevel {
    @Unique
    private final Set<DigimonEntity> digicube$parted = new LinkedHashSet<>();

    @Override
    public void digicube$track(DigimonEntity digimon) {
        digicube$parted.add(digimon);
    }

    @Override
    public Collection<DigimonEntity> digicube$parted() {
        digicube$parted.removeIf(Entity::isRemoved);
        return digicube$parted;
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"))
    private void digicube$addParts(Entity except, AABB box, Predicate<? super Entity> filter, CallbackInfoReturnable<List<Entity>> cir) {
        if (digicube$parted.isEmpty()) return;
        List<Entity> result = cir.getReturnValue();
        for (DigimonEntity digimon : digicube$parted()) {
            for (DigimonPart part : digimon.parts()) {
                if (part != except && part.getBoundingBox().intersects(box) && filter.test(part)) result.add(part);
            }
        }
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At("TAIL"))
    private <T extends Entity> void digicube$addTypedParts(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> filter,
                                                           List<? super T> result, int limit, CallbackInfo ci) {
        if (digicube$parted.isEmpty()) return;
        List<T> extra = new ArrayList<>();
        for (DigimonEntity digimon : digicube$parted()) {
            for (DigimonPart part : digimon.parts()) {
                T cast = test.tryCast(part);
                if (cast != null && part.getBoundingBox().intersects(box) && filter.test(cast)) extra.add(cast);
            }
        }
        for (T part : extra) {
            if (result.size() >= limit) return;
            result.add(part);
        }
    }
}
