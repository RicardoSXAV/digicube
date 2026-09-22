package com.digicube.fabric.client.party;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.party.PartyMemberView;
import net.minecraft.network.chat.Component;

/** What the party HUD and the command wheel say about a partner. */
final class PartyGraphics {
    private PartyGraphics() {}

    static Component name(PartyMemberView member) {
        if (!member.nickname().isEmpty()) return Component.literal(member.nickname());
        return DigimonSpeciesRegistry.get(member.species()).map(DigimonSpecies::translationKey)
                .map(Component::translatable).orElseGet(() -> Component.literal(member.species().getPath()));
    }

    /** Minutes and seconds left, rounded up to whole seconds. */
    static String clock(int ticks) {
        int seconds = (ticks + 19) / 20;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
