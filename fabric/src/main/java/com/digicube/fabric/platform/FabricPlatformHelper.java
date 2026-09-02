package com.digicube.fabric.platform;

import com.digicube.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric implementation of {@link IPlatformHelper}. Wired up via META-INF/services. */
public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
