package dev.remold.neoforge.compat;

import dev.remold.compat.rei.RemoldReiPlugin;
import me.shedaniel.rei.forge.REIPluginClient;

/** REI finds plugins on NeoForge by this annotation (on Fabric, through the "rei_client" entrypoint). */
@REIPluginClient
public class RemoldReiPluginNeoForge extends RemoldReiPlugin {
}
