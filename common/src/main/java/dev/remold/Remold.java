package dev.remold;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants for Remold. Everything in the {@code dev.remold} package tree outside of
 * {@code dev.remold.<loader>} is loader-agnostic and only uses vanilla Minecraft classes.
 */
public final class Remold {
    public static final String MOD_ID = "remold";
    public static final String NAME = "Remold";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    /** Permission level required to use the editor (2 = command blocks / "op"). */
    public static final int PERMISSION_LEVEL = 2;

    private Remold() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
