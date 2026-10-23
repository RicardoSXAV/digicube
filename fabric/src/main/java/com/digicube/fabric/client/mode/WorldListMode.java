package com.digicube.fabric.client.mode;

import com.digicube.Constants;
import com.digicube.mode.DigiCubeMode;
import com.digicube.mode.DigiCubeModeData;
import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.world.level.storage.LevelSummary;

/**
 * The world list's third line for a world in a DigiCube Mode: the mode's badge and word before vanilla's line
 * ("Tamer, Survival Mode, ..."), the mode read from the world's saved data without loading the world. A world with no
 * mode yet keeps vanilla's line, and so does a line that is a warning instead of the game mode.
 */
public final class WorldListMode {
    private static final int WHITE = 0xFFFFFF;

    private WorldListMode() {}

    /** The mode a list entry shows: {@link DigiCubeMode#UNSET} when it shows none. */
    public static DigiCubeMode of(LevelSummary summary) {
        // vanilla's line names the game mode only for a world it can open; a linked folder is never read through
        if (summary instanceof LevelSummary.SymlinkLevelSummary || summary instanceof LevelSummary.CorruptedLevelSummary
                || summary.isLocked() || summary.requiresManualConversion() || !summary.isCompatible()) {
            return DigiCubeMode.UNSET;
        }
        return DigiCubeModeData.read(Minecraft.getInstance().getLevelSource().getLevelPath(summary.getLevelId()));
    }

    /** The badge, then the mode's word before vanilla's line. */
    public static Component line(DigiCubeMode mode, Component info) {
        return Component.empty().append(badge(mode)).append(CommonComponents.SPACE)
                .append(Component.translatable("gui.digicube.mode.world_list", word(mode), info));
    }

    /** What the narrator reads in place of vanilla's line: "Tamer mode, Survival Mode, ...". */
    public static Component narration(DigiCubeMode mode, Component info) {
        return Component.translatable("gui.digicube.mode.world_list.narration", word(mode), info);
    }

    /**
     * The mode's 8 x 8 badge as an inline glyph. Glyphs take the text's colour and shadow, so it is drawn white
     * (its own colours, not dimmed by the line's grey) and without a shadow.
     */
    private static Component badge(DigiCubeMode mode) {
        return Component.object(new AtlasSprite(AtlasIds.GUI, Constants.id("mode/badge_" + mode.getSerializedName())))
                .withStyle(style -> style.withColor(WHITE).withShadowColor(0));
    }

    private static Component word(DigiCubeMode mode) {
        return Component.translatable(mode.translationKey());
    }
}
