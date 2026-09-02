package com.digicube;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.resources.Identifier;

/**
 * Values that every other class needs. Keep this class free of logic.
 */
public final class Constants {

    /** Must match {@code mod_id} in gradle.properties. Lowercase, no spaces. */
    public static final String MOD_ID = "digicube";

    public static final String MOD_NAME = "DigiCube";

    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {}

    /**
     * Builds a {@code digicube:<path>} identifier. Use this everywhere instead of
     * writing the namespace by hand, so a rename only has to happen in one place.
     */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
