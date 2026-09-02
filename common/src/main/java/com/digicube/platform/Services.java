package com.digicube.platform;

import com.digicube.Constants;
import com.digicube.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/**
 * Loads loader-specific implementations of our service interfaces at runtime.
 *
 * <p>This is plain Java's {@link ServiceLoader}. Each loader module declares its
 * implementation in a text file under {@code src/main/resources/META-INF/services/},
 * named after the fully-qualified interface name. That is how {@code common} stays
 * free of any Fabric or NeoForge import while still reaching loader features.
 */
public final class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {}

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No implementation found for service " + clazz.getName()
                                + ". Did you add a file in META-INF/services/ in the loader module?"));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
