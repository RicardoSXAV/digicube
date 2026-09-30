package com.digicube.analyzer;

import com.digicube.Constants;
import com.digicube.digimon.CombatMark;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The tamer's record just gained an entry: the client announces it and tags it as new in the Analyzer.
 * @param mark whether {@code id} names a combat mark ({@code digicube:<mark id>}) rather than a species
 */
public record AnalyzerDiscoveryPayload(boolean mark, Identifier id) implements CustomPacketPayload {
    public static final Type<AnalyzerDiscoveryPayload> TYPE = new Type<>(Constants.id("analyzer_discovery"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AnalyzerDiscoveryPayload> STREAM_CODEC =
            StreamCodec.ofMember(AnalyzerDiscoveryPayload::write, AnalyzerDiscoveryPayload::read);

    public static AnalyzerDiscoveryPayload species(Identifier species) { return new AnalyzerDiscoveryPayload(false, species); }
    public static AnalyzerDiscoveryPayload mark(CombatMark mark) { return new AnalyzerDiscoveryPayload(true, Constants.id(mark.id())); }

    /** The mark this names, or null for a species or a mark this version does not know. */
    public CombatMark combatMark() { return mark ? CombatMark.byId(id.getPath()).orElse(null) : null; }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(mark);
        buffer.writeIdentifier(id);
    }

    private static AnalyzerDiscoveryPayload read(RegistryFriendlyByteBuf buffer) {
        return new AnalyzerDiscoveryPayload(buffer.readBoolean(), buffer.readIdentifier());
    }

    @Override public Type<AnalyzerDiscoveryPayload> type() { return TYPE; }
}
