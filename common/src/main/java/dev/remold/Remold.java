package dev.remold;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Remold {
    public static final String MOD_ID = "remold";
    public static final String NAME = "Remold";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    public static final int PERMISSION_LEVEL = 2;

    private Remold() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
