package com.digicube.registry;

import com.digicube.Constants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import java.util.function.Function;

/**
 * Every item DigiCube adds.
 *
 * <p>Registration order matters only in that {@link #init()} must run before anything
 * reads these fields. The loader modules call it from their entry point.
 *
 * <p>Adding an item is four steps -- miss one and it shows up as a purple/black cube
 * called {@code item.digicube.foo}:
 * <ol>
 *   <li>a {@code ResourceKey} + {@code Item} field here</li>
 *   <li>{@code assets/digicube/items/foo.json} (client item definition)</li>
 *   <li>{@code assets/digicube/models/item/foo.json} (the model)</li>
 *   <li>{@code assets/digicube/textures/item/foo.png} and a line in {@code lang/en_us.json}</li>
 * </ol>
 */
public final class DCItems {

    /** The tamer's core tool: scans, stores and digivolves a partner Digimon. */
    public static final ResourceKey<Item> DIGIVICE_KEY = key("digivice");
    public static final Item DIGIVICE = register(DIGIVICE_KEY, Item::new, new Item.Properties().stacksTo(1));

    private DCItems() {}

    /**
     * Touching this class triggers its static initialiser, which is what actually
     * performs registration. Called from each loader's entry point.
     */
    public static void init() {
        Constants.LOG.debug("DigiCube items registered.");
    }

    private static ResourceKey<Item> key(String path) {
        return ResourceKey.create(Registries.ITEM, Constants.id(path));
    }

    /**
     * Since 1.21.2 an Item must know its own id before construction, hence the
     * factory + {@code setId} dance rather than a plain {@code new Item(props)}.
     */
    private static Item register(ResourceKey<Item> key, Function<Item.Properties, Item> factory, Item.Properties properties) {
        Item item = factory.apply(properties.setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }
}
