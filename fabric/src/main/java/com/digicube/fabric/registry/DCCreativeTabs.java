package com.digicube.fabric.registry;

import com.digicube.Constants;
import com.digicube.registry.DCItems;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Fabric registration of DigiCube's Creative tab and vanilla category entries. */
public final class DCCreativeTabs {

    /** The shared home for all player-facing DigiCube items in Creative mode. */
    public static final CreativeModeTab MAIN = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, Constants.id("main"),
            FabricCreativeModeTab.builder()
                    .title(Component.translatable("creative_tab.digicube.main"))
                    .icon(() -> new ItemStack(DCItems.DIGIVICE))
                    .displayItems((parameters, output) -> output.accept(DCItems.DIGIVICE))
                    .build());

    private DCCreativeTabs() {}

    /** Registers the tab and category entries after the shared item registry is initialized. */
    public static void init() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(output -> output.insertAfter(Items.COMPASS, DCItems.DIGIVICE));
    }
}
