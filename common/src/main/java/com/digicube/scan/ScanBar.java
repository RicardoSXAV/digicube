package com.digicube.scan;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

/**
 * One family's bar as the SCAN page shows it.
 * @param family      the family's first form ({@link com.digicube.digimon.DigimonFamilies})
 * @param data        data in the bar, 0 to {@link com.digicube.digimon.Progression#SCAN_CAPACITY}
 * @param seen        whether the tamer has sighted the family
 * @param defeatsLeft defeats to the next Digitama at the recent pace: 0 when ready, -1 with no data lately
 */
public record ScanBar(Identifier family, int data, boolean seen, int defeatsLeft) {
    public void write(FriendlyByteBuf buffer) {
        buffer.writeIdentifier(family);
        buffer.writeVarInt(data);
        buffer.writeBoolean(seen);
        buffer.writeVarInt(defeatsLeft);
    }

    public static ScanBar read(FriendlyByteBuf buffer) {
        return new ScanBar(buffer.readIdentifier(), buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt());
    }
}
