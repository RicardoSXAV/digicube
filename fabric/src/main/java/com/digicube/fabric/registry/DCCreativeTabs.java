package com.digicube.fabric.registry;

import com.digicube.Constants;
import com.digicube.digimon.DigimonFamilies;
import com.digicube.registry.DCItems;
import com.digicube.scan.DigitamaItem;
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
                    .displayItems((parameters, output) -> {
                        output.accept(DCItems.DIGIVICE);
                        output.accept(DCItems.RECALL_CHIP);
                        // One Digitama of each family, in the order the SCAN page shows them.
                        for (var family : DigimonFamilies.all()) output.accept(DigitamaItem.of(family));
                        output.accept(DCItems.DIGIMEAT);
                    })
                    .build());

    private DCCreativeTabs() {}

    /** Registers the tab and category entries after the shared item registry is initialized. */
    public static void init() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(output -> output.insertAfter(Items.COMPASS, DCItems.DIGIVICE, DCItems.RECALL_CHIP));
        // A Digitama hatches into a Digimon: beside the spawn eggs.
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS)
                .register(output -> { for (var family : DigimonFamilies.all()) output.accept(DigitamaItem.of(family)); });
        // Digimeat eats like cooked chicken: beside it.
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FOOD_AND_DRINKS)
                .register(output -> output.insertAfter(Items.COOKED_CHICKEN, DCItems.DIGIMEAT));
    }
}
