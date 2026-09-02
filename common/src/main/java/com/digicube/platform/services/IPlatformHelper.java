package com.digicube.platform.services;

/**
 * Things every mod loader can do, but each does differently.
 *
 * <p>Common code calls these through {@link com.digicube.platform.Services#PLATFORM};
 * each loader module ships its own implementation. When you need something from
 * Fabric or NeoForge inside common code, add a method here rather than importing
 * the loader directly.
 */
public interface IPlatformHelper {

    /** Human readable name of the loader currently running, e.g. "Fabric". */
    String getPlatformName();

    /** @return true if a mod with the given id is installed. */
    boolean isModLoaded(String modId);

    /** @return true when running from a dev workspace rather than a shipped jar. */
    boolean isDevelopmentEnvironment();

    /** @return "development" or "production". */
    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
