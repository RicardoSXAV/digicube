package com.digicube.registry;

import com.digicube.Constants;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Every item data component DigiCube adds. Registered before {@link DCItems}, from the same entry point.
 */
public final class DCDataComponents {

    /**
     * The family a Digitama item holds, named by its first form ({@code digicube:koromon}); the item model picks the
     * family's egg by it ({@code assets/digicube/items/digitama.json}).
     */
    public static final DataComponentType<Identifier> DIGITAMA = register("digitama",
            DataComponentType.<Identifier>builder().persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC).build());

    private DCDataComponents() {}

    /** Touching this class registers its components. Called from the shared entry point. */
    public static void init() {
        Constants.LOG.debug("DigiCube data components registered.");
    }

    private static <T> DataComponentType<T> register(String path, DataComponentType<T> type) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Constants.id(path), type);
    }
}
